package com.peerlink.app.bot

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [35], application = android.app.Application::class)
class ReceivedSquadFilesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val folder get() = File(context.filesDir, "received-squads")

    @Before fun clear() { folder.deleteRecursively() }
    @After fun cleanup() { folder.deleteRecursively() }

    @Test fun readyCaptureKeepsTheExactEngineInputAlongsideAReadableReport() {
        val id = "1795759907842-65"
        ReceivedSquadFiles.start(context, id)
        val capture = ReceivedSquadCapture.decode(ReceivedSquadCaptureTest.fixture())
        ReceivedSquadFiles.save(context, id, 1, capture)
        val files = ReceivedSquadFiles.exportedFiles(context)
        assertEquals(4, files.size)
        assertArrayEquals(capture.engineInput, files.single { it.extension == "dto" }.readBytes())
        val report = JSONObject(files.single { it.name == "$id-1.json" }.readText())
        assertTrue(report.getBoolean("engine_input_compatible"))
        assertEquals(23, report.getInt("selected_players"))
        assertEquals(147, report.getInt("parameters_per_player"))
        assertEquals(capture.engineInput?.let(ReceivedSquadCapture::sha256), report.getString("engine_input_sha256"))
    }

    @Test fun partialCaptureRetainsRawEvidenceAndExplainsWhyThereIsNoLoadableInput() {
        val id = "1795759907842-66"
        ReceivedSquadFiles.start(context, id)
        val raw = ReceivedSquadCaptureTest.fixture()
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).putInt(8, 5).putInt(28, 1)
            .putInt(48 + 864 + 16, -1)
        ReceivedSquadFiles.save(context, id, 1, ReceivedSquadCapture.decode(raw))
        val files = ReceivedSquadFiles.exportedFiles(context)
        assertTrue(files.none { it.extension == "dto" })
        assertArrayEquals(raw, files.single { it.name.endsWith(".capture.bin") }.readBytes())
        val report = JSONObject(files.single { it.name == "$id-1.json" }.readText())
        assertFalse(report.getBoolean("engine_input_compatible"))
        assertEquals("attributes_incomplete", report.getString("status"))
        assertEquals(1, report.getInt("missing_parameters"))
    }

    @Test fun unfinishedWritesStayOutOfTheZipAndThreeEngineSessionsAreRetained() {
        val capture = ReceivedSquadCapture.decode(ReceivedSquadCaptureTest.fixture())
        for (pid in 10..13) {
            val id = "1795759907842-$pid"
            ReceivedSquadFiles.start(context, id)
            ReceivedSquadFiles.save(context, id, 1, capture)
        }
        File(folder, "1795759907842-13-2.dto").writeBytes(byteArrayOf(1))
        File(folder, ".squad-unfinished.tmp").writeBytes(byteArrayOf(2))
        val files = ReceivedSquadFiles.exportedFiles(context)
        assertTrue(files.none { "-10-" in it.name || it.name.endsWith("-2.dto") || it.extension == "tmp" })
        assertEquals(12, files.size)
    }
}
