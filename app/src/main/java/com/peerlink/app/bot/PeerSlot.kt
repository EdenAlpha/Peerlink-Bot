package com.peerlink.app.bot

import com.peerlink.app.core.AppState

/**
 * Bot mode step 4 seam -- the peer slot the game's `.53.2` traffic lands in.
 *
 * In peer mode, "the peer" is the paired remote phone, reached over the
 * tunnel. In bot mode the SAME slot is filled locally: the driver arms this
 * object once the gate-side ladder is complete, and the tunnel (step-4
 * wiring) hands every packet the game addressed to .53.2 to [deliver].
 *
 * WIRING STATUS (2026-10-15): armed state + the deliver() seam exist and the
 * driver arms it; the TunnelEngine sink/source hookup lands in the step-4
 * commit. deliver() returns the packets to inject back to the game as the
 * peer -- empty until that hookup exists (honest seam, not a fake answer).
 */
object PeerSlot {

    @Volatile
    var armed = false
        private set

    @Volatile
    var gameUid = 0L
        private set

    @Volatile
    var gameAddress = ""
        private set

    /** Arm the slot: from here the bot owns .53.2 traffic. */
    fun arm(uid: Long, address: String) {
        gameUid = uid
        gameAddress = address
        armed = true
        AppState.appendLog("[BOT-SLOT  ] armed: game uid=$uid peer=$address")
    }

    fun disarm() {
        armed = false
        AppState.appendLog("[BOT-SLOT  ] disarmed")
    }

    /**
     * One packet the game addressed to the peer's fabricated identity.
     * Returns the packets to inject back to the game as the peer (none until
     * the tunnel hookup lands in step 4).
     */
    fun deliver(payload: ByteArray): List<ByteArray> = emptyList()
}
