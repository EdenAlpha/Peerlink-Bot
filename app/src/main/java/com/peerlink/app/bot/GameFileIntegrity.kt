package com.peerlink.app.bot

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Streaming checks of app-owned copies against the currently installed source. */
internal class GameFileIntegrity(private val report: (String) -> Unit) {
    data class Fingerprint(val bytes: Long, val sha256: String)
    var verified = 0
        private set
    var repaired = 0
        private set

    fun inspect(input: InputStream, destination: File, label: String): Pair<Fingerprint, Boolean> {
        val source = fingerprint(input)
        val copy = if (destination.isFile) runCatching {
            destination.inputStream().use(::fingerprint)
        }.getOrNull() else null
        val matches = source == copy
        report("[BOT-FILES] ${if (matches) "PASS" else if (copy == null) "MISSING_OR_UNREADABLE" else "MISMATCH"} " +
            "file=$label sourceBytes=${source.bytes} sourceSHA256=${source.sha256} " +
            "copyBytes=${copy?.bytes ?: -1} copySHA256=${copy?.sha256 ?: "unavailable"}")
        if (matches) verified++
        return source to matches
    }

    fun ensure(destination: File, label: String, readOnly: Boolean = false, source: () -> InputStream) {
        try {
            val (expected, matches) = source().use { inspect(it, destination, label) }
            if (!matches) source().use { replace(it, destination, label, expected, readOnly) }
            else if (readOnly) check(destination.setReadOnly()) { "Cannot protect $label" }
        } catch (error: Exception) {
            report("[BOT-FILES] FAIL file=$label error=${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    /** Verify the new bytes before replacing a previous copy, including same-size corruption. */
    fun replace(input: InputStream, destination: File, label: String, expected: Fingerprint,
                readOnly: Boolean = false) {
        val parent = requireNotNull(destination.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Cannot create directory for $label" }
        val temporary = File.createTempFile(".verified-", ".tmp", parent)
        try {
            temporary.outputStream().use { output -> input.copyTo(output, 128 * 1024); output.fd.sync() }
            val actual = temporary.inputStream().use(::fingerprint)
            check(actual == expected) { "Source changed or copy failed for $label; unchecked bytes were not installed" }
            if (readOnly) check(temporary.setReadOnly()) { "Cannot protect $label" }
            check(temporary.renameTo(destination)) { "Cannot save verified $label" }
            verified++
            repaired++
            report("[BOT-FILES] REPAIRED file=$label bytes=${actual.bytes} SHA256=${actual.sha256}")
        } finally { temporary.delete() }
    }

    companion object {
        fun fingerprint(input: InputStream): Fingerprint {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(128 * 1024)
            var bytes = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count == 0) continue
                digest.update(buffer, 0, count)
                bytes += count
            }
            return Fingerprint(bytes, digest.digest().joinToString("") { "%02x".format(it) })
        }
    }
}
