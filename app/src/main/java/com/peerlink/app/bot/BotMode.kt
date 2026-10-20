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
     * This phone's global IPv6, when it has one.
     *
     * The engine publishes a local address per address family, and the
     * transport's add-peer keeps a peer's id only when the IPv4 add and the
     * IPv6 add both succeed.  An engine with no IPv6 address has no IPv6
     * channel, throws the peer's id away and stops at the punch -- so the
     * bot has to declare the real IPv6 half, not an inert one
     * (see docs/ENGINE_DRIVEN_P2P.md).
     */
    @Volatile
    var hostIp6: String = ""
        private set

    /**
     * The IPv6 mapping the game's NAT service measured for our own socket
     * (T7's MAPPED-ADDRESS over IPv6).  Null when the device has no usable
     * IPv6, in which case the engine cannot have an IPv6 channel either.
     */
    @Volatile
    var mapped6: java.net.InetSocketAddress? = null
        private set

    /**
     * The IPv6 half of what the bot declares at the gate: this phone's own
     * global IPv6 as the host half, and the mapping the NAT service
     * measured as the reflexive half.  The same measure-then-advertise rule
     * the IPv4 half follows.
     */
    fun declaredProfile6(): KgsGate.NatProfile {
        val m = mapped6
        return KgsGate.NatProfile(
            hostAddress = hostIp6,
            hostPort = m?.port ?: BOT_PORT,
            reflexiveAddress = m?.address?.hostAddress ?: "",
            reflexivePort = m?.port ?: BOT_PORT,
            natType = KgsGate.NatProfile.NAT_P2P_A,
        )
    }

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
        hostIp6 = resolveHostIp6(ctx)
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
        // The IPv6 mapping arrives a moment later, from the same beating
        // socket's first IPv6 exchange; the declarations pick it up from
        // here.
        Thread({
            var waited = 0
            while (waited < 8_000) {
                val m6 = k.lastMapped6
                if (m6 != null) {
                    mapped6 = m6
                    AppState.appendLog("[BOT-MODE  ] IPv6 mapping " +
                        "${m6.address?.hostAddress}:${m6.port}")
                    return@Thread
                }
                Thread.sleep(250)
                waited += 250
            }
            AppState.appendLog("[BOT-MODE  ] no IPv6 mapping measured; " +
                "the engine will have no IPv6 channel")
        }, "bot-ipv6-mapping").apply { isDaemon = true }.start()
    }
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

    /**
     * This phone's global IPv6, if it has one.  Link-local and site-local
     * addresses are skipped: the engine needs a routable one, and the NAT
     * service can only report a mapping for an address it can see.
     */
    private fun resolveHostIp6(ctx: Context): String = runCatching {
        val ifaces = java.net.NetworkInterface.getNetworkInterfaces()
        var best = ""
        while (ifaces != null && ifaces.hasMoreElements()) {
            val n = ifaces.nextElement()
            if (n.isLoopback || !n.isUp) continue
            for (a in n.inetAddresses) {
                if (a !is java.net.Inet6Address) continue
                if (a.isLinkLocalAddress || a.isLoopbackAddress || a.isSiteLocalAddress) continue
                val h = a.hostAddress?.substringBefore('%') ?: continue
                if (h.isNotEmpty() && best.isEmpty()) best = h
            }
        }
        best
    }.getOrNull().orEmpty()

    fun stop() {
        keepalive?.stop()
        keepalive = null
    }
}
