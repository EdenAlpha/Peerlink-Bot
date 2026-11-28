package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class RoomMemoryPressureTest {
    @Test fun skipsRoutineCachedSweepOnceThePitchLoads() {
        val policy = RoomMemoryPressure()
        val totalMiB = 3743L
        assertFalse(policy.shouldReclaim(0, 550, totalMiB, false))
        assertTrue(policy.shouldReclaim(4_000, 544, totalMiB, false))
        policy.attempted(4_000, 18)

        // Repeated readings at the RAM level in the recorded match.
        assertFalse(policy.shouldReclaim(124_000, 470, totalMiB, false, pitchLoaded = true))
        assertFalse(policy.shouldReclaim(128_000, 467, totalMiB, false, pitchLoaded = true))
        assertTrue(policy.shouldReclaim(132_000, 210, totalMiB, true, pitchLoaded = true))
        policy.attempted(132_000, 0)
        assertFalse(policy.shouldReclaim(136_000, 210, totalMiB, true, pitchLoaded = true))
    }

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

    @Test fun suddenMemoryLossDuringLoadingCanInterruptBackoffOnce() {
        val policy = RoomMemoryPressure()
        assertFalse(policy.shouldReclaim(0, 680, 3743, false))
        assertTrue(policy.shouldReclaim(4000, 606, 3743, false))
        policy.attempted(4000, 0)
        assertFalse(policy.shouldReclaim(8000, 850, 3743, false))
        assertTrue(policy.shouldReclaim(44000, 637, 3743, false))
        policy.attempted(44000, 0)
        assertFalse(policy.shouldReclaim(48000, 390, 3743, false))
        assertFalse(policy.shouldReclaim(60000, 390, 3743, false))
        assertTrue(policy.shouldReclaim(64000, 390, 3743, false))
        policy.attempted(64000, 0)
        assertFalse(policy.shouldReclaim(90000, 380, 3743, true))
        assertFalse(policy.shouldReclaim(100000, 1300, 3743, false))
        assertFalse(policy.shouldReclaim(104000, 390, 3743, false))
        assertTrue(policy.shouldReclaim(108000, 390, 3743, false))
    }
}
