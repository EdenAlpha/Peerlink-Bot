package com.peerlink.app.bot

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PitchPoint(val x: Float, val z: Float) {
    operator fun plus(other: PitchPoint) = PitchPoint(x + other.x, z + other.z)
    operator fun minus(other: PitchPoint) = PitchPoint(x - other.x, z - other.z)
    operator fun times(factor: Float) = PitchPoint(x * factor, z * factor)
    fun dot(other: PitchPoint) = x * other.x + z * other.z
    fun length() = sqrt(dot(this))
    fun distance(other: PitchPoint): Float {
        val dx = x - other.x
        val dz = z - other.z
        return sqrt(dx * dx + dz * dz)
    }
}

internal data class BotPlayState(
    val frame: Int,
    val side: Int,
    val selected: Int,
    val owner: Int,
    val ownKeeper: Int,
    val opposingKeeper: Int,
    val ball: PitchPoint,
    val height: Float,
    val velocity: PitchPoint,
    val players: List<PitchPoint?>,
    val estimatedSelection: Boolean = false,
) {
    val ownPlayers get() = side * 11 until (side + 1) * 11
    val opponents get() = (1 - side) * 11 until (2 - side) * 11
    val centreRestart get() = abs(ball.x) < 0.8f && abs(ball.z) < 0.8f && height < 0.6f && velocity.length() < 0.45f
    val direction: Int get() {
        val ownX = players.getOrNull(if (ownKeeper >= 0) ownKeeper else side * 11)?.x ?: return 0
        val opponentX = players.getOrNull(if (opposingKeeper >= 0) opposingKeeper else (1 - side) * 11)?.x ?: return 0
        return when {
            ownX < -25f && opponentX > 25f -> 1
            ownX > 25f && opponentX < -25f -> -1
            else -> 0
        }
    }

    /** Resolve the tactical view from one snapshot. Geometry is in metres. */
    fun forControl(): BotPlayState {
        val reported = players.getOrNull(owner)
        val keeperHasReach = owner >= 0 && (owner == ownKeeper || owner == opposingKeeper)
        val inReach = reported != null && reported.distance(ball) <= (if (keeperHasReach) 3.2f else 2.8f)
        // A named player can remain in the native field after the ball has moved away.
        // Only infer a new carrier for a slow, reachable ball with clear separation.
        val complete = players.all { it != null }
        val carrier = when {
            inReach -> owner
            owner == -1 -> -1
            complete && height <= 1.35f && velocity.length() <= 7.5f -> closestCarrier()
            else -> -2
        }
        val controlled = when {
            players.getOrNull(selected) != null -> selected
            !complete -> -1
            carrier in ownPlayers -> carrier
            else -> ownPlayers.filter { it != ownKeeper && (ownKeeper >= 0 || it % 11 != 0) }
                .minByOrNull { players[it]!!.distance(ball) } ?: -1
        }
        return if (owner == carrier && selected == controlled) this else copy(owner = carrier, selected = controlled, estimatedSelection = selected < 0 && controlled >= 0)
    }

    private fun closestCarrier(): Int {
        var nearest = -1
        var first = Float.POSITIVE_INFINITY
        var second = Float.POSITIVE_INFINITY
        for ((id, point) in players.withIndex()) {
            val distance = point?.distance(ball) ?: continue
            if (distance < first) {
                second = first
                first = distance
                nearest = id
            } else if (distance < second) {
                second = distance
            }
        }
        return if (first <= 1.45f && second - first >= 0.65f) nearest else -2
    }

    companion object {
        fun decode(values: IntArray?): BotPlayState? {
            if (values == null || values.size != 55) return null
            val side = values[1]
            val selected = values[2]
            if (values[0] <= 0 || side !in 0..1 || selected !in -1..21 ||
                selected >= 0 && selected / 11 != side || values[3] !in -2..21) return null
            return BotPlayState(
                values[0], side, selected, values[3],
                values[4].takeIf { it in 0..21 && it / 11 == side } ?: -1,
                values[5].takeIf { it in 0..21 && it / 11 != side } ?: -1,
                PitchPoint(values[6] / 1000f, values[7] / 1000f), values[8] / 1000f,
                PitchPoint(values[9] / 1000f, values[10] / 1000f),
                List(22) { player ->
                    val offset = 11 + player * 2
                    if (values[offset] == Int.MIN_VALUE || values[offset + 1] == Int.MIN_VALUE) null
                    else PitchPoint(values[offset] / 1000f, values[offset + 1] / 1000f)
                },
            )
        }
    }
}

/** Confirm a close dribble when the engine temporarily loses the possession field.
 * A fast pass crossing a player or a contested touch must remain unowned. */
internal class BotControlTracker {
    private var previous: BotPlayState? = null

    fun reset() { previous = null }

    fun resolve(raw: BotPlayState): BotPlayState {
        val control = raw.forControl()
        val old = previous
        previous = control
        if (control.owner != -2 || old == null || old.side != control.side ||
            control.frame - old.frame !in 1..8 || control.height > 1.1f || old.height > 1.1f ||
            control.velocity.length() > 9.5f || control.players.any { it == null }) return control
        var closest = -1
        var first = Float.POSITIVE_INFINITY
        var second = Float.POSITIVE_INFINITY
        for (id in control.players.indices) {
            val d = control.players[id]!!.distance(control.ball)
            if (d < first) { second = first; first = d; closest = id }
            else if (d < second) second = d
        }
        if (closest < 0 || first > 2.5f || second - first < 0.7f) return control
        val before = old.players[closest] ?: return control
        val current = control.players[closest]!!
        val frames = control.frame - old.frame
        val moved = current - before
        if (before.distance(old.ball) > 2.5f || moved.length() !in 0.08f..(frames * 0.45f) ||
            (control.ball - old.ball - moved).length() > minOf(0.75f, frames * 0.15f)) return control
        val selected = if (control.estimatedSelection && closest in control.ownPlayers) closest else control.selected
        return control.copy(owner = closest, selected = selected)
    }
}

// Android gamepad keys in the pinned game's native input handler.
internal enum class BotAction(val key: Int, val holdMs: Long, val cooldownMs: Long) {
    PASS(96, 140, 480),
    THROUGH(100, 160, 850),
    SHOOT(99, 215, 800),
    CLEAR(97, 260, 850),
    PRESS(96, 175, 300),
    SWITCH(102, 100, 600);

    val defensive get() = this == PRESS || this == SWITCH
}

/** Debounce repeated buttons without making a new ball carrier wait for the previous player. */
internal class BotActionWindow {
    private var until = 0L
    private var previous: BotAction? = null
    private var player = -1

    fun available(now: Long, action: BotAction, control: BotPlayState): Boolean {
        if (action == BotAction.SWITCH && previous == action && player == control.selected)
            return now >= until + 1000
        if (action == BotAction.PRESS && previous == BotAction.SWITCH && player != control.selected)
            return true
        if (action.defensive && previous?.defensive == false && control.owner in control.opponents)
            return true
        return now >= until || (!action.defensive && control.selected >= 0 && control.selected == control.owner &&
            (previous?.defensive == true || player >= 0 && player != control.selected))
    }

    fun sent(now: Long, action: BotAction, selected: Int, centreRestart: Boolean = false) {
        until = now + if (centreRestart && action == BotAction.PASS) 1000L else action.cooldownMs
        previous = action
        player = selected
    }
}

/** Suspend tactical inputs while a scored ball rolls back out of the net. */
internal class GoalCelebration {
    private var lastBall: PitchPoint? = null
    private var lastHeight = 0f
    private var lastFrame = -1
    private var scoredEnd = 0

    fun reset() {
        lastBall = null
        lastFrame = -1
        scoredEnd = 0
    }

    fun observingGoal(state: BotPlayState): Boolean {
        val previous = lastBall
        val ball = state.ball
        val end = if (ball.x < 0f) -1 else 1
        if (state.frame < lastFrame) reset()
        if (scoredEnd != 0) {
            // A centre restart, or the ball leaving the six-yard area back
            // into play, releases the controller even if the park was missed.
            if (ball.x * scoredEnd <= 47f || abs(ball.z) >= 9.16f) scoredEnd = 0
        } else if (previous != null && lastFrame >= 0 && state.frame - lastFrame in 1..27) {
            // Interpolate where the full ball crossed the line. A ball which
            // goes wide and then rolls behind the net is still a goal kick.
            val line = 52.61f
            val from = previous.x * end
            val to = ball.x * end
            if (from < line && to in line..55.5f && abs(ball.z) <= 3.66f && state.height <= 2.44f) {
                val fraction = (line - from) / (to - from)
                val z = previous.z + (ball.z - previous.z) * fraction
                val height = lastHeight + (state.height - lastHeight) * fraction
                if (abs(z) <= 3.55f && height <= 2.33f) scoredEnd = end
            }
        }
        lastBall = ball
        lastHeight = state.height
        lastFrame = state.frame
        return scoredEnd != 0
    }
}

/** Help the host take the opening kickoff while the game is still at centre. */
internal class OpeningKickoff {
    private var ballPlayed = false
    private var attempts = 0
    private var lastAttemptFrame = 0L

    fun shouldPress(canPress: Boolean, home: Boolean, commandFrame: Long, stationaryAtCentre: Boolean): Boolean {
        if (!stationaryAtCentre) ballPlayed = true
        return canPress && home && stationaryAtCentre && !ballPlayed && commandFrame in 1L..180L &&
            attempts < 3 && (attempts == 0 || commandFrame - lastAttemptFrame >= 55)
    }

    fun pressed(commandFrame: Long) {
        attempts++
        lastAttemptFrame = commandFrame
    }
}

internal data class BotStick(val x: Float = 0f, val y: Float = 0f, val sprint: Boolean = false)
internal data class BotPlay(val stick: BotStick = BotStick(), val action: BotAction? = null, val receiver: Int = -1)

internal class BotBrain {
    private val motionX = FloatArray(22)
    private val motionZ = FloatArray(22)
    private var attackDirection = 0

    private var previousState: BotPlayState? = null

    fun resetDirection() {
        attackDirection = 0
        previousState = null
    }

    fun decide(state: BotPlayState): BotPlay {
        val previous = previousState
        previousState = state
        val player = state.players.getOrNull(state.selected)
            ?: return BotPlay(action = BotAction.PRESS.takeIf { state.owner in state.opponents && state.height < 1.7f })
        val toBall = player.distance(state.ball)
        val direction = state.direction
        if (direction != 0) attackDirection = direction

        if (state.centreRestart) {
            if (toBall <= 2.5f && state.owner == state.selected) return kickoffPass(state, player)
            // Let the native restart take place when the other side has the ball.
            if (toBall > 3f && state.opponents.any { (state.players[it]?.distance(state.ball) ?: 100f) < 3f }) return BotPlay()
        }
        if (state.owner == state.selected) return attack(state, player, previous)
        if (state.owner in state.ownPlayers) return support(state, player)
        // An unavailable ownership read still leaves usable ball geometry.
        // Chase or switch players, while pressure requires known opposition possession.
        return defend(state, player, toBall, previous)
    }

    fun kickoffPass(state: BotPlayState, player: PitchPoint): BotPlay {
        var best = BotPlay(action = BotAction.PASS)
        var bestScore = Float.NEGATIVE_INFINITY
        for (id in state.ownPlayers) {
            if (id == state.selected || id == state.ownKeeper) continue
            val target = state.players[id] ?: continue
            val distance = player.distance(target)
            if (distance !in 2.5f..26f || nearerReceiverOnLine(state, id, player, target)) continue
            val lane = clearance(state, player, target)
            if (lane < 1.6f) continue
            val score = minOf(lane, 5f) - distance * 0.4f -
                ((target.x - player.x) * attackDirection).coerceAtLeast(0f) * 0.4f
            if (score > bestScore) {
                bestScore = score
                best = BotPlay(steer(player, target), BotAction.PASS, id)
            }
        }
        return best
    }

    private fun attack(state: BotPlayState, player: PitchPoint, previous: BotPlayState?): BotPlay {
        val goal = PitchPoint(52.5f * attackDirection, 0f)
        val goalDistance = player.distance(goal)
        val isKeeper = state.selected == state.ownKeeper
        var pressure = Float.POSITIVE_INFINITY
        var closingPressure = pressure
        var backLine = Float.NEGATIVE_INFINITY
        var lastBack = backLine
        var seen = 0
        val elapsed = if (previous == null) 0 else state.frame - previous.frame
        for (id in state.players.indices) {
            motionX[id] = 0f
            motionZ[id] = 0f
            val current = state.players[id] ?: continue
            val old = previous?.players?.getOrNull(id) ?: continue
            if (elapsed !in 1..12) continue
            val dx = (current.x - old.x) / elapsed
            val dz = (current.z - old.z) / elapsed
            if (dx * dx + dz * dz <= 0.45f * 0.45f) {
                motionX[id] = dx
                motionZ[id] = dz
            }
        }
        val oldCarrier = previous?.players?.getOrNull(state.selected)
        for (id in state.opponents) {
            val marker = state.players[id] ?: continue
            seen++
            val distance = player.distance(marker)
            pressure = minOf(pressure, distance)
            val oldMarker = previous?.players?.getOrNull(id)
            // Use a short look-ahead in simulation frames to release the ball
            // before an approaching defender is already in tackling range.
            val closing = if (elapsed in 1..12 && oldCarrier != null && oldMarker != null)
                ((oldCarrier.distance(oldMarker) - distance) * 10f / elapsed).coerceIn(0f, 3f) else 0f
            closingPressure = minOf(closingPressure, distance - closing)
            val x = marker.x * attackDirection
            if (x >= lastBack) {
                backLine = lastBack
                lastBack = x
            } else if (x > backLine) backLine = x
        }
        val offsideLine = maxOf(0f, player.x * attackDirection, backLine)
        if (!isKeeper && seen >= 6 && attackDirection != 0 && goalDistance in 1f..23f && state.height < 1.7f) {
            val keeper = state.players.getOrNull(state.opposingKeeper)
            val far = PitchPoint(goal.x, if (keeper != null && keeper.z < 0f) 2.4f else -2.4f)
            val near = PitchPoint(goal.x, -far.z)
            val farLane = clearance(state, player, far, state.opposingKeeper)
            val nearLane = clearance(state, player, near, state.opposingKeeper)
            val shot = if (nearLane > farLane + 0.6f || farLane <= 1.15f && nearLane > 1.15f) near else far
            if (maxOf(farLane, nearLane) > 1.15f)
                return BotPlay(steer(player, shot), BotAction.SHOOT)
        }

        val run = carryLane(state, player)
        val runLength = player.distance(run)
        val runClearance = clearance(state, player, run)
        val canBurst = !isKeeper && attackDirection != 0 && runLength >= 4f && runClearance >= 2.8f
        val forwardRun = (run.x - player.x) * attackDirection
        var receiver: PitchPoint? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var receiverId = -1
        var bestAction = BotAction.PASS
        val ball = state.ball
        for (id in state.ownPlayers) {
            if (id == state.selected || id == state.ownKeeper) continue
            val target = state.players[id] ?: continue
            val distance = player.distance(target)
            if (distance !in 3f..33f || abs(target.z) >= 33f || seen == 0) continue
            if (seen == 11 && target.x * attackDirection > offsideLine + 0.05f) continue

            // Assisted ground passes can go to the receiver's feet even when the stick leads them.
            // Both the direct line and the aimed line must be playable from the actual ball.
            val direct = passingMargin(state, ball, target)
            if (direct < 1.35f) continue
            val leadFrames = (distance * 0.6f).coerceIn(3f, 12f)
            val aim = PitchPoint((target.x + motionX[id] * leadFrames).coerceIn(-51f, 51f),
                (target.z + motionZ[id] * leadFrames).coerceIn(-33f, 33f))
            val width = if (aim == target) direct else minOf(direct, passingMargin(state, ball, aim))
            if (width < 1.35f || nearerReceiverOnLine(state, id, player, aim)) continue
            val space = nearestOpponent(state, aim)
            if (space < 2.5f) continue
            val advance = (target.x - player.x) * attackDirection
            val score = advance.coerceIn(-14f, 19f) * 0.48f + minOf(width, 7f) * 1.1f +
                minOf(space, 9f) * 0.6f - distance * 0.19f
            if (score <= bestScore) continue
            bestScore = score
            receiver = aim
            receiverId = id
            bestAction = BotAction.PASS
            if (!isKeeper && seen == 11 && closingPressure <= 6f && pressure >= 2f &&
                advance in 8f..23f && target.x * attackDirection < offsideLine - 1f && width > 2.8f &&
                motionX[id] * attackDirection >= -0.04f) {
                // A through ball travels beyond the teammate. Check that extra stretch as well.
                val beyond = PitchPoint(aim.x + 4f * attackDirection, aim.z)
                if (abs(beyond.x) < 51f && passingMargin(state, ball, beyond) >= 2.5f &&
                    nearestOpponent(state, beyond) >= 3.5f && !nearerReceiverOnLine(state, id, player, beyond)) {
                    receiver = beyond
                    bestAction = BotAction.THROUGH
                }
            }
        }

        if (receiver != null) {
            // Compare what the teammate actually gains. A through ball's extra lead is
            // empty grass, which the carrier may already be able to reach in stride.
            val advance = (state.players[receiverId]!!.x - player.x) * attackDirection
            val breakaway = attackDirection != 0 && seen == 11 && player.x * attackDirection >= backLine &&
                canBurst && forwardRun > 5f
            val escapingChaser = canBurst && forwardRun >= 6f && advance < 12f && state.opponents.any { id ->
                state.players[id]?.let { (player.x - it.x) * attackDirection > 1f && it.distance(player) < 8f } == true
            }
            if (isKeeper || attackDirection == 0 || !breakaway && !escapingChaser &&
                (closingPressure <= 6f || advance >= 7f || runClearance < 2f)) {
                return BotPlay(steer(player, receiver), bestAction, receiverId)
            }
        }
        if (isKeeper || receiver == null && pressure < 2f && player.x * attackDirection < -30f) {
            if (attackDirection == 0) return BotPlay(action = BotAction.PASS)
            return BotPlay(steer(player, PitchPoint(player.x + 30f * attackDirection, player.z)), BotAction.CLEAR)
        }
        return BotPlay(steer(player, run, sprint = canBurst))
    }

    fun carryBetweenKicks(state: BotPlayState): BotStick {
        val player = state.players.getOrNull(state.selected) ?: return BotStick()
        if (state.owner != state.selected || state.centreRestart || state.selected == state.ownKeeper) return BotStick()
        val run = carryLane(state, player)
        val sprint = attackDirection != 0 && player.distance(run) >= 4f && clearance(state, player, run) >= 2.8f
        return steer(player, run, sprint)
    }

    private fun nearestOpponent(state: BotPlayState, target: PitchPoint): Float {
        var nearest = 100f
        for (id in state.opponents) state.players[id]?.let { nearest = minOf(nearest, it.distance(target)) }
        return nearest
    }

    private fun carryLane(state: BotPlayState, player: PitchPoint): PitchPoint {
        var best = player
        var bestScore = Float.NEGATIVE_INFINITY
        // Forward, diagonal, lateral, and a short turn out on either shoulder.
        for (lane in -3..3) {
            val side = lane.compareTo(0)
            val forward = when (abs(lane)) { 0 -> 10f; 1 -> 7f; 2 -> 1.5f; else -> -4f }
            val sideways = when (abs(lane)) { 0 -> 0f; 1 -> 5.5f; 2 -> 6.5f; else -> 4.5f }
            val target = PitchPoint((player.x + forward * attackDirection).coerceIn(-51f, 51f),
                (player.z + side * sideways).coerceIn(-32.5f, 32.5f))
            if (player.distance(target) < 1.5f) continue
            val laneSpace = clearance(state, player, target)
            val progress = (target.x - player.x) * attackDirection
            val score = minOf(laneSpace, 7f) + minOf(nearestOpponent(state, target), 8f) * 0.55f +
                progress * 0.30f - (abs(target.z) - 27f).coerceAtLeast(0f) * 0.23f
            if (score > bestScore) { bestScore = score; best = target }
        }
        return best
    }

    private fun support(state: BotPlayState, player: PitchPoint): BotPlay {
        if (state.selected == state.ownKeeper || attackDirection == 0) return BotPlay()
        val carrier = state.players[state.owner] ?: return BotPlay()
        if (player.distance(carrier) !in 4f..19f) return BotPlay()
        val space = PitchPoint(
            (carrier.x + 6f * attackDirection).coerceIn(-48f, 48f),
            (carrier.z + if (player.z >= carrier.z) 8f else -8f).coerceIn(-30f, 30f),
        )
        return BotPlay(steer(player, space))
    }

    private fun defend(state: BotPlayState, player: PitchPoint, distance: Float, previous: BotPlayState?): BotPlay {
        val onBall = state.owner in state.opponents
        // During a contested dribble the owner field can be unknown. Cover a nearby attacker
        // without firing a tackle at a loose or airborne ball.
        val threat = when {
            onBall -> state.owner
            state.owner == -2 && state.height < 1.35f && state.velocity.length() <= 8.5f -> {
                var nearest = -1
                var reach = minOf(2.65f, distance + 0.25f)
                for (id in state.opponents) {
                    val d = state.players[id]?.distance(state.ball) ?: continue
                    if (d < reach) { reach = d; nearest = id }
                }
                nearest
            }
            else -> -1
        }
        val lead = if (threat >= 0) projectedRun(state, previous, threat, 7f) else {
            val travel = state.velocity * 0.22f
            travel * (5f / maxOf(5f, travel.length()))
        }
        val keeper = state.ownKeeper.takeIf { it >= 0 } ?: state.ownPlayers.first
        val ownGoal = PitchPoint(-52.5f * attackDirection, 0f)
        val relativeDepth = (player.x - state.ball.x) * attackDirection
        val goalSide = attackDirection == 0 || relativeDepth <= 0.75f
        val pressing = onBall && state.height < 1.7f &&
            (distance <= 2.15f || goalSide && distance < 3.7f)
        val predicted = state.ball + lead
        var target = predicted
        if (threat >= 0 && !pressing && attackDirection != 0) {
            // Recover inside the attacker first. A last defender covering a winger stays in the
            // path to goal and can screen the central runner instead of abandoning the middle.
            val toGoal = ownGoal - predicted
            val goalDistance = maxOf(1f, toGoal.length())
            val lastCover = state.ownPlayers.none { id ->
                id != state.selected && id != keeper && state.players[id]?.let {
                    (it.x - predicted.x) * attackDirection < -2f && abs(it.z) < abs(predicted.z) + 2f
                } == true
            }
            val depth = when {
                !goalSide -> 5f
                lastCover && abs(predicted.z) > 9f -> 5.5f
                lastCover -> 3.5f
                else -> 1.8f
            }
            target = predicted + toGoal * (minOf(depth, goalDistance * 0.65f) / goalDistance)
            if (lastCover && abs(predicted.z) > 9f && predicted.x * attackDirection < -12f) {
                var danger: PitchPoint? = null
                var dangerDistance = goalDistance
                for (id in state.opponents) {
                    if (id == threat || id == state.opposingKeeper) continue
                    val runner = state.players[id] ?: continue
                    if (abs(runner.z) >= abs(predicted.z) - 4f || runner.distance(predicted) > 19f) continue
                    val d = runner.distance(ownGoal)
                    if (d < dangerDistance) { danger = runner; dangerDistance = d }
                }
                if (danger != null) target = target * 0.55f + danger * 0.45f
            }
        }
        target = PitchPoint(target.x.coerceIn(-52f, 52f), target.z.coerceIn(-34f, 34f))
        val myDistance = player.distance(target)
        fun recoveryCost(p: PitchPoint): Float = p.distance(target) + if (threat >= 0 && attackDirection != 0)
            ((p.x - predicted.x) * attackDirection).coerceIn(0f, 8f) * 0.65f else 0f
        val myCost = recoveryCost(player)
        val advantage = if (state.selected == keeper) 2f else maxOf(4.5f, myCost * 0.25f)
        val switch = !pressing && !state.estimatedSelection && distance > 5f && state.ownPlayers.any { other ->
            other != state.selected && other != keeper &&
                state.players[other]?.let { recoveryCost(it) + advantage < myCost } == true
        }
        val action = when {
            pressing -> BotAction.PRESS
            switch -> BotAction.SWITCH
            else -> null
        }
        if (state.selected == keeper && distance > 5f) return BotPlay(action = action)
        return BotPlay(steer(player, target, sprint = !pressing && myDistance > if (goalSide) 5.5f else 3f), action)
    }

    private fun projectedRun(state: BotPlayState, previous: BotPlayState?, id: Int, frames: Float): PitchPoint {
        val old = previous?.players?.getOrNull(id) ?: return noMovement
        val current = state.players[id] ?: return noMovement
        val elapsed = state.frame - previous.frame
        if (elapsed !in 1..12) return noMovement
        val delta = current - old
        // A formation reset can move players discontinuously. It is never a sprint.
        if (delta.length() > elapsed * 0.45f) return noMovement
        return delta * (frames / elapsed)
    }

    private val noMovement = PitchPoint(0f, 0f)

    private fun passingMargin(state: BotPlayState, from: PitchPoint, to: PitchPoint): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val length = maxOf(0.1f, sqrt(dx * dx + dz * dz))
        val travel = length / 15f
        var margin = 100f
        for (id in state.opponents) {
            val marker = state.players[id] ?: continue
            val mx = marker.x - from.x
            val mz = marker.z - from.z
            val projection = (mx * dx + mz * dz) / (length * length)
            if (projection < 0f) continue
            // Check where the defender is when the kick releases, crosses their line, and
            // reaches the receiver. Allow a stationary human to step into the lane as well.
            for (sample in 0..2) {
                val t = when (sample) {
                    0 -> projection.coerceIn(0f, 1f)
                    1 -> ((mx + motionX[id] * 9f) * dx + (mz + motionZ[id] * 9f) * dz)
                        .div(length * length).coerceIn(0f, 1f)
                    else -> 1f
                }
                val seconds = 0.14f + t * travel
                val frames = minOf(seconds * 27f, 12f)
                val x = mx + motionX[id] * frames - t * dx
                val z = mz + motionZ[id] * frames - t * dz
                val reach = (seconds - 0.18f).coerceAtLeast(0f) * 3.8f + t * length * 0.025f
                margin = minOf(margin, sqrt(x * x + z * z) - reach)
            }
        }
        return margin
    }

    private fun nearerReceiverOnLine(state: BotPlayState, receiver: Int, from: PitchPoint, aim: PitchPoint): Boolean {
        val dx = aim.x - from.x
        val dz = aim.z - from.z
        val lengthSquared = maxOf(0.01f, dx * dx + dz * dz)
        for (id in state.ownPlayers) {
            if (id == state.selected || id == receiver) continue
            val teammate = state.players[id] ?: continue
            val x = teammate.x - from.x
            val z = teammate.z - from.z
            val t = (x * dx + z * dz) / lengthSquared
            if (t !in 0.15f..0.82f) continue
            val cross = x * dz - z * dx
            if (cross * cross < 1.5f * 1.5f * lengthSquared) return true
        }
        return false
    }

    private fun steer(from: PitchPoint, to: PitchPoint, sprint: Boolean = false): BotStick {
        val delta = to - from
        val length = delta.length()
        if (length < 0.45f) return BotStick()
        val aim = delta * (1f / maxOf(1.8f, length))
        return BotStick((aim.x * 16).roundToInt() / 16f, (aim.z * 16).roundToInt() / 16f, sprint)
    }

    private fun clearance(state: BotPlayState, from: PitchPoint, to: PitchPoint, skip: Int = -1): Float {
        val dx = to.x - from.x
        val dz = to.z - from.z
        val lengthSquared = dx * dx + dz * dz
        if (lengthSquared < 0.25f) return 0f
        var minimum = 10000f
        for (id in state.opponents) {
            if (id == skip) continue
            val marker = state.players[id] ?: continue
            val x = marker.x - from.x
            val z = marker.z - from.z
            val progress = (x * dx + z * dz) / lengthSquared
            // A chaser behind the passer adds pressure but cannot intercept a ball moving away.
            if (progress < 0f) continue
            val nearest = minOf(progress, 1f)
            val gapX = x - dx * nearest
            val gapZ = z - dz * nearest
            minimum = minOf(minimum, gapX * gapX + gapZ * gapZ)
        }
        return sqrt(minimum)
    }
}
