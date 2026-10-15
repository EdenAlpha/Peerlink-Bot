package com.peerlink.app.bot

import android.content.Context
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/**
 * Bot identity -- the second Konami account the bot logs in as.
 *
 * On a user's phone these values live ONLY in app-private storage
 * (`bot_identity` prefs file). They must never be hardcoded or committed: the
 * repo ships the protocol, the phone supplies the account.
 */
data class BotIdentity(
    val userId: Long,
    val authCode: String,
    val hash: String = "",
    val lang: String = "US",
    val region: String = "REGION_US",
    val platform: String = "ANDROID",
    val myPlatform: String = "ANDROID",
    // Minted at account creation (CMD_CREATE_USER), kept local-only like the
    // bundle does: user_code for display, the first session_id, and the
    // full entry_info roster -- the only place a new account's squad is
    // ever visible; SET_MYCLUB_ENTRY_INFO needs it later.
    val userCode: String = "",
    val firstSessionId: String = "",
    val entryInfoJson: String = "",
) {
    val isComplete: Boolean get() = userId != 0L && authCode.isNotBlank()

    companion object {
        private const val FILE = "bot_identity"

        fun load(ctx: Context): BotIdentity? {
            val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val uid = p.getLong("user_id", 0L)
            val code = p.getString("auth_code", "").orEmpty()
            if (uid == 0L || code.isBlank()) return null
            return BotIdentity(
                userId = uid,
                authCode = code,
                hash = p.getString("hash", "").orEmpty(),
                lang = p.getString("lang", "US") ?: "US",
                region = p.getString("region", "REGION_US") ?: "REGION_US",
                platform = p.getString("platform", "ANDROID") ?: "ANDROID",
                myPlatform = p.getString("my_platform", "ANDROID") ?: "ANDROID",
                userCode = p.getString("user_code", "") ?: "",
                firstSessionId = p.getString("first_session_id", "") ?: "",
                entryInfoJson = p.getString("entry_info_json", "") ?: "",
            )
        }

        /** Device-private write path; also the only place credentials touch disk. */
        fun save(ctx: Context, id: BotIdentity) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putLong("user_id", id.userId)
                .putString("auth_code", id.authCode)
                .putString("hash", id.hash)
                .putString("lang", id.lang)
                .putString("region", id.region)
                .putString("platform", id.platform)
                .putString("my_platform", id.myPlatform)
                .putString("user_code", id.userCode)
                .putString("first_session_id", id.firstSessionId)
                .putString("entry_info_json", id.entryInfoJson)
                .apply()
        }

        fun clear(ctx: Context) {
            ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}

/**
 * One live gate session -- the Kotlin `Gate` of room1.py.
 *
 * The gate caches responses on rqid, so rqid starts at a random per-process
 * base and increments on every request, and pacing (~0.4 s) avoids
 * ERR_CONGESTION. Login/acquire:
 *
 *   login (bot identity) -> session_id
 *   CMD_CREATEJOIN_ROOM  -> room_id  (the number the user types into eFootball)
 */
class KgsGate(
    private val identity: BotIdentity,
    profile: NatProfile = NatProfile.undeclared(),
) {

    var sid: String = ""
        private set
    var uid: Long = 0L
        private set
    private var rqid: Long = 1_500_000L + Random.nextInt(0, 4_000_000)

    private fun nextRq(): Long = ++rqid

    private fun envelope(msgid: String): LinkedHashMap<String, Any?> = linkedMapOf(
        "msgid" to msgid,
        "rqid" to nextRq(),
        "user_id" to uid,
        "session_id" to sid,
        "my_platform" to identity.myPlatform,
        "s_keyword" to GateCodec.sKeyword(),
        "lang" to identity.lang,
        "region" to identity.region,
        "platform" to identity.platform,
        "client_version" to GateCodec.CLIENT_VERSION,
    )

    /** Public ladder step: envelope for [msgid] merged with [extra], sent. */
    fun step(msgid: String, extra: LinkedHashMap<String, Any?> = linkedMapOf()): LinkedHashMap<String, Any?> {
        val m = envelope(msgid)
        m.putAll(extra)
        return send(m)
    }

    /** POST one gate request and decode the response; never throws on server error shapes. */
    private fun send(msg: LinkedHashMap<String, Any?>, timeoutMs: Int = 25_000): LinkedHashMap<String, Any?> {        val body = GateCodec.encode(msg)
        val sign = GateCodec.makeSign(body)
        val url = URL("https://${GateCodec.HOST}/pes22/gate/gate_${msg["msgid"]}.php")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Accept-Language", "jp")
            setRequestProperty("User-Agent", GateCodec.userAgent())
            setRequestProperty("pes-custom-encrypt", "AES256")
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("Content-Encoding", "gzip")
            setRequestProperty("Cookie", "sign=$sign;")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("Connection", "close")
        }
        val status: String
        val data: ByteArray
        try {
            conn.outputStream.use { it.write(body) }
            status = try { conn.responseMessage ?: "${conn.responseCode}" } catch (_: Throwable) { "?" }
            data = try { conn.inputStream.use { it.readBytes() } } catch (_: Throwable) { ByteArray(0) }
        } catch (t: Throwable) {
            return linkedMapOf("result" to "_NET_FAIL", "_err" to (t.message ?: t.javaClass.simpleName))
        } finally {
            conn.disconnect()
        }
        if (data.isEmpty()) return linkedMapOf("result" to "_HTTP_EMPTY", "_status" to status)
        return try {
            val out = GateCodec.decode(data)
            @Suppress("UNCHECKED_CAST")
            (out as? LinkedHashMap<String, Any?>) ?: linkedMapOf("result" to "_DECODE_FAIL", "_status" to status)
        } catch (t: Throwable) {
            linkedMapOf("result" to "_DECODE_FAIL", "_err" to (t.message ?: "?"), "_status" to status, "_len" to data.size)
        }
    }

    /**
     * Shallow diagnostics without leaking identity values -- same discipline as
     * room1.describe(): only result/error surfaces, never request bodies.
     */
    private fun describe(o: Map<*, *>): String {
        val keep = linkedMapOf<String, Any?>()
        for (k in listOf("result", "msgid", "msg", "error_code", "error", "errcode",
                "show_info", "maintenance_end_time", "_status", "_err", "_len", "_rqid_echo")) {
            if (o.containsKey(k)) keep[k] = o[k]
        }
        return if (keep.isEmpty()) "NOERR" else keep.toString()
    }

    /** CMD_LOGIN as the bot account; returns the gate response. */
    fun login(): LinkedHashMap<String, Any?> {
        val m = envelope("CMD_LOGIN")
        m["user_id"] = identity.userId
        m["auth_code"] = identity.authCode
        m["session_id"] = ""
        if (identity.hash.isNotBlank()) m["hash"] = identity.hash
        val r = send(m)
        val result = r["result"]
        val session = r["session_id"]
        if (result == "NOERR" && session is String && session.isNotBlank()) {
            sid = session
            val pui = r["pes_user_info"]
            uid = identity.userId
            if (pui is Map<*, *>) {
                (pui["user_id"] as? Long)?.let { uid = it }
            }
            return r
        }
        throw GateException("login failed: ${describe(r)}")
    }

    /** CMD_SET_GAMERELAY_QUALITY; entries come from real on-device data when available. */
    fun setRelayQuality(qualityList: List<Map<String, Any?>>, lastUpdate: Long): LinkedHashMap<String, Any?> {
        val m = envelope("CMD_SET_GAMERELAY_QUALITY")
        m["quality_list"] = qualityList.map { LinkedHashMap(it).apply { put("last_update", lastUpdate) } }
        return send(m)
    }

    /**
     * The NAT block declared to the gate -- the exact shape the proven runs
     * established. Runs #26 vs #27-#29 (POSTROOM_FLOW §): nat_type=1 with a
     * port-preserving reflexive got `game_sycom:"SC"` + a real relay; the
     * capture's believable shape (reflexive port TRANSLATED to a distinct
     * port, nat_type 270532787) got `game_sycom:"P2P"` + no relay. The gate
     * reads what each side declares here and labels the match, so the bot
     * must declare the P2P shape -- it never runs any STUN of its own.
     *
     * [hostAddress]/[hostPort] are the bot's local binding (the punch stack
     * binds these); [reflexiveAddress]/[reflexivePort] are the device's real
     * public mapping as the gate/STUN sees it (measured at runtime, never
     * invented; on a phone these come from the existing connectivity probe).
     */
    data class NatProfile(
        val hostAddress: String,
        val hostPort: Int,
        val reflexiveAddress: String,
        val reflexivePort: Int,
        val natType: Long,
    ) {
        companion object {
            /** Capture-proven device shapes; nat variants observed on real clients. */
            const val NAT_P2P_A = 270532787L   // capture client A (0x102000B3)
            const val NAT_P2P_B = 268435711L   // capture client B (0x0FFFFFFF)

            /** Placeholder until the runtime probe fills the device's real mapping. */
            fun undeclared() = NatProfile("", 1, "", 1, NAT_P2P_A)

            /**
             * The rule from the record: advertise the reflexive that STUN
             * actually measured from the SAME socket that keeps beating
             * (T7 MAPPED-ADDRESS). Run #30 advertised a stale mapping while
             * beats left from the current egress -> uncorrelated -> DISCONN_HB.
             */
            fun measured(hostAddress: String, localPort: Int, mapped: InetSocketAddress): NatProfile =
                NatProfile(
                    hostAddress = hostAddress,
                    hostPort = localPort,
                    reflexiveAddress = mapped.address?.hostAddress ?: "",
                    reflexivePort = mapped.port,
                    natType = NAT_P2P_A,
                )
        }
    }

    private val nat: NatProfile = profile

    /**
     * CMD_CREATEJOIN_ROOM -- the bot hosts a 1v1 room. Body mirrors room1.create_room
     * verbatim, with the NAT block in the capture-proven P2P shape (runs #26/#27-#29:
     * nat_type 270532787 + translated reflexive port -> sycom P2P, no relay; nat_type 1
     * -> SC + relay). Returns the gate response (room_id inside on NOERR).
     */
    fun createRoom(): LinkedHashMap<String, Any?> {
        val m = envelope("CMD_CREATEJOIN_ROOM")
        m["core_settings"] = linkedMapOf<String, Any?>(
            "mode" to "STRIKE_ARENA", "event_id" to 0, "kind" to "STRIKE_ARENA",
            "name_tag" to 0, "entry_restriction" to "NONE", "password" to "",
            "lang" to identity.lang, "capacity" to 2, "observer_capacity" to 0,
            "guest_num_by_user" to 0, "is_coop_vs_com_single" to "NO",
        )
        m["match_settings"] = linkedMapOf(
            "match_env" to linkedMapOf<String, Any?>(
                "condition_home" to "CONDITION_RANDOM", "condition_away" to "CONDITION_RANDOM",
                "cpu_level" to "EASY", "match_time" to 1, "injury" to "NO", "ball_type" to 1,
                "exTime" to "0", "pk" to "NO", "substitution_times" to 1, "substitution" to 1,
                "exSubstitution" to "", "limitTime" to "NOSET", "equalization" to "NO",
                "regulation" to "", "can_use_mobile_controller" to "NO",
            )
        )
        m["event_account_type"] = "GAME_PLAYER"
        m["address_ipv4"] = linkedMapOf<String, Any?>(
            "host_address" to nat.hostAddress, "host_port" to nat.hostPort,
            "reflexive_address" to nat.reflexiveAddress, "reflexive_port" to nat.reflexivePort,
            "nat_type" to nat.natType,
        )
        m["address_ipv6"] = linkedMapOf<String, Any?>(
            "host_address" to "::", "host_port" to 0,
            "reflexive_address" to "::", "reflexive_port" to 0,
            "nat_type" to 0,
        )
        m["platform_session_id_info"] = linkedMapOf("ps_session_id" to "", "xb_session_id" to "")
        m["strike_arena_selected_info"] = listOf(linkedMapOf("player_id" to 1, "costume_id" to 0))
        return send(m)
    }

    /** Pull the room number out of a NOERR CMD_CREATEJOIN_ROOM response. */
    fun roomNumberOf(o: Map<*, *>): String {
        val rid = o["room_id"]
        return when (rid) {
            is Map<*, *> -> (rid["id"] ?: "").toString()
            else -> rid?.toString().orEmpty()
        }
    }

    class GateException(message: String) : Exception(message)
}
