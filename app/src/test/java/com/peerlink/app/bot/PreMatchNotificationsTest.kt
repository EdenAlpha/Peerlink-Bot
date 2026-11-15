package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class PreMatchNotificationsTest {
    @Test fun matchesTheIndependentlyCapturedPrePlayWireBody() {
        val captured = requireNotNull(javaClass.getResourceAsStream("/captured-datasync.msgpack"))
            .use { it.readBytes() }
        assertArrayEquals(captured, GateCodec.pack(PreMatchNotifications.dataSync(23)))
    }

    @Test fun refusesAMissingServerGameId() {
        for (id in listOf(0L, -1L))
            assertThrows(IllegalArgumentException::class.java) { PreMatchNotifications.dataSync(id) }
    }
}
