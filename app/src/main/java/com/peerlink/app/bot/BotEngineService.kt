package com.peerlink.app.bot

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import com.peerlink.app.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Private, bound, same-UID service. A native engine exit cannot take down the game VPN. */
class BotEngineService : Service() {
    private companion object {
        const val ENGINE_CHANNEL = "peerlink_bot_engine"
        const val ENGINE_NOTIFICATION_ID = 2011
    }

    @Volatile private var foregroundStatus = "bound"
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "bot-engine-service") }
    private val memoryWorker = Executors.newSingleThreadExecutor {
        Thread(it, "bot-memory-sampler").apply { priority = Thread.MIN_PRIORITY }
    }
    private var observer: Messenger? = null
    private var controllerAllowed = false
    private var automaticPlay = false
    private var tacticalReadFailed = false
    private var pollStarted = false
    private var pollTask: ScheduledFuture<*>? = null
    private var lastStatus = ""
    private val brain = BotBrain()
    private val controlTracker = BotControlTracker()
    private val kickoff = OpeningKickoff()
    private val celebration = GoalCelebration()
    private var waitingAfterGoal = false
    private var halftimeMenu = -1
    private var halftimeStartedAt = 0L
    private var halftimePostSeen = false
    private var halftimePresses = 0
    private var nextHalftimePress = 0L
    private var playTask: ScheduledFuture<*>? = null
    private var stick = BotStick()
    private var reportedRoundEnd: RoomRoundEnd? = null
    private var lastPublishedMatchFrame = -1L
    private var heldButton: BotAction? = null
    private var keyRelease: ScheduledFuture<*>? = null
    private val actionWindow = BotActionWindow()
    private var lastPlayFrame = 0
    private var lastPlayAt: Long? = null
    private var nextPlaySample = 0L
    private var detailedMemory = false
    @Volatile private var pss = -1L
    private var memorySample: Future<*>? = null
    private var nextMemorySample = 0L
    private val setupMemorySamples = SetupMemorySamples()
    private var priorityTid = 0
    private var originalNice: Int? = null
    private var nextSchedulerSample = 0L
    private var nextNativeSample = 0L
    private var nextNetworkSample = 0L
    private var nextMemoryReclaim = 0L
    private var nextHostStall = 0L
    @Volatile private var enteredGameplay = false
    private var diagnosticTask: ScheduledFuture<*>? = null
    private val connectionTrace = NativeConnectionTrace()
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val request = Message.obtain(message)
            worker.execute { handle(request) }
        }
    })

    override fun onCreate() {
        super.onCreate()
        EngineRuntime.report = ::report
        EngineRuntime.audit = { line -> diagnostic(line) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (foregroundStatus == "active") return START_NOT_STICKY
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(
                ENGINE_CHANNEL, "PeerLink bot engine", NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) })
            val notification = Notification.Builder(this, ENGINE_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("PeerLink bot engine")
                .setContentText("Running your local opponent")
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ENGINE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(ENGINE_NOTIFICATION_ID, notification)
            }
            foregroundStatus = "active"
        } catch (error: RuntimeException) {
            // Android can deny promotion if the user leaves before the start is
            // delivered. Release the started service and keep the existing bind.
            foregroundStatus = error.javaClass.simpleName
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onUnbind(intent: Intent?): Boolean {
        // The main PeerLink process owns the room and its VPN. If it goes
        // away, release the engine's started service as well as its binding.
        stopSelf()
        return false
    }

    private fun handle(message: Message) {
        observer = message.replyTo ?: observer
        val result = Bundle()
        try {
            val args = message.data
            when (message.what) {
                EngineProtocol.PROCESS_INFO -> {
                    val memory = ActivityManager.RunningAppProcessInfo()
                    ActivityManager.getMyMemoryState(memory)
                    result.putInt("pid", Process.myPid())
                    result.putInt("importance", memory.importance)
                    result.putString("foreground", foregroundStatus)
                }
                EngineProtocol.PREPARE -> {
                    detailedMemory = args.getBoolean("detailedMemory")
                    result.putBoolean("ok", EngineRuntime.prepare(this, args.getLong("uid")))
                    result.putInt("pid", Process.myPid())
                    result.putBoolean("retryable", EngineRuntime.startupRetryable)
                    result.putString("savedDatabaseSha256", EngineRuntime.savedDatabasePack?.fingerprint?.sha256)
                    diagnostic("ENGINE_FOREGROUND state=$foregroundStatus pid=${Process.myPid()}")
                    if (!result.getBoolean("ok")) result.putString("reason", lastStatus.ifBlank { "Bot simulation could not start" })
                    if (result.getBoolean("ok")) diagnostic(EngineRuntime.networkStartupDiagnostics())
                }
                EngineProtocol.PEER_RECORD -> {
                    val addresses = requireNotNull(args.getStringArray("addresses"))
                    val ports = requireNotNull(args.getIntArray("ports"))
                    val nat = requireNotNull(args.getLongArray("nat"))
                    require(addresses.size == 4 && ports.size == 4 && nat.size == 2)
                    result.putByteArray("record", EngineRuntime.buildPeerRecord(args.getLong("peer"),
                        addresses[0], ports[0], addresses[1], ports[1], nat[0],
                        addresses[2], ports[2], addresses[3], ports[3], nat[1]))
                }
                EngineProtocol.VERIFY_SAVED_DATABASE ->
                    result.putBoolean("ok", EngineRuntime.verifySavedDatabase())
                EngineProtocol.SET_PEERS -> result.putBoolean("ok", EngineRuntime.setPeers(requireNotNull(args.getByteArray("record"))))
                EngineProtocol.OPEN_SESSION -> result.putBoolean("ok", EngineRuntime.openSession(args.getLong("peer"), args.getLong("connectingStart")))
                EngineProtocol.BEGIN_INTRODUCTION -> result.putBoolean("ok", EngineRuntime.beginIntroduction(args.getLong("peer"), args.getLong("connectingStart")))
                EngineProtocol.TRANSPORT_IO_READY -> result.putInt("state", EngineRuntime.transportIoReady())
                EngineProtocol.CHECK -> result.putLong("verdict", EngineRuntime.check())
                EngineProtocol.CONFIGURE_TURN -> result.putBoolean("ok", EngineRuntime.configureTurn(
                    requireNotNull(args.getByteArray("input"))))
                EngineProtocol.SERVER_SETTINGS -> {
                    check(args.containsKey("revisionCheck")) { "Server revision policy is missing" }
                    val settings = MatchServerSettings(args.getBoolean("revisionCheck"),
                        requireNotNull(args.getString("protocolOptions")), requireNotNull(args.getString("onlineOptions")),
                        requireNotNull(args.getIntArray("slowdownThresholds")).toList())
                    result.putBoolean("ok", EngineRuntime.configureServerSettings(settings))
                }
                EngineProtocol.TURN_REQUEST -> result.putByteArray("request", EngineRuntime.turnRequest())
                EngineProtocol.TURN_ADDRESS -> result.putByteArray("address", EngineRuntime.turnAddress())
                EngineProtocol.PEER_TURN_ADDRESS -> result.putBoolean("ok", EngineRuntime.peerTurnAddress(
                    requireNotNull(args.getByteArray("address"))))
                EngineProtocol.PREPARE_CHANNELS -> result.putBoolean("ok", EngineRuntime.prepareChannels(
                    args.getLong("peer"), requireNotNull(args.getByteArray("squad")), args.getLong("homeUid")))
                EngineProtocol.SETUP_MATCH -> {
                    val ok = EngineRuntime.setupMatch(args.getLong("peer"), args.getLong("gameId"),
                        requireNotNull(args.getString("sid")), "MYCLUB_LOBBY_ROOM_MATCH_1VS1",
                        requireNotNull(args.getString("keyword")), requireNotNull(args.getByteArray("squad")),
                        requireNotNull(args.getByteArray("roomRules")))
                    result.putBoolean("ok", ok)
                    if (ok && !pollStarted) {
                        pollStarted = true
                        nextMemorySample = 0L
                        diagnosticTask?.cancel(false)
                        diagnosticTask = null
                        pollTask = worker.scheduleWithFixedDelay(::poll, 0, 500, TimeUnit.MILLISECONDS)
                    }
                }
                EngineProtocol.SNAPSHOT -> result.putLongArray("state", snapshot().values)
                EngineProtocol.LOADING_DIAGNOSTICS -> result.putString("details", EngineRuntime.loadingDiagnostics())
                EngineProtocol.START_TEST -> {
                    controllerAllowed = args.getBoolean("controllerAllowed")
                    automaticPlay = controllerAllowed && reportedRoundEnd == null && args.getBoolean("automatic", true)
                    tacticalReadFailed = false
                    if (!automaticPlay) stopPlaying()
                    result.putBoolean("ok", controllerAllowed)
                }
                EngineProtocol.PASS -> result.putBoolean("ok", pressPass())
                EngineProtocol.PROBE_CONTROLLER -> result.putBoolean("ok", EngineRuntime.probeController())
                EngineProtocol.HEAP_RECORDING_PROBE -> result.putBoolean("ok",
                    com.peerlink.app.BuildConfig.DEBUG && args.getInt("pid") == Process.myPid() && EngineRuntime.probeHeapRecording())
                EngineProtocol.PROBE_PREVIEW -> result.putBoolean("ok", EngineRuntime.probePreviewCallback())
                EngineProtocol.STOP -> {
                    diagnostic("PEERLINK_STOP component=engine_service reason=stop_command")
                    sampleDiagnostics()
                    diagnosticTask?.cancel(false)
                    automaticPlay = false
                    pollTask?.cancel(false)
                    stopPlaying()
                    EngineRuntime.stopPreview()
                    EngineRuntime.neutral()
                    result.putBoolean("ok", true)
                }
                else -> error("Unknown bot engine command")
            }
            if (message.what == EngineProtocol.BEGIN_INTRODUCTION || message.what == EngineProtocol.OPEN_SESSION) {
                diagnostic("NATIVE_COMMAND command=${if (message.what == EngineProtocol.OPEN_SESSION) "open_session" else "begin_introduction"} accepted=${result.getBoolean("ok")}")
                if (result.getBoolean("ok") && !pollStarted && diagnosticTask == null) startDiagnostics(250)
            }
            if (message.what == EngineProtocol.PREPARE_CHANNELS || message.what == EngineProtocol.PEER_TURN_ADDRESS || message.what == EngineProtocol.SETUP_MATCH) {
                diagnostic("NATIVE_COMMAND command=${when (message.what) {
                    EngineProtocol.PREPARE_CHANNELS -> "prepare_channels"
                    EngineProtocol.PEER_TURN_ADDRESS -> "peer_connection_record"
                    else -> "setup_match"
                }} accepted=${result.getBoolean("ok")}")
                sampleDiagnostics()
            }
        } catch (error: Exception) {
            // Never put session IDs, requests or native memory into a public log/export.
            result.putString("error", error.cause?.message ?: error.message ?: "Bot engine command failed")
        }
        runCatching {
            message.replyTo?.send(Message.obtain(null, message.what).apply { arg1 = message.arg1; data = result })
        }
        message.recycle()
    }
    private fun reportRoundEnd(end: RoomRoundEnd) {
        if (reportedRoundEnd == end || reportedRoundEnd == RoomRoundEnd.RESULT) return
        val recipient = observer ?: return
        runCatching { recipient.send(Message.obtain(null, EngineProtocol.ROUND_ENDED).apply {
            data = Bundle().apply { putInt("pid", Process.myPid()); putString("end", end.name) }
        }) }.onSuccess {
            reportedRoundEnd = end
            automaticPlay = false
            stopPlaying()
        }
    }

    private fun report(line: String) {
        lastStatus = line
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.STATUS).apply {
            data = Bundle().apply { putInt("pid", Process.myPid()); putString("status", line) }
        }) }
    }
    private fun snapshot(passive: Boolean = false): EngineSnapshot {
        val now = SystemClock.elapsedRealtime()
        val values = EngineRuntime.snapshot(passive)
        observeHostStall("snapshot", now)
        val state = EngineSnapshot(values)
        val playing = enteredGameplay || state.matchConstructed
        val tid = values.getOrNull(33) ?: 0L
        val needsMatchCpu = state.needsMatchCpu(enteredGameplay, reportedRoundEnd != null)
        setMatchThreadPriority(if (needsMatchCpu) tid.toInt() else 0)
        val sampleMemory = now >= nextMemorySample
        val sampleScheduler = needsMatchCpu && tid > 0 && now >= nextSchedulerSample
        if ((sampleMemory || sampleScheduler) && memorySample?.isDone != false) {
            val inSetup = pollStarted
            if (sampleMemory) nextMemorySample = now + if (playing) 15000 else 5000
            if (sampleScheduler) nextSchedulerSample = now + 1000
            memorySample = memoryWorker.submit {
                val started = SystemClock.elapsedRealtime()
                if (sampleMemory && inSetup && !playing && !enteredGameplay) EngineRuntime.reclaimMatchPool()
                if (sampleScheduler) {
                    val counters = runCatching {
                        File("/proc/self/task/$tid/schedstat").bufferedReader().use { it.readLine() }
                    }.getOrNull()
                    EngineRuntime.journal("BOT_GAME_THREAD tid=$tid requestedAtMs=$now sampledAtMs=$started " +
                        "frame=${state.commandFrame} schedstat=${counters?.trim()?.replace(' ', ',') ?: "unavailable"}")
                }
                if (sampleMemory) {
                    // Keep page-table walks out of normal team loading on low-RAM phones.
                    if (detailedMemory && !playing) pss = Debug.getPss()
                    // The kernel maintains these counters without walking the engine's page tables.
                    val usage = runCatching { File("/proc/self/status").useLines { lines ->
                        lines.filter { it.startsWith("VmRSS:") || it.startsWith("VmSwap:") ||
                            it.startsWith("RssAnon:") || it.startsWith("RssFile:") || it.startsWith("RssShmem:") }
                            .associate { it.substringBefore(':') to it.substringAfter(':').trim().substringBefore(' ').toLong() }
                    } }.getOrDefault(emptyMap())
                    val memory = ActivityManager.MemoryInfo()
                    getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
                    val phase = when {
                        playing -> "match_play"
                        inSetup -> "team_setup"
                        state.metadataReady -> "teams_ready"
                        state.connected -> "exchanging_teams"
                        else -> "connecting"
                    }
                    val line = "BOT_MEMORY phase=$phase availableKiB=${memory.availMem / 1024} " +
                        "thresholdKiB=${memory.threshold / 1024} lowMemory=${memory.lowMemory} " +
                        "rssKiB=${usage["VmRSS"] ?: -1} swapKiB=${usage["VmSwap"] ?: -1} " +
                        "residentAnonKiB=${usage["RssAnon"] ?: -1} residentFileKiB=${usage["RssFile"] ?: -1} " +
                        "residentSharedKiB=${usage["RssShmem"] ?: -1} androidPssKiB=${if (playing) -1 else pss} " +
                        "nativeHeapKiB=${Debug.getNativeHeapAllocatedSize() / 1024} " +
                        "javaUsedKiB=${(Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / 1024} " +
                        "sampleStartMs=$started sampleDurationMs=${SystemClock.elapsedRealtime() - started}"
                    if (playing) EngineRuntime.journal(line) else {
                        diagnostic(line)
                        if (detailedMemory) setupMemorySamples.next(state.metadataReady, inSetup, started, pss)?.let { checkpoint ->
                            val scanStart = SystemClock.elapsedRealtime()
                            val groups = EngineRuntime.setupMemoryGroups(checkpoint)
                            EngineRuntime.journal("BOT_MEMORY_GROUPS phase=$phase checkpoint=$checkpoint $groups " +
                                "sampleStartMs=$scanStart sampleDurationMs=${SystemClock.elapsedRealtime() - scanStart}")
                        }
                    }
                }
            }
        }
        values[18] = if (playing) -1 else pss
        return state
    }
    // Called on the service worker. Target only the TID observed inside this process.
    private fun setMatchThreadPriority(tid: Int) {
        if (tid == priorityTid) return
        val target = Process.THREAD_PRIORITY_FOREGROUND
        originalNice?.let { original ->
            runCatching {
                if (Process.getThreadPriority(priorityTid) == target)
                    Process.setThreadPriority(priorityTid, original)
            }
        }
        originalNice = null
        priorityTid = tid
        if (tid <= 0 || !File("/proc/self/task/$tid").isDirectory) return
        try {
            val before = Process.getThreadPriority(tid)
            if (before > target) {
                Process.setThreadPriority(tid, target)
                originalNice = before
            }
            EngineRuntime.journal("BOT_CPU_PRIORITY tid=$tid beforeNice=$before actualNice=${Process.getThreadPriority(tid)} requestedNice=$target")
        } catch (error: RuntimeException) {
            EngineRuntime.journal("BOT_CPU_PRIORITY tid=$tid requestedNice=$target result=${error.javaClass.simpleName}")
        }
    }

    private fun observeHostStall(operation: String, startedAt: Long) {
        val now = SystemClock.elapsedRealtime()
        if (enteredGameplay && now - startedAt >= 37 && now >= nextHostStall) {
            nextHostStall = now + 1000
            EngineRuntime.journal("BOT_HOST_STALL operation=$operation startedAtMs=$startedAt durationMs=${now - startedAt}")
        }
    }
    private fun diagnostic(line: String, firstFailure: Boolean = false) {
        if (firstFailure || line.startsWith("MATCH_LOADING") || line.startsWith("NATIVE_STATE") ||
            line.startsWith("PEERLINK_STOP") || line.startsWith("BOT_INPUT") || line.startsWith("BOT_MEMORY"))
            EngineRuntime.journal(line)
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.DIAGNOSTIC).apply {
            data = Bundle().apply {
                putString("line", line)
                putBoolean("firstFailure", firstFailure)
                putLong("observedAtMs", SystemClock.elapsedRealtime())
            }
        }) }
    }
    private fun startDiagnostics(intervalMs: Long) {
        diagnosticTask = worker.scheduleWithFixedDelay({ sampleDiagnostics() }, 0, intervalMs, TimeUnit.MILLISECONDS)
    }

    private fun sampleDiagnostics(published: EngineSnapshot? = null) {
        try {
            val state = published ?: snapshot(passive = true)
            state.roundEnd?.let(::reportRoundEnd)
            val now = SystemClock.elapsedRealtime()
            // Network startup can fail before SETUP_MATCH enables the loading
            // monitor. Export these passive diagnostics during introduction,
            // including a resolver still pending or a failed native startup.
            if (now >= nextNetworkSample) {
                nextNetworkSample = now + 5000
                diagnostic(EngineRuntime.networkStartupDiagnostics())
            }
            connectionTrace.sample(state, now).forEach {
                diagnostic(it.text, it.firstFailure)
                if (it.firstFailure) {
                    diagnostic(EngineRuntime.databaseDiagnostics())
                    diagnostic(EngineRuntime.loadingDiagnostics())
                }
            }
        } catch (error: Exception) {
            diagnostic("MONITOR_UNAVAILABLE error=${error.javaClass.simpleName}; native cause not observed")
            diagnosticTask?.cancel(false)
        }
    }
    private fun logNativeState(state: EngineSnapshot) {
        val now = SystemClock.elapsedRealtime()
        if (now < nextNativeSample) return
        nextNativeSample = now + 5000
        if (!enteredGameplay) diagnostic(EngineRuntime.loadingDiagnostics())
        val checkResult = state.needsResultCheck(enteredGameplay, lastPublishedMatchFrame)
        lastPublishedMatchFrame = state.commandFrame
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.NATIVE_STATE).apply {
            data = Bundle().apply {
                putLongArray("state", state.values)
                putInt("pid", Process.myPid())
                putBoolean("checkResult", checkResult)
            }
        }) }
    }
    private fun poll() {
        try {
            val startedAt = SystemClock.elapsedRealtime()
            val advance = EngineRuntime.advanceMatch(controllerAllowed)
            observeHostStall("match_poll", startedAt)
            if (advance == -214) reportRoundEnd(RoomRoundEnd.DISCONNECTED)
            check(advance >= 0) {
                val reason = when (advance) {
                    -205 -> "The bot's user-data task disappeared before completion"
                    -206 -> "The bot's user-data synchronization stopped before completion"
                    -207 -> "The bot's user-data synchronization timed out"
                    -208 -> "The bot's server match context was rejected"
                    -209 -> "The bot's match-preview menu did not finish loading"
                    -210 -> "The bot could not queue its match-preview step on the game thread"
                    -211 -> "The bot could not skip the match introduction"
                    -214 -> "The bot and eFootball produced different match states"
                    -215 -> "The bot could not apply this room's match rules"
                    -216 -> "The bot's original match-setup task is unavailable"
                    -217 -> "The bot's match-setup synchronization failed"
                    -218 -> "The bot's match-setup synchronization timed out"
                    -219 -> "The bot could not start the original platform session and match reporting tasks"
                    else -> "Bot match setup failed"
                }
                "$reason (native stage $advance)"
            }
            val state = snapshot()
            if (state.matchConstructed && state.simulationInputReady) enteredGameplay = true
            sampleDiagnostics(state)
            logNativeState(state)
            val line = state.status(afterKickoff = enteredGameplay)
            if (line != lastStatus) {
                if (enteredGameplay && state.matchAtBreak) diagnostic(EngineRuntime.loadingDiagnostics())
                report(line)
            }
            val now = SystemClock.elapsedRealtime()
            // Loading the foreground game caused a confirmed Android LMK on
            // the 4 GB phone. Return already-freed native pages during this
            // loading window; never discard live match objects or game data.
            if (advance >= 2 && !enteredGameplay && !state.matchConstructed && now >= nextMemoryReclaim) {
                nextMemoryReclaim = now + 5000
                EngineRuntime.releaseUnusedMemory("match_loading")
            }
            if (automaticPlay && enteredGameplay && state.canControlMatch) {
                if (!tacticalReadFailed && playTask == null)
                    playTask = worker.scheduleWithFixedDelay(::play, 0, 100, TimeUnit.MILLISECONDS)
                // Use the minimal native state for the opening kickoff.
                // Stop assisting once the ball moves, even if the reader
                // later loses tactical data or play returns to centre.
                if (kickoff.shouldPress(state.canTestPass, state.hostsRoom, state.commandFrame, state.ballStationaryAtCentre) &&
                    pressPass(state, manual = false)) {
                    kickoff.pressed(state.commandFrame)
                    EngineRuntime.journal("BOT_PLAY_KICKOFF frame=${state.commandFrame} action=PASS")
                }
            } else if (playTask != null) {
                stopPlaying()
            }
            if (state.matchAtBreak && enteredGameplay) {
                brain.resetDirection()
                if (halftimeMenu < 0) {
                    halftimeStartedAt = now
                    halftimePostSeen = false
                }
                if (state.halftimeMenu != halftimeMenu) {
                    halftimeMenu = state.halftimeMenu
                    halftimePresses = 0
                    nextHalftimePress = 0
                    if (halftimeMenu == 4) halftimePostSeen = true
                    EngineRuntime.journal("BOT_HALFTIME_MENU view=${state.halftimeView} frame=${state.commandFrame} " +
                        "breakElapsedMs=${now - halftimeStartedAt} postSeen=$halftimePostSeen")
                }
                if (automaticPlay && state.halftimeCanConfirm && heldButton == null && halftimePresses < 2 &&
                    now >= nextHalftimePress && EngineRuntime.halftimeConfirm()) {
                    heldButton = BotAction.PASS // Same A button. The regular release also works between halves.
                    keyRelease = worker.schedule(::releaseButton, BotAction.PASS.holdMs, TimeUnit.MILLISECONDS)
                    halftimePresses++
                    nextHalftimePress = now + 2500
                    EngineRuntime.journal("BOT_HALFTIME_CONFIRM attempt=$halftimePresses frame=${state.commandFrame}")
                }
            } else if (halftimeMenu >= 0) {
                EngineRuntime.journal("BOT_HALFTIME_EXIT frame=${state.commandFrame} playable=${state.canTestPass} " +
                    "connected=${state.connected} breakElapsedMs=${now - halftimeStartedAt}")
                halftimeMenu = -1
            }
        } catch (error: Exception) {
            automaticPlay = false
            pollTask?.cancel(false)
            stopPlaying()
            // A checksum stop can occur inside the normal five-second sample
            // interval. Export its exact comparison even if throttled state
            // reporting would otherwise skip this final observation.
            val failure = runCatching { snapshot() }.getOrNull()
            runCatching { diagnostic(EngineRuntime.loadingDiagnostics()) }
            if (failure != null) {
                sampleDiagnostics(failure)
                logNativeState(failure)
            }
            EngineRuntime.neutral()
            report(if (failure?.nativeFailed == true) failure.status() else error.message ?: "Bot match setup stopped")
            if (!worker.isShutdown && diagnosticTask == null) startDiagnostics(1000)
        }
    }
    private fun play() {
        try {
            val now = SystemClock.elapsedRealtime()
            val state = EngineRuntime.playState()
            observeHostStall("play_state", now)
            if (state == null || state.frame == lastPlayFrame) {
                if (lastPlayAt?.let { now - it >= 250 } != false) setStick(BotStick())
                if (now >= nextPlaySample) {
                    nextPlaySample = now + 3000
                    EngineRuntime.journal("BOT_PLAY_WAIT state=${if (state == null) "unavailable" else "no_new_frame"}")
                }
                return
            }
            lastPlayFrame = state.frame
            lastPlayAt = now
            val afterGoal = celebration.observingGoal(state)
            if (afterGoal != waitingAfterGoal) {
                waitingAfterGoal = afterGoal
                EngineRuntime.journal("BOT_GOAL_INPUT frame=${state.frame} paused=$afterGoal ballX=${state.ball.x} ballZ=${state.ball.z}")
            }
            if (afterGoal) {
                if (heldButton != null) releaseButton()
                setStick(BotStick())
                return
            }
            val control = controlTracker.resolve(state)
            if (heldButton?.defensive == false) {
                if (control.owner in control.opponents) releaseButton() else return
            } else if (heldButton != null && control.selected >= 0 && control.owner == control.selected) {
                releaseButton()
            }
            val plan = brain.decide(control)
            val action = plan.action
            val canPress = action != null && heldButton == null && actionWindow.available(now, action, control)
            // Aiming a pass while the button is unavailable walks the carrier toward the target.
            val movement = if (action != null && !action.defensive && !canPress)
                brain.carryBetweenKicks(control) else plan.stick
            if (canPress && action != null) {
                if (!pressButton(action, movement)) return
                actionWindow.sent(now, action, control.selected, control.centreRestart)
                EngineRuntime.journal("BOT_PLAY_ACTION frame=${state.frame} action=${action.name} " +
                    "selected=${state.selected} owner=${state.owner} side=${state.side} " +
                    "controlPlayer=${control.selected} controlOwner=${control.owner} intendedReceiver=${plan.receiver}")
            } else if (!setStick(movement)) return
            if (now >= nextPlaySample) {
                nextPlaySample = now + 3000
                EngineRuntime.journal("BOT_PLAY_STATE frame=${state.frame} side=${state.side} " +
                    "selected=${state.selected} owner=${state.owner} direction=${state.direction} " +
                    "controlPlayer=${control.selected} controlOwner=${control.owner} " +
                    "ballX=${state.ball.x} ballZ=${state.ball.z} positions=${state.players.count { it != null }} " +
                    "stickX=${stick.x} stickY=${stick.y} sprint=${stick.sprint}")
            }
        } catch (error: Exception) {
            tacticalReadFailed = true
            stopPlaying()
            EngineRuntime.journal("BOT_PLAY_TACTICS_STOPPED error=${error.javaClass.simpleName}")
        }
    }

    private fun setStick(next: BotStick): Boolean {
        if (next == stick) return true
        if (!EngineRuntime.inputMotion(next.x, next.y, next.sprint)) return false
        stick = next
        return true
    }

    private fun pressButton(action: BotAction, aim: BotStick): Boolean {
        if (heldButton != null) return false
        val result = EngineRuntime.aimAndPress(aim.takeUnless { it == stick }, action)
        if (result == EngineRuntime.ButtonInput.MOTION_FAILED) return false
        stick = aim
        if (result != EngineRuntime.ButtonInput.PRESSED) return false
        heldButton = action
        keyRelease = worker.schedule(::releaseButton, action.holdMs, TimeUnit.MILLISECONDS)
        return true
    }

    private fun releaseButton() {
        keyRelease?.cancel(false)
        keyRelease = null
        heldButton?.let { action ->
            try {
                if (!EngineRuntime.inputKey(action.key, false)) {
                    EngineRuntime.neutral()
                    stick = BotStick()
                }
            } finally {
                heldButton = null
            }
        }
    }

    private fun stopPlaying() {
        playTask?.cancel(false)
        playTask = null
        celebration.reset()
        controlTracker.reset()
        waitingAfterGoal = false
        lastPlayFrame = 0
        lastPlayAt = null
        runCatching { releaseButton() }
        runCatching { setStick(BotStick()) }
    }

    private fun pressPass(published: EngineSnapshot? = null, manual: Boolean = true): Boolean {
        if (!controllerAllowed || heldButton != null || !(published ?: snapshot()).canTestPass) return false
        val state = if (manual) null else EngineRuntime.playState()?.forControl()
        val player = state?.players?.getOrNull(state.selected)
        val aim = if (state != null && player != null && state.centreRestart && state.owner == state.selected)
            brain.kickoffPass(state, player).stick else BotStick()
        if (!pressButton(BotAction.PASS, aim)) return false
        actionWindow.sent(SystemClock.elapsedRealtime(), BotAction.PASS, state?.selected ?: -1, !manual)
        if (manual) {
            diagnostic("BOT_INPUT key=96 action=down nativeAccepted=true destination=private_bot_engine")
            report("Bot Pass input sent — check the response in eFootball")
        }
        return true
    }
    override fun onDestroy() {
        if (foregroundStatus == "active") stopForeground(STOP_FOREGROUND_REMOVE)
        diagnostic("PEERLINK_STOP component=engine_service reason=android_onDestroy action=close_host_and_exit")
        diagnosticTask?.cancel(false)
        worker.shutdownNow()
        memoryWorker.shutdownNow()
        runCatching { EngineRuntime.neutral() }
        EngineRuntime.closeHost()
        super.onDestroy()
        // The original engine owns native worker threads and cannot be dlclosed safely.
        // The bot Activity shares this process; VPN and UI live in the main process.
        Process.killProcess(Process.myPid())
    }
}
