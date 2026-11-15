package com.peerlink.app.bot

/** Original pre-play phase report. Empty checks describe a match that has not
 * started; gameplay phases and statistics must come from the running engine. */
internal object PreMatchNotifications {
    fun dataSync(gameId: Long): LinkedHashMap<String, Any?> {
        require(gameId > 0) { "A server-assigned game ID is required" }
        return linkedMapOf(
            "game_id" to gameId, "phase" to "DATASYNC", "gtimer" to 0,
            "game_check_data" to linkedMapOf(
                "match_time_frame" to 0, "match_time_tick" to 0,
                "com_stop_info" to 0, "entry_check_data" to 0, "ball_move_max_speed" to 0,
                "gameplayer_parameter_check_data_list" to emptyList<Any>(),
                "opponent_gameplayer_parameter_check_data_list" to emptyList<Any>(),
                "custom_stadium" to linkedMapOf(
                    "stadium_id" to 0, "custom_stadium_name" to "NotImplement",
                    "stadium_color1" to 0, "stadium_color2" to 0,
                    "stadium_color3" to 40, "stadium_color4" to 40,
                    "goalnet_pattern" to 1, "goalnet_color1" to 40, "goalnet_color2" to 40,
                    "pitch_pattern" to 1, "entrance_set" to 1, "bill_set" to 1,
                    "pitchside_objects" to emptyList<Any>(), "goal_effect1" to 0,
                    "goal_effect2" to 0, "kickoff_effect" to 0, "has_invalid_assets" to "NO",
                ),
                "golazo_info" to linkedMapOf("golazo_count_list" to emptyList<Any>()),
            ),
        )
    }
}
