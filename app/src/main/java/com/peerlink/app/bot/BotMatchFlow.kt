package com.peerlink.app.bot

import com.peerlink.app.core.AppState

/**
 * The full match ladder -- the Kotlin port of the bundle's `chain3.py` live
 * sequence, reconstructed from the ground-truth record. The CLI proved this
 * order end-to-end: login -> quality -> create room -> [join ladder] ->
 * squads -> entry sides -> ready/go -> session -> TURN exchange -> DTLS hold
 * -> START_GAME both -> phase ladder with mid-phase goals/foul -> result
 * battery -> set_result BOTH sides -> END resend -> get_result polls.
 *
 * Two seams stay explicit rather than faked (both are capture-derived data):
 *  - [SquadSource]: the 23-player squad lists (with `parameters` arrays) that
 *    the SGR checksums and START_GAME lineups are computed from.
 *  - [RESULT via squad data]: check_sum = sum(parameters) - par[59] - par[60]
 *    (46/46 capture rule, 2026-10-13; reversing the run-#40 CHEAT verdict).
 * Every divergence note below is from the record and is load-bearing.
 */
class BotMatchFlow(
    /** Owner-side gate session. */
    private val owner: KgsGate,
    /** Bot-side gate session. */
    private val bot: KgsGate,
    /** Squad lists per uid (id/serial/parameters/averaged_overall...). */
    private val squads: SquadSource,
) {
    /** Player rows as raw maps exactly as the gate exchanged them. */
    fun interface SquadSource {
        fun squadFor(uid: Long): List<Map<String, Any?>>
    }

    companion object {
        private const val PACE_MS = 400L

        /** The capture's own open-NAT word: the peer is punchable. */
        private const val OPEN_NAT_WORD = 0x102000b3L

        /** Capture's inert IPv6 -- "::" / 0 everywhere (block 98). */
        private val NET6 = linkedMapOf<String, Any?>(
            "host_address" to "::", "host_port" to 0,
            "reflexive_address" to "::", "reflexive_port" to 0, "nat_type" to 0,
        )

        /** Capture phase->match-clock (blocks 114-125); monotonic, verbatim. */
        val PHASE_GT = linkedMapOf(
            "1ST_15MIN" to 2921, "1ST_30MIN" to 5264, "BREAK1" to 9098,
            "2ND" to 9403, "2ND_15MIN" to 11160, "2ND_30MIN" to 12947,
            "END" to 15772,
        )

        /** Real starting-XI positions in capture fielding order (4-3-3). */
        val XI_POSITIONS = listOf("GK", "CB", "CB", "LSB", "ST", "RWG", "RSB",
            "CB", "OMF", "LWG", "CF")

        private fun pace() = Thread.sleep(PACE_MS)

        /**
         * check_sum rule (reverse_checksum.py, 46/46 capture pairs): the sum
         * of parameters minus par[59] and par[60]; par[60] is always 0, par[59]
         * carries the difference for every player on both lists. The old
         * 2000+pid%500 shape is what earned the run-#40 CHEAT verdict.
         * max_speed is a measured band (capture starters ~19.5k..32.9k).
         */
        private fun checkEntry(p: Map<String, Any?>): LinkedHashMap<String, Any?> {
            val gid = p["gameplayer_id"] as? Map<*, *>
            val pid = (gid?.get("id") as? Long) ?: (gid?.get("id") as? Int)?.toLong() ?: 0L
            val starting = p["_starting"] == true
            val par = p["parameters"] as? List<*>
            val cs = if (par != null && par.size > 60 && par.all { it is Int || it is Long }) {
                var s = 0L
                for (v in par) s += (v as Number).toLong()
                s - (par[59] as Number).toLong() - (par[60] as Number).toLong()
            } else {
                2000L + (pid % 500)
            }
            return linkedMapOf(
                "gameplayer_id" to linkedMapOf("id" to gid?.get("id"), "serial" to gid?.get("serial")),
                "check_sum" to cs,
                "max_speed" to (if (starting) 19000L + (pid % 14000) else 0L),
            )
        }

        /** One myteam_player_list entry (11 keys, capture-shaped). */
        private fun sgrEntry(p: Map<String, Any?>, idx: Int): LinkedHashMap<String, Any?> {
            val gid = p["gameplayer_id"] as? Map<*, *>
            val starting = idx < 11
            val ao = p["averaged_overall"] as? Number
            val rating = if (ao != null && ao.toDouble() != 0.0) (ao.toDouble() * 10).toInt() else 600
            return linkedMapOf(
                "gameplayer_id" to linkedMapOf("id" to gid?.get("id"), "serial" to gid?.get("serial")),
                "rating" to if (starting) rating else 0,
                "play_time" to if (starting) 90 else 0,
                "position" to (XI_POSITIONS.getOrElse(idx) { "BENCH" }),
                "is_starting_member" to (if (starting) "YES" else "NO"),
                "is_join_game" to (if (starting) "YES" else "NO"),
                "oap" to 0, "base_value" to 0, "is_man_of_the_match" to "NO",
            )
        }

        private fun sgrStats(score: Int, poss: Int, shots: Int, on: Int) = linkedMapOf<String, Any?>(
            "score" to score, "ball_possession" to poss, "shots" to shots,
            "shots_on_goal" to on, "faul" to 0, "faul_offside" to 0,
            "corner_kick" to 0, "free_kick" to 0, "pass_success_rate" to 0,
            "cross" to 0, "pass_cut" to 0, "tackle" to 0, "save" to 0,
        )

        /**
         * CMD_SET_GAME_RESULT body -- rebuilt from the accepted live capture.
         * Three root objects plus the caller's envelope-level game_id. The
         * lists the real client sends EMPTY are load-bearing: omitting them
         * is the ERR_INVALIDARG body-completeness bug.
         */
        fun sgrRequest(
            gameId: Any?, mine: List<Map<String, Any?>>, theirs: List<Map<String, Any?>>,
            homeScore: Int, awayScore: Int, abnormal: String, tickMs: Long?,
        ): LinkedHashMap<String, Any?> {
            val markedMine = mine.mapIndexed { i, p -> LinkedHashMap(p).apply { put("_starting", i < 11) } }
            val markedTheirs = theirs.mapIndexed { i, p -> LinkedHashMap(p).apply { put("_starting", i < 11) } }
            val players = markedMine.mapIndexed { i, p -> sgrEntry(p, i) }
            val myChecks = markedMine.map { checkEntry(it) }
            val oppChecks = markedTheirs.map { checkEntry(it) }

            val matchResult = linkedMapOf<String, Any?>(
                "game_id" to gameId,
                "abnormalend_reason" to abnormal,
                "is_problem" to "NO",
                "user_network_status" to linkedMapOf(
                    "is_stun_keep_alive_failed" to "NO",
                    "is_network_blocked_disconn" to "NO",
                    "is_background_timeout" to "NO",
                    "is_background_at_match" to "NO",
                ),
                "ex_flag" to "NO", "pk_flag" to "NO", "error_code" to "_0x0",
                "myteam_player_list" to players,
                "match_stat_side_info_list" to listOf(
                    sgrStats(homeScore, 62, 2, 1), sgrStats(awayScore, 38, 0, 0)),
                "achievement_progress_list" to emptyList<Any?>(),
                "use_team_style" to "TEAM_STYLE_SHORT_COUNTER",
                "owngoal_user_list" to emptyList<Any?>(),
                "event_record" to 0, "restart_num" to 0, "control_style" to 0,
                "coop_score" to linkedMapOf(
                    "total" to 0, "shoot" to 0, "pass" to 0, "dribble" to 0,
                    "offense_positioning" to 0, "duel" to 0, "defense_positioning" to 0),
                "behind_flag" to listOf("YES", "NO"),
                "tour_pvp_bonus_result_list" to emptyList<Any?>(),
                "ml_event_match_bonus" to linkedMapOf(
                    "count" to 0, "golazo_info" to linkedMapOf("golazo_count_list" to emptyList<Any?>())),
                "opp_gameplayer_id_list" to markedTheirs.take(11).map { p ->
                    val gid = p["gameplayer_id"] as? Map<*, *>
                    linkedMapOf("id" to gid?.get("id"), "serial" to 0)
                },
                "use_setting_multi_formation" to "YES",
            )
            val gameCheck = linkedMapOf<String, Any?>(
                "match_time_frame" to 0,
                // Real capture: wall-clock ms kickoff->SET (822300 for ~13.7 min).
                // elapsed*60 was a CHEAT-grade mismatch; tick is measured.
                "match_time_tick" to (tickMs ?: 822300L),
                "com_stop_info" to 0, "entry_check_data" to 0,
                "ball_move_max_speed" to 117283,
                "gameplayer_parameter_check_data_list" to myChecks,
                "opponent_gameplayer_parameter_check_data_list" to oppChecks,
                "custom_stadium" to linkedMapOf(
                    "stadium_id" to 0, "custom_stadium_name" to "NotImplement",
                    "stadium_color1" to 0, "stadium_color2" to 0,
                    "stadium_color3" to 40, "stadium_color4" to 40,
                    "goalnet_pattern" to 1, "goalnet_color1" to 40,
                    "goalnet_color2" to 40, "pitch_pattern" to 1,
                    "entrance_set" to 1, "bill_set" to 1,
                    "pitchside_objects" to emptyList<Any?>(), "goal_effect1" to 0,
                    "goal_effect2" to 0, "kickoff_effect" to 0,
                    "has_invalid_assets" to "NO"),
                "golazo_info" to linkedMapOf("golazo_count_list" to List(11) { 0 }),
            )
            val analyst = linkedMapOf<String, Any?>(
                "match_json" to """{"Dribble":{"Neutral":524.0,"Normal":141.0,"Finess":37.0,"Dash":0.0},""" +
                    """"Pass":{"Short":2.0,"Through":55.0,"Long":1.0,"Cross":0.0,"CrossGrnd":0.0,"ThroughFly":8.0},""" +
                    """"Shoot":{"Normal":0.0,"Control":0.0,"Impact":0.0,"Loop":0.0},""" +
                    """"Defence":{"Delay":507.0,"Matchup":78.0,"Press":0.0},""" +
                    """"OperationType":"Virtual","ModernControl":"YES","OffenseInstructions":"YES","DeffenseInstructions":"NO"}""",
                "advice_json" to "",
                "advice_info_match" to linkedMapOf(
                    "game_id" to gameId, "advice_info_list" to emptyList<Any?>()),
                "is_invalid_advice" to "YES",
            )
            return linkedMapOf(
                "match_result" to matchResult,
                "game_check_data" to gameCheck,
                "analyst_set_game_result_info" to analyst,
            )
        }
    }

    // ------------------------------------------------------------ ladder
    /**
     * The proven join ladder (sendreq_probe.py, around CMD_JOIN_ROOM).
     * CMD_JOIN_ROOM alone gives NQZT- for both sides; the request/invite
     * exchange is part of the path:
     *   bot SEND_JOIN_ROOM_REQUEST{target:0} -> count
     *   -> owner SEND_JOIN_ROOM_REQUEST{target:bot} (invite, if needed)
     *   -> both GET_REQUESTED_JOIN_ROOM_INFO -> bot JOIN_ROOM (CUSTOM)
     *   -> count == 2.
     */
    fun sendJoinRoomRequest(g: KgsGate, target: Long, rid: Long) =
        g.step("CMD_SEND_JOIN_ROOM_REQUEST", linkedMapOf(
            "target_user_id" to target,
            "room_id" to linkedMapOf("id" to rid),
        ))

    fun getRequestedJoinRoomInfo(g: KgsGate, sender: Long) =
        g.step("CMD_GET_REQUESTED_JOIN_ROOM_INFO", linkedMapOf("sender_user_id" to sender))

    /** CMD_GET_ROOM_INFO -> room_info.num_users (the seating probe). */
    fun seatedCount(g: KgsGate, rid: Long): Any? {
        val r = g.step("CMD_GET_ROOM_INFO", linkedMapOf(
            "mode" to "CUSTOM", "id" to 0,
            "room_id" to linkedMapOf("id" to rid),
            "event_id" to 0, "user_compe_id" to 0, "is_room_search" to "NO",
        ))
        val info = r["room_info"] as? Map<*, *> ?: return "err:${r["result"]}"
        return info["num_users"]
    }

    /** CMD_JOIN_ROOM (room2.join_body verbatim; probe sets mode CUSTOM). */
    fun joinRoom(g: KgsGate, rid: Long, nat4: KgsGate.NatProfile) =
        g.step("CMD_JOIN_ROOM", linkedMapOf(
            "mode" to "CUSTOM",
            "room_id" to linkedMapOf("id" to rid),
            "event_id" to 0, "password" to "", "is_need_password" to "NO",
            "address_ipv4" to linkedMapOf(
                "host_address" to nat4.hostAddress, "host_port" to nat4.hostPort,
                "reflexive_address" to nat4.reflexiveAddress,
                "reflexive_port" to nat4.reflexivePort, "nat_type" to nat4.natType),
            "address_ipv6" to NET6,
            "platform_session_id_info" to linkedMapOf(
                "ps_session_id" to "", "xb_session_id" to ""),
            "event_account_type" to "GAME_PLAYER",
            "is_invited" to "NO",
            "strike_arena_selected_info" to listOf(
                linkedMapOf("player_id" to 1, "costume_id" to 0)),
            "user_compe_id" to 0,
        ))

    /** The full join ladder; returns final seat count (2 = both seated). */
    fun runJoinLadder(rid: Long, ownerNat: KgsGate.NatProfile, botNat: KgsGate.NatProfile): Long {
        sendJoinRoomRequest(bot, 0L, rid)
        pace()
        val n1 = seatedCount(owner, rid)
        pace()
        if ((n1 as? Long ?: ((n1 as? Int)?.toLong() ?: 0L)) < 2L) {
            sendJoinRoomRequest(owner, bot.uid, rid)
            pace()
        }
        getRequestedJoinRoomInfo(bot, owner.uid)
        pace()
        getRequestedJoinRoomInfo(owner, bot.uid)
        pace()
        joinRoom(bot, rid, botNat)
        pace()
        val n2 = seatedCount(owner, rid)
        pace()
        return when (n2) {
            is Long -> n2
            is Int -> n2.toLong()
            else -> -1L
        }
    }

    /** CMD_GET_USER_SQUAD -- the roster fetch that feeds [SquadSource]. */
    fun userSquad(g: KgsGate, target: Long) =
        g.step("CMD_GET_USER_SQUAD", linkedMapOf("target_user_id" to target))

    /** CMD_USER_DATA_SYNC (11.1.0 builder FUN_07eadd40), sent by BOTH clients. */
    fun userDataSync(g: KgsGate, side: String, userName: String) =
        g.step("CMD_USER_DATA_SYNC", linkedMapOf(
            "name" to userName, "pad_no" to 0,
            "np_account_id" to "", "xuid_one" to "",
            "platform_id" to "ANDROID", "platform_user_name" to userName,
            "avator" to linkedMapOf(
                "is_already_set" to "NO", "player_id" to 0, "special_id" to 0,
                "background_id" to 0, "special_id_background" to 0,
                "is_enable_player" to "YES", "is_enable_background" to "YES",
                "material_key_player" to "", "material_key_background" to ""),
            "user_info" to linkedMapOf(
                "user_id" to g.uid, "platform_kind" to "ANDROID",
                "is_platform_game_session" to "NO"),
            "side" to side, "guest_num" to 0,
        ))

    /** CMD_TEAM_DATA_SYNC -- both clients, right before CMD_START_GAME. */
    fun teamDataSync(g: KgsGate, side: String, jsonData: String) =
        g.step("CMD_TEAM_DATA_SYNC", linkedMapOf("side" to side, "json_data" to jsonData))

    /** CMD_SET_ROOM_USER_ENTRY_SIDE (verbatim). */
    fun entrySide(g: KgsGate, rid: Long, side: String, leader: Boolean) =
        g.step("CMD_SET_ROOM_USER_ENTRY_SIDE", linkedMapOf(
            "room_id" to linkedMapOf("id" to rid),
            "entry_side" to side,
            "is_entry_side_leader" to (if (leader) "YES" else "NO"),
        ))

    /**
     * CMD_SET_ROOM_MATCH_READY (verbatim). Captured values that measured
     * NOERR: is_single_matching "NO", gamerelay_measure_result "COMPLETE"
     * -- "MEASUREING" was a server-reached state we declared too early.
     */
    fun matchReady(g: KgsGate, rid: Long, nat4: KgsGate.NatProfile) =
        g.step("CMD_SET_ROOM_MATCH_READY", linkedMapOf(
            "room_id" to linkedMapOf("id" to rid),
            "is_single_matching" to "NO",
            "gamerelay_measure_result" to "COMPLETE",
            "address_ipv4" to linkedMapOf(
                "host_address" to nat4.hostAddress, "host_port" to nat4.hostPort,
                "reflexive_address" to nat4.reflexiveAddress,
                "reflexive_port" to nat4.reflexivePort,
                "nat_type" to nat4.natType),
            "address_ipv6" to NET6,
        ))

    /**
     * CMD_GET_GAME_SESSION (capture block 102 fixes): session_use_kind
     * "LOBBY_IN_ROOM" and room_id {"id": n} -- the reply is the P2P
     * rendezvous set (game_sycom + gamerelay).
     */
    fun gameSession(g: KgsGate, rid: Long) =
        g.step("CMD_GET_GAME_SESSION", linkedMapOf(
            "session_use_kind" to "LOBBY_IN_ROOM",
            "room_id" to linkedMapOf("id" to rid),
        ))

    fun checkMatchEnable(g: KgsGate) =
        g.step("CMD_CHECK_MATCH_ENABLE", linkedMapOf("event_id" to 0))

    /** CMD_CHANGE_GAMEPHASE with game_check_data attached (verbatim). */
    fun changePhase(g: KgsGate, gameId: Any?, phase: String, gtimer: Long) =
        g.step("CMD_CHANGE_GAMEPHASE", linkedMapOf(
            "game_id" to gameId, "phase" to phase, "gtimer" to gtimer,
            "game_check_data" to (phaseGameCheck(phase, gtimer)),
        ))

    /** game_check_data for phase changes -- capture form. */
    private fun phaseGameCheck(phase: String, gtimer: Long) = linkedMapOf<String, Any?>(
        "phase" to phase, "gtimer" to gtimer,
    )

    /**
     * CMD_ADD_SCORE (blocks 116/120 fixes): goals filed DURING their live
     * phase; the SENDING uid goes on every goal on both sides; assist is 0
     * (never self-assisted); scorers are outfield starters, never the GK.
     * phase="END" goals are accepted but never counted (run #41's 0-0).
     */
    fun addScore(
        g: KgsGate, gameId: Any?, goalUser: Long, side: String,
        scorerId: Any?, scorerSerial: Int, minute: Int, phase: String, gtimer: Long,
    ) = g.step("CMD_ADD_SCORE", linkedMapOf(
        "game_id" to gameId,
        "goal_user_id" to goalUser, "goal_side" to side,
        "goal_gameplayer_id" to scorerId, "goal_player_side" to side,
        "assist_user_id" to 0, "assist_side" to side,
        "assist_gameplayer_id" to scorerId, "assist_player_side" to side,
        "gtimer" to gtimer, "phase" to phase, "match_minute" to minute,
    ))

    /** CMD_ADD_FOUL (block 122): one carded foul, 92', 2ND_30MIN. */
    fun addFoul(
        g: KgsGate, gameId: Any?, cardUser: Long, side: String,
        cardPlayerId: Any?, cardPlayerSerial: Int, minute: Int, phase: String,
    ): LinkedHashMap<String, Any?> {
        val gid = linkedMapOf("id" to cardPlayerId, "serial" to cardPlayerSerial)
        return g.step("CMD_ADD_FOUL", linkedMapOf(
            "game_id" to gameId,
            "card_user_id" to cardUser, "card_side" to side,
            "card_gameplayer_id" to gid, "card_kind" to "NONE",
            "injury_user_id" to 0, "injury_side" to "HOME",
            "injury_gameplayer_id" to gid,
            "gtimer" to (minute * 60L), "phase" to phase, "match_minute" to minute,
        ))
    }

    /** CMD_START_GAME -- BOTH clients send it, each with its own side. */
    fun startGame(
        g: KgsGate, gameId: Any?, teamId: Any?,
        home: List<Any?>, away: List<Any?>, side: String,
    ) = g.step("CMD_START_GAME", linkedMapOf(
        "match_category" to "MYCLUB_LOBBY_ROOM_MATCH_1VS1",  // captured value; the plain LOBBY_ variant is a different category
        "game_id" to gameId, "side" to side,
        "player_list_home" to home, "player_list_away" to away,
        "team_id" to teamId, "is_vs_aimatch" to "NO",
    ))

    /** CMD_SET_GAME_RESULT -- one side's report (see [sgrRequest]). */
    fun setGameResult(
        g: KgsGate, gameId: Any?, mine: List<Map<String, Any?>>,
        theirs: List<Map<String, Any?>>, homeScore: Int, awayScore: Int,
        abnormal: String, tickMs: Long?,
    ) = g.step("CMD_SET_GAME_RESULT", sgrRequest(gameId, mine, theirs, homeScore, awayScore, abnormal, tickMs)
        .apply { put("game_id", gameId) })

    /** CMD_GET_GAME_RESULT verdict -- poll until the server resolves. */
    fun getGameResult(g: KgsGate, gameId: Any?): Map<String, Any?> =
        g.step("CMD_GET_GAME_RESULT", linkedMapOf("game_id" to gameId))

    /**
     * The post-kickoff phase ladder (capture 114-125): phases in order with
     * the monotonic match clock, the 2-1 goal pattern filed DURING their
     * phases (16'/34' HOME, 61' AWAY), and the single 92' foul. Scorers are
     * outfield starters from the lineups.
     */
    fun runMatchLadder(gameId: Any?, rid: Long, home: List<Map<String, Any?>>, away: List<Map<String, Any?>>) {
        val scorerH = home.getOrNull(4)?.gid()
        val scorerA = away.getOrNull(10)?.gid()
        pace()
        for (p in listOf("1ST_15MIN", "1ST_30MIN", "BREAK1", "2ND", "2ND_15MIN", "2ND_30MIN", "END")) {
            changePhase(owner, gameId, p, PHASE_GT[p]!!.toLong())
            pace()
            when (p) {
                "1ST_15MIN" -> addScore(owner, gameId, owner.uid, "HOME", scorerH, 0, 16, p, 3000)
                "1ST_30MIN" -> addScore(owner, gameId, owner.uid, "HOME", scorerH, 0, 34, p, 7000)
                "2ND_15MIN" -> addScore(owner, gameId, owner.uid, "AWAY", scorerA, 0, 61, p, 12000)
                "2ND_30MIN" -> addFoul(owner, gameId, owner.uid, "AWAY",
                    scorerA?.get("id"), (scorerA?.get("serial") as? Number)?.toInt() ?: 0, 92, p)
            }
        }
    }

    // -------------------------------------------------- pre-connection half
    /** CMD_GET_ROOM_INFO -> room_info (the state the driver polls). */
    fun roomState(g: KgsGate, rid: Long): Map<*, *> {
        val r = g.step("CMD_GET_ROOM_INFO", linkedMapOf(
            "mode" to "CUSTOM", "id" to 0,
            "room_id" to linkedMapOf("id" to rid),
            "event_id" to 0, "user_compe_id" to 0, "is_room_search" to "NO",
        ))
        return (r["room_info"] as? Map<*, *>) ?: emptyMap<String, Any?>()
    }

    /**
     * From a CMD_GET_GAME_SESSION reply: the OTHER user's address entry of
     * ntl_address_list. The server distributes both sides' NAT blocks here --
     * the automatic source of the game's address (host half = its real local
     * socket, reflexive half = what it announced).
     */
    fun opponentAddress(gs: Map<*, *>, selfU: Long): Map<*, *>? {
        val list = gs["ntl_address_list"] as? List<*> ?: return null
        for (e in list) {
            val m = e as? Map<*, *> ?: continue
            val uid = (m["user_id"] as? Number)?.toLong() ?: 0L
            if (uid != selfU) return m
        }
        return null
    }

    /**
     * Hand the engine the opponent, in the engine's own record layout.
     *
     * The session reply carries the opponent's address blocks for BOTH
     * families and their NAT types, which is exactly what the engine's peer
     * record is made of.  The blocks are written by the engine's own
     * builder, so the record is the engine's own shape and not a copy.
     *
     * Both NAT words must say the peer is punchable.  The engine's
     * transport adds the peer to the IPv4 channel AND the IPv6 channel and
     * keeps the peer's id only when both succeed; without the IPv6 half
     * the id is thrown away and the connect stops at the punch.
     */
    fun handPeerToEngine(entry: Map<*, *>, peerId: Long): Boolean {
        if (EngineHost.base == 0L) return false
        AppState.appendLog("[BOT-FLOW  ] engine hand off: building the peer record")
        try {
            val a4 = entry["address_ipv4"] as? Map<*, *> ?: return false
            val a6 = entry["address_ipv6"] as? Map<*, *>
            fun n(m: Map<*, *>?, k: String): Long =
                (m?.get(k) as? Number)?.toLong() ?: 0L

            val v4Nat = n(a4, "nat_type")
            val v6Nat = n(a6, "nat_type")

            // The opponent the ENGINE is given is this app's own peer slot.  The
            // tunnel carries the game alone, so the engine's frames can only meet
            // the game's frames on loopback: it is told its peer is
            // 127.0.0.1:1163 (and [::1]:1163), it punches there, the slot carries
            // its frames to the game as the fabricated peer, and the game's frames
            // are handed back to it.  The NAT word is the capture's own open-NAT
            // value, so the engine treats the peer as reachable, exactly as it
            // would on a phone behind an open NAT.
            val rec = EngineHost.buildPeerRecord(
                peerId = peerId,
                v4Host = PeerSlot.LOOPBACK_PEER_IP, v4HostPort = PeerSlot.ENGINE_PORT,
                v4Reflexive = PeerSlot.LOOPBACK_PEER_IP,
                v4ReflexivePort = PeerSlot.ENGINE_PORT, v4NatType = OPEN_NAT_WORD,
                v6Host = "::1", v6HostPort = PeerSlot.ENGINE_PORT,
                v6Reflexive = "::1",
                v6ReflexivePort = PeerSlot.ENGINE_PORT, v6NatType = OPEN_NAT_WORD,
            ) ?: return false
            val ok = EngineHost.setPeers(rec)
            if (ok) {
                PeerSlot.engineAttached = true
                // The engine's own punch (0x7db9b40 in the lab): the entry that
                // makes it speak.  Without it the engine may hold the record and
                // sit still, and the game would get nothing.
                AppState.appendLog("[BOT-FLOW  ] engine punch -> ${EngineHost.punch()}")
                watchEngineVerdict()
            }
            AppState.appendLog("[BOT-FLOW  ] engine got the peer: $peerId " +
                "(session said v4nat=$v4Nat v6nat=$v6Nat) -- its opponent is now " +
                "${PeerSlot.LOOPBACK_PEER_IP}:${PeerSlot.ENGINE_PORT} -> $ok")
            return ok
        } catch (t: Throwable) {
            // Anything failing in the hand-off must leave the app alive and
            // say so -- the app must never die in the middle of a match.
            AppState.appendLog("[BOT-FLOW  ] engine hand off failed: $t")
            return false
        }
    }

    /**
     * Drive the engine the way the real game does -- its per-frame update
     * (the poll) every ~100 ms -- and report its own verdict for a while.
     * Its check returns 1 when its transport says the peer is connected;
     * that number, and whether the engine has actually been heard from, is
     * the whole answer to "did the engine take over".
     */
    private fun watchEngineVerdict() {
        Thread({
            var last = -1L
            var polls = 0L
            val until = System.currentTimeMillis() + 60_000L
            while (System.currentTimeMillis() < until) {
                try {
                    EngineHost.poll()
                } catch (_: Throwable) {
                }
                polls++
                if (polls % 10L == 0L) {
                    val v = try {
                        EngineHost.check()
                    } catch (_: Throwable) {
                        -1L
                    }
                    if (v != last) {
                        AppState.appendLog("[BOT-FLOW  ] engine verdict=$v " +
                            "heard=${PeerSlot.engineHeard} polls=$polls")
                        last = v
                    }
                    if (v == 1L) break
                }
                Thread.sleep(100)
            }
        }, "engine-verdict").apply { isDaemon = true }.start()
    }


    /** CMD_GET_GAME_ID -- builder form first, then the server-side fallbacks. */
    fun getGameId(g: KgsGate, rid: Long): Long? {
        val forms = listOf(
            "map-int" to linkedMapOf<String, Any?>("room_id" to linkedMapOf("id" to rid)),
            "flat-int" to linkedMapOf<String, Any?>("room_id" to rid),
            "str" to linkedMapOf<String, Any?>("room_id" to rid.toString()),
            "arr-str" to linkedMapOf<String, Any?>("room_id" to listOf(rid.toString())),
            "map-str" to linkedMapOf<String, Any?>("room_id" to linkedMapOf("id" to rid.toString())),
            "roominfo" to linkedMapOf<String, Any?>(
                "mode" to "CUSTOM", "id" to 0,
                "room_id" to linkedMapOf("id" to rid),
                "event_id" to 0, "user_compe_id" to 0, "is_room_search" to "NO"),
        )
        for ((_, extra) in forms) {
            val r = g.step("CMD_GET_GAME_ID", extra)
            val gid = findKeyIn(r, "game_id") as? Number
            if (r["result"] == "NOERR" && gid != null) return gid.toLong()
            Thread.sleep(PACE_MS)
        }
        return null
    }

    /** CMD_SEND_SESSION_ID (FUN_07f88880 body; Android sends both ids empty). */
    fun sendSessionId(g: KgsGate, gameId: Long) = g.step("CMD_SEND_SESSION_ID", linkedMapOf(
        "game_id" to gameId,
        "platform_session_id_info" to linkedMapOf("ps_session_id" to "", "xb_session_id" to ""),
    ))

    /**
     * CMD_SET_GAME_SESSION_CHECK_RES -- the CAPTURE block-105 connection report
     * (six capture-exact fields: matching_num 0, answer "NO", antenna_min 3,
     * error_code "_0x0", link_type "Ethernet", create_session_time 0 -- all
     * flagged in the record as kickoff-critical).
     */
    fun checkResEcho(g: KgsGate, rid: Long, selfU: Long, oppU: Long, gs: Map<*, *>) =
        g.step("CMD_SET_GAME_SESSION_CHECK_RES", linkedMapOf(
            "game_sycom" to (gs["game_sycom"] as? String ?: ""),
            "error_code" to "NOERR",
            "gamerelay" to relayBlock(gs),
            "failed_reason" to 0,
            "is_lobby" to "YES",
            "room_id" to linkedMapOf("id" to rid),
            "statistical_data" to linkedMapOf(
                "user_id_list" to listOf(selfU, oppU), "matching_num" to 0,
                "answer" to "NO", "antenna_min" to 3,
                "error_code" to "_0x0", "link_type" to "Ethernet",
                "create_session_time" to 0, "background_times" to "",
            ),
        ))

    /**
     * The 11.1.0 antenna heartbeat: SET echo (mid-match statistical_data
     * variant) + GET poll. This is the liveness signal the real client keeps
     * pushing through the WHOLE match.
     */
    fun antennaBeat(
        g: KgsGate, rid: Long, selfU: Long, oppU: Long, gs: Map<*, *>,
    ): Pair<LinkedHashMap<String, Any?>, LinkedHashMap<String, Any?>> {
        val set = g.step("CMD_SET_GAME_SESSION_CHECK_RES", linkedMapOf(
            "game_sycom" to (gs["game_sycom"] as? String ?: ""),
            "error_code" to "NOERR",
            "gamerelay" to relayBlock(gs),
            "failed_reason" to 0,
            "is_lobby" to "YES",
            "room_id" to linkedMapOf("id" to rid),
            "statistical_data" to linkedMapOf(
                "user_id_list" to listOf(selfU, oppU), "matching_num" to 2,
                "answer" to "", "antenna_min" to asLong(gs["ping"]),
                "error_code" to "NOERR", "link_type" to "",
                "create_session_time" to asLong(gs["connecting_start_time"]),
                "background_times" to "",
            ),
        ))
        val get = g.step("CMD_GET_GAME_SESSION_CHECK_RES", linkedMapOf(
            "room_id" to linkedMapOf("id" to rid), "is_lobby" to "YES",
            "game_entry_user_list" to listOf(selfU, oppU),
        ))
        return set to get
    }

    /** The gamerelay block both session-check bodies echo from the server. */
    private fun relayBlock(gs: Map<*, *>): LinkedHashMap<String, Any?> {
        val gr = gs["gamerelay"] as? Map<*, *> ?: emptyMap<String, Any?>()
        return linkedMapOf(
            "name" to (gr["name"] as? String ?: ""),
            "region" to (gr["region"] as? String ?: ""),
            "ip_addr" to (gr["ip_addr"] as? String ?: ""),
            "port" to asLong(gr["port"]),
        )
    }

    private fun asLong(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is String -> v.toLongOrNull() ?: 0L
        else -> 0L
    }

    /** CMD_SEND_TURN_ADDRESS_DATA -- announce this side's address to the peer. */
    fun sendTurnAddress(g: KgsGate, target: Long, addressB64: String, size: Int) =
        g.step("CMD_SEND_TURN_ADDRESS_DATA", linkedMapOf(
            "target_user_id" to target, "address" to addressB64,
            "address_data_size" to size,
        ))

    /** CMD_GET_TURN_ADDRESS_DATA -- the gate answers with our own address. */
    fun getTurnAddress(g: KgsGate) = g.step("CMD_GET_TURN_ADDRESS_DATA")

    /** CMD_WATCH_TURN_ADDRESS_DATA -- yields the peer's announced address. */
    fun watchTurnAddress(g: KgsGate) = g.step("CMD_WATCH_TURN_ADDRESS_DATA")

    private fun Map<String, Any?>.gid(): Map<String, Any?> =
        (this["gameplayer_id"] as? Map<String, Any?>) ?: emptyMap()
}

/** Recursive key search (the bundle's `find_key`): first match wins. */
internal fun findKeyIn(o: Any?, key: String): Any? = when (o) {
    is Map<*, *> -> if (o.containsKey(key)) o[key]
        else o.values.firstNotNullOfOrNull { findKeyIn(it, key) }
    is List<*> -> o.firstNotNullOfOrNull { findKeyIn(it, key) }
    else -> null
}
