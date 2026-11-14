package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Original game runtime, confined to the private :botengine process. */
internal object EngineRuntime {
    const val ENGINE_SHA256 = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
    private const val CLIENT_CHECK = 0x7e013acL
    const val BLOCK_SIZE = 0x80
    const val RECORD_SIZE = 0x218

    @Volatile var base: Long = 0L
        private set
    @Volatile var client: Long = 0L
        private set
    @Volatile private var ownerUid = 0L
    private var gameHost: GameAndroidRuntime? = null
    var report: (String) -> Unit = {}
    @Volatile private var runtimeThread: Thread? = null
    private val runtime = ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, "engine-runtime").apply {
            isDaemon = true
            runtimeThread = this
        }
    }
    private var heartbeatStarted = false

    private val nativeAvailable = try {
        System.loadLibrary("peerlinkbackend")
        true
    } catch (error: LinkageError) {
        AppState.appendLog("[ENGINE-HOST] native library unavailable: ${error.message}")
        false
    }

    private fun <T> onRuntime(action: () -> T): T {
        if (Thread.currentThread() === runtimeThread) return action()
        return runtime.submit(Callable { action() }).get(60, TimeUnit.SECONDS)
    }

    /** Refuse unknown builds before executing any absolute-offset calls. */
    private fun load(path: File): Boolean {
        if (base != 0L) return true
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().buffered().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != ENGINE_SHA256) {
            AppState.appendLog("[ENGINE-HOST] unsupported game build; engine not started (sha256=$actual)")
            return false
        }
        base = nativeLoad(path.absolutePath)
        return base != 0L
    }

    @Synchronized
    fun prepare(context: Context, uid: Long): Boolean {
        if (!nativeAvailable || uid !in 1..0xffffffffL) return false
        if (client != 0L) {
            if (ownerUid == uid) return true
            AppState.appendLog("[ENGINE-HOST] another account owns this runtime; restart the app before switching")
            return false
        }
        return try {
            val app = context.applicationContext ?: context
            val engine = File(app.filesDir, "engine/libUE4.so")
            check(android.os.Build.VERSION.SDK_INT >= 31) { "Bot engine requires Android 12 or later" }
            if (!engine.isFile || !onRuntime { load(engine) }) return false
            val files = GameAssets.prepare(app, report)
            report("Starting bot simulation")
            val host = gameHost ?: GameAndroidRuntime.create(app, files).also { gameHost = it }
            val bound = EngineAndroidHost.onMainThread(Callable {
                val activity = host.activity()
                if (!nativeInstallAndroidHost(base, activity, activity.assets,
                        activity.filesDir.absolutePath, activity.getExternalFilesDir(null)?.absolutePath)) false
                else if (!nativeStartApplication(base, host.surface(), files.root.absolutePath,
                        "-opengl -nosound -unattended -NoSplash -culture=en -featureleveles31 " +
                            "-ResX=320 -ResY=180 -ProjectDir=${files.project.absolutePath}/")) false
                else { host.resumeAssets(); nativeResumeApplication(base); true }
            })
            if (!bound) return false
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            while (!nativeApplicationReady(base)) {
                check(System.nanoTime() < deadline) { "Bot simulation startup timed out" }
                Thread.sleep(200)
            }
            EngineAndroidHost.onMainThread(Callable { host.resumeGame() })
            check(onRuntime { nativeLoadDatabase(base, files.database.absolutePath) }) { "Bot game database could not start" }
            onRuntime {
                client = nativeStart(base, uid)
                if (client == 0L) return@onRuntime false
                ownerUid = uid
                // The original application's game thread owns heartbeat, message
                // dispatch and model updates. A second manual tick races it.
                report("Bot simulation started — waiting for your game")
                true
            }
        } catch (error: Exception) {
            report("Bot could not start: ${error.cause?.message ?: error.message}")
            false
        } catch (error: LinkageError) {
            report("Bot engine unavailable: ${error.message}")
            false
        }
    }

    fun setPeers(record: ByteArray): Boolean = record.size == RECORD_SIZE && onRuntime {
        client != 0L && nativeSetPeers(base, record)
    }

    /** Accepted by the original session manager; does not assert connectivity. */
    fun openSession(peerUid: Long, connectingStart: Long): Boolean = onRuntime {
        client != 0L && nativeOpenSession(base, peerUid, connectingStart)
    }

    /** Original SDK's own verdict. No connection state is changed here. */
    fun check(): Long = onRuntime {
        if (client == 0L) 0L else nativeCall(base, CLIENT_CHECK, client, 0, 0, 0)
    }

    fun buildBlock(family: Int, address: String, port: Int): ByteArray? = onRuntime {
        if (base == 0L) return@onRuntime null
        val pointer = nativeAlloc(BLOCK_SIZE)
        if (pointer == 0L) return@onRuntime null
        try {
            if (nativeBuildBlock(base, pointer, family, address, port) == 0L) null
            else ByteArray(BLOCK_SIZE).also { nativeRead(pointer, it) }
        } finally {
            nativeFree(pointer)
        }
    }

    /** Original SDK input layout; NAT words are 32-bit values, not pointers. */
    fun buildPeerRecord(
        peerId: Long,
        v4Host: String, v4HostPort: Int, v4Reflexive: String, v4ReflexivePort: Int, v4NatType: Long,
        v6Host: String, v6HostPort: Int, v6Reflexive: String, v6ReflexivePort: Int, v6NatType: Long,
    ): ByteArray? {
        if (peerId !in 1..0xffffffffL || v4NatType !in 0..0xffffffffL || v6NatType !in 0..0xffffffffL) return null
        val blocks = listOf(
            0x00c to (buildBlock(1, v4Host, v4HostPort) ?: return null),
            0x08c to (buildBlock(1, v4Reflexive, v4ReflexivePort) ?: return null),
            0x110 to (buildBlock(2, v6Host, v6HostPort) ?: return null),
            0x190 to (buildBlock(2, v6Reflexive, v6ReflexivePort) ?: return null),
        )
        return ByteArray(RECORD_SIZE).also { record ->
            putInt(record, 8, peerId.toInt())
            for ((offset, block) in blocks) block.copyInto(record, offset)
            putInt(record, 0x10c, v4NatType.toInt())
            putInt(record, 0x210, v6NatType.toInt())
            putInt(record, 0x214, -1)
            // The JNI adapter supplies the original interface vtable in its
            // owned copy before submitting the record to the original SDK.
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    fun setupMatch(peerUid: Long, gameId: Long, sid: String, category: String,
                   keyword: String, squad: ByteArray): Boolean = onRuntime {
        if (client == 0L || peerUid == ownerUid || gameId <= 0) return@onRuntime false
        val root = File(gameHost!!.activity().filesDir.parentFile, "match-inputs").apply { mkdirs() }
        val squadFile = File(root, "squad.dto").apply { writeBytes(squad) }
        val sidBytes = sid.toByteArray(Charsets.UTF_8)
        val categoryBytes = category.toByteArray(Charsets.UTF_8)
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        require(sidBytes.size in 1..512 && categoryBytes.size in 1..64 && keywordBytes.size == 8)
        val input = java.nio.ByteBuffer.allocate(32 + sidBytes.size + categoryBytes.size + 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val teamId = java.nio.ByteBuffer.wrap(squad).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        input.put(byteArrayOf(82, 71, 73, 49)).putInt(ownerUid.toInt()).putLong(gameId)
            .putInt(teamId).putInt(sidBytes.size).putInt(categoryBytes.size).putInt(8)
            .put(sidBytes).put(categoryBytes).put(keywordBytes)
        val contextFile = File(root, "context.dto").apply { writeBytes(input.array()) }
        nativeSetupMatch(base, peerUid, squadFile.absolutePath, contextFile.absolutePath)
    }

    fun snapshot(): LongArray = onRuntime { if (base == 0L) LongArray(18) else nativeSnapshot(base) }
    fun advanceMatch(controllerAllowed: Boolean): Int = onRuntime {
        if (base == 0L) -1 else nativeAdvanceMatch(base, controllerAllowed)
    }
    fun inputKey(code: Int, down: Boolean): Boolean = onRuntime {
        if (base == 0L) false else nativeInputKey(base, code, if (down) 0 else 1)
    }
    fun neutral() = onRuntime { if (base != 0L) nativeNeutralInput(base) }

    private external fun nativeLoad(path: String): Long
    private external fun nativeStartApplication(base: Long, surface: android.view.Surface, root: String, command: String): Boolean
    private external fun nativeResumeApplication(base: Long)
    private external fun nativeApplicationReady(base: Long): Boolean
    private external fun nativeLoadDatabase(base: Long, path: String): Boolean
    private external fun nativeSetupMatch(base: Long, peer: Long, squad: String, context: String): Boolean
    private external fun nativeSnapshot(base: Long): LongArray
    private external fun nativeAdvanceMatch(base: Long, controllerAllowed: Boolean): Int
    private external fun nativeInputKey(base: Long, code: Int, action: Int): Boolean
    private external fun nativeNeutralInput(base: Long)
    private external fun nativePrepareCore(base: Long): Boolean
    private external fun nativeInstallAndroidHost(
        base: Long, activity: android.app.NativeActivity, assets: android.content.res.AssetManager,
        internalPath: String, externalPath: String?,
    ): Boolean
    private external fun nativeStart(base: Long, uid: Long): Long
    private external fun nativeSetPeers(base: Long, record: ByteArray): Boolean
    private external fun nativeOpenSession(base: Long, peer: Long, connectingStart: Long): Boolean
    private external fun nativeTick(base: Long)
    private external fun nativeCall(base: Long, offset: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
    private external fun nativeAlloc(size: Int): Long
    private external fun nativeFree(address: Long)
    private external fun nativeRead(address: Long, bytes: ByteArray)
    private external fun nativeBuildBlock(base: Long, destination: Long, family: Int, address: String, port: Int): Long
}
