package com.peerlink.app.bot

import org.json.JSONArray
import org.json.JSONObject

/**
 * The bot account's squad bootstrap -- the Kotlin port of the T5 live proof
 * (2026-10-15, `exp_squad_ready.py`): a freshly minted account has NO squad
 * (`CMD_GET_USER_SQUAD` -> `ERR_NOT_ENOUGH_PLAYER_IN_SQUAD`) and the gate
 * refuses its `CMD_SET_ROOM_MATCH_READY` with `ERR_EMPTY` (errcode PNPS-)
 * no matter what endpoints or quality reports it sends. The real client
 * bootstraps the account with `CMD_SET_MYCLUB_ENTRY_INFO` from the roster
 * in its own CMD_CREATE_USER response; after that the ready goes NOERR and
 * the room flips (proven end-to-end on the live gate).
 *
 * Body shape (build_body of squad_fix.py, live-proven): the captured
 * template's `base_team`/`check_status`/`squad_data`/`squad_name` are kept,
 * then base_team comes from the account's `recommend_base_team_id`,
 * coach from `coach_list`, and the 23 players from `gameplayer_list`
 * (ids/serials are the account's own).
 */
object BotSquad {

    private const val TAG = "BOT-SQUAD "

    private fun log(line: String) = AppState.appendLog("[$TAG] $line")

    /** CMD_GET_USER_SQUAD -- the squad-existence probe the proven ladder runs. */
    fun check(gate: KgsGate): String {
        val m = gate.step("CMD_GET_USER_SQUAD",
            linkedMapOf("target_user_id" to gate.uid))
        return m["result"]?.toString() ?: "null"
    }

    /**
     * Push the account's roster from [entryInfo] (the CMD_CREATE_USER
     * response's entry_info map). Returns the gate's result string.
     */
    fun setFromEntryInfo(gate: KgsGate, entryInfo: Map<*, *>): String {
        val cap = GateCaptures.decodeBody(GateCaptures.SET_MYCLUB_ENTRY_INFO_BODY_HEX)
        if (cap.isEmpty() || cap["squad_data"] == null) {
            log("capture template failed to decode")
            return "_TEMPLATE_FAIL"
        }

        val players = ArrayList<LinkedHashMap<String, Any?>>()
        for (p in entryInfo["gameplayer_list"] as? List<*> ?: emptyList<Any?>()) {
            val row = p as? Map<*, *> ?: continue
            val gp = row["gameplayer_id"] ?: continue
            players.add(linkedMapOf("gameplayer_id" to gp,
                "uniform_number" to (row["uniform_number"] ?: 0)))
        }
        val coach = ArrayList<LinkedHashMap<String, Any?>>()
        for (c in entryInfo["coach_list"] as? List<*> ?: emptyList<Any?>()) {
            val row = c as? Map<*, *> ?: continue
            row["coach_id"]?.let { coach.add(linkedMapOf("coach_id" to it)) }
        }
        val baseTeamId = entryInfo["recommend_base_team_id"]

        val m = gate.step("CMD_SET_MYCLUB_ENTRY_INFO", linkedMapOf(
            "base_team" to linkedMapOf<String, Any?>(
                "base_team_id" to baseTeamId,
                "base_team_license" to "NOT_LICENSED",
                "team_name" to "PLink Bot",
                "team_name_short" to "PLB",
            ),
            "check_status" to (cap["check_status"] ?: linkedMapOf<String, Any?>(
                "skip_check_team_name" to "YES",
                "skip_check_team_name_short" to "YES")),
            "squad_data" to linkedMapOf<String, Any?>(
                "player_list" to players,
                "coach" to coach,
            ),
            "squad_name" to (cap["squad_name"] ?: "Game Plan"),
        ))
        val res = m["result"]?.toString() ?: "null"
        log("SET_MYCLUB_ENTRY_INFO -> $res (players=${players.size} coach=${coach.size} base=$baseTeamId)")
        return res
    }

    /**
     * Make sure [gate]'s account holds a squad. When it does not and the
     * roster JSON from its mint is available, bootstrap it; returns true
     * when a squad exists afterwards.
     */
    fun ensure(gate: KgsGate, entryInfoJson: String): Boolean {
        var r = check(gate)
        if (r == "NOERR") return true
        if (r != "ERR_NOT_ENOUGH_PLAYER_IN_SQUAD") {
            log("squad check -> $r (unexpected)")
            return false
        }
        if (entryInfoJson.isBlank()) {
            log("no stored roster for this account -- it cannot be bootstrapped")
            return false
        }
        val ei = runCatching { jsonToMap(JSONObject(entryInfoJson)) }.getOrNull()
        if (ei == null) {
            log("stored roster failed to parse")
            return false
        }
        r = setFromEntryInfo(gate, ei)
        if (r != "NOERR") return false
        r = check(gate)
        log("squad after bootstrap -> $r")
        return r == "NOERR"
    }

    // -- org.json -> plain maps ------------------------------------------------
    private fun jsonToMap(o: JSONObject): LinkedHashMap<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        for (k in o.keys()) {
            out[k] = when (val v = o.get(k)) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> jsonToList(v)
                JSONObject.NULL -> null
                else -> v
            }
        }
        return out
    }

    /** Stored form of the mint roster (parsed back by [jsonToMap]). */
    fun mapToJson(m: Map<*, *>): String = mapToJsonObject(m).toString()

    private fun mapToJsonObject(m: Map<*, *>): JSONObject {
        val out = JSONObject()
        for ((k, v) in m) {
            if (k !is String) continue
            out.put(k, wrapForJson(v))
        }
        return out
    }

    private fun listToJsonArray(l: List<*>): JSONArray {
        val out = JSONArray()
        for (v in l) out.put(wrapForJson(v))
        return out
    }

    private fun wrapForJson(v: Any?): Any? = when (v) {
        null -> JSONObject.NULL
        is Map<*, *> -> mapToJsonObject(v)
        is List<*> -> listToJsonArray(v)
        is Boolean, Int, Long, Double, String -> v
        is Number -> v.toLong().let { if (it.toDouble() == v.toDouble()) it else v.toDouble() }
        else -> v.toString()
    }

    private fun jsonToList(a: JSONArray): ArrayList<Any?> {
        val out = ArrayList<Any?>(a.length())
        for (i in 0 until a.length()) {
            out.add(when (val v = a.get(i)) {
                is JSONObject -> jsonToMap(v)
                is JSONArray -> jsonToList(v)
                JSONObject.NULL -> null
                else -> v
            })
        }
        return out
    }
}
