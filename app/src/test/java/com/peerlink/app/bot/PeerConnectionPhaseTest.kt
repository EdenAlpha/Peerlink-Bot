package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class PeerConnectionPhaseTest {
    @Test fun matchSessionIsOpenedOnlyAfterOriginalNetworkIoIsReady() {
        var checks = 0
        var opens = 0
        assertTrue(PeerConnectionPhase.openAfterNetworkIo(
            running = { true }, transportIoReady = { if (++checks < 3) 0 else 1 },
            openSession = { assertEquals(3, checks); opens++; true },
            pause = { assertEquals(0, opens) },
        ))
        assertEquals(1, opens)
    }

    @Test fun cancelledNetworkIoWaitNeverStartsTheMatchSession() {
        var running = true
        assertFalse(PeerConnectionPhase.openAfterNetworkIo(
            running = { running }, transportIoReady = { 0 },
            openSession = { fail("The native network I/O is not ready"); false },
            pause = { running = false },
        ))
    }

    @Test fun confirmedTransportCanProceedWithBothDataChannelsStillClosed() {
        val phoneState = EngineSnapshot(longArrayOf(1, 1, 4, 0, 0, 0, 0, 0))
        var preparations = 0
        assertTrue(PeerConnectionPhase.awaitTransport(
            running = { true }, snapshot = { phoneState },
            prepareChannels = { preparations++; true }, progress = {},
            pause = { fail("Server progression must not wait for match-data channels") },
        ))
        assertEquals(1, preparations)
        assertFalse(phoneState.connected)
        assertFalse(phoneState.canTestPass)
    }

    @Test fun aSessionWithoutTheOriginalSdkVerdictCannotBeReportedAsConnected() {
        var running = true
        val waiting = EngineSnapshot(longArrayOf(1, 0, 4, 0, 0, 0, 0, 0))
        assertFalse(PeerConnectionPhase.awaitTransport(
            running = { running }, snapshot = { waiting },
            prepareChannels = { true }, progress = {}, pause = { running = false },
        ))
        assertFalse(waiting.transportConnected)
        assertFalse(waiting.canTestPass)
    }

    @Test fun configuredOriginalTransportUsesItsOwnConnectedSessionVerdict() {
        val values = LongArray(23).also { it[0] = 1; it[2] = 4; it[21] = 2 }
        val state = EngineSnapshot(values)
        assertTrue(PeerConnectionPhase.awaitTransport(
            running = { true }, snapshot = { state }, prepareChannels = { true },
            progress = {}, pause = { fail("The old NTL check does not own this transport") },
        ))
        assertFalse(state.connected)
        assertFalse(state.canTestPass)
    }

    @Test fun addressExchangeRunsWhileNativeSessionIsStillConnecting() {
        var exchanged = false
        var prepared = false
        assertTrue(PeerConnectionPhase.awaitTransport(
            running = { true },
            snapshot = { EngineSnapshot(LongArray(23).also { it[2] = if (exchanged) 4 else 1; it[21] = 2 }) },
            exchangeAddresses = { exchanged = true },
            prepareChannels = { prepared = true; true }, progress = {},
            pause = { fail("Connection records must be exchanged before waiting for the session") },
        ))
        assertTrue(prepared)
    }

    @Test fun unansweredPeerCannotConstructDataHandlerOrReachServerConfirmation() {
        var running = true
        val waiting = EngineSnapshot(longArrayOf(1, 0, 1, 1, 1, 0, 0, 0))
        assertFalse(PeerConnectionPhase.awaitTransport(
            running = { running }, snapshot = { waiting },
            prepareChannels = { fail("No connected session exists"); false },
            progress = {}, pause = { running = false },
        ))
    }
}
