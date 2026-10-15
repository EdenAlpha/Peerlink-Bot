package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import kotlin.concurrent.thread

/**
 * Bot mode step 4 -- the peer slot, LIVE.
 *
 * In peer mode "the peer" is the paired remote phone reached over the tunnel.
 * In bot mode the same slot is filled locally: the tunnel hands every packet
 * the game addressed to the fabricated peer identity (.53.2) to
 * [handleGamePacketToPeer]; this object answers with the byte-exact
 * punch/session/capability forms (PeerBotEngine, 98/99 replay) and injects
 * its replies back toward the game through the TunnelEngine's injector.
 *
 * The game's address comes AUTOMATICALLY from the gate session's
 * ntl_address_list (the same source the real client uses): its host half is
 * the game's real local socket -- the delivery target -- and its reflexive
 * half is what the game announced. No waiting for a first packet, so the bot
 * punches FIRST exactly like the real creator side of the 2026-10-13 match,
 * retrying (~600 ms) until the exchange crosses; every knock is answered
 * instantly; then the session + capability burst follows. The first inbound
 * packet still refreshes the address (ground truth beats declaration).
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

    /** The game's real local socket (delivery target), refreshed by its packets. */
    @Volatile
    private var gameIp = ""
    @Volatile
    private var gamePort = 0

    /** The fabricated port the game sent TO (our declared port). */
    @Volatile
    private var gameTargetPort = BotMode.BOT_PORT

    @Volatile
    private var sawInboundStun = false

    private val loggedClasses = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** TunnelEngine installs (and re-installs) the injector lambda. */
    fun installInjector(fn: (ByteArray, String, Int, Int) -> Unit) {
        injector = fn
    }

    /**
     * Arm the slot. [announced] = the game's announced address (logs);
     * [localIp]/[localPort] = its host half from the session's address list
     * (the delivery target; "" if not declared yet -- the first inbound
     * packet then supplies it).
     */
    fun arm(uid: Long, announced: String, localIp: String, localPort: Int) {
        engine = PeerBotEngine(uid.toString())
        gameUid = uid
        gameAddress = announced
        gameIp = localIp
        gamePort = localPort
        gameTargetPort = BotMode.BOT_PORT
        sawInboundStun = false
        armed = true
        AppState.appendLog("[BOT-SLOT  ] armed: game uid=$uid announced=$announced " +
            (if (localIp.isNotBlank() && localPort > 0) "local=$localIp:$localPort"
             else "local address pending"))
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

        // The game's own packets are the ground truth for its address.
        gameIp = p.srcIp
        gamePort = p.srcPort
        gameTargetPort = p.dstPort

        val eng = engine ?: return false
        val (reply, note) = eng.handle(p.payload)
        if (note == "not-stun") {
            noteGameFrame(p.payload)
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
     * Originate side: punch FIRST (the real creator did), retrying ~600 ms
     * apart until the exchange crosses; then the session + capability burst.
     */
    private fun originateLoop() {
        try {
            val eng = engine ?: return
            val tid = eng.freshTid()
            val deadline = System.currentTimeMillis() + 30_000L
            var punches = 0
            while (armed && !sawInboundStun && punches < 15 &&
                System.currentTimeMillis() < deadline
            ) {
                if (gameIp.isBlank() || gamePort <= 0) {
                    Thread.sleep(200)   // address not declared yet -- wait for it
                    continue
                }
                val m = PeerBotEngine.punchReq(tid, gameUid.toString())
                injector?.invoke(m, gameIp, gamePort, gameTargetPort)
                punches++
                Thread.sleep(600)
            }
            if (!armed) return
            if (!sawInboundStun) {
                AppState.appendLog("[BOT-SLOT  ] no answer to $punches punches yet -- " +
                    "slot stays armed and reactive")
                return
            }
            // Session + capability burst, paced (the capture's order).
            for (m in eng.originateBytes(nSession = 3)) {
                if (!armed) return
                injector?.invoke(m, gameIp, gamePort, gameTargetPort)
                Thread.sleep(120)
            }
            AppState.appendLog("[BOT-SLOT  ] originate burst sent " +
                "(punch crossing seen; sessions + capability out)")
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
