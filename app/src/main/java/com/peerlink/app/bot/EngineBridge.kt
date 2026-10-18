package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.PrimeClient
import com.peerlink.app.godmode.PrimeShellRunner
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge's front door.
 *
 * The bot's own P2P half is meant to become the game's own engine -- the same
 * 160 MB library the real game runs -- fed the bot's id and squad. Everything
 * downstream depends on one question that has never been answered on this
 * phone:
 *
 *   can we reach the installed game's engine at all, and will it load?
 *
 * This runs the native probe and puts the answer in the app log.
 *
 * It runs it TWO ways, because "can we read the game's files" is exactly the
 * kind of thing that is only true in one of them, and guessing has cost us
 * before:
 *
 *   1. as this app itself -- always available, needs nothing else running,
 *      but Android may refuse to let an app read another app's files
 *   2. as the privileged helper -- the uid 2000 (or root) process the app's
 *      other shell commands run in, which certainly can read them, but which
 *      is only up when the user's Prime side is up
 *
 * The probe prints `UID=` so the two results can never be confused. Whichever
 * one works is the one the bridge is built on.
 *
 * The probe only looks, hashes, copies and tries to open. It never writes to
 * the game's storage.
 */
object EngineBridge {

    private val started = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null

    /** Raw `KEY=value` report from the route that got furthest, or null. */
    @Volatile
    var lastReport: String? = null
        private set

    /** Which route produced [lastReport]: "app" or "helper". */
    @Volatile
    var lastRoute: String? = null
        private set

    /** SHA-256 of the engine file the installed game loads. */
    @Volatile
    var engineSha256: String? = null
        private set

    /** Size of that file, in bytes. */
    @Volatile
    var engineSize: Long = 0L
        private set

    /** The engine's path exactly as the probe found it. */
    @Volatile
    var enginePath: String? = null
        private set

    /** `load_ok` | `load_failed` | `engine_crashed_on_load` | ... | null. */
    @Volatile
    var verdict: String? = null
        private set

    /** Load base address as text (`0x...`), when the load succeeded. */
    @Volatile
    var loadBase: String? = null
        private set

    /** Result of the app-uid route, raw. */
    @Volatile
    var appReport: String? = null
        private set

    /** Result of the helper route, raw. Null when the helper was not up. */
    @Volatile
    var helperReport: String? = null
        private set

    /**
     * Ask for the probe to be run, supplying a context. Safe to call from
     * anywhere, any number of times: only the first call in this process does
     * work.
     */
    fun probeOnce(context: Context) {
        appContext = context.applicationContext
        PrimeClient.init(context.applicationContext)
        probeOnce()
    }

    /**
     * Ask for the probe to be run. Does nothing if no context has been
     * supplied yet. Safe to call any number of times.
     */
    fun probeOnce() {
        if (appContext == null) {
            AppState.appendLog("[ENGINE-BRIDGE] no context yet -- probe not started")
            return
        }
        if (!started.compareAndSet(false, true)) return
        Thread({ runProbe() }, "engine-probe").apply { isDaemon = true }.start()
    }

    private fun log(line: String) = AppState.appendLog("[ENGINE-BRIDGE] $line")

    /** One run of the privileged helper. Never throws. */
    private fun probeViaHelper(): String = try {
        PrimeClient.engineProbe() ?: ""
    } catch (t: Throwable) {
        log("helper route threw ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    /**
     * One run of the probe as this app. Uses the same runner the shell
     * commands use, so the timeout and output handling are identical.
     */
    private fun probeAsApp(context: Context): String = try {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libpeerlinkengine.so")
        if (!exe.isFile) {
            log("probe program is missing from this APK: ${exe.absolutePath}")
            ""
        } else {
            val stage = File(context.filesDir, "engine").absolutePath
            PrimeShellRunner.run(
                listOf(exe.absolutePath, "--out", stage),
                90_000L,
                512 * 1024,
            )
        }
    } catch (t: Throwable) {
        log("app route threw ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    private fun runProbe() {
        val context = appContext ?: return
        log("looking for the game's engine")

        // Route 1 -- as this app. Always possible, so it goes first.
        val asApp = probeAsApp(context)
        appReport = asApp
        if (asApp.isNotBlank()) {
            log("--- as this app ---")
            absorb(asApp)
        }

        // Route 2 -- as the privileged helper, if it is up.
        if (PrimeClient.isAlive(timeoutMs = 800)) {
            val viaHelper = probeViaHelper()
            helperReport = viaHelper
            if (viaHelper.isNotBlank()) {
                log("--- as the privileged helper ---")
                absorb(viaHelper)
            }
        } else {
            log("the privileged helper is not running -- app-level result only")
        }

        log(
            when (verdict) {
                "load_ok" -> "the game's engine LOADS on this phone (route=$lastRoute, base=$loadBase)"
                "engine_crashed_on_load" -> "the engine was opened but died loading (route=$lastRoute)"
                "load_failed" -> "the loader refused the engine -- see the error above"
                "no_game_library_found" -> "the game's library was not found (route=$lastRoute)"
                "engine_file_missing" -> "the engine file the game lists is missing"
                null -> "the probe produced no verdict -- see the lines above"
                else -> "probe finished with $verdict"
            }
        )
    }

    /**
     * Take one route's report: pull the facts out of it and echo the lines
     * that matter into the app log. The last route to answer wins the shared
     * fields, which is fine -- if both answer they must agree, and if they
     * disagree that disagreement is itself the finding.
     */
    private fun absorb(report: String) {
        for (line in report.lineSequence()) {
            if (line.startsWith("RESULT=") || line.startsWith("UID=") ||
                line.startsWith("UE4_ROM_PATH=") || line.startsWith("UE4_ROM_SIZE=") ||
                line.startsWith("UE4_SHA256=") || line.startsWith("STAGE_UE4=") ||
                line.startsWith("CHILD_SIBLING=") || line.startsWith("CHILD_LOAD=") ||
                line.startsWith("CHILD_BASE=") || line.startsWith("CHILD_TRY_") ||
                line.startsWith("LOAD_SIGNAL=") || line.startsWith("LOAD_EXIT=") ||
                line.startsWith("LOAD_TIMEOUT=")
            ) {
                log(line)
            }
        }
        if (!report.contains("RESULT=")) return

        lastReport = report
        lastRoute = if (report === appReport) "app" else "helper"
        for (line in report.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            when (line.substring(0, eq)) {
                "UE4_SHA256" -> engineSha256 = line.substring(eq + 1)
                "UE4_ROM_SIZE" -> engineSize = line.substring(eq + 1).toLongOrNull() ?: engineSize
                "UE4_ROM_PATH" -> enginePath = line.substring(eq + 1)
                "CHILD_BASE" -> loadBase = line.substring(eq + 1)
                "RESULT" -> verdict = line.substring(eq + 1)
            }
        }
    }
}
