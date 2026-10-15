package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Bot mode -- the local peer, wired into the SAME datapath peer mode uses.
 *
 * The user's architecture (corrected 2026-10-15): bot mode must behave like
 * peer mode in every way -- the NATIVE backend owns the TUN and passes
 * everything through (login, Konami, DNS all work there), diverting only the
 * game's fabricated-IP UDP frames. The single difference: those frames travel
 * to a peer that is LOCAL instead of a remote phone.
 *
 * That is exactly what this object is: the native backend is configured with
 * `peerLanIp = 127.0.0.1, peerPort = 1162`, so its tunnel-out frames
 * (`[56B TunnelDiagMeta][inner IP packet]` -- or header-less, both accepted)
 * land on this slot's loopback socket; the slot answers as the fabricated
 * peer, and the native side injects the replies straight into the game.
 *
 * Wire details taken from `peerlink_backend.cpp`:
 *  - tunnel-out frames: sendto(peer_addr), diag header is OPTIONAL to parse
 *    (`parse_tunnel_diag_meta` failure -> whole datagram is the payload);
 *  - 1-byte control codes: 0xFC -> reply 0xFD (peer probe), 0xFF -> keepalive;
 *  - replies are written to the TUN as-is, so the slot builds a complete
 *    inner IPv4/UDP packet (src = the fabricated peer identity).
 */
object PeerSlot {

    /** The native backend's peer for bot mode: this very slot, on loopback. */
    const val LOOPBACK_PEER_IP = "127.0.0.1"

    /** NativeBackendConfig.localPort -- the backend's bound peer socket. */
    const val BACKEND_PORT = 17024

    private const val DIAG_MAGIC = 0x504C4447
    private const val DIAG_VERSION = 2
    private const val DIAG_HEADER_SIZE = 56

    @Volatile
    private var engine: PeerBotEngine? = null

    /** Legacy TunnelEngine path (kept for compilability; unused in bot mode). */
    @Volatile
    private var injector: ((ByteArray, String, Int, Int) -> Unit)? = null

    @Volatile
    private var armed = false
        private set

    @Volatile
    var gameUid = 0L
        private set

    /** The address the gate told us the game announced (for logs). */
    @Volatile
    var gameAddress = ""
        private set

    /** The game's real local socket, refreshed by its own frames. */
    @Volatile
    private var gameIp = ""
    @Volatile
    private var gamePort = 0

    /** The fabricated port the game sent TO (our declared port = .53.2:1162). */
    @Volatile
    private var gameTargetPort = BotMode.BOT_PORT

    @Volatile
    private var sawInboundStun = false

    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private var originator: Thread? = null
    private val loggedClasses = java.util.Collections.synchronizedSet(HashSet<Int>())

    /** TunnelEngine installs (and re-installs) the legacy injector lambda. */
    fun installInjector(fn: (ByteArray, String, Int, Int) -> Unit) {
        injector = fn
    }

    /**
     * Arm the slot. [announced] = the game's announced address (logs);
     * [localIp]/[localPort] = its host half from the session's address list.
     */
    fun arm(uid: Long, announced: String, localIp: String, localPort: Int) {
        // Idempotent re-arm: the driver arms this slot the moment go_match
        // fires (socket live, reactive), then re-targets it once the
        // session reply carries the game's address. Never churn the socket
        // or lose the punch-watch state on the update.
        if (armed && gameUid == uid && socket != null) {
            if (announced.isNotBlank()) gameAddress = announced
            if (localIp.isNotBlank()) gameIp = localIp
            if (localPort > 0) gamePort = localPort
            AppState.appendLog("[BOT-SLOT  ] target updated: " +
                "announced=$gameAddress local=$gameIp:$gamePort")
            return
        }
        engine = PeerBotEngine(uid.toString())
        gameUid = uid
        gameAddress = announced
        gameIp = localIp
        gamePort = localPort
        gameTargetPort = BotMode.BOT_PORT
        sawInboundStun = false
        armed = true

        val s = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LOOPBACK_PEER_IP, BotMode.BOT_PORT))
            }
        } catch (t: Throwable) {
            AppState.appendLog("[BOT-SLOT  ] loopback peer bind failed: ${t.message}")
            armed = false
            return
        }
        socket = s
        AppState.appendLog("[BOT-SLOT  ] armed: game uid=$uid announced=$announced " +
            (if (localIp.isNotBlank() && localPort > 0) "local=$localIp:$localPort"
             else "local address pending") +
            " -- peer slot on $LOOPBACK_PEER_IP:${BotMode.BOT_PORT}")
        worker = thread(name = "peer-slot-rx") { receiveLoop(s) }
        originator = thread(name = "peer-slot-tx") { originateLoop(s) }
    }

    fun disarm() {
        armed = false
        runCatching { socket?.close() }
        socket = null
        engine = null
        worker = null
        originator = null
        AppState.appendLog("[BOT-SLOT  ] disarmed")
    }

    // ------------------------------------------------ loopback transport
    /**
     * Frames the native backend pulled out of the game's TUN arrive here.
     * Replies go straight back through the same loopback socket; the native
     * side injects them into the game as the fabricated peer's packets.
     */
    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        while (armed && !s.isClosed) {
            s.soTimeout = 250
            val pkt = DatagramPacket(buf, buf.size)
            val n = try {
                s.receive(pkt)
                pkt.length
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Throwable) {
                if (!armed) break else continue
            }
            if (n <= 0) continue
            val from = pkt.socketAddress as? InetSocketAddress
            val data = buf.copyOf(n)

            if (n == 1) {
                when (data[0].toInt() and 0xFF) {
                    0xFC -> runCatching {
                        s.send(DatagramPacket(byteArrayOf(0xFDu.toByte()), 1, from))
                    } // peer probe -> acknowledge, exactly like a real peer
                    0xFF -> Unit // native keepalive -- nothing to answer
                }
                continue
            }

            val payload = stripDiagHeader(data) ?: continue
            val inner = parseInnerUdp(payload) ?: continue
            val fromGame = inner.dstIp == AppState.peerFabricatedIp
            if (!fromGame) {
                if (loggedClasses.add(-2)) {
                    AppState.appendLog("[BOT-SLOT  ] non-peer tunnel frame dropped " +
                        "(${inner.srcIp}:${inner.srcPort} -> ${inner.dstIp}:${inner.dstPort})")
                }
                continue
            }
            gameIp = inner.srcIp
            gamePort = inner.srcPort
            gameTargetPort = inner.dstPort

            val eng = engine ?: continue
            val (reply, note) = eng.handle(inner.udpPayload)
            if (note == "not-stun") {
                noteGameFrame(inner.udpPayload)
                continue
            }
            if (!sawInboundStun) {
                sawInboundStun = true
                AppState.appendLog("[BOT-SLOT  ] game knocked ($note, " +
                    "${inner.srcIp}:${inner.srcPort}) -- answering")
            }
            if (reply != null && from != null) {
                val out = buildInnerUdpPacket(
                    srcIp = AppState.peerFabricatedIp,
                    dstIp = inner.srcIp,
                    srcPort = inner.dstPort,
                    dstPort = inner.srcPort,
                    payload = reply,
                )
                runCatching { s.send(DatagramPacket(out, out.size, from)) }
            }
        }
    }

    /**
     * Punch-first originate burst (the real creator side's behaviour), sent
     * through the native backend to the game: the inner packet is addressed
     * game-ward while the DATAGRAM is addressed to the backend's socket.
     */
    private fun originateLoop(s: DatagramSocket) {
        try {
            val eng = engine ?: return
            val tid = eng.freshTid()
            val deadline = System.currentTimeMillis() + 30_000L
            var punches = 0
            while (armed && !sawInboundStun && punches < 15 &&
                System.currentTimeMillis() < deadline
            ) {
                if (gameIp.isBlank() || gamePort <= 0) {
                    Thread.sleep(200)
                    continue
                }
                sendToGame(s, PeerBotEngine.punchReq(tid, gameUid.toString()))
                punches++
                Thread.sleep(600)
            }
            if (!armed) return
            if (!sawInboundStun) {
                AppState.appendLog("[BOT-SLOT  ] no answer to $punches punches yet -- " +
                    "slot stays armed and reactive")
                return
            }
            for (m in eng.originateBytes(nSession = 3)) {
                if (!armed) return
                sendToGame(s, m)
                Thread.sleep(120)
            }
            AppState.appendLog("[BOT-SLOT  ] originate burst sent " +
                "(punch crossing seen; sessions + capability out)")
        } catch (t: Throwable) {
            AppState.appendLog("[BOT-SLOT  ] originate stopped: ${t.javaClass.simpleName}")
        }
    }

    private fun sendToGame(s: DatagramSocket, payload: ByteArray) {
        val backendIp = AppState.localIp.get().orEmpty()
        if (backendIp.isBlank() || gameIp.isBlank() || gamePort <= 0) return
        val out = buildInnerUdpPacket(
            srcIp = AppState.peerFabricatedIp,
            dstIp = gameIp,
            srcPort = gameTargetPort,
            dstPort = gamePort,
            payload = payload,
        )
        runCatching {
            s.send(DatagramPacket(out, out.size, InetSocketAddress(backendIp, BACKEND_PORT)))
        }
    }

    // ------------------------------------------------ wire helpers
    /** Strip the optional 56-byte native diag header; null when unusable. */
    private fun stripDiagHeader(data: ByteArray): ByteArray? {
        if (data.size < 1) return null
        val magic = (data[0].toInt() and 0xFF) or
            ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or
            ((data[3].toInt() and 0xFF) shl 24)
        if (data.size >= DIAG_HEADER_SIZE && magic == DIAG_MAGIC &&
            (data[4].toInt() and 0xFF) == DIAG_VERSION
        ) {
            return data.copyOfRange(DIAG_HEADER_SIZE, data.size)
        }
        return data
    }

    private class Inner(val srcIp: String, val srcPort: Int, val dstIp: String,
                        val dstPort: Int, val udpPayload: ByteArray)

    /** Minimal IPv4+UDP parse of an inner packet (the native uses IPv4 here). */
    private fun parseInnerUdp(buf: ByteArray): Inner? {
        if (buf.size < 28) return null
        if ((buf[0].toInt() shr 4) != 4) return null
        val ihl = (buf[0].toInt() and 0x0F) * 4
        if ((buf[9].toInt() and 0xFF) != 17) return null
        if (buf.size < ihl + 8) return null
        val srcIp = "%d.%d.%d.%d".format(
            buf[12].toInt() and 0xFF, buf[13].toInt() and 0xFF,
            buf[14].toInt() and 0xFF, buf[15].toInt() and 0xFF)
        val dstIp = "%d.%d.%d.%d".format(
            buf[16].toInt() and 0xFF, buf[17].toInt() and 0xFF,
            buf[18].toInt() and 0xFF, buf[19].toInt() and 0xFF)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        return Inner(srcIp, srcPort, dstIp, dstPort, buf.copyOfRange(ihl + 8, buf.size))
    }

    /** Complete inner IPv4/UDP packet (checksums included), as the TUN needs. */
    private fun buildInnerUdpPacket(
        srcIp: String, dstIp: String, srcPort: Int, dstPort: Int, payload: ByteArray,
    ): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val p = ByteArray(total)
        p[0] = 0x45
        p[8] = 0x40
        p[9] = 17
        writeU16(p, 2, total)
        System.arraycopy(ipBytes(srcIp), 0, p, 12, 4)
        System.arraycopy(ipBytes(dstIp), 0, p, 16, 4)
        val udpOffset = 20
        writeU16(p, udpOffset, srcPort)
        writeU16(p, udpOffset + 2, dstPort)
        writeU16(p, udpOffset + 4, udpLen)
        writeU16(p, udpOffset + 6, 0)
        System.arraycopy(payload, 0, p, udpOffset + 8, payload.size)
        val ipChecksum = checksum(p, 0, 20)
        p[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        p[11] = (ipChecksum and 0xFF).toByte()
        // UDP checksum with pseudo-header.
        var sum: Long = 0
        for (i in 12 until 20) sum += (p[i].toInt() and 0xFF).toLong() shl (if ((i - 12) % 2 == 0) 8 else 0)
        sum += 17
        sum += udpLen.toLong()
        var i = 0
        while (i < udpLen - 1) {
            sum += ((p[udpOffset + i].toInt() and 0xFF) shl 8) or
                (p[udpOffset + i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < udpLen) sum += (p[udpOffset + i].toInt() and 0xFF) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        var udpChecksum = sum.inv().toInt() and 0xFFFF
        if (udpChecksum == 0) udpChecksum = 0xFFFF
        writeU16(p, udpOffset + 6, udpChecksum)
        return p
    }

    private fun writeU16(buf: ByteArray, off: Int, value: Int) {
        buf[off] = ((value shr 8) and 0xFF).toByte()
        buf[off + 1] = (value and 0xFF).toByte()
    }

    private fun checksum(buf: ByteArray, off: Int, len: Int): Int {
        var sum = 0
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += ((buf[i].toInt() and 0xFF) shl 8) or (buf[i + 1].toInt() and 0xFF)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        while ((sum shr 16) > 0) sum = (sum and 0xFFFF) + (sum shr 16)
        return sum.inv() and 0xFFFF
    }

    private fun ipBytes(ip: String): ByteArray {
        val parts = ip.split('.')
        val out = ByteArray(4)
        for (k in 0 until minOf(4, parts.size)) {
            out[k] = (parts[k].toIntOrNull() ?: 0).toByte()
        }
        return out
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

    // ------------------------------ legacy TunnelEngine hookup (unused in
    // bot mode now that the native datapath carries the frames; kept intact
    // so TunnelEngine's TUNNEL branch still compiles and peer mode is
    // untouched).
    fun handleGamePacketToPeer(destIp: String, buffer: ByteArray, length: Int): Boolean {
        if (!armed) return false
        if (destIp != AppState.peerFabricatedIp) return false
        val p = parseUdp(buffer, length) ?: return false
        gameIp = p.srcIp
        gamePort = p.srcPort
        gameTargetPort = p.dstPort
        val eng = engine ?: return false
        val (reply, note) = eng.handle(p.payload)
        if (note == "not-stun") {
            noteGameFrame(p.payload)
            return true
        }
        if (reply != null) {
            injector?.invoke(reply, p.srcIp, p.srcPort, p.dstPort)
        }
        return true
    }

    private class Parsed(val srcIp: String, val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    /** Minimal IPv4+UDP parse of a raw TUN packet (legacy path). */
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
