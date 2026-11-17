package com.peerlink.app.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual room match_env -> the pinned native lobby rule importer. */
internal object RoomMatchRules {
    private val strings = listOf("condition_home", "condition_away", "cpu_level", "injury",
        "exTime", "pk", "exSubstitution", "limitTime", "equalization", "regulation",
        "can_use_mobile_controller")

    fun encode(environment: Map<*, *>): ByteArray {
        fun integer(key: String, range: LongRange): Int {
            val value = environment[key] as? Number ?: error("Room rules are missing $key")
            val number = value.toLong()
            require(number in range && value.toDouble() == number.toDouble()) { "Invalid room rule $key" }
            return number.toInt()
        }
        val values = intArrayOf(integer("match_time", 1L..255L), integer("ball_type", 0L..65535L),
            integer("substitution_times", 0L..255L), integer("substitution", 0L..255L))
        val text = strings.map { key ->
            val value = environment[key] as? String ?: error("Room rules are missing $key")
            require(value.length <= 64 && value.all { it.code in 32..126 }) { "Invalid room rule $key" }
            value.toByteArray(Charsets.US_ASCII)
        }
        return ByteBuffer.allocate(28 + text.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0x31524c50).putInt(1).putInt(strings.size)
            values.forEach { putInt(it) }
            text.forEach { putInt(it.size); put(it) }
        }.array()
    }
}
