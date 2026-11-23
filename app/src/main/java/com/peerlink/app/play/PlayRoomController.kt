package com.peerlink.app.play

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.peerlink.app.bot.BotMatchDriver
import com.peerlink.app.bot.BotHeapRecording
import com.peerlink.app.bot.BotMode
import com.peerlink.app.bot.EngineHost
import com.peerlink.app.bot.PeerSlot
import com.peerlink.app.service.PeerBotService
import com.peerlink.app.service.PeerLinkVpnService
import android.content.Intent

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
/** The bot's room service: creates the room and returns its number. */
interface BotRoomBackend {
    /** Create a room as the bot; returns the room number to show the user. */
    suspend fun createRoom(ctx: Context, allowResourceDownload: Boolean = false,
                           verifyEngine: ((Int) -> Unit)? = null): String
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
    data class Ready(val number: String, val note: String = "") : RoomState

    /** Room creation failed (gate down, not logged in, …). */
    data class Failed(val reason: String, val recordMemoryOnRetry: Boolean = false) : RoomState

    data class FilesNeeded(val missingFiles: Int, val missingBytes: Long, val totalFiles: Int,
                           val recordMemoryOnRetry: Boolean = false) : RoomState

    /** The local bot and its connection are still shutting down. */
    data object Ending : RoomState
}

/**
 * Drives the Play PeerLink room flow. Owns the room number end-to-end: the bot
 * creates the room at the gate, this holds the number and hands it to the UI
 * copyable, and clears it when the match is done.
 */
object PlayRoomController {

    private val io = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )
    private val _state = MutableStateFlow<RoomState>(RoomState.Idle)
    val state: StateFlow<RoomState> = _state.asStateFlow()

    /** Set once the bot's gate room-service is wired in. */
    @Volatile
    var backend: BotRoomBackend? = null

    @Volatile
    private var appContext: Context? = null

    /** Attach the bot's room service (called once the UI context exists). */
    fun attach(b: BotRoomBackend, ctx: Context) {
        backend = b
        appContext = ctx.applicationContext
    }

    /**
     * Ask the bot to create a room: login at the gate, CMD_CREATEJOIN_ROOM,
     * surface the returned number. Never invents one on failure.
     */
    @Synchronized fun createRoom(allowResourceDownload: Boolean = false, recordMemory: Boolean = false) {
        if (_state.value is RoomState.Creating || _state.value is RoomState.Ready || _state.value is RoomState.Ending) return
        val be = backend
        val ctx = appContext
        if (be == null || ctx == null) {
            _state.value = RoomState.Failed(
                "The bot's room service isn't linked in yet. This needs the bot " +
                    "logged into Konami's gate — the next piece of work."
            )
            return
        }
        _state.value = RoomState.Creating(if (recordMemory) "Starting the bot memory recorder…" else "The bot is opening a room at Konami…")
        io.launch {
            var recording: String? = null
            var opened = false
            try {
                val verify: ((Int) -> Unit)? = if (recordMemory) {
                    val id = BotHeapRecording.prepare(ctx)
                    recording = id
                    { pid: Int ->
                        preparationNote("Checking that Android recorded a bot allocation…")
                        BotHeapRecording.startAndVerify(ctx, id, pid) { EngineHost.probeHeapRecording(pid) }
                    }
                } else null
                onRoomCreated(be.createRoom(ctx, allowResourceDownload, verify))
                opened = true
            } catch (missing: com.peerlink.app.bot.GameResourcePacks.MissingResources) {
                _state.value = RoomState.FilesNeeded(missing.missingFiles, missing.missingBytes, missing.totalFiles, recordMemory)
            } catch (t: Throwable) {
                _state.value = RoomState.Failed(t.message ?: "Room creation failed.", recordMemory)
            } finally {
                if (!opened) recording?.let { id -> runCatching { BotHeapRecording.finish(ctx, id, "preparation_ended") } }
            }
        }
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

    fun preparationNote(note: String) {
        _state.update { current ->
            when (current) {
                is RoomState.Creating -> current.copy(note = note)
                is RoomState.Ready -> current.copy(note = note)
                else -> current
            }
        }
    }

    /** End the local bot session before allowing another room to be opened. */
    @Synchronized fun endSession() {
        if (_state.value is RoomState.Creating || _state.value is RoomState.Ending) return
        val ctx = appContext ?: return
        _state.value = RoomState.Ending
        io.launch {
            try {
                if (BotMode.active) {
                    // This is a deliberate end, so a later service restart
                    // must not resurrect the old VPN session.
                    ctx.getSharedPreferences(PeerLinkVpnService.SESSION_STATE_PREFS, Context.MODE_PRIVATE)
                        .edit().clear().commit()
                }
                val serviceRunning = PeerBotService.running
                PeerBotService.stop(ctx)
                if (!serviceRunning) {
                    BotMatchDriver.stop("user_end_session")
                    PeerSlot.disarm("user_end_session")
                    if (BotMode.active) ctx.stopService(Intent(ctx, PeerLinkVpnService::class.java))
                    BotMode.stop()
                    if (EngineHost.isReady) EngineHost.stop("user_end_session")
                }
                val stopped = withTimeoutOrNull(45_000L) {
                    while (PeerBotService.running || EngineHost.isReady) delay(100L)
                    true
                } == true
                _state.value = if (stopped) RoomState.Idle
                else RoomState.Failed("The bot is still closing. Wait a moment, then try again.")
            } catch (error: Exception) {
                _state.value = RoomState.Failed(error.message ?: "Couldn't end the bot session.")
            }
        }
    }

    /** Copy the room number so it can be typed into eFootball. */
    fun copyNumber(ctx: Context, number: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("peerlink-room", number))
    }
}
