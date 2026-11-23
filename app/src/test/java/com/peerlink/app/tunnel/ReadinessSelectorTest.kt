package com.peerlink.app.tunnel

import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ReadinessSelectorTest {
    @Test fun sleepingReaderAcceptsNewRegistrationsAndCleanShutdown() {
        val pool = Executors.newSingleThreadExecutor()
        val running = AtomicBoolean(true)
        val started = CountDownLatch(1)
        val received = CountDownLatch(24)
        val listeners = mutableListOf<DatagramChannel>()
        try {
            ReadinessSelector().use { selector ->
                val reader = pool.submit {
                    started.countDown()
                    val data = ByteBuffer.allocate(8)
                    while (running.get()) {
                        if (selector.select() == 0) continue
                        val it = selector.selectedKeys().iterator()
                        while (it.hasNext()) {
                            val key = it.next()
                            it.remove()
                            data.clear()
                            if ((key.channel() as DatagramChannel).receive(data) != null) {
                                assertEquals(key.attachment(), data.getInt(0))
                                received.countDown()
                            }
                        }
                    }
                }
                try {
                    assertTrue(started.await(2, TimeUnit.SECONDS))
                    DatagramChannel.open().use { sender ->
                        repeat(24) { n ->
                            val socket = DatagramChannel.open()
                            listeners.add(socket)
                            socket.configureBlocking(false)
                            socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                            selector.register(socket, SelectionKey.OP_READ, n)
                            sender.send(ByteBuffer.allocate(4).putInt(0, n), socket.localAddress)
                        }
                    }
                    assertTrue(received.await(3, TimeUnit.SECONDS))
                } finally {
                    running.set(false)
                    selector.wakeup()
                    reader.get(2, TimeUnit.SECONDS)
                }
            }
        } finally {
            listeners.forEach { it.close() }
            pool.shutdownNow()
        }
    }
}
