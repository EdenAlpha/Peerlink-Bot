package com.peerlink.app.bot

/** Reports observations, not a guessed remote disconnect initiator. No Android
 * dependencies, native calls, packet contents, account data or credentials. */
internal class NativeConnectionTrace {
    data class Event(val text: String, val firstFailure: Boolean = false)
    private var previous: EngineSnapshot? = null
    private var previousAt = 0L
    private var lastReportAt = 0L
    private var failed = false

    fun sample(state: EngineSnapshot, observedAtMs: Long): List<Event> {
        val before = previous
        val changed = before == null || state.connectionFields() != before.connectionFields()
        val firstFailure = state.nativeFailed && !failed
        val events = mutableListOf<Event>()
        if (firstFailure) {
            failed = true
            events += Event("FIRST_NATIVE_FAILURE observedAtMs=$observedAtMs " +
                "previousSampleGapMs=${if (before == null) -1 else observedAtMs - previousAt} " +
                "observedIn=bot_engine disconnectInitiator=unresolved " +
                "reason=${state.nativeFailureReason}; ${state.connectionDetails()} " +
                "previous={${before?.connectionDetails() ?: "unavailable"}}", true)
        }
        if (changed || observedAtMs - lastReportAt >= 5000) {
            events += Event("NATIVE_STATE observedAtMs=$observedAtMs ${state.connectionDetails()}")
            lastReportAt = observedAtMs
        }
        previous = EngineSnapshot(state.values.copyOf())
        previousAt = observedAtMs
        return events
    }
}

private fun EngineSnapshot.number(index: Int) = values.getOrElse(index) { 0 }
internal fun EngineSnapshot.connectionFields() =
    (listOf(0, 1, 2, 3, 4, 5, 6, 7, 19, 21, 22) + (23..28)).map { number(it) }

internal val EngineSnapshot.nativeFailed: Boolean
    get() = number(7) != 0L || number(5) == 6L || number(3) == 6L || number(4) == 6L

// Build 11.1.1: 7a1f508 initializes the model; 7a1edd4 records these
// reasons at a5bb198. 7a2131c result 4 compares player/coach DB checksums.
internal val EngineSnapshot.nativeFailureReason: String
    get() = when (number(23)) {
        1L -> "match-data transport failed or its handler was lost; initiating side unknown"
        3L -> "native network-role lookup failed"
        4L -> "native match-data handler initialization failed"
        5L -> "native model channel setup failed"
        6L -> "native match-data synchronization timed out"
        7L -> "native match-data validation returned error 3"
        8L -> "native player/coach database checksum comparison failed"
        0L -> "native connection failed without a recorded detailed reason"
        else -> "unmapped native reason ${number(23)}"
    }

internal fun EngineSnapshot.connectionDetails(): String =
    "session=${number(2)} ch14=${number(3)} ch15=${number(4)} " +
        "modelPhase=${number(5)} modelComplete=${number(6)} modelError=${number(7)} " +
        "reasonCode=${if (values.size > 23) number(23) else "unavailable"} " +
        "syncResult=${number(24)} checkDatabase=${number(25)} modelPresent=${number(26)} " +
        "networkRole=${number(27)} modelUsers=${number(28)} handler=${number(19)} " +
        "transportKind=${number(21)} turnState=${number(22)} frame=${number(20)} pssKiB=${number(18)}"
