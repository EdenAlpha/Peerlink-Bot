package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class EngineSnapshotTest {
    private fun state() = LongArray(30).apply {
        this[2] = 4; this[3] = 4; this[4] = 4
        this[5] = 5; this[6] = 1
        this[8] = 17; this[9] = 54; this[10] = 22
        this[11] = 1; this[16] = 1; this[17] = 1
        this[29] = 1
    }
    @Test fun connectionAndTeamsAloneDoNotAuthorizeGameplayInputs() {
        val values = state().apply { this[8] = 0; this[9] = 0; this[10] = 0; this[11] = 0 }
        val snapshot = EngineSnapshot(values)
        assertTrue(snapshot.connected)
        assertTrue(snapshot.metadataReady)
        assertFalse(snapshot.canTestPass)
    }
    @Test fun bothOriginalDataChannelsAndEnabledPresentControllerSettingsAreRequired() {
        assertTrue(EngineSnapshot(state()).canTestPass)
        for (field in listOf(3, 4, 16, 17)) {
            assertFalse(EngineSnapshot(state().apply { this[field] = 0 }).canTestPass)
        }
    }
    @Test fun absentOptionalSettingsDoNotDisableAnActiveOriginalController() {
        // Recorded Android/QEMU match: source is enabled, optional settings absent.
        val values = state().apply { this[17] = -1 }
        assertTrue(EngineSnapshot(values).canTestPass)
        for (field in listOf(2, 3, 4, 8, 9, 10, 11, 16)) {
            assertFalse(EngineSnapshot(values.copyOf().apply { this[field] = 0 }).canTestPass)
        }
        assertFalse(EngineSnapshot(values.copyOf().apply { this[17] = -2 }).canTestPass)
    }
    @Test fun offPitchTransitionSentinelsDoNotBecomeLivePlay() {
        val snapshot = EngineSnapshot(state().apply { this[11] = 0; this[14] = 100000 })
        assertTrue(snapshot.matchConstructed)
        assertFalse(snapshot.canTestPass)
    }
    @Test fun gameThreadRetainsItsBudgetDuringRecordedTransitionsAndReleasesItAtEnd() {
        val fields = state().copyOf(35)
        for (outer in listOf(17L, 20L, 17L, 21L)) {
            fields[8] = outer
            assertTrue(EngineSnapshot(fields).needsMatchCpu(true, false))
        }
        assertFalse(EngineSnapshot(fields).needsMatchCpu(false, false))
        assertFalse(EngineSnapshot(fields).needsMatchCpu(true, true))
        fields[2] = 0
        assertFalse(EngineSnapshot(fields).needsMatchCpu(true, false))
        fields[2] = 4
        fields[34] = 1
        assertFalse(EngineSnapshot(fields).needsMatchCpu(true, false))
    }
    @Test fun recordedLoadedPitchStillWaitingAtFrameZeroCannotAuthorizePass() {
        // Physical 5.0.23: outer17/core54, 22 players, ball at centre and
        // controllers enabled, but commandFrame0/missingMask2/tickGate0.
        val snapshot = EngineSnapshot(state().apply { this[29] = 0 })
        assertTrue(snapshot.matchConstructed)
        assertTrue(snapshot.ballOnPitch)
        assertFalse(snapshot.canTestPass)
        assertEquals("Waiting for the match to be ready", snapshot.status())
    }
    @Test fun aTransientInputWaitAfterKickoffKeepsTheGameplayStatus() {
        val snapshot = EngineSnapshot(state().apply { this[29] = 0 })
        assertFalse(snapshot.canTestPass)
        assertEquals("Match in progress", snapshot.status(afterKickoff = true))
    }
    @Test fun missingReadinessFromAnOlderOrIncompleteSnapshotCannotAuthorizePass() {
        assertFalse(EngineSnapshot(state().copyOf(29)).canTestPass)
    }
    @Test fun aConnectedHalftimeDoesNotAuthorizePassOrLookLikeMatchClosure() {
        val values = state().apply { this[8] = 21; this[29] = 0 }
        val snapshot = EngineSnapshot(values)
        assertFalse(snapshot.canTestPass)
        assertEquals("Match paused — waiting for play to resume", snapshot.status(afterKickoff = true))
    }
    @Test fun continueIsAvailableOnlyAtAConfirmedHalftimeMenu() {
        val halftime = state().copyOf(33).apply { this[8] = 21 }
        for (menu in 0L..6L) {
            halftime[32] = menu
            val snapshot = EngineSnapshot(halftime)
            assertEquals(menu == 3L || menu == 5L, snapshot.halftimeCanConfirm)
            assertFalse(snapshot.canTestPass)
        }
        halftime[32] = 5
        assertFalse(EngineSnapshot(halftime.copyOf(32)).halftimeCanConfirm)
        assertFalse(EngineSnapshot(halftime.copyOf().apply { this[8] = 17 }).halftimeCanConfirm)
        assertFalse(EngineSnapshot(halftime.copyOf().apply { this[2] = 0 }).halftimeCanConfirm)
    }
}
