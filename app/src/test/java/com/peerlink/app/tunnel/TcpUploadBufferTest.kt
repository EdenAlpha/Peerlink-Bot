package com.peerlink.app.tunnel

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.nio.channels.WritableByteChannel
import org.junit.Assert.*
import org.junit.Test

class TcpUploadBufferTest {
    private class Socket(var capacity: Int) : WritableByteChannel {
        val output = ByteArrayOutputStream()
        var writes = 0
        override fun write(src: ByteBuffer): Int {
            writes++
            val accepted = minOf(capacity, src.remaining())
            repeat(accepted) { output.write(src.get().toInt()) }
            return accepted
        }
        override fun isOpen() = true
        override fun close() = Unit
    }

    @Test fun zeroWriteReturnsImmediatelyAndResumesInOrderOnReadiness() {
        val socket = Socket(0)
        val upload = TcpUploadBuffer(capacity = 4)
        val source = byteArrayOf(1, 2, 3, 4, 5, 6)
        assertEquals(104L, upload.accept(socket, 100, 100, ByteBuffer.wrap(source)))
        source.fill(99)
        assertEquals(1, socket.writes)
        upload.onWritable(socket)
        assertEquals(2, socket.writes)
        socket.capacity = 2
        upload.onWritable(socket)
        val retransmit = byteArrayOf(3, 4, 5, 6)
        assertEquals(106L, upload.accept(socket, 104, 102, ByteBuffer.wrap(retransmit)))
        assertEquals(3, socket.writes)
        upload.onWritable(socket)
        upload.onWritable(socket)
        assertFalse(upload.hasPending)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6), socket.output.toByteArray())
    }

    @Test fun aBackedUpLoopbackSocketDeliversTheCompleteStream() {
        val bytes = ByteArray(1024 * 1024).also { java.util.Random(42).nextBytes(it) }
        val pool = Executors.newSingleThreadExecutor()
        try {
            ServerSocketChannel.open().use { server ->
                server.bind(InetSocketAddress("127.0.0.1", 0))
                SocketChannel.open(server.localAddress).use { client ->
                    server.accept().use { remote ->
                        client.socket().sendBufferSize = 4096
                        client.configureBlocking(false)
                        remote.socket().soTimeout = 5000
                        val upload = TcpUploadBuffer(4096)
                        var accepted = 0L
                        fun sendSome() {
                            val offset = accepted.toInt()
                            val length = minOf(8192, bytes.size - offset)
                            accepted = upload.accept(client, accepted, accepted, ByteBuffer.wrap(bytes, offset, length))
                        }
                        while (!upload.hasPending && accepted < bytes.size) sendSome()
                        assertTrue("the paused receiver must exercise a partial write", upload.hasPending)
                        val reader = pool.submit<ByteArray> { remote.socket().getInputStream().readBytes() }
                        Selector.open().use { selector ->
                            client.register(selector, SelectionKey.OP_WRITE)
                            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                            while (accepted < bytes.size || upload.hasPending) {
                                assertTrue("socket failed to drain", System.nanoTime() < deadline)
                                if (upload.hasPending) {
                                    if (selector.select(250) == 0) continue
                                    selector.selectedKeys().clear()
                                    upload.onWritable(client)
                                }
                                if (accepted < bytes.size) sendSome()
                            }
                        }
                        client.shutdownOutput()
                        assertArrayEquals(bytes, reader.get(5, TimeUnit.SECONDS))
                    }
                }
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun partialWritesDuplicatesGapsAndSequenceWrapPreserveEveryByteOnce() {
        val socket = Socket(1)
        val upload = TcpUploadBuffer(2)
        fun offer(expected: Long, seq: Long, vararg bytes: Byte): Long =
            upload.accept(socket, expected, seq, ByteBuffer.wrap(bytes))
        assertEquals(1L, offer(0xfffffffeL, 0xfffffffeL, 1, 2, 3, 4))
        assertEquals(1L, offer(1, 3, 8, 9))
        assertEquals(1L, offer(1, 0xffffffffL, 2, 3))
        upload.onWritable(socket)
        assertEquals(2L, offer(1, 0, 3, 4))
        while (upload.hasPending) upload.onWritable(socket)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), socket.output.toByteArray())
    }
}
