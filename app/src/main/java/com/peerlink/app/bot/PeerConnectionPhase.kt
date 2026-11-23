package com.peerlink.app.bot

import java.util.concurrent.TimeUnit

/** The server's connection report belongs to the confirmed peer transport.
 * Match-data readiness remains a separate prerequisite for gameplay inputs.
 */
internal object PeerConnectionPhase {
    fun confirmServerConnection(
        running: () -> Boolean,
        report: () -> Map<*, *>,
        poll: () -> Map<*, *>,
        exchangeAddresses: () -> Unit = {},
        progress: (String) -> Unit = {},
        timeoutSeconds: Long = 90,
        pause: () -> Unit = { Thread.sleep(1_000) },
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        var reported = false
        while (running()) {
            exchangeAddresses()
            val response = if (reported) poll() else report()
            if (!running()) return false
            val result = response["result"] as? String
            when (result) {
                null, "_NET_FAIL", "_HTTP_EMPTY", "_DECODE_FAIL" -> Unit
                "NOERR" -> {
                    if (reported) {
                        when (response["is_check_finished"]) {
                            "YES" -> {
                                val error = response["error_code"] as? String
                                check(error == "NOERR") { "The server rejected the room connection check: $error" }
                                progress("SERVER_CONNECTION_CHECK confirmed=true")
                                return true
                            }
                            "NO" -> Unit
                            else -> error("The room connection check returned an invalid completion flag")
                        }
                    } else {
                        reported = true
                        progress("SERVER_CONNECTION_CHECK report=NOERR awaiting_confirmation=true")
                        continue
                    }
                }
                else -> error("The server refused the room connection ${if (reported) "check" else "report"}: $result")
            }
            check(System.nanoTime() < deadline) { "The server did not confirm the room connection in time" }
            pause()
        }
        return false
    }

    fun awaitMetadata(
        running: () -> Boolean,
        snapshot: () -> EngineSnapshot,
        exchangeAddresses: () -> Unit = {},
        timeoutSeconds: Long = 90,
        pause: () -> Unit = { Thread.sleep(100) },
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (running()) {
            exchangeAddresses()
            val state = snapshot()
            check(!state.nativeFailed) { "Bot engine synchronization failed: ${state.nativeFailureReason}" }
            if (state.connected && state.metadataReady) return true
            check(System.nanoTime() < deadline) { "The original team-data synchronization timed out" }
            pause()
        }
        return false
    }

    /** The configured transport consumes the original SDK's network-I/O
     * handles, which are created asynchronously after SDK startup.
     */
    fun openAfterNetworkIo(
        running: () -> Boolean,
        transportIoReady: () -> Int,
        openSession: () -> Boolean,
        progress: (Int) -> Unit = {},
        timeoutSeconds: Long = 90,
        pause: () -> Unit = { Thread.sleep(500) },
    ): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (running()) {
            val state = transportIoReady()
            progress(state)
            check(state >= 0) { "The original SDK network I/O is unavailable (stage $state)" }
            if (state == 1) {
                check(openSession()) { "The original match session did not accept its ready network I/O" }
                return true
            }
            check(System.nanoTime() < deadline) { "The original network I/O startup timed out" }
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
            check(!state.nativeFailed) { "Bot engine connection failed: ${state.nativeFailureReason}. See FIRST_NATIVE_FAILURE in the match log." }
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
