package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class SetupMemorySamplesTest {
    @Test fun capturesObservedMenuJumpAndStops() {
        val samples = SetupMemorySamples()
        assertNull(samples.next(false, false, 0, 560_000))
        assertEquals("baseline", samples.next(true, false, 1000, 580_000))
        assertNull(samples.next(true, true, 2000, 581_000))
        assertEquals("setup_loading", samples.next(true, true, 6500, 786_000))
        assertEquals("setup_followup", samples.next(true, true, 11500, 812_476))
        assertNull(samples.next(true, true, 17500, 920_000))
    }

    @Test fun flatRunGetsOnlyThreeSamplesEvenWhenSetupIsFirstObservedPhase() {
        val samples = SetupMemorySamples()
        assertEquals("baseline", samples.next(false, true, 1000, 580_000))
        assertNull(samples.next(false, true, 5500, 580_000))
        assertEquals("setup_loading", samples.next(false, true, 6050, 580_000))
        assertEquals("setup_followup", samples.next(false, true, 12000, 580_000))
        assertNull(samples.next(true, true, 20000, 990_000))
    }
}
