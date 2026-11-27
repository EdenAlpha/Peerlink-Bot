package com.peerlink.app.check

import android.app.Application
import android.content.Context
import android.os.SystemClock
import com.peerlink.app.bot.BotHeapRecording
import com.peerlink.app.godmode.PrimeExecResult
import com.peerlink.app.play.BotRoomBackend
import com.peerlink.app.play.PlayRoomController
import com.peerlink.app.play.RoomState
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Duration
import java.util.Collections
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowSystemClock

private enum class Scenario { SLOTS_FULL, EMPTY_TRACE, NO_PERFETTO, ENGINE_DIED, ORDINARY, VERIFIED, PRIME_OFF }

private object Phone {
    var scenario = Scenario.SLOTS_FULL
    var cleanups = 0
    var heapStarts = 0
    var schedulerStarts = 0
    var roomsOpened = 0
    var probes = 0
    var dumps = 0
    val stoppedTraces = Collections.synchronizedList(mutableListOf<String>())
    val success = PrimeExecResult("", 0, true)
    // Android 15 spells "sesions" this way in the shell error.
    val quota = PrimeExecResult("Service error: Too many concurrent tracing sesions (5) for uid 2000 limit is 5", 1, false)
}

@Implements(className = "com.peerlink.app.bot.BotPerfetto", isInAndroidSdk = false)
class RoomTracePlatformShadow {
    @Implementation fun shell(command: String, timeoutMs: Int): PrimeExecResult {
        if (command.contains("am force-stop")) {
            Phone.cleanups++
            if (Phone.scenario == Scenario.PRIME_OFF) return PrimeExecResult("Prime did not respond", -1, false)
            return PrimeExecResult("Final 931 MiB available (+42 MiB)", 0, true)
        }
        if (command.contains("command -v perfetto") && Phone.scenario == Scenario.NO_PERFETTO) {
            return PrimeExecResult("Perfetto is unavailable", 1, false)
        }
        return Phone.success
    }

    @Implementation fun start(key: String, config: String): PrimeExecResult {
        if (key.startsWith("peerlink-scheduler-")) {
            Phone.schedulerStarts++
            return if (Phone.scenario == Scenario.VERIFIED && Phone.schedulerStarts > 1) Phone.success else Phone.quota
        }
        Phone.heapStarts++
        return when (Phone.scenario) {
            Scenario.EMPTY_TRACE, Scenario.VERIFIED -> Phone.success
            else -> Phone.quota
        }
    }

    @Implementation fun stop(key: String): Boolean {
        Phone.stoppedTraces.add(key)
        return true
    }

    @Implementation fun copyPrefix(ctx: Context, key: String, dirName: String, output: File,
                                   maxBytes: Long, timeoutMs: Int): Boolean {
        if (Phone.scenario == Scenario.VERIFIED && key.startsWith("peerlink-bot-")) {
            val timestampNs = (++Phone.dumps) * 2_000_000_000L
            output.writeBytes(sampleTrace(timestampNs))
            return true
        }
        if (Phone.scenario == Scenario.EMPTY_TRACE && output.extension == "probe") {
            ShadowSystemClock.advanceBy(Duration.ofSeconds(5))
        }
        return false
    }
}

// One Perfetto TracePacket with a mapped allocation for the fixture's bot process.
private fun sampleTrace(timestampNs: Long): ByteArray {
    fun varint(value: Long) = ByteArrayOutputStream().apply {
        var remaining = value
        while (remaining >= 128) {
            write((remaining.toInt() and 127) or 128)
            remaining = remaining ushr 7
        }
        write(remaining.toInt())
    }.toByteArray()
    fun scalar(field: Int, value: Long) = varint(field * 8L) + varint(value)
    fun message(field: Int, bytes: ByteArray) = varint(field * 8L + 2) + varint(bytes.size.toLong()) + bytes
    val frame = message(6, scalar(1, 44) + scalar(3, 2) + scalar(4, 13000))
    val stack = message(7, scalar(1, 3) + message(2, varint(44)))
    val allocation = message(2, scalar(1, 3) + scalar(2, 65536))
    val dump = message(5, scalar(1, 1171) + allocation)
    return message(1, scalar(8, timestampNs) + scalar(10, 7) + message(12, frame + stack) + message(37, dump))
}

@Implements(className = "com.peerlink.app.godmode.PrimeClient", isInAndroidSdk = false)
class RoomPrimeShadow {
    @Implementation fun isAlive(timeoutMs: Int) = Phone.scenario != Scenario.PRIME_OFF
}

@Implements(className = "com.peerlink.app.bot.EngineHost", isInAndroidSdk = false)
class RoomEngineProcessShadow {
    @Implementation fun isReady() = Phone.scenario != Scenario.ENGINE_DIED
    @Implementation fun getEnginePid() = 1171
    @Implementation fun getLastError() = "Bot engine stopped"
    @Implementation(methodName = "probeHeapRecording\$app_debug") fun probe(pid: Int): Boolean {
        Phone.probes++
        return true
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, instrumentedPackages = ["com.peerlink.app.bot"],
    shadows = [RoomTracePlatformShadow::class, RoomEngineProcessShadow::class, RoomPrimeShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RecordingRoomRegressionTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private var startMatch: (() -> Unit)? = null

    @Before fun setUp() {
        SystemClock.setCurrentTimeMillis(1750000000000L)
        Phone.cleanups = 0
        Phone.heapStarts = 0
        Phone.schedulerStarts = 0
        Phone.roomsOpened = 0
        Phone.probes = 0
        Phone.dumps = 0
        Phone.stoppedTraces.clear()
        PlayRoomController.onRoomFailed("fixture reset")
        PlayRoomController.attach(object : BotRoomBackend {
            override suspend fun createRoom(ctx: Context, allowResourceDownload: Boolean,
                    verifyEngine: ((Int) -> Unit)?, onMatchStarting: (() -> Unit)?): String {
                verifyEngine?.invoke(1171)
                startMatch = onMatchStarting
                Phone.roomsOpened++
                return "12345678"
            }
        }, context)
    }

    private fun create(scenario: Scenario, record: Boolean = true): RoomState {
        Phone.scenario = scenario
        PlayRoomController.createRoom(recordMemory = record)
        return awaitRoom()
    }

    private fun awaitRoom(): RoomState {
        val deadline = System.nanoTime() + 40_000_000_000L
        while (PlayRoomController.state.value is RoomState.Creating && System.nanoTime() < deadline) Thread.sleep(20)
        return PlayRoomController.state.value
    }

    @Test fun replacingABlockedRoomUsesTheNormalRoomBackendOnce() {
        val oldRoom = create(Scenario.ENGINE_DIED, record = false) as RoomState.Ready
        PlayRoomController.roomRecovery("00000000", "The old match is still assigned.")
        PlayRoomController.replaceRoom(oldRoom.number)
        assertEquals(oldRoom, PlayRoomController.state.value)

        PlayRoomController.attach(object : BotRoomBackend {
            override suspend fun createRoom(ctx: Context, allowResourceDownload: Boolean,
                    verifyEngine: ((Int) -> Unit)?, onMatchStarting: (() -> Unit)?): String {
                Phone.roomsOpened++
                assertNull(verifyEngine)
                return "87654321"
            }
        }, context)
        PlayRoomController.roomRecovery(oldRoom.number, "The old match is still assigned.")
        PlayRoomController.replaceRoom(oldRoom.number)
        PlayRoomController.replaceRoom(oldRoom.number)
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((PlayRoomController.state.value is RoomState.Ending || PlayRoomController.state.value is RoomState.Creating) &&
            System.nanoTime() < deadline) Thread.sleep(20)

        val replacement = PlayRoomController.state.value as RoomState.Ready
        assertEquals("87654321", replacement.number)
        assertNull(replacement.recoveryReason)
        assertEquals(2, Phone.roomsOpened)
    }

    @Test fun quotaDoesNotCloseTheRoomOrLeaveTheSchedulerWaitingForAMissingHeapTrace() {
        val room = create(Scenario.SLOTS_FULL)
        assertTrue(room.toString(), room is RoomState.Ready)
        assertEquals("12345678", (room as RoomState.Ready).number)
        assertTrue(room.diagnosticsNote.orEmpty().contains("tracing slots were full"))
        assertEquals(1, Phone.cleanups)
        assertEquals(1, Phone.heapStarts)
        assertEquals(1, Phone.roomsOpened)
        assertTrue(Phone.stoppedTraces.any { it.startsWith("peerlink-bot-") })
        checkNotNull(startMatch).invoke()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!schedulerReport().contains("Scheduler recording failed") && System.nanoTime() < deadline) Thread.sleep(20)
        val report = schedulerReport()
        assertTrue(report, report.contains("Scheduler recording failed"))
        assertFalse(report, report.contains("waiting for the bot memory recording"))
        assertEquals(1, Phone.schedulerStarts)
    }

    private fun schedulerReport() = File(context.filesDir, "bot-scheduler").listFiles().orEmpty()
        .filter { it.extension == "txt" }.maxByOrNull { it.name }?.readText().orEmpty()

    @Test fun zeroTraceBytesStillOpenTheRoomAndSaveTheReason() {
        val room = create(Scenario.EMPTY_TRACE)
        assertTrue(room.toString(), room is RoomState.Ready)
        assertTrue((room as RoomState.Ready).diagnosticsNote.orEmpty().contains("no verified live memory samples"))
        assertEquals(1, Phone.cleanups)
        assertEquals(1, Phone.heapStarts)
        assertTrue(Phone.probes > 0)
        val files = File(context.filesDir, "bot-heap").listFiles().orEmpty()
        assertTrue(files.any { it.extension == "txt" && it.readText().contains("reason=no_live_trace_bytes") })
        assertFalse(files.any { it.extension == "pending" })
    }

    @Test fun missingProfilerStillAllowsCleanupAndRoomCreation() {
        val room = create(Scenario.NO_PERFETTO)
        assertTrue(room.toString(), room is RoomState.Ready)
        assertNotNull((room as RoomState.Ready).diagnosticsNote)
        assertEquals(1, Phone.cleanups)
        assertEquals(0, Phone.heapStarts)
        assertEquals(1, Phone.roomsOpened)
    }

    @Test fun actualEngineDeathStillFailsRoomCreation() {
        val room = create(Scenario.ENGINE_DIED)
        assertTrue(room.toString(), room is RoomState.Failed)
        assertEquals("Bot engine stopped", (room as RoomState.Failed).reason)
        assertEquals(0, Phone.roomsOpened)
    }

    @Test fun workingHeapRecordingStillSavesAndReleasesItsSlotAtThePitch() {
        val room = create(Scenario.VERIFIED)
        assertTrue(room.toString(), room is RoomState.Ready)
        assertNull((room as RoomState.Ready).diagnosticsNote)
        assertTrue(BotHeapRecording.observing(1171))
        checkNotNull(startMatch).invoke()
        val untilWaiting = System.nanoTime() + 5_000_000_000L
        while (!schedulerReport().contains("waiting for the bot memory recording") &&
            System.nanoTime() < untilWaiting) Thread.sleep(20)
        assertTrue(schedulerReport().contains("waiting for the bot memory recording"))
        BotHeapRecording.completed(context, 1171, "pitch_loaded")
        val dir = File(context.filesDir, "bot-heap")
        val untilSaved = System.nanoTime() + 5_000_000_000L
        while (dir.listFiles().orEmpty().none { it.extension == "pftrace" } &&
            System.nanoTime() < untilSaved) Thread.sleep(20)
        assertTrue(dir.listFiles().orEmpty().any { it.extension == "pftrace" && it.length() > 0 })
        assertEquals(2, Phone.schedulerStarts)
    }

    @Test fun primeOffPromptsForCleanupAndDoesNotOpenAnUncleanedRoom() {
        val room = create(Scenario.PRIME_OFF)
        assertTrue(room.toString(), room is RoomState.Failed)
        assertTrue((room as RoomState.Failed).reason.contains("Activate Prime"))
        assertEquals(0, Phone.heapStarts)
        assertEquals(0, Phone.roomsOpened)
    }

    @Test fun clearRamButtonOpensTheRoomWithOneCleanupAndNoHeavyProbesByDefault() {
        Phone.scenario = Scenario.EMPTY_TRACE
        val directory = File(context.filesDir, "bot-scheduler").apply { mkdirs() }
        val previous = File(directory, "1700000000000-0123456789ab.pending")
        previous.writeText("scheduler")
        assertFalse(BotHeapRecording.enabledForRooms(context))
        PlayRoomController.createRoomForPlay(clearRam = true)
        val room = awaitRoom() as RoomState.Ready
        assertNull(room.diagnosticsNote)
        assertEquals(1, Phone.cleanups)
        assertEquals(1, Phone.roomsOpened)
        assertEquals(0, Phone.heapStarts)
        assertEquals(0, Phone.probes)
        assertTrue(previous.isFile)
        assertTrue(Phone.stoppedTraces.isEmpty())
        assertNull(startMatch)
        assertTrue(schedulerReport().contains("RAM cleanup exit=0"))
    }

    @Test fun detailedRecordingCanBeSelectedForTheClearRamButton() {
        Phone.scenario = Scenario.SLOTS_FULL
        BotHeapRecording.setEnabledForRooms(context, true)
        try {
            PlayRoomController.createRoomForPlay(clearRam = true)
            val room = awaitRoom() as RoomState.Ready
            assertTrue(room.diagnosticsNote.orEmpty().contains("tracing slots were full"))
            assertEquals(1, Phone.heapStarts)
            assertNotNull(startMatch)
        } finally {
            BotHeapRecording.setEnabledForRooms(context, false)
        }
    }

    @Test fun primeUnavailableDuringCleanupOffersTheSameCleanupOnRetry() {
        Phone.scenario = Scenario.PRIME_OFF
        PlayRoomController.createRoomForPlay(clearRam = true)
        assertTrue((awaitRoom() as RoomState.Failed).clearRamOnRetry)
        assertEquals(0, Phone.roomsOpened)
        assertEquals(0, Phone.heapStarts)
    }

    @Test fun ordinaryRoomSkipsCleanupAndRecording() {
        val room = create(Scenario.ORDINARY, record = false)
        assertTrue(room.toString(), room is RoomState.Ready)
        assertNull((room as RoomState.Ready).diagnosticsNote)
        assertEquals(0, Phone.cleanups)
        assertEquals(0, Phone.heapStarts)
        assertNull(startMatch)
    }
}
