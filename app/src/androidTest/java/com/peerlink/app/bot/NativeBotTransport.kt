package com.peerlink.app.bot

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.peerlink.app.tunnel.NativeBackendConfig
import com.peerlink.app.tunnel.NativePeerLinkBackend
import java.io.FileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** The production native VPN backend, with packet-preserving UNIX datagrams as its TUN. */
internal class NativeBotTransport(initialPeerPort: Int, incoming: (ByteArray) -> Unit) : AutoCloseable {
    private val nativeEnd = FileDescriptor()
    private val gameEnd = FileDescriptor()
    private val running = AtomicBoolean(true)
    private val backend = NativePeerLinkBackend(
        NativeBackendConfig("127.0.0.1", initialPeerPort, BotMode.GAME_FAB_IP, BotMode.BOT_FAB_IP,
            "127.0.0.1", 0),
        object : NativePeerLinkBackend.Callbacks {
            override fun preparePeerSocket(fdForBinding: Int) = true
            override fun fabricateStunResponse(packet: ByteArray, length: Int): ByteArray? = null
            override fun onNativeLog(level: Int, message: String, fileOnly: Boolean) {
                if (!fileOnly) Log.i("PeerlinkNativeVpnTest", message)
            }
        }
    )
    private val bridge: ParcelFileDescriptor
    private val reader: Thread
    init {
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, nativeEnd, gameEnd)
        val transferred = ParcelFileDescriptor.dup(nativeEnd).detachFd()
        Os.close(nativeEnd)
        bridge = ParcelFileDescriptor.adoptFd(backend.start(transferred))
        reader = thread(name = "native-vpn-test-tun", isDaemon = true) {
            val input = FileInputStream(gameEnd)
            val buffer = ByteArray(65535)
            while (running.get()) {
                try {
                    val n = input.read(buffer)
                    if (n > 0) incoming(buffer.copyOf(n)) else break
                } catch (error: Exception) {
                    if (running.get()) Log.e("PeerlinkNativeVpnTest", "TUN reader failed", error)
                    break
                }
            }
        }
    }
    fun retarget(port: Int) = backend.retargetLocalPeer(port)
    fun verify() = backend.verifyPeerPath(3000)
    fun send(packet: ByteArray) { check(Os.write(gameEnd, packet, 0, packet.size) == packet.size) }
    override fun close() {
        running.set(false)
        backend.stop()
        runCatching { Os.shutdown(gameEnd, OsConstants.SHUT_RDWR) }
        reader.join(1000)
        Os.close(gameEnd)
        bridge.close()
    }
}
