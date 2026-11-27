package com.peerlink.app.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** The capture envelope carries the existing PSQ1 input and the original inline metadata. */
internal class ReceivedSquadCapture private constructor(val raw: ByteArray) {
    private val envelope = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
    private fun word(offset: Int) = envelope.getInt(offset).toLong() and 0xffffffffL
    val status = listOf("captured", "waiting_for_team_sync", "memory_unavailable", "peer_team_ambiguous",
        "invalid_team_metadata", "attributes_incomplete")[word(8).toInt()]
    val account = word(12)
    val teamSlot = word(16)
    val playerCount = word(20).toInt()
    val missingRecords = word(24)
    val missingParameters = word(28)
    val defaultFormations = word(32) == 3L
    val engineInput: ByteArray? = if (playerCount > 0) raw.copyOfRange(48, 48 + 864 + 608 * playerCount) else null
    private val input = engineInput?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN) }
    private fun inputWord(offset: Int) = input!!.getInt(offset).toLong() and 0xffffffffL
    private fun inputText(offset: Int, length: Int): String {
        val bytes = engineInput!!.copyOfRange(offset, offset + length)
        return bytes.takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    }
    private fun identity(id: Long, serial: Long) = mapOf("id" to id, "serial" to serial)
    private val parameters: List<List<Long>> = List(playerCount) { player ->
        List(147) { index -> inputWord(864 + player * 608 + 16 + index * 4) }
    }
    val completePlayers = parameters.count { values -> values.all { it in 0..65535L } }
    val attributesComplete = playerCount >= 11 && completePlayers == playerCount &&
        missingRecords == 0L && missingParameters == 0L && status == "captured"

    /** An equivalent server-shaped value goes through exactly the production serializer. */
    fun squadResponse(): Map<String, Any?> {
        check(playerCount > 0) { "No received roster is available" }
        val selected = List(playerCount) { slot ->
            val offset = 864 + slot * 608
            mapOf("gameplayer_id" to identity(inputWord(offset), input!!.getLong(offset + 8)),
                "uniform_number" to inputWord(offset + 4))
        }
        val roster = selected.mapIndexed { i, player ->
            mapOf("gameplayer_id" to player["gameplayer_id"], "parameters" to parameters[i])
        }
        val coach = mapOf("coach_id" to identity(inputWord(20), input!!.getLong(24)))
        val settings = linkedMapOf<String, Any?>(
            "order" to List(40) { inputWord(112 + it * 4) },
            "captain" to inputWord(800),
            "set_piece_taker" to List(6) { inputWord(804 + it * 4) },
            "df_overlap_setplay" to List(3) { inputWord(828 + it * 4) },
        )
        listOf("auto_member_change", "auto_offside_trap", "auto_plan_change", "use_multi_tactics_plan",
            "auto_attack_level", "member_assisit").forEachIndexed { i, key -> settings[key] = inputWord(840 + i * 4) }
        val formations = List(2) { variant ->
            fun phase(phase: Int) = List(11) { slot ->
                val offset = 272 + ((variant * 2 + phase) * 11 + slot) * 12
                mapOf("role" to inputWord(offset), "position_x" to inputWord(offset + 4),
                    "position_y" to inputWord(offset + 8))
            }
            mapOf("team_style" to if (defaultFormations) 0 else null,
                "use_multi_formation" to if (defaultFormations) "NO" else null,
                "formation_offensive" to phase(0), "formation_defensive" to phase(1))
        }
        return mapOf("result" to "NOERR",
            "squad_data" to mapOf("player_list" to selected, "coach" to listOf(coach),
                "squad_gameplan" to mapOf("match_plan_settings" to settings, "formation" to formations)),
            "player_list" to roster, "coach_list" to listOf(coach),
            "team_data" to mapOf("base_team_id" to inputWord(12), "team_name" to inputText(32, 72),
                "team_name_short" to inputText(104, 8)))
    }

    val compatibilityIssue: String? by lazy {
        when {
            !attributesComplete -> "Complete keyed player attributes have not been recovered"
            !defaultFormations -> "Received formation options exceed the current squad input format"
            else -> runCatching {
                check(SquadInput.encode(squadResponse(), account).contentEquals(engineInput)) {
                    "The decoded squad does not round-trip to the captured input bytes"
                }
            }.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
        }
    }
    val engineCompatible get() = compatibilityIssue == null

    fun report(): Map<String, Any?> = linkedMapOf(
        "schema" to "peerlink.received_squad.v1",
        "source" to "decoded_peer_team_and_native_player_cache",
        "status" to status,
        "source_account" to account,
        "native_team_slot" to teamSlot,
        "selected_players" to playerCount,
        "parameters_per_player" to 147,
        "players_with_complete_attributes" to completePlayers,
        "missing_player_records" to missingRecords,
        "missing_parameters" to missingParameters,
        "attributes_complete" to attributesComplete,
        "formation_options_match_loader_defaults" to defaultFormations,
        "engine_input_format" to "PSQ1",
        "engine_input_version" to 1,
        "engine_input_compatible" to engineCompatible,
        "compatibility_issue" to compatibilityIssue,
        "engine_input_sha256" to engineInput?.let(::sha256),
        "native_team_metadata_hex" to if (playerCount > 0) hex(raw.copyOfRange(48 + 864 + 608 * playerCount, raw.size)) else null,
        "squad_response" to if (playerCount > 0) squadResponse() else null,
    )

    companion object {
        fun decode(bytes: ByteArray): ReceivedSquadCapture {
            require(bytes.size >= 48) { "Incomplete squad capture header" }
            val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(header.int == 0x31435352 && header.int == 1) { "Unsupported squad capture format" }
            val status = header.int
            require(status in 0..5) { "Unknown squad capture status" }
            val peer = header.int.toLong() and 0xffffffffL
            val slot = header.int
            val count = header.int
            require(count == 0 || count in 11..40) { "Invalid received roster size" }
            require(status != 0 || count > 0) { "Captured squad has no players" }
            require(bytes.size == if (count == 0) 48 else 48 + 864 + count * 608 + 0x2100) {
                "Incomplete squad capture payload"
            }
            if (count > 0) {
                require(peer > 0 && slot in 0..1 && header.getInt(48) == 0x31515350 &&
                    header.getInt(52) == 1 && (header.getInt(56).toLong() and 0xffffffffL) == peer &&
                    header.getInt(64) == count) { "Received squad input identity differs from its capture" }
            }
            return ReceivedSquadCapture(bytes)
        }
        fun sha256(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
        private fun hex(bytes: ByteArray): String {
            val digits = "0123456789abcdef"
            return buildString(bytes.size * 2) {
                for (byte in bytes) {
                    val value = byte.toInt() and 255
                    append(digits[value ushr 4]); append(digits[value and 15])
                }
            }
        }
    }
}
