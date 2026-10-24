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
 * The bot's own P2P half is the game's own engine -- the same 160 MB library
 * the real game runs.  (CORRECTED 2026-10-24: the old comment here said "fed
 * the bot's id and squad" -- that was wrong.  There is no squad path into the
 * engine; what the engine gets is its identity (my-id) and the PEER record
 * (the opponent's real server-issued blocks) -- and then it plays the whole
 * exchange by itself.)  Everything downstream depends on two questions that
 * have never been answered on this phone:
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

    /** The engine is ~154 MiB; anything much smaller is not it. */
    private const val MIN_ENGINE_BYTES = 100L * 1024 * 1024

    private const val GAME_PACKAGE = "jp.konami.pesam"

    /**
     * Have the engine staged where this app can load it.
     *
     * Two routes, tried in this order:
     *
     *   1. this app itself.  The game's library directory under /data/app is
     *      readable; what an app cannot do is *find* it, and that is exactly
     *      what the package manager is for.  No helper, no root, nothing to
     *      start first.
     *   2. the privileged helper, which can also read the game's library and
     *      is the older route.
     *
     * The old code only tried route 2 and gave up when the helper was not
     * running, which is why the engine was never staged on the phone.
     */
    fun stageEngine(context: Context): Boolean {
        val stage = java.io.File(stageDir(context))
        val staged = java.io.File(stage, "libUE4.so")
        if (staged.isFile && staged.length() >= MIN_ENGINE_BYTES) {
            log("the engine is already staged: ${staged.absolutePath} (${staged.length()} bytes)")
            return true
        }

        // ---- route 1: as this app -------------------------------------
        val gameLibDir = gameLibDir(context)
        if (gameLibDir != null) {
            log("the game's library directory is $gameLibDir")
            if (copyEngineFrom(java.io.File(gameLibDir), stage)) {
                log("engine staged for the app: ${stage.absolutePath} (route=app)")
                return true
            }
        } else {
            log("the game's library directory was not found from this app")
        }
        val fromApk = copyEngineFromApk(context, stage)
        if (fromApk) {
            log("engine staged from the game's APK: ${stage.absolutePath} (route=app/apk)")
            return true
        }

        // ---- route 2: the privileged helper ---------------------------
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
        if (staged.isFile && staged.length() >= MIN_ENGINE_BYTES) {
            log("engine staged for the app: ${stage.absolutePath} (route=helper)")
            return true
        }
        log("the helper ran but the engine is still not staged at ${staged.absolutePath}")
        return false
    }

    /**
     * Called when a bot-mode session starts.
     *
     * The engine belongs to the app's process, and the export's log only
     * covers the session, so this both reports the engine's state and, when
     * it is not up yet, gets it up -- in the log window, where the story can
     * be read afterwards.
     */
    fun ensureEngineForSession(context: Context): Boolean {
        val staged = File(stageDir(context), "libUE4.so")
        val stagedBytes = if (staged.isFile) staged.length() else 0L
        val loaded = EngineHost.base != 0L
        log("engine status: staged=$stagedBytes bytes loaded=$loaded")

        if (loaded) return true
        if (!stageEngine(context)) {
            log("the engine is not staged -- the bot answers the game itself this session")
            return false
        }
        val uid = context
            .getSharedPreferences("bot_mode_prefs", Context.MODE_PRIVATE)
            .getLong("uid", 0L)
        if (uid == 0L) {
            log("the bot's id is not known yet -- the engine waits")
            return false
        }
        val up = prepareWithRetries(context, uid)
        log(if (up) "the engine is up for this session" else "the engine did NOT come up this session")
        return up
    }

    /**
     * The engine's load, tried more than once.
     *
     * The session starts the engine within a second of the tunnel coming up,
     * and the app's own native library is loaded as part of that same start --
     * so the first ask can land a moment too early (that is what the phone's
     * log showed: "No implementation found ... is the library loaded?").
     * Three tries, five seconds apart, covers the first seconds of a session
     * without holding anything up; every later ask is a cheap no-op.
     */
    private fun prepareWithRetries(context: Context, uid: Long): Boolean {
        var up = EngineHost.prepare(context, uid)
        var attempt = 1
        while (!up && attempt < 3) {
            log("the engine did not come up on try $attempt -- the app's own " +
                "library may still be loading; trying again in five seconds")
            try {
                Thread.sleep(5_000)
            } catch (_: InterruptedException) {
                return up
            }
            attempt++
            up = EngineHost.prepare(context, uid)
        }
        return up
    }

    /**
     * The directory the installed game keeps its native libraries in, asked
     * of the package manager -- which knows the path an app is not allowed
     * to search for itself.
     */
    private fun gameLibDir(context: Context): String? = try {
        val info = context.packageManager.getApplicationInfo(GAME_PACKAGE, 0)
        val dir = info.nativeLibraryDir
        when {
            dir.isNullOrBlank() -> null
            java.io.File(dir, "libUE4.so").isFile -> dir
            else -> null
        }
    } catch (t: Throwable) {
        log("package lookup failed: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** Copy the engine (and its ad-library dependency) into [stage]. */
    private fun copyEngineFrom(srcDir: java.io.File, stage: java.io.File): Boolean {
        val src = java.io.File(srcDir, "libUE4.so")
        if (!src.isFile || src.length() < MIN_ENGINE_BYTES) {
            log("the engine is not readable at ${src.absolutePath}")
            return false
        }
        return try {
            if (!stage.isDirectory && !stage.mkdirs()) {
                log("cannot make the staging directory ${stage.absolutePath}")
                return false
            }
            copyFile(src, java.io.File(stage, "libUE4.so"))
            val ad = java.io.File(srcDir, "libgadsme.so")
            if (ad.isFile) copyFile(ad, java.io.File(stage, "libgadsme.so"))
            true
        } catch (t: Throwable) {
            log("staging the engine failed: ${t.javaClass.simpleName}: ${t.message}")
            false
        }
    }

    /**
     * Some games keep their libraries inside the APK instead of extracting
     * them.  Same idea, read from the archive.
     */
    private fun copyEngineFromApk(context: Context, stage: java.io.File): Boolean = try {
        val info = context.packageManager.getApplicationInfo(GAME_PACKAGE, 0)
        val apk = java.io.File(info.sourceDir)
        if (!apk.isFile) {
            false
        } else {
            java.util.zip.ZipFile(apk).use { zip ->
                val entry = zip.getEntry("lib/arm64-v8a/libUE4.so")
                if (entry == null) {
                    false
                } else {
                    if (!stage.isDirectory) stage.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        java.io.FileOutputStream(java.io.File(stage, "libUE4.so")).use { output ->
                            input.copyTo(output, 1024 * 1024)
                        }
                    }
                    val ad = zip.getEntry("lib/arm64-v8a/libgadsme.so")
                    if (ad != null) {
                        zip.getInputStream(ad).use { input ->
                            java.io.FileOutputStream(java.io.File(stage, "libgadsme.so")).use { output ->
                                input.copyTo(output, 1024 * 1024)
                            }
                        }
                    }
                    true
                }
            }
        }
    } catch (t: Throwable) {
        log("reading the game's APK failed: ${t.javaClass.simpleName}: ${t.message}")
        false
    }

    /** Copy one file, then make it readable and not writable. */
    private fun copyFile(src: java.io.File, dst: java.io.File) {
        java.io.FileInputStream(src).use { input ->
            java.io.FileOutputStream(dst).use { output ->
                input.copyTo(output, 1024 * 1024)
            }
        }
        dst.setReadable(true, false)
        dst.setWritable(false, false)
        dst.setExecutable(false, false)
    }

    private fun identify() {
        val context = appContext ?: return
        log("looking for the game's engine")

        val asApp = probeAsApp(
            context,
            hashOnly = true,
            timeoutMs = 45_000L,
            engine = gameLibDir(context)?.let { "$it/libUE4.so" },
        )
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
    private fun probeAsApp(
        context: Context,
        hashOnly: Boolean,
        timeoutMs: Long,
        engine: String? = null,
    ): String = try {
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
                if (!engine.isNullOrBlank()) {
                    add("--engine")
                    add(engine)
                }
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
