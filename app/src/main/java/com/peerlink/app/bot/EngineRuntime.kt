package com.peerlink.app.bot

import android.content.Context
import android.app.ActivityManager
import com.peerlink.app.core.AppState
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Original game runtime, confined to the private :botengine process. */
internal object EngineRuntime {
    const val ENGINE_SHA256 = "2cea0c85344d7d7f295aea1c2353621f02db78dbccd888a0a21a0508ac330659"
    const val BLOCK_SIZE = 0x80
    const val RECORD_SIZE = 0x218

    @Volatile var base: Long = 0L
        private set
    @Volatile var client: Long = 0L
        private set
    @Volatile private var ownerUid = 0L
    private var gameHost: GameAndroidRuntime? = null
    private var loadedEngineFile: File? = null
    private var discovery: BotDiscoveryServer? = null
    var report: (String) -> Unit = {}
    var audit: (String) -> Unit = {}
    @Volatile var startupRetryable = true
        private set
    @Volatile var savedDatabasePack: GameDatabaseUpdate.Pack? = null
        private set
    @Volatile private var runtimeThread: Thread? = null
    private val runtime = ScheduledThreadPoolExecutor(1) { action ->
        Thread(action, "engine-runtime").apply {
            isDaemon = true
            runtimeThread = this
        }
    }
    private var heartbeatStarted = false
    @Volatile private var trimEngineCache = false

    private val nativeAvailable = try {
        System.loadLibrary("peerlinkbackend")
        true
    } catch (error: LinkageError) {
        AppState.appendLog("[ENGINE-HOST] native library unavailable: ${error.message}")
        false
    }

    private fun <T> onRuntime(action: () -> T): T {
        if (Thread.currentThread() === runtimeThread) return action()
        return runtime.submit(Callable { action() }).get(60, TimeUnit.SECONDS)
    }

    /** A small, version-pinned set of player edit records bundled with the app. */
    private fun stageEditSupplement(context: Context): File {
        val staged = File.createTempFile("peerlink-edit-work-", ".bin", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            // AAPT expands assets with a .gz suffix and removes that suffix while
            // packaging. Keep the gzip bytes under a neutral extension so the
            // runtime sees the exact filename and compressed data it expects.
            GZIPInputStream(context.assets.open("game-host/editwork-supplement-11.1.1.dat")).use { input ->
                staged.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        check(size <= 1_842_080L) { "Player data supplement is too large" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(size == 1_842_080L && hash == "da2bd7c92c3417cf47d4916a52e8dee111fc3e5cab6a724bd427d6b0276cd29a") {
                "Player data supplement could not be verified"
            }
            return staged
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    /** Refuse unknown builds before executing any absolute-offset calls. */
    private fun verifiedEngine(path: File, source: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        path.inputStream().buffered().use { input ->
            val bytes = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        audit("[BOT-FILES] ENGINE_BUILD source=$source result=${if (actual == ENGINE_SHA256) "PASS" else "FAIL"} " +
            "actualSHA256=$actual supportedSHA256=$ENGINE_SHA256")
        return actual == ENGINE_SHA256
    }

    private fun load(staged: File, installed: File?): File? {
        loadedEngineFile?.let { return it }
        if (installed != null && installed.isFile && installed.canRead()) {
            val verified = try {
                verifiedEngine(installed, "installed")
            } catch (_: java.io.IOException) {
                false
            }
            if (verified) {
                // A read-only mapping of the game's installed ELF shares clean
                // pages with the foreground game, even in separate processes.
                base = nativeLoad(installed.absolutePath)
                if (base != 0L) {
                    loadedEngineFile = installed
                    audit("[BOT-FILES] ENGINE_MAPPING source=installed")
                    return installed
                }
                audit("[BOT-FILES] ENGINE_MAPPING installed=rejected fallback=staged")
            }
        }
        if (!verifiedEngine(staged, "staged")) {
            report("Unsupported eFootball build — this bot test requires the verified 11.1.1 engine")
            return null
        }
        base = nativeLoad(staged.absolutePath)
        if (base == 0L) return null
        loadedEngineFile = staged
        audit("[BOT-FILES] ENGINE_MAPPING source=staged")
        return staged
    }

    @Synchronized
    fun prepare(context: Context, uid: Long): Boolean {
        startupRetryable = true
        if (!nativeAvailable || uid !in 1..0xffffffffL) return false
        val app = context.applicationContext ?: context
        val selectedPack = try {
            GameDatabaseUpdate.selectedPack(app)
        } catch (error: Exception) {
            startupRetryable = false
            report(error.message ?: "PeerLink could not check its saved eFootball data.")
            return false
        }
        if (client != 0L) {
            if (ownerUid == uid) {
                if (savedDatabasePack == selectedPack) return true
                report("Restart the bot session to change its eFootball data.")
                return false
            }
            AppState.appendLog("[ENGINE-HOST] another account owns this runtime; restart the app before switching")
            return false
        }
        return try {
            val journalDirectory = File(app.filesDir, "engine-events").apply { mkdirs() }
            val journalName = "${System.currentTimeMillis()}-${android.os.Process.myPid()}.log"
            val journalFile = File(journalDirectory, journalName)
            val journalOpened = nativeOpenJournal(journalFile.absolutePath)
            val keeperDirectory = File(app.filesDir, "keeper-events").apply { mkdirs() }
            val keeperOpened = nativeOpenKeeperJournal(File(keeperDirectory, journalName).absolutePath)
            audit("ENGINE_EVENT_JOURNAL opened=$journalOpened keeperTrace=$keeperOpened pid=${android.os.Process.myPid()}")
            if (journalOpened) {
                journal("APP_BUILD version=${com.peerlink.app.BuildConfig.VERSION_NAME} source=${com.peerlink.app.BuildConfig.SOURCE_COMMIT}")
            }
            for ((directory, opened) in listOf(journalDirectory to journalOpened, keeperDirectory to keeperOpened)) {
                if (!opened) continue
                directory.listFiles()?.filter { it.isFile && it.name.matches(Regex("[0-9]+-[0-9]+\\.log")) }
                    ?.sortedByDescending { it.name }?.drop(8)?.forEach { it.delete() }
            }
            val engine = File(app.filesDir, "engine/libUE4.so")
            check(android.os.Build.VERSION.SDK_INT >= 31) { "Bot engine requires Android 12 or later" }
            if (!engine.isFile) return false
            val headless = BotRendering.headless(app)
            val deviceMemory = ActivityManager.MemoryInfo()
            app.getSystemService(ActivityManager::class.java).getMemoryInfo(deviceMemory)
            trimEngineCache = headless && deviceMemory.totalMem in 1..(4L * 1024 * 1024 * 1024)
            // EngineBridge pins the staged library before this service starts.
            // Complete the asset scan and database decode before dlopen runs the
            // engine's constructors and increases the bot's resident memory.
            var readCacheAdvised = 0L
            val files = GameAssets.prepare(app, report, audit, allowMissingMatchResources = headless,
                releaseVerifiedCache = { file -> nativeReleaseVerifiedFileCache(file.absolutePath) },
                fingerprintPrivateFile = { file ->
                    if (!trimEngineCache || file.length() < 32L * 1024 * 1024) {
                        GameFileIntegrity.fingerprintFile(file)
                    } else {
                        val descriptor = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                        android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                            GameFileIntegrity.fingerprint(input) { offset, length ->
                                if (nativeReleaseReadWindow(descriptor.fd, offset, length)) readCacheAdvised += length
                            }
                        }
                    }
                })
            journal("BOT_MEMORY_CACHE phase=verification_read advisedBytes=$readCacheAdvised")
            val installedEngine = files.game.applicationInfo.nativeLibraryDir?.let { File(it, "libUE4.so") }
            val mappedEngine = onRuntime { load(engine, installedEngine) } ?: return false
            if (mappedEngine != engine) {
                val advised = nativeReleaseVerifiedFileCache(engine.absolutePath)
                audit("BOT_MEMORY_CACHE phase=unused_staged_engine advisedBytes=$advised")
            }
            if (headless) {
                val enabled = nativeEnableHeadless(base, files.missingMatchResources > 0)
                audit("[BOT-FILES] HEADLESS_SETUP result=${if (enabled) "PASS" else "FAIL"} ${nativeHeadlessDiagnostics(base)}")
                if (!enabled) {
                    startupRetryable = false
                    error("The graphics-free bot could not start. Export diagnostics for the startup details.")
                }
            }
            report("Starting bot simulation")
            check(nativeConfigureFiles(base, File(files.root, "assets").absolutePath)) { "Bot asset directory is unavailable" }
            // NativeActivity creates its own NativeCode. Its surface belongs
            // to the bot service and is never launched as a display task.
            if (gameHost == null) gameHost = GameAndroidRuntime.create(app, files.game, files.root, mappedEngine)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
            var nextReport = 0L
            while (!nativeApplicationReady(base)) {
                if (headless && nativeHeadlessState(base) < 0) {
                    startupRetryable = false
                    audit("[BOT-FILES] HEADLESS_INITIALIZE result=FAIL ${nativeHeadlessDiagnostics(base)}")
                    error("The graphics-free bot could not initialize. Export diagnostics for the startup details.")
                }
                if (System.nanoTime() >= nextReport) {
                    android.util.Log.i("PeerlinkEngineHost", nativeStartupInfo(base))
                    nextReport = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                }
                check(System.nanoTime() < deadline) { "Bot simulation startup timed out" }
                Thread.sleep(200)
            }
            if (headless) {
                val initialized = nativeHeadlessState(base) == 1
                audit("[BOT-FILES] HEADLESS_INITIALIZE result=${if (initialized) "PASS" else "FAIL"} ${nativeHeadlessDiagnostics(base)}")
                if (!initialized) {
                    startupRetryable = false
                    error("Graphics-free bot did not initialize")
                }
            }
            check(files.savedDatabasePack == selectedPack) { "The selected update changed during startup. Try again." }
            check(savedDatabasePack == null || savedDatabasePack == selectedPack) {
                "Restart the bot session to change its eFootball data."
            }
            check(onRuntime { nativeLoadDatabase(base, files.database.absolutePath) }) { "Bot game database could not start" }
            if (selectedPack != null) {
                report("Loading the saved eFootball update")
                startupRetryable = false
                val result = onRuntime { nativeApplySavedDatabase(base, selectedPack.playerCount) }
                audit("[BOT-FILES] SAVED_DATABASE_UPDATE result=$result SHA256=${selectedPack.fingerprint.sha256}")
                check(result == 1) {
                    when (result) {
                        -2, -3 -> "The engine could not open the saved eFootball update."
                        -4 -> "The engine could not reload its database."
                        -5 -> "The engine loaded different player data from the saved update."
                        -6 -> "The engine timed out loading the saved eFootball update."
                        -7 -> "Restart the bot session before retrying the saved update."
                        else -> "The bot's database was unavailable."
                    }
                }
                savedDatabasePack = selectedPack
                startupRetryable = true
            } else {
                val editSupplement = stageEditSupplement(app)
                try {
                    check(onRuntime { nativeMergeEditRecords(base, editSupplement.absolutePath) }) {
                        "Bot player data could not be prepared"
                    }
                } finally {
                    editSupplement.delete()
                }
            }
            // The original Java resume callback checks kernel phase == 1.
            // Framework onResume runs before our native window is attached;
            // deliver its game-side notification once after the kernel/data
            // startup, matching the successful full-application host.
            gameHost?.engineReady()
            onRuntime {
                if (headless) {
                    if (discovery == null) discovery = BotDiscoveryServer(audit)
                    check(nativeConfigureLocalDiscovery(base, BotDiscoveryServer.ADDRESS)) {
                        "The bot's local connection settings were rejected"
                    }
                }
                client = nativeStart(base, uid)
                if (client == 0L) return@onRuntime false
                ownerUid = uid
                releaseUnusedMemory("engine_prepared")
                // The original application's game thread owns heartbeat, message
                // dispatch and model updates. A second manual tick races it.
                report("Bot simulation started — waiting for your game")
                true
            }
        } catch (error: Exception) {
            report("Bot could not start: ${error.cause?.message ?: error.message}")
            false
        } catch (error: LinkageError) {
            report("Bot engine unavailable: ${error.message}")
            false
        }
    }

    fun setPeers(record: ByteArray): Boolean = record.size == RECORD_SIZE && onRuntime {
        client != 0L && nativeSetPeers(base, record)
    }

    /** Accepted by the original session manager; does not assert connectivity. */
    fun openSession(peerUid: Long, connectingStart: Long): Boolean = onRuntime {
        client != 0L && nativeOpenSession(base, peerUid, connectingStart)
    }
    fun beginIntroduction(peerUid: Long, connectingStart: Long): Boolean = onRuntime {
        client != 0L && nativeBeginIntroduction(base, peerUid, connectingStart)
    }
    fun transportIoReady(): Int = onRuntime { if (client == 0L) -1 else nativeTransportIoReady(base) }

    fun configureTurn(input: ByteArray): Boolean = onRuntime {
        client != 0L && nativeConfigureTurn(base, input)
    }

    internal fun configureServerSettings(settings: MatchServerSettings): Boolean = onRuntime {
        client != 0L && nativeConfigureServerSettings(base, settings.revisionCheck,
            settings.protocolOptions, settings.onlineOptions, settings.slowdownThresholds.toIntArray())
    }

    fun databaseDiagnostics(): String = onRuntime { nativeDatabaseDiagnostics(base) }
    fun verifySavedDatabase(): Boolean = onRuntime {
        val pack = savedDatabasePack
        pack != null && nativeVerifySavedDatabase(base, pack.playerCount)
    }
    fun networkStartupDiagnostics(): String = onRuntime {
        nativeNetworkStartupDiagnostics(base) + " " + (discovery?.diagnostics() ?: "localStunReady=false")
    }
    fun loadingDiagnostics(): String = onRuntime { nativeLoadingDiagnostics(base) }
    fun turnRequest(): ByteArray? = onRuntime { if (client == 0L) null else nativeTurnRequest(base) }

    fun turnAddress(): ByteArray? = onRuntime { if (client == 0L) null else nativeTurnAddress(base) }

    fun peerTurnAddress(input: ByteArray): Boolean = onRuntime {
        client != 0L && nativePeerTurnAddress(base, input)
    }

    /** Compatibility status query, derived only from published native states.
     * The SDK's check routine performs network work and belongs to the app's
     * original runtime; it must never be called by a status watcher. */
    fun check(): Long = if (EngineSnapshot(snapshot(passive = true)).transportConnected) 1L else 0L

    fun buildBlock(family: Int, address: String, port: Int): ByteArray? = onRuntime {
        if (base == 0L) return@onRuntime null
        val pointer = nativeAlloc(BLOCK_SIZE)
        if (pointer == 0L) return@onRuntime null
        try {
            if (nativeBuildBlock(base, pointer, family, address, port) == 0L) null
            else ByteArray(BLOCK_SIZE).also { nativeRead(pointer, it) }
        } finally {
            nativeFree(pointer)
        }
    }

    /** Original SDK input layout; NAT words are 32-bit values, not pointers. */
    fun buildPeerRecord(
        peerId: Long,
        v4Host: String, v4HostPort: Int, v4Reflexive: String, v4ReflexivePort: Int, v4NatType: Long,
        v6Host: String, v6HostPort: Int, v6Reflexive: String, v6ReflexivePort: Int, v6NatType: Long,
    ): ByteArray? {
        if (peerId !in 1..0xffffffffL || v4NatType !in 0..0xffffffffL || v6NatType !in 0..0xffffffffL) return null
        val blocks = listOf(
            0x00c to (buildBlock(1, v4Host, v4HostPort) ?: return null),
            0x08c to (buildBlock(1, v4Reflexive, v4ReflexivePort) ?: return null),
            0x110 to (buildBlock(2, v6Host, v6HostPort) ?: return null),
            0x190 to (buildBlock(2, v6Reflexive, v6ReflexivePort) ?: return null),
        )
        return ByteArray(RECORD_SIZE).also { record ->
            putInt(record, 8, peerId.toInt())
            for ((offset, block) in blocks) block.copyInto(record, offset)
            putInt(record, 0x10c, v4NatType.toInt())
            putInt(record, 0x210, v6NatType.toInt())
            putInt(record, 0x214, -1)
            // The JNI adapter supplies the original interface vtable in its
            // owned copy before submitting the record to the original SDK.
        }
    }

    private fun putInt(bytes: ByteArray, offset: Int, value: Int) {
        for (i in 0..3) bytes[offset + i] = (value ushr (8 * i)).toByte()
    }

    private fun storeSquad(squad: ByteArray): File {
        val root = File(gameHost!!.activity().filesDir.parentFile, "match-inputs").apply { mkdirs() }
        return File(root, "squad.dto").apply { writeBytes(squad) }
    }

    fun prepareChannels(peerUid: Long, squad: ByteArray, homeUid: Long): Boolean = onRuntime {
        if (client == 0L || peerUid == ownerUid || (homeUid != ownerUid && homeUid != peerUid)) return@onRuntime false
        nativePrepareChannels(base, peerUid, homeUid, storeSquad(squad).absolutePath)
    }

    fun setupMatch(peerUid: Long, gameId: Long, sid: String, category: String,
                   keyword: String, squad: ByteArray, roomRules: ByteArray): Boolean = onRuntime {
        if (client == 0L || peerUid == ownerUid || gameId <= 0) return@onRuntime false
        val squadFile = storeSquad(squad)
        val root = squadFile.parentFile!!
        val sidBytes = sid.toByteArray(Charsets.UTF_8)
        val categoryBytes = category.toByteArray(Charsets.UTF_8)
        val keywordBytes = keyword.toByteArray(Charsets.ISO_8859_1)
        require(sidBytes.size in 1..512 && categoryBytes.size in 1..64 && keywordBytes.size == 8)
        val input = java.nio.ByteBuffer.allocate(32 + sidBytes.size + categoryBytes.size + 8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val teamId = java.nio.ByteBuffer.wrap(squad).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt(12)
        input.put(byteArrayOf(82, 71, 73, 49)).putInt(ownerUid.toInt()).putLong(gameId)
            .putInt(teamId).putInt(sidBytes.size).putInt(categoryBytes.size).putInt(8)
            .put(sidBytes).put(categoryBytes).put(keywordBytes)
        val contextFile = File(root, "context.dto").apply { writeBytes(input.array()) }
        require(roomRules.size in 72..776) { "The room's native match rules are missing or invalid" }
        val rulesFile = File(root, "room-rules.dto").apply { writeBytes(roomRules) }
        nativeSetupMatch(base, peerUid, squadFile.absolutePath, contextFile.absolutePath, rulesFile.absolutePath)
    }

    fun snapshot(passive: Boolean = false): LongArray = onRuntime {
        if (base == 0L) LongArray(34) else nativeSnapshot(base, passive)
    }
    fun advanceMatch(controllerAllowed: Boolean): Int = onRuntime {
        if (base == 0L) -1 else nativeAdvanceMatch(base, controllerAllowed)
    }
    fun inputKey(code: Int, down: Boolean): Boolean = onRuntime {
        if (base == 0L) false else nativeInputKey(base, code, if (down) 0 else 1)
    }
    fun halftimeConfirm(): Boolean = onRuntime { base != 0L && nativeHalftimeConfirm(base) }
    fun playState(): BotPlayState? = onRuntime {
        if (base == 0L) null else BotPlayState.decode(nativePlayState(base))
    }
    fun inputMotion(x: Float, y: Float, dash: Boolean): Boolean = onRuntime {
        base != 0L && nativeInputMotion(base, x, y, if (dash) 1f else 0f)
    }
    fun neutral() = onRuntime { if (base != 0L) nativeNeutralInput(base) }
    fun probeController(): Boolean = onRuntime { base != 0L && nativeProbeController(base) }
    fun journal(line: String) { if (nativeAvailable) nativeJournal(line) }
    fun setupMemoryGroups(checkpoint: String): String = if (nativeAvailable) {
        nativeSetupMemoryGroups(checkpoint == "baseline", checkpoint == "setup_followup")
    } else "status=unavailable"
    fun probeHeapRecording(): Boolean = nativeAvailable && nativeHeapRecordingProbe(BotHeapRecording.PROBE_BYTES)
    fun reclaimMatchPool() {
        val loaded = base
        if (nativeAvailable && trimEngineCache && loaded != 0L) nativeReclaimMatchPool(loaded)
    }
    fun releaseUnusedMemory(trigger: String) = onRuntime {
        val started = android.os.SystemClock.elapsedRealtime()
        val engineCacheDropped = if (trimEngineCache) nativeReleaseEngineCache(base) else -3L
        val result = nativeReleaseUnusedMemory()
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        // The sampler already measures PSS off this thread during loading.
        audit("BOT_MEMORY_RECLAIM trigger=$trigger eagerRelease=${result and 2 != 0} " +
            "purgeAccepted=${result and 1 != 0} durationMs=$elapsed engineCacheDroppedBytes=$engineCacheDropped")
    }
    fun probePreviewCallback(): Boolean = onRuntime { base != 0L && nativePreviewProbe(base) }
    fun stopPreview() = onRuntime { if (base != 0L) nativeStopPreview(base) }
    fun closeHost() {
        stopPreview(); gameHost?.close(); gameHost = null
        discovery?.close(); discovery = null
    }

    private external fun nativeLoad(path: String): Long
    private external fun nativeOpenJournal(path: String): Boolean
    private external fun nativeOpenKeeperJournal(path: String): Boolean
    private external fun nativeJournal(line: String)
    private external fun nativeEnableHeadless(base: Long, missingMatchResources: Boolean): Boolean
    private external fun nativeHeadlessState(base: Long): Int
    private external fun nativeHeadlessDiagnostics(base: Long): String
    private external fun nativeConfigureLocalDiscovery(base: Long, address: String): Boolean
    private external fun nativeBeginIntroduction(base: Long, peer: Long, connectingStart: Long): Boolean
    private external fun nativeTransportIoReady(base: Long): Int
    private external fun nativeConfigureTurn(base: Long, input: ByteArray): Boolean
    private external fun nativeConfigureServerSettings(base: Long, revisionCheck: Boolean,
        protocolOptions: String, onlineOptions: String, slowdownThresholds: IntArray): Boolean
    private external fun nativeDatabaseDiagnostics(base: Long): String
    private external fun nativeNetworkStartupDiagnostics(base: Long): String
    private external fun nativeLoadingDiagnostics(base: Long): String
    private external fun nativeTurnRequest(base: Long): ByteArray?
    private external fun nativeTurnAddress(base: Long): ByteArray?
    private external fun nativePeerTurnAddress(base: Long, input: ByteArray): Boolean
    private external fun nativeConfigureFiles(base: Long, assets: String): Boolean
    private external fun nativeMergeEditRecords(base: Long, path: String): Boolean
    private external fun nativeApplicationReady(base: Long): Boolean
    private external fun nativeStartupInfo(base: Long): String
    private external fun nativeLoadDatabase(base: Long, path: String): Boolean
    private external fun nativeApplySavedDatabase(base: Long, expectedPlayers: Int): Int
    private external fun nativeVerifySavedDatabase(base: Long, expectedPlayers: Int): Boolean
    private external fun nativePrepareChannels(base: Long, peer: Long, home: Long, squad: String): Boolean
    private external fun nativeSetupMatch(base: Long, peer: Long, squad: String, context: String, roomRules: String): Boolean
    private external fun nativeSnapshot(base: Long, passive: Boolean): LongArray
    private external fun nativeAdvanceMatch(base: Long, controllerAllowed: Boolean): Int
    private external fun nativeReleaseUnusedMemory(): Int
    private external fun nativeSetupMemoryGroups(baseline: Boolean, last: Boolean): String
    private external fun nativeHeapRecordingProbe(bytes: Int): Boolean
    private external fun nativeReleaseReadWindow(fd: Int, offset: Long, length: Long): Boolean
    private external fun nativeReclaimMatchPool(base: Long)
    private external fun nativeReleaseEngineCache(base: Long): Long
    private external fun nativeReleaseVerifiedFileCache(path: String): Long
    private external fun nativeHalftimeConfirm(base: Long): Boolean
    private external fun nativeInputKey(base: Long, code: Int, action: Int): Boolean
    private external fun nativePlayState(base: Long): IntArray?
    private external fun nativeInputMotion(base: Long, x: Float, y: Float, dash: Float): Boolean
    private external fun nativeNeutralInput(base: Long)
    private external fun nativeProbeController(base: Long): Boolean
    private external fun nativePreviewProbe(base: Long): Boolean
    private external fun nativeStopPreview(base: Long)
    private external fun nativeStart(base: Long, uid: Long): Long
    private external fun nativeSetPeers(base: Long, record: ByteArray): Boolean
    private external fun nativeOpenSession(base: Long, peer: Long, connectingStart: Long): Boolean
    private external fun nativeCall(base: Long, offset: Long, a0: Long, a1: Long, a2: Long, a3: Long): Long
    private external fun nativeAlloc(size: Int): Long
    private external fun nativeFree(address: Long)
    private external fun nativeRead(address: Long, bytes: ByteArray)
    private external fun nativeBuildBlock(base: Long, destination: Long, family: Int, address: String, port: Int): Long
}
