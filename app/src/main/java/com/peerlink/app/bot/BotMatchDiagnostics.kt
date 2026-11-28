package com.peerlink.app.bot

import android.content.Context
import android.app.ActivityManager
import android.os.SystemClock
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker
import com.peerlink.app.godmode.GodModeManager
import com.peerlink.app.godmode.PrimeClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal object BotMatchDiagnostics {
    private const val DIRECTORY = "bot-scheduler"
    private const val TRACE_LIMIT_BYTES = 48L * 1024 * 1024
    private val validId = Regex("[0-9]{13}-[a-f0-9]{12}")

    private class Recording(val id: String, val pid: Int, val heapVerified: Boolean) {
        val ending = AtomicBoolean(false)
        var waitingForHeap = false
    }
    @Volatile private var active: Recording? = null
    private var lastHeapRelease: Pair<String, Int>? = null
    private var memoryGuard: Job? = null
    private val guardLock = Any()

    private fun directory(ctx: Context) = File(ctx.filesDir, DIRECTORY)
    private fun key(id: String) = "peerlink-scheduler-$id"

    private fun record(ctx: Context, id: String, message: String) {
        AppState.appendLog("[BOT-DIAGNOSTICS] $message")
        runCatching {
            val dir = directory(ctx)
            check(dir.isDirectory || dir.mkdirs())
            File(dir, "$id.txt").appendText("${System.currentTimeMillis()} $message\n")
        }.onFailure { AppState.appendLog("[BOT-DIAGNOSTICS] Could not save the report: ${it.javaClass.simpleName}") }
    }

    fun gameExitReport(): String? {
        if (android.os.Build.VERSION.SDK_INT < 30 || !PrimeClient.isAlive(timeoutMs = 500)) return null
        val queriedAt = System.currentTimeMillis()
        val reply = BotPerfetto.shell("dumpsys -t 3 activity exit-info jp.konami.pesam", 5_000)
        return buildString {
            appendLine("Android exit history for eFootball. Match the timestamps to the room.")
            appendLine("queriedAtMs=$queriedAt exit=${reply.exitCode}")
            appendLine(reply.output.take(32 * 1024).ifBlank { "Android returned no exit history." })
        }
    }

    enum class RamCleanup { COMPLETED, INCOMPLETE, PRIME_UNAVAILABLE }

    fun startMemoryGuard(ctx: Context, id: String, scope: CoroutineScope) = synchronized(guardLock) {
        require(validId.matches(id))
        memoryGuard?.cancel()
        memoryGuard = scope.launch {
            try {
                val manager = ctx.getSystemService(ActivityManager::class.java)
                val memory = ActivityManager.MemoryInfo()
                val gameUid by lazy { ctx.packageManager.getApplicationInfo("jp.konami.pesam", 0).uid }
                val appUid = android.os.Process.myUid()
                var guardPid = 0
                var policy = RoomMemoryPressure()
                val script = ctx.assets.open("prime-room-cleanup.sh").bufferedReader().use { it.readText() }
                record(ctx, id, "Room memory guard started")
                while (isActive) {
                    delay(4000)
                    val pid = EngineHost.enginePid
                    if (pid <= 0 || EngineHost.roundEnd(pid) != null) continue
                    if (guardPid != pid) {
                        guardPid = pid
                        policy = RoomMemoryPressure()
                    }
                    manager.getMemoryInfo(memory)
                    val before = memory.availMem / 1048576
                    val pitchLoaded = EngineHost.isPitchLoaded(pid)
                    val shouldReclaim = policy.shouldReclaim(
                        SystemClock.elapsedRealtime(), before, memory.totalMem / 1048576, memory.lowMemory, pitchLoaded,
                    )
                    if (!shouldReclaim || EngineHost.roundEnd(pid) != null) continue
                    val result = GodModeManager.runRoomMemoryGuard("set -- guard $gameUid $appUid\n$script")
                    delay(1500)
                    manager.getMemoryInfo(memory)
                    val after = memory.availMem / 1048576
                    val requested = result.ok && result.output.lineSequence().any { it.trim() == "Android cached-app sweep requested" }
                    policy.attempted(SystemClock.elapsedRealtime(), if (requested) after - before else 0)
                    record(ctx, id, "Room memory guard pid=$pid pitchLoaded=$pitchLoaded cachedSweep=$requested beforeMiB=$before afterMiB=$after exit=${result.exitCode}\n${result.output.take(600)}")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                record(ctx, id, "Room memory guard stopped: ${error.javaClass.simpleName}")
            }
        }
    }

    fun stopMemoryGuard() = synchronized(guardLock) {
        memoryGuard?.cancel()
        memoryGuard = null
    }

    @Synchronized fun reclaimBeforeRoom(ctx: Context, id: String): RamCleanup {
        require(validId.matches(id))
        if (PrimeGameplayTracker.isMatchProtected()) {
            record(ctx, id, "RAM cleanup skipped while a match was active")
            return RamCleanup.INCOMPLETE
        }
        return runCatching {
            val dir = directory(ctx)
            val script = ctx.assets.open("prime-room-cleanup.sh").bufferedReader().use { it.readText() }
            val result = BotPerfetto.shell(script, 30_000)
            record(ctx, id, "RAM cleanup exit=${result.exitCode}\n${result.output}")
            prune(dir)
            when {
                result.ok -> RamCleanup.COMPLETED
                result.exitCode == -1 && !PrimeClient.isAlive(timeoutMs = 800) -> RamCleanup.PRIME_UNAVAILABLE
                else -> RamCleanup.INCOMPLETE
            }
        }.getOrElse {
            record(ctx, id, "RAM cleanup failed: ${it.message ?: it.javaClass.simpleName}")
            RamCleanup.INCOMPLETE
        }
    }

    @Synchronized fun startScheduler(ctx: Context, id: String, pid: Int, heapVerified: Boolean): Boolean {
        require(validId.matches(id))
        if (pid <= 0) return false
        return runCatching {
            val dir = directory(ctx)
            check(dir.isDirectory || dir.mkdirs()) { "Could not open the scheduler recording folder" }
            for (previous in pendingIds(dir)) runCatching { finish(ctx, previous, "next_recording") }
            val marker = File(dir, "$id.pending")
            check(marker.createNewFile()) { "Scheduler recording already exists" }
            val recording = Recording(id, pid, heapVerified)
            active = recording
            check(BotPerfetto.shell("perfetto --query | grep -q 'linux.ftrace'").ok) {
                "Android scheduler tracing is unavailable"
            }
            attemptStart(ctx, recording)
        }.getOrElse {
            record(ctx, id, "Scheduler recording failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    private fun attemptStart(ctx: Context, recording: Recording): Boolean {
        val id = recording.id
        return runCatching {
            check(!recording.ending.get() && EngineHost.isReady && EngineHost.enginePid == recording.pid) {
                "The bot ended before the scheduler recording could start"
            }
            val config = ctx.assets.open("bot-scheduler.pbtxt").bufferedReader().use { it.readText() } +
                "\nunique_session_name: \"${key(id)}\"\n"
            val started = BotPerfetto.start(key(id), config)
            if (!started.ok && recording.heapVerified && lastHeapRelease != (id to recording.pid) &&
                started.output.contains("too many concurrent tracing", ignoreCase = true)) {
                // Android rejected this session. The user-requested heap profile
                // owns a slot until the pitch loads. Its stop callback retries once.
                recording.waitingForHeap = true
                record(ctx, id, "Scheduler recording waiting for the bot memory recording to finish")
            } else {
                check(started.ok) { "Perfetto start exit=${started.exitCode} ${started.output.take(350)}" }
                check(BotPerfetto.shell("perfetto --is_detached=${key(id)} >/dev/null").ok) {
                    "Perfetto did not confirm the scheduler session"
                }
                record(ctx, id, "Scheduler recording started pid=${recording.pid}")
            }
            true
        }.getOrElse {
            record(ctx, id, "Scheduler recording failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    // Called on the heap export worker after Perfetto confirms the stop.
    @Synchronized fun heapStopped(ctx: Context, id: String, pid: Int, reason: String) {
        lastHeapRelease = id to pid
        val recording = active ?: return
        if (recording.id != id || recording.pid != pid || !recording.waitingForHeap ||
            recording.ending.get() || !EngineHost.isReady || EngineHost.enginePid != pid) return
        recording.waitingForHeap = false
        if (reason == "pitch_loaded") attemptStart(ctx, recording)
        else record(ctx, id, "Scheduler recording cancelled before a slot opened reason=$reason")
    }

    fun completed(ctx: Context, pid: Int, reason: String) {
        val recording = active?.takeIf { pid > 0 && it.pid == pid } ?: return
        if (!recording.ending.compareAndSet(false, true)) return
        Thread({ runCatching { finish(ctx, recording.id, reason) } }, "bot-scheduler-save").start()
    }

    @Synchronized private fun finish(ctx: Context, id: String, reason: String): Boolean {
        val dir = directory(ctx)
        val marker = File(dir, "$id.pending")
        if (!marker.exists()) return true
        if (active?.id == id) active = null
        val key = key(id)
        val source = BotPerfetto.tracePath(key)
        if (!BotPerfetto.stop(key)) {
            record(ctx, id, "Scheduler save deferred reason=$reason stage=stop")
            return false
        }
        val part = File(dir, "$id.part")
        val trace = File(dir, "$id.pftrace")
        try {
            if (BotPerfetto.copyPrefix(ctx, key, DIRECTORY, part, TRACE_LIMIT_BYTES, timeoutMs = 30_000)) {
                Files.move(part.toPath(), trace.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                record(ctx, id, "Scheduler recording saved bytes=${trace.length()} reason=$reason")
            } else {
                if (!BotPerfetto.shell("[ ! -s $source ]").ok) {
                    record(ctx, id, "Scheduler save deferred reason=$reason stage=copy")
                    return false
                }
                if (!trace.isFile) record(ctx, id, "Scheduler recording was empty reason=$reason")
            }
            if (!BotPerfetto.shell("rm -f $source").ok) return false
            return marker.delete() || !marker.exists()
        } finally {
            part.delete()
        }
    }

    data class Export(val files: List<File>, val pending: Boolean)

    @Synchronized fun export(ctx: Context): Export {
        val dir = directory(ctx)
        for (id in pendingIds(dir)) runCatching { finish(ctx, id, "export") }
        val files = dir.listFiles().orEmpty().filter {
            it.isFile && validId.matches(it.nameWithoutExtension)
        }
        val reports = files.filter { it.extension == "txt" }.sortedBy { it.name }
        val trace = reports.lastOrNull()?.let { File(dir, "${it.nameWithoutExtension}.pftrace") }
            ?.takeIf { it.isFile }
        return Export(reports + listOfNotNull(trace), pendingIds(dir).isNotEmpty())
    }

    private fun pendingIds(dir: File) = dir.listFiles().orEmpty().filter {
        it.isFile && it.extension == "pending" && validId.matches(it.nameWithoutExtension)
    }.map { it.nameWithoutExtension }

    private fun prune(dir: File) {
        val files = dir.listFiles().orEmpty().filter { validId.matches(it.nameWithoutExtension) }
        val pending = pendingIds(dir).toSet()
        val older = files.map { it.nameWithoutExtension }.distinct().sortedDescending().drop(3).toSet()
        files.filter {
            it.nameWithoutExtension in older && it.nameWithoutExtension !in pending &&
                (it.extension == "txt" || it.extension == "pftrace")
        }.forEach { it.delete() }
    }
}
