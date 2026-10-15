package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import kotlin.random.Random

/**
 * Mints the bot's Konami account on the phone -- the Kotlin port of the
 * bundle's mint.py, replaying the committed CREATE_USER capture body:
 *
 *   attempt 1: fresh user_name (PLinkBot####), captured device id
 *   attempt 2: fresh user_name + fresh device_identifier (64 hex)
 *   -> on NOERR + auth_code: identity built (hash from the capture body,
 *      user_code + first session from the reply)
 *   -> then VERIFIED with the proven captured login before returning.
 *
 * This object saves nothing; BotRoomService stores the identity in
 * app-private prefs only after [mint] returns non-null.
 */
object BotMinter {

    private fun log(line: String) = AppState.appendLog("[BOT-MINT ] $line")

    /** Returns a verified fresh identity, or null (reason logged). */
    fun mint(): BotIdentity? {
        val base = GateCaptures.decodeBody(GateCaptures.CREATE_USER_BODY_HEX)
        if (base.isEmpty()) {
            log("capture body failed to decode -- stopping")
            return null
        }
        val gate = KgsGate(BotIdentity(userId = 0L, authCode = ""), BotMode.declaredProfile())

        var r = attempt(gate, base, freshName(), null)
        if (!fresh(r)) {
            log("first attempt deduped/refused -- retrying with a fresh device id")
            r = attempt(gate, base, freshName(), freshDevice())
        }
        if (!fresh(r)) {
            log("gate refused account creation (result=${r["result"]}, errcode=${r["errcode"] ?: ""})")
            return null
        }

        val uid = ((r["pes_user_info"] as? Map<*, *>)?.get("user_id") as? Number)?.toLong() ?: 0L
        val auth = (r["auth_code"] as? String).orEmpty()
        val entryInfo = r["entry_info"] as? Map<*, *> ?: emptyMap<String, Any?>()
        val candidate = BotIdentity(
            userId = uid,
            authCode = auth,
            hash = (base["hash"] as? String).orEmpty(),
            userCode = (r["user_code"] as? String).orEmpty(),
            firstSessionId = (r["session_id"] as? String).orEmpty(),
            entryInfoJson = if (entryInfo.isEmpty()) ""
            else BotSquad.mapToJson(entryInfo),
        )

        // Verify with the PROVEN login (captured body + patched credentials)
        // before anything is saved anywhere.
        val checkGate = KgsGate(candidate, BotMode.declaredProfile())
        val check = try {
            checkGate.login()
        } catch (t: Throwable) {
            log("minted account failed login verify: ${t.message}")
            return null
        }
        val ok = check["result"] == "NOERR" &&
            (check["session_id"] as? String).orEmpty().isNotBlank()
        if (!ok) {
            log("minted account failed login verify (${check["result"]})")
            return null
        }

        // T5 live proof (2026-10-15): a minted account has NO squad and its
        // ready is refused ERR_EMPTY/PNPS- until CMD_SET_MYCLUB_ENTRY_INFO
        // pushes the roster from THIS mint response. Do it now, on this
        // live session, exactly like the real client bootstraps.
        if (entryInfo.isNotEmpty()) {
            val setRes = BotSquad.setFromEntryInfo(checkGate, entryInfo)
            val sqOk = setRes == "NOERR" && BotSquad.check(checkGate) == "NOERR"
            log("squad bootstrap -> ${if (sqOk) "OK (23-player roster on file)" else "FAILED ($setRes)"}")
            if (!sqOk) return null
        } else {
            log("mint response carried no entry_info -- squad not bootstrapped")
            return null
        }
        log("bot account minted and verified: uid=$uid")
        return candidate
    }

    private fun attempt(
        gate: KgsGate,
        base: LinkedHashMap<String, Any?>,
        name: String,
        deviceId: String?,
    ): LinkedHashMap<String, Any?> {
        val m = LinkedHashMap<String, Any?>(base)
        val pui = LinkedHashMap<String, Any?>()
        (m["pes_user_info"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String) pui[k] = v }
        pui["user_name"] = name
        m["pes_user_info"] = pui
        if (deviceId != null) {
            val dev = LinkedHashMap<String, Any?>()
            (m["device"] as? Map<*, *>)?.forEach { (k, v) -> if (k is String) dev[k] = v }
            dev["device_identifier"] = deviceId
            m["device"] = dev
        }
        m["rqid"] = gate.nextRqid()
        m["client_version"] = GateCodec.CLIENT_VERSION
        m["s_keyword"] = GateCodec.sKeyword()
        val r = gate.sendRaw(m)
        log("create[$name] -> ${r["result"]} ${r["errcode"] ?: ""}")
        return r
    }

    private fun fresh(r: Map<*, *>): Boolean {
        val auth = (r["auth_code"] as? String).orEmpty()
        val uid = ((r["pes_user_info"] as? Map<*, *>)?.get("user_id") as? Number)?.toLong() ?: 0L
        return r["result"] == "NOERR" && auth.isNotBlank() && uid > 0L
    }

    private fun freshName(): String = "PLinkBot%04d".format(Random.nextInt(0, 10000))

    private fun freshDevice(): String =
        (1..64).map { "0123456789abcdef"[Random.nextInt(0, 16)] }.joinToString("")
}
