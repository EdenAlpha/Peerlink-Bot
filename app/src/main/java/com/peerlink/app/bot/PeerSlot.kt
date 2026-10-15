package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import kotlin.concurrent.thread

/**
 * Bot mode step 4 -- the peer slot, now LIVE.
 *
 * In peer mode "the peer" is the paired remote phone reached over the tunnel.
 * In bot mode the same slot is filled locally: the tunnel hands every packet
 * the game addressed to the fabricated peer identity (.53.2) to
 * [handleGamePacketToPeer]; this object answers with the byte-exact
 * punch/session/capability forms (PeerBotEngine, 98/99 replay) and injects
 * its replies back toward the game through the TunnelEngine's injector.
 *
 * Timing follows the 2026-10-13 two-phone capture: the game's first knock is
 * learned (address + port from its own packet), answered instantly; the bot
 * then originates its own punch (retried ~every 600 ms) and, once the knock
 * has crossed, sends the session + capability burst -- exactly the order the
 * real creator side used.
 *
 * Non-STUN frames (the game channel's 8-byte-header frames) are consumed and
 * logged by class; a cls-0xC0 frame is the live key box the real game hands
 * the receiving side -- logged explicitly so the moment is visible in the
 * app log (full bytes live in the tunnel capture as usual).
 */
object PeerSlot {

    @Volatile
    private var engine: PeerBotEngine? = null

    @Volatile
    private var injector: ((ByteArray, String, Int, Int) -> Unit)? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    var armed = false
        private set

    @Volatile
    var gameUid = 0L
        private set

    /** The address the gate told us the game announced (for logs). */
    @Volatile
    var gameAddress = ""
        private set

    /** The game's real local socket address, learned from its first packet. */
    @Volatile
    private var gameIp = ""
    @Volatile
    private var gamePort = 0

    /** The fabricated port the game sent TO (our declared port). */
    @Volatile
    private var gameTargetPort = 0

    @Volatile
    private var sawInboundStun = false

    private val loggedClasses = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** TunnelEngine installs (and re-installs) the injector lambda. */
    fun installInjector(fn: (ByteArray, String, Int, Int) -> Unit) {
        injector = fn
    }

    /** Arm the slot: from here the bot owns .53.2 traffic. */
    fun arm(uid: Long, address: String) {
        engine = PeerBotEngine(uid.toString())
        gameUid = uid
        gameAddress = address
        gameIp = ""
        gamePort = 0
        gameTargetPort = 0
        sawInboundStun = false
        armed = true
        AppState.appendLog("[BOT-SLOT  ] armed: game uid=$uid announced=$address")
        worker = thread(name = "peer-slot") { originateLoop() }
    }

    fun disarm() {
        armed = false
        engine = null
        worker = null
        AppState.appendLog("[BOT-SLOT  ] disarmed")
    }

    /**
     * One packet the game addressed to the peer's fabricated identity.
     * Returns true when this slot consumed it (originals are never tunneled
     * in bot mode -- there is no far phone). Called from TunnelEngine.
     */
    fun handleGamePacketToPeer(destIp: String, buffer: ByteArray, length: Int): Boolean {
        if (!armed) return false
        if (destIp != AppState.peerFabricatedIp) return false

        val p = parseUdp(buffer, length) ?: return false
        if (!sawInboundStun) {
            gameIp = p.srcIp
            gamePort = p.srcPort
            gameTargetPort = p.dstPort
        }

        val eng = engine ?: return false
        val (reply, note) = eng.handle(p.payload)
        if (note == "not-stun") {
            noteGameFrame(p.payload)
            if (!sawInboundStun && p.payload.size >= 8) {
                // pure game-channel frame (no STUN seen yet) -- keep the
                // learned address but do not count it as the knock
            }
            return true
        }

        if (!sawInboundStun) {
            sawInboundStun = true
            AppState.appendLog("[BOT-SLOT  ] game knocked ($note, " +
                "${p.srcIp}:${p.srcPort}) -- answering")
        }
        if (reply != null) {
            injector?.invoke(reply, p.srcIp, p.srcPort, p.dstPort)
        }
        return true
    }

    /**
     * Originate side: wait for the game's first knock (learns its address),
     * punch with retries, then the session + capability burst. Mirrors the
     * real creator side of the 2026-10-13 capture.
     */
    private fun originateLoop() {
        try {
            val deadline = System.currentTimeMillis() + 60_000L
            while (armed && !sawInboundStun && System.currentTimeMillis() < deadline) {
                Thread.sleep(200)
            }
            if (!armed || !sawInboundStun) {
                AppState.appendLog("[BOT-SLOT  ] no knock from the game yet -- " +
                    "slot stays armed and reactive")
                return
            }
            val eng = engine ?: return
            val toIp = gameIp
            val toPort = gamePort
            val fromPort = gameTargetPort
            val tid = eng.freshTid()
            // Punch with retries (~600 ms like the capture's cadence).
            for (i in 0 until 5) {
                if (!armed) return
                val m = eng.punchReq(tid, gameUid.toString())
                injector?.invoke(m, toIp, toPort, fromPort)
                Thread.sleep(600)
            }
            // Session + capability burst, paced.
            for (m in eng.originateBytes(nSession = 3)) {
                if (!armed) return
                injector?.invoke(m, toIp, toPort, fromPort)
                Thread.sleep(120)
            }
            AppState.appendLog("[BOT-SLOT  ] originate burst sent " +
                "(punch + sessions + capability)")
        } catch (t: Throwable) {
            AppState.appendLog("[BOT-SLOT  ] originate stopped: ${t.javaClass.simpleName}")
        }
    }

    /** Log every distinct non-STUN class once (c0 = the key box). */
    private fun noteGameFrame(payload: ByteArray) {
        if (payload.size < 8) return
        val cls = payload[4].toInt() and 0xFF
        if (!loggedClasses.add(cls)) return
        val head = payload.copyOfRange(0, minOf(24, payload.size))
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        if (cls == 0xC0) {
            AppState.appendLog("[BOT-SLOT  ] KEY BOX (c0) received len=${payload.size} " +
                "head=$head -- the live key exchange frame")
        } else {
            AppState.appendLog("[BOT-SLOT  ] game frame class=0x%02x len=%d head=%s"
                .format(cls, payload.size, head))
        }
    }

    private class Parsed(val srcIp: String, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    /** Minimal IPv4+UDP parse of a raw TUN packet (self-contained on purpose). */
    private fun parseUdp(buf: ByteArray, length: Int): Parsed? {
        if (length < 28) return null
        if ((buf[0].toInt() shr 4) != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if ((buf[9].toInt() and 0xFF) != 17) return null
        if (length < ihl + 8) return null
        val srcIp = "%d.%d.%d.%d".format(
            buf[12].toInt() and 0xFF, buf[13].toInt() and 0xFF,
            buf[14].toInt() and 0xFF, buf[15].toInt() and 0xFF)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payload = buf.copyOfRange(ihl + 8, length)
        return Parsed(srcIp, srcPort, dstPort, payload)
    }
}
