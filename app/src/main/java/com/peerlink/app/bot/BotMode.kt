package com.peerlink.app.bot

import com.peerlink.app.core.AppState

/**
 * PeerLink bot mode -- the two-phone P2P flow with the far phone replaced by
 * the local bot (architecture locked 2026-10-15):
 *
 *  - The fabricated pair is FIXED in this mode (there is no second LAN to
 *    compare): the game on this phone gets 197.210.53.1 from the existing
 *    StunFabricator (untouched), and the bot claims 197.210.53.2 at the gate.
 *  - The bot never runs the StunFabricator -- it just declares the peer
 *    identity. Traffic the game sends to .53.2 is the bot's to answer: the
 *    peer slot, filled locally instead of by another phone.
 *  - T7 beats run for the bot uid from the app process (outside the per-app
 *    VPN), so Konami STUN sees the bot alive.
 *
 * Peer mode is untouched: it keeps computing the pair from both phones' LANs
 * and nothing here runs unless a room is created in bot mode.
 */
object BotMode {

    /** Fixed fabricated identities for bot mode (by convention). */
    const val GAME_FAB_IP = "197.210.53.1"
    const val BOT_FAB_IP = "197.210.53.2"

    /**
     * The port the bot declares as its own. The peer slot matches by address,
     * so any valid port works; 1162 mirrors the pattern seen in the real
     * two-phone match capture.
     */
    const val BOT_PORT = 1162

    /**
     * Local bind port for the bot's T7 beating socket (bundle convention).
     * Independent of the declared port: the declared value is fabricated, so
     * the beating socket must not collide with game sockets on this phone.
     */
    const val BOT_LOCAL_PORT = 44001

    @Volatile
    private var keepalive: T7Keepalive? = null

    /**
     * Put the fixed fabricated pair in place. Called on every room create --
     * also re-asserts the values if anything overwrote them since (say, a
     * stale peer-mode calculation at VPN start).
     */
    fun ensureStarted() {
        AppState.myFabricatedIp = GAME_FAB_IP
        AppState.peerFabricatedIp = BOT_FAB_IP
    }

    /** Start (or keep) the bot's T7 beats for [uid]. Idempotent. */
    fun startBeats(uid: Long) {
        if (keepalive != null) return
        val k = T7Keepalive(listOf(uid.toString()), BOT_LOCAL_PORT)
        k.start()
        keepalive = k
    }

    /** The profile the bot declares at the gate: the fabricated peer identity. */
    fun declaredProfile(): KgsGate.NatProfile =
        KgsGate.NatProfile.fabricated(BOT_FAB_IP, BOT_PORT)

    fun stop() {
        keepalive?.stop()
        keepalive = null
    }
}
