package com.peerlink.app.bot

import com.peerlink.app.core.AppState

/**
 * The engine, loaded in this app, and its own entry points.
 *
 * The engine (libUE4.so) exports almost nothing, so the way in is the load
 * base plus a fixed offset.  Every offset below was read out of the
 * engine's own code -- build B, sha256 2cea0c85...330659 -- and the
 * reasoning is in docs/ENGINE_DRIVEN_P2P.md.
 *
 * Nothing here re-implements the engine: its own code runs, in this process,
 * with this app's sockets and this app's tunnel.  That is what makes the
 * exchange the engine's own: the punch, the UDP packets and the protocol
 * all come from the engine's code, and the bot's fabricated answers are
 * not needed at all.
 */
object EngineHost {

    init {
        // The native half of this object lives in libpeerlinkbackend.so.  At
        // VPN session start the engine was asked for before that library was
        // in, and the first call died with UnsatisfiedLinkError ("No
        // implementation found ... is the library loaded?").  Loading it here
        // means whoever asks first still gets through; System.loadLibrary is
        // a no-op once the library is already in, so this costs nothing.
        try {
            System.loadLibrary("peerlinkbackend")
        } catch (t: Throwable) {
            AppState.appendLog("[ENGINE-HOST] the JNI library did not load: $t")
        }
    }

    /** The engine's load base, 0 when it is not loaded. */
    @Volatile
    var base: Long = 0L
        private set

    /** The client the engine's own start produced (0 before the start). */
    @Volatile
    var client: Long = 0L
        private set

    // ---- the engine's own entry points, by offset from the load base ----

    /** The engine's own start: it creates the client and its threads. */
    const val START = 0x7db9294L

    /** The client the start produced. */
    const val CLIENT_GET = 0x7e02524L

    /** The client's own first setter (the lab passes the client, 1, 0, 0). */
    const val CLIENT_SET = 0x7e02878L

    /** The client that owns the P2P session (FUN_07eb9730's dispatch). */
    const val SET_MY_ID = 0x7db9730L

    /** The peer's record: user id, address blocks, NAT words (0x218 bytes). */
    const val SET_PEERS = 0x7db96b4L

    /** The client's own step: the punch and the connect, once per tick. */
    const val CLIENT_STEP = 0x7e016ecL

    /** The client's own check: the verdict on the P2P exchange. */
    const val CLIENT_CHECK = 0x7e013acL

    /**
     * The client's own per-frame update (FUN_07efe9e4).  The real game calls
     * this every frame while the connection phase runs: it marks the peer's
     * candidate addresses per record and walks the client's own state.  The
     * lab showed the readiness chain hangs off exactly this call -- without
     * it the engine's promoted connection never becomes answer-ready.
     */
    const val CLIENT_POLL = 0x7dfe9e4L

    /** The engine's own punch (FUN_07eb9b40's dispatch). */
    const val PUNCH = 0x7db9b40L

    /**
     * The engine's own app-language setter.  Every lab run hands the engine
     * the app's language (1 = US) through it before the start; the engine's
     * startup reads it and builds its own blocks from it.
     */
    const val APP_LANGUAGE = 0x822ee30L

    /** One address block, as the engine's own builder writes it. */
    const val BLOCK_SIZE = 0x80

    /** The peer record, as the engine's SetPeers reads it. */
    const val RECORD_SIZE = 0x218

    // ---- loading ---------------------------------------------------------

    /**
     * Load the engine from [path] (the staged copy the bot owns) and keep
     * its load base.  Idempotent.  The engine's ~11,900 registration
     * functions run here, so this belongs to a moment when the phone is
     * idle -- not in the middle of a match.
     */
    fun load(path: String): Boolean {
        if (base != 0L) return true
        val b = try {
            nativeLoad(path)
        } catch (t: Throwable) {
            AppState.appendLog("[ENGINE-HOST] load failed: $t")
            return false
        }
        if (b == 0L) {
            AppState.appendLog("[ENGINE-HOST] engine did not load")
            return false
        }
        base = b
        AppState.appendLog("[ENGINE-HOST] engine loaded at 0x$b")
        return true
    }

    /**
     * Call one of the engine's own functions: base + [offset], with up to
     * four integer arguments.
     */
    fun call(
        offset: Long,
        a0: Long = 0L,
        a1: Long = 0L,
        a2: Long = 0L,
        a3: Long = 0L,
    ): Long {
        if (base == 0L) return 0L
        return try {
            nativeCall(base, offset, a0, a1, a2, a3)
        } catch (t: Throwable) {
            AppState.appendLog("[ENGINE-HOST] call 0x$offset failed: $t")
            0L
        }
    }

    /**
     * The engine's own start, in the order the lab proved works: the start
     * call, the client getter, the client's own first setter.  The engine
     * creates its client and its own threads here; from this point on the
     * engine drives the exchange by itself.
     */
    fun start(): Long {
        if (base == 0L) return 0L
        AppState.appendLog("[ENGINE-HOST] calling the engine's own start")
        // The lab's order, every run: the app's language, then the start.
        call(APP_LANGUAGE, 1L, 0L, 0L, 0L)
        call(START, 0L, 0L, 0L)
        val client = call(CLIENT_GET, 0L, 0L, 0L)
        if (client == 0L) {
            AppState.appendLog("[ENGINE-HOST] the engine made no client")
            return 0L
        }
        call(CLIENT_SET, client, 1L, 0L, 0L)
        this.client = client
        AppState.appendLog("[ENGINE-HOST] started, client=0x$client")
        return client
    }

    /**
     * Tell the engine which player it is.  The engine turns this into the
     * 16-byte text it stamps on every frame it sends -- the same value the
     * phone's own capture carries.
     */
    fun setMyId(id: Long): Boolean {
        if (base == 0L) return false
        val r = call(SET_MY_ID, 0L, id, 0L)
        AppState.appendLog("[ENGINE-HOST] set my id $id -> $r")
        return r != 0L
    }

    /**
     * Hand the engine the peer's record.
     *
     * The record is 0x218 bytes and is the server's own data in the
     * engine's own layout:
     *
     *   +0x000 peer id (uint32)
     *   +0x004 peer IPv4 host block      (0x80)
     *   +0x084 peer IPv4 reflexive block  (0x80)
     *   +0x104 peer IPv4 NAT word
     *   +0x108 peer IPv6 host block      (0x80)
     *   +0x188 peer IPv6 reflexive block  (0x80)
     *   +0x208 peer IPv6 NAT word
     *   +0x20c the transport's peer id (the engine writes -1 itself)
     *
     * Both NAT words must say the peer is punchable (bit 4 set, the
     * capture's own open-NAT value 0x102000b3): the engine's transport adds
     * the peer to the IPv4 channel AND the IPv6 channel, and keeps the
     * peer's id only when both succeed.
     */
    fun setPeers(record: ByteArray): Boolean {
        if (base == 0L) return false
        if (record.size != 0x218) {
            AppState.appendLog("[ENGINE-HOST] peer record is " +
                "${record.size} bytes, expected 0x218")
            return false
        }
        val p = nativeAlloc(record.size)
        if (p == 0L) return false
        try {
            nativeWrite(p, record)
            val r = call(SET_PEERS, 0L, p, 1L)
            AppState.appendLog("[ENGINE-HOST] set peers -> $r")
            return r != 0L
        } finally {
            nativeFree(p)
        }
    }

    /** The engine's own verdict on the exchange: 1 = connected. */
    fun check(): Long = if (client != 0L) call(CLIENT_CHECK, client, 0L, 0L) else 0L

    /**
     * The engine's own per-frame update -- the call the real game makes every
     * frame of the connection phase.  Returns its own status; harmless to
     * call while the client is still punching (the lab's runs).
     */
    fun poll(): Long = if (client != 0L) call(CLIENT_POLL, client, 0L, 0L) else 0L

    /** The engine's own punch: from here the engine drives the exchange. */
    fun punch(): Long = call(PUNCH, 0L, 0L, 0L)

    /** The engine's own step, for the times the app wants to move it on. */
    fun step(client: Long): Long = call(CLIENT_STEP, client, 0L, 0L)

    /**
     * Load the engine, start it, and tell it which player it is.
     *
     * The engine is loaded in this process, so it uses this app's sockets and
     * this app's tunnel; its own threads then carry the exchange.  The load
     * is heavy (the engine is 161 MB and registers ~11,900 functions), so it
     * belongs to a moment when the phone is idle -- before a match, not
     * during one.
     */
    @Synchronized
    fun prepare(ctx: android.content.Context, uid: Long): Boolean {
        val staged = java.io.File(ctx.filesDir, "engine/libUE4.so")
        if (!staged.exists()) {
            AppState.appendLog("[ENGINE-HOST] no staged engine at " +
                "${staged.absolutePath}")
            return false
        }
        if (!load(staged.absolutePath)) return false
        if (!nativeNoJavaActivity()) {
            // The lab installs a fake "no Java attached" activity before it
            // starts the engine; without one the engine's start walks into a
            // null activity and dies, taking this whole process with it.  A
            // build whose setter did not verify is refused here, so the app
            // stays up and the slot's own answers keep the match alive.
            AppState.appendLog("[ENGINE-HOST] the engine's no-Java activity is " +
                "not in -- the start would die on it, so it is not called")
            return false
        }
        if (start() == 0L) return false
        return setMyId(uid)
    }

    /**
     * One address block, built by the ENGINE's own builder (0x7e04330) so
     * the block in the peer record is the engine's own shape and not a copy
     * of it.  0x80 bytes.
     */
    fun buildBlock(family: Int, address: String, port: Int): ByteArray? {
        if (base == 0L) return null
        val p = nativeAlloc(BLOCK_SIZE)
        if (p == 0L) return null
        try {
            val r = nativeBuildBlock(base, p, family, address, port)
            if (r == 0L) return null
            val out = ByteArray(BLOCK_SIZE)
            nativeRead(p, out)
            return out
        } finally {
            nativeFree(p)
        }
    }

    /**
     * The peer's record, in the engine's own layout, ready for SetPeers.
     *
     * The four address blocks are built by the engine's own builder; the
     * two NAT words are the server's own, with bit 4 set so the engine
     * treats the peer as punchable on both families (the capture's own
     * open-NAT value is 0x102000b3).
     */
    fun buildPeerRecord(
        peerId: Long,
        v4Host: String,
        v4HostPort: Int,
        v4Reflexive: String,
        v4ReflexivePort: Int,
        v4NatType: Long,
        v6Host: String,
        v6HostPort: Int,
        v6Reflexive: String,
        v6ReflexivePort: Int,
        v6NatType: Long,
    ): ByteArray? {
        if (base == 0L) return null
        val rec = ByteArray(RECORD_SIZE)
        // The layout is the engine's own, measured in the lab: eight bytes of
        // header, then the peer's id, then the four address blocks, then the
        // two NAT words, then the transport's id (which the engine writes
        // itself).
        //
        //   +0x000 header (8 bytes)
        //   +0x008 peer id (uint32)
        //   +0x00c peer IPv4 host block      (0x80)
        //   +0x08c peer IPv4 reflexive block  (0x80)
        //   +0x10c peer IPv4 NAT word
        //   +0x110 peer IPv6 host block      (0x80)
        //   +0x190 peer IPv6 reflexive block  (0x80)
        //   +0x210 peer IPv6 NAT word
        //   +0x214 the transport's peer id (the engine writes -1 itself)
        rec[8] = (peerId and 0xFF).toByte()
        rec[9] = ((peerId shr 8) and 0xFF).toByte()
        rec[10] = ((peerId shr 16) and 0xFF).toByte()
        rec[11] = ((peerId shr 24) and 0xFF).toByte()
        // +0x00c / +0x08c: the peer's IPv4 host and reflexive blocks
        buildBlock(1, v4Host, v4HostPort)?.let { copyInto(rec, 0x00C, it) }
        buildBlock(1, v4Reflexive, v4ReflexivePort)?.let { copyInto(rec, 0x08C, it) }
        // +0x10c: the peer's IPv4 NAT word
        putLong(rec, 0x10C, v4NatType)
        // +0x110 / +0x190: the peer's IPv6 host and reflexive blocks
        buildBlock(2, v6Host, v6HostPort)?.let { copyInto(rec, 0x110, it) }
        buildBlock(2, v6Reflexive, v6ReflexivePort)?.let { copyInto(rec, 0x190, it) }
        // +0x210: the peer's IPv6 NAT word
        putLong(rec, 0x210, v6NatType)
        // +0x214: the transport's peer id -- the engine writes -1 itself.
        // FOUR bytes: the record ends at 0x218, and an 8-byte write here
        // runs past the end of the array -- that crashed the app at the
        // hand-off before this fix.
        putInt(rec, 0x214, -1)
        return rec
    }

    private fun copyInto(dst: ByteArray, at: Int, src: ByteArray) {
        val n = minOf(src.size, dst.size - at)
        System.arraycopy(src, 0, dst, at, n)
    }

    private fun putLong(dst: ByteArray, at: Int, v: Long) {
        for (i in 0 until 8) {
            dst[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }

    private fun putInt(dst: ByteArray, at: Int, v: Int) {
        for (i in 0 until 4) {
            dst[at + i] = ((v shr (8 * i)) and 0xFF).toByte()
        }
    }

    // ---- native -----------------------------------------------------------
    private external fun nativeLoad(path: String): Long
    private external fun nativeCall(
        base: Long,
        offset: Long,
        a0: Long,
        a1: Long,
        a2: Long,
        a3: Long,
    ): Long
    private external fun nativeBase(): Long
    private external fun nativeNoJavaActivity(): Boolean
    private external fun nativeAlloc(size: Int): Long
    private external fun nativeWrite(addr: Long, bytes: ByteArray)
    private external fun nativeFree(addr: Long)
    private external fun nativeBuildBlock(
        base: Long,
        dst: Long,
        family: Int,
        address: String,
        port: Int,
    ): Long
    private external fun nativeRead(addr: Long, bytes: ByteArray)
}
