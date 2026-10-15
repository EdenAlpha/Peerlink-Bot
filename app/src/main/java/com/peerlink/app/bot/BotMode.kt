package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.network.LanPathResolver

/**
 * PeerLink bot mode -- the two-phone P2P flow with the far phone replaced by
 * the local bot (architecture locked 2026-10-15):
 *
 *  - The fabricated pair is FIXED in this mode (there is no second LAN to
 *    compare): the game on this phone gets 197.210.53.1 from the existing
 *    StunFabricator (untouched), and the bot's punched address is
 *    197.210.53.2. At the gate the bot declares a TRANSLATED endpoint --
 *    host = this phone's real LAN IP, reflexive = .53.2:1162 (the address
 *    the game punches from the session reply). The declared host must be
 *    an address the client actually owns: run #21's host == reflexive ==
 *    real egress was accepted, and the real games in the flipped peer-mode
 *    rooms declare real hosts with fabricated reflexives. The 2026-10-15
 *    device run declared host .53.2 -- a public address that exists
 *    nowhere on the wire -- and the gate answered ERR_EMPTY x7 (empty
 *    endpoint record).
 *  - The bot never runs the StunFabricator -- it just declares the peer
 *    identity. Traffic the game sends to .53.2 is the bot's to answer.
 *  - The VPN for the game runs the KOTLIN datapath with no peer device: no
 *    LAN-path lock, no native peer backend, no bidirectional probe (all
 *    peer-mode-only requirements; their absence was the 2026-10-15
 *    "Peer LAN IP is not set" failure). The mode marker is persisted so a
 *    process restart (START_STICKY) comes back as a bot-mode session.
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

    private const val PREFS = "bot_mode_prefs"

    /** True while a bot-mode room session owns the phone. */
    @Volatile
    var active = false
        private set

    @Volatile
    private var keepalive: T7Keepalive? = null

    /**
     * The host half the bot declares at the gate: this phone's real LAN IPv4,
     * captured in [ensureStarted] before any gate traffic. The reflexive half
     * stays the fabricated peer identity (the address the game punches).
     * Fallback is loopback -- also a proven-accepted host shape -- never the
     * fabricated IP (a host the client does not own is the rejected shape).
     */
    @Volatile
    var hostIp: String = "127.0.0.1"
        private set

    /**
     * Put the fixed fabricated pair in place. Called on every room create --
     * also re-asserts the values if anything overwrote them since (say, a
     * stale peer-mode calculation at VPN start).
     */
    fun ensurePair() {
        AppState.myFabricatedIp = GAME_FAB_IP
        AppState.peerFabricatedIp = BOT_FAB_IP
    }

    /** Begin a bot-mode session: fixed pair + host capture + marker + beats. */
    fun ensureStarted(ctx: Context, uid: Long) {
        ensurePair()
        hostIp = resolveHostIp(ctx)
        active = true
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("active", true)
            .putLong("uid", uid)
            .apply()
        startBeats(uid)
    }

    /**
     * Restore the mode after a process/service restart (START_STICKY): the
     * in-memory [active] flag dies with the process, the persisted one does
     * not -- without this, a restarted VPN service would take the peer path
     * and fail with "Peer LAN IP is not set".
     */
    fun restoreIfActive(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!p.getBoolean("active", false)) return
        ensurePair()
        active = true
        val uid = p.getLong("uid", 0L)
        if (uid != 0L) startBeats(uid)
    }

    /** Start (or keep) the bot's T7 beats for [uid]. Idempotent. */
    @Synchronized
    fun startBeats(uid: Long) {
        if (keepalive != null) return
        val k = T7Keepalive(listOf(uid.toString()), BOT_LOCAL_PORT)
        k.start()
        keepalive = k
    }

    /** The profile the bot declares at the gate: a TRANSLATED endpoint --
     * host = this phone's real LAN IP, reflexive = the fabricated peer
     * identity (.53.2:1162, the address the game punches from the session
     * reply). Same shape the real games declare in the flipped peer-mode
     * rooms (real host : game port / fabricated reflexive : game port). */
    fun declaredProfile(): KgsGate.NatProfile =
        KgsGate.NatProfile(
            hostAddress = hostIp,
            hostPort = BOT_PORT,
            reflexiveAddress = BOT_FAB_IP,
            reflexivePort = BOT_PORT,
            natType = KgsGate.NatProfile.NAT_P2P_A,
        )

    /**
     * Real LAN IPv4 for the declared host half: the active network's address
     * first (fresh, pre-VPN), the stored LAN IP second, loopback last. Never
     * the fabricated IP -- host == reflexive is the rejected shape.
     */
    private fun resolveHostIp(ctx: Context): String {
        val underlay = runCatching {
            LanPathResolver.resolveDefaultUnderlay(ctx)?.localIp
        }.getOrNull().orEmpty()
        if (isRealHost(underlay)) return underlay
        val stored = AppState.localIp.get().orEmpty()
        if (isRealHost(stored)) return stored
        return "127.0.0.1"
    }

    private fun isRealHost(ip: String): Boolean {
        if (ip.isBlank() || ip == BOT_FAB_IP) return false
        val parts = ip.split(".")
        return parts.size == 4 && parts.all { p -> p.toIntOrNull() in 0..255 }
    }

    fun stop() {
        keepalive?.stop()
        keepalive = null
    }
}
