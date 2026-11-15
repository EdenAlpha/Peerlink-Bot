package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Corrupts only disposable app-owned copies, never the installed game's files. */
@RunWith(AndroidJUnit4::class)
class GameFileIntegrityPhoneTest {
    @Test(timeout = 300_000) fun verifiesInstalledSourcesAndRepairsCorruptedCachedCopies() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val lines = mutableListOf<String>()
        val audit: (String) -> Unit = { lines.add(it); Log.i("PeerlinkFileTest", it) }
        assertTrue(EngineBridge.stageEngine(context))
        val files = GameAssets.prepare(context, {}, audit)
        val database = File(files.database, "Ball.decoded")
        val native = File(files.root, "internal/UE4Game/PesMobile/PesMobile/Content/Paks")
            .listFiles()!!.filter { it.isFile && it.length() > 0 }.minBy { it.length() }
        val targets = listOf(database, native)
        val before = targets.map { it.inputStream().use { input -> GameFileIntegrity.fingerprint(input) } }
        targets.forEach { file ->
            RandomAccessFile(file, "rw").use { it.seek(0); val byte = it.read(); it.seek(0); it.write(byte xor 0x80) }
        }
        lines.clear()
        GameAssets.prepare(context, {}, audit)
        assertEquals(before, targets.map { it.inputStream().use { input -> GameFileIntegrity.fingerprint(input) } })
        assertEquals(2, lines.count { "MISMATCH" in it })
        assertEquals(2, lines.count { "REPAIRED" in it })
        assertTrue(lines.any { "SUMMARY result=PASS" in it && "repaired=2" in it })
        assertTrue(lines.any { "downloadedUpdates=NOT_CHECKED" in it })
        val retained = GameAssets.auditReportFile(context).readText()
        assertTrue(retained.contains("startedAtMillis="))
        assertTrue(retained.contains("SUMMARY result=PASS") && retained.contains("repaired=2"))
        // Session logging may begin after startup, or be reset with an already
        // running engine. The separate export source must survive that reset.
        com.peerlink.app.core.AppState.clearLogs()
        assertEquals(retained, GameAssets.auditReportFile(context).readText())
        Log.i("PeerlinkFileTest", "ORIGINAL_SOURCE_AUDIT_AND_SAME_SIZE_CACHE_REPAIR_PASSED")
    }
}
