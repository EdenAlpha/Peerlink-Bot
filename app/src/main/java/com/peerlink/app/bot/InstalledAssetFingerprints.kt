package com.peerlink.app.bot

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Digests belong to one installed game. Only an unchanged private pack can reuse its check. */
internal class InstalledAssetFingerprints(private val file: File, private val installation: String) {
    private val values = Properties()

    init {
        if (file.isFile && file.length() in 1..131072L) {
            try {
                file.inputStream().buffered().use(values::load)
            } catch (_: IOException) {
                values.clear()
            } catch (_: IllegalArgumentException) {
                values.clear()
            }
        }
        if (values.getProperty("installation") != installation) values.clear()
        values.setProperty("installation", installation)
    }

    operator fun get(key: String): GameFileIntegrity.Fingerprint? {
        val value = values.getProperty(key) ?: return null
        val bytes = value.substringBefore(' ').toLongOrNull() ?: return null
        val hash = value.substringAfter(' ', "")
        if (bytes < 0 || hash.length != 64 || !hash.all { it in 'a'..'f' || it in '0'..'9' }) return null
        return GameFileIntegrity.Fingerprint(bytes, hash)
    }

    operator fun set(key: String, fingerprint: GameFileIntegrity.Fingerprint) {
        values.setProperty(key, "${fingerprint.bytes} ${fingerprint.sha256}")
    }

    fun hasVerifiedCopy(key: String, stamp: String?, expected: GameFileIntegrity.Fingerprint) =
        stamp != null && values.getProperty("copy/$key") == "$stamp ${expected.sha256}"

    fun rememberCopy(key: String, stamp: String, verified: GameFileIntegrity.Fingerprint) {
        values.setProperty("copy/$key", "$stamp ${verified.sha256}")
    }

    fun save(): Boolean {
        return try {
            val tmp = File.createTempFile(".asset-digests-", ".tmp", file.parentFile)
            try {
                tmp.outputStream().buffered().use { values.store(it, null) }
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally {
                tmp.delete()
            }
        } catch (_: IOException) {
            false
        }
    }
}
