package com.peerlink.app.bot

import android.content.Context
import android.content.Intent
import com.peerlink.app.core.AppState
import com.peerlink.app.play.BotRoomBackend
import com.peerlink.app.service.PeerBotService
import com.peerlink.app.service.PeerLinkVpnService
import com.peerlink.app.ui.MainActivity

/**
 * The bot's room service -- the per-session gate flow in the proven order
 * (bundle: RUN.txt + room1/room2), now in BOT MODE addressing (locked
 * 2026-10-15):
 *
 *   fixed fabricated pair (game .53.1 / bot .53.2) + bot T7 beats
 *   -> CMD_LOGIN (bot account)
 *   -> quality ladder: CMD_GET_GAMERELAY_QUALITYCHECK_LIST -> measure the
 *      UDP echo regions -> CMD_SET_GAMERELAY_QUALITY (BotRelayQuality; the
 *      capture blocks 35/38-40 form -- the ready is refused ERR_EMPTY
 *      without it)
 *   -> CMD_CREATEJOIN_ROOM -> room number for the user's own eFootball.
 *
 * The declared endpoint is translated (real LAN host / fabricated .53.2
 * reflexive): no measurement of the bot's own NAT, and no empty-address
 * path (KgsGate.createRoom refuses a blank endpoint outright). Fails
 * honestly when a step has no real data (no bot account). Never invents a
 * room number.
 */
object BotRoomService : BotRoomBackend {

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

        // The bot declares the translated endpoint at the gate (real LAN
        // host, fabricated .53.2 reflexive -- the address the game punches).
        var gate = KgsGate(identity, BotMode.declaredProfile())
        gate.login()

        // SQUAD GUARD (T5 proof, 2026-10-15): a minted account without a
        // squad row cannot ready (ERR_EMPTY/PNPS-). Older bot accounts that
        // were minted before the roster bootstrap existed have no squad and
        // no stored roster -- they cannot be repaired, so replace them with
        // a freshly minted, squad-bootstrapped account, automatically.
        if (!BotSquad.ensure(gate, identity.entryInfoJson)) {
            AppState.appendLog(
                "[BOT-SQUAD ] bot account has no usable squad -- minting a replacement")
            BotIdentity.clear(ctx)
            BotMode.stop()
            val fresh = BotMinter.mint()
                ?: throw KgsGate.GateException(
                    "The old bot account has no squad and its replacement was " +
                        "refused -- try once more; the log shows the mint lines."
                )
            BotIdentity.save(ctx, fresh)
            BotMode.ensureStarted(ctx, fresh.userId)
            gate = KgsGate(fresh, BotMode.declaredProfile())
            gate.login()
        }
        val identityInUse = BotIdentity.load(ctx) ?: identity

        // Step 1: fixed fabricated pair (game .53.1 / bot .53.2).
        // Step 2: the bot's T7 beats start here and keep running; the mode
        // marker is persisted so a restarted VPN service comes back as a
        // bot-mode session instead of failing the peer-mode requirements.
        BotMode.ensureStarted(ctx, identityInUse.userId)

        // The capture's login-time quality report (blocks 35 + 38-40): fetch
        // the region list, measure the echo endpoints, SET. The room-time
        // report (block 63) re-sends the SET inside the room before the
        // ready -- run #47 order: entry_side -> relay_qual -> CME -> ready.
        // The device runs of 2026-10-15 proved the ready is refused
        // (ERR_EMPTY) when no measurement was ever reported, regardless of
        // the declared endpoint shape.
        val quality = BotRelayQuality.loginReport(gate)
        if (quality.skipped || quality.result != "NOERR") {
            AppState.appendLog(
                "[BOT-QUALITY] login report did not land (${quality.result ?: "no endpoints"}) " +
                    "-- the room-time report will retry"
            )
        }

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
            // Keep the bot session ALIVE while the user plays in eFootball:
            // a foreground service (persistent room notification + live
            // status) and the per-app VPN for the game's traffic. Without
            // this the app freezes the moment the user switches away and the
            // bot never answers the game's To Match (2026-10-15 fix).
            PeerBotService.start(ctx, num)
            startGameVpn(ctx)

            // Step 3: the state-driven driver takes over -- it watches the
            // room and fires the bot's half of the gate ladder as the user's
            // game joins (creator = HOME: entry side -> ready -> go ->
            // session -> TURN -> antenna), then arms the peer slot.
            val rid = ((r["room_id"] as? Map<*, *>)?.get("id") as? Number)?.toLong() ?: 0L
            if (rid != 0L) BotMatchDriver.start(gate, rid, num)
            return num
        }
        throw KgsGate.GateException(
            "room create rejected: ${shallow(r)}"
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

    /**
     * Bring the per-app VPN up for the game now, so its traffic is handled
     * from its first packet. Consent is a one-time system dialog; if it was
     * never granted, say so honestly instead of silently doing nothing.
     */
    private fun startGameVpn(ctx: Context) {
        runCatching {
            if (android.net.VpnService.prepare(ctx) == null) {
                val vpnIntent = Intent(ctx, PeerLinkVpnService::class.java)
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    ctx.startForegroundService(vpnIntent)
                } else {
                    ctx.startService(vpnIntent)
                }
                AppState.appendLog("[BOT-SVC   ] game VPN service started")
                PeerBotService.status("game VPN starting -- join from eFootball now")
            } else {
                // Fresh installs (and every reinstall) need ONE system dialog
                // before any VPN can run. Ask for it through the UI instead of
                // silently doing nothing (the 2026-10-15 "no VPN" report).
                AppState.appendLog("[BOT-SVC   ] VPN permission needed -- raising the one-time consent dialog")
                PeerBotService.status("VPN permission needed -- approve the Android dialog")
                val ask = Intent(ctx, MainActivity::class.java).apply {
                    action = PeerBotService.ACTION_VPN_CONSENT
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                ctx.startActivity(ask)
            }
        }.onFailure { AppState.appendLog("[BOT-SVC   ] VPN start failed: ${it.message}") }
    }
}
