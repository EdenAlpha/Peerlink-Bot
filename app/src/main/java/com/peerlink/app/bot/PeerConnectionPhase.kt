package com.peerlink.app.bot

import java.util.concurrent.TimeUnit

/** The server's connection report belongs to the confirmed peer transport.
 * Match-data readiness remains a separate prerequisite for gameplay inputs.
 */
internal object PeerConnectionPhase {
    /** The configured transport consumes an already connected original NTL
     * introduction. Starting its session before that verdict is rejected.
     */
    fun openAfterIntroduction(
        running: () -> Boolean,
        sdkVerdict: () -> Long,
        openSession: () -> Boolean,
        progress: (Long) -> Unit = {},
        timeoutSeconds: Long = 90,
        pause: () -> Unit = { Thread.sleep(500) },
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (running()) {
            val verdict = sdkVerdict()
            progress(verdict)
            if (verdict == 1L) {
                check(openSession()) { "The original match session did not accept the completed peer introduction" }
                return true
            }
            check(System.nanoTime() < deadline) { "The initial peer handshake timed out" }
            pause()
        }
        return false
    }

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
