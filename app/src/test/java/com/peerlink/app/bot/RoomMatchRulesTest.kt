package com.peerlink.app.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class RoomMatchRulesTest {
    private fun room() = linkedMapOf<String, Any>(
        "condition_home" to "CONDITION_RANDOM", "condition_away" to "CONDITION_GOOD",
        "cpu_level" to "EASY", "match_time" to 1L, "injury" to "NO", "ball_type" to 22L,
        "exTime" to "NO", "pk" to "YES", "substitution_times" to 3L, "substitution" to 5L,
        "exSubstitution" to "", "limitTime" to "MIDDLE", "equalization" to "NO",
        "regulation" to "NORMAL", "can_use_mobile_controller" to "YES")

    @Test fun preservesActualServerRulesInNativeWireOrder() {
        val input = ByteBuffer.wrap(RoomMatchRules.encode(room())).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x31524c50, input.int)
        assertEquals(1, input.int)
        assertEquals(11, input.int)
        assertArrayEquals(intArrayOf(1,22,3,5), IntArray(4) { input.int })
        val actual = List(11) { ByteArray(input.int).also { input.get(it) }.toString(Charsets.US_ASCII) }
        assertEquals(listOf("CONDITION_RANDOM", "CONDITION_GOOD", "EASY", "NO", "NO", "YES",
            "", "MIDDLE", "NO", "NORMAL", "YES"), actual)
        assertFalse(input.hasRemaining())
    }

    @Test fun preservesSixMinuteRoomTime() {
        val input = RoomMatchRules.encode(room().apply { put("match_time", 6L) })
        assertEquals(6, ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
    }

    @Test fun refusesMissingRulesInsteadOfSelectingNativeDefaults() {
        for (key in room().keys) {
            assertThrows("missing $key", IllegalStateException::class.java) {
                RoomMatchRules.encode(room().apply { remove(key) })
            }
        }
    }

    @Test fun refusesLossyNumbersAndNativeStringTerminators() {
        for ((key, value) in listOf("match_time" to 0, "match_time" to 256,
            "match_time" to 1.5, "ball_type" to 65536, "injury" to "YES\u0000NO")) {
            assertThrows("invalid $key", IllegalArgumentException::class.java) {
                RoomMatchRules.encode(room().apply { put(key, value) })
            }
        }
    }
}
