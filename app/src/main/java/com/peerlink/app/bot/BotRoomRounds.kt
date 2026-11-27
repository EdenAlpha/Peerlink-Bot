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
                    var lobbyAvailable = go == "NO"
                    if (seats < 2 || lobbyAvailable) nextReadyCheck = Long.MIN_VALUE
                    if (!lobbyAvailable && seats >= 2 && now() >= nextReadyCheck) {
                        nextReadyCheck = now() + 6000
                        lobbyAvailable = requestLobbyReady(roomId, finished.sessionStart)
                    }
                    if (!active()) return
                    if (!released && (lobbyAvailable || finished.end != RoomRoundEnd.DISCONNECTED || resultOrProcessExit())) {
                        finishEngine()
                        released = true
                        if (seats < 2) status("Back in room $roomNumber. Waiting for your game to return.")
                        else if (!lobbyAvailable) status("Back in room $roomNumber. Waiting for the previous match to close.")
                    }
                    if (released && lobbyAvailable && seats >= 2 && now() >= nextPrepare) {
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
            var readyReply: String? = null
            var lastGo: String? = null
            var lastSession = ""
            var lastUsers = -1
            while (active()) {
                val now = nowMs()
                val reply = readRoom()
                if (!active()) return null
                check(reply["result"] != "ERR_ROOM_NOT_FOUND" && reply["result"] != "ERR_INVALID_SESSION") {
                    "This room is no longer available. End this room to create another."
                }
                val info = reply["room_info"] as? Map<*, *>
                val users = (info?.get("num_users") as? Number)?.toInt() ?: -1
                val go = info?.get("is_go_match") as? String
                if (reply["result"] != "NOERR" || users < 0) {
                    wait()
                    continue
                }
                val changed = users != lastUsers || go != lastGo
                if (changed) {
                    if (lastUsers < 2 || users < 2 || lastGo == "YES" && go == "NO") {
                        readyReply = null
                        nextReady = Long.MIN_VALUE
                        nextSession = Long.MIN_VALUE
                        lastSession = ""
                    }
                    log("room now has $users user(s) go=$go")
                    lastUsers = users
                    lastGo = go
                }
                val oldReply = readyReply
                if (users >= 2 && now >= nextReady) {
                    readyReply = setReady()
                    nextReady = now + if (readyReply == "NOERR" || alreadyReady(readyReply)) 20_000 else 5000
                    log("match_ready -> $readyReply seats=$users go=$go")
                }
                if (!active()) return null
                if (users >= 2 && go == "YES") {
                    if (previousStart == null) return RoomGo()
                    if (canCheckSession(readyReply) && now >= nextSession) {
                        nextSession = now + 3000
                        val session = readSession()
                        val state = sessionAge(session, previousStart)
                        if (state != lastSession) log("REMATCH_SESSION state=$state seats=$users go=$go")
                        lastSession = state
                        if (state == "new" && active()) return RoomGo(session)
                    }
                }
                if (changed || readyReply != oldReply || now >= nextStatus) {
                    nextStatus = now + 30_000
                    status(when {
                        users < 2 -> "Waiting for you to join the room."
                        readyReply == "NOERR" || alreadyReady(readyReply) -> "Bot is ready. Press To Match in eFootball."
                        readyReply == "ERR_ALREADY_MATCH" -> "Both players are here. Waiting for the room to release the previous match."
                        else -> "Both players are here. Retrying the bot’s Ready."
                    })
                }
                wait()
            }
            return null
        }

        /** The service can still publish the old Go flag and address while returning to the room. */
        fun canCheckSession(result: String?) = result == "NOERR" || alreadyReady(result) || result == "ERR_ALREADY_MATCH"

        fun sessionAge(reply: Map<*, *>, previousStart: Long): String {
            val start = (reply["connecting_start_time"] as? Number)?.toLong() ?: 0
            return when {
                reply["result"] != "NOERR" || start !in 1..0xffffffffL -> "unavailable"
                start <= previousStart -> "previous"
                else -> "new"
            }
        }
        fun freshSession(reply: Map<*, *>, previousStart: Long) = sessionAge(reply, previousStart) == "new"
    }
}
