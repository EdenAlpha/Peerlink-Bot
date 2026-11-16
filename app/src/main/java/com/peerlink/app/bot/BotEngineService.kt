package com.peerlink.app.bot

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Private, bound, same-UID service. A native engine exit cannot take down the game VPN. */
class BotEngineService : Service() {
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "bot-engine-service") }
    private var observer: Messenger? = null
    private var controllerAllowed = false
    private var testing = false
    private var pollStarted = false
    private var pollTask: ScheduledFuture<*>? = null
    private var lastStatus = ""
    private var nextPass = 0L
    private var passes = 0
    private var keyDown = false
    private var pss = 0L
    private var nextMemorySample = 0L
    private var nextNativeSample = 0L
    private var nextNetworkSample = 0L
    private var nextMemoryReclaim = 0L
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
    override fun onBind(intent: Intent?): IBinder = messenger.binder
    private fun handle(message: Message) {
        observer = message.replyTo ?: observer
        val result = Bundle()
        try {
            val args = message.data
            when (message.what) {
                EngineProtocol.PREPARE -> {
                    result.putBoolean("ok", EngineRuntime.prepare(this, args.getLong("uid")))
                    result.putInt("pid", Process.myPid())
                    result.putBoolean("retryable", EngineRuntime.startupRetryable)
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
                EngineProtocol.SET_PEERS -> result.putBoolean("ok", EngineRuntime.setPeers(requireNotNull(args.getByteArray("record"))))
                EngineProtocol.OPEN_SESSION -> result.putBoolean("ok", EngineRuntime.openSession(args.getLong("peer"), args.getLong("connectingStart")))
                EngineProtocol.BEGIN_INTRODUCTION -> result.putBoolean("ok", EngineRuntime.beginIntroduction(args.getLong("peer"), args.getLong("connectingStart")))
                EngineProtocol.TRANSPORT_IO_READY -> result.putInt("state", EngineRuntime.transportIoReady())
                EngineProtocol.CHECK -> result.putLong("verdict", EngineRuntime.check())
                EngineProtocol.CONFIGURE_TURN -> result.putBoolean("ok", EngineRuntime.configureTurn(
                    requireNotNull(args.getByteArray("input"))))
                EngineProtocol.REVISION_POLICY -> {
                    check(args.containsKey("enabled")) { "Server revision policy is missing" }
                    result.putBoolean("ok", EngineRuntime.configureRevisionCheck(args.getBoolean("enabled")))
                    diagnostic("SERVER_REVISION_POLICY enabled=${args.getBoolean("enabled")} accepted=${result.getBoolean("ok")}")
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
                        requireNotNull(args.getString("keyword")), requireNotNull(args.getByteArray("squad")))
                    result.putBoolean("ok", ok)
                    if (ok && !pollStarted) {
                        pollStarted = true
                        pollTask = worker.scheduleWithFixedDelay(::poll, 0, 500, TimeUnit.MILLISECONDS)
                    }
                }
                EngineProtocol.SNAPSHOT -> result.putLongArray("state", snapshot().values)
                EngineProtocol.LOADING_DIAGNOSTICS -> result.putString("details", EngineRuntime.loadingDiagnostics())
                EngineProtocol.START_TEST -> {
                    controllerAllowed = args.getBoolean("controllerAllowed")
                    testing = controllerAllowed && args.getBoolean("automatic", true)
                    passes = 0
                    result.putBoolean("ok", controllerAllowed)
                }
                EngineProtocol.PASS -> result.putBoolean("ok", pressPass())
                EngineProtocol.PROBE_CONTROLLER -> result.putBoolean("ok", EngineRuntime.probeController())
                EngineProtocol.PROBE_PREVIEW -> result.putBoolean("ok", EngineRuntime.probePreviewCallback())
                EngineProtocol.STOP -> {
                    diagnostic("PEERLINK_STOP component=engine_service reason=stop_command")
                    sampleDiagnostics()
                    diagnosticTask?.cancel(false)
                    testing = false
                    pollTask?.cancel(false)
                    EngineRuntime.stopPreview()
                    EngineRuntime.neutral()
                    keyDown = false
                    result.putBoolean("ok", true)
                }
                else -> error("Unknown bot engine command")
            }
            if (message.what == EngineProtocol.BEGIN_INTRODUCTION || message.what == EngineProtocol.OPEN_SESSION) {
                diagnostic("NATIVE_COMMAND command=${if (message.what == EngineProtocol.OPEN_SESSION) "open_session" else "begin_introduction"} accepted=${result.getBoolean("ok")}")
                if (result.getBoolean("ok") && diagnosticTask == null)
                    diagnosticTask = worker.scheduleWithFixedDelay(::sampleDiagnostics, 0, 100, TimeUnit.MILLISECONDS)
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
    private fun report(line: String) {
        lastStatus = line
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.STATUS).apply {
            data = Bundle().apply { putString("status", line) }
        }) }
    }
    private fun snapshot(passive: Boolean = false): EngineSnapshot {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextMemorySample) { pss = Debug.getPss().toLong(); nextMemorySample = now + 5000 }
        return EngineSnapshot(EngineRuntime.snapshot(passive).copyOf(29).also { it[18] = pss })
    }
    private fun diagnostic(line: String, firstFailure: Boolean = false) {
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.DIAGNOSTIC).apply {
            data = Bundle().apply {
                putString("line", line)
                putBoolean("firstFailure", firstFailure)
                putLong("observedAtMs", SystemClock.elapsedRealtime())
            }
        }) }
    }
    private fun sampleDiagnostics() {
        try {
            val state = snapshot(passive = true)
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
        diagnostic(EngineRuntime.loadingDiagnostics())
        runCatching { observer?.send(Message.obtain(null, EngineProtocol.NATIVE_STATE).apply {
            data = Bundle().apply { putLongArray("state", state.values) }
        }) }
    }
    private fun poll() {
        try {
            val advance = EngineRuntime.advanceMatch(controllerAllowed)
            check(advance >= 0) {
                val reason = when (advance) {
                    -205 -> "The bot's user-data task disappeared before completion"
                    -206 -> "The bot's user-data synchronization stopped before completion"
                    -207 -> "The bot's user-data synchronization timed out"
                    -208 -> "The bot's server match context was rejected"
                    -209 -> "The bot's match-preview menu did not finish loading"
                    -210 -> "The bot could not queue its match-preview step on the game thread"
                    -211 -> "The bot could not skip the match introduction"
                    else -> "Bot match setup failed"
                }
                "$reason (native stage $advance)"
            }
            val state = snapshot()
            logNativeState(state)
            val line = state.status()
            if (line != lastStatus) report(line)
            val now = SystemClock.elapsedRealtime()
            // Loading the foreground game caused a confirmed Android LMK on
            // the 4 GB phone. Return already-freed native pages during this
            // loading window; never discard live match objects or game data.
            if (advance >= 2 && !state.matchConstructed && now >= nextMemoryReclaim) {
                nextMemoryReclaim = now + 5000
                EngineRuntime.releaseUnusedMemory("match_loading")
                val memory = android.app.ActivityManager.MemoryInfo()
                getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(memory)
                diagnostic("BOT_MEMORY availableKiB=${memory.availMem / 1024} " +
                    "thresholdKiB=${memory.threshold / 1024} lowMemory=${memory.lowMemory} " +
                    "nativeHeapKiB=${Debug.getNativeHeapAllocatedSize() / 1024}")
            }
            if (testing && passes < 10 && state.canTestPass && now >= nextPass) {
                if (pressPass()) { passes++; nextPass = now + 6000 }
            }
        } catch (error: Exception) {
            testing = false
            pollTask?.cancel(false)
            runCatching { logNativeState(snapshot()) }
            EngineRuntime.neutral()
            val failure = runCatching { snapshot() }.getOrNull()
            report(if (failure?.nativeFailed == true) failure.status() else error.message ?: "Bot match setup stopped")
        }
    }
    private fun pressPass(): Boolean {
        if (!controllerAllowed || keyDown || !snapshot().canTestPass) return false
        if (!EngineRuntime.inputKey(96, true)) return false
        diagnostic("BOT_INPUT key=96 action=down nativeAccepted=true destination=private_bot_engine")
        keyDown = true
        worker.schedule({
            try {
                val accepted = EngineRuntime.inputKey(96, false)
                diagnostic("BOT_INPUT key=96 action=up nativeAccepted=$accepted destination=private_bot_engine")
            } finally { keyDown = false }
        }, 140, TimeUnit.MILLISECONDS)
        report("Bot Pass input sent — check the response in eFootball")
        return true
    }
    override fun onDestroy() {
        diagnostic("PEERLINK_STOP component=engine_service reason=android_onDestroy action=close_host_and_exit")
        diagnosticTask?.cancel(false)
        testing = false
        runCatching { EngineRuntime.neutral() }
        EngineRuntime.closeHost()
        worker.shutdownNow()
        super.onDestroy()
        // The original engine owns native worker threads and cannot be dlclosed safely.
        // The bot Activity shares this process; VPN and UI live in the main process.
        Process.killProcess(Process.myPid())
    }
}
