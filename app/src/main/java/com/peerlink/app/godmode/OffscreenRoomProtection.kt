package com.peerlink.app.godmode

import android.content.Context
import android.content.pm.PackageManager
import com.peerlink.app.bot.EngineHost
import com.peerlink.app.core.AppState
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Keeps the room visible to Android on a shell-owned display during play. */
internal object OffscreenRoomProtection {
    private class Lease(val room: String, val script: String) {
        val token = UUID.randomUUID().toString().replace("-", "")
        val shown = CompletableDeferred<Int>()
        @Volatile var stopping = false
        var sampler: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operations = Mutex()
    @Volatile private var current: Lease? = null

    suspend fun prepare(ctx: Context, room: String, mainTask: Int): Boolean = withContext(Dispatchers.IO) {
        operations.withLock {
            if (!roomReady(room) || mainTask <= 0) return@withLock false
            current?.let {
                if (it.room == room && !it.stopping && it.sampler?.isActive == true) return@withLock true
                close(it)
            }
            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
                log("Android does not support activities on a second display")
                return@withLock false
            }
            val script = ctx.assets.open("prime-room-display.sh").bufferedReader().use { it.readText() }
            val lease = Lease(room, script)
            current = lease
            var keep = false
            try {
                val apk = "'" + ctx.applicationInfo.sourceDir.replace("'", "'\"'\"'") + "'"
                val reply = command(lease, "start", 12_000, "$apk $mainTask")
                val id = reply?.lineSequence()?.firstOrNull { it.startsWith("DISPLAY_ID=") }
                    ?.substringAfter('=')?.toIntOrNull()
                val shown = id != null && withTimeoutOrNull(1_500) { lease.shown.await() } == id
                keep = shown && !lease.stopping && roomReady(room)
                if (!keep) {
                    log("Off-screen setup did not finish display=${id ?: "unavailable"} activityStarted=$shown")
                    return@withLock false
                }
                log("Off-screen display $id opened for room $room")
                lease.sampler = scope.launch { sample(lease) }
                true
            } finally {
                if (!keep) withContext(NonCancellable) { close(lease) }
            }
        }
    }

    fun roomOnDisplay(token: String?, display: Int): String? = current?.takeIf {
        display > 0 && !it.stopping && it.token == token && roomReady(it.room)
    }?.room

    fun activityShown(token: String?, display: Int) {
        current?.takeIf { it.token == token && roomOnDisplay(token, display) != null }?.shown?.complete(display)
    }

    fun stop() {
        val lease = current ?: return
        lease.stopping = true
        scope.launch {
            operations.withLock {
                if (current === lease) close(lease)
            }
        }
    }

    private suspend fun sample(lease: Lease) {
        delay(3_000)
        var missed = 0
        while (currentCoroutineContext().isActive && !lease.stopping && roomReady(lease.room)) {
            val line = command(lease, "sample", 5_500, EngineHost.enginePid.coerceAtLeast(0).toString())
                ?.lineSequence()?.firstOrNull { it.startsWith("DISPLAY_SAMPLE ") }
            if (line == null) {
                if (++missed == 2) break
            } else {
                missed = 0
                AppState.appendFileOnly("[BOT-PRIORITY] ${line.removePrefix("DISPLAY_SAMPLE ")}")
            }
            delay(20_000)
        }
        if (current === lease && !lease.stopping) stop()
    }

    private fun close(lease: Lease) {
        lease.stopping = true
        lease.sampler?.cancel()
        val closed = command(lease, "stop", 4_000)?.lineSequence()?.any { it == "DISPLAY_CLOSED" } == true
        if (current === lease) current = null
        log(if (closed) "Off-screen display closed" else "Display lease renewal ended; Android will release it on expiry")
    }

    private fun command(lease: Lease, mode: String, timeout: Int, args: String = ""): String? =
        PrimeClient.executeQuiet("set -- $mode ${lease.token} $args\n${lease.script}", timeout)

    internal fun roomReady(number: String): Boolean = (PlayRoomController.state.value as? RoomState.Ready)
        ?.let { it.number == number && it.recoveryReason == null } == true

    private fun log(message: String) = AppState.appendLog("[BOT-PRIORITY] $message")
}
