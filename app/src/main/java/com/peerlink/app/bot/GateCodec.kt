package com.peerlink.app.bot

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gate wire codec -- the Kotlin port of `gate_client.py`.
 *
 *   body = IV(16) || AES-256-CBC(KEY_B, PKCS7( zlib( msgpack(msg) ) ))
 *   sign = b64( HMAC_SHA256(secret|str(rand^clock), body) || clock_le || rand_le )
 *
 * [KEY_B] and [SIGN_SECRET] are protocol constants recovered from the game and
 * already published in the kgs-gate-cipher repo; they are not user
 * credentials. User credentials (the bot account) live only in the app's
 * device-private storage and never enter this file or the repo.
 */
object GateCodec {

    /** Gate body key, recovered 2026-10 (protocol constant). */
    private val KEY_B = byteArrayOf(
        0x43, 0x74, 0x09, 0x81.toByte(), 0x52, 0x3c, 0xdc.toByte(), 0x17,
        0x1e, 0x71, 0xde.toByte(), 0x2c, 0xca.toByte(), 0xb1.toByte(), 0xa5.toByte(), 0xa9.toByte(),
        0xb8, 0x6f, 0x4b, 0x83.toByte(), 0x31, 0x96, 0xc5.toByte(), 0x5f,
        0xac.toByte(), 0xd4.toByte(), 0xbd.toByte(), 0x25, 0x84, 0x6c, 0x33, 0xf5.toByte(),
    )

    private const val SIGN_SECRET = "Xrq-RtAF_91MAE82"

    const val HOST = "pes22-game.cs.konami.net"

    /** Per-build obfuscated constant behind s_keyword (struct @0x9932ab0 in 11.1.0 libUE4.so). */
    private const val SK_CONST_1101 = "YE5PUd6m"
    private const val SK_CONST_1100 = "hGcKSg6k"

    // 2026-10-15 store update: the live gate answers ERR_DEFCLIENTVER for
    // 6.1.0 and NOERR for 6.1.1 (proven live; the 11.1.0 s_keyword CONST is
    // still accepted, so this is the only constant the update moved).
    const val CLIENT_VERSION = "6.1.1"
    const val BUILD = "11.1.0"

    /**
     * s_keyword = CONST[i] ^ client_version[i % len(cv)]. Computed per request,
     * never stored -- it silently stops matching after a game update otherwise.
     */
    fun sKeyword(clientVersion: String = CLIENT_VERSION, build: String = BUILD): String {
        val konst = if (build == "11.0.1") SK_CONST_1100 else SK_CONST_1101
        val cv = clientVersion.toByteArray(Charsets.ISO_8859_1)
        val out = ByteArray(konst.length)
        for (i in out.indices) {
            out[i] = (konst[i].code xor cv[i % cv.size].code).toByte()
        }
        return String(out, Charsets.ISO_8859_1)
    }

    fun userAgent(clientVersion: String = CLIENT_VERSION, osName: String = "ANDROID",
                  version: String = "1.0", model: String = "redroid14_arm64_only",
                  build: String = BUILD): String {
        val t = (System.currentTimeMillis() / 1000L).toString()
        return "PES/$version ($osName 14; 0; $model; 0; $t; $clientVersion; ; $build; )"
    }

    // ------------------------------------------------------------- pack
    /** Minimal MessagePack writer, subset sufficient for gate bodies. */
    fun pack(msg: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        packValue(msg, out)
        return out.toByteArray()
    }

    private fun packValue(v: Any?, out: ByteArrayOutputStream) {
        when (v) {
            null -> out.write(0xc0)
            is Boolean -> out.write(if (v) 0xc3 else 0xc2)
            is Int -> packInt(v.toLong(), out)
            is Long -> packInt(v, out)
            is String -> packString(v, out)
            is ByteArray -> packBin(v, out)
            is Map<*, *> -> {
                packSize(v.size, out, 0x80, 0xde)
                for ((k, item) in v) {
                    packValue(k.toString(), out)
                    packValue(item, out)
                }
            }
            is List<*> -> {
                packSize(v.size, out, 0x90, 0xdc)
                for (item in v) packValue(item, out)
            }
            else -> throw IllegalArgumentException("gate: unsupported type ${v?.javaClass}")
        }
    }

    private fun packInt(v: Long, out: ByteArrayOutputStream) {
        when {
            v in 0..127 -> out.write(v.toInt())
            v < 0 && v >= -32 -> out.write(v.toInt() and 0xFF)
            v in 0..0xFF -> { out.write(0xcc); out.write(v.toInt()) }
            v in 0..0xFFFF -> { out.write(0xcd); writeU16(v.toInt(), out) }
            v in 0..0xFFFFFFFFL -> { out.write(0xce); writeU32(v, out) }
            v >= 0 -> { out.write(0xcf); writeU64(v, out) }
            v >= -128 -> { out.write(0xd0); out.write(v.toInt()) }
            v >= Short.MIN_VALUE -> { out.write(0xd1); writeU16(v.toInt(), out) }
            v >= Int.MIN_VALUE -> { out.write(0xd2); writeU32(v, out) }
            else -> { out.write(0xd3); writeU64(v, out) }
        }
    }

    private fun packSize(n: Int, out: ByteArrayOutputStream, fix: Int, wide: Int) {
        when {
            n < 16 -> out.write(fix or n)
            n <= 0xFFFF -> { out.write(wide); writeU16(n, out) }
            else -> { out.write(if (wide == 0xde) 0xdf else 0xdd); writeU32(n.toLong(), out) }
        }
    }

    private fun packString(s: String, out: ByteArrayOutputStream) {
        val b = s.toByteArray(Charsets.UTF_8)
        when {
            b.size < 32 -> out.write(0xa0 or b.size)
            b.size <= 0xFF -> { out.write(0xd9); out.write(b.size) }
            b.size <= 0xFFFF -> { out.write(0xda); writeU16(b.size, out) }
            else -> { out.write(0xdb); writeU32(b.size.toLong(), out) }
        }
        out.write(b)
    }

    private fun packBin(b: ByteArray, out: ByteArrayOutputStream) {
        when {
            b.size < 256 -> { out.write(0xc4); out.write(b.size) }
            b.size <= 0xFFFF -> { out.write(0xc5); writeU16(b.size, out) }
            else -> { out.write(0xc6); writeU32(b.size.toLong(), out) }
        }
        out.write(b)
    }

    private fun writeU16(v: Int, out: ByteArrayOutputStream) {
        out.write((v ushr 8) and 0xFF); out.write(v and 0xFF)
    }

    private fun writeU32(v: Long, out: ByteArrayOutputStream) {
        writeU16(((v ushr 16) and 0xFFFF).toInt(), out)
        writeU16((v and 0xFFFF).toInt(), out)
    }

    private fun writeU64(v: Long, out: ByteArrayOutputStream) {
        writeU32((v ushr 32) and 0xFFFFFFFFL, out)
        writeU32(v and 0xFFFFFFFFL, out)
    }

    // ----------------------------------------------------------- unpack
    /** Minimal MessagePack reader; returns Map / List / String / Long / Boolean / null / ByteArray. */
    fun unpack(body: ByteArray): Any? {
        val r = Reader(body)
        return r.readAll()
    }

    private class Reader(val b: ByteArray) {
        var p = 0
        fun u8(): Int = b[p++].toInt() and 0xFF
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long = ((u16().toLong()) shl 16) or u16().toLong()
        fun u64(): Long = (u32() shl 32) or u32()
        fun take(n: Int): ByteArray {
            val out = b.copyOfRange(p, p + n)
            p += n
            return out
        }

        fun readAll(): Any? = readValue()

        fun readValue(): Any? {
            val t = u8()
            return when {
                t == 0xc0 -> null
                t == 0xc2 -> false
                t == 0xc3 -> true
                t in 0x00..0x7f -> t.toLong()
                t in 0xe0..0xff -> (t - 0x100).toLong()
                t == 0xcc -> u8().toLong()
                t == 0xcd -> u16().toLong()
                t == 0xce -> u32()
                t == 0xcf -> u64()
                t == 0xd0 -> u8().let { if (it >= 0x80) it - 0x100 else it }.toLong()
                t == 0xd1 -> u16().let { if (it >= 0x8000) it - 0x10000 else it }.toLong()
                t == 0xd2 -> u32().let { if (it > 0x7FFFFFFFL) it - 0x100000000L else it }
                t == 0xd3 -> u64()
                t in 0xa0..0xbf -> readStr(t and 0x1f)
                t == 0xd9 -> readStr(u8())
                t == 0xda -> readStr(u16())
                t == 0xdb -> readStr(u32().toInt())
                t == 0xc4 -> take(u8())
                t == 0xc5 -> take(u16())
                t == 0xc6 -> take(u32().toInt())
                t in 0x80..0x8f -> readMap(t and 0x0f)
                t == 0xde -> readMap(u16())
                t == 0xdf -> readMap(u32().toInt())
                t in 0x90..0x9f -> readArray(t and 0x0f)
                t == 0xdc -> readArray(u16())
                t == 0xdd -> readArray(u32().toInt())
                t == 0xd4 -> { take(1); null }  // fixext: skip
                t == 0xd5 -> { take(2); null }
                t == 0xd6 -> { take(4); null }
                t == 0xd7 -> { take(8); null }
                t == 0xd8 -> { take(16); null }
                t == 0xc7 -> { take(u8()); null }
                t == 0xc8 -> { take(u16()); null }
                t == 0xc9 -> { take(u32().toInt()); null }
                t == 0xca -> { take(4); null }  // float
                t == 0xcb -> { take(8); null }  // double
                else -> throw IllegalStateException("gate: unknown msgpack byte 0x%02x @%d".format(t, p - 1))
            }
        }

        fun readStr(n: Int): String = String(take(n), Charsets.UTF_8)
        fun readMap(n: Int): LinkedHashMap<String, Any?> {
            val m = LinkedHashMap<String, Any?>(n.coerceAtLeast(1))
            repeat(n) {
                val k = readValue()
                val v = readValue()
                m[k.toString()] = v
            }
            return m
        }
        fun readArray(n: Int): ArrayList<Any?> {
            val a = ArrayList<Any?>(n.coerceAtLeast(1))
            repeat(n) { a.add(readValue()) }
            return a
        }
    }

    // -------------------------------------------------------------- body
    fun encode(msg: Any?): ByteArray {
        val packed = pack(msg)
        val def = Deflater(9)
        def.setInput(packed)
        def.finish()
        val raw = ByteArrayOutputStream(packed.size)
        val buf = ByteArray(8192)
        while (!def.finished()) {
            val n = def.deflate(buf)
            if (n <= 0) break
            raw.write(buf, 0, n)
        }
        def.end()
        val data = raw.toByteArray()
        val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY_B, "AES"), IvParameterSpec(iv))
        return iv + cipher.doFinal(data)
    }

    fun decode(body: ByteArray): Any? {
        require(body.size > 16 + 16) { "gate: body too short" }
        val iv = body.copyOfRange(0, 16)
        val ct = body.copyOfRange(16, body.size)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(KEY_B, "AES"), IvParameterSpec(iv))
        val raw = cipher.doFinal(ct)
        val plain: ByteArray = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
        } else {
            val inf = Inflater()
            inf.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 4)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            inf.end()
            out.toByteArray()
        }
        return unpack(plain)
    }

    // -------------------------------------------------------------- sign
    fun makeSign(body: ByteArray): String {
        val clock = java.security.SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        val rand = java.security.SecureRandom().nextInt().toLong() and 0xFFFFFFFFL
        return makeSign(body, clock, rand)
    }

    fun makeSign(body: ByteArray, clock: Long, rand: Long): String {
        val mixed = (rand xor clock) and 0xFFFFFFFFL
        val key = (SIGN_SECRET + "|" + mixed).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        val digest = mac.doFinal(body)
        val tail = ByteArray(8)
        for (i in 0..3) tail[i] = ((clock ushr (8 * i)) and 0xFF).toByte()
        for (i in 0..3) tail[4 + i] = ((rand ushr (8 * i)) and 0xFF).toByte()
        return Base64.encodeToString(digest + tail, Base64.NO_WRAP)
    }

    fun verifySign(body: ByteArray, sign: String): Boolean {
        val raw = try { Base64.decode(sign, Base64.NO_WRAP) } catch (_: Throwable) { return false }
        if (raw.size != 40) return false
        var clock = 0L
        var rand = 0L
        for (i in 0..3) clock = clock or ((raw[32 + i].toLong() and 0xFF) shl (8 * i))
        for (i in 0..3) rand = rand or ((raw[36 + i].toLong() and 0xFF) shl (8 * i))
        val expected = makeSign(body, clock, rand)
        return java.security.MessageDigest.isEqual(expected.toByteArray(), sign.toByteArray())
    }

    /**
     * Offline self-test, mirroring gate_client.py's `__main__` block: encode ->
     * decode roundtrip, sign roundtrip, and the s_keyword regression. No
     * network, no credentials -- runnable from a unit test or debug screen.
     */
    fun selftest(): String {
        val probe = linkedMapOf<Any?, Any?>(
            "msgid" to "CMD_GET_SERVER_ENV", "rqid" to 0, "lang" to "en",
            "n" to 12345, "neg" to -7, "list" to listOf("a", 1), "ok" to true)
        val b = encode(probe)
        val back = unpack(b)
        require(back is Map<*, *> && back["msgid"] == "CMD_GET_SERVER_ENV" && back["rqid"] == 0L &&
            back["n"] == 12345L && back["neg"] == (-7).toLong() && back["ok"] == true) { "roundtrip: $back" }
        val s = makeSign(b)
        require(verifySign(b, s)) { "sign roundtrip" }
        val sk = sKeyword("6.0.1", "11.0.1").toByteArray(Charsets.ISO_8859_1)
        val want = byteArrayOf(0x5E, 0x69, 0x53, 0x65, 0x62, 0x51, 0x18, 0x5B)
        require(sk.contentEquals(want)) { "s_keyword %s".format(sk.joinToString("") { "%02x".format(it) }) }
        return "selftest OK: msgpack+cipher roundtrip, sign roundtrip, s_keyword 11.0.1 regression"
    }
}
