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
import com.peerlink.app.bot.BotMatchDiagnostics
import com.peerlink.app.bot.BotMode
import com.peerlink.app.bot.EngineHost
import com.peerlink.app.bot.PeerSlot
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.OffscreenRoomProtection
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
                           verifyEngine: ((Int) -> Unit)? = null,
                           onMatchStarting: (() -> Unit)? = null): String
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
    data class Ready(val number: String, val note: String = "", val diagnosticsNote: String? = null,
                     val recoveryReason: String? = null, val offscreenNote: String? = null) : RoomState

    /** Room creation failed (gate down, not logged in, …). */
    data class Failed(val reason: String, val clearRamOnRetry: Boolean = false) : RoomState

    data class FilesNeeded(val missingFiles: Int, val missingBytes: Long, val totalFiles: Int,
                           val clearRamOnRetry: Boolean = false) : RoomState

    /** The local bot and its connection are still shutting down. */
    data object Ending : RoomState
}

/**
 * Drives the Play PeerLink room flow. Owns the room number end-to-end: the bot
 * creates the room at the gate, this holds the number and hands it to the UI
 * copyable, and clears it when the match is done.
 */
object PlayRoomController {
    private data class RoomOptions(val allowResourceDownload: Boolean, val recordMemory: Boolean, val clearRam: Boolean)


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
    private var roomOptions = RoomOptions(allowResourceDownload = false, recordMemory = false, clearRam = false)

    /** Attach the bot's room service (called once the UI context exists). */
    fun attach(b: BotRoomBackend, ctx: Context) {
        backend = b
        appContext = ctx.applicationContext
    }

    fun createRoomForPlay(clearRam: Boolean) {
        val detailed = clearRam && appContext?.let(BotHeapRecording::enabledForRooms) == true
        createRoom(recordMemory = detailed, clearRam = clearRam)
    }

    /**
     * Ask the bot to create a room: login at the gate, CMD_CREATEJOIN_ROOM,
     * surface the returned number. Never invents one on failure.
     */
    @Synchronized fun createRoom(allowResourceDownload: Boolean = false, recordMemory: Boolean = false, clearRam: Boolean = recordMemory) {
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
        val cleanup = clearRam || recordMemory
        roomOptions = RoomOptions(allowResourceDownload, recordMemory, cleanup)
        _state.value = RoomState.Creating(if (cleanup) "Freeing RAM before loading the bot…" else "The bot is opening a room at Konami…")
        io.launch {
            val cleanupId = if (cleanup) BotHeapRecording.newId() else null
            val recording = cleanupId?.takeIf { recordMemory }
            var heapVerified = false
            var cleanupNote: String? = null
            var heapNote: String? = null
            var opened = false
            try {
                if (cleanupId != null) {
                    when (BotMatchDiagnostics.reclaimBeforeRoom(ctx, cleanupId)) {
                        BotMatchDiagnostics.RamCleanup.PRIME_UNAVAILABLE ->
                            error("Activate Prime in Settings, then tap Clear RAM and create room.")
                        BotMatchDiagnostics.RamCleanup.INCOMPLETE ->
                            cleanupNote = "RAM cleanup was incomplete. See Export diagnostics for details."
                        BotMatchDiagnostics.RamCleanup.COMPLETED -> Unit
                    }
                    BotMatchDiagnostics.startMemoryGuard(ctx, cleanupId, io)
                    preparationNote(cleanupNote ?: "Opening the bot’s room…")
                }
                val verify: ((Int) -> Unit)? = recording?.let { id ->
                    heapNote = BotHeapRecording.prepare(ctx, id)
                    preparationNote(heapNote ?: cleanupNote ?: "Opening the bot’s room…")
                    if (heapNote != null) return@let null
                    val attach: (Int) -> Unit = { pid ->
                        preparationNote("Checking the bot's memory recording…")
                        heapNote = BotHeapRecording.startAndVerify(ctx, id, pid) { EngineHost.probeHeapRecording(pid) }
                        check(pid > 0 && EngineHost.isReady && EngineHost.enginePid == pid) {
                            EngineHost.lastError.ifBlank { "The bot engine stopped during room setup." }
                        }
                        heapVerified = heapNote == null
                        heapNote?.let { preparationNote(it) }
                    }
                    attach
                }
                val startScheduler = recording?.let { id ->
                    val start: () -> Unit = {
                        val pid = EngineHost.enginePid
                        io.launch {
                            if (EngineHost.isReady && pid == EngineHost.enginePid &&
                                !BotMatchDiagnostics.startScheduler(ctx, id, pid, heapVerified))
                                preparationNote("Scheduler recording failed. Export diagnostics for details.")
                        }
                    }
                    start
                }
                val number = be.createRoom(ctx, allowResourceDownload, verify, startScheduler)
                val diagnosticsNote = listOfNotNull(cleanupNote, heapNote).joinToString(" ").ifBlank { null }
                onRoomCreated(number, diagnosticsNote)
                opened = true
                val ready = _state.value as? RoomState.Ready
                if (ready != null && (cleanup || AppState.isGodModeActive.get())) {
                    OffscreenRoomProtection.startForRoom(ctx, ready.number)
                }
            } catch (missing: com.peerlink.app.bot.GameResourcePacks.MissingResources) {
                _state.value = RoomState.FilesNeeded(missing.missingFiles, missing.missingBytes, missing.totalFiles, cleanup)
            } catch (t: Throwable) {
                _state.value = RoomState.Failed(t.message ?: "Room creation failed.", cleanup)
            } finally {
                if (!opened && cleanup) {
                    BotMatchDiagnostics.stopMemoryGuard()
                    recording?.let { id -> runCatching { BotHeapRecording.finish(ctx, id, "preparation_ended") } }
                }
            }
        }
    }

    /** Called by the wiring with the room number the bot got back. */
    fun onRoomCreated(number: String, diagnosticsNote: String? = null) {
        val n = number.filter { it.isLetterOrDigit() }
        _state.value = if (n.isBlank()) {
            RoomState.Failed("Konami returned an empty room number.")
        } else {
            RoomState.Ready(n, diagnosticsNote = diagnosticsNote)
        }
    }

    /** Called by the wiring when room creation failed. */
    fun onRoomFailed(reason: String) {
        _state.value = RoomState.Failed(reason)
        OffscreenRoomProtection.stop()
        BotMatchDiagnostics.stopMemoryGuard()
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

    internal fun offscreenNote(number: String, note: String) {
        _state.update { current ->
            if (current is RoomState.Ready && current.number == number && current.recoveryReason == null)
                current.copy(offscreenNote = note)
            else current
        }
    }

    internal fun roomRecovery(number: String, reason: String?) {
        val code = number.filter { it.isLetterOrDigit() }
        _state.update { current ->
            if (current is RoomState.Ready && current.number == code) current.copy(recoveryReason = reason)
            else current
        }
    }

    @Synchronized internal fun replaceRoom(number: String) {
        val current = _state.value as? RoomState.Ready ?: return
        if (current.number == number && current.recoveryReason != null) stopSession(replacement = roomOptions)
    }

    /** End the local bot session before allowing another room to be opened. */
    @Synchronized fun endSession() = stopSession()

    private fun stopSession(replacement: RoomOptions? = null) {
        if (_state.value is RoomState.Creating || _state.value is RoomState.Ending) return
        val ctx = appContext ?: return
        _state.value = RoomState.Ending
        OffscreenRoomProtection.stop()
        BotMatchDiagnostics.stopMemoryGuard()
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
                    while (PeerBotService.running || EngineHost.isReady || !BotMatchDriver.isIdle) delay(100L)
                    true
                } == true
                synchronized(this@PlayRoomController) {
                    _state.value = if (stopped) RoomState.Idle
                    else RoomState.Failed("The bot is still closing. Wait a moment, then try again.",
                        clearRamOnRetry = replacement?.clearRam == true)
                    if (stopped && replacement != null) {
                        createRoom(replacement.allowResourceDownload, replacement.recordMemory, replacement.clearRam)
                    }
                }
            } catch (error: Exception) {
                _state.value = RoomState.Failed(error.message ?: "Couldn't end the bot session.",
                    clearRamOnRetry = replacement?.clearRam == true)
            }
        }
    }

    /** Copy the room number so it can be typed into eFootball. */
    fun copyNumber(ctx: Context, number: String) {
        val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("peerlink-room", number))
    }
}
