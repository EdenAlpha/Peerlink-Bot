package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * T7 Signal keepalive + reflexive measurement -- the on-device port of the
 * bundle's `stun_keepalive.py`. Verified wire facts from the record:
 *
 *  - STUN BINDING_REQUEST (method 0x0001) carrying attr 0x9090 (RP_P2P_HEADER
 *    with the sender's own user_id), sent to pesam.stun.service.konami.net:3478
 *    every ~5 s for the whole session (403x in the match capture).
 *  - The 0x080B punch method is NOT answered by Konami STUN; that form belongs
 *    to the peer-punch conversation only.
 *  - BOTH identities beat in a real match; beating only the bot uid got the
 *    OWNER side stamped DISCONN_HB.
 *  - [mappedAddress] sends the known-answered T7 form from the SAME socket
 *    that beats and parses MAPPED-ADDRESS (0x0001) -- that ip:port is what the
 *    gate must be told. Run #30 advertised a stale mapping while the beats
 *    left from the current egress; the server saw liveness but could not
 *    correlate it -> DISCONN_HB anyway. Measure-then-advertise is the rule.
 */
object T7 {
    private val MAGIC = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)
    const val STUN_HOST = "pesam.stun.service.konami.net"
    const val STUN_PORT = 3478
    const val INTERVAL_MS = 5_000L

    /** T7 Signal: BINDING(0x0001) + attr 0x9090 (own user_id). */
    fun t7(uid: String): ByteArray {
        val attr = PeerBotEngine.p2pHeader(uid)   // 24-byte 0x9090 value
        val buf = ByteBuffer.allocate(20 + 4 + attr.size).order(ByteOrder.BIG_ENDIAN)
        buf.putShort(0x0001)
        buf.putShort(attr.size.toShort())
        buf.put(MAGIC)
        val tid = ByteArray(12)
        SecureRandom().nextBytes(tid)
        buf.put(tid)
        buf.putShort(0x9090.toShort())
        buf.putShort(attr.size.toShort())
        buf.put(attr)
        return buf.array()
    }

    /**
     * Send one T7 from [sock] and read the MAPPED-ADDRESS (0x0001) reply --
     * the ip:port Konami STUN sees as this socket's source.
     */
    fun mappedAddress(sock: DatagramSocket, uid: String, timeoutMs: Int = 2_000): InetSocketAddress? {
        val req = t7(uid)
        val txid = req.copyOfRange(8, 20)
        val dst = InetSocketAddress(InetAddress.getByName(STUN_HOST), STUN_PORT)
        sock.broadcast = false
        sock.soTimeout = timeoutMs
        sock.send(DatagramPacket(req, req.size, dst))
        val buf = ByteArray(2048)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (_: Throwable) {
                return null
            }
            if (pkt.length < 20) continue
            if (!(buf[4] == MAGIC[0] && buf[5] == MAGIC[1] && buf[6] == MAGIC[2] && buf[7] == MAGIC[3])) continue
            if (!buf.copyOfRange(8, 20).contentEquals(txid)) continue
            val msgLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val msgEnd = minOf(20 + msgLen, pkt.length)
            var off = 20
            while (off + 4 <= msgEnd) {
                val atype = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                val alen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                if (atype == 0x0001 && alen >= 8 && off + 4 + alen <= msgEnd) {
                    val port = ((buf[off + 6].toInt() and 0xFF) shl 8) or (buf[off + 7].toInt() and 0xFF)
                    val ip = InetAddress.getByAddress(buf.copyOfRange(off + 8, off + 12))
                    return InetSocketAddress(ip, port)
                }
                off += 4 + alen + ((4 - (alen % 4)) % 4)
            }
        }
        return null
    }

    /**
     * The game's NAT service over IPv6, if this device can reach one.
     *
     * The engine publishes a local address per address family, and it only
     * has an IPv6 one when its own IPv6 traversal works.  The transport's
     * add-peer keeps a peer's id only when the IPv4 add AND the IPv6 add
     * both succeed, so the IPv6 half is not optional for the connect: with
     * no IPv6 channel the engine throws the peer's id away and stops at the
     * punch (see docs/ENGINE_DRIVEN_P2P.md).
     */
    fun serviceV6(): InetAddress? = runCatching {
        InetAddress.getAllByName(STUN_HOST).firstOrNull { it is Inet6Address }
    }.getOrNull()

    /**
     * Send one T7 from [sock6] over IPv6 and read the MAPPED-ADDRESS
     * (0x0001) reply -- the IPv6 ip:port the NAT service sees as this
     * socket's source.  Same rule as the IPv4 half: measure from the socket
     * that keeps beating, and advertise exactly what was measured.
     */
    fun mappedAddress6(
        sock6: DatagramSocket,
        addr6: InetAddress,
        uid: String,
        timeoutMs: Int = 2_000,
    ): InetSocketAddress? {
        val req = t7(uid)
        val txid = req.copyOfRange(8, 20)
        val dst = InetSocketAddress(addr6, STUN_PORT)
        sock6.soTimeout = timeoutMs
        runCatching { sock6.send(DatagramPacket(req, req.size, dst)) }
        val buf = ByteArray(2048)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock6.receive(pkt)
            } catch (_: Throwable) {
                return null
            }
            if (pkt.length < 20) continue
            if (!(buf[4] == MAGIC[0] && buf[5] == MAGIC[1] && buf[6] == MAGIC[2] && buf[7] == MAGIC[3])) continue
            if (!buf.copyOfRange(8, 20).contentEquals(txid)) continue
            val msgLen = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
            val msgEnd = minOf(20 + msgLen, pkt.length)
            var off = 20
            while (off + 4 <= msgEnd) {
                val atype = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                val alen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                // MAPPED-ADDRESS over IPv6: family(2) + port(2) + address(16)
                if (atype == 0x0001 && alen >= 20 && off + 4 + alen <= msgEnd) {
                    val port = ((buf[off + 6].toInt() and 0xFF) shl 8) or (buf[off + 7].toInt() and 0xFF)
                    val ip = InetAddress.getByAddress(buf.copyOfRange(off + 8, off + 24))
                    return InetSocketAddress(ip, port)
                }
                off += 4 + alen + ((4 - (alen % 4)) % 4)
            }
        }
        return null
    }
}

/**
 * Sustained T7 keepalive for one registration (the bot, and when this device
 * plays both slots, both uids). Measures the reflexive at bind time from the
 * beating socket and reports it exactly once via [onMeasured].
 */
class T7Keepalive(
    private val uids: List<String>,
    private val localPort: Int,
    private val onMeasured: (InetSocketAddress) -> Unit = {},
) {
    @Volatile
    private var running = false
    private var worker: Thread? = null

    var lastMapped: InetSocketAddress? = null
        private set

    /**
     * The IPv6 half of the same measurement: the NAT service's IPv6 view of
     * this socket.  Null when the device has no usable IPv6 (then the engine
     * cannot have an IPv6 channel either, and the connect stops at the
     * punch -- see docs/ENGINE_DRIVEN_P2P.md).
     */
    var lastMapped6: InetSocketAddress? = null
        private set

    fun start() {
        if (running || uids.isEmpty()) return
        running = true
        worker = thread(name = "t7-keepalive") {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
            }
            // The IPv6 half runs on its own socket: a different family, a
            // different port space, and the engine's IPv6 mapping has to be
            // measured from the socket that actually carries the IPv6 traffic.
            var sock6: DatagramSocket? = null
            var dst6: InetSocketAddress? = null
            try {
                val a6 = T7.serviceV6()
                if (a6 != null) {
                    val s6 = DatagramSocket(null).apply {
                        reuseAddress = true
                        bind(InetSocketAddress(localPort))
                    }
                    sock6 = s6
                    dst6 = InetSocketAddress(a6, T7.STUN_PORT)
                }
            } catch (e: Throwable) {
                AppState.appendLog("[T7        ] no IPv6 NAT service: $e")
            }
            try {
                val dst = InetSocketAddress(InetAddress.getByName(T7.STUN_HOST), T7.STUN_PORT)
                val first = T7.mappedAddress(sock, uids.first())
                if (first != null) {
                    lastMapped = first
                    onMeasured(first)
                }
                val s6 = sock6
                val d6 = dst6
                if (s6 != null && d6 != null) {
                    val first6 = T7.mappedAddress6(s6, d6.address, uids.first())
                    if (first6 != null) {
                        lastMapped6 = first6
                        AppState.appendLog("[T7        ] IPv6 mapping " +
                            "${first6.address?.hostAddress}:${first6.port}")
                    }
                }
                var lastBeat = 0L
                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeat >= T7.INTERVAL_MS) {
                        for (u in uids) {
                            val m = T7.t7(u)
                            runCatching { sock.send(DatagramPacket(m, m.size, dst)) }
                            if (s6 != null && d6 != null) {
                                runCatching { s6.send(DatagramPacket(m, m.size, d6)) }
                            }
                        }
                        lastBeat = now
                    }
                    sock.soTimeout = 250
                    val buf = ByteArray(2048)
                    val pkt = DatagramPacket(buf, buf.size)
                    try {
                        sock.receive(pkt)
                    } catch (_: Throwable) {
                        // timeout -- loop; the beat check above drives cadence
                    }
                }
            } catch (_: Throwable) {
                // socket gone; keepalive stops; callers see it via state hooks
            } finally {
                runCatching { sock.close() }
                runCatching { sock6?.close() }
            }
        }.also { worker = it }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
