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

    @Test fun matchingCacheAndLocalCopiesNeedNoNetworkOrHelperForCachedFiles() {
        val root = kotlin.io.path.createTempDirectory("resource-local").toFile()
        try {
            File(root, packs[0].name).writeBytes(contents)
            val localReads = mutableListOf<String>()
            GameResourcePacks.prepareSet(root, packs, false, {}, { _, _ -> }, { pack ->
                localReads.add(pack.name)
                sequenceOf(contents.inputStream())
            }, { error("Local copies must not trigger a download") })
            assertEquals(packs.drop(1).map { it.name }, localReads)
            for (pack in packs) assertArrayEquals(contents, File(root, pack.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun missingFilesReportExactRemainingDownloadWithoutStartingIt() {
        val root = kotlin.io.path.createTempDirectory("resource-no-download").toFile()
        try {
            File(root, packs[0].name).writeBytes(contents)
            val error = assertThrows(GameResourcePacks.MissingResources::class.java) {
                GameResourcePacks.prepareSet(root, packs, false, {}, { _, _ -> }, { emptySequence() }, {
                    error("A download must require the separate user action")
                })
            }
            assertEquals(2, error.missingFiles)
            assertEquals(2 * fingerprint.bytes, error.missingBytes)
            assertEquals(3, error.totalFiles)
            assertEquals(listOf(packs[0].name), root.listFiles()!!.map { it.name })
        } finally { root.deleteRecursively() }
    }

    @Test fun localOnlyPreparationKeepsAvailableFilesAndReportsEveryMissingName() {
        val root = kotlin.io.path.createTempDirectory("resource-local-only").toFile()
        try {
            val missing = assertThrows(GameResourcePacks.MissingResources::class.java) {
                GameResourcePacks.prepareLocalSet(root, packs, {}, { _, _ -> }, { pack ->
                    if (pack == packs[0]) sequenceOf(contents.inputStream()) else emptySequence()
                })
            }
            assertEquals(packs.drop(1).map { it.name }, missing.missingNames)
            assertArrayEquals(contents, File(root, packs[0].name).readBytes())
            assertEquals(listOf(packs[0].name), root.listFiles()!!.map { it.name })
            assertTrue(missing.message!!.contains("No resource download started"))
        } finally { root.deleteRecursively() }
    }

    @Test fun headlessModeKeepsOnlyVerifiedExistingAliasesWithoutCreatingMissingFiles() {
        val root = kotlin.io.path.createTempDirectory("resource-headless-alias").toFile()
        try {
            val source = File(root, "source").apply { mkdirs() }
            val native = File(root, "native").apply { mkdirs() }
            File(source, packs[0].name).writeBytes(contents)
            File(native, packs[1].name).writeBytes(contents)
            File(native, packs[2].name).writeBytes(ByteArray(contents.size))
            assertEquals(1, GameResourcePacks.verifyAndLinkSet(source, native, packs, {}, true))
            assertArrayEquals(contents, File(native, packs[0].name).readBytes())
            assertArrayEquals(contents, File(native, packs[1].name).readBytes())
            assertFalse(File(native, packs[2].name).exists())
            assertFalse(File(source, packs[2].name).exists())
            File(source, packs[2].name).writeBytes(contents)
            assertEquals(0, GameResourcePacks.verifyAndLinkSet(source, native, packs, {}, true))
            assertThrows(IllegalStateException::class.java) {
                GameResourcePacks.verifyAndLinkSet(source, native, packs, {}, false)
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedLocalDataIsRejectedAndAnotherMatchingLocalCopyCanBeUsed() {
        val root = kotlin.io.path.createTempDirectory("resource-local-hash").toFile()
        try {
            GameResourcePacks.prepareSet(root, packs, false, {}, { _, _ -> }, {
                sequenceOf(ByteArray(contents.size).inputStream(), contents.inputStream())
            }, { error("Matching second local source should be used") })
            for (pack in packs) assertArrayEquals(contents, File(root, pack.name).readBytes())
        } finally { root.deleteRecursively() }
    }

    @Test fun selectedDownloadFetchesOnlyFilesNotFoundLocally() {
        val root = kotlin.io.path.createTempDirectory("resource-download-selected").toFile()
        try {
            val network = mutableListOf<String>()
            GameResourcePacks.prepareSet(root, packs, true, {}, { _, _ -> }, { pack ->
                if (pack == packs[0]) sequenceOf(contents.inputStream()) else emptySequence()
            }, { pack -> network.add(pack.name); contents.inputStream() })
            assertEquals(packs.drop(1).map { it.name }, network)
        } finally { root.deleteRecursively() }
    }
}
