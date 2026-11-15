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
        val check = GameFileIntegrity { logs.add(it) }
        check.ensure(copy, "test") { source.inputStream() }
        assertEquals("original", source.readText())
        assertEquals(source.readText(), copy.readText())
        assertEquals(1, check.repaired)
        assertTrue(logs.any { "MISMATCH" in it })
        assertTrue(logs.any { "REPAIRED" in it })
    }

    @Test fun validCopyIsReusedWithoutWriting() = withDirectory { root ->
        val copy = File(root, "copy").apply { writeText("good"); setLastModified(10000) }
        val before = copy.lastModified()
        val check = GameFileIntegrity {}
        check.ensure(copy, "test") { "good".byteInputStream() }
        assertEquals(before, copy.lastModified())
        assertEquals(0, check.repaired)
        assertEquals(1, check.verified)
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

    @Test fun originalDatabaseSchemaRejectsWrongTableAndPartialRecord() {
        assertEquals(44, GameDatabaseSchema.strides.size)
        assertEquals(2, GameDatabaseSchema.validate("Player.bin", 800))
        for ((name, bytes) in listOf("Fake.bin" to 400, "Player.bin" to 401)) {
            try { GameDatabaseSchema.validate(name, bytes); fail("Invalid table accepted") }
            catch (expected: IllegalArgumentException) { }
        }
    }
}
