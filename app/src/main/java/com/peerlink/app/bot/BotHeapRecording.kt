package com.peerlink.app.bot

import android.content.Context
import android.os.Process
import android.os.SystemClock
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.GodModeManager
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
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

    private fun remote(id: String) = "/data/misc/perfetto-traces/peerlink-bot-$id.pftrace"
    private fun key(id: String) = "peerlink-bot-$id"
    private fun directory(ctx: Context) = File(ctx.filesDir, DIRECTORY)
    private fun asApp(ctx: Context) = "run-as ${ctx.packageName} --user ${Process.myUid() / 100000}"
    private fun shell(command: String) = GodModeManager.runUserShell(command, 15_000)
    private fun status(ctx: Context, id: String, message: String) {
        File(directory(ctx), "$id.txt").appendText("${System.currentTimeMillis()} $message\n")
        AppState.appendLog("[BOT-MEMORY] $message")
    }

    @Synchronized fun prepare(ctx: Context): String {
        val dir = directory(ctx)
        check(dir.isDirectory || dir.mkdirs()) { "PeerLink could not open its recording folder." }
        val ready = shell("command -v perfetto >/dev/null || { echo 'Perfetto is unavailable'; exit 1; }\n" +
            "${asApp(ctx)} sh -c 'test -d files/$DIRECTORY'")
        if (!ready.ok) {
            val detail = ready.output.trim().take(300).ifBlank {
                if (ready.exitCode == -1) "Prime did not respond." else "Android could not open the private recording folder."
            }
            val entry = "recorder_preflight_failed exit=${ready.exitCode} detail=$detail"
            AppState.appendLog("[BOT-MEMORY] $entry")
            runCatching { File(dir, PREFLIGHT_ERROR_FILE).writeText("${System.currentTimeMillis()} $entry\n") }
            if (ready.exitCode == -1) error("Activate Prime in Settings, then tap Record memory and create room.")
            error("Could not start memory recording. $detail")
        }
        for (pending in pendingIds(dir)) {
            check(finish(ctx, pending, "next_recording")) { "The previous recording is still on this phone. Turn on Prime and export it first." }
        }
        val id = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().replace("-", "").take(12)}"
        val marker = File(dir, "$id.pending")
        check(marker.createNewFile())
        prune(dir)
        status(ctx, id, "requested target=${ctx.packageName}:botengine build=${com.peerlink.app.BuildConfig.VERSION_NAME}")
        activeId = id
        return id
    }

    /** Attach to the prepared bot before the room opens and before team setup starts. */
    @Synchronized fun startAndVerify(ctx: Context, id: String, pid: Int, sampleAllocation: () -> Boolean) {
        check(id == activeId && pid > 0) { "PeerLink could not identify the bot process for recording." }
        val traceConfig = config(ctx.packageName)
        File(directory(ctx), "$id.pending").writeText(pid.toString())
        status(ctx, id, "attaching_to_prepared_bot pid=$pid")
        val run = shell("printf '%s\\n' '$traceConfig' | perfetto --txt -c - --detach=${key(id)} -o ${remote(id)}")
        if (!run.ok) {
            status(ctx, id, "recorder_start_failed exit=${run.exitCode} ${run.output.take(300)}")
            error("Android could not start the memory recording. Open Export diagnostics for the reason.")
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
        if (reason == "no_new_bot_dump") error("The bot's memory recording stopped updating. Export diagnostics now.")
        error("Android started the recorder but supplied no live allocation stack for this bot. Export diagnostics now.")
    }

    // Copy a fixed prefix through a pipe so that Prime never receives the trace as JSON.
    private fun copyPrefix(ctx: Context, id: String, output: File): Boolean {
        val source = remote(id)
        val command = """
            n=$(wc -c < $source) || exit 1
            [ "${'$'}n" -gt 0 ] && [ "${'$'}n" -le $MAX_BYTES ] || exit 1
            head -c "${'$'}n" $source | ${asApp(ctx)} sh -c 'umask 077; cat > files/$DIRECTORY/${output.name}' || exit 1
            printf '%s' "${'$'}n"
        """.trimIndent()
        val result = shell(command)
        val expected = result.output.trim().toLongOrNull() ?: return false
        return result.ok && expected in 1..MAX_BYTES && output.isFile && output.length() == expected
    }

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
        // Exit 2 specifically means there is no detached session, including a natural timeout.
        val stop = shell("perfetto --attach=${key(id)} --stop >/dev/null 2>&1; rc=\$?; [ \"\$rc\" = 0 ] || [ \"\$rc\" = 2 ]")
        if (!stop.ok) return false
        val part = File(dir, "$id.part")
        try {
            if (copyPrefix(ctx, id, part)) {
                Files.move(part.toPath(), File(dir, "$id.pftrace").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                status(ctx, id, "saved pid=${marker.readText().ifBlank { "unknown" }} reason=$reason")
            } else {
                if (!shell("[ ! -s ${remote(id)} ]").ok) return false
                if (!File(dir, "$id.pftrace").isFile) status(ctx, id, "empty_recording reason=$reason")
            }
            if (!shell("rm -f ${remote(id)}").ok) return false
            marker.delete()
            return !marker.exists()
        } finally { part.delete() }
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
        files.filter { it.nameWithoutExtension in older && it.extension in setOf("txt", "pftrace") }.forEach { it.delete() }
    }
}
