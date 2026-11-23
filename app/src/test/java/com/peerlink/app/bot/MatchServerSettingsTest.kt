package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class MatchServerSettingsTest {
    @Test fun obeysBothExplicitServerPolicies() {
        assertTrue(MatchServerSettings.fromResponse(mapOf("result" to "NOERR", "is_revision_check" to "YES")).revisionCheck)
        assertFalse(MatchServerSettings.fromResponse(mapOf("result" to "NOERR", "is_revision_check" to "NO")).revisionCheck)
    }

    @Test fun preservesBothOriginalOptionFields() {
        val settings = MatchServerSettings.fromResponse(mapOf(
            "result" to "NOERR", "is_revision_check" to "YES",
            "proto_opt" to "eyJkZWZhdWx0Ijp7fX0=", "onsys_opt" to "eyJkZWZhdWx0Ijp7InRpbWVvdXRfc2V0dGluZ3MiOnt9fX0=",
            "slowdown_judgment_threshold_list" to listOf(51L, 25L, 17L),
        ))
        assertEquals("eyJkZWZhdWx0Ijp7fX0=", settings.protocolOptions)
        assertEquals("eyJkZWZhdWx0Ijp7InRpbWVvdXRfc2V0dGluZ3MiOnt9fX0=", settings.onlineOptions)
        assertEquals(listOf(51, 25, 17), settings.slowdownThresholds)
        assertEquals(MatchServerSettings(true, "", "", emptyList()), MatchServerSettings.fromResponse(
            mapOf("result" to "NOERR", "is_revision_check" to "YES"),
        ))
    }

    @Test fun neverDisablesCheckingBecauseAResponseIsMissingOrInvalid() {
        for (response in listOf(
            emptyMap<String, Any>(), mapOf("result" to "NOERR"),
            mapOf("result" to "NOERR", "is_revision_check" to false),
            mapOf("result" to "NOERR", "is_revision_check" to "no"),
            mapOf("result" to "ERR_EXPIRED", "is_revision_check" to "NO")
        )) {
            try { MatchServerSettings.fromResponse(response); fail("Invalid server response accepted: $response") }
            catch (expected: IllegalStateException) { }
        }
    }

    @Test fun nativeFailureRemainsVisibleAfterTransportCleanup() {
        val snapshot = EngineSnapshot(LongArray(29).apply { this[5] = 6; this[23] = 8 })
        assertTrue(snapshot.status().contains("database checksum comparison failed"))
    }
}
