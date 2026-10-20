package com.peerlink.app.bot

import android.util.Base64
import com.peerlink.app.core.AppState
import com.peerlink.app.service.PeerBotService
import kotlin.concurrent.thread

/**
 * Bot mode step 3 -- the state-driven driver (v2, 2026-10-15 late).
 *
 * The ladder is the proven order, byte-for-byte:
 *   seat -> entry side (HOME/leader) -> check_match_enable (OTZP- tolerated)
 *   -> match_ready (.53.2) -> go_match -> game_session -> opponent address
 *   -> check-res echo -> game_id -> send_session_id -> TURN announce/watch
 *   -> antenna beat -> PeerSlot armed (live connection phase).
 *
 * v1 could stop silently, and the user's game then sat on "Waiting for
 * Opponent" forever because the bot's half had failed quietly (a 10-minute
 * seat watch that gave up, one-shot rungs that treated a transient network
 * answer as fatal, a single ready send never re-asserted). v2 policy:
 *
 *  - the seat watch has NO deadline (runs until the driver is stopped);
 *  - every rung retries on transport noise (_NET_FAIL / _HTTP_EMPTY /
 *    _DECODE_FAIL) and on ERR_ALREADY_* answers (idempotent states);
 *  - the ready is RE-ASSERTED on a cadence while waiting -- if it was lost
 *    the server receives it again, if it landed the answer is
 *    ERR_ALREADY_READY and nothing changes;
 *  - the peer slot is armed the moment go_match fires (the game starts
 *    punching immediately), then re-targeted with the session's address;
 *  - every rung pushes its state to the notification, so the phone itself
 *    shows where the bot stands (the user's screen is the log).
 */
object BotMatchDriver {

    private const val SEAT_POLL_MS = 2_000L
    private const val GO_POLL_MS = 1_000L

    /** Re-assert the bot's ready on this cadence while waiting for go. */
    private const val READY_REASSERT_MS = 20_000L

    /** Notification heartbeat while nothing else is moving. */
    private const val STATUS_EVERY_MS = 30_000L

    /** Retry spacing for transient ("_NET_FAIL"-class) answers. */
    private const val RETRY_MS = 3_000L

    /**
     * Antenna re-push cadence. The exact 11.1.0 interval comes from the
     * server's AntennaInfoSendIntervalMs (CMD_GET_SERVER_ENV); until that is
     * wired in, 5 s matches the STUN beat's proven cadence and stays well
     * inside any sane interval.
     */
    private const val ANTENNA_INTERVAL_MS = 5_000L

    /** TURN watch: poll for the peer address, then carry on without it. */
    private const val WATCH_POLL_MS = 2_000L
    private const val WATCH_TIMEOUT_MS = 60_000L

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

    /** Log + live notification (never a silent state again). */
    private fun status(line: String) {
        log(line)
        runCatching { PeerBotService.status(line) }
    }

    /** Transport noise: retry, never stop on it. */
    private fun transient(result: String?): Boolean =
        result == null || result == "_NET_FAIL" ||
            result == "_HTTP_EMPTY" || result == "_DECODE_FAIL"

    /** ERR_ALREADY_*: the state is already what we asked for. */
    private fun already(result: String?): Boolean =
        result?.startsWith("ERR_ALREADY") == true

    private fun run(gate: KgsGate, rid: Long, roomNumber: String) {
        val flow = BotMatchFlow(gate, gate, BotMatchFlow.SquadSource { emptyList() })
        val self = gate.uid
        try {
            status("room $roomNumber open -- join it from eFootball now")

            val oppSeen = waitForSeat(flow, gate, rid, self)
            if (!running) return
            var opp = if (oppSeen < 0) 0L else oppSeen
            if (opp == 0L) {
                log("seated, but the opponent uid was not parsed from room_info " +
                    "-- the session's address list will carry the true id")
            }
            status("your game joined -- the bot is firing its half")

            // -- entry side (creator = HOME / leader). Retried; never fatal.
            var entryOk = false
            while (running && !entryOk) {
                val es = flow.entrySide(gate, rid, "HOME", true)
                val r = es["result"]?.toString()
                when {
                    r == "NOERR" -> { entryOk = true; log("entry_side -> NOERR") }
                    already(r) -> { entryOk = true; log("entry_side -> $r (already set)") }
                    else -> {
                        log("entry_side -> $r -- retrying")
                        Thread.sleep(if (transient(r)) RETRY_MS else READY_REASSERT_MS)
                    }
                }
            }
            if (!running) return

            // -- ROOM-TIME relay-quality report (capture block 63). The real
            //    client measures AGAIN inside the room, after the entry side
            //    and immediately before check_match_enable -> ready
            //    (run #47 exactly: entry_side -> relay_qual -> CME -> ready
            //    -> NOERR). This is the measurement the server binds to the
            //    room; declaring COMPLETE with an empty record is the
            //    ERR_EMPTY the device runs hit every time. Retried on
            //    transport noise, never fatal on a hard error (logged).
            var qualityDone = false
            while (running && !qualityDone) {
                val rep = BotRelayQuality.roomReport(gate)
                if (rep.skipped) {
                    log("room relay quality -> no endpoints to measure (continuing)")
                    qualityDone = true
                } else if (transient(rep.result)) {
                    log("room relay quality -> ${rep.result} -- retrying")
                    Thread.sleep(RETRY_MS)
                } else {
                    qualityDone = true
                }
            }
            if (!running) return

            // -- check_match_enable, immediately before the first ready
            //    (capture adjacency). ERR_INVALID_SESSION / OTZP- is the
            //    documented non-blocking answer; noise just retries.
            var cmeDone = false
            while (running && !cmeDone) {
                val cme = flow.checkMatchEnable(gate)
                val r = cme["result"]?.toString()
                if (transient(r)) {
                    log("check_match_enable -> $r -- retrying")
                    Thread.sleep(RETRY_MS)
                    continue
                }
                log("check_match_enable -> $r" + if (r != "NOERR") " (non-blocking)" else "")
                cmeDone = true
            }
            if (!running) return

            // -- ready + go_match watch. The ready is re-asserted on a
            //    cadence: the server wants both sides' ready, and the game
            //    sends ITS ready when the user presses To Match. A lost
            //    ready must never be the reason the room stalls.
            log("bot endpoint: host=${BotMode.hostIp}:${BotMode.BOT_PORT} " +
                "reflexive=${BotMode.BOT_FAB_IP}:${BotMode.BOT_PORT}")
            var firstReady = true
            var nextReadyAt = 0L
            var nextStatusAt = 0L
            var lastUsers = -1
            var go = false
            while (running && !go) {
                val now = System.currentTimeMillis()
                if (firstReady || now >= nextReadyAt) {
                    val mr = flow.matchReady(gate, rid, BotMode.declaredProfile())
                    val r = mr["result"]?.toString()
                    firstReady = false
                    nextReadyAt = now + READY_REASSERT_MS
                    when {
                        r == "NOERR" -> log("match_ready -> NOERR")
                        already(r) -> log("match_ready -> $r (already ready)")
                        else -> log("match_ready -> $r (will re-assert)")
                    }
                }
                val info = flow.roomState(gate, rid)
                val n = (info["num_users"] as? Number)?.toInt() ?: lastUsers
                if (n > 0 && n != lastUsers) {
                    lastUsers = n
                    log("room now has $n user(s)")
                }
                if ((info["is_go_match"] as? String) == "YES") {
                    go = true
                    break
                }
                if (now >= nextStatusAt) {
                    nextStatusAt = now + STATUS_EVERY_MS
                    status("ready sent -- waiting for both sides " +
                        "(press Ready + To Match in eFootball)")
                }
                Thread.sleep(GO_POLL_MS)
            }
            if (!running) return
            status("both sides ready -- the game is entering the connection phase")

            // The game punches the moment its tunnel exists: make the peer
            // slot live NOW (reactive) and fill its target once the session
            // reply carries the game's address (arm is idempotent).
            PeerSlot.arm(opp, gate.uid, "", "", 0)

            // -- game_session + the opponent's address (retried together:
            //    the reply must ALSO contain the address block).
            var gs: Map<*, *>? = null
            var oppBlock: Map<*, *>? = null
            var rounds = 0
            while (running && (gs == null || oppBlock == null)) {
                rounds++
                if (gs == null) {
                    val r = flow.gameSession(gate, rid)
                    val res = r["result"]?.toString()
                    if (res == "NOERR") {
                        gs = r
                        log("game_session -> NOERR")
                    } else {
                        log("game_session -> $res (round $rounds) -- retrying")
                        Thread.sleep(RETRY_MS)
                        continue
                    }
                }
                oppBlock = flow.opponentAddress(gs!!, self)
                if (oppBlock == null) {
                    log("session reply has no opponent address yet (round $rounds)")
                    gs = null
                    Thread.sleep(RETRY_MS)
                }
            }
            if (!running) return

            val sessionOpp = (oppBlock!!["user_id"] as? Number)?.toLong() ?: opp
            if (sessionOpp > 0 && sessionOpp != opp) {
                opp = sessionOpp
                log("opponent uid from the session: $opp")
            }
            val a4 = oppBlock!!["address_ipv4"] as? Map<*, *>
            val localIp = (a4?.get("host_address") as? String).orEmpty()
            val localPort = (a4?.get("host_port") as? Number)?.toInt() ?: 0
            val reflIp = (a4?.get("reflexive_address") as? String).orEmpty()
            val reflPort = (a4?.get("reflexive_port") as? Number)?.toInt() ?: 0
            log("game address from the session: local=$localIp:$localPort " +
                "declared=$reflIp:$reflPort")
            PeerSlot.arm(opp, gate.uid, "$reflIp:$reflPort", localIp, localPort)

            // -- the engine, when it is loaded and driving the exchange,
            //    gets the opponent in the engine's own record.  Everything
            //    the engine needs for both families comes from this same
            //    session reply; the bot does not answer the engine's P2P
            //    frames at all.
            if (EngineHost.base != 0L) {
                flow.handPeerToEngine(oppBlock!!, opp)
            }

            // -- check-res echo (capture block 105 body); retried on noise,
            //    otherwise logged and carried on (the game sends its own).
            var chkDone = false
            while (running && !chkDone) {
                val chk = flow.checkResEcho(gate, rid, self, opp, gs!!)
                val cr = chk["result"]?.toString()
                if (transient(cr)) {
                    log("check_res -> $cr -- retrying")
                    Thread.sleep(RETRY_MS)
                } else {
                    log("check_res (capture block 105 body) -> $cr")
                    chkDone = true
                }
            }
            if (!running) return

            // -- game id + session id (the gate assembles these over a beat).
            var gameId: Long? = null
            while (running && gameId == null) {
                gameId = flow.getGameId(gate, rid)
                if (gameId == null) {
                    log("game_id not there yet -- retrying")
                    Thread.sleep(RETRY_MS)
                }
            }
            log("game_id found")
            flow.sendSessionId(gate, gameId!!)

            // -- TURN announce + watch (the watch polls for the peer address;
            //    when it stays blank the slot learns the address from traffic).
            val addr = "${BotMode.BOT_FAB_IP}:${BotMode.BOT_PORT}"
            val blob = Base64.encodeToString(addr.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val sd = flow.sendTurnAddress(gate, opp, blob, addr.length)
            log("turn announce -> ${sd["result"]}")
            flow.getTurnAddress(gate)
            val watchDeadline = System.currentTimeMillis() + WATCH_TIMEOUT_MS
            var peerAddr = ""
            while (running && peerAddr.isBlank() && System.currentTimeMillis() < watchDeadline) {
                val watch = flow.watchTurnAddress(gate)
                peerAddr = (watch["address"] as? String).orEmpty()
                if (peerAddr.isBlank()) Thread.sleep(WATCH_POLL_MS)
            }
            log("turn watch -> peer address " +
                (if (peerAddr.isBlank()) "pending (the slot learns it from traffic)"
                 else peerAddr))

            // -- antenna beat (the liveness signal the server watches).
            val (setR, getR) = flow.antennaBeat(gate, rid, self, opp, gs!!)
            log("antenna set=${setR["result"]} get=${getR["result"]} " +
                "finished=${getR["is_check_finished"]}")

            PeerSlot.arm(opp, gate.uid, peerAddr, localIp, localPort)
            log("connection phase ARMED -- the bot answers .53.2 traffic from here")
            status("CONNECTED -- answering the game's connection packets")

            // Mid-match liveness: re-push the antenna echo on a cadence.
            while (running) {
                Thread.sleep(ANTENNA_INTERVAL_MS)
                if (!running) break
                flow.antennaBeat(gate, rid, self, opp, gs!!)
            }
        } catch (t: Throwable) {
            log("stopped by ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            running = false
        }
    }

    /**
     * Poll the room until the opponent is seated. NO deadline: giving up
     * here is what left the user's game waiting forever (v1's 10-minute
     * timeout). Runs until the driver is stopped; heartbeats meanwhile.
     */
    private fun waitForSeat(flow: BotMatchFlow, gate: KgsGate, rid: Long, self: Long): Long {
        var lastStatusAt = 0L
        while (running) {
            val info = flow.roomState(gate, rid)
            val n = (info["num_users"] as? Number)?.toInt() ?: 0
            if (n >= 2) return findOpponentUid(info, self) ?: 0L
            val now = System.currentTimeMillis()
            if (now - lastStatusAt >= STATUS_EVERY_MS) {
                lastStatusAt = now
                status("waiting for your game to join the room...")
            }
            Thread.sleep(SEAT_POLL_MS)
        }
        return -1L
    }

    /**
     * Scan room_info for the opponent's user id. Defensive on purpose: the
     * exact container field is not yet pinned, so any user_id-style key
     * holding an id that is not the bot counts. If nothing is found the
     * driver continues with 0 (logged) -- the server decides, and the
     * session's address list carries the true id anyway.
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
