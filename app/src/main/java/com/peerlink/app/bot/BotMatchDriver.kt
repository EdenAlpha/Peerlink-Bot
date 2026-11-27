package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import com.peerlink.app.service.PeerBotService
import kotlin.concurrent.thread

/**
 * Bot mode step 3 -- the state-driven driver (v2, 2026-10-15 late).
 *
 * The original transport also requires the authenticated TURN configuration
 * and opaque native address exchange while its session is connecting.
 *   transport configuration -> seat -> entry side (HOME/leader) -> check_match_enable
 *   -> match_ready (.53.2) -> go_match -> game_session -> opponent address
 *   -> original session + TURN exchange -> confirmed server connection check
 *   -> game_id -> send_session_id -> original engine match setup.
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

    /** Re-assert the bot's ready on this cadence while waiting for go. */
    private const val READY_REASSERT_MS = 20_000L

    /** Notification heartbeat while nothing else is moving. */
    private const val STATUS_EVERY_MS = 30_000L

    /** Retry spacing for transient ("_NET_FAIL"-class) answers. */
    private const val RETRY_MS = 3_000L

    /** TURN watch: poll for the peer address, then carry on without it. */
    private const val WATCH_TIMEOUT_MS = 60_000L

    @Volatile private var driver: Thread? = null
    @Volatile private var stopping = false
    private val running get() = Thread.currentThread() === driver && !stopping

    @Synchronized fun start(gate: KgsGate, rid: Long, roomNumber: String, onMatchStarting: (() -> Unit)?,
                            prepareNextEngine: (() -> Boolean) -> String?): Boolean {
        if (driver != null) return false
        stopping = false
        val worker = thread(start = false, name = "bot-match-driver") {
            run(gate, rid, roomNumber, onMatchStarting, prepareNextEngine)
        }
        driver = worker
        worker.start()
        return true
    }

    @Synchronized fun stop(reason: String = "driver_stop") {
        log("PEERLINK_STOP component=match_driver reason=$reason observedAtMs=${android.os.SystemClock.elapsedRealtime()}")
        stopping = true
        driver?.interrupt()
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

    private fun run(gate: KgsGate, rid: Long, roomNumber: String, onMatchStarting: (() -> Unit)?,
                    prepareNextEngine: (() -> Boolean) -> String?) {
        val flow = BotMatchFlow(gate, gate, BotMatchFlow.SquadSource { emptyList() })
        try {
            BotRoomRounds(
                active = { running },
                play = { room, prior -> playRound(flow, gate, room, roomNumber, prior,
                    onMatchStarting.takeIf { prior == null }) },
                readRoom = { room -> flow.roomReply(gate, room) },
                finishEngine = {
                    EngineHost.finishRound()
                    if (running) PeerSlot.finishRound()
                },
                prepareEngine = { prepareNextEngine { running } },
                status = ::status,
                resultOrProcessExit = { !EngineHost.isReady || EngineHost.roundEnd(EngineHost.enginePid) == RoomRoundEnd.RESULT },
                requestLobbyReady = { room, lastStart ->
                    val result = flow.matchReady(gate, room, BotMode.declaredProfile())["result"]?.toString()
                    log("ROOM_RETURN_READY result=$result")
                    result == "NOERR" || BotRoomRounds.alreadyReady(result) &&
                        BotRoomRounds.freshSession(flow.gameSession(gate, room), lastStart)
                },
            ).run(rid, roomNumber)
        } catch (error: Exception) {
            if (running) {
                log("DRIVER_EXCEPTION type=${error.javaClass.simpleName} observedAtMs=${android.os.SystemClock.elapsedRealtime()}")
                PeerSlot.logFlow("driver_exception")
                status(error.message ?: "Bot match setup stopped")
            }
        } finally {
            synchronized(this) { driver = null }
        }
    }

    private fun playRound(flow: BotMatchFlow, gate: KgsGate, rid: Long, roomNumber: String,
                          previous: CompletedRoomMatch?, onMatchStarting: (() -> Unit)?): CompletedRoomMatch? {
        val self = gate.uid
        val pid = EngineHost.enginePid
        var connectingStart = 0L
        try {
            check(EngineHost.isReady) { "Bot simulation is not ready; start a new room" }
            // Apply the same server settings as the regular client before
            // constructing the original match-data model.
            while (running) {
                val environment = gate.step("CMD_GET_SERVER_ENV")
                if (transient(environment["result"]?.toString())) { Thread.sleep(RETRY_MS); continue }
                val settings = MatchServerSettings.fromResponse(environment)
                check(EngineHost.configureServerSettings(settings)) {
                    "The bot could not apply the server's match settings"
                }
                log("SERVER_MATCH_SETTINGS source=CMD_GET_SERVER_ENV is_revision_check=${if (settings.revisionCheck) "YES" else "NO"} nativeApplied=true")
                break
            }
            if (!running) return null
            val squadReply = flow.userSquad(gate, self)
            check(squadReply["result"] == "NOERR") { "Could not read the bot's current squad" }
            val squadInput = SquadInput.encode(squadReply, self)
            while (running) {
                val turn = flow.turnServerList(gate, EngineHost.turnRequest())
                val result = turn["result"]?.toString()
                if (transient(result)) { Thread.sleep(RETRY_MS); continue }
                check(result == "NOERR") { "The match transport configuration was refused: $result" }
                check(EngineHost.configureTurn(TurnConfiguration.encode(turn))) {
                    "The bot engine did not accept the server's match transport configuration"
                }
                log("original match transport configured from the authenticated server response")
                break
            }
            if (!running) return null
            status(if (previous == null) "room $roomNumber open -- join it from eFootball now"
                else "Bot is back in room $roomNumber. Getting ready for the next match…")

            val oppSeen = waitForSeat(flow, gate, rid, self)
            if (!running) return null
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
            if (!running) return null

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
            if (!running) return null

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
            if (!running) return null

            // -- ready + go_match watch. The ready is re-asserted on a
            //    cadence: the server wants both sides' ready, and the game
            //    sends ITS ready when the user presses To Match. A lost
            //    ready must never be the reason the room stalls.
            log("bot endpoint: host=${BotMode.hostIp}:${BotMode.BOT_PORT} " +
                "reflexive=${BotMode.BOT_FAB_IP}:${BotMode.BOT_PORT}")
            val go = BotRoomRounds.awaitGo(
                previousStart = previous?.sessionStart, active = { running },
                setReady = { flow.matchReady(gate, rid, BotMode.declaredProfile())["result"]?.toString() },
                readRoom = { flow.roomReply(gate, rid) }, readSession = { flow.gameSession(gate, rid) },
                status = ::status, log = ::log,
            ) ?: return null
            var gs = go.confirmedSession
            if (!running) return null
            status("both sides ready -- the game is entering the connection phase")

            // The game punches the moment its tunnel exists: make the peer
            // slot live NOW (reactive) and fill its target once the session
            // reply carries the game's address (arm is idempotent).
            PeerSlot.arm(opp, gate.uid, "", "", 0)
            runCatching { onMatchStarting?.invoke() }
                .onFailure { log("Scheduler start request failed: ${it.javaClass.simpleName}") }

            // -- game_session + the opponent's address (retried together:
            //    the reply must ALSO contain the address block).
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
            if (!running) return null

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
            check(gs!!["game_sycom"] == "P2P") { "The server selected relay; this bot test requires P2P" }
            connectingStart = (gs!!["connecting_start_time"] as? Number)?.toLong() ?: 0L
            check(flow.handPeerToEngine(oppBlock!!, opp, connectingStart)) { "The bot engine did not accept the server's session" }
            val turnExchange = NativeTurnExchange(
                localAddress = EngineHost::turnAddress,
                acceptPeer = EngineHost::peerTurnAddress,
                send = { address, size -> flow.sendTurnAddress(gate, opp, address, size) },
                get = { flow.getTurnAddress(gate) },
                watch = { flow.watchTurnAddress(gate) },
                log = ::log,
            )
            // Confirm the original peer transport, then send the recorded
            // server report. Waiting here for data channels blocks the gate
            // sequence that supplies the later game/session setup inputs.
            status("Connecting both game engines")
            var lastIntroductionVerdict = -1
            if (!PeerConnectionPhase.openAfterNetworkIo(
                running = { running }, transportIoReady = EngineHost::transportIoReady,
                openSession = { EngineHost.openSession(opp, connectingStart) },
                progress = { verdict ->
                    if (verdict != lastIntroductionVerdict) {
                        log("original network I/O state=$verdict")
                        lastIntroductionVerdict = verdict
                    }
                },
            )) return null
            var lastConnectionState = ""
            if (!PeerConnectionPhase.awaitTransport(
                running = { running },
                snapshot = EngineHost::snapshot,
                prepareChannels = { EngineHost.prepareChannels(opp, squadInput) },
                exchangeAddresses = turnExchange::pump,
                progress = { state ->
                    val progress = state.values.take(8).joinToString(",") + ",${state.matchDataHandlerPresent},${state.configuredTurnTransport}"
                    if (progress != lastConnectionState) {
                        log("native connection state [core,sdk,session,channel14,channel15,model,complete,error,handler,configuredTurn]=$progress")
                        lastConnectionState = progress
                    }
                },
            )) return null
            log("original peer transport confirmed; reporting connection before waiting for match data")
            status("Peer link connected — confirming match setup with the server")

            if (!PeerConnectionPhase.confirmServerConnection(
                running = { running },
                report = { flow.checkResEcho(gate, rid, self, opp, gs!!) },
                poll = { flow.pollConnectionReport(gate, rid, self, opp) },
                exchangeAddresses = turnExchange::pump,
                progress = ::log,
            )) return null

            // -- game id + session id (the gate assembles these over a beat).
            var gameId: Long? = null
            while (running && gameId == null) {
                gameId = flow.getGameId(gate, rid)
                if (gameId == null) {
                    log("game_id not there yet -- retrying")
                    Thread.sleep(RETRY_MS)
                }
            }
            if (!running) return null
            log("game_id found")
            val sessionIdReply = flow.sendSessionId(gate, gameId!!)
            log("send_session_id -> ${sessionIdReply["result"] ?: "unavailable"}")
            status("Connected — synchronizing team data")
            if (!PeerConnectionPhase.awaitMetadata(
                running = { running }, snapshot = EngineHost::snapshot,
                exchangeAddresses = turnExchange::pump,
            )) return null
            status("Connected — setting up the bot's match")
            val room = flow.roomState(gate, rid)
            val matchSettings = room["room_match_settings"] as? Map<*, *>
            val environment = matchSettings?.get("match_env") as? Map<*, *>
                ?: error("The server did not provide this room's match rules")
            val roomRules = RoomMatchRules.encode(environment)
            check(EngineHost.setupMatch(opp, gameId!!, gate.sid, squadInput, roomRules)) { "The bot's native match setup was rejected" }
            log("ROOM_MATCH_RULES source=CMD_GET_ROOM_INFO match_time=${environment["match_time"]} " +
                "cpu_level=${environment["cpu_level"]} injury=${environment["injury"]} " +
                "ball_type=${environment["ball_type"]} nativeInputSupplied=true")
            val controllersAllowed = environment["can_use_mobile_controller"] == "YES"
            check(EngineHost.startInputTest(controllersAllowed)) { "Enable mobile controllers in the room settings so the bot can play" }

            turnExchange.pump()

            log("connection phase ARMED -- the ferry is live; the ENGINE owns " +
                "every answer from here, the bot answers nothing")
            // The decisive moment: whether the engine is up.  With the
            // fabricated answerers DELETED (2026-10-24), an engine that is
            // not loaded means an honest dead connection -- never a fake one.
            log("engine at the connection phase: loaded=${EngineHost.isReady} " +
                "peerPort=${com.peerlink.app.core.AppState.enginePeerPort}")
            status(EngineHost.snapshot().status())

            while (running) {
                EngineHost.roundEnd(pid)?.let { return CompletedRoomMatch(it, connectingStart) }
                Thread.sleep(1000)
                if (running && EngineHost.roundEnd(pid) == null) turnExchange.pump()
            }
        } catch (error: Exception) {
            if (running && connectingStart > 0L) {
                EngineHost.roundEnd(pid)?.let { return CompletedRoomMatch(it, connectingStart) }
            }
            throw error
        }
        return null
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
