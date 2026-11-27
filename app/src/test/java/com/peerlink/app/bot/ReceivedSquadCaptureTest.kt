package com.peerlink.app.bot

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class ReceivedSquadCaptureTest {
    @Test fun completeSelectedRosterRoundTripsThroughTheActualEngineSerializer() {
        for (count in listOf(11, 23, 40)) {
            val capture = ReceivedSquadCapture.decode(fixture(count))
            assertEquals(71L, capture.account)
            assertEquals(count, capture.completePlayers)
            assertTrue(capture.attributesComplete)
            assertNull(capture.compatibilityIssue)
            assertTrue(capture.engineCompatible)
            assertArrayEquals(capture.engineInput, SquadInput.encode(capture.squadResponse(), capture.account))
            val roster = capture.squadResponse()["player_list"] as List<*>
            assertEquals(count, roster.size)
            val final = roster.last() as Map<*, *>
            assertEquals(147, (final["parameters"] as List<*>).size)
            assertEquals(9000000000L + count - 1, (final["gameplayer_id"] as Map<*, *>)["serial"])
        }
    }
    @Test fun unknownAttributeStaysMissingAndCannotBeExportedAsALoadableSquad() {
        val bytes = fixture()
        val wire = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        wire.putInt(8, 5)
        wire.putInt(28, 1)
        wire.putInt(48 + 864 + 15 * 608 + 16 + 42 * 4, -1)
        val capture = ReceivedSquadCapture.decode(bytes)
        assertEquals(22, capture.completePlayers)
        assertFalse(capture.attributesComplete)
        assertFalse(capture.engineCompatible)
        val players = capture.squadResponse()["player_list"] as List<*>
        assertEquals(0xffffffffL, ((players[15] as Map<*, *>)["parameters"] as List<*>)[42])
    }
    @Test fun unsupportedTacticsAndCorruptIdentityFailTheSameProductionValidation() {
        val tactics = fixture()
        ByteBuffer.wrap(tactics).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 1)
        val captured = ReceivedSquadCapture.decode(tactics)
        assertTrue(captured.attributesComplete)
        assertFalse(captured.engineCompatible)
        val duplicate = fixture()
        val wire = ByteBuffer.wrap(duplicate).order(ByteOrder.LITTLE_ENDIAN)
        wire.putInt(48 + 864 + 608, wire.getInt(48 + 864))
        wire.putLong(48 + 864 + 608 + 8, wire.getLong(48 + 864 + 8))
        assertFalse(ReceivedSquadCapture.decode(duplicate).engineCompatible)
    }
    @Test fun badSizeVersionAndAccountAreRejectedBeforeReadingAPlayer() {
        val original = fixture()
        val mutations = listOf(original.copyOf(30), original.copyOf(original.size - 1),
            original.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 5) },
            original.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(12, 72) },
            original.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 41) })
        for (bad in mutations) {
            try { ReceivedSquadCapture.decode(bad); fail("Malformed capture accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aWaitingCaptureHasAReadableStatusAndNoInventedRoster() {
        val bytes = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x31435352).putInt(1).putInt(1).array()
        val capture = ReceivedSquadCapture.decode(bytes)
        assertEquals("waiting_for_team_sync", capture.status)
        assertEquals(0, capture.playerCount)
        assertFalse(capture.engineCompatible)
        assertNull(capture.report()["squad_response"])
    }

    companion object {
        fun fixture(count: Int = 23): ByteArray {
            val players = List(count) { slot -> mapOf(
                "gameplayer_id" to mapOf("id" to 1000L + slot, "serial" to 9000000000L + slot),
                "parameters" to List(147) { (slot * 160L + it) },
                "uniform_number" to slot + 1) }
            val settings = linkedMapOf<String, Any?>(
                "order" to List(40) { if (it < count) it else 255 }, "captain" to 3,
                "set_piece_taker" to List(6) { it + 1 }, "df_overlap_setplay" to List(3) { it + 4 })
            for (key in listOf("auto_member_change", "auto_offside_trap", "auto_plan_change",
                    "use_multi_tactics_plan", "auto_attack_level", "member_assisit")) settings[key] = 0
            val positions = List(11) { mapOf("role" to it, "position_x" to 12 + it, "position_y" to 35 + it) }
            val formation = mapOf("team_style" to 0, "use_multi_formation" to "NO",
                "formation_offensive" to positions, "formation_defensive" to positions)
            val coach = mapOf("coach_id" to mapOf("id" to 400L, "serial" to 8000000000L))
            val response = mapOf(
                "squad_data" to mapOf("player_list" to players, "coach" to listOf(coach),
                    "squad_gameplan" to mapOf("match_plan_settings" to settings, "formation" to List(2) { formation })),
                "player_list" to players, "coach_list" to listOf(coach),
                "team_data" to mapOf("base_team_id" to 150, "team_name" to "My captured squad", "team_name_short" to "MY"))
            val input = SquadInput.encode(response, 71)
            return ByteBuffer.allocate(48 + input.size + 0x2100).order(ByteOrder.LITTLE_ENDIAN).apply {
                putInt(0x31435352); putInt(1); putInt(0); putInt(71); putInt(1); putInt(count)
                putInt(0); putInt(0); putInt(3); putInt(0); putInt(0); putInt(0)
                put(input)
            }.array()
        }
    }
}
