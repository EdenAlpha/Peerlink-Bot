package com.peerlink.app.bot

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming checks of app-owned copies against the currently installed source. */
internal class GameFileIntegrity(
    private val report: (String) -> Unit,
    private val onVerifiedCopy: (File) -> Unit,
    private val fingerprintPrivateFile: (File) -> Fingerprint,
) {
    constructor(report: (String) -> Unit, onVerifiedCopy: (File) -> Unit) :
        this(report, onVerifiedCopy, ::fingerprintFile)
    constructor(report: (String) -> Unit) : this(report, {})

    data class Fingerprint(val bytes: Long, val sha256: String)
    var verified = 0
        private set
    var repaired = 0
        private set

    fun inspect(input: InputStream, destination: File, label: String): Pair<Fingerprint, Boolean> {
        val source = fingerprint(input)
        return source to verifyCopy(destination, label, source, "source_read")
    }

    private fun verifyCopy(destination: File, label: String, source: Fingerprint, proof: String): Boolean {
        val copy = if (destination.isFile) runCatching {
            fingerprintPrivateFile(destination)
        }.getOrNull() else null
        val matches = source == copy
        report("[BOT-FILES] ${if (matches) "PASS" else if (copy == null) "MISSING_OR_UNREADABLE" else "MISMATCH"} " +
            "file=$label sourceBytes=${source.bytes} sourceSHA256=${source.sha256} " +
            "copyBytes=${copy?.bytes ?: -1} copySHA256=${copy?.sha256 ?: "unavailable"} proof=$proof")
        if (matches) {
            verified++
            onVerifiedCopy(destination)
        }
        return matches
    }

    fun ensure(destination: File, label: String, readOnly: Boolean = false,
               installedSource: Fingerprint? = null, source: () -> InputStream): Fingerprint {
        try {
            if (installedSource != null && verifyCopy(destination, label, installedSource, "verified_installation")) {
                if (readOnly) check(destination.setReadOnly()) { "Cannot protect $label" }
                return installedSource
            }
            val (expected, matches) = source().use { inspect(it, destination, label) }
            if (!matches) source().use { replace(it, destination, label, expected, readOnly) }
            else if (readOnly) check(destination.setReadOnly()) { "Cannot protect $label" }
            return expected
        } catch (error: Exception) {
            report("[BOT-FILES] FAIL file=$label error=${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    /** The installed source key and both kernel timestamps must still match the previous digest. */
    fun ensurePack(destination: File, key: String, fingerprints: InstalledAssetFingerprints,
                   fileStamp: (File) -> String?, source: () -> InputStream): Fingerprint {
        val before = fileStamp(destination)
        val expected = fingerprints[key]
        if (expected != null && fingerprints.hasVerifiedCopy(key, before, expected)) {
            verified++
            report("[BOT-FILES] PASS file=$key bytes=${expected.bytes} proof=unchanged_verified_copy")
            return expected
        }
        val result = ensure(destination, key, installedSource = expected, source = source)
        fingerprints[key] = result
        // A first install or repair replaces the inode. Establish its on-disk stamp on the
        // next read, never associate a digest of an older inode with its replacement.
        if (before != null && fileStamp(destination) == before) fingerprints.rememberCopy(key, before, result)
        return result
    }

    /** Verify the new bytes before replacing a previous copy, including same-size corruption. */
    fun replace(input: InputStream, destination: File, label: String, expected: Fingerprint,
                readOnly: Boolean = false) {
        replace(input, destination, label, readOnly) { actual ->
            check(actual == expected) { "Source changed or copy failed for $label; unchecked bytes were not installed" }
        }
    }

    fun replace(input: InputStream, destination: File, label: String, readOnly: Boolean,
                verify: (Fingerprint) -> Unit): Fingerprint {
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create directory for $label" }
        val temporary = File.createTempFile(".verified-", ".tmp", parent)
        try {
            temporary.outputStream().use { output -> input.copyTo(output, 128 * 1024); output.fd.sync() }
            val actual = fingerprintPrivateFile(temporary)
            verify(actual)
            if (readOnly) check(temporary.setReadOnly()) { "Cannot protect $label" }
            check(temporary.renameTo(destination)) { "Cannot save verified $label" }
            verified++
            repaired++
            onVerifiedCopy(destination)
            report("[BOT-FILES] REPAIRED file=$label bytes=${actual.bytes} SHA256=${actual.sha256}")
            return actual
        } finally { temporary.delete() }
    }

    companion object {
        private const val CACHE_WINDOW = 8L * 1024 * 1024
        fun fingerprintFile(file: File): Fingerprint = file.inputStream().use { fingerprint(it) }

        fun fingerprint(input: InputStream): Fingerprint = fingerprint(input, null)

        fun fingerprint(input: InputStream, releaseReadWindow: ((Long, Long) -> Unit)?): Fingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            var releasedThrough = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
                if (releaseReadWindow != null && bytes - releasedThrough >= CACHE_WINDOW) {
                    releaseReadWindow(releasedThrough, CACHE_WINDOW)
                    releasedThrough += CACHE_WINDOW
                }
            }
            return Fingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
