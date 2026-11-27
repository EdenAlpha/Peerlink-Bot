package com.peerlink.app.bot

internal enum class RoomRoundEnd { RESULT, DISCONNECTED, ENGINE_STOPPED }

internal data class RoomGo(val confirmedSession: Map<*, *>? = null)

internal data class CompletedRoomMatch(val end: RoomRoundEnd, val sessionStart: Long)

/** Own one existing room across multiple games. A break in play is never a completed round. */
internal class BotRoomRounds(
    private val active: () -> Boolean,
    private val play: (Long, CompletedRoomMatch?) -> CompletedRoomMatch?,
    private val readRoom: (Long) -> Map<*, *>,
    private val finishEngine: () -> Unit,
    private val prepareEngine: () -> String?,
    private val status: (String) -> Unit,
    private val resultOrProcessExit: () -> Boolean,
    private val requestLobbyReady: (Long, Long) -> Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val waitForRoom: () -> Unit = { Thread.sleep(3000) },
) {
    fun run(roomId: Long, roomNumber: String) {
        var previous: CompletedRoomMatch? = null
        while (active()) {
            val finished = play(roomId, previous) ?: return
            if (!active()) return
            status(when (finished.end) {
                RoomRoundEnd.RESULT -> "Match finished. Returning to room $roomNumber…"
                RoomRoundEnd.DISCONNECTED -> "Match disconnected. Returning to room $roomNumber…"
                RoomRoundEnd.ENGINE_STOPPED -> "Bot engine stopped. Returning to room $roomNumber…"
            })
            var released = false
            var nextReadyCheck = Long.MIN_VALUE
            var nextPrepare = Long.MIN_VALUE
            var retryDelayMs = 15_000L
            while (active()) {
                val reply = readRoom(roomId)
                if (!active()) return
                if (reply["result"] == "ERR_ROOM_NOT_FOUND" || reply["result"] == "ERR_INVALID_SESSION") {
                    if (!released) finishEngine()
                    status("Room $roomNumber is no longer available. End this room to create another.")
                    return
                }
                val info = reply["room_info"] as? Map<*, *>
                val seats = (info?.get("num_users") as? Number)?.toInt() ?: 0
                val go = info?.get("is_go_match")
                if (reply["result"] == "NOERR" && seats > 0 && (go == "NO" || go == "YES")) {
                    // A confirmed result or a dead process needs no further native reporting.
                    // After a native disconnect, let the engine finish its result while the room
                    // is still playing. A final result can arrive after the disconnect signal.
                    var canReturn = go == "NO" || finished.end != RoomRoundEnd.DISCONNECTED || resultOrProcessExit()
                    if (!released && !canReturn && seats >= 2 && now() >= nextReadyCheck) {
                        // Some rooms retain the old Go flag until a participant readies again.
                        // Give the still-running result handler time when the server declines.
                        nextReadyCheck = now() + 20_000
                        canReturn = requestLobbyReady(roomId, finished.sessionStart)
                    }
                    if (!active()) return
                    if (!released && canReturn) {
                        finishEngine()
                        released = true
                        if (seats < 2) status("Back in room $roomNumber. Waiting for your game to return.")
                    }
                    if (released && seats >= 2 && now() >= nextPrepare) {
                        status("Preparing the bot for the next match in room $roomNumber…")
                        val issue = prepareEngine()
                        if (!active()) return
                        if (issue == null) break
                        status("Room $roomNumber is still open. $issue Retrying in ${retryDelayMs / 1000} seconds.")
                        nextPrepare = now() + retryDelayMs
                        retryDelayMs = minOf(retryDelayMs * 2, 60_000)
                    }
                }
                waitForRoom()
            }
            if (!active()) return
            previous = finished
        }
    }

    companion object {
        fun confirmedResult(reply: Map<*, *>) = reply["result"] == "NOERR" && reply["get_match_result"] == "YES"

        fun alreadyReady(result: String?): Boolean =
            result?.startsWith("ERR_ALREADY") == true && result.endsWith("READY")

        fun awaitGo(previousStart: Long?, active: () -> Boolean, setReady: () -> String?,
                    readRoom: () -> Map<*, *>, readSession: () -> Map<*, *>,
                    status: (String) -> Unit, log: (String) -> Unit,
                    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
                    wait: () -> Unit = { Thread.sleep(1000) }): RoomGo? {
            var nextReady = Long.MIN_VALUE
            var nextStatus = Long.MIN_VALUE
            var nextSession = Long.MIN_VALUE
            var accepted = false
            var lastUsers = -1
            while (active()) {
                val now = nowMs()
                if (now >= nextReady) {
                    val result = setReady()
                    accepted = result == "NOERR" || alreadyReady(result)
                    nextReady = now + 20_000
                    log("match_ready -> $result")
                }
                if (!active()) return null
                val reply = readRoom()
                check(reply["result"] != "ERR_ROOM_NOT_FOUND" && reply["result"] != "ERR_INVALID_SESSION") {
                    "This room is no longer available. End this room to create another."
                }
                val info = reply["room_info"] as? Map<*, *>
                val users = (info?.get("num_users") as? Number)?.toInt() ?: 0
                if (users > 0 && users != lastUsers) {
                    lastUsers = users
                    log("room now has $users user(s)")
                }
                if (reply["result"] == "NOERR" && info?.get("is_go_match") == "YES") {
                    if (previousStart == null) return RoomGo()
                    if (accepted && active() && now >= nextSession) {
                        nextSession = now + 3000
                        val session = readSession()
                        if (freshSession(session, previousStart)) return RoomGo(session)
                    }
                }
                if (now >= nextStatus) {
                    nextStatus = now + 30_000
                    status(if (accepted) "Bot is ready. Press To Match in eFootball."
                        else "Waiting for the room to accept the bot’s Ready.")
                }
                wait()
            }
            return null
        }

        /** The service can still publish the old Go flag and address while returning to the room. */
        fun freshSession(reply: Map<*, *>, previousStart: Long): Boolean {
            val start = (reply["connecting_start_time"] as? Number)?.toLong() ?: 0
            return reply["result"] == "NOERR" && start in 1..0xffffffffL && start > previousStart
        }
    }
}
