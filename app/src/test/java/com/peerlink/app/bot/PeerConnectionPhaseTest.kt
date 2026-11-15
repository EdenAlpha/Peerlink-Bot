package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class PeerConnectionPhaseTest {
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
