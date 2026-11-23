package com.peerlink.app.bot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class GameFileIntegrityTest {
    private fun withDirectory(test: (File) -> Unit) {
        val root = Files.createTempDirectory("game-file-audit").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }

    @Test fun detectsSameSizeCorruptionAndRepairsOnlyTheCopy() = withDirectory { root ->
        val source = File(root, "source").apply { writeText("original") }
        val copy = File(root, "copy").apply { writeText("corrupt!") }
        val logs = mutableListOf<String>()
        val advised = mutableListOf<String>()
        val check = GameFileIntegrity({ logs.add(it) }) { file ->
            assertEquals(copy, file)
            advised.add(file.readText())
        }
        check.ensure(copy, "test") { source.inputStream() }
        assertEquals("original", source.readText())
        assertEquals(source.readText(), copy.readText())
        assertEquals(1, check.repaired)
        assertTrue(logs.any { "MISMATCH" in it })
        assertTrue(logs.any { "REPAIRED" in it })
        assertEquals(listOf("original"), advised)
    }

    @Test fun validCopyIsReusedWithoutWriting() = withDirectory { root ->
        val copy = File(root, "copy").apply { writeText("good"); setLastModified(10000) }
        val before = copy.lastModified()
        val advised = mutableListOf<File>()
        val check = GameFileIntegrity({}) { advised.add(it) }
        check.ensure(copy, "test") { "good".byteInputStream() }
        assertEquals(before, copy.lastModified())
        assertEquals(0, check.repaired)
        assertEquals(1, check.verified)
        assertEquals(listOf(copy), advised)
    }

    @Test fun cacheHintsCoverOnlyCompleteAlreadyHashedWindows() {
        val window = 8 * 1024 * 1024
        val bytes = ByteArray(window * 2 + 991) { (it * 31).toByte() }
        val partialReads = object : java.io.ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, off: Int, length: Int) =
                super.read(buffer, off, minOf(length, 23171))
            fun consumed() = pos
        }
        val ranges = mutableListOf<Pair<Long, Long>>()
        val actual = GameFileIntegrity.fingerprint(partialReads) { offset, length ->
            assertTrue(offset + length <= partialReads.consumed())
            ranges.add(offset to length)
        }
        assertEquals(listOf(0L to window.toLong(), window.toLong() to window.toLong()), ranges)
        assertEquals(GameFileIntegrity.fingerprint(bytes.inputStream()), actual)
    }

    @Test fun missingAndTruncatedFilesAreRecovered() = withDirectory { root ->
        val copy = File(root, "nested/copy")
        val check = GameFileIntegrity {}
        check.ensure(copy, "test") { "original".byteInputStream() }
        copy.writeText("orig")
        check.ensure(copy, "test") { "original".byteInputStream() }
        assertEquals("original", copy.readText())
        assertEquals(2, check.repaired)
    }

    @Test fun changedSourceDoesNotReplacePreviousCopyOrLeaveTemporaryFiles() = withDirectory { root ->
        val copy = File(root, "copy").apply { writeText("old") }
        var opens = 0
        try {
            GameFileIntegrity {}.ensure(copy, "test") {
                (++opens).let { if (it == 1) "expected" else "changing" }.byteInputStream()
            }
            fail("Changed source accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("Source changed"))
        }
        assertEquals("old", copy.readText())
        assertEquals(listOf("copy"), root.listFiles()!!.map { it.name })
    }

    @Test fun warmStartChecksPrivateBytesAndRepairsCorruptionFromOriginal() = withDirectory { root ->
        val cacheFile = File(root, "fingerprints")
        val copy = File(root, "copy")
        val audit = GameFileIntegrity {}
        val cold = InstalledAssetFingerprints(cacheFile, "installation A")
        val expected = audit.ensure(copy, "pack", installedSource = cold["pack"]) { "good".byteInputStream() }
        cold["pack"] = expected
        assertTrue(cold.save())
        val warm = InstalledAssetFingerprints(cacheFile, "installation A")
        audit.ensure(copy, "pack", installedSource = warm["pack"]) { error("unexpected read on warm start") }
        copy.writeText("oops")
        audit.ensure(copy, "pack", installedSource = warm["pack"]) { "good".byteInputStream() }
        assertEquals("good", copy.readText())
        assertNull(InstalledAssetFingerprints(cacheFile, "installation B")["pack"])
        assertNull(warm["different-table"])
        cacheFile.writeText("corrupt")
        assertNull(InstalledAssetFingerprints(cacheFile, "installation A")["pack"])
    }

    @Test fun originalDatabaseSchemaRejectsWrongTableAndPartialRecord() {
        assertEquals(44, GameDatabaseSchema.strides.size)
        assertEquals(2, GameDatabaseSchema.validate("Player.bin", 800))
        for ((name, bytes) in listOf("Fake.bin" to 400, "Player.bin" to 401)) {
            try { GameDatabaseSchema.validate(name, bytes); fail("Invalid table accepted") }
            catch (expected: IllegalArgumentException) { }
        }
    }
}
