package com.peerlink.app.bot

import java.net.DatagramPacket
import java.net.DatagramSocket
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
        buf.putShort(0x9090)
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

    fun start() {
        if (running || uids.isEmpty()) return
        running = true
        worker = thread(name = "t7-keepalive") {
            val sock = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(localPort))
            }
            try {
                val dst = InetSocketAddress(InetAddress.getByName(T7.STUN_HOST), T7.STUN_PORT)
                val first = T7.mappedAddress(sock, uids.first())
                if (first != null) {
                    lastMapped = first
                    onMeasured(first)
                }
                var lastBeat = 0L
                while (running) {
                    val now = System.currentTimeMillis()
                    if (now - lastBeat >= T7.INTERVAL_MS) {
                        for (u in uids) {
                            val m = T7.t7(u)
                            runCatching { sock.send(DatagramPacket(m, m.size, dst)) }
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
            }
        }.also { worker = it }
    }

    fun stop() {
        running = false
        worker?.interrupt()
        worker = null
    }
}
