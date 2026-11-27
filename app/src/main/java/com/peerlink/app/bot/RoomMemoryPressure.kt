package com.peerlink.app.bot

/** Decide when a recorded room should ask Prime to release other cached apps. */
internal class RoomMemoryPressure {
    private var lowSamples = 0
    private var nextAttempt = 0L
    private var cooldown = 20_000L

    fun shouldReclaim(now: Long, availableMiB: Long, totalMiB: Long, androidLow: Boolean): Boolean {
        if (totalMiB <= 0 || availableMiB < 0) return false
        val target = (totalMiB / 4).coerceIn(384, 1024)
        if (!androidLow && availableMiB >= target) {
            lowSamples = 0
            if (availableMiB >= target + 96) cooldown = 20_000
            return false
        }
        lowSamples = minOf(2, lowSamples + 1)
        return now >= nextAttempt && (androidLow || lowSamples >= 2)
    }

    fun attempted(now: Long, gainMiB: Long) {
        cooldown = if (gainMiB >= 32) 20_000 else minOf(cooldown * 2, 120_000)
        nextAttempt = now + cooldown
        lowSamples = 0
    }
}
