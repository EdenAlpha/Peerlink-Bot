package com.peerlink.app.bot

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** Transport-only test with opaque bytes. It does not simulate a game handshake or prove kickoff. */
@RunWith(AndroidJUnit4::class)
class BotFerryTransportTest {
    private fun socket(port: Int = 0) = DatagramSocket(InetSocketAddress("127.0.0.1", port)).apply { soTimeout = 3000 }
    private fun DatagramSocket.sendTo(bytes: ByteArray, port: Int, ip: String = "127.0.0.1") =
        send(DatagramPacket(bytes, bytes.size, InetSocketAddress(ip, port)))
    private fun DatagramSocket.receiveBytes(): ByteArray {
        val packet = DatagramPacket(ByteArray(4096), 4096); receive(packet)
        return packet.data.copyOf(packet.length)
    }
    @Test fun liveNativeVpnFollowsTwoReplacementRoomPortsAndCarriesBothDirections() {
        BotMode.ensurePair()
        val injected = ArrayBlockingQueue<ByteArray>(8)
        val oldPort = PeerSlot.reserveEndpoint()
        NativeBotTransport(oldPort) { injected.offer(it) }.use { vpn ->
            PeerSlot.disarm("test_room_restart")
            Thread.sleep(1200) // Exercise an already-running socket after its old listener closed.
            socket(oldPort).use { stale ->
                PeerSlot.onArmed = { check(vpn.retarget(it)) }
                try {
                    repeat(2) { round ->
                        val current = PeerSlot.reserveEndpoint()
                        assertNotEquals(oldPort, current)
                        PeerSlot.arm(100, 200, "", "10.210.3.251", 47885)
                        assertTrue("The local probe must reach this room", vpn.verify())
                        PeerSlot.engineAttached = true
                        socket().use { engine ->
                            engine.sendTo(byteArrayOf(7), PeerSlot.ENGINE_REFLEXIVE_PORT, PeerSlot.REFLEXIVE_PEER_IP)
                            val first = requireNotNull(injected.poll(3, TimeUnit.SECONDS))
                            assertArrayEquals(byteArrayOf(7), first.copyOfRange(28, first.size))
                            val payload = byteArrayOf(3, round.toByte(), 5, 7)
                            val carrier = ByteArray(28 + payload.size)
                            carrier[0] = 0x45; carrier[8] = 64; carrier[9] = 17
                            carrier[2] = (carrier.size ushr 8).toByte(); carrier[3] = carrier.size.toByte()
                            byteArrayOf(10, 210.toByte(), 3, 251.toByte()).copyInto(carrier, 12)
                            byteArrayOf(197.toByte(), 210.toByte(), 53, 2).copyInto(carrier, 16)
                            carrier[20] = (47885 ushr 8).toByte(); carrier[21] = 47885.toByte()
                            carrier[22] = (current ushr 8).toByte(); carrier[23] = current.toByte()
                            carrier[24] = 0; carrier[25] = (8 + payload.size).toByte(); payload.copyInto(carrier, 28)
                            vpn.send(carrier)
                            assertArrayEquals(payload, engine.receiveBytes())
                            val reply = byteArrayOf(8, round.toByte(), 10)
                            engine.sendTo(reply, PeerSlot.ENGINE_REFLEXIVE_PORT, PeerSlot.REFLEXIVE_PEER_IP)
                            val returned = requireNotNull(injected.poll(3, TimeUnit.SECONDS))
                            assertArrayEquals(reply, returned.copyOfRange(28, returned.size))
                            assertEquals(1L, PeerSlot.gameTraffic().fromGame)
                        }
                        PeerSlot.disarm("test_next_room")
                    }
                    stale.soTimeout = 200
                    try { stale.receiveBytes(); fail("The old room received traffic after retargeting") }
                    catch (_: SocketTimeoutException) { }
                } finally { PeerSlot.onArmed = null; PeerSlot.disarm() }
            }
        }
    }

    @Test fun directProxyFromAnotherLocalMobileAddressIsAccepted() {
        val source = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        org.junit.Assume.assumeNotNull(source)
        DatagramSocket(InetSocketAddress(source, 0)).use { game -> socket().use { engine ->
            game.soTimeout = 3000
            try {
                val port = PeerSlot.reserveEndpoint()
                PeerSlot.arm(100, 200, "", "10.210.3.251", 47885)
                PeerSlot.engineAttached = true
                val bytes = byteArrayOf(2, 4, 6, 8)
                engine.sendTo(byteArrayOf(7), PeerSlot.ENGINE_PORT)
                val learned = System.nanoTime() + 1_000_000_000L
                while (!PeerSlot.engineHeard && System.nanoTime() < learned) Thread.sleep(5)
                assertTrue(PeerSlot.engineHeard)
                game.sendTo(bytes, port, requireNotNull(source).hostAddress!!)
                assertArrayEquals(bytes, engine.receiveBytes())
                engine.sendTo(byteArrayOf(9), PeerSlot.ENGINE_PORT)
                assertArrayEquals(byteArrayOf(9), game.receiveBytes())
                assertEquals(1L, PeerSlot.gameTraffic().fromGame)
            } finally { PeerSlot.disarm() }
        } }
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
                assertArrayEquals(byteArrayOf(7), game.receiveBytes())

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
                engine.sendTo(reply, PeerSlot.ENGINE_REFLEXIVE_PORT, PeerSlot.REFLEXIVE_PEER_IP)
                val returned = backend.receiveBytes()
                assertArrayEquals(reply, returned.copyOfRange(28, returned.size))
                assertArrayEquals(byteArrayOf(10, 77, 0, 2), returned.copyOfRange(16, 20))
                // A tunneled packet must not change the other candidate's
                // return route, even when both candidates are active.
                engine.sendTo(reply, PeerSlot.ENGINE_PORT)
                assertArrayEquals(reply, game.receiveBytes())
                val deadline = System.nanoTime() + 1_000_000_000L
                while (PeerSlot.gameTraffic().toGame < 4 && System.nanoTime() < deadline) Thread.sleep(10)
                val traffic = PeerSlot.gameTraffic()
                assertEquals("Both game routes are counted once", 2L, traffic.fromGame)
                assertEquals("Only forwarded bot replies are counted", 4L, traffic.toGame)
            } finally { PeerSlot.disarm() }
        } } }
    }
}
