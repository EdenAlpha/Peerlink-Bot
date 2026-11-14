package com.peerlink.app.bot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*

/** Transport-only test with opaque bytes. It does not simulate a game handshake or prove kickoff. */
@RunWith(AndroidJUnit4::class)
class BotFerryTransportTest {
    private fun socket(port: Int = 0) = DatagramSocket(InetSocketAddress("127.0.0.1", port)).apply { soTimeout = 3000 }
    private fun DatagramSocket.sendTo(bytes: ByteArray, port: Int) =
        send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", port)))
    private fun DatagramSocket.receiveBytes(): ByteArray {
        val packet = DatagramPacket(ByteArray(4096), 4096); receive(packet)
        return packet.data.copyOf(packet.length)
    }
    @Test fun hostAndFabricatedCandidatesCarryUnchangedPayloadsOnSeparatePorts() {
        BotMode.ensurePair()
        socket().use { game -> socket().use { engine -> socket(PeerSlot.BACKEND_PORT).use { backend ->
            try {
                val port = PeerSlot.reserveEndpoint()
                assertNotEquals(game.localPort, port)
                assertEquals(port, PeerSlot.reserveEndpoint())
                PeerSlot.arm(100, 200, "", "127.0.0.1", game.localPort)
                PeerSlot.engineAttached = true
                engine.sendTo(byteArrayOf(7), PeerSlot.ENGINE_PORT)
                backend.receiveBytes() // Original opaque marker wrapped for the TUN route.

                val direct = byteArrayOf(0x45, 2, 3)
                game.sendTo(direct, port)
                assertArrayEquals(direct, engine.receiveBytes())
                val reply = byteArrayOf(8, 9, 10)
                engine.sendTo(reply, PeerSlot.ENGINE_PORT)
                assertArrayEquals(reply, game.receiveBytes())

                backend.sendTo(byteArrayOf(1, 2), port) // Malformed transport frame must not kill the listener.
                val tunneled = byteArrayOf(11, 12, 13, 14)
                val packet = ByteArray(28 + tunneled.size)
                packet[0] = 0x45; packet[9] = 17
                packet[2] = 0; packet[3] = packet.size.toByte()
                byteArrayOf(10, 77, 0, 2).copyInto(packet, 12)
                BotMode.BOT_FAB_IP.split('.').map { it.toInt().toByte() }.toByteArray().copyInto(packet, 16)
                packet[20] = 0x12; packet[21] = 0x34
                packet[22] = (port ushr 8).toByte(); packet[23] = port.toByte()
                packet[25] = (8 + tunneled.size).toByte(); tunneled.copyInto(packet, 28)
                backend.sendTo(packet, port)
                assertArrayEquals(tunneled, engine.receiveBytes())
                engine.sendTo(reply, PeerSlot.ENGINE_PORT)
                val returned = backend.receiveBytes()
                assertArrayEquals(reply, returned.copyOfRange(28, returned.size))
                assertArrayEquals(byteArrayOf(10, 77, 0, 2), returned.copyOfRange(16, 20))
            } finally { PeerSlot.disarm() }
        } } }
    }
}
