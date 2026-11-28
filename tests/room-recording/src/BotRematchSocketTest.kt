package com.peerlink.app.bot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class BotRematchSocketTest {
    @Test fun directRoutePreservesShortAndFullSizeDatagramsAcrossBufferReuseAndRejoin() {
        fun socket() = DatagramSocket(InetSocketAddress("127.0.0.1", 0)).apply { soTimeout = 3000 }
        fun transfer(sender: DatagramSocket, port: Int, receiver: DatagramSocket, sourcePort: Int, bytes: ByteArray) {
            sender.send(DatagramPacket(bytes, bytes.size, InetSocketAddress("127.0.0.1", port)))
            val packet = DatagramPacket(ByteArray(65535), 65535)
            receiver.receive(packet)
            assertEquals(sourcePort, packet.port)
            assertArrayEquals(bytes, packet.data.copyOf(packet.length))
        }

        val roomPort = PeerSlot.reserveEndpoint()
        try {
            repeat(2) { round ->
                socket().use { game -> socket().use { bot ->
                    PeerSlot.arm(10L + round, 20, "", "127.0.0.1", game.localPort)
                    PeerSlot.engineAttached = true
                    val enginePort = PeerSlot.ENGINE_PORT
                    transfer(bot, enginePort, game, roomPort, byteArrayOf(7))
                    for (size in listOf(1, 65507, 3, 1207, 37, 79)) {
                        val data = ByteArray(size) { (it + round + size).toByte() }
                        transfer(game, roomPort, bot, enginePort, data)
                        transfer(bot, enginePort, game, roomPort, data)
                    }
                    PeerSlot.finishRound()
                } }
            }
        } finally { PeerSlot.disarm("test_end") }
    }

    @Test fun secondRoundKeepsTheRoomsLocalPortAndForgetsThePreviousOpponent() {
        val port = PeerSlot.reserveEndpoint()
        val socketField = PeerSlot::class.java.getDeclaredField("socket").apply { isAccessible = true }
        val listener = socketField.get(PeerSlot) as java.nio.channels.DatagramChannel
        try {
            PeerSlot.arm(31L, 30L, "", "127.0.0.1", 42232)
            PeerSlot.engineAttached = true
            assertTrue(PeerSlot.ownsListener(port))
            PeerSlot.finishRound()
            assertSame(listener, socketField.get(PeerSlot))
            assertTrue(listener.isOpen)
            assertFalse(PeerSlot.engineAttached)
            assertFalse(PeerSlot.ownsListener(port))
            assertEquals(port, PeerSlot.reserveEndpoint())
            PeerSlot.arm(52L, 30L, "", "127.0.0.1", 42233)
            assertTrue(PeerSlot.ownsListener(port))
            assertEquals(52L, PeerSlot.gameUid)
            assertTrue(PeerSlot.ENGINE_PORT > 0 && PeerSlot.ENGINE_REFLEXIVE_PORT > 0)
        } finally { PeerSlot.disarm("test_end") }
        assertFalse(listener.isOpen)
    }
}
