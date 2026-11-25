package com.peerlink.app.bot

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineNativeCrashTraceTest {
    // Signal is field 10 of Tombstone. Its address-present flag is field 8 and
    // its fault address is field 9. Proto3 may omit an address equal to zero.
    private fun signal(vararg fields: Int): String {
        val bytes = byteArrayOf(0x52, fields.size.toByte()) + fields.map(Int::toByte).toByteArray()
        return EngineNativeCrashTrace.read(bytes.inputStream()).lineSequence().elementAt(1)
    }

    @Test fun reportsSmallOffsetRegardlessOfFieldOrder() {
        assertEquals("signalNumber=11 faultNearNull=true lowOffset=24", signal(8, 11, 72, 24, 64, 1))
    }

    @Test fun distinguishesZeroFromMissingOrUnknown() {
        assertEquals("faultNearNull=true lowOffset=0", signal(64, 1))
        assertEquals("faultNearNull=unavailable", signal())
        assertEquals("faultNearNull=unavailable", signal(72, 24, 64, 0))
    }

    @Test fun omitsAddressesOutsideTheLowPage() {
        assertEquals("faultNearNull=false", signal(64, 1, 72, 0x80, 0x20))
        assertEquals("faultNearNull=false", signal(64, 1, 72, 0xb4, 0x24))
    }
}
