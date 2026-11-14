package com.peerlink.app.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Current account squad -> the same validated owned input format used by the native lab. */
internal object SquadInput {
    fun encode(response: Map<*, *>, account: Long): ByteArray {
        require(account in 1..0xffffffffL)
        val squad = response["squad_data"].map("squad_data")
        val selected = squad["player_list"].list("selected players")
        require(selected.size in 11..40) { "Bot squad must contain 11–40 players" }
        fun identity(row: Any?, field: String): Pair<Long, Long> {
            val id = row.map(field)[field].map(field)
            val number = id["id"].number()
            val serial = id["serial"].number()
            require(number in 1..0xffffffffL && serial > 0) { "Invalid squad identity" }
            return number to serial
        }
        val roster = response["player_list"].list("player roster").associateBy { identity(it, "gameplayer_id") }
        val keys = selected.map { identity(it, "gameplayer_id") }
        require(keys.distinct().size == keys.size && keys.all { it in roster }) { "Selected players are missing or duplicated" }
        val plan = squad["squad_gameplan"].map("game plan")
        val settings = plan["match_plan_settings"].map("match settings")
        val order = settings["order"].list("lineup order").map { it.number().toInt() }
        require(order.size == 40 && order.all { it == 255 || it in keys.indices } &&
            order.take(11).all { it != 255 } && order.filter { it != 255 }.distinct().size == order.count { it != 255 }) {
            "Invalid starting lineup"
        }
        val coordinates = ArrayList<Int>(132)
        for (variant in plan["formation"].list("formations").also { require(it.size == 2) }) {
            val formation = variant.map("formation")
            require(formation["team_style"].number() == 0L && formation["use_multi_formation"] == "NO") {
                "Bot control test currently requires the default squad tactics"
            }
            for (phase in listOf("formation_offensive", "formation_defensive")) {
                for (position in formation[phase].list(phase).also { require(it.size == 11) }) {
                    val row = position.map("position")
                    val role = row["role"].number().toInt()
                    val x = row["position_x"].number().toInt()
                    val y = row["position_y"].number().toInt()
                    require(role in 0..12 && x in 0..255 && y in 0..255)
                    coordinates.addAll(listOf(role, x, y))
                }
            }
        }
        val coach = identity(squad["coach"].list("coach").first(), "coach_id")
        require(response["coach_list"].list("coach roster").any { identity(it, "coach_id") == coach })
        val team = response["team_data"].map("team")
        val teamId = team["base_team_id"].number()
        require(teamId in 1..0x3ffffL) { "Invalid bot base team" }
        val name = (team["team_name"] as? String ?: error("Missing team name")).toByteArray()
        val shortName = (team["team_name_short"] as? String ?: error("Missing team abbreviation")).toByteArray()
        require(name.size < 70 && shortName.size < 4 && 0.toByte() !in name && 0.toByte() !in shortName)
        val selections = listOf(settings["captain"]) + settings["set_piece_taker"].list("set pieces") +
            settings["df_overlap_setplay"].list("overlaps")
        require(selections.size == 10)
        val extras = listOf("auto_member_change", "auto_offside_trap", "auto_plan_change",
            "use_multi_tactics_plan", "auto_attack_level", "member_assisit").map { settings[it].number() }
        require(extras.all { it == 0L }) { "Bot control test currently requires the default squad plan" }
        val output = ByteBuffer.allocate(864 + selected.size * 608).order(ByteOrder.LITTLE_ENDIAN)
        output.putInt(0x31515350).putInt(1).putInt(account.toInt()).putInt(teamId.toInt())
            .putInt(keys.size).putInt(coach.first.toInt()).putLong(coach.second)
        output.put(name).put(ByteArray(72 - name.size)).put(shortName).put(ByteArray(8 - shortName.size))
        order.forEach { output.putInt(it) }; coordinates.forEach { output.putInt(it) }
        selections.forEach {
            val slot = it.number().toInt(); require(slot == 255 || slot in keys.indices); output.putInt(slot)
        }
        extras.forEach { output.putInt(it.toInt()) }
        check(output.position() == 864)
        for ((index, key) in keys.withIndex()) {
            val uniform = selected[index].map("player")["uniform_number"].number()
            require(uniform in 0..65535)
            val parameters = roster[key].map("roster player")["parameters"].list("player parameters")
            require(parameters.size == 147)
            output.putInt(key.first.toInt()).putInt(uniform.toInt()).putLong(key.second)
            parameters.forEach { value -> val n = value.number(); require(n in 0..65535); output.putInt(n.toInt()) }
            output.putInt(0)
        }
        check(!output.hasRemaining())
        return output.array()
    }
    private fun Any?.map(field: String): Map<*, *> = this as? Map<*, *> ?: error("Missing bot $field")
    private fun Any?.list(field: String): List<*> = this as? List<*> ?: error("Missing bot $field")
    private fun Any?.number(): Long = (this as? Number)?.toLong() ?: error("Invalid bot squad number")
}
