package com.peerlink.app.bot

import android.content.Context
import android.os.SystemClock
import com.peerlink.app.core.AppState
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

/** An explicitly requested Perfetto recording of this APK's private bot process. */
internal object BotHeapRecording {
    private const val DIRECTORY = "bot-heap"
    private const val PREFLIGHT_ERROR_FILE = "last_preflight_error.txt"
    private const val MAX_BYTES = 32L * 1024 * 1024
    private const val SAMPLE_INTERVAL_BYTES = 2 * 1024 * 1024
    internal const val PROBE_BYTES = SAMPLE_INTERVAL_BYTES + 1
    private val validId = Regex("[0-9]{13}-[a-f0-9]{12}")
    private val recordingPid = AtomicInteger()
    @Volatile private var activeId: String? = null

    internal fun config(pkg: String): String {
        require(pkg.matches(Regex("[a-zA-Z0-9_]+(\\.[a-zA-Z0-9_]+)+")))
        return """
            buffers { size_kb: 2048 fill_policy: RING_BUFFER }
            data_sources { config {
              name: "android.heapprofd"
              heapprofd_config {
                process_cmdline: "$pkg:botengine"
                no_startup: true
                sampling_interval_bytes: $SAMPLE_INTERVAL_BYTES
                shmem_size_bytes: 8388608
                adaptive_sampling_shmem_threshold: 4194304
                adaptive_sampling_max_sampling_interval_bytes: 8388608
                block_client: false
                continuous_dump_config { dump_phase_ms: 1000 dump_interval_ms: 2000 }
              }
            } }
            duration_ms: 900000
            write_into_file: true
            flush_period_ms: 1000
            file_write_period_ms: 1000
            max_file_size_bytes: 25165824
        """.trimIndent()
    }

    private fun key(id: String) = "peerlink-bot-$id"
    private fun remote(id: String) = BotPerfetto.tracePath(key(id))
    private fun directory(ctx: Context) = File(ctx.filesDir, DIRECTORY)
    private fun status(ctx: Context, id: String, message: String) {
        File(directory(ctx), "$id.txt").appendText("${System.currentTimeMillis()} $message\n")
        AppState.appendLog("[BOT-MEMORY] $message")
    }

    val requested get() = activeId != null

    private const val DETAIL_KEY = "detailed_bot_room_recording"

    fun enabledForRooms(ctx: Context) =
        ctx.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).getBoolean(DETAIL_KEY, false)

    fun setEnabledForRooms(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences("peerlink_prefs", Context.MODE_PRIVATE).edit().putBoolean(DETAIL_KEY, enabled).apply()
    }

    fun newId() = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(12)}"

    private class RecorderUnavailable(message: String) : Exception(message)

    /** A missing trace must never turn a working engine into a failed room. */
    private fun capture(ctx: Context, id: String, step: String, action: () -> Unit): String? {
        return try {
            action()
            null
        } catch (error: Exception) {
            if (error is CancellationException || error is InterruptedException) throw error
            val note = (error as? RecorderUnavailable)?.message ?: "Detailed memory recording is unavailable."
            val detail = "recording_unavailable step=$step cause=${error.javaClass.simpleName} detail=${error.message}"
            AppState.appendLog("[BOT-MEMORY] $detail")
            runCatching {
                File(directory(ctx), "$id.txt").appendText("${System.currentTimeMillis()} $detail\n")
            }
            // Stop only this request's trace. Uncopied output retains its pending marker for export.
            if (!runCatching { finish(ctx, id, "recording_unavailable") }.getOrDefault(false)) {
                AppState.appendLog("[BOT-MEMORY] saving_partial_recording_deferred id=$id")
            }
            runCatching { prune(directory(ctx)) }
            note
        }
    }

    @Synchronized fun prepare(ctx: Context, id: String): String? = capture(ctx, id, "prepare") {
        val dir = directory(ctx)
        check(dir.isDirectory || dir.mkdirs()) { "PeerLink could not open its recording folder." }
        val ready = BotPerfetto.shell("command -v perfetto >/dev/null || { echo 'Perfetto is unavailable'; exit 1; }\n" +
            "${BotPerfetto.asApp(ctx)} sh -c 'test -d files/$DIRECTORY'")
        if (!ready.ok) {
            val detail = ready.output.trim().take(300).ifBlank {
                if (ready.exitCode == -1) "Prime did not respond." else "Android could not open the private recording folder."
            }
            val entry = "recorder_preflight_failed exit=${ready.exitCode} detail=$detail"
            AppState.appendLog("[BOT-MEMORY] $entry")
            runCatching { File(dir, PREFLIGHT_ERROR_FILE).writeText("${System.currentTimeMillis()} $entry\n") }
            throw RecorderUnavailable("Detailed memory recording is unavailable. See Export diagnostics for the reason.")
        }
        for (pending in pendingIds(dir)) {
            check(finish(ctx, pending, "next_recording")) { "The previous recording is still on this phone. Turn on Prime and export it first." }
        }
        val marker = File(dir, "$id.pending")
        check(marker.createNewFile())
        prune(dir)
        status(ctx, id, "requested target=${ctx.packageName}:botengine build=${com.peerlink.app.BuildConfig.VERSION_NAME}")
        activeId = id
    }

    /** Attach to the prepared bot before the room opens and before team setup starts. */
    @Synchronized fun startAndVerify(ctx: Context, id: String, pid: Int, sampleAllocation: () -> Boolean): String? =
        capture(ctx, id, "verify") { verify(ctx, id, pid, sampleAllocation) }

    private fun verify(ctx: Context, id: String, pid: Int, sampleAllocation: () -> Boolean) {
        check(id == activeId && pid > 0) { "PeerLink could not identify the bot process for recording." }
        val traceConfig = config(ctx.packageName)
        File(directory(ctx), "$id.pending").writeText(pid.toString())
        status(ctx, id, "attaching_to_prepared_bot pid=$pid")
        val run = BotPerfetto.start(key(id), traceConfig)
        if (!run.ok) {
            status(ctx, id, "recorder_start_failed exit=${run.exitCode} ${run.output.take(300)}")
            if (run.output.contains("too many concurrent tracing", ignoreCase = true)) {
                throw RecorderUnavailable("Android's tracing slots were full. Detailed memory recording is unavailable.")
            }
            throw RecorderUnavailable("Android could not start detailed memory recording.")
        }
        val probe = File(directory(ctx), "$id.probe")
        val start = SystemClock.elapsedRealtime()
        val deadline = start + 20_000
        var nextProbe = start + 1000
        var probes = 0
        var firstMappedDump = 0L
        var snapshot: HeapProfileSamples.Result? = null
        try {
            do {
                if (copyPrefix(ctx, id, probe)) {
                    val reading = probe.inputStream().use { HeapProfileSamples.inspect(it, pid) }
                    snapshot = reading
                    reading.problem()?.let { reason ->
                        status(ctx, id, "recording_stopped pid=$pid reason=$reason")
                        error(if (reason == "buffer_overrun") "Android's memory recording buffer filled. Export diagnostics now."
                            else "Android stopped the memory recording ($reason). Export diagnostics now.")
                    }
                    if (reading.hasStack && reading.latestDumpPacketNs > 0) {
                        if (firstMappedDump == 0L) firstMappedDump = reading.latestDumpPacketNs
                        // Seeing the same complete file twice cannot establish that the producer is still alive.
                        if (reading.latestDumpPacketNs - firstMappedDump >= 1_000_000_000L) {
                            recordingPid.set(pid)
                            status(ctx, id, "allocation_stack_verified pid=$pid probes=$probes live_dump_ns=${reading.latestDumpPacketNs}")
                            File(directory(ctx), PREFLIGHT_ERROR_FILE).delete()
                            return
                        }
                    }
                }
                val now = SystemClock.elapsedRealtime()
                if (now >= deadline) break
                if (probes < 4 && now >= nextProbe) {
                    val accepted = runCatching(sampleAllocation)
                    if (accepted.getOrNull() != true) {
                        status(ctx, id, "probe_failed pid=$pid reason=${accepted.exceptionOrNull()?.javaClass?.simpleName ?: "rejected"}")
                        error("The bot could not make its test allocation. Export diagnostics now.")
                    }
                    probes++
                    nextProbe = now + 3000
                }
                Thread.sleep(1000)
            } while (true)
        } finally {
            probe.delete()
        }
        val reason = when {
            snapshot == null -> "no_live_trace_bytes"
            !snapshot.sawTarget -> "no_dump_for_bot"
            snapshot.hasStack -> "no_new_bot_dump"
            else -> "no_mapped_allocation"
        }
        status(ctx, id, "no_verified_allocation_stack pid=$pid reason=$reason probes=$probes")
        if (reason == "no_new_bot_dump") throw RecorderUnavailable("Detailed memory recording stopped updating.")
        throw RecorderUnavailable("Android returned no verified live memory samples for the bot.")
    }

    private fun copyPrefix(ctx: Context, id: String, output: File) =
        BotPerfetto.copyPrefix(ctx, key(id), DIRECTORY, output, MAX_BYTES)

    fun observing(pid: Int) = pid > 0 && pid == recordingPid.get()

    fun completed(ctx: Context, pid: Int, reason: String) {
        val id = activeId ?: return
        if (pid <= 0 || !recordingPid.compareAndSet(pid, 0)) return
        Thread({ runCatching { finish(ctx, id, reason) } }, "bot-heap-finish").start()
    }

    @Synchronized fun finish(ctx: Context, id: String, reason: String): Boolean {
        val dir = directory(ctx)
        val marker = File(dir, "$id.pending")
        if (!marker.exists()) return true
        if (activeId == id) { recordingPid.set(0); activeId = null }
        if (!BotPerfetto.stop(key(id))) return false
        marker.readText().trim().toIntOrNull()?.let { pid ->
            BotMatchDiagnostics.heapStopped(ctx, id, pid, reason)
        }
        val part = File(dir, "$id.part")
        try {
            if (copyPrefix(ctx, id, part)) {
                Files.move(part.toPath(), File(dir, "$id.pftrace").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                status(ctx, id, "saved pid=${marker.readText().ifBlank { "unknown" }} reason=$reason")
            } else {
                if (!BotPerfetto.shell("[ ! -s ${remote(id)} ]").ok) return false
                if (!File(dir, "$id.pftrace").isFile) status(ctx, id, "empty_recording reason=$reason")
            }
            if (!BotPerfetto.shell("rm -f ${remote(id)}").ok) return false
            marker.delete()
            return !marker.exists()
        } finally {
            part.delete()
        }
    }

    data class Export(val files: List<File>, val pending: Boolean, val starting: Boolean = false)

    @Synchronized fun export(ctx: Context): Export {
        val dir = directory(ctx)
        if (!dir.isDirectory) return Export(emptyList(), false)
        val pending = pendingIds(dir)
        // A new recording has no PID until the prepared engine reaches startAndVerify.
        val starting = pending.firstOrNull { id -> id == activeId && File(dir, "$id.pending").length() == 0L }
        for (id in pending) {
            if (id != starting) runCatching { finish(ctx, id, "export") }
        }
        val files = dir.listFiles().orEmpty().filter { f ->
            f.isFile && (f.name == PREFLIGHT_ERROR_FILE ||
                (f.extension in setOf("pftrace", "txt") && validId.matches(f.nameWithoutExtension)))
        }.sortedBy { it.name }
        return Export(files, pendingIds(dir).isNotEmpty(), starting != null)
    }

    private fun pendingIds(dir: File) = dir.listFiles().orEmpty().filter {
        it.extension == "pending" && validId.matches(it.nameWithoutExtension)
    }.map { it.nameWithoutExtension }

    private fun prune(dir: File) {
        val files = dir.listFiles().orEmpty().filter { validId.matches(it.nameWithoutExtension) }
        val older = files.map { it.nameWithoutExtension }.distinct().sortedDescending().drop(3).toSet()
        val pending = pendingIds(dir).toSet()
        files.filter { it.nameWithoutExtension in older && it.nameWithoutExtension !in pending &&
            it.extension in setOf("txt", "pftrace") }.forEach { it.delete() }
    }
}
