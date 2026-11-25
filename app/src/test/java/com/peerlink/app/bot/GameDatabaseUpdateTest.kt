package com.peerlink.app.bot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class GameDatabaseUpdateTest {
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val original = "expected pack".toByteArray()
    private val fingerprint = original.inputStream().use(GameFileIntegrity::fingerprint)

    @Test fun invalidImportPreservesThePreviousCopy() {
        val source = File(temporary.root, "imported.cpk")
        original.inputStream().use { GameDatabaseUpdate.save(it, source, fingerprint) }
        for (invalid in listOf(original.copyOf(original.size - 1),
            original.copyOf().also { it[0] = 0 },
            original.copyOf(original.size + 1))) {
            assertThrows(IllegalStateException::class.java) {
                invalid.inputStream().use { GameDatabaseUpdate.save(it, source, fingerprint) }
            }
            assertArrayEquals(original, source.readBytes())
        }
        assertEquals(listOf("imported.cpk"), temporary.root.list()?.toList())
    }

    @Test fun stageInstallsTheVerifiedFileAtTheNativeDownloadPath() {
        val source = File(temporary.root, "imported.cpk")
        val root = temporary.newFolder("bot")
        val target = File(root, "internal/Download/dt870_mobile_and.cpk")
        original.inputStream().use { GameDatabaseUpdate.save(it, source, fingerprint) }
        val audit = mutableListOf<String>()
        assertTrue(GameDatabaseUpdate.stage(source, root, fingerprint, audit::add))
        assertArrayEquals(original, target.readBytes())
        assertTrue(audit.any { it.contains("status=staged") })
        assertTrue(GameDatabaseUpdate.stage(source, root, fingerprint, audit::add))
        assertArrayEquals(original, target.readBytes())
    }

    @Test fun missingOrInvalidInputCannotActivateTheUpdate() {
        val source = File(temporary.root, "imported.cpk")
        val root = temporary.newFolder("bot")
        val download = File(root, "internal/Download").apply { mkdirs() }
        val update = File(download, "dt870_mobile_and.cpk").apply { writeBytes(original) }
        val other = File(download, "original-resource.cpk").apply { writeText("existing") }
        assertFalse(GameDatabaseUpdate.stage(source, root, fingerprint) {})
        assertFalse(update.exists())
        assertEquals("existing", other.readText())
        source.writeBytes(original.reversedArray())
        assertThrows(IllegalStateException::class.java) {
            GameDatabaseUpdate.stage(source, root, fingerprint) {}
        }
        assertFalse(update.exists())
    }
}
