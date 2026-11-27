package com.peerlink.app.bot

import android.content.*
import android.os.*
import com.peerlink.app.core.AppState
import com.peerlink.app.service.PeerBotService
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/** Main-process client. Native addresses and game memory never cross this boundary. */
object EngineHost {
    const val RECORD_SIZE = 0x218
    @Volatile var isReady = false
        private set
    @Volatile var lastError = ""
        private set
    @Volatile internal var startupRetryable = true
        private set
    @Volatile internal var savedDatabaseSha256: String? = null
        private set
    @Volatile private var ownerUid = 0L
    @Volatile private var service: Messenger? = null
    @Volatile private var lastRoundEnd: Pair<Int, RoomRoundEnd>? = null
    private var context: Context? = null
    @Volatile var enginePid = 0
        private set
    @Volatile private var engineStartedAt = 0L
    @Volatile private var stopRequestedAt = 0L
    private var nextFlowReportAt = 0L
    private var binding: CountDownLatch? = null
    private var bound = false
    private var foregroundStartRequested = false
    private val preparation = Any()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val sequence = AtomicInteger()
    private val replyThread = HandlerThread("bot-engine-replies").apply { start() }
    private val replies = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(message: Message) {
            val data = message.data
            if (message.what == EngineProtocol.ROUND_ENDED) {
                val pid = data.getInt("pid")
                if (pid == enginePid && pid > 0 && stopRequestedAt == 0L)
                    RoomRoundEnd.entries.firstOrNull { it.name == data.getString("end") }?.let { lastRoundEnd = pid to it }
            } else if (message.what == EngineProtocol.STATUS) {
                if (data.getInt("pid") != enginePid || stopRequestedAt != 0L) return
                val line = data.getString("status").orEmpty()
                if (line.isNotBlank()) PeerBotService.status(line)
            } else if (message.what == EngineProtocol.NATIVE_STATE) {
                data.getLongArray("state")?.let { values ->
                    AppState.appendLog("[BOT-NATIVE] state=${values.contentToString()} (frame counter at index 20)")
                    if (BotHeapRecording.observing(enginePid) && EngineSnapshot(values).matchConstructed) {
                        context?.let { BotHeapRecording.completed(it, enginePid, "pitch_loaded") }
                    }
                }
            } else if (message.what == EngineProtocol.DIAGNOSTIC) {
                val line = data.getString("line").orEmpty()
                if (line.startsWith("[BOT-FILES]")) {
                    AppState.appendLog(line)
                    return
                }
                val firstFailure = data.getBoolean("firstFailure")
                val observedAt = data.getLong("observedAtMs")
                AppState.appendLog("[BOT-DIAG] ${data.getString("line")} " +
                    "hostStopRequestedAtMs=$stopRequestedAt receivedAtMs=${SystemClock.elapsedRealtime()}")
                if (firstFailure || observedAt >= nextFlowReportAt) {
                    nextFlowReportAt = observedAt + 5000
                    PeerSlot.logFlow(if (firstFailure) "first_native_failure" else "native_monitor")
                }
                if (firstFailure) context?.let { BotMatchDiagnostics.completed(it, enginePid, "match_disconnected") }
            } else pending.remove(message.arg1)?.complete(data)
        }
    })
    private var connection: ServiceConnection? = null
    private fun newConnection(): ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            synchronized(this@EngineHost) {
                if (!bound || connection !== this) return
                service = Messenger(binder)
                binding?.countDown()
            }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected(this, "onServiceDisconnected")
        override fun onBindingDied(name: ComponentName) = disconnected(this, "onBindingDied")
        override fun onNullBinding(name: ComponentName) = disconnected(this, "onNullBinding")
    }
    @Synchronized private fun disconnected(lost: ServiceConnection, callback: String) {
        if (connection !== lost) return
        AppState.appendLog("[BOT-DIAG] ENGINE_SERVICE_LOST callback=$callback observedAtMs=${SystemClock.elapsedRealtime()} " +
            "hostStopRequestedAtMs=$stopRequestedAt; checking Android process exit record")
        PeerSlot.logFlow("engine_service_lost")
        val lostPid = enginePid
        val since = engineStartedAt
        context?.let { owner ->
            if (BotHeapRecording.observing(lostPid)) BotHeapRecording.completed(owner, lostPid, "bot_process_ended")
            BotMatchDiagnostics.completed(owner, lostPid, "bot_process_ended")
            EngineProcessExitReports.observe(owner, lostPid, since) { line ->
                AppState.appendLog("[BOT-DIAG] $line")
            }
        }
        service = null
        isReady = false
        savedDatabaseSha256 = null
        PeerSlot.engineAttached = false
        lastError = "Bot engine stopped"
        PeerBotService.status(lastError)
        releaseService()
        for ((id, future) in pending) if (pending.remove(id, future))
            future.completeExceptionally(IllegalStateException(lastError))
    }

    @Synchronized private fun releaseService() {
        service = null
        binding?.countDown()
        binding = null
        val owner = context ?: return
        val previous = connection
        connection = null
        if (bound && previous != null) {
            bound = false
            runCatching { owner.unbindService(previous) }
        }
        if (foregroundStartRequested) {
            foregroundStartRequested = false
            runCatching { owner.stopService(Intent(owner, BotEngineService::class.java)) }
        }
    }

    private fun bind(owner: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Prepare the bot on a worker thread" }
        val latch = synchronized(this) {
            val app = owner.applicationContext
            context = app
            if (service != null) return
            binding ?: CountDownLatch(1).also {
                binding = it
                val intent = Intent(app, BotEngineService::class.java)
                val next = newConnection()
                connection = next
                bound = app.bindService(intent, next, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
                if (!bound) {
                    releaseService()
                    error("Could not start the bot engine service")
                }
                foregroundStartRequested = try {
                    app.startForegroundService(intent) != null
                } catch (error: RuntimeException) {
                    AppState.appendLog("[BOT-DIAG] ENGINE_FOREGROUND_START unavailable=${error.javaClass.simpleName}")
                    false
                }
            }
        }
        check(latch.await(20, TimeUnit.SECONDS) && service != null) { "Bot engine service did not start" }
    }
    private fun request(command: Int, arguments: Bundle = Bundle(), timeout: Long = 30): Bundle {
        val endpoint = service ?: error("Bot engine is not running")
        val id = sequence.incrementAndGet()
        val response = CompletableFuture<Bundle>()
        pending[id] = response
        try {
            endpoint.send(Message.obtain(null, command).apply { arg1 = id; data = arguments; replyTo = replies })
            val result = response.get(timeout, TimeUnit.SECONDS)
            result.getString("error")?.let { error(it) }
            return result
        } finally { pending.remove(id) }
    }
    fun prepare(owner: Context, uid: Long): Boolean = synchronized(preparation) {
        try {
            val selected = GameDatabaseUpdate.selectedPack(owner)?.fingerprint?.sha256
            if (isReady) return ownerUid == uid && savedDatabaseSha256 == selected
            startupRetryable = true
            stopRequestedAt = 0
            enginePid = 0
            lastRoundEnd = null
            engineStartedAt = System.currentTimeMillis()
            bind(owner)
            val process = request(EngineProtocol.PROCESS_INFO, timeout = 10)
            enginePid = process.getInt("pid")
            AppState.appendLog("[BOT-DIAG] ENGINE_PROCESS pid=$enginePid " +
                "foreground=${process.getString("foreground")} importance=${process.getInt("importance")}")
            val result = request(EngineProtocol.PREPARE, Bundle().apply { putLong("uid", uid) }, 300)
            startupRetryable = result.getBoolean("retryable", true)
            savedDatabaseSha256 = result.getString("savedDatabaseSha256")
            isReady = result.getBoolean("ok") && savedDatabaseSha256 == selected
            if (isReady) ownerUid = uid
            else lastError = result.getString("reason") ?: if (result.getBoolean("ok"))
                "The selected eFootball data changed. Start a new bot session."
            else "The bot simulation did not start"
            isReady
        } catch (error: Exception) {
            if (error is GameDatabaseUpdate.UnsupportedArchive) startupRetryable = false
            releaseService()
            lastError = error.cause?.message ?: error.message ?: "Bot startup failed"
            PeerBotService.status(lastError)
            false
        }
    }
    fun buildPeerRecord(peerId: Long, v4Host: String, v4HostPort: Int, v4Reflexive: String,
                        v4ReflexivePort: Int, v4NatType: Long, v6Host: String, v6HostPort: Int,
                        v6Reflexive: String, v6ReflexivePort: Int, v6NatType: Long): ByteArray? =
        request(EngineProtocol.PEER_RECORD, Bundle().apply {
            putLong("peer", peerId)
            putStringArray("addresses", arrayOf(v4Host, v4Reflexive, v6Host, v6Reflexive))
            putIntArray("ports", intArrayOf(v4HostPort, v4ReflexivePort, v6HostPort, v6ReflexivePort))
            putLongArray("nat", longArrayOf(v4NatType, v6NatType))
        }).getByteArray("record")
    fun setPeers(record: ByteArray): Boolean = record.size == RECORD_SIZE &&
        request(EngineProtocol.SET_PEERS, Bundle().apply { putByteArray("record", record) }).getBoolean("ok")
    fun openSession(peerUid: Long, connectingStart: Long): Boolean = request(EngineProtocol.OPEN_SESSION,
        Bundle().apply { putLong("peer", peerUid); putLong("connectingStart", connectingStart) }).getBoolean("ok")
    internal fun beginIntroduction(peerUid: Long, connectingStart: Long): Boolean = request(EngineProtocol.BEGIN_INTRODUCTION,
        Bundle().apply { putLong("peer", peerUid); putLong("connectingStart", connectingStart) }).getBoolean("ok")
    internal fun transportIoReady(): Int = request(EngineProtocol.TRANSPORT_IO_READY).getInt("state")
    internal fun configureServerSettings(settings: MatchServerSettings): Boolean = request(EngineProtocol.SERVER_SETTINGS,
        Bundle().apply {
            putBoolean("revisionCheck", settings.revisionCheck)
            putString("protocolOptions", settings.protocolOptions)
            putString("onlineOptions", settings.onlineOptions)
            putIntArray("slowdownThresholds", settings.slowdownThresholds.toIntArray())
        }).getBoolean("ok")
    internal fun configureTurn(input: ByteArray): Boolean = request(EngineProtocol.CONFIGURE_TURN,
        Bundle().apply { putByteArray("input", input) }).getBoolean("ok")
    internal fun verifySavedDatabase(): Boolean =
        request(EngineProtocol.VERIFY_SAVED_DATABASE).getBoolean("ok")
    internal fun turnRequest(): ByteArray = requireNotNull(
        request(EngineProtocol.TURN_REQUEST).getByteArray("request")) { "The engine's transport account parameters are unavailable" }
    internal fun turnAddress(): ByteArray? = request(EngineProtocol.TURN_ADDRESS).getByteArray("address")
    internal fun peerTurnAddress(address: ByteArray): Boolean = request(EngineProtocol.PEER_TURN_ADDRESS,
        Bundle().apply { putByteArray("address", address) }).getBoolean("ok")
    fun check(): Long = if (!isReady) 0 else runCatching { request(EngineProtocol.CHECK).getLong("verdict") }.getOrDefault(0)
    fun setupMatch(peer: Long, gameId: Long, sid: String, squad: ByteArray, roomRules: ByteArray): Boolean =
        request(EngineProtocol.SETUP_MATCH, Bundle().apply {
            putLong("peer", peer); putLong("gameId", gameId); putString("sid", sid)
            putString("keyword", GateCodec.sKeyword()); putByteArray("squad", squad)
            putByteArray("roomRules", roomRules)
        }, 60).getBoolean("ok")
    fun prepareChannels(peer: Long, squad: ByteArray, homeUid: Long = ownerUid): Boolean = request(EngineProtocol.PREPARE_CHANNELS,
        Bundle().apply { putLong("peer", peer); putLong("homeUid", homeUid); putByteArray("squad", squad) }, 60).getBoolean("ok")
    fun snapshot(): EngineSnapshot = EngineSnapshot(request(EngineProtocol.SNAPSHOT).getLongArray("state") ?: LongArray(19))
    internal fun loadingDiagnostics(): String = request(EngineProtocol.LOADING_DIAGNOSTICS).getString("details").orEmpty()
    fun startInputTest(controllerAllowed: Boolean, automatic: Boolean = true): Boolean = request(EngineProtocol.START_TEST,
        Bundle().apply {
            putBoolean("controllerAllowed", controllerAllowed)
            putBoolean("automatic", automatic)
        }).getBoolean("ok")
    fun pass(): Boolean = request(EngineProtocol.PASS).getBoolean("ok")
    internal fun probeController(): Boolean = request(EngineProtocol.PROBE_CONTROLLER).getBoolean("ok")
    internal fun probeHeapRecording(pid: Int): Boolean =
        isReady && pid > 0 && pid == enginePid && request(EngineProtocol.HEAP_RECORDING_PROBE,
            Bundle().apply { putInt("pid", pid) }, timeout = 3).getBoolean("ok")
    internal fun probePreviewCallback(): Boolean = request(EngineProtocol.PROBE_PREVIEW).getBoolean("ok")
    internal fun roundEnd(pid: Int): RoomRoundEnd? = when {
        !isReady || enginePid != pid -> RoomRoundEnd.ENGINE_STOPPED
        else -> lastRoundEnd?.takeIf { it.first == pid }?.second
    }

    /** Wait for this private process to exit before binding a fresh engine in the same room. */
    internal fun finishRound() {
        val binder = service?.binder ?: return
        val exited = CountDownLatch(1)
        val death = IBinder.DeathRecipient { exited.countDown() }
        val linked = try { binder.linkToDeath(death, 0); true } catch (_: DeadObjectException) { false }
        try {
            stop("room_rematch")
            check(!linked || !binder.isBinderAlive || exited.await(15, TimeUnit.SECONDS)) {
                "The previous bot engine is still closing."
            }
        } finally {
            if (linked) runCatching { binder.unlinkToDeath(death, 0) }
        }
    }

    fun stop(reason: String = "engine_host_stop") = synchronized(preparation) {
        context?.let { owner ->
            if (BotHeapRecording.observing(enginePid)) BotHeapRecording.completed(owner, enginePid, "bot_stopped")
            BotMatchDiagnostics.completed(owner, enginePid, "bot_stopped")
        }
        stopRequestedAt = SystemClock.elapsedRealtime()
        AppState.appendLog("[BOT-DIAG] PEERLINK_STOP component=engine_host reason=$reason observedAtMs=$stopRequestedAt")
        PeerSlot.logFlow("engine_host_stop")
        runCatching { request(EngineProtocol.STOP) }
        isReady = false
        savedDatabaseSha256 = null
        ownerUid = 0
        releaseService()
    }
}

internal object EngineProtocol {
    const val PREPARE = 1
    const val PEER_RECORD = 2
    const val SET_PEERS = 3
    const val OPEN_SESSION = 4
    const val CHECK = 5
    const val SETUP_MATCH = 6
    const val SNAPSHOT = 7
    const val START_TEST = 8
    const val PASS = 9
    const val STOP = 10
    const val PROBE_CONTROLLER = 11
    const val PREPARE_CHANNELS = 12
    const val CONFIGURE_TURN = 13
    const val TURN_ADDRESS = 14
    const val PEER_TURN_ADDRESS = 15
    const val TURN_REQUEST = 16
    const val BEGIN_INTRODUCTION = 17
    const val TRANSPORT_IO_READY = 18
    const val SERVER_SETTINGS = 19
    const val LOADING_DIAGNOSTICS = 20
    const val PROBE_PREVIEW = 21
    const val PROCESS_INFO = 22
    const val HEAP_RECORDING_PROBE = 23
    const val VERIFY_SAVED_DATABASE = 24
    const val STATUS = 100
    const val NATIVE_STATE = 101
    const val DIAGNOSTIC = 102
    const val ROUND_ENDED = 103
}

data class EngineSnapshot(val values: LongArray) {
    private fun field(index: Int) = values.getOrElse(index) { 0 }
    internal val roundEnd: RoomRoundEnd? get() = when {
        field(34) == 1L -> RoomRoundEnd.RESULT
        nativeFailed -> RoomRoundEnd.DISCONNECTED
        else -> null
    }
    val sessionConnected get() = field(2) == 4L
    // The configured TURN session owns its connection verdict. The NTL
    // fallback uses the separate original SDK verdict as well.
    val transportConnected get() = sessionConnected && (field(21) == 2L || field(1) == 1L)
    val configuredTurnTransport get() = field(21) == 2L
    val connected get() = sessionConnected && field(3) == 4L && field(4) == 4L
    val metadataReady get() = field(5) == 5L && field(6) == 1L && field(7) == 0L
    val matchConstructed get() = field(8) == 17L && field(9) == 54L && field(10) == 22L
    val matchAtBreak get() = connected && field(8) == 21L && field(9) == 54L && field(10) == 22L
    val halftimeMenu get() = field(32).toInt()
    val halftimeCanConfirm get() = matchAtBreak && (halftimeMenu == 3 || halftimeMenu == 5)
    val halftimeView get() = when (halftimeMenu) {
        1 -> "match_idle"
        2 -> "halftime_menu_loading"
        3 -> "halftime_waiting_for_input"
        4 -> "halftime_finishing"
        5 -> "halftime_menu_open"
        6 -> "halftime_time_up_demo"
        else -> "unknown"
    }
    val ballOnPitch get() = field(11) == 1L
    val simulationInputReady get() = field(29) == 1L
    val canControlMatch get() = connected && matchConstructed && ballOnPitch && field(16) == 1L &&
        (field(17) == 1L || field(17) == -1L)
    val canTestPass get() = canControlMatch && simulationInputReady
    val memoryMiB get() = if (field(18) < 0) -1L else field(18) / 1024
    val matchDataHandlerPresent get() = field(19) == 1L
    val ballSpeedMmPerSecond get() = field(15)
    val commandFrame get() = field(30)
    val hostsRoom get() = field(31) == 1L
    val ballStationaryAtCentre get() = ballOnPitch && field(12) in -800L..800L &&
        field(13) in 0L..600L && field(14) in -800L..800L && ballSpeedMmPerSecond < 450L
    fun status(afterKickoff: Boolean = false): String = when {
        nativeFailed -> "Bot connection failed: $nativeFailureReason"
        afterKickoff && matchAtBreak && halftimeMenu in 1..2 -> "Halftime — waiting for the bot’s halftime menu"
        afterKickoff && matchAtBreak && halftimeMenu == 6 -> "Halftime — finishing the first-half presentation"
        afterKickoff && matchAtBreak && halftimeMenu == 4 -> "Halftime — waiting for the second half"
        afterKickoff && matchAtBreak -> "Match paused — waiting for play to resume"
        afterKickoff && !matchConstructed -> "Match play has stopped — waiting for the result"
        !sessionConnected -> "Connecting to your game"
        !connected -> "Peer link connected — opening match-data channels"
        !metadataReady -> "Connected — exchanging teams"
        !matchConstructed -> "Loading the match — continue in eFootball when ready"
        !ballOnPitch -> "Match loaded — waiting for play to resume"
        !afterKickoff && !simulationInputReady -> "Waiting for the match to be ready"
        else -> "Match in progress"
    }
}
