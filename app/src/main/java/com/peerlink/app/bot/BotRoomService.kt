package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.play.BotRoomBackend

/**
 * The bot's room service -- the per-session gate flow in the proven order
 * (bundle: RUN.txt + room1/room2), now in BOT MODE addressing (locked
 * 2026-10-15):
 *
 *   fixed fabricated pair (game .53.1 / bot .53.2) + bot T7 beats
 *   -> CMD_LOGIN (bot account)
 *   -> CMD_SET_GAMERELAY_QUALITY [when real data exists]
 *   -> CMD_CREATEJOIN_ROOM -> room number for the user's own eFootball.
 *
 * The declared endpoint is the fabricated peer identity: no real address, no
 * measurement, and no empty-address path (KgsGate.createRoom refuses a blank
 * endpoint outright). Fails honestly when a step has no real data (no bot
 * account, no quality template). Never invents a room number.
 */
object BotRoomService : BotRoomBackend {

    /** Relay-quality entries captured from the real client, if the phone has them. */
    @Volatile
    var qualityTemplate: List<Map<String, Any?>>? = null

    override suspend fun createRoom(ctx: Context): String {
        val loaded = BotIdentity.load(ctx)
        val identity = if (loaded != null) loaded else {
            // First room on this phone: mint the bot's account (the mint.py
            // port), verify it with the proven login, keep it in app-private
            // prefs. One tap, nothing to type.
            AppState.appendLog("[BOT-MINT ] no bot account yet -- creating one")
            val minted = BotMinter.mint()
                ?: throw KgsGate.GateException(
                    "Couldn't create the bot account (Konami's gate refused the " +
                        "sign-up). Try once more -- if it keeps failing, watch the " +
                        "log for [BOT-MINT] lines."
                )
            BotIdentity.save(ctx, minted)
            minted
        }

        // Step 1: fixed fabricated pair (game .53.1 / bot .53.2).
        // Step 2: the bot's T7 beats start here and keep running.
        BotMode.ensureStarted()
        BotMode.startBeats(identity.userId)

        // The bot declares the fabricated peer identity at the gate.
        val gate = KgsGate(identity, BotMode.declaredProfile())

        gate.login()

        val quality = qualityTemplate
        if (quality != null && quality.isNotEmpty()) {
            val q = gate.setRelayQuality(quality, System.currentTimeMillis() / 1000L)
            if (q["result"] != "NOERR") {
                throw KgsGate.GateException("relay quality rejected: ${shallow(q)}")
            }
        }
        // No qualityTemplate: proceed without it -- runs #27-#29 still reached
        // P2P without the quality step -- but the record warns sycom can come
        // back SC despite the right nat_type (the real client sends it).

        val r = gate.createRoom()
        if (r["result"] == "_NO_ENDPOINT") {
            throw KgsGate.GateException(
                "Bot endpoint not set -- bot mode must declare the fabricated " +
                    "peer identity before creating a room."
            )
        }
        if (r["result"] == "NOERR") {
            val num = gate.roomNumberOf(r)
            if (num.isBlank()) {
                throw KgsGate.GateException("Room opened but Konami returned no room number.")
            }
            // Step 3: the state-driven driver takes over in the background --
            // it watches the room and fires the bot's half of the gate ladder
            // as the user's game joins (creator = HOME: entry side -> ready ->
            // go -> session -> TURN -> antenna), then arms the peer slot.
            val rid = ((r["room_id"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L
            if (rid != 0L) BotMatchDriver.start(gate, rid, num)
            return num
        }
        throw KgsGate.GateException(
            "room create rejected: ${shallow(r)}" +
                (if (quality == null) "  [note: no relay-quality sent -- sycom may come back SC]" else "")
        )
    }

    /** Shallow error surface only -- no request/response bodies land in the UI. */
    private fun shallow(o: Map<*, *>): String {
        val parts = ArrayList<String>()
        for (k in listOf("result", "msg", "error_code", "error", "errcode", "show_info", "_status", "_err")) {
            val v = o[k] ?: continue
            parts.add("$k=$v")
        }
        return if (parts.isEmpty()) "gate rejected the request" else parts.joinToString("  ")
    }
}
