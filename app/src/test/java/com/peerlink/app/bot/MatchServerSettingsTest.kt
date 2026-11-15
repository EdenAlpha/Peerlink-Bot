package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class MatchServerSettingsTest {
    @Test fun obeysBothExplicitServerPolicies() {
        assertTrue(MatchServerSettings.revisionCheck(mapOf("result" to "NOERR", "is_revision_check" to "YES")))
        assertFalse(MatchServerSettings.revisionCheck(mapOf("result" to "NOERR", "is_revision_check" to "NO")))
    }

    @Test fun neverDisablesCheckingBecauseAResponseIsMissingOrInvalid() {
        for (response in listOf(
            emptyMap<String, Any>(), mapOf("result" to "NOERR"),
            mapOf("result" to "NOERR", "is_revision_check" to false),
            mapOf("result" to "NOERR", "is_revision_check" to "no"),
            mapOf("result" to "ERR_EXPIRED", "is_revision_check" to "NO")
        )) {
            try { MatchServerSettings.revisionCheck(response); fail("Invalid server response accepted: $response") }
            catch (expected: IllegalStateException) { }
        }
    }

    @Test fun nativeFailureRemainsVisibleAfterTransportCleanup() {
        val snapshot = EngineSnapshot(LongArray(29).apply { this[5] = 6; this[23] = 8 })
        assertTrue(snapshot.status().contains("database checksum comparison failed"))
    }
}
