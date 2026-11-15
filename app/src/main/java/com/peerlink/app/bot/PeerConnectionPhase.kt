package com.peerlink.app.bot

import java.util.concurrent.TimeUnit

/** The server's connection report belongs to the confirmed peer transport.
 * Match-data readiness remains a separate prerequisite for gameplay inputs.
 */
internal object PeerConnectionPhase {
    fun awaitTransport(
        running: () -> Boolean,
        snapshot: () -> EngineSnapshot,
        prepareChannels: () -> Boolean,
        progress: (EngineSnapshot) -> Unit,
        exchangeAddresses: () -> Unit = {},
        timeoutSeconds: Long = 90,
        pause: () -> Unit = { Thread.sleep(500) },
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var prepared = false
        while (running()) {
            exchangeAddresses()
            val state = snapshot()
            progress(state)
            if (!prepared && state.sessionConnected) {
                check(prepareChannels()) { "The bot's match-data channel setup was rejected" }
                prepared = true
                continue
            }
            if (prepared && state.transportConnected) return true
            check(System.nanoTime() < deadline) { "The peer connection timed out before the native transport connected" }
            pause()
        }
        return false
    }
}
