package com.peerlink.app.bot

import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class NativeTurnExchangeTest {
    private val phoneRecord = Base64.getDecoder().decode("AOf1FwIECgAAAAAAAAAAAAAAAAA=")
    private fun reply(bytes: ByteArray) = mapOf(
        "result" to "NOERR", "address" to Base64.getEncoder().encodeToString(bytes),
        "address_data_size" to bytes.size,
    )

    @Test fun carriesBinaryRecordsFromBothGetAndWatchWithoutInterpretingThem() {
        val local = byteArrayOf(0, 0xff.toByte(), 12, 0, 34)
        val nextPeer = phoneRecord.copyOf().also { it[1] = 4 }
        val accepted = ArrayList<ByteArray>()
        var sent = 0
        val exchange = NativeTurnExchange(
            localAddress = { local }, acceptPeer = { accepted.add(it); true },
            send = { encoded, size ->
                assertArrayEquals(local, Base64.getDecoder().decode(encoded))
                assertEquals(local.size, size)
                sent++
                mapOf("result" to "NOERR")
            }, get = { reply(phoneRecord) }, watch = { reply(nextPeer) }, log = {},
        )
        exchange.pump()
        assertEquals(1, sent)
        assertEquals(2, accepted.size)
        assertArrayEquals(phoneRecord, accepted[0])
        assertArrayEquals(nextPeer, accepted[1])
    }

    @Test fun retriesFailedAnnouncementAndDeduplicatesPeerRecords() {
        var now = 0L
        var sends = 0
        var inputs = 0
        val exchange = NativeTurnExchange(
            localAddress = { phoneRecord }, acceptPeer = { inputs++; true },
            send = { _, _ -> mapOf("result" to if (++sends == 1) "_NET_FAIL" else "NOERR") },
            get = { reply(phoneRecord) }, watch = { reply(phoneRecord) }, log = {}, clock = { now },
        )
        exchange.pump()
        now = 1000
        exchange.pump()
        now = 2000
        exchange.pump()
        assertEquals(2, sends)
        assertEquals(1, inputs)
    }

    @Test fun rejectsTruncatedRecordBeforeCallingTheNativeInput() {
        val exchange = NativeTurnExchange(
            localAddress = { null }, acceptPeer = { fail("Truncated data must not reach the engine"); false },
            send = { _, _ -> error("No local address") },
            get = { reply(phoneRecord).toMutableMap().also { it["address_data_size"] = 19 } },
            watch = { emptyMap<String, Any>() }, log = {},
        )
        assertThrows(IllegalArgumentException::class.java) { exchange.pump() }
    }
}
