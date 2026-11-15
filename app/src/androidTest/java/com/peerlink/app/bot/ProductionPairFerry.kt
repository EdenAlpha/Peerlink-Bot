package com.peerlink.app.bot

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Drives the real PeerSlot with another original SDK. The fixture supplies
 * only the VPN's outer IPv4/UDP carrier; game payloads stay unchanged.
 */
internal class ProductionPairFerry(botUid: Long, peerUid: Long) : AutoCloseable {
    private val running = AtomicBoolean(true)
    private val host = socket("127.0.0.1", 39202)
    private val reflexive = socket("127.0.0.2", 39204)
    private val backend = socket("127.0.0.1", PeerSlot.BACKEND_PORT)
    @Volatile private var gameEndpoint: InetSocketAddress? = null
    private val port = PeerSlot.reserveEndpoint()
    init {
        BotMode.ensurePair()
        PeerSlot.arm(peerUid, botUid, "", "127.0.0.1", 0)
    }
    private val threads = listOf(
        pump(host, "host") { packet, payload ->
            if (packet.port == port) {
                gameEndpoint?.let { host.send(DatagramPacket(payload, payload.size, it)) }
            } else {
                gameEndpoint = packet.socketAddress as InetSocketAddress
                host.send(DatagramPacket(payload, payload.size, InetSocketAddress("127.0.0.1", port)))
            }
        },
        pump(reflexive, "reflexive") { packet, payload ->
            gameEndpoint = packet.socketAddress as InetSocketAddress
            val inner = carrier(payload, packet.port, port)
            backend.send(DatagramPacket(inner, inner.size, InetSocketAddress("127.0.0.1", port)))
        },
        pump(backend, "backend") { _, inner ->
            require(inner.size >= 28 && inner[0].toInt() and 0xf0 == 0x40)
            val offset = (inner[0].toInt() and 15) * 4 + 8
            require(offset <= inner.size)
            val payload = inner.copyOfRange(offset, inner.size)
            gameEndpoint?.let { reflexive.send(DatagramPacket(payload, payload.size, it)) }
        }
    )
    private fun pump(socket: DatagramSocket, label: String,
                     forward: (DatagramPacket, ByteArray) -> Unit): Thread = thread(name = "production-fixture-$label", isDaemon = true) {
        while (running.get()) {
            try {
                val packet = DatagramPacket(ByteArray(65535), 65535)
                socket.receive(packet)
                forward(packet, packet.data.copyOfRange(packet.offset, packet.offset + packet.length))
            } catch (_: SocketTimeoutException) {
            } catch (error: Exception) {
                if (running.get()) Log.e("PeerlinkPairTest", "Production fixture $label stopped", error)
            }
        }
    }
    override fun close() {
        running.set(false)
        PeerSlot.disarm()
        listOf(host, reflexive, backend).forEach { it.close() }
        threads.forEach { it.join(1000) }
    }
    companion object {
        private fun socket(ip: String, port: Int) = DatagramSocket(InetSocketAddress(ip, port)).apply { soTimeout = 100 }
        private fun carrier(payload: ByteArray, sourcePort: Int, destinationPort: Int): ByteArray {
            val bytes = ByteArray(28 + payload.size)
            fun put16(at: Int, value: Int) { bytes[at] = (value ushr 8).toByte(); bytes[at + 1] = value.toByte() }
            bytes[0] = 0x45; bytes[8] = 64; bytes[9] = 17
            put16(2, bytes.size)
            byteArrayOf(10, 77, 0, 2).copyInto(bytes, 12)
            BotMode.BOT_FAB_IP.split('.').map { it.toInt().toByte() }.toByteArray().copyInto(bytes, 16)
            put16(20, sourcePort); put16(22, destinationPort); put16(24, payload.size + 8)
            payload.copyInto(bytes, 28)
            return bytes
        }
    }
}
