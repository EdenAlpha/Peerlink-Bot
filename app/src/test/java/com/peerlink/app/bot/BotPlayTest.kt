package com.peerlink.app.bot

import org.junit.Assert.*
import org.junit.Test

class BotPlayTest {
    private fun scenario(player: PitchPoint, owner: Int = 6): BotPlayState {
        val players = List(22) { id ->
            if (id < 11) PitchPoint(-25f, 12f + id)
            else PitchPoint(26f, -12f - (id - 11))
        }.toMutableList()
        players[0] = PitchPoint(-51f, 0f)
        players[11] = PitchPoint(51f, 0f)
        players[6] = player
        return BotPlayState(100, 0, 6, owner, 0, 11, player, 0.1f, PitchPoint(0f, 0f), players)
    }

    @Test fun controlledPlayerCanKickOffImmediatelyAndWaitsForOpponentsRestart() {
        val state = scenario(PitchPoint(-0.9f, 0f)).copy(ball = PitchPoint(0f, 0f))
        assertEquals(BotAction.PASS, BotBrain().decide(state).action)
        val opponentsRestart = state.copy(
            owner = 18, players = state.players.toMutableList().apply {
                this[6] = PitchPoint(-10f, 0f)
                this[18] = PitchPoint(0.8f, 0f)
            },
        )
        assertEquals(BotPlay(), BotBrain().decide(opponentsRestart))
    }

    @Test fun shootsInRangeAndReversesAimWhenEndsChange() {
        val state = scenario(PitchPoint(39f, 2f))
        val brain = BotBrain()
        val shot = brain.decide(state)
        assertEquals(BotAction.SHOOT, shot.action)
        assertTrue(shot.stick.x > 0f)
        val reversed = state.copy(ball = state.ball * -1f, players = state.players.map { it?.times(-1f) })
        val secondHalfShot = brain.decide(reversed)
        assertEquals(BotAction.SHOOT, secondHalfShot.action)
        assertTrue(secondHalfShot.stick.x < 0f)
    }

    @Test fun pressurePassesToOpenTeammate() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[4] = PitchPoint(9f, 5f)
            this[16] = PitchPoint(1f, -2.5f)
        }
        val plan = BotBrain().decide(state.copy(players = players))
        assertEquals(BotAction.PASS, plan.action)
        assertTrue(plan.stick.x > 0f && plan.stick.y > 0f)
    }

    @Test fun throughBallRequiresAForwardLaneAndOnsideReceiver() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[4] = PitchPoint(16f, 5f)
            this[16] = PitchPoint(-3f, -2.5f)
        }
        assertEquals(BotAction.THROUGH, BotBrain().decide(state.copy(players = players)).action)
        players[4] = PitchPoint(23f, 5f)
        for (id in 12..21) if (id != 16) players[id] = PitchPoint(18f, -17f)
        assertNotEquals(BotAction.THROUGH, BotBrain().decide(state.copy(players = players)).action)
    }

    @Test fun dribblesAndSprintsWhenThereIsSpace() {
        val plan = BotBrain().decide(scenario(PitchPoint(5f, 1f)))
        assertNull(plan.action)
        assertTrue(plan.stick.sprint && plan.stick.x > 0f)
    }

    @Test fun pressesCloseOpponentAndSwitchesToCloserDefender() {
        val state = scenario(PitchPoint(-3f, 0f), 17).copy(ball = PitchPoint(-1f, 0f))
        val players = state.players.toMutableList().apply { this[17] = state.ball }
        assertEquals(BotAction.PRESS, BotBrain().decide(state.copy(players = players)).action)
        players[6] = PitchPoint(-20f, 0f)
        players[8] = PitchPoint(-3f, 0f)
        assertEquals(BotAction.SWITCH, BotBrain().decide(state.copy(players = players)).action)
    }

    @Test fun keeperDistributesAndDoesNotChaseAcrossPitch() {
        val state = scenario(PitchPoint(-30f, 16f))
        val keeper = state.copy(selected = 0, owner = 0, ball = requireNotNull(state.players[0]))
        assertEquals(BotAction.PASS, BotBrain().decide(keeper).action)
        val opponent = keeper.copy(owner = 20, ball = requireNotNull(keeper.players[20]))
        val plan = BotBrain().decide(opponent)
        assertEquals(BotAction.SWITCH, plan.action)
        assertEquals(BotStick(), plan.stick)
    }

    @Test fun rejectsInvalidSelectionAndAcceptsVersionedNativeLayout() {
        val words = IntArray(55).apply {
            this[0] = 99; this[1] = 1; this[2] = 17; this[3] = -1
            this[4] = 11; this[5] = 0
            this[7] = -3000; this[8] = 108
            this[11 + 2 * 17] = 1200
        }
        val state = requireNotNull(BotPlayState.decode(words))
        assertEquals(17, state.selected)
        assertEquals(PitchPoint(0f, -3f), state.ball)
        assertEquals(PitchPoint(1.2f, 0f), state.players[17])
        assertNull(BotPlayState.decode(words.copyOf(54)))
        assertNull(BotPlayState.decode(words.copyOf().apply { this[2] = 0 }))
        assertNull(BotPlayState.decode(words.copyOf().apply { this[0] = 0 }))
    }

    @Test fun failedPhoneReadStillAllowsOpeningPassWithLimitedRetries() {
        val kickoff = OpeningKickoff()
        assertFalse(kickoff.shouldPress(false, true, 4, true))
        assertFalse(kickoff.shouldPress(true, false, 21, true))
        assertTrue(kickoff.shouldPress(true, true, 21, true))
        kickoff.pressed(21)
        assertFalse(kickoff.shouldPress(true, true, 25, true))
        assertTrue(kickoff.shouldPress(true, true, 76, true))
        kickoff.pressed(76)
        assertTrue(kickoff.shouldPress(true, true, 131, true))
        kickoff.pressed(131)
        assertFalse(kickoff.shouldPress(true, true, 180, true))
        assertFalse(kickoff.shouldPress(true, true, 2000, true))
    }

    @Test fun automaticOpeningAssistNeverReturnsAfterPlayStarts() {
        val kickoff = OpeningKickoff()
        assertFalse(kickoff.shouldPress(false, true, 35, false))
        assertFalse(kickoff.shouldPress(true, true, 100, true))
        assertFalse(kickoff.shouldPress(true, true, 7, true)) // Even after halftime resets a frame counter.
        assertFalse(OpeningKickoff().shouldPress(true, true, 181, true))
    }

    @Test fun partialSnapshotStillShootsAndDoesNotTreatUnknownPossessionAsFreeBall() {
        val state = scenario(PitchPoint(39f, 2f))
        val partial = state.copy(players = state.players.toMutableList().apply { this[4] = null; this[16] = null })
        assertEquals(BotAction.SHOOT, BotBrain().decide(partial).action)
        assertEquals(BotPlay(), BotBrain().decide(partial.copy(owner = -2)))
        assertEquals(BotPlay(), BotBrain().decide(partial.copy(selected = -1)))
        assertEquals(BotAction.PRESS, BotBrain().decide(partial.copy(selected = -1, owner = 15)).action)
    }

    @Test fun missingPossessionStillAllowsMovementTowardTheLiveBall() {
        val unresolved = scenario(PitchPoint(-7f, 2f), -2).copy(
            ball = PitchPoint(1f, -2f), velocity = PitchPoint(2f, 0f),
        )
        val plan = BotBrain().decide(unresolved)
        assertNull(plan.action)
        assertTrue(plan.stick.x > 0f && plan.stick.y < 0f && plan.stick.sprint)
    }

    @Test fun recordedCentreBallRejectsDistantKeeperAndFindsKickoffPlayer() {
        // Phone 5.0.34, frame 47 positions rounded to centimetres.
        val players = listOf(
            PitchPoint(-47.07f, 0f), PitchPoint(-18.5f, 6f), PitchPoint(-18.5f, -6f),
            PitchPoint(-15.5f, 23f), PitchPoint(-15.5f, -23f), PitchPoint(-10f, 5.5f),
            PitchPoint(-10f, -5.5f), PitchPoint(-5.5f, 24f), PitchPoint(-5.5f, -24f),
            PitchPoint(-3.66f, 9.47f), PitchPoint(0.55f, 0.25f), PitchPoint(47.07f, 0f),
            PitchPoint(19.5f, 0f), PitchPoint(18.5f, -6f), PitchPoint(18.5f, 6f),
            PitchPoint(17f, -18f), PitchPoint(17f, 18f), PitchPoint(11f, 0f),
            PitchPoint(1.5f, -15.5f), PitchPoint(1.51f, -10.04f), PitchPoint(1.5f, 15.5f),
            PitchPoint(1.51f, 10.04f),
        )
        for ((selected, owner) in listOf(10 to 0, -1 to -2)) {
            val state = scenario(PitchPoint(0f, 0f)).copy(selected = selected, owner = owner, players = players)
            val control = state.forControl()
            assertEquals(10, control.selected)
            assertEquals(10, control.owner)
            assertEquals(BotAction.PASS, BotBrain().decide(control).action)
        }
    }

    @Test fun distantKeeperReadingCannotBlockAnOpenShotOrDefensivePressure() {
        val shot = scenario(PitchPoint(39f, 2f), 0)
        assertEquals(BotAction.SHOOT, BotBrain().decide(shot.forControl()).action)
        val opponent = shot.copy(ball = PitchPoint(37f, 2f), players = shot.players.toMutableList().apply {
            this[15] = PitchPoint(37f, 2.1f)
        })
        assertEquals(15, opponent.forControl().owner)
        assertEquals(BotAction.PRESS, BotBrain().decide(opponent.forControl()).action)
    }

    @Test fun airborneFastContestedAndExplicitlyLooseBallsDoNotBecomePossession() {
        val near = scenario(PitchPoint(35f, 0f), 0)
        val contested = near.copy(players = near.players.toMutableList().apply { this[18] = PitchPoint(35.25f, 0f) })
        for (state in listOf(near.copy(height = 2.5f), near.copy(velocity = PitchPoint(12f, 0f)), contested))
            assertEquals(-2, state.forControl().owner)
        assertEquals(-1, near.copy(owner = -1).forControl().owner)
        val keeper = near.copy(selected = 0, ball = requireNotNull(near.players[0]))
        assertEquals(0, keeper.forControl().owner)
    }

    @Test fun missingCursorStillAllowsAChaseAndKeepsAwaySide() {
        val state = scenario(PitchPoint(5f, 0f), -2).copy(selected = -1, ball = PitchPoint(12f, 0f))
        val control = state.forControl()
        assertEquals(6, control.selected)
        assertTrue(BotBrain().decide(control).stick.sprint)
        val away = state.copy(side = 1, ownKeeper = 11, opposingKeeper = 0)
        assertTrue(away.forControl().selected in 12..21)
    }

    @Test fun bothRecordedGoalsPauseTacticalInputsUntilRestart() {
        val gate = GoalCelebration()
        fun sample(frame: Int, x: Float, height: Float, z: Float) =
            gate.observingGoal(scenario(PitchPoint(x, z)).copy(frame = frame, height = height))
        // Recorded wide shot and keeper restart. The chord between these sparse samples
        // crosses the goal mouth, but the ball is actually observed outside the net.
        assertFalse(sample(1539, -49.999f, 2.952f, -3.741f))
        assertFalse(sample(1566, -59.4f, 0.109f, 8.96f))
        assertFalse(sample(1620, -50.351f, 0.782f, 5.06f))
        // 5.0.35 engine CHECK_FRAME ball positions, including the rebound into the field.
        assertFalse(sample(2457, -48.05f, 2.29f, 4.68f))
        assertTrue(sample(2484, -54.84f, 0.68f, -3.04f))
        assertTrue(sample(2511, -52.99f, 0.11f, -0.27f))
        assertTrue(sample(2673, -50.58f, 0.11f, 3.36f))
        assertFalse(sample(2781, 0f, 0.11f, 0f))
        assertFalse(sample(3510, -49.31f, 1.25f, 1.46f))
        assertTrue(sample(3537, -54.71f, 0.11f, 1.02f))
        assertTrue(sample(3564, -52.94f, 0.11f, -1.3f))
        assertTrue(sample(3591, -51.89f, 0.11f, -2.65f))
        assertTrue(sample(3672, -50.65f, 0.11f, -4.22f))
        gate.reset()
        assertFalse(sample(1, 0f, 0.11f, 0f))
    }

    @Test fun wideShotsCrossbarShotsAndShotsThatStayInPlayRemainControllable() {
        for (end in listOf(-1, 1)) {
            fun crossing(z: Float, height: Float, to: Float): Boolean {
                val gate = GoalCelebration()
                val start = scenario(PitchPoint(52f * end, z)).copy(height = height)
                assertFalse(gate.observingGoal(start))
                return gate.observingGoal(start.copy(frame = start.frame + 1, ball = PitchPoint(to * end, z)))
            }
            assertFalse(crossing(4.2f, 0.1f, 54f))
            assertFalse(crossing(0f, 2.8f, 54f))
            assertFalse(crossing(0f, 0.1f, 52.4f))
            assertTrue(crossing(2f, 1f, 53f))
        }
    }

    @Test fun openForwardPassIsPlayedOnFirstTouchBeforeDefenderApproaches() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[4] = PitchPoint(17f, 4f)
            this[16] = PitchPoint(2f, -11f)
        }
        for (sign in listOf(1f, -1f)) {
            val snapshot = state.copy(ball = state.ball * sign, players = players.map { it?.times(sign) })
            val play = BotBrain().decide(snapshot.forControl())
            assertEquals(BotAction.PASS, play.action)
            assertTrue(play.stick.x * sign > 0f && play.stick.y * sign > 0f)
        }
    }

    @Test fun approachingOpponentTriggersEarlyOutletWhileRetreatingOpponentDoesNot() {
        val state = scenario(PitchPoint(2f, 0f))
        fun snapshot(frame: Int, distance: Float) = state.copy(frame = frame,
            players = state.players.toMutableList().apply {
                this[4] = PitchPoint(4f, 6f)
                this[16] = PitchPoint(2f, -distance)
            })
        fun playWithPrevious(distance: Float): BotPlay {
            val brain = BotBrain()
            brain.decide(snapshot(80, distance).forControl())
            return brain.decide(snapshot(84, 8f).forControl())
        }
        assertEquals(BotAction.PASS, playWithPrevious(10f).action)
        val carry = playWithPrevious(7f)
        assertNull(carry.action)
        assertTrue(carry.stick.sprint)
    }

    @Test fun markedAndOffsideReceiversAreSkippedWithRoomToCarry() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[4] = PitchPoint(30f, 3f)
            this[8] = PitchPoint(12f, 4f)
            this[15] = PitchPoint(12.5f, 4.5f)
        }
        val plan = BotBrain().decide(state.copy(players = players).forControl())
        assertNull(plan.action)
        assertTrue(plan.stick.x > 0f && plan.stick.sprint)
    }

    @Test fun shootsAtOtherCornerWhenDefenderCoversFarPost() {
        val state = scenario(PitchPoint(39f, 0f))
        val players = state.players.toMutableList().apply { this[15] = PitchPoint(46f, -1.3f) }
        val plan = BotBrain().decide(state.copy(players = players).forControl())
        assertEquals(BotAction.SHOOT, plan.action)
        assertTrue(plan.stick.y > 0f)
    }

    @Test fun turnoverAndNewReceiverCanPlayImmediatelyWithoutRepeatingSameButtons() {
        val carrier = scenario(PitchPoint(2f, 0f))
        val window = BotActionWindow()
        window.sent(1000, BotAction.SWITCH, 5)
        assertTrue(window.available(1100, BotAction.PASS, carrier))
        assertFalse(window.available(1100, BotAction.SWITCH, carrier))
        window.sent(1100, BotAction.PASS, 6)
        assertFalse(window.available(1200, BotAction.PASS, carrier))
        assertTrue(window.available(1200, BotAction.SHOOT, carrier.copy(selected = 7, owner = 7)))
        assertFalse(window.available(1200, BotAction.SHOOT, carrier.copy(selected = 7, owner = -2)))
        window.sent(1200, BotAction.PASS, -1)
        assertFalse(window.available(1300, BotAction.PASS, carrier))
    }

    @Test fun runnerClosingAPassingLaneChangesTheReceiver() {
        val state = scenario(PitchPoint(2f, 0f))
        fun withMarker(frame: Int, markerZ: Float) = state.copy(frame = frame,
            players = state.players.toMutableList().apply {
                this[8] = PitchPoint(19f, 0f)
                this[7] = PitchPoint(11f, 7f)
                this[15] = PitchPoint(12f, markerZ)
            })
        val brain = BotBrain()
        brain.decide(withMarker(40, -4.1f))
        val plan = brain.decide(withMarker(44, -2.7f))
        assertEquals(7, plan.receiver)
        assertTrue(plan.stick.y > 0f)
    }

    @Test fun groundPassDoesNotAimThroughTheCloserTeammate() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[7] = PitchPoint(11f, 1f)
            this[8] = PitchPoint(21f, 2f)
        }
        assertEquals(7, BotBrain().decide(state.copy(players = players)).receiver)
    }

    @Test fun defenderKeepsRunningDuringSwitchAndDoesNotSwitchOnAnEstimatedCursor() {
        val state = scenario(PitchPoint(-20f, 0f), 17).copy(ball = PitchPoint(-1f, 0f))
        val players = state.players.toMutableList().apply {
            this[17] = state.ball
            this[8] = PitchPoint(-3f, 2f)
        }
        val known = state.copy(players = players)
        val switch = BotBrain().decide(known)
        assertEquals(BotAction.SWITCH, switch.action)
        assertTrue(switch.stick.x > 0f)
        val unknown = known.copy(selected = -1).forControl()
        assertTrue(unknown.estimatedSelection)
        assertNotEquals(BotAction.SWITCH, BotBrain().decide(unknown).action)
    }

    @Test fun standingDefenderCanStepIntoPassAndBotUsesSideOutletInEitherDirection() {
        val state = scenario(PitchPoint(2f, 0f))
        val formation = state.players.toMutableList().apply {
            this[8] = PitchPoint(22f, 0f)
            this[7] = PitchPoint(11f, 7f)
            this[15] = PitchPoint(14f, -3f)
        }
        for (sign in listOf(1f, -1f)) {
            val rotated = state.copy(ball = state.ball * sign, players = formation.map { it?.times(sign) })
            val play = BotBrain().decide(rotated)
            assertEquals(7, play.receiver)
            assertTrue(play.stick.y * sign > 0)
        }
    }

    @Test fun throughPassMustAlsoBeSafeBeyondTheReceiver() {
        val state = scenario(PitchPoint(2f, 0f))
        val players = state.players.toMutableList().apply {
            this[4] = PitchPoint(16f, 5f)
            this[16] = PitchPoint(-3f, -2.5f)
            this[17] = PitchPoint(20.5f, 5.5f)
        }
        assertNotEquals(BotAction.THROUGH, BotBrain().decide(state.copy(players = players)).action)
    }

    @Test fun burstAwayFromChasingDefenderWhenSidePassGainsLittle() {
        val state = scenario(PitchPoint(10f, 2f))
        val players = state.players.toMutableList().apply {
            this[7] = PitchPoint(17.5f, 10f)
            this[16] = PitchPoint(7f, 2.5f)
        }
        for (teammateX in listOf(17.5f, 18.6f)) {
            players[7] = PitchPoint(teammateX, 10f)
            val play = BotBrain().decide(state.copy(players = players))
            assertNull(play.action)
            assertTrue(play.stick.sprint && play.stick.x > 0.8f)
        }
    }

    @Test fun blockedPassAndCarryChooseAnEscapeInsteadOfRunningIntoTheMarker() {
        val state = scenario(PitchPoint(5f, 0f))
        val players = state.players.toMutableList().apply {
            for (id in 1..10) if (id != 6) this[id] = PitchPoint(42f, 15f)
            this[8] = PitchPoint(20f, 0f)
            this[15] = PitchPoint(7.5f, 0f)
        }
        val brain = BotBrain()
        val blocked = state.copy(players = players)
        val play = brain.decide(blocked)
        assertNull(play.action)
        assertTrue(kotlin.math.abs(play.stick.y) >= 0.5f)
        val protecting = brain.carryBetweenKicks(blocked)
        assertTrue(kotlin.math.abs(protecting.y) >= 0.5f)
    }

    @Test fun centreRestartAimsAtAVisibleOpenTeammateAndDebouncesTheSamePasser() {
        val state = scenario(PitchPoint(-0.5f, 0f)).copy(ball = PitchPoint(0f, 0f))
        val players = state.players.toMutableList().apply {
            this[7] = PitchPoint(-5f, 7f)
            this[8] = PitchPoint(3f, 5f)
            this[15] = PitchPoint(1.3f, 2.2f)
        }
        val restart = state.copy(players = players)
        val brain = BotBrain()
        val plan = brain.decide(restart)
        assertEquals(BotAction.PASS, plan.action)
        assertEquals(7, plan.receiver)
        assertTrue(plan.stick.x < 0 && plan.stick.y > 0)
        assertEquals(BotStick(), brain.carryBetweenKicks(restart))
        val window = BotActionWindow()
        window.sent(1000, BotAction.PASS, 6, centreRestart = true)
        assertFalse(window.available(1500, BotAction.PASS, restart))
        assertTrue(window.available(2020, BotAction.PASS, restart))
    }

    @Test fun lastDefenderScreensTheCutbackWhileAnOutfielderWithCoverCanPressWide() {
        val state = scenario(PitchPoint(-39f, 8f), 17).copy(ball = PitchPoint(-35f, 15f))
        val players = state.players.toMutableList().apply {
            this[17] = state.ball
            this[18] = PitchPoint(-43f, 0f)
        }
        val brain = BotBrain()
        val last = brain.decide(state.copy(players = players))
        assertTrue("last defender protects the inside", last.stick.x < 0 && last.stick.y < 0)
        players[5] = PitchPoint(-42f, 2f)
        val covered = brain.decide(state.copy(frame = 101, players = players))
        assertTrue("cover frees the defender to close down the wing", covered.stick.x > 0 && covered.stick.y > 0)
    }

    @Test fun beatenDefenderRecoversInsideWithSprintAndTimesTheirChallenge() {
        val state = scenario(PitchPoint(-18f, 2f), 17).copy(ball = PitchPoint(-21.1f, 2f))
        val players = state.players.toMutableList().apply { this[17] = state.ball }
        val beaten = BotBrain().decide(state.copy(players = players))
        assertNotEquals(BotAction.PRESS, beaten.action)
        assertTrue(beaten.stick.sprint && beaten.stick.x < 0)
        players[6] = PitchPoint(-23f, 2f)
        val close = BotBrain().decide(state.copy(players = players))
        assertEquals(BotAction.PRESS, close.action)
        assertFalse(close.stick.sprint)
    }

    @Test fun trackingARealDribbleRestoresPossessionForEitherTeamButRejectsAPassOrATie() {
        for (carrier in listOf(6, 16)) {
            val base = scenario(PitchPoint(15f, 1f), -2)
            fun at(frame: Int, x: Float, ballX: Float, contested: Boolean = false) = base.copy(frame = frame,
                players = base.players.toMutableList().apply {
                    this[6] = PitchPoint(-12f, 1f)
                    this[carrier] = PitchPoint(x, 1f)
                    if (contested) this[20] = PitchPoint(ballX + 2.2f, 1f)
                }, ball = PitchPoint(ballX, 1f), velocity = PitchPoint(8f, 0f))
            val brain = BotControlTracker()
            assertEquals(-2, brain.resolve(at(100, 15f, 17f)).owner)
            assertEquals(carrier, brain.resolve(at(103, 15.7f, 17.7f)).owner)
            assertEquals(-2, brain.resolve(at(106, 16.4f, 18.4f, contested = true)).owner)
            brain.reset()
            brain.resolve(at(100, 15f, 16.3f))
            assertEquals(-2, brain.resolve(at(103, 15.3f, 17.7f)).owner)
        }
    }

    @Test fun uncertainCloseOpponentIsCoveredWithoutPressingAndFirstConfirmedTurnoverCanBeChallenged() {
        val state = scenario(PitchPoint(-18f, 2f), -2).copy(ball = PitchPoint(-21.1f, 2f))
        val players = state.players.toMutableList().apply { this[17] = state.ball }
        val recovering = BotBrain().decide(state.copy(players = players))
        assertNull(recovering.action)
        assertTrue(recovering.stick.x < 0f && recovering.stick.sprint)
        val window = BotActionWindow()
        window.sent(1000, BotAction.SHOOT, 6)
        assertFalse(window.available(1100, BotAction.PRESS, state))
        assertTrue(window.available(1100, BotAction.PRESS, state.copy(owner = 17)))
        window.sent(1100, BotAction.PRESS, 6)
        assertFalse(window.available(1150, BotAction.PRESS, state.copy(owner = 17)))
    }
}
