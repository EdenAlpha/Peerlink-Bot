package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Build-B networking host. All native lifecycle calls share one runtime thread. */
object EngineHost {
    private const val ENGINE_SHA256 = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
    private const val CLIENT_CHECK = 0x7e013acL
    const val BLOCK_SIZE = 0x80
    const val RECORD_SIZE = 0x218

    @Volatile var base: Long = 0L
        private set
    @Volatile var client: Long = 0L
        private set
    @Volatile private var ownerUid = 0L
    private var androidHost: EngineAndroidHost? = null
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
            val host = androidHost ?: EngineAndroidHost.fromInstalledGame(app).also { androidHost = it }
            val engine = File(app.filesDir, "engine/libUE4.so")
            if (!engine.isFile || !onRuntime { load(engine) && nativePrepareCore(base) }) return false
            // Keep ANativeActivity.env on Android's real main thread. The
            // native helper obtains each worker thread's own env from the VM.
            val bound = EngineAndroidHost.onMainThread(Callable {
                nativeInstallAndroidHost(base, host, host.assets, app.filesDir.absolutePath,
                    app.getExternalFilesDir(null)?.absolutePath)
            })
            if (!bound) return false
            onRuntime {
                client = nativeStart(base, uid)
                if (client == 0L) return@onRuntime false
                ownerUid = uid
                if (!heartbeatStarted) {
                    runtime.scheduleWithFixedDelay({
                        nativeTick(base)
                    }, 0, 250, TimeUnit.MILLISECONDS)
                    heartbeatStarted = true
                }
                AppState.appendLog("[ENGINE-HOST] real Android host and original SDK started; peer connection pending")
                true
            }
        } catch (error: Exception) {
            AppState.appendLog("[ENGINE-HOST] preparation stopped: ${error.javaClass.simpleName}: ${error.message}")
            false
        } catch (error: LinkageError) {
            AppState.appendLog("[ENGINE-HOST] native library unavailable: ${error.message}")
            false
        }
    }

    fun setPeers(record: ByteArray): Boolean = record.size == RECORD_SIZE && onRuntime {
        client != 0L && nativeSetPeers(base, record)
    }

    /** Accepted by the original session manager; does not assert connectivity. */
    fun openSession(peerUid: Long): Boolean = onRuntime {
        client != 0L && nativeOpenSession(base, peerUid)
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

    private external fun nativeLoad(path: String): Long
    private external fun nativePrepareCore(base: Long): Boolean
    private external fun nativeInstallAndroidHost(
        base: Long, activity: android.app.NativeActivity, assets: android.content.res.AssetManager,
        internalPath: String, externalPath: String?,
    ): Boolean
    private external fun nativeStart(base: Long, uid: Long): Long
    private external fun nativeSetPeers(base: Long, record: ByteArray): Boolean
    private external fun nativeOpenSession(base: Long, peer: Long): Boolean
    private external fun nativeTick(base: Long)
    private external fun nativeCall(base: Long, offset: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
    private external fun nativeAlloc(size: Int): Long
    private external fun nativeFree(address: Long)
    private external fun nativeRead(address: Long, bytes: ByteArray)
    private external fun nativeBuildBlock(base: Long, destination: Long, family: Int, address: String, port: Int): Long
}
