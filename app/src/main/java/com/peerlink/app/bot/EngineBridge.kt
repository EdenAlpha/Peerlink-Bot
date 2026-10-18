package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import com.peerlink.app.godmode.PrimeClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The bridge's front door.
 *
 * The bot's own P2P half is meant to become the game's own engine -- the same
 * 160 MB library the real game runs -- fed the bot's id and squad. Everything
 * downstream of that depends on one question that has never been answered on
 * this phone:
 *
 *   can we reach the installed game's engine at all, and can we load it?
 *
 * This runs the native probe once per process, in the background, and puts the
 * answer in the app log where the user already reads. It is deliberately
 * small and read-only with respect to the game: it looks, hashes, copies, and
 * tries to open. It never writes to the game's storage.
 *
 * Facts it establishes, each of which was previously only assumed:
 *   - the exact path of the engine the installed game actually loads
 *   - its byte size and SHA-256  -> pins which build of the engine is on the
 *     phone, so every address we know can be re-anchored to THAT build
 *     instead of to the build we happen to hold on the PC
 *   - whether the loader will open it, and the base address it lands at
 *   - if it fails, the loader's own words for why
 *
 * The probe isolates the load in a child process: the engine runs ~11,900
 * registration functions the instant it is opened, and if one of them is
 * unhappy we want the signal number, not a dead helper.
 */
object EngineBridge {

    private val probeStarted = AtomicBoolean(false)

    /** Raw `KEY=value` report from the last probe, or null if it never ran. */
    @Volatile
    var lastReport: String? = null
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

    /**
     * Ask for the probe to be run. Safe to call from anywhere, any number of
     * times: only the first call in this process does work.
     */
    fun probeOnce() {
        if (!probeStarted.compareAndSet(false, true)) return
        Thread({ runProbe() }, "engine-probe").apply { isDaemon = true }.start()
    }

    private fun log(line: String) = AppState.appendLog("[ENGINE-BRIDGE] $line")

    private fun runProbe() {
        log("looking for the game's engine")
        val report = try {
            PrimeClient.engineProbe()
        } catch (t: Throwable) {
            log("probe threw ${t.javaClass.simpleName}: ${t.message}")
            null
        }
        if (report.isNullOrBlank()) {
            log("probe did not run -- the privileged helper is not up")
            return
        }
        lastReport = report

        for (line in report.lineSequence()) {
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            when (line.substring(0, eq)) {
                "UE4_SHA256" -> engineSha256 = line.substring(eq + 1)
                "UE4_ROM_SIZE" -> engineSize = line.substring(eq + 1).toLongOrNull() ?: 0L
                "UE4_ROM_PATH" -> enginePath = line.substring(eq + 1)
                "CHILD_BASE" -> loadBase = line.substring(eq + 1)
                "RESULT" -> verdict = line.substring(eq + 1)
            }
        }

        // The probe's own words for the parts that decide what happens next.
        for (line in report.lineSequence()) {
            if (line.startsWith("RESULT=") || line.startsWith("UE4_ROM_PATH=") ||
                line.startsWith("UE4_ROM_SIZE=") || line.startsWith("UE4_SHA256=") ||
                line.startsWith("STAGE_UE4=") || line.startsWith("CHILD_SIBLING=") ||
                line.startsWith("CHILD_LOAD=") || line.startsWith("CHILD_BASE=") ||
                line.startsWith("CHILD_TRY_") || line.startsWith("LOAD_SIGNAL=") ||
                line.startsWith("LOAD_EXIT=") || line.startsWith("LOAD_TIMEOUT=")
            ) {
                log(line)
            }
        }

        log(
            when (verdict) {
                "load_ok" -> "the game's engine LOADS on this phone (base $loadBase)"
                "engine_crashed_on_load" -> "the engine was opened but died loading"
                "load_failed" -> "the loader refused the engine -- see the error above"
                "no_game_library_found" -> "the installed game's library was not found"
                "engine_file_missing" -> "the engine file the game lists is missing"
                else -> "probe finished early with $verdict"
            }
        )
    }
}
