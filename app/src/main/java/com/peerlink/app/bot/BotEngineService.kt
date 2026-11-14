package com.peerlink.app.bot

import android.app.Service
import android.content.Intent
import android.os.*
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Private, bound, same-UID service. A native engine exit cannot take down the game VPN. */
class BotEngineService : Service() {
    private val worker = ScheduledThreadPoolExecutor(1) { Thread(it, "bot-engine-service") }
    private var observer: Messenger? = null
    private var controllerAllowed = false
    private var testing = false
    private var pollStarted = false
    private var lastStatus = ""
    private var nextPass = 0L
    private var passes = 0
    private var keyDown = false
    private var pss = 0L
    private var nextMemorySample = 0L
    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(message: Message) {
            val request = Message.obtain(message)
            worker.execute { handle(request) }
        }
    })

    override fun onCreate() {
        super.onCreate()
        EngineRuntime.report = ::report
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
                    if (!result.getBoolean("ok")) result.putString("reason", lastStatus.ifBlank { "Bot simulation could not start" })
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
                EngineProtocol.CHECK -> result.putLong("verdict", EngineRuntime.check())
                EngineProtocol.SETUP_MATCH -> {
                    val ok = EngineRuntime.setupMatch(args.getLong("peer"), args.getLong("gameId"),
                        requireNotNull(args.getString("sid")), "MYCLUB_LOBBY_ROOM_MATCH_1VS1",
                        requireNotNull(args.getString("keyword")), requireNotNull(args.getByteArray("squad")))
                    result.putBoolean("ok", ok)
                    if (ok && !pollStarted) {
                        pollStarted = true
                        worker.scheduleWithFixedDelay(::poll, 0, 500, TimeUnit.MILLISECONDS)
                    }
                }
                EngineProtocol.SNAPSHOT -> result.putLongArray("state", snapshot().values)
                EngineProtocol.START_TEST -> {
                    controllerAllowed = args.getBoolean("controllerAllowed")
                    testing = controllerAllowed
                    passes = 0
                    result.putBoolean("ok", testing)
                }
                EngineProtocol.PASS -> result.putBoolean("ok", pressPass())
                EngineProtocol.PROBE_CONTROLLER -> result.putBoolean("ok", EngineRuntime.probeController())
                EngineProtocol.STOP -> {
                    testing = false
                    EngineRuntime.neutral()
                    keyDown = false
                    result.putBoolean("ok", true)
                }
                else -> error("Unknown bot engine command")
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
    private fun snapshot(): EngineSnapshot {
        val now = SystemClock.elapsedRealtime()
        if (now >= nextMemorySample) { pss = Debug.getPss().toLong(); nextMemorySample = now + 5000 }
        return EngineSnapshot(EngineRuntime.snapshot().copyOf(19).also { it[18] = pss })
    }
    private fun poll() {
        try {
            val advance = EngineRuntime.advanceMatch(controllerAllowed)
            check(advance >= 0) { "Bot match setup failed (stage $advance)" }
            val state = snapshot()
            val line = state.status()
            if (line != lastStatus) report(line)
            val now = SystemClock.elapsedRealtime()
            if (testing && passes < 10 && state.canTestPass && now >= nextPass) {
                if (pressPass()) { passes++; nextPass = now + 6000 }
            }
        } catch (error: Exception) {
            testing = false
            EngineRuntime.neutral()
            report(error.message ?: "Bot match setup stopped")
        }
    }
    private fun pressPass(): Boolean {
        if (!controllerAllowed || keyDown || !snapshot().canTestPass) return false
        if (!EngineRuntime.inputKey(96, true)) return false
        keyDown = true
        worker.schedule({
            try { EngineRuntime.inputKey(96, false) } finally { keyDown = false }
        }, 140, TimeUnit.MILLISECONDS)
        report("Bot Pass input sent — check the response in eFootball")
        return true
    }
    override fun onDestroy() {
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
