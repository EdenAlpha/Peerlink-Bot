package com.peerlink.app.bot

import com.peerlink.app.tunnel.StunFabricator
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** The private engine's STUN discovery stays within the phone, like its peer. */
internal class BotDiscoveryServer(private val audit: (String) -> Unit) : AutoCloseable {
    companion object { const val ADDRESS = "127.0.0.80" }
    private val running = AtomicBoolean(true)
    private val requests = AtomicLong()
    private val replies = AtomicLong()
    private val sockets = linkedMapOf<InetSocketAddress, DatagramSocket>()
    private val workers = mutableListOf<Thread>()

    init {
        try {
            // Bind every RFC 5780 alternate source before the SDK starts.
            // No wildcard listener, SO_REUSEADDR, or physical-interface bind.
            for (ip in StunFabricator.LOCAL_BOT_SERVERS) for (port in 3478..3479) {
                val endpoint = InetSocketAddress(ip, port)
                val socket = DatagramSocket(null)
                try {
                    socket.soTimeout = 250
                    socket.bind(endpoint)
                    sockets[endpoint] = socket
                } catch (error: Exception) { socket.close(); throw error }
            }
            sockets.forEach { (endpoint, socket) ->
                workers += thread(name = "bot-discovery-${endpoint.port}-${endpoint.address.hostAddress}", isDaemon = true) {
                    receive(endpoint, socket)
                }
            }
            audit("[BOT-DISCOVERY] ready=true endpoint=$ADDRESS:3478 scope=loopback originalStunParser=true")
        } catch (error: Exception) {
            close()
            throw IllegalStateException("The bot's local connection service could not start", error)
        }
    }

    private fun receive(endpoint: InetSocketAddress, socket: DatagramSocket) {
        while (running.get()) {
            try {
                val packet = DatagramPacket(ByteArray(4096), 4096)
                socket.receive(packet)
                // The SDK binds its real physical address before discovery.
                // A datagram addressed to our loopback listener can therefore
                // legitimately arrive with this phone's Wi-Fi/cellular source.
                if (!packet.address.isLoopbackAddress &&
                    runCatching { NetworkInterface.getByInetAddress(packet.address) }.getOrNull() == null) continue
                requests.incrementAndGet()
                val response = StunFabricator.localBotBindingReply(
                    packet.data.copyOfRange(packet.offset, packet.offset + packet.length),
                    endpoint.address.hostAddress!!, endpoint.port, packet.port, BotMode.BOT_FAB_IP,
                ) ?: continue
                val source = sockets[InetSocketAddress(response.sourceIp, response.sourcePort)] ?: continue
                source.send(DatagramPacket(response.payload, response.payload.size,
                    packet.address, response.destinationPort))
                val count = replies.incrementAndGet()
                if (count == 1L || count % 5L == 0L)
                    audit("[BOT-DISCOVERY] ${diagnostics()} mapping=${BotMode.BOT_FAB_IP}:${packet.port}")
            } catch (_: SocketTimeoutException) {
            } catch (error: Exception) {
                if (running.get()) audit("[BOT-DISCOVERY] replyFailed=${error.javaClass.simpleName}")
                if (error is SocketException) break
            }
        }
    }

    fun diagnostics(): String = "localStunReady=${running.get()} localStunRequests=${requests.get()} localStunReplies=${replies.get()}"

    override fun close() {
        if (!running.getAndSet(false)) return
        sockets.values.forEach { it.close() }
        workers.forEach { if (it !== Thread.currentThread()) it.join(1000) }
    }
}
