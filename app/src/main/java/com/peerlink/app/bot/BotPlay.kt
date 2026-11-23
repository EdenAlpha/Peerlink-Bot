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
        return now >= until || (!action.defensive && control.selected >= 0 && control.selected == control.owner &&
            (previous?.defensive == true || player >= 0 && player != control.selected))
    }

    fun sent(now: Long, action: BotAction, selected: Int) {
        until = now + action.cooldownMs
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
        val ballStill = state.velocity.length() < 0.45f
        val center = abs(state.ball.x) < 0.8f && abs(state.ball.z) < 0.8f && state.height < 0.6f
        val direction = state.direction
        if (direction != 0) attackDirection = direction

        if (center && ballStill) {
            if (toBall <= 2.5f && state.owner == state.selected) return BotPlay(action = BotAction.PASS)
            // Let the native restart take place when the other side has the ball.
            if (toBall > 3f && state.opponents.any { (state.players[it]?.distance(state.ball) ?: 100f) < 3f }) return BotPlay()
        }
        if (state.owner == state.selected) return attack(state, player, previous)
        if (state.owner in state.ownPlayers) return support(state, player)
        // An unavailable ownership read still leaves usable ball geometry.
        // Chase or switch players, while pressure requires known opposition possession.
        return defend(state, player, toBall, previous)
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
            if (maxOf(farLane, nearLane) > 1.15f || goalDistance < 9f && pressure < 3.5f)
                return BotPlay(steer(player, shot), BotAction.SHOOT)
        }

        var receiver: PitchPoint? = null
        var bestScore = Float.NEGATIVE_INFINITY
        var bestLane = 0f
        var receiverId = -1
        for (id in state.ownPlayers) {
            if (id == state.selected || id == state.ownKeeper) continue
            val target = state.players[id] ?: continue
            val distance = player.distance(target)
            if (distance !in 3f..33f || abs(target.z) >= 33f || seen == 0) continue
            if (seen == 11 && target.x * attackDirection > offsideLine + 0.05f) continue
            val aim = PitchPoint((target.x + motionX[id] * 9f).coerceIn(-51f, 51f),
                (target.z + motionZ[id] * 9f).coerceIn(-33f, 33f))
            val width = passingMargin(state, player, aim)
            if (width <= 1.1f) continue
            var space = Float.POSITIVE_INFINITY
            for (opponent in state.opponents) {
                val marker = state.players[opponent] ?: continue
                space = minOf(space, marker.distance(aim))
            }
            if (space < 2.3f || nearerReceiverOnLine(state, id, player, aim)) continue
            val advance = (target.x - player.x) * attackDirection
            val score = advance.coerceIn(-14f, 19f) * 0.62f + minOf(width, 6f) * 0.65f +
                minOf(space, 9f) * 0.4f - distance * 0.17f
            if (score > bestScore) {
                bestScore = score
                receiver = aim
                receiverId = id
                bestLane = width
            }
        }

        if (receiver != null) {
            val advance = (receiver.x - player.x) * attackDirection
            val through = !isKeeper && seen == 11 && closingPressure <= 6f && pressure >= 2f &&
                advance in 8f..23f && receiver.x * attackDirection < offsideLine - 1f && bestLane > 2.8f
            if (isKeeper || closingPressure <= 6f || advance >= 7f || attackDirection == 0)
                return BotPlay(steer(player, receiver), if (through) BotAction.THROUGH else BotAction.PASS, receiverId)
        }
        if (isKeeper || receiver == null && pressure < 2f && player.x * attackDirection < -30f) {
            if (attackDirection == 0) return BotPlay(action = BotAction.PASS)
            return BotPlay(steer(player, PitchPoint(player.x + 30f * attackDirection, player.z)), BotAction.CLEAR)
        }

        // Carry into space on the first decision. Compare a straight run
        // with two angled runs so a blocked passing lane still has an outlet.
        var bestRun = player
        var runScore = Float.NEGATIVE_INFINITY
        for (angle in -2..2) {
            val x = (player.x + (when (abs(angle)) { 0 -> 10f; 1 -> 6f; else -> 0f }) * attackDirection).coerceIn(-51f, 51f)
            val z = (player.z * 0.92f + angle.coerceIn(-1, 1) * 8f).coerceIn(-32f, 32f)
            val target = PitchPoint(x, z)
            val length = player.distance(target)
            if (length < 1f) continue
            val score = minOf(clearance(state, player, target), 9f) +
                (x - player.x) * attackDirection * 0.22f - abs(z) * 0.025f
            if (score > runScore) {
                runScore = score
                bestRun = target
            }
        }
        return BotPlay(steer(player, bestRun, sprint = closingPressure > 4f && player.distance(bestRun) > 4f))
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
        val lead = if (onBall) projectedRun(state, previous, state.owner, 7f) else {
            val travel = state.velocity * 0.22f
            travel * (5f / maxOf(5f, travel.length()))
        }
        val pressing = onBall && distance < 3.7f && state.height < 1.7f
        val cover = if (onBall && !pressing) -attackDirection * 1.5f else 0f
        val target = PitchPoint((state.ball.x + lead.x + cover).coerceIn(-52f, 52f),
            (state.ball.z + lead.z).coerceIn(-34f, 34f))
        val keeper = state.ownKeeper.takeIf { it >= 0 } ?: state.ownPlayers.first
        val myDistance = player.distance(target)
        val advantage = if (state.selected == keeper) 2f else maxOf(4.5f, myDistance * 0.25f)
        val switch = !pressing && !state.estimatedSelection && distance > 5f && state.ownPlayers.any { other ->
            other != state.selected && other != keeper &&
                state.players[other]?.let { it.distance(target) + advantage < myDistance } == true
        }
        val action = when {
            pressing -> BotAction.PRESS
            switch -> BotAction.SWITCH
            else -> null
        }
        if (state.selected == keeper && distance > 5f) return BotPlay(action = action)
        return BotPlay(steer(player, target, sprint = !pressing && myDistance > 5.5f), action)
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
        val travel = maxOf(0.1f, sqrt(dx * dx + dz * dz) / 17f)
        val leadFrames = minOf(14f, travel * 27f)
        var margin = 100f
        for (id in state.opponents) {
            val marker = state.players[id] ?: continue
            val x = marker.x - from.x
            val z = marker.z - from.z
            val vx = dx - motionX[id] * leadFrames
            val vz = dz - motionZ[id] * leadFrames
            val closest = (x * vx + z * vz) / maxOf(0.01f, vx * vx + vz * vz)
            if (closest < 0f) continue
            val t = minOf(closest, 1f)
            val gapX = x - t * vx
            val gapZ = z - t * vz
            val reach = maxOf(0f, t * travel - 0.22f) * 2.7f
            margin = minOf(margin, sqrt(gapX * gapX + gapZ * gapZ) - reach)
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
