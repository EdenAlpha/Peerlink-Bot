package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class RoomMemoryPressureTest {
    @Test fun requiresSustainedPressureAndBacksOffIfThereIsLittleToReclaim() {
        val policy = RoomMemoryPressure()
        assertFalse(policy.shouldReclaim(0, 1402, 3743, false))
        assertFalse(policy.shouldReclaim(4000, 718, 3743, false))
        assertTrue(policy.shouldReclaim(8000, 501, 3743, false))
        policy.attempted(8000, 0)
        assertFalse(policy.shouldReclaim(16000, 490, 3743, true))
        assertTrue(policy.shouldReclaim(48000, 480, 3743, true))
        policy.attempted(48000, 210)
        assertFalse(policy.shouldReclaim(67000, 690, 3743, true))
        assertTrue(policy.shouldReclaim(68000, 680, 3743, true))
    }
    @Test fun healthyReadingResetsTransientPressureAndNoDataDoesNothing() {
        val policy = RoomMemoryPressure()
        assertFalse(policy.shouldReclaim(0, 0, 0, true))
        for (available in listOf(850L, 1550L, 700L))
            assertFalse(policy.shouldReclaim(0, available, 3743, false))
        assertTrue(policy.shouldReclaim(0, 210, 3743, true))
    }
}
