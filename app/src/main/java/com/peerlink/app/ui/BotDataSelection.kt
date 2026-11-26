package com.peerlink.app.ui

import android.content.Context
import android.net.Uri
import com.peerlink.app.bot.GameDatabaseUpdate
import com.peerlink.app.core.AppState
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

internal data class BotDataState(
    val pack: GameDatabaseUpdate.Pack? = null,
    val busy: String? = null,
    val invalid: Boolean = false,
    val error: String? = null,
) {
    val canCreateRoom get() = busy == null && !invalid
}

/** Owns the import while the Android picker is open or the user changes tabs. */
internal object BotDataSelection {
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val selection = MutableStateFlow(BotDataState(busy = "Checking saved data…"))
    val state = selection.asStateFlow()
    private var started = false

    @Synchronized fun load(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        io.launch {
            selection.value = try {
                BotDataState(pack = GameDatabaseUpdate.selectedPack(app))
            } catch (error: Exception) {
                AppState.appendLog("[BOT-FILES] SAVED_DATABASE_SELECTION unreadable=${error.javaClass.simpleName}")
                BotDataState(invalid = true, error = "PeerLink couldn't verify its saved update.")
            }
        }
    }

    @Synchronized fun import(context: Context, uri: Uri) {
        edit("Verifying the selected file…") {
            val pack = GameDatabaseUpdate.import(context.applicationContext, uri)
            AppState.appendLog("[BOT-FILES] SAVED_DATABASE_IMPORT accepted bytes=${pack.fingerprint.bytes} SHA256=${pack.fingerprint.sha256}")
            BotDataState(pack = pack)
        }
    }

    @Synchronized fun useInstalled(context: Context) {
        edit("Switching to installed data…") {
            GameDatabaseUpdate.remove(context.applicationContext)
            AppState.appendLog("[BOT-FILES] SAVED_DATABASE_SELECTION installed")
            BotDataState()
        }
    }

    private fun edit(message: String, change: () -> BotDataState) {
        val before = selection.value
        if (before.busy != null || !PlayRoomController.state.value.canEditGameData) return
        selection.value = before.copy(busy = message, error = null)
        io.launch {
            selection.value = try {
                change()
            } catch (error: Exception) {
                AppState.appendLog("[BOT-FILES] SAVED_DATABASE_SELECTION rejected=${error.javaClass.simpleName}")
                before.copy(error = when (error) {
                    is GameDatabaseUpdate.UnsupportedArchive -> "This file doesn't match a supported eFootball update."
                    is IOException, is SecurityException -> "Android couldn't read or save the selected file."
                    else -> "PeerLink couldn't change the saved data."
                })
            }
        }
    }
}

internal val RoomState.canEditGameData: Boolean
    get() = this == RoomState.Idle || this is RoomState.Failed || this is RoomState.FilesNeeded
