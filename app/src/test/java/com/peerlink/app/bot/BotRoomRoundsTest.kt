package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class BotRoomRoundsTest {
    private fun room(seats: Int, go: String = "NO") = mapOf("result" to "NOERR",
        "room_info" to mapOf("num_users" to seats, "is_go_match" to go))

    private class Fixture(val end: RoomRoundEnd, val replies: MutableList<Map<*, *>>) {
        var active = true
        var finalResult = false
        var lobbyAvailability = LobbyAvailability.WAITING
        var failedPreparations = 0
        var onWait: () -> Unit = {}
        val events = mutableListOf<String>()
        val messages = mutableListOf<String>()
        val recovery = mutableListOf<Pair<Long, String?>>()
        val roomId = 4286L
        private var elapsed = 0L

        fun run() = BotRoomRounds(
            active = { active },
            play = { id, last ->
                assertEquals(roomId, id)
                events += if (last == null) "first match" else "second match"
                if (last == null) CompletedRoomMatch(end, 245L)
                else { assertEquals(CompletedRoomMatch(end, 245L), last); null }
            },
            readRoom = { id ->
                assertEquals(roomId, id)
                events += "room"
                replies.removeAt(0)
            },
            finishEngine = { events += "release" },
            prepareEngine = {
                events += "prepare"
                if (failedPreparations > 0) { failedPreparations--; "Engine temporarily unavailable." } else null
            },
            status = messages::add,
            resultOrProcessExit = { finalResult },
            requestLobbyReady = { id, old ->
                assertEquals(roomId, id); assertEquals(245L, old)
                events += "ready"
                lobbyAvailability
            },
            reportRecovery = { recovery += elapsed to it },
            now = { elapsed },
            waitForRoom = { elapsed += 3000; onWait() },
        ).run(roomId, "412345")
    }

    @Test fun finishedGameReturnsToSameRoomAndWaitsForTheOpponentBeforeReloading() {
        val fixture = Fixture(RoomRoundEnd.RESULT, mutableListOf(room(1), room(2)))
        fixture.run()
        assertEquals(listOf("first match", "room", "release", "room", "prepare", "second match"), fixture.events)
        assertTrue(fixture.messages.any { "412345" in it && "return" in it })
    }

    @Test fun disconnectKeepsResultsAliveThroughFailedPollAndWaitsForLobbyOrFinalResult() {
        val fixture = Fixture(RoomRoundEnd.DISCONNECTED, mutableListOf(mapOf("result" to "_NET_FAIL"),
            room(2, "YES"), room(2, "YES"), room(2, "YES"), room(2)))
        fixture.onWait = { if (fixture.replies.size == 2) fixture.finalResult = true }
        fixture.run()
        assertEquals(listOf("first match", "room", "room", "ready", "room", "room", "ready", "release",
            "room", "prepare", "second match"), fixture.events)
    }

    @Test fun readyCanReturnFromDisconnectWhenTheOldGoFlagIsStillSet() {
        val fixture = Fixture(RoomRoundEnd.DISCONNECTED, mutableListOf(room(2, "YES")))
        fixture.lobbyAvailability = LobbyAvailability.OPEN
        fixture.run()
        assertEquals(listOf("first match", "room", "ready", "release", "prepare", "second match"), fixture.events)
    }

    @Test fun lostEngineCanRestartInExistingRoomWithoutAnotherRoomCreation() {
        val fixture = Fixture(RoomRoundEnd.ENGINE_STOPPED, mutableListOf(room(2)))
        fixture.run()
        assertEquals(listOf("first match", "room", "release", "prepare", "second match"), fixture.events)
    }

    @Test fun breakCelebrationAndAResultBeingPreparedAreNotTerminal() {
        fun state(outer: Long, result: Long = 0, error: Long = 0) = EngineSnapshot(LongArray(35).apply {
            this[2] = 4; this[3] = 4; this[4] = 4; this[8] = outer; this[9] = 54; this[10] = 22
            this[7] = error; this[34] = result
        })
        for (outer in listOf(17L, 21L, 0L)) assertNull(state(outer).roundEnd)
        assertEquals(RoomRoundEnd.DISCONNECTED, state(17, error = 1).roundEnd)
        assertEquals(RoomRoundEnd.RESULT, state(17, result = 1, error = 1).roundEnd)
        assertNull(EngineSnapshot(LongArray(34)).roundEnd)
    }

    @Test fun secondGoNeedsANewerSuccessfulServerSession() {
        fun reply(t: Any?, result: String = "NOERR") = mapOf("result" to result, "connecting_start_time" to t)
        for (bad in listOf(reply(245L), reply(200L), reply(null), reply(255L, "_NET_FAIL"), reply(-1L)))
            assertFalse(BotRoomRounds.freshSession(bad, 245L))
        assertTrue(BotRoomRounds.freshSession(reply(246), 245L))
    }

    @Test fun cancelWhileWaitingAndExpiredRoomNeverStartAnotherEngine() {
        val cancelled = Fixture(RoomRoundEnd.RESULT, mutableListOf(room(1)))
        cancelled.onWait = { cancelled.active = false }
        cancelled.run()
        assertFalse("prepare" in cancelled.events)
        val closed = Fixture(RoomRoundEnd.DISCONNECTED, mutableListOf(mapOf("result" to "ERR_ROOM_NOT_FOUND")))
        closed.run()
        assertEquals(listOf("first match", "room", "release"), closed.events)
        assertTrue(closed.recovery.single().second.orEmpty().contains("no longer available"))
    }

    @Test fun releasedBotOffersNewRoomAfterRepeatedOldAssignmentsAndStillRecoversTheSameRoom() {
        val replies = MutableList<Map<*, *>>(14) { room(2, "YES") }.apply { add(room(2)) }
        val fixture = Fixture(RoomRoundEnd.DISCONNECTED, replies)
        fixture.lobbyAvailability = BotRoomRounds.lobbyAvailability("ERR_ALREADY_MATCH",
            mapOf("result" to "NOERR", "connecting_start_time" to 245L), 245L)
        fixture.onWait = { if (replies.size == 14) fixture.finalResult = true }

        fixture.run()

        // Engine exited just after the first poll. The first stale reply observed
        // with the process gone was at six seconds.
        assertEquals(36_000L, fixture.recovery.first().first)
        assertTrue(fixture.recovery.first().second.orEmpty().contains("previous match"))
        assertEquals(listOf("prepare", "second match"), fixture.events.takeLast(2))
        assertEquals(1, fixture.events.count { it == "release" })
        assertNull(fixture.recovery.last().second)
    }

    @Test fun transientFailuresAndAnEngineStillReportingResultsCannotOfferReplacement() {
        for (end in listOf(RoomRoundEnd.DISCONNECTED, RoomRoundEnd.ENGINE_STOPPED)) {
            val fixture = Fixture(end, MutableList(15) { room(2, "YES") })
            if (end == RoomRoundEnd.DISCONNECTED) fixture.lobbyAvailability = LobbyAvailability.PREVIOUS_MATCH
            fixture.onWait = { if (fixture.replies.isEmpty()) fixture.active = false }
            fixture.run()
            assertTrue(fixture.recovery.isEmpty())
            assertFalse("prepare" in fixture.events)
        }
    }

    @Test fun actualReadyLoopRunsTwiceInSameRoomAndWaitsOutOldGoAndOldSession() {
        val id = 710L
        val ready = mutableListOf<Long>()
        val sessions = mutableListOf(200L, 208L)
        val starts = mutableListOf<Long>()
        var elapsed = 0L
        val rounds = BotRoomRounds(
            active = { true },
            play = { sameId, old ->
                assertEquals(id, sameId)
                val go = requireNotNull(BotRoomRounds.awaitGo(old?.sessionStart, { true },
                    setReady = { ready += sameId; "NOERR" }, readRoom = { room(2, "YES") },
                    readSession = { mapOf("result" to "NOERR", "connecting_start_time" to sessions.removeAt(0)) },
                    log = {}, status = {}, nowMs = { elapsed }, wait = { elapsed += 1000 }))
                val start = (go.confirmedSession?.get("connecting_start_time") as? Number)?.toLong() ?: 200L
                starts += start
                if (old == null) CompletedRoomMatch(RoomRoundEnd.RESULT, start) else null
            },
            readRoom = { room(2) }, finishEngine = {}, prepareEngine = { null }, status = {},
            resultOrProcessExit = { true }, requestLobbyReady = { _, _ -> LobbyAvailability.WAITING },
        )
        rounds.run(id, "111222")
        assertEquals(listOf(id, id), ready)
        assertEquals(listOf(200L, 208L), starts)
        assertTrue(sessions.isEmpty())
    }

    @Test fun staleGoAfterRejectedReadyCannotStartAnotherGame() {
        var elapsed = 0L
        var active = true
        var sessions = 0
        assertNull(BotRoomRounds.awaitGo(700L, { active }, setReady = { "ERR_ALREADY_PLAYING" },
            readRoom = { room(2, "YES") }, readSession = { sessions++; emptyMap<String, Any?>() },
            status = {}, log = {}, nowMs = { elapsed }, wait = { elapsed += 1000; active = false }))
        assertEquals(0, sessions)
    }

    @Test fun rejoiningOpponentReadiesImmediatelyAndAlreadyMatchedOnlyResumesANewerSession() {
        var tick = 0
        val readies = mutableListOf<Int>()
        val lookups = mutableListOf<Int>()
        val records = mutableListOf<String>()
        val go = BotRoomRounds.awaitGo(245L, { tick <= 8 },
            setReady = { readies += tick; "ERR_ALREADY_MATCH" },
            readRoom = { when (tick) {
                0, 2 -> room(1, "YES")
                1, 3 -> room(2)
                else -> room(2, "YES")
            } }, readSession = {
                lookups += tick
                mapOf("result" to "NOERR", "connecting_start_time" to if (tick < 7) 245L else 400L)
            }, status = {}, log = records::add, nowMs = { tick * 1000L }, wait = { tick++ })
        assertEquals(listOf(1, 3), readies)
        assertEquals(listOf(4, 7), lookups)
        assertEquals(400L, go?.confirmedSession?.get("connecting_start_time"))
        assertTrue(records.any { "state=previous" in it })
    }

    @Test fun aStaleAssignmentDuringRematchReadyAlsoOffersRecoveryUntilANewMatchArrives() {
        var tick = 0
        val notices = mutableListOf<String?>()
        val go = BotRoomRounds.awaitGo(245L, { tick < 40 }, setReady = { "ERR_ALREADY_MATCH" },
            readRoom = { room(2, "YES") },
            readSession = { mapOf("result" to "NOERR", "connecting_start_time" to if (tick < 36) 245L else 246L) },
            status = {}, log = {}, reportRecovery = notices::add, nowMs = { tick * 1000L }, wait = { tick++ })
        assertEquals(246L, go?.confirmedSession?.get("connecting_start_time"))
        assertEquals(2, notices.size)
        assertTrue(notices.first().orEmpty().contains("previous match"))
        assertNull(notices.last())
    }

    @Test fun firstRoomWaitsForBothSeatsAndRetriesReadyAfterReentry() {
        var tick = 0
        val reads = mutableListOf<Int>()
        val result = BotRoomRounds.awaitGo(null, { tick < 5 }, setReady = { reads += tick; "NOERR" },
            readRoom = { when (tick) { 0, 2 -> room(1); 1, 3 -> room(2); else -> room(2, "YES") } },
            readSession = { error("first session comes from connection phase") }, status = {}, log = {},
            nowMs = { tick * 1000L }, wait = { tick++ })
        assertNotNull(result)
        assertEquals(listOf(1, 3), reads)
    }

    @Test fun confirmedSeatingCanReadyWhenRoomHasYetToPublishGo() {
        var ready = 0
        var tick = 0
        val active = BotRoomRounds.awaitGo(null, { tick < 3 }, { ready++; "NOERR" },
            readRoom = { if (tick == 0) mapOf("result" to "NOERR", "room_info" to mapOf("num_users" to 2))
                else room(2, "YES") }, readSession = { error("first room") },
            status = {}, log = {}, nowMs = { tick * 1000L }, wait = { tick++ })
        assertNotNull(active)
        assertEquals(1, ready)
    }

    @Test fun transientEngineStartupFailureRetriesInTheSameRoomWithBackoff() {
        val fixture = Fixture(RoomRoundEnd.ENGINE_STOPPED, MutableList(6) { room(2) })
        fixture.failedPreparations = 1
        fixture.run()
        assertEquals(1, fixture.events.count { it == "release" })
        assertEquals(6, fixture.events.count { it == "room" })
        assertEquals(2, fixture.events.count { it == "prepare" })
        assertEquals("second match", fixture.events.last())
        assertTrue(fixture.messages.any { "Retrying in 15 seconds" in it })
    }

    @Test fun stalledPlayOnlyAsksForTheResultAndOnlyAFinalServerReplyConfirmsIt() {
        val playing = LongArray(35).apply {
            this[2] = 4; this[3] = 4; this[4] = 4; this[8] = 17; this[9] = 54; this[10] = 22; this[30] = 870
        }
        assertFalse(EngineSnapshot(playing).needsResultCheck(false, 870))
        assertFalse(EngineSnapshot(playing).needsResultCheck(true, 800))
        assertTrue(EngineSnapshot(playing).needsResultCheck(true, 870))
        assertNull(EngineSnapshot(playing).roundEnd)
        assertFalse(BotRoomRounds.confirmedResult(mapOf("result" to "NOERR", "get_match_result" to "NO")))
        assertFalse(BotRoomRounds.confirmedResult(mapOf("result" to "_NET_FAIL", "get_match_result" to "YES")))
        assertTrue(BotRoomRounds.confirmedResult(mapOf("result" to "NOERR", "get_match_result" to "YES")))
        playing[8] = 21
        assertFalse(EngineSnapshot(playing).needsResultCheck(true, 870))
    }
}
