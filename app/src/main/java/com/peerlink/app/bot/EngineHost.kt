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
    @Volatile private var ownerUid = 0L
    @Volatile private var service: Messenger? = null
    private var context: Context? = null
    private var binding: CountDownLatch? = null
    private val preparation = Any()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<Bundle>>()
    private val sequence = AtomicInteger()
    private val replyThread = HandlerThread("bot-engine-replies").apply { start() }
    private val replies = Messenger(object : Handler(replyThread.looper) {
        override fun handleMessage(message: Message) {
            val data = message.data
            if (message.what == EngineProtocol.STATUS) {
                val line = data.getString("status").orEmpty()
                if (line.isNotBlank()) PeerBotService.status(line)
            } else pending.remove(message.arg1)?.complete(data)
        }
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = Messenger(binder)
            synchronized(this@EngineHost) { binding?.countDown() }
        }
        override fun onServiceDisconnected(name: ComponentName) = disconnected()
        override fun onBindingDied(name: ComponentName) = disconnected()
        override fun onNullBinding(name: ComponentName) = disconnected()
    }
    private fun disconnected() {
        service = null
        isReady = false
        PeerSlot.engineAttached = false
        lastError = "Bot engine stopped — start a new room"
        PeerBotService.status(lastError)
        for ((id, future) in pending) if (pending.remove(id, future))
            future.completeExceptionally(IllegalStateException(lastError))
        synchronized(this) {
            binding?.countDown()
            binding = null
            runCatching { context?.unbindService(connection) }
        }
    }
    private fun bind(owner: Context) {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Prepare the bot on a worker thread" }
        val latch = synchronized(this) {
            context = owner.applicationContext
            if (service != null) return
            binding ?: CountDownLatch(1).also {
                binding = it
                if (!context!!.bindService(Intent(context, BotEngineService::class.java), connection,
                        Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)) {
                    binding = null
                    error("Could not start the bot engine service")
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
        if (isReady) return ownerUid == uid
        try {
            bind(owner)
            val result = request(EngineProtocol.PREPARE, Bundle().apply { putLong("uid", uid) }, 300)
            isReady = result.getBoolean("ok")
            if (isReady) ownerUid = uid
            else lastError = result.getString("reason") ?: "The bot simulation did not start"
            isReady
        } catch (error: Exception) {
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
    fun check(): Long = if (!isReady) 0 else runCatching { request(EngineProtocol.CHECK).getLong("verdict") }.getOrDefault(0)
    fun setupMatch(peer: Long, gameId: Long, sid: String, squad: ByteArray): Boolean =
        request(EngineProtocol.SETUP_MATCH, Bundle().apply {
            putLong("peer", peer); putLong("gameId", gameId); putString("sid", sid)
            putString("keyword", GateCodec.sKeyword()); putByteArray("squad", squad)
        }, 60).getBoolean("ok")
    fun snapshot(): EngineSnapshot = EngineSnapshot(request(EngineProtocol.SNAPSHOT).getLongArray("state") ?: LongArray(19))
    fun startInputTest(controllerAllowed: Boolean): Boolean = request(EngineProtocol.START_TEST,
        Bundle().apply { putBoolean("controllerAllowed", controllerAllowed) }).getBoolean("ok")
    fun pass(): Boolean = request(EngineProtocol.PASS).getBoolean("ok")
    internal fun probeController(): Boolean = request(EngineProtocol.PROBE_CONTROLLER).getBoolean("ok")
    fun stop() {
        runCatching { request(EngineProtocol.STOP) }
        isReady = false
        ownerUid = 0
        service = null
        synchronized(this) { runCatching { context?.unbindService(connection) }; binding = null }
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
    const val STATUS = 100
}

data class EngineSnapshot(val values: LongArray) {
    private fun field(index: Int) = values.getOrElse(index) { 0 }
    val connected get() = field(2) == 4L && field(3) == 4L && field(4) == 4L
    val metadataReady get() = field(5) == 5L && field(6) == 1L && field(7) == 0L
    val matchConstructed get() = field(8) == 17L && field(9) == 54L && field(10) == 22L
    val ballOnPitch get() = field(11) == 1L
    val canTestPass get() = connected && matchConstructed && ballOnPitch && field(16) == 1L && field(17) == 1L
    val memoryMiB get() = field(18) / 1024
    val ballSpeedMmPerSecond get() = field(15)
    fun status(): String = when {
        !connected -> "Connecting to your game"
        !metadataReady -> "Connected — exchanging teams"
        !matchConstructed -> "Loading the match — continue in eFootball when ready"
        !ballOnPitch -> "Match loaded — waiting for play to resume"
        else -> "Match simulation ready — bot control test available"
    }
}
