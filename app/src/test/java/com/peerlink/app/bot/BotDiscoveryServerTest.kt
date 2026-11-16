package com.peerlink.app.bot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Inet4Address
import java.net.NetworkInterface
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

class BotDiscoveryServerTest {
    private fun request(flags: Int = 0, targetPort: Int = 0): ByteArray {
        val attributes = mutableListOf<ByteArray>()
        if (flags != 0) attributes += byteArrayOf(0, 3, 0, 4, 0, 0, 0, flags.toByte())
        if (targetPort != 0) attributes += byteArrayOf(0, 0x27, 0, 4,
            (targetPort ushr 8).toByte(), targetPort.toByte(), 0, 0)
        attributes += byteArrayOf(0xf0.toByte(), 0, 0, 8) + ByteArray(8)
        val body = attributes.fold(ByteArray(0)) { a, b -> a + b }
        return byteArrayOf(0, 1, (body.size ushr 8).toByte(), body.size.toByte(),
            0x21, 0x12, 0xa4.toByte(), 0x42) + ByteArray(12) { (it + 1).toByte() } + body
    }
    private fun receive(socket: DatagramSocket): DatagramPacket =
        DatagramPacket(ByteArray(4096), 4096).also { socket.receive(it) }

    @Test fun answersRealLoopbackDatagramsAndAlternateSourceRequests() {
        BotDiscoveryServer { }.use { server ->
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { client ->
                client.soTimeout = 1500
                val input = request()
                client.send(DatagramPacket(input, input.size, InetSocketAddress(BotDiscoveryServer.ADDRESS, 3478)))
                val answer = receive(client)
                assertEquals("127.0.0.80", answer.address.hostAddress)
                assertEquals(3478, answer.port)
                assertArrayEquals(input.copyOfRange(8, 20), answer.data.copyOfRange(8, 20))
                assertEquals(1, answer.data[0].toInt())
                assertEquals(1, answer.data[1].toInt())
                // First response attribute is XOR-MAPPED-ADDRESS. It must
                // retain this real SDK socket's port, even with RESPONSE-PORT.
                val mappedPort = ((answer.data[26].toInt() and 255) shl 8) or
                    (answer.data[27].toInt() and 255)
                assertEquals(client.localPort, mappedPort xor 0x2112)
                DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { destination ->
                    destination.soTimeout = 1500
                    val changed = request(6, destination.localPort)
                    client.send(DatagramPacket(changed, changed.size,
                        InetSocketAddress(BotDiscoveryServer.ADDRESS, 3478)))
                    val changedAnswer = receive(destination)
                    assertEquals("127.0.0.81", changedAnswer.address.hostAddress)
                    assertEquals(3479, changedAnswer.port)
                    assertArrayEquals(changed.copyOfRange(8, 20), changedAnswer.data.copyOfRange(8, 20))
                }
                // Receiving a datagram can win the race with the sender's
                // post-send counter update. Wait for that observation, while
                // preserving the real packet/source checks above.
                val deadline = System.nanoTime() + 1_000_000_000L
                while (!server.diagnostics().contains("localStunReplies=2") &&
                    System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(server.diagnostics().contains("localStunReplies=2"))
            }
        }
        // All fixed source endpoints are released when the engine closes.
        BotDiscoveryServer { }.close()
    }

    @Test fun acceptsAnSdkSocketBoundToThePhonesPhysicalAddress() {
        val physical = NetworkInterface.getNetworkInterfaces().toList()
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        assumeNotNull(physical)
        BotDiscoveryServer { }.use { server ->
            DatagramSocket(InetSocketAddress(physical, 0)).use { client ->
                client.soTimeout = 1500
                val input = request()
                client.send(DatagramPacket(input, input.size,
                    InetSocketAddress(BotDiscoveryServer.ADDRESS, 3478)))
                val answer = receive(client)
                assertEquals("127.0.0.80", answer.address.hostAddress)
                assertArrayEquals(input.copyOfRange(8, 20), answer.data.copyOfRange(8, 20))
                val deadline = System.nanoTime() + 1_000_000_000L
                while (!server.diagnostics().contains("localStunReplies=1") &&
                    System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue(server.diagnostics().contains("localStunReplies=1"))
            }
        }
    }
}
