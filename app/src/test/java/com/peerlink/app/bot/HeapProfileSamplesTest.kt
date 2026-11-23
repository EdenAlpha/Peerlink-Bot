package com.peerlink.app.bot

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class HeapProfileSamplesTest {
    private fun varint(number: Int) = ByteArrayOutputStream().apply {
        var value = number
        while (value >= 128) { write(128 or (value and 127)); value = value ushr 7 }
        write(value)
    }.toByteArray()
    private fun value(field: Int, n: Int) = varint(field * 8) + varint(n)
    private fun message(field: Int, bytes: ByteArray) = varint(field * 8 + 2) + varint(bytes.size) + bytes
    private fun packet(writer: Int, data: ByteArray) = message(1, value(10, writer) + data)
    private val sample = message(37, message(5, message(2, value(1, 3) + value(2, 65536)) + value(1, 71)))
    private val stack = message(7, value(1, 3) + message(2, varint(44)))
    private val frame = message(6, value(1, 44) + value(3, 2) + value(4, 13000))
    private val interned = message(12, stack + frame)
    private fun inspect(trace: ByteArray, pid: Int = 71) = HeapProfileSamples.inspect(ByteArrayInputStream(trace), pid)
    private fun found(trace: ByteArray, pid: Int = 71) = inspect(trace, pid).let { it.hasStack && it.problem() == null }

    @Test fun requiresTheBotsAllocationAndRecordedCodeLocationEvenWhenDefinitionsArriveLater() {
        val trace = packet(7, sample) + packet(7, interned)
        assertTrue(found(trace))
        assertFalse(found(trace, 72))
        assertFalse(found(packet(7, sample) + packet(7, message(12, stack))))
        assertFalse(found(packet(7, interned)))
    }
    @Test fun keepsWritersAndIncrementalGenerationsSeparate() {
        assertFalse(found(packet(9, interned) + packet(7, sample)))
        assertFalse(found(packet(7, interned) + packet(7, value(13, 1) + sample)))
    }
    @Test fun ignoresIncompleteLastPacketInLiveSnapshot() {
        val definitions = packet(7, interned)
        val complete = packet(7, sample)
        assertFalse(found(definitions + complete.copyOf(complete.size - 2)))
        assertTrue(found(definitions + complete + byteArrayOf(10, 74, 1)))
    }
    @Test fun aLostProfilerCannotPassUsingItsEarlierMappedAllocations() {
        val good = packet(7, interned + sample)
        val loss = value(6, 1) + value(7, 1) + value(14, 1)
        fun stopped(pid: Int) = packet(7, message(37, message(5, loss + value(1, pid))))
        val failed = inspect(good + stopped(71))
        assertTrue(failed.hasStack)
        assertEquals("buffer_overrun", failed.problem())
        assertFalse(found(good + stopped(71)))
        assertTrue(found(good + stopped(72)))
        val sameDump = message(5, value(1, 71) + message(2, value(1, 3) + value(2, 1024)) + loss)
        assertFalse(found(packet(7, interned + message(37, sameDump))))
    }
    @Test fun configurationTargetsOnlyTheBotAndDoesNotBlockItsAllocator() {
        val text = BotHeapRecording.config("com.peerlink.app")
        assertTrue(text.contains("process_cmdline: \"com.peerlink.app:botengine\""))
        assertTrue(text.contains("block_client: false"))
        assertTrue(text.contains("no_startup: true"))
        assertTrue(text.contains("shmem_size_bytes: 8388608"))
        assertTrue(text.contains("adaptive_sampling_shmem_threshold: 4194304"))
        assertTrue(text.contains("flush_period_ms: 1000"))
        assertFalse(text.contains("all: true"))
        assertThrows(IllegalArgumentException::class.java) { BotHeapRecording.config("com.peerlink.app'") }
    }
}
