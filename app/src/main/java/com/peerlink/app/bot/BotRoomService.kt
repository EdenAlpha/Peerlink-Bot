package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.play.BotRoomBackend

/**
 * The bot's room service: log the bot into Konami's gate and open a room the
 * user's own eFootball (same phone, behind the PeerLink VPN) can join.
 *
 * Fails honestly when the device has no bot account stored yet -- it never
 * invents a room number.
 */
object BotRoomService : BotRoomBackend {

    override suspend fun createRoom(ctx: Context): String {
        val identity = BotIdentity.load(ctx)
            ?: throw KgsGate.GateException(
                "No bot account on this phone yet. Add the bot's user_id and " +
                    "auth_code under General Settings, then create the room again."
            )
        val gate = KgsGate(identity)
        gate.login()
        // CMD_CREATEJOIN_ROOM directly; relay-quality only when the phone has
        // real region data (never a synthesized list).
        val r = gate.createRoom()
        val result = r["result"]
        if (result == "NOERR") {
            val num = gate.roomNumberOf(r)
            if (num.isBlank()) {
                throw KgsGate.GateException("Room opened but Konami returned no room number.")
            }
            return num
        }
        val msg = shallowDescribe(r)
        throw KgsGate.GateException(msg)
    }

    /** Shallow error surface only -- no request/response bodies land in the UI. */
    private fun shallowDescribe(o: Map<*, *>): String {
        val parts = ArrayList<String>()
        for (k in listOf("result", "msg", "error_code", "error", "errcode", "show_info", "_status", "_err")) {
            val v = o[k] ?: continue
            parts.add("$k=$v")
        }
        return if (parts.isEmpty()) "gate rejected the request" else parts.joinToString("  ")
    }
}
