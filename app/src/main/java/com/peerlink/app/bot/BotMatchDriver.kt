package com.peerlink.app.bot

import android.util.Base64
import com.peerlink.app.core.AppState
import kotlin.concurrent.thread

/**
 * Bot mode step 3 -- the state-driven driver.
 *
 * The CLI walked a scripted order with two scripted players; in bot mode the
 * second player is the user's REAL game, so the bot advances on ROOM STATE:
 *
 *   poll CMD_GET_ROOM_INFO until the game is seated (num_users == 2)
 *   -> entry side (bot is the room creator: HOME / leader)
 *   -> CMD_CHECK_MATCH_ENABLE (ERR_INVALID_SESSION/OTZP- is non-blocking,
 *      proven in every run since #29 -- tolerated, never fatal)
 *   -> CMD_SET_ROOM_MATCH_READY with the FABRICATED profile (.53.2)
 *   -> poll room state for is_go_match == YES (both sides ready)
 *   -> CMD_GET_GAME_SESSION -> check-res echo (capture block 105 body)
 *   -> CMD_GET_GAME_ID (6-form fallback) -> CMD_SEND_SESSION_ID
 *   -> TURN announce + watch -> antenna beat
 *   -> PeerSlot.arm(...): the connection phase is live (step 4's sink).
 *
 * Then a periodic antenna re-push keeps the 11.1.0 liveness signal flowing
 * (the real client polls CMD_GET_GAME_SESSION_CHECK_RES on an interval and
 * re-pushes the SET through the WHOLE match). Every step logs through
 * AppState; a required-step failure stops the driver with the reason (never
 * a silent stall). The T7 beats belong to BotMode and keep running.
 */
object BotMatchDriver {

    private const val SEAT_POLL_MS = 2_000L
    private const val GO_POLL_MS = 1_000L
    private const val SEAT_TIMEOUT_MS = 10 * 60_000L
    private const val GO_TIMEOUT_MS = 90_000L

    /**
     * Antenna re-push cadence. The exact 11.1.0 interval comes from the
     * server's AntennaInfoSendIntervalMs (CMD_GET_SERVER_ENV); until that is
     * wired in, 5 s matches the STUN beat's proven cadence and stays well
     * inside any sane interval.
     */
    private const val ANTENNA_INTERVAL_MS = 5_000L

    @Volatile
    private var running = false

    /** Start the driver for a freshly created room. False if one is running. */
    fun start(gate: KgsGate, rid: Long, roomNumber: String): Boolean {
        if (running) return false
        running = true
        thread(name = "bot-match-driver") { run(gate, rid, roomNumber) }
        return true
    }

    fun stop() {
        running = false
    }

    private fun log(line: String) = AppState.appendLog("[BOT-DRIVER] $line")

    private fun run(gate: KgsGate, rid: Long, roomNumber: String) {
        val flow = BotMatchFlow(gate, gate, BotMatchFlow.SquadSource { emptyList() })
        val self = gate.uid
        try {
            log("room $roomNumber open -- waiting for your game to join")
            val opp = waitForSeat(flow, gate, rid, self)
            if (opp == null) {
                log("no game joined (timeout) -- driver stopped")
                return
            }
            if (!running) return
            if (opp == 0L) {
                log("seated, but the opponent uid was not parsed from room_info " +
                    "-- user lists will carry 0; watch the server's answer")
            }
            log("your game is seated (uid=$opp) -- firing the bot's half")

            val es = flow.entrySide(gate, rid, "HOME", true)
            if (es["result"] != "NOERR") {
                log("entry_side rejected (${es["result"]}) -- stopping")
                return
            }
            log("entry_side -> NOERR")

            val cme = flow.checkMatchEnable(gate)
            // s23.12: ERR_INVALID_SESSION/OTZP- here is known and non-blocking.
            log("check_match_enable -> ${cme["result"]}")

            val mr = flow.matchReady(gate, rid, BotMode.declaredProfile())
            val mrRes = mr["result"].toString()
            if (mrRes != "NOERR" && mrRes != "ERR_ALREADY_READY") {
                log("match_ready rejected ($mrRes) -- stopping")
                return
            }
            log("match_ready -> $mrRes")

            if (!waitGo(flow, gate, rid)) {
                log("go_match never flipped (timeout) -- stopping")
                return
            }
            log("go_match YES -- opening the session")

            val gs = flow.gameSession(gate, rid)
            if (gs["result"] != "NOERR") {
                log("game_session rejected (${gs["result"]}) -- stopping")
                return
            }
            log("game_session -> NOERR")

            val chk = flow.checkResEcho(gate, rid, self, opp, gs)
            log("check_res (capture block 105 body) -> ${chk["result"]}")

            val gameId = flow.getGameId(gate, rid)
            if (gameId == null) {
                log("game_id missing in every form -- stopping")
                return
            }
            log("game_id found")
            flow.sendSessionId(gate, gameId)

            val addr = "${BotMode.BOT_FAB_IP}:${BotMode.BOT_PORT}"
            val blob = Base64.encodeToString(addr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val sd = flow.sendTurnAddress(gate, opp, blob, addr.length)
            log("turn announce -> ${sd["result"]}")
            flow.getTurnAddress(gate)
            val watch = flow.watchTurnAddress(gate)
            val peerAddr = (watch["address"] as? String).orEmpty()
            log("turn watch -> ${watch["result"]} " +
                "(peer address ${if (peerAddr.isBlank()) "pending" else peerAddr})")

            val (setR, getR) = flow.antennaBeat(gate, rid, self, opp, gs)
            log("antenna set=${setR["result"]} get=${getR["result"]} " +
                "finished=${getR["is_check_finished"]}")

            PeerSlot.arm(opp, peerAddr)
            log("connection phase ARMED -- the bot answers .53.2 traffic from here")

            // Mid-match liveness: re-push the antenna echo on a cadence.
            while (running) {
                Thread.sleep(ANTENNA_INTERVAL_MS)
                if (!running) break
                flow.antennaBeat(gate, rid, self, opp, gs)
            }
        } catch (t: Throwable) {
            log("stopped by ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            running = false
        }
    }

    /** Poll the room until the opponent is seated; null on timeout. */
    private fun waitForSeat(flow: BotMatchFlow, gate: KgsGate, rid: Long, self: Long): Long? {
        val deadline = System.currentTimeMillis() + SEAT_TIMEOUT_MS
        while (running && System.currentTimeMillis() < deadline) {
            val info = flow.roomState(gate, rid)
            val n = (info["num_users"] as? Number)?.toInt() ?: 0
            if (n >= 2) return findOpponentUid(info, self) ?: 0L
            Thread.sleep(SEAT_POLL_MS)
        }
        return null
    }

    /** Poll until both sides are ready (is_go_match == YES). */
    private fun waitGo(flow: BotMatchFlow, gate: KgsGate, rid: Long): Boolean {
        val deadline = System.currentTimeMillis() + GO_TIMEOUT_MS
        while (running && System.currentTimeMillis() < deadline) {
            val info = flow.roomState(gate, rid)
            if ((info["is_go_match"] as? String) == "YES") return true
            Thread.sleep(GO_POLL_MS)
        }
        return false
    }

    /**
     * Scan room_info for the opponent's user id. Defensive on purpose: the
     * exact container field is not yet pinned, so any user_id-style key
     * holding an id that is not the bot counts. If nothing is found the
     * driver continues with 0 (logged) -- the server decides.
     */
    private fun findOpponentUid(info: Map<*, *>, self: Long): Long? {
        val found = ArrayList<Long>()
        fun scan(o: Any?) {
            when (o) {
                is Map<*, *> -> for ((k, v) in o) {
                    if (k is String && v is Number &&
                        (k == "user_id" || k.contains("user_id") || k.contains("member_user"))
                    ) {
                        val u = v.toLong()
                        if (u > 0 && u != self) found.add(u)
                    } else {
                        scan(v)
                    }
                }
                is List<*> -> o.forEach { scan(it) }
            }
        }
        scan(info)
        return found.firstOrNull()
    }
}
