package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class PeerConnectionPhaseTest {
    @Test fun phaseReportCannotPrecedeNativeMetadataSynchronization() {
        var synchronized = false
        val values = LongArray(29).apply { this[2] = 4; this[3] = 4; this[4] = 4 }
        assertTrue(PeerConnectionPhase.awaitMetadata(
            running = { true },
            snapshot = { EngineSnapshot(values.copyOf().apply {
                this[5] = if (synchronized) 5 else 3; this[6] = 1
            }) },
            pause = { synchronized = true },
        ))
        assertTrue(synchronized)
    }

    @Test fun cancelledMetadataWaitCannotReportCompletion() {
        var running = true
        assertFalse(PeerConnectionPhase.awaitMetadata(
            running = { running }, snapshot = { EngineSnapshot(LongArray(29)) },
            pause = { running = false },
        ))
    }

    @Test fun nativeFailureIsReportedBeforeTheGenericNinetySecondTimeout() {
        val failed = EngineSnapshot(LongArray(29).apply {
            this[2] = 5; this[3] = 6; this[4] = 6; this[5] = 6
            this[7] = 1; this[23] = 8; this[24] = 4
        })
        val error = assertThrows(IllegalStateException::class.java) {
            PeerConnectionPhase.awaitTransport(
                running = { true }, snapshot = { failed }, progress = {},
                prepareChannels = { fail("Failed native state cannot start a new model"); false },
                pause = { fail("Do not hide the native reason behind a later timeout") },
            )
        }
        assertTrue(error.message!!.contains("database checksum comparison failed"))
    }

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
