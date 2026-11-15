package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class NativeConnectionTraceTest {
    private fun connected() = LongArray(29).apply {
        this[2] = 4; this[3] = 4; this[4] = 4
        this[5] = 5; this[6] = 1; this[23] = 0; this[24] = 1
    }

    @Test fun recordsFirstNativeErrorBeforeLaterCleanupAndDoesNotBlameThePeer() {
        val trace = NativeConnectionTrace()
        val values = connected()
        trace.sample(EngineSnapshot(values), 1000)
        values[2] = 5; values[3] = 6; values[4] = 6; values[5] = 6
        values[6] = 0; values[7] = 1; values[23] = 8; values[24] = 4
        val first = trace.sample(EngineSnapshot(values), 1100).single { it.firstFailure }
        assertTrue(first.text.contains("previousSampleGapMs=100"))
        assertTrue(first.text.contains("disconnectInitiator=unresolved"))
        assertTrue(first.text.contains("checksum comparison failed"))
        assertTrue(first.text.contains("previous={session=4 ch14=4 ch15=4"))
        values.fill(0)
        trace.sample(EngineSnapshot(values), 1200)
        values[7] = 1; values[23] = 1
        assertFalse(trace.sample(EngineSnapshot(values), 1300).any { it.firstFailure })
    }

    @Test fun absentHandlerAndZeroedStartupStateAreNotReportedAsFailure() {
        val trace = NativeConnectionTrace()
        assertFalse(trace.sample(EngineSnapshot(LongArray(29)), 0).any { it.firstFailure })
        val opening = connected().apply { this[3] = 3; this[4] = 0; this[5] = 0; this[6] = 0 }
        assertFalse(trace.sample(EngineSnapshot(opening), 100).any { it.firstFailure })
    }

    @Test fun frameChangesDoNotFloodLogsButHeartbeatProvesMonitoringContinues() {
        val trace = NativeConnectionTrace()
        val values = connected()
        trace.sample(EngineSnapshot(values), 1000)
        repeat(49) { values[20]++; assertTrue(trace.sample(EngineSnapshot(values), 1100L + it * 100).isEmpty()) }
        assertEquals(1, trace.sample(EngineSnapshot(values), 6000).size)
    }

    @Test fun genericTransportErrorAndUnmappedCodesRemainExplicitlyUncertain() {
        val values = connected().apply { this[7] = 1; this[23] = 1 }
        assertTrue(EngineSnapshot(values).nativeFailureReason.contains("initiating side unknown"))
        values[23] = 123
        assertEquals("unmapped native reason 123", EngineSnapshot(values).nativeFailureReason)
    }
}
