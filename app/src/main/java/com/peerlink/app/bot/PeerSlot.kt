package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import android.os.Process
import android.os.SystemClock
import com.peerlink.app.bot.PacketFlowTrace.Point
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NetworkInterface
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
    const val REFLEXIVE_PEER_IP = "127.0.0.2"

    /**
     * Host-candidate alias handed to the engine. The reflexive candidate has
     * its own address and socket so original NAT policy can distinguish them.
     */
    val ENGINE_PORT: Int get() = engineSock4?.localPort ?: error("Engine ferry is not armed")
    val ENGINE_REFLEXIVE_PORT: Int get() = engineReflexiveSock4?.localPort ?: error("Reflexive ferry is not armed")

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

    private const val UDP_RECEIVE_CAPACITY = 65535
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
    @Volatile private var gameHostIp = ""
    @Volatile private var gameHostPort = 0

    /** The port the game sent TO (our declared port = .53.2:1162). */
    @Volatile
    private var gameTargetPort = 0
    @Volatile private var directGamePeer: InetSocketAddress? = null

    @Volatile private var socket: DatagramSocket? = null
    private val acceptedLocalSources = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var worker: Thread? = null
    private val loggedClasses = java.util.Collections.synchronizedSet(HashSet<Int>())
    @Volatile private var flowTrace = PacketFlowTrace(SystemClock::elapsedRealtime)

    /** Counts game-side packets once, including direct and VPN candidates. */
    internal fun gameTraffic(): BotPacketCounts = flowTrace.gameTraffic()

    fun logFlow(trigger: String) {
        AppState.appendLog("[BOT-FLOW] trigger=$trigger armed=$armed engineAttached=$engineAttached " + flowTrace.summary())
    }

    /** Give the blocking socket workers a chance to run when both engines are active. */
    private fun prioritizeForwarder() {
        val tid = Process.myTid()
        val name = Thread.currentThread().name
        val target = Process.THREAD_PRIORITY_DISPLAY
        runCatching {
            val before = Process.getThreadPriority(tid)
            if (before > target) Process.setThreadPriority(tid, target)
            AppState.appendLog("[BOT-FLOW] THREAD_PRIORITY name=$name tid=$tid beforeNice=$before " +
                "actualNice=${Process.getThreadPriority(tid)}")
        }.onFailure {
            AppState.appendLog("[BOT-FLOW] THREAD_PRIORITY name=$name tid=$tid requestedNice=$target " +
                "result=${it.javaClass.simpleName}")
        }
    }

    private fun noteDrop(reason: String) {
        val count = flowTrace.drop(reason)
        if (count == 1L || count % 1000 == 0L)
            AppState.appendLog("[BOT-FLOW] DROP reason=$reason count=$count observedAtMs=${SystemClock.elapsedRealtime()}")
    }
    private fun recordUnsent(point: Point, reflexive: Boolean, payload: ByteArray, receipt: FerryReceipt) {
        flowTrace.record(point, reflexive, payload.size)
        BotWireRecorder.recordTransfer(point, reflexive, payload, receipt)
    }

    private fun forward(socket: DatagramSocket, packet: DatagramPacket, payload: ByteArray, point: Point,
                        reflexive: Boolean, receipt: FerryReceipt, errorPrefix: String) {
        val sendStart = System.nanoTime()
        try {
            socket.send(packet)
        } catch (error: Exception) {
            recordUnsent(point, reflexive, payload, receipt)
            noteDrop(errorPrefix + error.javaClass.simpleName)
            return
        }
        val sent = System.nanoTime()
        val sentWall = System.currentTimeMillis()
        val cpu = android.os.Debug.threadCpuTimeNanos()
        val cpuDelta = if (cpu >= receipt.cpu && receipt.cpu >= 0) cpu - receipt.cpu else -1
        flowTrace.record(point, reflexive, payload.size)
        flowTrace.record(if (point == Point.GAME_RX) Point.ENGINE_TX else Point.GAME_TX, reflexive, payload.size)
        BotWireRecorder.recordTransfer(point, reflexive, payload, receipt, sendStart, sent, sentWall, cpuDelta)
    }

    /** Reserve before the gate advertises a port; do not collide with the game's own UDP socket. */
    @Synchronized fun reserveEndpoint(): Int = reserveEndpoint(0)

    private fun reserveEndpoint(port: Int): Int {
        val existing = socket
        if (existing != null && !existing.isClosed) return existing.localPort
        val reserved = DatagramSocket(null)
        try {
            // A real game's host candidate can reach this phone directly;
            // fabricated-address packets arrive from the VPN on loopback.
            reserved.bind(InetSocketAddress("0.0.0.0", port))
            socket = reserved
            return reserved.localPort
        } catch (error: Exception) { reserved.close(); throw error }
    }

    /**
     * Invoked the moment this slot's loopback socket exists. The VPN service
     * uses it to RE-CONNECT the native backend's peer UDP socket: the backend
     * connects to 127.0.0.1:1162 at VPN start -- before the slot exists --
     * and the first keepalive against the unbound port poisons the connected
     * socket (one ICMP port-unreachable poisons every later send on a
     * connected UDP socket).
     */
    @Volatile
    var onArmed: ((Int) -> Unit)? = null

    /** Observe without reserving a new socket during a service-stop race. */
    internal fun ownsListener(port: Int): Boolean = armed && socket?.let { !it.isClosed && it.localPort == port } == true

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
    @Synchronized fun arm(oppUid: Long, botUid: Long, announced: String, localIp: String, localPort: Int) {
        // Idempotent re-arm: the driver arms this slot the moment go_match
        // fires (socket live, reactive), then re-targets it once the session
        // reply carries the game's address. Never churn the socket or lose
        // the punch-watch state on the update.
        if (armed && socket != null) {
            require(gameUid == oppUid || gameUid == 0L || oppUid == 0L) { "A different game owns this ferry; start a new room" }
            if (oppUid > 0) gameUid = oppUid
            if (announced.isNotBlank()) gameAddress = announced
            if (localIp.isNotBlank()) { gameIp = localIp; gameHostIp = localIp }
            if (localPort > 0) { gamePort = localPort; gameHostPort = localPort }
            AppState.appendLog("[BOT-SLOT  ] target updated: " +
                "announced=$gameAddress local=$gameIp:$gamePort")
            return
        }
        gameUid = oppUid
        gameAddress = announced
        gameIp = localIp
        gamePort = localPort
        gameHostIp = localIp
        gameHostPort = localPort
        acceptedLocalSources.clear()
        flowTrace = PacketFlowTrace(SystemClock::elapsedRealtime)
        try {
            gameTargetPort = reserveEndpoint()
            val listener = requireNotNull(socket)
            armed = true
            openEngineSockets()
            worker = thread(name = "peer-slot-rx") { receiveLoop(listener) }
            AppState.appendLog("[BOT-SLOT  ] ferry armed: game uid=$oppUid bot uid=$botUid " +
                (if (localIp.isNotBlank() && localPort > 0) "local=$localIp:$localPort" else "local address pending") +
                " -- on $LOOPBACK_PEER_IP:${listener.localPort}")
            runCatching { onArmed?.invoke(listener.localPort) }
        } catch (error: Exception) {
            disarm("socket_start_failed")
            throw IllegalStateException("Could not start the local match sockets", error)
        }
    }

    @Synchronized internal fun finishRound() {
        val port = socket?.localPort ?: return
        disarm("match_ended")
        // Keep the port already advertised for this room. New round-specific sockets and
        // learned routes are installed by arm() once both players are ready again.
        reserveEndpoint(port)
    }

    @Synchronized fun disarm(reason: String = "peer_slot_disarm") {
        AppState.appendLog("[BOT-DIAG] PEERLINK_STOP component=packet_ferry reason=$reason observedAtMs=${SystemClock.elapsedRealtime()}")
        logFlow("before_disarm")
        armed = false
        engineAttached = false
        engineHeard = false
        directGamePeer = null
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
        prioritizeForwarder()
        val buf = ByteArray(UDP_RECEIVE_CAPACITY)
        val pkt = DatagramPacket(buf, buf.size)
        var rxCount = 0
        while (armed && !s.isClosed) {
            pkt.length = buf.size
            val receiveCall = System.nanoTime()
            val receipt = try {
                s.receive(pkt)
                FerryReceipt.now(receiveCall)
            } catch (error: Throwable) {
                if (armed && !s.isClosed) noteDrop("game_receive_${error.javaClass.simpleName}")
                if (!armed) break else continue
            }
            val n = pkt.length
            if (n <= 0) continue
            rxCount++
            val data = buf.copyOf(n)
            if (rxCount <= 8 || rxCount % 1000 == 0) {
                AppState.appendLog("[BOT-SLOT  ] game transport rx #$rxCount n=$n")
            }

            val fromBackend = pkt.address.isLoopbackAddress && pkt.port == BACKEND_PORT
            if (!fromBackend) {
                // Raw traffic addressed to the server-advertised host candidate
                // has no inner IP header. Carry it unchanged and reply to the
                // same local socket (including the VPN's ordinary UDP proxy).
                val sourceIp = pkt.address.hostAddress.orEmpty()
                val ownedAddress = pkt.address.isLoopbackAddress || sourceIp == gameHostIp ||
                    sourceIp in acceptedLocalSources || runCatching {
                        NetworkInterface.getByInetAddress(pkt.address) != null
                    }.getOrDefault(false).also { if (it) acceptedLocalSources.add(sourceIp) }
                if (!ownedAddress) {
                    noteDrop("unexpected_game_source")
                    if (loggedClasses.add(-3)) AppState.appendLog(
                        "[BOT-SLOT] rejected source=$sourceIp:${pkt.port} expectedGame=$gameHostIp:$gameHostPort " +
                            "declaredBot=${BotMode.hostIp} listener=${s.localPort}")
                    continue
                }
                directGamePeer = pkt.socketAddress as? InetSocketAddress
                if (engineAttached) {
                    sendToEngine(data, false, receipt)
                    noteGameFrame(data)
                } else {
                    recordUnsent(Point.GAME_RX, false, data, receipt)
                    noteDrop("game_to_engine_not_attached")
                }
                continue
            }
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
                noteDrop("invalid_tunnel_header")
                AppState.appendLog("[BOT-SLOT  ] rx #$rxCount diag-strip -> null (dropped)")
                continue
            }
            val inner = parseInnerUdp(stripped)
            if (inner == null) {
                noteDrop("invalid_inner_udp")
                AppState.appendLog("[BOT-SLOT  ] rx #$rxCount parse -> not IPv4/UDP " +
                    "(len=${stripped.size} head=${stripped.take(12).joinToString("") { "%02x".format(it) }})")
                continue
            }
            val fromGame = inner.dstIp == AppState.peerFabricatedIp
            if (!fromGame) {
                noteDrop("unexpected_tunnel_destination")
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
                recordUnsent(Point.GAME_RX, true, inner.udpPayload, receipt)
                noteDrop("game_to_engine_not_attached")
                if (loggedClasses.add(-1)) {
                    AppState.appendLog("[BOT-SLOT  ] the engine is not attached -- " +
                        "the game's frame is carried nowhere; the bot answers NOTHING")
                }
                continue
            }
            sendToEngine(inner.udpPayload, true, receipt)
            noteGameFrame(inner.udpPayload)
        }
    }

    /**
     * Separate loopback aliases preserve the opponent's host and reflexive
     * candidates. Original SDK payloads stay unchanged; only their local UDP
     * transport is translated to the appropriate game route.
     */
    @Volatile
    private var engineSock4: DatagramSocket? = null
    @Volatile private var engineReflexiveSock4: DatagramSocket? = null
    @Volatile private var engineReflexivePeer4: InetSocketAddress? = null

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

    private fun boundEngineSocket(address: String, port: Int, reuse: Boolean): DatagramSocket {
        val opened = DatagramSocket(null)
        try {
            opened.reuseAddress = reuse
            opened.bind(InetSocketAddress(address, port))
            return opened
        } catch (error: Exception) {
            opened.close()
            throw error
        }
    }

    private fun openEngineSockets() {
        val s4 = boundEngineSocket(LOOPBACK_PEER_IP, 0, true)
        engineSock4 = s4
        val reflexive = boundEngineSocket(REFLEXIVE_PEER_IP, 0, false)
        engineReflexiveSock4 = reflexive
        val s6 = try {
            boundEngineSocket("::1", s4.localPort, true)
        } catch (error: Exception) {
            AppState.appendLog("[BOT-SLOT  ] no IPv6 loopback peer: ${error.message}")
            null
        }
        engineSock6 = s6
        AppState.appendLog("[BOT-SLOT  ] the engine's peer is $LOOPBACK_PEER_IP:$ENGINE_PORT" +
            " (host), $REFLEXIVE_PEER_IP:$ENGINE_REFLEXIVE_PORT (reflexive)" +
            (if (s6 != null) " and [::1]:$ENGINE_PORT" else " (IPv4 only)"))
        startEngineReplyLoop(s4, reflexive, s6)
    }

    private fun closeEngineSockets() {
        runCatching { engineSock4?.close() }
        runCatching { engineReflexiveSock4?.close() }
        runCatching { engineSock6?.close() }
        engineSock4 = null
        engineReflexiveSock4 = null
        engineReflexivePeer4 = null
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
    private fun sendToEngine(payload: ByteArray, reflexive: Boolean, receipt: FerryReceipt) {
        val v4Peer = if (reflexive) engineReflexivePeer4 ?: enginePeer4 else enginePeer4 ?: engineReflexivePeer4
        val v4Socket = if (reflexive) engineReflexiveSock4 else engineSock4
        val useV4 = v4Peer != null && v4Socket != null
        val peer = if (useV4) v4Peer else enginePeer6
        val sender = if (useV4) v4Socket else engineSock6
        if (peer == null || sender == null) {
            recordUnsent(Point.GAME_RX, reflexive, payload, receipt)
            noteDrop("game_to_engine_no_socket")
            return
        }
        forward(sender, DatagramPacket(payload, payload.size, peer), payload, Point.GAME_RX, reflexive, receipt, "engine_send_")
    }

    /**
     * Host-candidate frames return as raw UDP. Reflexive-candidate frames use
     * the VPN carrier and fabricated peer address. The originating candidate
     * selects its route; traffic on the other candidate cannot change it.
     */
    private fun startEngineReplyLoop(s4: DatagramSocket, reflexive4: DatagramSocket, s6: DatagramSocket?) {
        engineRxThread?.interrupt()
        engineRxThread = thread(name = "bot-engine-rx") {
            fun pump(s: DatagramSocket, isV6: Boolean, reflexive: Boolean) {
                prioritizeForwarder()
                val buf = ByteArray(UDP_RECEIVE_CAPACITY)
                val pkt = DatagramPacket(buf, buf.size)
                while (armed && !s.isClosed) {
                    pkt.length = buf.size
                    val receiveCall = System.nanoTime()
                    val receipt = try {
                        s.receive(pkt)
                        FerryReceipt.now(receiveCall)
                    } catch (error: Throwable) {
                        if (armed && !s.isClosed) noteDrop("engine_receive_${error.javaClass.simpleName}")
                        continue
                    }
                    val n = pkt.length
                    if (n > 0) {
                        val from = pkt.socketAddress as? InetSocketAddress
                        if (from != null) {
                            val known = if (isV6) enginePeer6 != null else if (reflexive) engineReflexivePeer4 != null else enginePeer4 != null
                            if (isV6) enginePeer6 = from else if (reflexive) engineReflexivePeer4 = from else enginePeer4 = from
                            if (!known) {
                                AppState.appendLog("[BOT-SLOT  ] the engine's own socket " +
                                    "is $from (${if (isV6) "IPv6" else if (reflexive) "IPv4 reflexive" else "IPv4 host"}) -- " +
                                    "the game's frames go there")
                            }
                        }
                        forwardEngineFrame(buf.copyOf(n), reflexive, receipt)
                    }
                }
            }
            val tr = thread(name = "bot-engine-reflexive-rx") { pump(reflexive4, false, true) }
            val t6 = if (s6 != null) thread(name = "bot-engine-rx6") { pump(s6, true, false) } else null
            pump(s4, false, false)
            tr.join()
            t6?.join()
        }
    }

    private fun forwardEngineFrame(payload: ByteArray, reflexive: Boolean, receipt: FerryReceipt) {
        if (!engineHeard) {
            engineHeard = true
            AppState.appendLog("[BOT-SLOT  ] the engine's own bytes are flowing " +
                "(${payload.size} B first frame) -- the whole exchange is the " +
                "engine's from here")
        }
        if (!reflexive) {
            val direct = directGamePeer ?: if (gameHostIp.isNotBlank() && gameHostPort > 0)
                InetSocketAddress(gameHostIp, gameHostPort) else null
            val sender = socket
            if (direct == null || sender == null) {
                recordUnsent(Point.ENGINE_RX, false, payload, receipt)
                noteDrop("engine_to_game_no_host_route")
            } else {
                forward(sender, DatagramPacket(payload, payload.size, direct), payload, Point.ENGINE_RX, false, receipt, "game_host_send_")
            }
            return
        }
        if (gameIp.isBlank() || gamePort <= 0) {
            recordUnsent(Point.ENGINE_RX, true, payload, receipt)
            noteDrop("engine_to_game_no_vpn_route")
            return
        }
        val out = buildInnerUdpPacket(
            srcIp = AppState.peerFabricatedIp,
            dstIp = gameIp,
            srcPort = gameTargetPort,
            dstPort = gamePort,
            payload = payload,
        )
        val to = socket
        if (to == null) {
            recordUnsent(Point.ENGINE_RX, true, payload, receipt)
            noteDrop("engine_to_game_no_ferry_socket")
            return
        }
        forward(to, DatagramPacket(out, out.size, InetSocketAddress(LOOPBACK_PEER_IP, BACKEND_PORT)),
            payload, Point.ENGINE_RX, true, receipt, "game_vpn_send_")
    }

    // ------------------------------------------------ wire helpers
    /** Strip the optional 56-byte native diag header; null when unusable. */
    private fun stripDiagHeader(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        if (data.size < 4) return data
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
        val receipt = FerryReceipt.now(System.nanoTime())
        val p = parseUdp(buffer, length) ?: return false
        gameIp = p.srcIp
        gamePort = p.srcPort
        gameTargetPort = p.dstPort
        if (!engineAttached) {
            recordUnsent(Point.GAME_RX, true, p.payload, receipt)
            noteDrop("game_to_engine_not_attached")
            return false
        }
        sendToEngine(p.payload, true, receipt)
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
