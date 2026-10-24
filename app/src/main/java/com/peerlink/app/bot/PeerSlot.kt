package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Bot mode -- the on-device FERRY between the game's P2P socket and the
 * ENGINE's own P2P socket. Nothing else.
 *
 * Rewritten 2026-10-24: the fabricated answering code (PeerBotEngine -- the
 * punch/session/capability answerers and the punch originate burst) is
 * DELETED, not neutered. The bot answers the game's P2P frames never. The
 * engine inside this app owns the whole P2P exchange: its own punch, its
 * own session/capability bytes, its own protocol. What this slot does is
 * pure transport on one phone: the game's frames travel to the engine's
 * socket, the engine's frames travel back to the game through the native
 * backend. The fabricated identities (197.210.53.x) are the ADDRESSING
 * plate the server hands both sides so that two P2P stacks on one phone
 * can find each other -- they are routing, not answers.
 *
 * Wire details taken from `peerlink_backend.cpp`:
 *  - tunnel-out frames: sendto(peer_addr), diag header is OPTIONAL to parse
 *    (`parse_tunnel_diag_meta` failure -> whole datagram is the payload);
 *  - 1-byte control codes: 0xFC -> reply 0xFD (peer probe), 0xFF -> keepalive;
 *  - replies are written to the TUN as-is, so this ferry builds a complete
 *    inner IPv4/UDP packet (src = the game-side identity).
 */
object PeerSlot {

    /** The native backend's peer for bot mode: this very slot, on loopback. */
    const val LOOPBACK_PEER_IP = "127.0.0.1"

    /**
     * The loopback port the ENGINE is handed as its peer: its frames arrive on
     * this slot's sockets and its answers travel back to the game. The game
     * never sees this port -- it keeps punching the fabricated peer identity.
     */
    const val ENGINE_PORT = 1163

    /**
     * True once the engine holds this slot as its peer (the peer record was
     * accepted). Its frames are then carried to the game -- and ONLY its
     * frames; the bot never answers anything itself.
     */
    @Volatile
    var engineAttached: Boolean = false

    /**
     * True once the engine's own frames have been seen on this ferry. From
     * that moment the exchange is entirely the engine's bytes.
     */
    @Volatile
    var engineHeard: Boolean = false

    /** NativeBackendConfig.localPort -- the backend's bound peer socket. */
    const val BACKEND_PORT = 17024

    private const val DIAG_MAGIC = 0x504C4447
    private const val DIAG_VERSION = 2
    private const val DIAG_HEADER_SIZE = 56

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

    /** The port the game sent TO (our declared port = .53.2:1162). */
    @Volatile
    private var gameTargetPort = BotMode.BOT_PORT

    private var socket: DatagramSocket? = null
    private var worker: Thread? = null
    private val loggedClasses = java.util.Collections.synchronizedSet(HashSet<Int>())

    /**
     * Invoked the moment this slot's loopback socket exists. The VPN service
     * uses it to RE-CONNECT the native backend's peer UDP socket: the backend
     * connects to 127.0.0.1:1162 at VPN start -- before the slot exists --
     * and the first keepalive against the unbound port poisons the connected
     * socket (one ICMP port-unreachable poisons every later send on a
     * connected UDP socket).
     */
    @Volatile
    var onArmed: (() -> Unit)? = null

    /** TunnelEngine installs (and re-installs) the legacy injector lambda. */
    fun installInjector(fn: (ByteArray, String, Int, Int) -> Unit) {
        injector = fn
    }

    /**
     * Arm the ferry. [oppUid] = the game's user id (session addressing/logs);
     * [botUid] = the BOT's own user id (the stamp the engine carries as its
     * own identity). [announced]/[localIp]/[localPort] = the game's address
     * (logs + the punch target from the session's address list).
     */
    fun arm(oppUid: Long, botUid: Long, announced: String, localIp: String, localPort: Int) {
        // Idempotent re-arm: the driver arms this slot the moment go_match
        // fires (socket live, reactive), then re-targets it once the session
        // reply carries the game's address. Never churn the socket or lose
        // the punch-watch state on the update.
        if (armed && gameUid == oppUid && socket != null) {
            if (announced.isNotBlank()) gameAddress = announced
            if (localIp.isNotBlank()) gameIp = localIp
            if (localPort > 0) gamePort = localPort
            AppState.appendLog("[BOT-SLOT  ] target updated: " +
                "announced=$gameAddress local=$gameIp:$gamePort")
            return
        }
        gameUid = oppUid
        gameAddress = announced
        gameIp = localIp
        gamePort = localPort
        gameTargetPort = BotMode.BOT_PORT
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
        AppState.appendLog("[BOT-SLOT  ] ferry armed: game uid=$oppUid bot uid=$botUid " +
            (if (localIp.isNotBlank() && localPort > 0) "local=$localIp:$localPort"
             else "local address pending") +
            " -- on $LOOPBACK_PEER_IP:${BotMode.BOT_PORT}; " +
            "the ENGINE answers the game, this slot only carries the bytes")
        // The listener now exists -- clear the backend's poisoned connected
        // socket so its peer-TX sends reach this slot (see onArmed note).
        runCatching { onArmed?.invoke() }
        // The engine's own peer sockets, bound before anything is handed
        // over: its very first punch must have somewhere to land.
        openEngineSockets()
        worker = thread(name = "peer-slot-rx") { receiveLoop(s) }
    }

    fun disarm() {
        armed = false
        engineAttached = false
        engineHeard = false
        closeEngineSockets()
        runCatching { socket?.close() }
        socket = null
        worker = null
        AppState.appendLog("[BOT-SLOT  ] disarmed")
    }

    // ------------------------------------------------ loopback transport
    /**
     * Frames the native backend pulled out of the game's TUN arrive here and
     * are FORWARDED to the engine's own socket -- always, unchanged. The bot
     * answers nothing: if the engine is not attached the frame is logged and
     * dropped, honestly.
     */
    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(4096)
        var rxCount = 0
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
            rxCount++
            val data = buf.copyOf(n)
            val head = data.take(12).joinToString("") { "%02x".format(it) }
            AppState.appendLog("[BOT-SLOT  ] rx #$rxCount n=$n head=$head")

            if (n == 1) {
                when (data[0].toInt() and 0xFF) {
                    0xFC -> runCatching {
                        // the native backend's own probe -- transport, not the
                        // game's P2P; acknowledged like any local socket.
                        s.send(DatagramPacket(byteArrayOf(0xFDu.toByte()), 1, pkt.socketAddress))
                    }
                    0xFF -> Unit // native keepalive -- nothing to answer
                }
                continue
            }

            val stripped = stripDiagHeader(data)
            if (stripped == null) {
                AppState.appendLog("[BOT-SLOT  ] rx #$rxCount diag-strip -> null (dropped)")
                continue
            }
            val inner = parseInnerUdp(stripped)
            if (inner == null) {
                AppState.appendLog("[BOT-SLOT  ] rx #$rxCount parse -> not IPv4/UDP " +
                    "(len=${stripped.size} head=${stripped.take(12).joinToString("") { "%02x".format(it) }})")
                continue
            }
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

            // The ENGINE plays the opponent. Every game frame goes to its
            // socket unchanged; nothing is answered from here. If the engine
            // is not attached there is no answer at all -- that is the
            // honest state, not a fabricated one.
            if (!engineAttached) {
                if (loggedClasses.add(-1)) {
                    AppState.appendLog("[BOT-SLOT  ] the engine is not attached -- " +
                        "the game's frame is carried nowhere; the bot answers NOTHING")
                }
                continue
            }
            sendToEngine(inner.udpPayload)
            noteGameFrame(inner.udpPayload)
        }
    }

    /**
     * The engine's peer on this phone is the ferry itself: the engine is
     * handed 127.0.0.1:1163 (and [::1]:1163) as the opponent, so its own
     * frames arrive on these sockets and its answers travel back to the game
     * over the backend. The tunnel carries the game alone, so this is the
     * only place the engine's half can meet the game's half on one phone.
     */
    @Volatile
    private var engineSock4: DatagramSocket? = null

    @Volatile
    private var engineSock6: DatagramSocket? = null

    /**
     * Where the engine's own packets come from: the engine binds its own UDP
     * port when it starts, so the address is not known in advance. It is
     * learned from its first datagram -- that source address:port is the
     * engine's own socket, and it is where the game's frames must be sent.
     */
    @Volatile
    private var enginePeer4: InetSocketAddress? = null

    @Volatile
    private var enginePeer6: InetSocketAddress? = null

    @Volatile
    private var engineRxThread: Thread? = null

    private fun openEngineSockets() {
        if (engineSock4 != null) return
        val s4 = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(LOOPBACK_PEER_IP, ENGINE_PORT))
                soTimeout = 250
            }
        } catch (t: Throwable) {
            AppState.appendLog("[BOT-SLOT  ] cannot bind the engine's peer socket " +
                "$LOOPBACK_PEER_IP:$ENGINE_PORT: ${t.message}")
            return
        }
        val s6 = try {
            DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("::1", ENGINE_PORT))
                soTimeout = 250
            }
        } catch (t: Throwable) {
            AppState.appendLog("[BOT-SLOT  ] no IPv6 loopback peer: ${t.message}")
            null
        }
        engineSock4 = s4
        engineSock6 = s6
        AppState.appendLog("[BOT-SLOT  ] the engine's peer is $LOOPBACK_PEER_IP:$ENGINE_PORT" +
            (if (s6 != null) " and [::1]:$ENGINE_PORT" else " (IPv4 only)"))
        startEngineReplyLoop(s4, s6)
    }

    private fun closeEngineSockets() {
        runCatching { engineSock4?.close() }
        runCatching { engineSock6?.close() }
        engineSock4 = null
        engineSock6 = null
        enginePeer4 = null
        enginePeer6 = null
        engineRxThread = null
    }

    /**
     * The game's frame goes to the engine exactly as it arrived, addressed to
     * the engine's OWN socket -- the address its own packets come from (see
     * [enginePeer4]). Until the engine has spoken there is nowhere to send.
     */
    private fun sendToEngine(payload: ByteArray): Boolean {
        val a4 = enginePeer4
        val s4 = engineSock4
        if (a4 != null && s4 != null) {
            return runCatching {
                s4.send(DatagramPacket(payload, payload.size, a4))
                true
            }.getOrDefault(false)
        }
        val a6 = enginePeer6
        val s6 = engineSock6
        if (a6 != null && s6 != null) {
            return runCatching {
                s6.send(DatagramPacket(payload, payload.size, a6))
                true
            }.getOrDefault(false)
        }
        return false
    }

    /**
     * The engine's frames go back to the game, over the backend's peer
     * socket, in the shape the game expects: from the game-side peer
     * identity, to the game. The PAYLOAD is never touched -- the engine's
     * own bytes, end to end.
     */
    private fun startEngineReplyLoop(s4: DatagramSocket, s6: DatagramSocket?) {
        engineRxThread?.interrupt()
        engineRxThread = thread(name = "bot-engine-rx") {
            fun pump(s: DatagramSocket, isV6: Boolean) {
                val buf = ByteArray(4096)
                while (armed && !s.isClosed) {
                    val pkt = DatagramPacket(buf, buf.size)
                    val n = try {
                        s.receive(pkt)
                        pkt.length
                    } catch (_: Throwable) {
                        continue
                    }
                    if (n > 0) {
                        val from = pkt.socketAddress as? InetSocketAddress
                        if (from != null) {
                            val known = if (isV6) enginePeer6 != null else enginePeer4 != null
                            if (isV6) enginePeer6 = from else enginePeer4 = from
                            if (!known) {
                                AppState.appendLog("[BOT-SLOT  ] the engine's own socket " +
                                    "is $from (${if (isV6) "IPv6" else "IPv4"}) -- " +
                                    "the game's frames go there")
                            }
                        }
                        forwardEngineFrame(buf.copyOf(n))
                    }
                }
            }
            val t6 = if (s6 != null) thread(name = "bot-engine-rx6") { pump(s6, true) } else null
            pump(s4, false)
            t6?.join()
        }
    }

    private fun forwardEngineFrame(payload: ByteArray) {
        if (!engineHeard) {
            engineHeard = true
            AppState.appendLog("[BOT-SLOT  ] the engine's own bytes are flowing " +
                "(${payload.size} B first frame) -- the whole exchange is the " +
                "engine's from here")
        }
        if (gameIp.isBlank() || gamePort <= 0) return
        val out = buildInnerUdpPacket(
            srcIp = AppState.peerFabricatedIp,
            dstIp = gameIp,
            srcPort = gameTargetPort,
            dstPort = gamePort,
            payload = payload,
        )
        val to = socket ?: return
        runCatching {
            to.send(
                DatagramPacket(
                    out, out.size,
                    InetSocketAddress(LOOPBACK_PEER_IP, BACKEND_PORT)
                )
            )
        }
    }

    // ------------------------------------------------ wire helpers
    /** Strip the optional 56-byte native diag header; null when unusable. */
    private fun stripDiagHeader(data: ByteArray): ByteArray? {
        if (data.size < 1) return null
        // BIG-endian, like read_u32() in peerlink_backend.cpp (which writes the
        // magic with write_u32, also big-endian). On the wire the four bytes
        // are 50 4c 44 47 = "PLDG".
        val magic = ((data[0].toInt() and 0xFF) shl 24) or
            ((data[1].toInt() and 0xFF) shl 16) or
            ((data[2].toInt() and 0xFF) shl 8) or
            (data[3].toInt() and 0xFF)
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
    // untouched).  The fabricating answerer is gone -- this path only
    // FORWARDS the game's frame to the engine, same as the main ferry.
    fun handleGamePacketToPeer(destIp: String, buffer: ByteArray, length: Int): Boolean {
        if (!armed) return false
        if (destIp != AppState.peerFabricatedIp) return false
        val p = parseUdp(buffer, length) ?: return false
        gameIp = p.srcIp
        gamePort = p.srcPort
        gameTargetPort = p.dstPort
        if (!engineAttached) return false
        sendToEngine(p.payload)
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
        val dstIp = "%d.%d.%d.%d".format(
            buf[16].toInt() and 0xFF, buf[17].toInt() and 0xFF,
            buf[18].toInt() and 0xFF, buf[19].toInt() and 0xFF)
        val srcPort = ((buf[ihl].toInt() and 0xFF) shl 8) or (buf[ihl + 1].toInt() and 0xFF)
        val dstPort = ((buf[ihl + 2].toInt() and 0xFF) shl 8) or (buf[ihl + 3].toInt() and 0xFF)
        val payload = buf.copyOfRange(ihl + 8, length)
        return Parsed(srcIp, srcPort, dstPort, payload)
    }
}
