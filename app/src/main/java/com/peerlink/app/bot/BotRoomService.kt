package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.play.BotRoomBackend
import java.net.InetSocketAddress

/**
 * The bot's room service -- the per-session gate flow in the proven order
 * (bundle: RUN.txt + room1/room2):
 *
 *   T7 keepalive (bind-time reflexive measurement) -> CMD_LOGIN (bot account)
 *   -> CMD_SET_GAMERELAY_QUALITY [when real data exists]
 *   -> CMD_CREATEJOIN_ROOM -> room number for the user's own eFootball.
 *
 * Fails honestly when a step has no real data (no bot account, no measured
 * reflexive was required-but-missing, no quality template). Never invents
 * values; never invents a room number.
 */
object BotRoomService : BotRoomBackend {

    /** Called at room-create time to fetch the T7-measured reflexive, if any. */
    @Volatile
    var measuredEndpointProvider: (() -> InetSocketAddress?)? = null

    /** Relay-quality entries captured from the real client, if the phone has them. */
    @Volatile
    var qualityTemplate: List<Map<String, Any?>>? = null

    override suspend fun createRoom(ctx: Context): String {
        val identity = BotIdentity.load(ctx)
            ?: throw KgsGate.GateException(
                "No bot account on this phone yet. Add the bot's user_id and " +
                    "auth_code under General Settings, then create the room again."
            )

        // Profile: advertise what STUN actually measured from the beating
        // socket. Without a measurement we say so instead of inventing one.
        val measured = measuredEndpointProvider?.invoke()
        val gate = if (measured != null) {
            KgsGate(identity, KgsGate.NatProfile.measured(localAddress(ctx), localPort, measured))
        } else {
            KgsGate(identity)
        }

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
        if (r["result"] == "NOERR") {
            val num = gate.roomNumberOf(r)
            if (num.isBlank()) {
                throw KgsGate.GateException("Room opened but Konami returned no room number.")
            }
            return num
        }
        throw KgsGate.GateException(
            "room create rejected: ${shallow(r)}" +
                (if (quality == null) "  [note: no relay-quality sent -- sycom may come back SC]" else "")
        )
    }

    private const val localPort = 44001  // bundle convention: bot beats from its own fixed port

    private fun localAddress(ctx: Context): String {
        return try {
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            for (ni in nis) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.inetAddresses) {
                    if (!ia.isLoopbackAddress && ia is java.net.Inet4Address) return ia.hostAddress ?: ""
                }
            }
            ""
        } catch (_: Throwable) {
            ""
        }
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
