package com.peerlink.app.bot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class GameDatabaseUpdateTest {
    @get:Rule val temporary = org.junit.rules.TemporaryFolder()
    private val original = "expected pack".toByteArray()
    private val replacement = "newer expected pack".toByteArray()
    private val previous = GameDatabaseUpdate.Pack("Previous", original.inputStream().use(GameFileIntegrity::fingerprint), 1)
    private val current = GameDatabaseUpdate.Pack("Current", replacement.inputStream().use(GameFileIntegrity::fingerprint), 2)
    private val supported = listOf(previous, current)

    @Test fun invalidImportPreservesThePreviousCopy() {
        val source = File(temporary.root, "imported.cpk")
        original.inputStream().use { GameDatabaseUpdate.save(it, source, supported) }
        for (invalid in listOf(replacement.copyOf(replacement.size - 1),
            replacement.copyOf().also { it[0] = 0 },
            replacement.copyOf(replacement.size + 1))) {
            assertThrows(GameDatabaseUpdate.UnsupportedArchive::class.java) {
                invalid.inputStream().use { GameDatabaseUpdate.save(it, source, supported) }
            }
            assertArrayEquals(original, source.readBytes())
        }
        assertEquals(listOf("imported.cpk"), temporary.root.list()?.toList())
    }

    @Test fun replacingAnImportUpdatesTheIdentityAndEngineFile() {
        val source = File(temporary.root, "imported.cpk")
        val root = temporary.newFolder("bot")
        val target = File(root, "internal/Download/dt870_mobile_and.cpk")
        assertEquals(previous, original.inputStream().use { GameDatabaseUpdate.save(it, source, supported) })
        val audit = mutableListOf<String>()
        assertEquals(previous, GameDatabaseUpdate.stage(source, root, supported, audit::add))
        assertArrayEquals(original, target.readBytes())
        assertTrue(audit.any { it.contains("status=staged") })
        assertEquals(current, replacement.inputStream().use { GameDatabaseUpdate.save(it, source, supported) })
        assertEquals(current, GameDatabaseUpdate.selectedPack(source, supported))
        assertEquals(current, GameDatabaseUpdate.stage(source, root, supported, audit::add))
        assertArrayEquals(replacement, target.readBytes())
    }

    @Test fun missingOrInvalidInputCannotActivateTheUpdate() {
        val source = File(temporary.root, "imported.cpk")
        val root = temporary.newFolder("bot")
        val download = File(root, "internal/Download").apply { mkdirs() }
        val update = File(download, "dt870_mobile_and.cpk").apply { writeBytes(original) }
        val other = File(download, "original-resource.cpk").apply { writeText("existing") }
        assertNull(GameDatabaseUpdate.stage(source, root, supported) {})
        assertFalse(update.exists())
        assertEquals("existing", other.readText())
        source.writeBytes(original.reversedArray())
        assertThrows(GameDatabaseUpdate.UnsupportedArchive::class.java) {
            GameDatabaseUpdate.stage(source, root, supported) {}
        }
        assertFalse(update.exists())
    }
}
