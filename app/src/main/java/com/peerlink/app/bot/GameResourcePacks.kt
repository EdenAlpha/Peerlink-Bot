package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.service.PeerBotService
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files

/** Original downloaded match packs used by the retained 11.1.1 host.
 * Installed APK assets alone contain the title screen, not EnvironmentPreview.
 */
internal object GameResourcePacks {
    private const val MANIFEST = "game-host/match-resources-11.1.1.tsv"
    private const val RELEASE = "https://github.com/EdenAlpha/Peerlink-Bot/releases/download/engine-resources-11.1.1/"
    data class Pack(val name: String, val bytes: Long, val sha256: String) {
        val fingerprint get() = GameFileIntegrity.Fingerprint(bytes, sha256)
    }

    fun parse(text: String): List<Pack> {
        val packs = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
            val fields = line.split('\t')
            require(fields.size == 3) { "Invalid match resource manifest" }
            require(Regex("pc[0-9]{4}_mobile_and\\.(pak|utoc|ucas)").matches(fields[0]))
            val bytes = fields[1].toLong()
            require(bytes in 1..1_000_000_000L && Regex("[a-f0-9]{64}").matches(fields[2]))
            Pack(fields[0], bytes, fields[2])
        }.toList()
        require(packs.isNotEmpty() && packs.map { it.name }.distinct().size == packs.size)
        for (group in packs.groupBy { it.name.substringBeforeLast('.') }.values)
            require(group.map { it.name.substringAfterLast('.') }.toSet() == setOf("pak", "utoc", "ucas")) {
                "Incomplete match resource pack"
            }
        return packs
    }

    private fun manifest(context: Context) = context.assets.open(MANIFEST).bufferedReader().use { parse(it.readText()) }
    private fun directory(context: Context) = File(context.filesDir, "bot-game-11.1.1/project/PesMobile/Content/Paks")

    /** Run before the bounded engine startup IPC and before opening a room. */
    fun prepare(context: Context) {
        val packs = manifest(context)
        ensure(directory(context), packs, { AppState.appendLog(it) }, { done, total ->
            PeerBotService.status("Preparing bot match files ${done * 100 / total}% (${total / 1_000_000} MB first download)")
        }) { pack -> download(pack) }
    }

    internal fun ensure(directory: File, packs: List<Pack>, report: (String) -> Unit,
                        progress: (Long, Long) -> Unit, open: (Pack) -> InputStream) {
        val total = packs.sumOf { it.bytes }
        var completed = 0L
        val integrity = GameFileIntegrity(report)
        for (pack in packs) {
            val destination = File(directory, pack.name)
            val actual = if (destination.isFile) destination.inputStream().use(GameFileIntegrity::fingerprint) else null
            if (actual == pack.fingerprint) {
                report("[BOT-FILES] RESOURCE_PASS file=${pack.name} bytes=${pack.bytes} SHA256=${pack.sha256}")
            } else {
                report("[BOT-FILES] RESOURCE_DOWNLOAD file=${pack.name} expectedBytes=${pack.bytes}")
                open(pack).use { input ->
                    var received = 0L
                    var lastReport = 0L
                    val measured = object : FilterInputStream(input) {
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            check(!Thread.currentThread().isInterrupted) { "Match resource preparation cancelled" }
                            val count = super.read(buffer, offset, length)
                            if (count > 0) {
                                received += count
                                check(received <= pack.bytes) { "Match resource exceeds its verified size" }
                                val now = System.nanoTime()
                                if (now - lastReport > 500_000_000) {
                                    progress(completed + received, total)
                                    lastReport = now
                                }
                            }
                            return count
                        }
                    }
                    integrity.replace(measured, destination, "match/${pack.name}", pack.fingerprint)
                }
            }
            completed += pack.bytes
            progress(completed, total)
        }
        report("[BOT-FILES] RESOURCE_SET result=PASS files=${packs.size} bytes=$total source=pinned_original_11.1.1_match_packs")
    }

    /** Refuse to start with only the base pack; a copy audit must cover these too. */
    fun verifyAndLink(context: Context, nativePaks: File, report: (String) -> Unit) {
        val source = directory(context)
        for (pack in manifest(context)) {
            val input = File(source, pack.name)
            check(input.isFile && input.inputStream().use(GameFileIntegrity::fingerprint) == pack.fingerprint) {
                "Bot match resource missing or damaged: ${pack.name}; prepare the match files before creating a room"
            }
            link(input, File(nativePaks, pack.name), pack.fingerprint, report)
            report("[BOT-FILES] RESOURCE_PASS file=${pack.name} bytes=${pack.bytes} SHA256=${pack.sha256}")
        }
    }

    fun link(source: File, destination: File, fingerprint: GameFileIntegrity.Fingerprint, report: (String) -> Unit) {
        destination.parentFile!!.mkdirs()
        if (destination.isFile && Files.isSameFile(source.toPath(), destination.toPath())) return
        val temporary = File.createTempFile(".resource-", ".link", destination.parentFile)
        try {
            check(temporary.delete())
            try {
                Files.createLink(temporary.toPath(), source.toPath())
                check(temporary.renameTo(destination)) { "Cannot install match resource link" }
            } catch (_: java.io.IOException) {
                source.inputStream().use { GameFileIntegrity(report).replace(it, destination, "native/${source.name}", fingerprint) }
            }
        } finally { temporary.delete() }
    }

    private fun download(pack: Pack): InputStream {
        val connection = URL(RELEASE + pack.name).openConnection() as HttpURLConnection
        connection.connectTimeout = 30_000
        connection.readTimeout = 120_000
        connection.instanceFollowRedirects = true
        try {
            check(connection.responseCode == 200) { "Match file download failed: HTTP ${connection.responseCode} (${pack.name})" }
            val length = connection.contentLengthLong
            check(length == -1L || length == pack.bytes) { "Match file download has the wrong size (${pack.name})" }
            return object : FilterInputStream(connection.inputStream) {
                override fun close() { try { super.close() } finally { connection.disconnect() } }
            }
        } catch (error: Exception) { connection.disconnect(); throw error }
    }
}
