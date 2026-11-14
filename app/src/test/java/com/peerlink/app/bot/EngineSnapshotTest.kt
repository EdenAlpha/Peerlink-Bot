package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class EngineSnapshotTest {
    private fun state() = LongArray(19).apply {
        this[2] = 4; this[3] = 4; this[4] = 4
        this[5] = 5; this[6] = 1
        this[8] = 17; this[9] = 54; this[10] = 22
        this[11] = 1; this[16] = 1; this[17] = 1
    }
    @Test fun connectionAndTeamsAloneDoNotAuthorizeGameplayInputs() {
        val values = state().apply { this[8] = 0; this[9] = 0; this[10] = 0; this[11] = 0 }
        val snapshot = EngineSnapshot(values)
        assertTrue(snapshot.connected)
        assertTrue(snapshot.metadataReady)
        assertFalse(snapshot.canTestPass)
    }
    @Test fun bothOriginalDataChannelsAndControllerSettingsAreRequired() {
        assertTrue(EngineSnapshot(state()).canTestPass)
        for (field in listOf(3, 4, 16, 17)) {
            assertFalse(EngineSnapshot(state().apply { this[field] = 0 }).canTestPass)
        }
    }
    @Test fun offPitchTransitionSentinelsDoNotBecomeLivePlay() {
        val snapshot = EngineSnapshot(state().apply { this[11] = 0; this[14] = 100000 })
        assertTrue(snapshot.matchConstructed)
        assertFalse(snapshot.canTestPass)
    }
}
