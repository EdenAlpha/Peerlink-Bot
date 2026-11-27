package com.peerlink.app.bot

/** Decide when a recorded room should ask Prime to release other cached apps. */
internal class RoomMemoryPressure {
    private var lowSamples = 0
    private var nextAttempt = 0L
    private var cooldown = 20_000L
    private var lastAttempt = 0L
    private var criticalSample = false
    private var handledCritical = false

    fun shouldReclaim(now: Long, availableMiB: Long, totalMiB: Long, androidLow: Boolean): Boolean {
        if (totalMiB <= 0 || availableMiB < 0) return false
        val target = (totalMiB / 4).coerceIn(384, 1024)
        criticalSample = androidLow || availableMiB <= target / 2
        if (!androidLow && availableMiB >= target) {
            lowSamples = 0
            if (availableMiB >= target + 96) {
                cooldown = 20_000
                handledCritical = false
            }
            return false
        }
        lowSamples = minOf(2, lowSamples + 1)
        // A fruitless sweep at moderate pressure can back off for two minutes.
        // Allow one earlier request if headroom collapses during match loading.
        val escalating = criticalSample && !handledCritical && now - lastAttempt >= 20_000
        return (now >= nextAttempt || escalating) && (androidLow || lowSamples >= 2)
    }

    fun attempted(now: Long, gainMiB: Long) {
        lastAttempt = now
        if (criticalSample) handledCritical = true
        cooldown = if (gainMiB >= 32) 20_000 else minOf(cooldown * 2, 120_000)
        nextAttempt = now + cooldown
        lowSamples = 0
    }
}
