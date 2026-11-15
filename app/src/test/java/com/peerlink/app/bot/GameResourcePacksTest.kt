package com.peerlink.app.bot

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class GameResourcePacksTest {
    private val contents = "verified original resource".toByteArray()
    private val fingerprint = contents.inputStream().use(GameFileIntegrity::fingerprint)
    private val packs = listOf("pak", "utoc", "ucas").map {
        GameResourcePacks.Pack("pc1000_mobile_and.$it", fingerprint.bytes, fingerprint.sha256)
    }

    @Test fun missingAndSameSizeDamagedPacksAreReplacedButValidPacksAreReused() {
        val root = kotlin.io.path.createTempDirectory("resource-integrity").toFile()
        try {
            File(root, packs[0].name).writeBytes(contents)
            File(root, packs[1].name).writeBytes(ByteArray(contents.size))
            val downloaded = mutableListOf<String>()
            GameResourcePacks.ensure(root, packs, {}, { _, _ -> }) {
                downloaded.add(it.name); contents.inputStream()
            }
            assertEquals(listOf(packs[1].name, packs[2].name), downloaded)
            for (pack in packs) assertArrayEquals(contents, File(root, pack.name).readBytes())
            GameResourcePacks.ensure(root, packs, {}, { _, _ -> }) { error("Verified pack downloaded twice") }
        } finally { root.deleteRecursively() }
    }

    @Test fun truncatedOrWrongDownloadsCannotReplaceAnExistingPack() {
        for (wrong in listOf(contents.copyOf(contents.size - 1), ByteArray(contents.size), contents + 1)) {
            val root = kotlin.io.path.createTempDirectory("resource-download").toFile()
            try {
                val previous = byteArrayOf(1, 2, 3)
                val target = File(root, packs[0].name).apply { writeBytes(previous) }
                assertThrows(IllegalStateException::class.java) {
                    GameResourcePacks.ensure(root, packs.take(1), {}, { _, _ -> }) { wrong.inputStream() }
                }
                assertArrayEquals(previous, target.readBytes())
                assertEquals(listOf(target.name), root.listFiles()!!.map { it.name })
            } finally { root.deleteRecursively() }
        }
    }

    @Test fun manifestRejectsTraversalDuplicateAndIncompleteTriplets() {
        val valid = packs.joinToString("\n") { "${it.name}\t${it.bytes}\t${it.sha256}" }
        assertEquals(packs, GameResourcePacks.parse(valid))
        for (wrong in listOf(valid.replace("pc1000", "../pc1000"), "$valid\n$valid", valid.lineSequence().first()))
            assertThrows(IllegalArgumentException::class.java) { GameResourcePacks.parse(wrong) }
    }
}
