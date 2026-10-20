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
 * downstream depends on two questions that have never been answered on this
 * phone:
 *
 *   which build of the engine is installed here?   (cheap, safe)
 *   will the loader open it?                       (heavy, risky)
 *
 * THEY ARE SPLIT, AND ONLY THE CHEAP ONE RUNS BY ITSELF.
 *
 * The first version of this ran the whole thing -- locate, hash, copy 161 MB,
 * then load a 161 MB library, twice -- and it was wired to fire when a room
 * opened and when bot mode started. That is the worst possible moment: the
 * bot is mid-handshake and the phone is already running eFootball. It cost
 * memory, disk and CPU exactly where they were needed, and the bot stopped
 * reaching match. That was a bad decision on my part and it is reversed here.
 *
 * [identifyOnce] is what runs on its own. It reads the engine file in 1 MB
 * chunks, prints its size and SHA-256, and stops. It writes nothing, forks
 * nothing, allocates nothing of size. It is safe to run while a match is in
 * progress, so it no longer matters exactly when it fires.
 *
 * [loadTest] is the heavy half. It is NOT called from anywhere automatically.
 * It may only be run deliberately, when the phone is idle and no match is
 * running, because it will pull a 161 MB library into the process and run the
 * engine's ~11,900 registration functions.
 *
 * Both routes are kept, and both print `UID=`, because "can we read another
 * app's files" is exactly the kind of thing that is only true in one of them:
 *
 *   1. as this app itself -- always available, nothing else needed
 *   2. as the privileged helper -- the uid 2000 / root process the shell
 *      commands run in, when the user's Prime side is up
 */
object EngineBridge {

    private val identified = AtomicBoolean(false)
    private val loadTestStarted = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null

    // ---- facts, from whichever route answered last ------------------------

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

    /** `engine_identified` | `load_ok` | `load_failed` | ... | null. */
    @Volatile
    var verdict: String? = null
        private set

    /** Load base address as text (`0x...`), from the heavy test only. */
    @Volatile
    var loadBase: String? = null
        private set

    @Volatile
    var appReport: String? = null
        private set

    @Volatile
    var helperReport: String? = null
        private set

    /**
     * The cheap half. Runs once per process, in the background, and is safe at
     * any time -- including in the middle of a match.
     */
    fun identifyOnce(context: Context) {
        appContext = context.applicationContext
        PrimeClient.init(context.applicationContext)
        if (!identified.compareAndSet(false, true)) return
        Thread({ identify() }, "engine-identify").apply { isDaemon = true }.start()
    }

    private fun log(line: String) = AppState.appendLog("[ENGINE-BRIDGE] $line")

    /**
     * Where the engine is staged so that THIS app can load it.
     *
     * The privileged helper does the copying (it can read the game's
     * library), but the engine has to end up where the app can dlopen it:
     * the app's own files directory.  The helper writes there as root, the
     * app reads it back, and the staged files are readable and not
     * writable.
     */
    fun stageDir(context: Context): String =
        java.io.File(context.filesDir, "engine").absolutePath

    /**
     * Have the privileged helper stage the engine where this app can load
     * it.  The helper can read the game's library; the app cannot.
     */
    fun stageEngine(context: Context): Boolean {
        if (!PrimeClient.isAlive(timeoutMs = 800)) {
            log("the privileged helper is not running -- the engine stays unstaged")
            return false
        }
        val r = probeViaHelper(
            hashOnly = false,
            timeoutMs = 110_000,
            stageDir = stageDir(context),
        )
        helperReport = r
        if (r.isNotBlank()) absorb(r)
        log("engine staged for the app: ${stageDir(context)}")
        return true
    }

    private fun identify() {
        val context = appContext ?: return
        log("looking for the game's engine")

        val asApp = probeAsApp(context, hashOnly = true, timeoutMs = 45_000L)
        appReport = asApp
        if (asApp.isNotBlank()) {
            log("--- as this app ---")
            absorb(asApp)
        }

        if (PrimeClient.isAlive(timeoutMs = 800)) {
            val viaHelper = probeViaHelper(hashOnly = true, timeoutMs = 45_000, stageDir = null)
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
                "engine_identified" -> "the engine here is ${engineSize} bytes, sha256 ${engineSha256} (route=$lastRoute)"
                "no_game_library_found" -> "the game's library was not found (route=$lastRoute)"
                "engine_file_missing" -> "the engine file the game lists is missing"
                null -> "the probe produced no verdict -- see the lines above"
                else -> "probe finished with $verdict"
            }
        )
    }

    /**
     * The heavy half: actually try to load the engine. NOT called from
     * anywhere automatically, and it must not be -- it copies the engine
     * (161 MB), forks a child, and has that child dlopen the library, which
     * runs the engine's ~11,900 registration functions. Only run this when no
     * match is in progress.
     */
    fun loadTest(context: Context) {
        appContext = context.applicationContext
        PrimeClient.init(context.applicationContext)
        if (!loadTestStarted.compareAndSet(false, true)) return
        Thread({ runLoadTest() }, "engine-load-test").apply { isDaemon = true }.start()
    }

    private fun runLoadTest() {
        val context = appContext ?: return
        log("LOAD TEST -- heavy; no match should be running")
        val asApp = probeAsApp(context, hashOnly = false, timeoutMs = 90_000L)
        appReport = asApp
        if (asApp.isNotBlank()) {
            log("--- as this app ---")
            absorb(asApp)
        }
        if (PrimeClient.isAlive(timeoutMs = 800)) {
            val viaHelper = probeViaHelper(
                hashOnly = false,
                timeoutMs = 110_000,
                stageDir = stageDir(context),
            )
            helperReport = viaHelper
            if (viaHelper.isNotBlank()) {
                log("--- as the privileged helper ---")
                absorb(viaHelper)
            }
        }
        log(
            when (verdict) {
                "load_ok" -> "the game's engine LOADS on this phone (route=$lastRoute, base=$loadBase)"
                "engine_crashed_on_load" -> "the engine was opened but died loading (route=$lastRoute)"
                "load_failed" -> "the loader refused the engine -- see the error above"
                else -> "load test finished with $verdict"
            }
        )
    }

    // ---- the two routes ---------------------------------------------------

    /** One run of the privileged helper. Never throws. */
    private fun probeViaHelper(hashOnly: Boolean, timeoutMs: Int, stageDir: String?): String = try {
        PrimeClient.engineProbe(
            stageDir = stageDir ?: PrimeClient.DEFAULT_ENGINE_STAGE_DIR,
            hashOnly = hashOnly,
            timeoutMs = timeoutMs,
        ) ?: ""
    } catch (t: Throwable) {
        log("helper route threw ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    /** One run of the probe as this app, using the same runner the shell commands use. */
    private fun probeAsApp(context: Context, hashOnly: Boolean, timeoutMs: Long): String = try {
        val exe = File(context.applicationInfo.nativeLibraryDir, "libpeerlinkengine.so")
        if (!exe.isFile) {
            log("probe program is missing from this APK: ${exe.absolutePath}")
            ""
        } else {
            val stage = File(context.filesDir, "engine").absolutePath
            val command = buildList {
                add(exe.absolutePath)
                add("--out")
                add(stage)
                if (hashOnly) add("--hash-only")
            }
            PrimeShellRunner.run(command, timeoutMs, 512 * 1024)
        }
    } catch (t: Throwable) {
        log("app route threw ${t.javaClass.simpleName}: ${t.message}")
        ""
    }

    /**
     * Take one route's report: pull the facts out and echo the lines that
     * matter into the app log. The last route to answer wins the shared
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
