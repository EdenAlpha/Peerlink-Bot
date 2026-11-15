package com.peerlink.app.bot

/** Original 11.1.1 table names/strides at libUE4 +0xcd5f20/+0xcd5fd0.
 * Only used with the engine pinned by EngineRuntime.ENGINE_SHA256. */
internal object GameDatabaseSchema {
    val strides = linkedMapOf(
        "Ball.bin" to 188,
        "BallCondition.bin" to 8,
        "Boots.bin" to 304,
        "Coach.bin" to 176,
        "CoachDeleteList.bin" to 8,
        "CompetitionEntry.bin" to 12,
        "CompetitionUnit.bin" to 2472,
        "CompetitionBerth.bin" to 8,
        "CompetitionSlots.bin" to 8,
        "CompetitionTournament.bin" to 8,
        "CompetitionTournamentAssignment.bin" to 8,
        "CompetitionTournamentDetail.bin" to 8,
        "CompetitionTournamentPosition.bin" to 8,
        "CompetitionTournamentPositionFor3Rd.bin" to 8,
        "CategoryTeamList.bin" to 12,
        "Country.bin" to 1488,
        "City.bin" to 8,
        "Derby.bin" to 12,
        "GameDefine.bin" to 8,
        "Glove.bin" to 204,
        "CoachTactics.bin" to 16,
        "CoachTacticsFormation.bin" to 16,
        "Player.bin" to 400,
        "PlayerAssignment.bin" to 24,
        "PlayerDeleteList.bin" to 8,
        "SpecialPlayerAssignment.bin" to 24,
        "Stadium.bin" to 1096,
        "StadiumOrder.bin" to 8,
        "StadiumOrderInConfederation.bin" to 8,
        "StadiumWeight.bin" to 8,
        "Tactics.bin" to 12,
        "TacticsFormation.bin" to 12,
        "Team.bin" to 1600,
        "Weather.bin" to 12,
        "PlayerWeekly.bin" to 16,
        "TeamWeekly.bin" to 8,
        "PlayerVariationDetail.bin" to 168,
        "PlayerBooster.bin" to 40,
        "CoachBooster.bin" to 4,
        "CoachVariationDetail.bin" to 160,
        "CoachLink.bin" to 8,
        "PlayerVariationPrSkill.bin" to 16,
        "Playstyle.bin" to 4,
        "PlayerSkill.bin" to 4,
    )
    fun validate(name: String, bytes: Int): Int {
        val stride = requireNotNull(strides[name]) { "Unexpected database table $name" }
        require(bytes >= 0 && bytes % stride == 0) { "Invalid decoded length for $name: $bytes, record stride $stride" }
        return bytes / stride
    }
}
