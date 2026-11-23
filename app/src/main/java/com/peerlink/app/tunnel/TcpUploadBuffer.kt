package com.peerlink.app.tunnel

import java.nio.ByteBuffer
import java.nio.channels.WritableByteChannel

/** Bytes already acknowledged to the device must survive reuse of its packet buffer. */
internal class TcpUploadBuffer(private val capacity: Int = 65535) {
    private var pending: ByteBuffer? = null
    val hasPending get() = pending != null

    fun accept(channel: WritableByteChannel, expected: Long, sequence: Long, data: ByteBuffer): Long {
        val overlap = (expected - sequence).toInt() // TCP sequence numbers wrap at 32 bits.
        if (overlap < 0 || overlap >= data.remaining()) return expected
        data.position(data.position() + overlap)
        val start = data.position()
        if (pending == null) channel.write(data)
        if (data.hasRemaining()) {
            val buffer = pending ?: ByteBuffer.allocate(capacity).apply { limit(0) }
            val unread = buffer.remaining()
            val count = minOf(data.remaining(), capacity - unread)
            if (count > 0) {
                if (capacity - buffer.limit() < count) {
                    buffer.compact()
                    buffer.flip()
                }
                val first = buffer.position()
                val end = buffer.limit()
                buffer.limit(capacity)
                buffer.position(end)
                val oldLimit = data.limit()
                data.limit(data.position() + count)
                buffer.put(data)
                data.limit(oldLimit)
                buffer.flip()
                buffer.position(first)
                pending = buffer
            }
        }
        return (expected + data.position() - start) and 0xffffffffL
    }

    fun onWritable(channel: WritableByteChannel) {
        val buffer = pending ?: return
        channel.write(buffer)
        if (!buffer.hasRemaining()) pending = null
    }
}
