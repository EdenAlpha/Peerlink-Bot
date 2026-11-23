package com.peerlink.app.bot

/** At most three mapping walks, all before play. Used only by the memory worker. */
internal class SetupMemorySamples {
    private var baselineKiB: Long? = null
    private var setupStartMs: Long? = null
    private var growthKiB: Long? = null
    private var complete = false

    fun next(teamsReady: Boolean, inSetup: Boolean, nowMs: Long, pssKiB: Long): String? {
        if (complete || pssKiB <= 0 || (!teamsReady && !inSetup)) return null
        if (inSetup && setupStartMs == null) setupStartMs = nowMs
        val baseline = baselineKiB
        if (baseline == null) {
            baselineKiB = pssKiB
            return "baseline"
        }
        val start = setupStartMs ?: return null
        if (!inSetup) return null
        val growth = growthKiB
        if (growth == null) {
            if (pssKiB - baseline < 48 * 1024 && nowMs - start < 5_000) return null
            growthKiB = pssKiB
            return "setup_loading"
        }
        if (pssKiB - growth < 16 * 1024 && nowMs - start < 10_000) return null
        complete = true
        return "setup_followup"
    }
}
