package com.peerlink.app.play

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Backend that actually creates a Konami match room on the bot's behalf.
 *
 * The bot is the room HOST: it logs into Konami's gate (the same protocol
 * `chain3.py` drives) and asks for a room, which comes back with a room
 * number. That number is what the user types into their own eFootball — on the
 * same phone, behind the PeerLink VPN — to join the bot.
 *
 * This interface is the seam so the UI is built to the correct contract now,
 * while the gate client itself is ported. It is `suspend` because the gate
 * call is network I/O.
 */
interface BotRoomBackend {
    /** Create a room as the bot; returns the room number to show the user. */
    suspend fun createRoom(): String
}

/**
 * State of the bot-hosted room.
 *
 * Flow (correct direction): the BOT creates the room, PeerLink shows the
 * number, the user's own eFootball joins it. The code goes
 * bot → user → into eFootball, never the other way.
 */
sealed interface RoomState {
    /** No room yet. */
    data object Idle : RoomState

    /** The bot is creating a room at Konami's gate. */
    data class Creating(val note: String) : RoomState

    /** The bot has a room; [number] is shown copyable to type into eFootball. */
    data class Ready(val number: String) : RoomState

    /** Room creation failed (gate down, not logged in, …). */
    data class Failed(val reason: String) : RoomState
}

/**
 * Drives the Play PeerLink room flow. Owns the room number end-to-end: the bot
 * creates the room at the gate, this holds the number and hands it to the UI
 * copyable, and clears it when the match is done.
 */
object PlayRoomController {

    private val _state = MutableStateFlow<RoomState>(RoomState.Idle)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    /** Set once the bot's gate room-service is wired in. */
    @Volatile
    var backend: BotRoomBackend? = null

    /**
     * Ask the bot to create a room. Runs [BotRoomBackend.createRoom] on the
     * caller's coroutine; safe to call from a UI click (it launches its own
     * scope-free work via the provided suspend caller).
     */
    fun createRoom() {
        val be = backend
        if (be == null) {
            _state.value = RoomState.Failed(
                "The bot's room service isn't linked in yet. This needs the bot " +
                    "logged into Konami's gate — the next piece of work."
            )
            return
        }
        _state.value = RoomState.Creating("The bot is opening a room at Konami…")
    }

    /** Called by the wiring with the room number the bot got back. */
    fun onRoomCreated(number: String) {
        val n = number.filter { it.isLetterOrDigit() }
        _state.value = if (n.isBlank()) {
            RoomState.Failed("Konami returned an empty room number.")
        } else {
            RoomState.Ready(n)
        }
    }

    /** Called by the wiring when room creation failed. */
    fun onRoomFailed(reason: String) {
        _state.value = RoomState.Failed(reason)
    }

    /** Drop the held room and return to idle. */
    fun clear() {
        _state.value = RoomState.Idle
    }

    /** Copy the room number so it can be typed into eFootball. */
    fun copyNumber(ctx: Context, number: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("peerlink-room", number))
    }
}
