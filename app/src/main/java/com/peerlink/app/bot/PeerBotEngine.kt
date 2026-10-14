package com.peerlink.app.bot

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * One parsed STUN datagram from eFootball's P2P signaling.
 *
 * [attrs] are (code, value) pairs; values are the raw, unpadded bytes of each
 * attribute (padding is stripped by the parser).
 */
data class Stun(
    val type: Int,
    val tid: ByteArray,
    val attrs: List<Pair<Int, ByteArray>>,
) {
    override fun equals(other: Any?): Boolean = other is Stun && other.type == type && other.tid.contentEquals(tid) && other.attrs == attrs
    override fun hashCode(): Int = type * 31 + tid.contentHashCode()
}

/**
 * The far-side PeerLink bot, on-device: speaks eFootball's P2P signaling so the
 * real game (behind the PeerLink VPN faking open NAT) never falls back to relay.
 *
 * This is the Kotlin port of `work/peer_bot.py`, whose builders were validated
 * **byte-exact** against the real-match capture (98/99 responses identical; the
 * one miss was a single capability code, now fixed here via the first=2 rule).
 *
 * Wire layer decoded from capture m4/m5:
 *
 *   SIGNALING (plaintext STUN, no tunnel header) -- speakable:
 *     punch 0x080b/0x090b  48 B   attr 0x9090 (24 B)
 *     sess  0x080c/0x090c  256 B long (0x0026 zero-block + 0x9090 + 0x9091)
 *                            64 B short (0x9090 + 0x9091)
 *     cap   0x080a/0x090a  48 B req; 56 B res (0x909d UHP_FINALIZE_RET)
 *
 *   GAMEPLAY (class-0x00 frames) stays encrypted with the game's own cipher.
 *   Its key exchange (the first class-0xc0 frame) is NOT yet recovered; this
 *   engine holds the signaling layer so the stream stays in P2P. That is the
 *   boundary of what is proven.
 *
 * Pure builders/parser/[handle] are socket-free and unit-testable; [open],
 * [originate] and [pump] drive a real UDP socket for live play.
 */
class PeerBotEngine(private val uid: String) {

    companion object {
        private val MAGIC = byteArrayOf(0x21, 0x12, 0xA4.toByte(), 0x42)

        const val PUNCH_REQ = 0x080B
        const val PUNCH_RES = 0x090B
        const val CAP_REQ = 0x080A
        const val CAP_RES = 0x090A
        const val SESS_REQ = 0x080C
        const val SESS_RES = 0x090C

        const val A_P2P = 0x9090        // RP_P2P_HEADER
        const val A_REL = 0x9091        // RP_RELIABILITY
        const val A_REL_ECHO = 0x909C   // RP_RELIABILITY_ECHO
        const val A_UHP = 0x909D        // UHP_FINALIZE_RET
        const val A_ZERO = 0x0026       // zero block (session long form)

        /** 8-byte constant prefix of the 0x9090 attribute value. */
        private val P2P_PREFIX = byteArrayOf(0x02, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00)

        private val RANDOM = SecureRandom()

        /** Lowercase hex, for tests and logging. */
        fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

        fun u16(v: Int): ByteArray = byteArrayOf(((v ushr 8) and 0xFF).toByte(), (v and 0xFF).toByte())

        /** STUN attribute: code u16be | length u16be | value | pad-to-4. */
        fun attr(code: Int, value: ByteArray): ByteArray {
            val pad = (4 - (value.size % 4)) % 4
            val buf = ByteBuffer.allocate(4 + value.size + pad).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(code.toShort())
            buf.putShort(value.size.toShort())
            buf.put(value)
            repeat(pad) { buf.put(0) }
            return buf.array()
        }

        /** 0x9090 value: 8-byte prefix + own user_id as ASCII, NUL-padded to 16. */
        fun p2pHeader(id: String): ByteArray {
            val s = id.toByteArray(Charsets.US_ASCII)
            require(s.size <= 16) { "user_id too long" }
            val out = ByteArray(8 + 16)
            System.arraycopy(P2P_PREFIX, 0, out, 0, 8)
            System.arraycopy(s, 0, out, 8, s.size)
            return out
        }

        /** Full STUN datagram: type u16be | length u16be | magic | tid(12) | attrs. */
        fun stun(mtype: Int, tid: ByteArray, attrs: ByteArray): ByteArray {
            require(tid.size == 12) { "tid must be 12 bytes" }
            val buf = ByteBuffer.allocate(20 + attrs.size).order(ByteOrder.BIG_ENDIAN)
            buf.putShort(mtype.toShort())
            buf.putShort(attrs.size.toShort())
            buf.put(MAGIC)
            buf.put(tid)
            buf.put(attrs)
            return buf.array()
        }

        /** 188-byte attr 0x0026: first byte 0x09, rest zeros (from capture). */
        fun zeroBlock(): ByteArray = ByteArray(188).also { it[0] = 0x09 }

        /**
         * Byte-exact 12-byte reliability value seen in the capture:
         *   `00 01 01 <seed> | 00 00 00 00 | <counter u32be>`
         * Long-form sessions use seed 0x03; short-form use 0x04.
         */
        fun buildReliability(counter: Int, seed: Int): ByteArray {
            val head = byteArrayOf(0x00, 0x01, 0x01, (seed and 0xFF).toByte(), 0x00, 0x00, 0x00, 0x00)
            val cnt = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(counter).array()
            return head + cnt
        }

        // -------------------------------------------------------- builders
        fun punchReq(tid: ByteArray, id: String) = stun(PUNCH_REQ, tid, attr(A_P2P, p2pHeader(id)))
        fun punchRes(tid: ByteArray, id: String) = stun(PUNCH_RES, tid, attr(A_P2P, p2pHeader(id)))
        fun capReq(tid: ByteArray, id: String) = stun(CAP_REQ, tid, attr(A_P2P, p2pHeader(id)))

        /** Capability response: 0x909d = `00 04 | 00 <code>` + own 0x9090. */
        fun capRes(tid: ByteArray, id: String, code: Int): ByteArray {
            val uhp = byteArrayOf(0x00, 0x04, 0x00, (code and 0xFF).toByte())
            return stun(CAP_RES, tid, attr(A_UHP, uhp) + attr(A_P2P, p2pHeader(id)))
        }

        fun sessReqLong(tid: ByteArray, id: String, counter: Int) =
            stun(SESS_REQ, tid, attr(A_ZERO, zeroBlock()) + attr(A_P2P, p2pHeader(id)) + attr(A_REL, buildReliability(counter, 3)))

        fun sessResLong(tid: ByteArray, id: String, counter: Int) =
            stun(SESS_RES, tid, attr(A_ZERO, zeroBlock()) + attr(A_P2P, p2pHeader(id)) + attr(A_REL_ECHO, buildReliability(counter, 3)))

        fun sessReqShort(tid: ByteArray, id: String, counter: Int) =
            stun(SESS_REQ, tid, attr(A_P2P, p2pHeader(id)) + attr(A_REL, buildReliability(counter, 4)))

        fun sessResShort(tid: ByteArray, id: String, counter: Int) =
            stun(SESS_RES, tid, attr(A_P2P, p2pHeader(id)) + attr(A_REL_ECHO, buildReliability(counter, 4)))

        // -------------------------------------------------------- parsing
        fun parseStun(buf: ByteArray): Stun? {
            if (buf.size < 20) return null
            if (!(buf[4] == MAGIC[0] && buf[5] == MAGIC[1] && buf[6] == MAGIC[2] && buf[7] == MAGIC[3])) return null
            val bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN)
            val mtype = bb.short.toInt() and 0xFFFF
            val len = bb.short.toInt() and 0xFFFF
            if ((mtype and 0xC000) != 0) return null
            val tid = buf.copyOfRange(8, 20)
            val attrs = ArrayList<Pair<Int, ByteArray>>()
            var off = 20
            val end = minOf(20 + len, buf.size)
            while (off + 4 <= end) {
                val code = ((buf[off].toInt() and 0xFF) shl 8) or (buf[off + 1].toInt() and 0xFF)
                val alen = ((buf[off + 2].toInt() and 0xFF) shl 8) or (buf[off + 3].toInt() and 0xFF)
                if (off + 4 + alen > end) break
                attrs.add(code to buf.copyOfRange(off + 4, off + 4 + alen))
                off += 4 + ((alen + 3) and 0x3.inv())
            }
            return Stun(mtype, tid, attrs)
        }

        /** The peer's own user_id, stamped in its 0x9090 attribute. */
        fun ownUid(attrs: List<Pair<Int, ByteArray>>): String? {
            for ((code, v) in attrs) {
                if (code == A_P2P && v.size >= 24) {
                    var end = 24
                    while (end > 8 && v[end - 1] == 0.toByte()) end--
                    return String(v, 8, end - 8, Charsets.US_ASCII)
                }
            }
            return null
        }

        /** (seed, counter) from a 0x9091/0x909c reliability value. */
        fun relCounter(attrs: List<Pair<Int, ByteArray>>): Pair<Int, Long>? {
            for ((code, v) in attrs) {
                if ((code == A_REL || code == A_REL_ECHO) && v.size >= 12) {
                    val seed = v[3].toInt() and 0xFF
                    val counter = ByteBuffer.wrap(v, 8, 4).order(ByteOrder.BIG_ENDIAN).int.toLong() and 0xFFFFFFFFL
                    return seed to counter
                }
            }
            return null
        }

        /**
         * Structural + byte-exact self-test, mirroring the Python `selftest()`.
         * Callable from a unit test or a debug screen; no socket, no phone.
         */
        fun selftest(): String {
            val p = punchReq(ByteArray(12) { 0x11 }, "12345")
            require(p.size == 48) { "punch size ${p.size}" }
            require(((p[0].toInt() and 0xFF) shl 8 or (p[1].toInt() and 0xFF)) == PUNCH_REQ)
            val rel = buildReliability(0x13, 3).hex()
            require(rel == "000101030000000000000013") { "rel3 $rel" }
            val rel4 = buildReliability(0x14, 4).hex()
            require(rel4 == "000101040000000000000014") { "rel4 $rel4" }
            require(sessReqLong(ByteArray(12) { 0x22 }, "12345", 0x13).size == 256)
            require(sessReqShort(ByteArray(12) { 0x33 }, "12345", 0x14).size == 64)
            require(capRes(ByteArray(12) { 0x44 }, "12345", 0).size == 56)
            // round-trip parse
            val rt = parseStun(punchReq(ByteArray(12) { 0x55 }, "1012115771"))!!
            require(ownUid(rt.attrs) == "1012115771") { "parse ownUid" }
            return "selftest OK: punch48 sess_long256 sess_short64 cap_res56 reliability byte-exact"
        }
    }

    /** The peer's address, learned from the first inbound datagram. */
    var peer: InetSocketAddress? = null
        private set

    /** The peer's user_id, read from its 0x9090 stamp. */
    var peerUid: String? = null
        private set

    private var sock: DatagramSocket? = null
    private var count = 0
    private var capResSent = false

    /** Bind a UDP socket; returns the local address actually chosen. */
    fun open(bindPort: Int = 0): InetSocketAddress {
        val s = DatagramSocket(null).apply {
            reuseAddress = true
            bind(InetSocketAddress(bindPort))
        }
        sock = s
        return s.localSocketAddress as InetSocketAddress
    }

    /**
     * Answer one inbound datagram. Pure — no socket touched, so it is
     * unit-testable. Returns (reply bytes or null, note).
     */
    fun handle(buf: ByteArray, src: InetSocketAddress? = null): Pair<ByteArray?, String> {
        val p = parseStun(buf) ?: return null to "not-stun"
        val id = ownUid(p.attrs)
        if (id != null) peerUid = id
        if (src != null) peer = src

        return when (p.type) {
            PUNCH_REQ -> punchRes(p.tid, uid) to "punch_res"
            CAP_REQ -> {
                // Capture rule: first capability response carries code 2, all
                // later ones carry code 0. This was the single replay miss.
                val code = if (capResSent) 0 else 2
                capResSent = true
                capRes(p.tid, uid, code) to "cap_res(code=$code)"
            }
            SESS_REQ -> {
                val counter = (relCounter(p.attrs)?.second ?: 0L).toInt()
                val longForm = p.attrs.any { it.first == A_ZERO }
                if (longForm) sessResLong(p.tid, uid, counter) to "sess_res_long"
                else sessResShort(p.tid, uid, counter) to "sess_res_short"
            }
            else -> null to "type=0x%04x".format(p.type)
        }
    }

    /** Originate our own punch + session(s) + capability toward the peer. */
    fun originate(dest: InetSocketAddress, nSession: Int = 3): Int {
        val s = sock ?: error("call open() first")
        val msgs = ArrayList<ByteArray>()
        msgs.add(punchReq(randomTid(), uid))
        repeat(nSession) {
            count += 1
            msgs.add(sessReqLong(randomTid(), uid, 0x13 + count))
        }
        msgs.add(capReq(randomTid(), uid))
        for (m in msgs) s.send(DatagramPacket(m, m.size, dest))
        return msgs.size
    }

    /** Receive one datagram, answer it, return a log line (or null on timeout). */
    fun pump(timeoutMs: Int = 50): String? {
        val s = sock ?: return null
        s.soTimeout = timeoutMs
        val buf = ByteArray(4096)
        val pkt = DatagramPacket(buf, buf.size)
        try {
            s.receive(pkt)
        } catch (_: SocketTimeoutException) {
            return null
        }
        val data = buf.copyOfRange(pkt.offset, pkt.offset + pkt.length)
        val src = pkt.socketAddress as? InetSocketAddress
        val (out, note) = handle(data, src)
        if (out != null && src != null) s.send(DatagramPacket(out, out.size, src))
        return "<- ${src?.address?.hostAddress}:${src?.port} $note (${data.size} B)"
    }

    fun close() {
        sock?.close()
        sock = null
    }

    private fun randomTid(): ByteArray {
        val t = ByteArray(12)
        RANDOM.nextBytes(t)
        return t
    }
}
