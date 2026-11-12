package com.peerlink.app.bot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GateCodecTest {
    @Test
    fun userAgentPlacesTimestampAfterDeviceModel() {
        val before = System.currentTimeMillis() / 1000L
        val header = GateCodec.userAgent(
            clientVersion = "6.1.1",
            model = "TestDevice",
            build = "11.1.1",
        )
        val after = System.currentTimeMillis() / 1000L
        val fields = header.substringAfter('(').substringBeforeLast(')')
            .split(';').map(String::trim)

        assertEquals(8, fields.size)
        val timestamp = fields[3].toLong()
        assertTrue(timestamp in before..after)
        assertEquals(
            listOf("ANDROID 14", "0", "TestDevice", timestamp.toString(), "6.1.1", "", "11.1.1", ""),
            fields,
        )
    }
}
