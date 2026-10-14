package com.peerlink.app.play

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The state of an eFootball match room as far as Play PeerLink is concerned.
 *
 * A "room" is the match room Konami issues from inside eFootball. PeerLink does
 * not mint the number itself — the game creates the room and shows the code.
 * This controller launches the game for you, holds the code you take from it,
 * shows it copyable in the UI, and keeps it as the handle the on-phone bot uses
 * to join as your opponent through the PeerLink VPN.
 */
sealed interface RoomState {
    /** No room yet. */
    data object Idle : RoomState

    /** eFootball was launched so the room can be created in-game. */
    data class Creating(val note: String) : RoomState

    /** A room code is held and shown copyable. */
    data class Ready(val number: String) : RoomState

    /** Something went wrong (game missing, blank code, …). */
    data class Failed(val reason: String) : RoomState
}

/**
 * Drives the Play PeerLink room flow. Owns the room code end-to-end:
 * launch the game → you copy the code out of eFootball → hold + show it →
 * the on-phone bot (next step) joins that room as the far peer.
 */
object PlayRoomController {

    private const val EFOOTBALL = "jp.konami.pesam"

    private val _state = MutableStateFlow<RoomState>(RoomState.Idle)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    /**
     * Open eFootball so the room can be created in-game. PeerLink stays out of
     * the game's menus (they change per update); it only gets you there and
     * waits for the code.
     */
    fun launchGameToCreateRoom(ctx: Context) {
        _state.value = RoomState.Creating(
            "Open eFootball → create a match room, then copy its code back here."
        )
        val launch = ctx.packageManager.getLaunchIntentForPackage(EFOOTBALL)
        if (launch == null) {
            _state.value = RoomState.Failed("eFootball is not installed on this phone.")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { ctx.startActivity(launch) }.onFailure {
            _state.value = RoomState.Failed("Could not open eFootball. Launch it from your home screen.")
        }
    }

    /**
     * Hold the room code taken from the game. Digits and letters only — eFootball
     * codes are short alphanumeric strings, so this strips any stray spaces or
     * punctuation picked up while copying.
     */
    fun setRoomNumber(raw: String) {
        val number = raw.filter { it.isLetterOrDigit() }
        _state.value = if (number.isBlank()) {
            RoomState.Failed("Enter the room code shown in eFootball.")
        } else {
            RoomState.Ready(number)
        }
    }

    /** Drop the held room and return to idle. */
    fun clear() {
        _state.value = RoomState.Idle
    }

    /** Copy the room code to the clipboard so it can be shared or pasted. */
    fun copyNumber(ctx: Context, number: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("peerlink-room", number))
    }
}
