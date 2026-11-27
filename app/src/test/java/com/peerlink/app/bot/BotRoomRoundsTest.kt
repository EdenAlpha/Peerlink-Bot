package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class BotRoomRoundsTest {
    private fun room(seats: Int, go: String = "NO") = mapOf("result" to "NOERR",
        "room_info" to mapOf("num_users" to seats, "is_go_match" to go))

    private class Fixture(val end: RoomRoundEnd, val replies: MutableList<Map<*, *>>) {
        var active = true
        var finalResult = false
        var acceptReady = false
        var failedPreparations = 0
        var onWait: () -> Unit = {}
        val events = mutableListOf<String>()
        val messages = mutableListOf<String>()
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
                acceptReady
            },
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
            room(2, "YES"), room(2, "YES"), room(2, "YES")))
        fixture.onWait = { if (fixture.replies.size == 1) fixture.finalResult = true }
        fixture.run()
        assertEquals(listOf("first match", "room", "room", "ready", "room", "room", "release",
            "prepare", "second match"), fixture.events)
    }

    @Test fun readyCanReturnFromDisconnectWhenTheOldGoFlagIsStillSet() {
        val fixture = Fixture(RoomRoundEnd.DISCONNECTED, mutableListOf(room(2, "YES")))
        fixture.acceptReady = true
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
        assertTrue(closed.messages.last().contains("no longer available"))
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
            resultOrProcessExit = { true }, requestLobbyReady = { _, _ -> false },
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
}
