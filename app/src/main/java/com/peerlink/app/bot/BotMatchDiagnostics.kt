package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.core.PrimeGameplayTracker
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal object BotMatchDiagnostics {
    private const val DIRECTORY = "bot-scheduler"
    private const val TRACE_LIMIT_BYTES = 48L * 1024 * 1024
    private val validId = Regex("[0-9]{13}-[a-f0-9]{12}")

    private data class Recording(val id: String, val pid: Int)
    @Volatile private var active: Recording? = null

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

    @Synchronized fun reclaimBeforeRoom(ctx: Context, id: String): Boolean {
        require(validId.matches(id))
        if (PrimeGameplayTracker.isMatchProtected()) {
            record(ctx, id, "RAM cleanup skipped while a match was active")
            return false
        }
        return runCatching {
            val script = ctx.assets.open("prime-room-cleanup.sh").bufferedReader().use { it.readText() }
            val result = BotPerfetto.shell(script, 30_000)
            record(ctx, id, "RAM cleanup exit=${result.exitCode}\n${result.output}")
            prune(directory(ctx))
            result.ok
        }.getOrElse {
            record(ctx, id, "RAM cleanup failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    @Synchronized fun startScheduler(ctx: Context, id: String, pid: Int): Boolean {
        require(validId.matches(id))
        return runCatching {
            val dir = directory(ctx)
            check(dir.isDirectory || dir.mkdirs()) { "Could not open the scheduler recording folder" }
            for (previous in pendingIds(dir)) runCatching { finish(ctx, previous, "next_room") }
            check(BotPerfetto.shell("perfetto --query | grep -q 'linux.ftrace'").ok) {
                "Android scheduler tracing is unavailable"
            }
            val config = ctx.assets.open("bot-scheduler.pbtxt").bufferedReader().use { it.readText() }
            val marker = File(dir, "$id.pending")
            check(marker.createNewFile()) { "Scheduler recording already exists" }
            val started = BotPerfetto.start(key(id), config)
            check(started.ok) { "Perfetto start exit=${started.exitCode} ${started.output.take(350)}" }
            check(BotPerfetto.shell("perfetto --is_detached=${key(id)} >/dev/null").ok) {
                "Perfetto did not confirm the scheduler session"
            }
            active = Recording(id, pid)
            record(ctx, id, "Scheduler recording started pid=$pid")
            true
        }.getOrElse {
            record(ctx, id, "Scheduler recording failed: ${it.message ?: it.javaClass.simpleName}")
            false
        }
    }

    fun completed(ctx: Context, pid: Int, reason: String) {
        val recording = active?.takeIf { pid > 0 && it.pid == pid } ?: return
        Thread({ runCatching { finish(ctx, recording.id, reason) } }, "bot-scheduler-save").start()
    }

    @Synchronized private fun finish(ctx: Context, id: String, reason: String): Boolean {
        val dir = directory(ctx)
        val marker = File(dir, "$id.pending")
        if (!marker.exists()) return true
        if (active?.id == id) active = null
        val key = key(id)
        val source = BotPerfetto.tracePath(key)
        if (!BotPerfetto.stop(key)) return false
        val part = File(dir, "$id.part")
        val trace = File(dir, "$id.pftrace")
        try {
            if (BotPerfetto.copyPrefix(ctx, key, DIRECTORY, part, TRACE_LIMIT_BYTES, timeoutMs = 30_000)) {
                Files.move(part.toPath(), trace.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                record(ctx, id, "Scheduler recording saved bytes=${trace.length()} reason=$reason")
            } else {
                if (!BotPerfetto.shell("[ ! -s $source ]").ok) return false
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
        val trace = files.filter { it.extension == "pftrace" }.maxByOrNull { it.name }
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
