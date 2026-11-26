package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.core.AppState
import com.peerlink.app.service.PeerBotService
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files

/** Original downloaded match packs used by the retained 11.1.1 host.
 * Installed APK assets alone contain the title screen, not EnvironmentPreview.
 */
internal object GameResourcePacks {
    private const val MANIFEST = "game-host/match-resources-11.1.1.tsv"
    data class Pack(val name: String, val bytes: Long, val sha256: String) {
        val fingerprint get() = GameFileIntegrity.Fingerprint(bytes, sha256)
    }
    class MissingResources(val missingFiles: Int, val missingBytes: Long, val totalFiles: Int,
                           val missingNames: List<String> = emptyList()) :
        IllegalStateException("${totalFiles - missingFiles}/$totalFiles bot match files found; " +
            "$missingFiles could not be read. No resource download started.")

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
    @Suppress("UNUSED_PARAMETER")
    fun prepare(context: Context, allowDownload: Boolean = false) {
        val packs = manifest(context)
        val report: (String) -> Unit = { AppState.appendLog(it) }
        val local = GameResourceLocalCopies(context, packs.map { it.name }.toSet(), report)
        try {
            prepareLocalSet(directory(context), packs, report, { done, total ->
                PeerBotService.status("Preparing bot match files ${done * 100 / total}%")
            }, local::open)
        } catch (missing: MissingResources) {
            if (!BotRendering.headless(context)) throw missing
            report("[BOT-FILES] HEADLESS_OPTIONAL missing=${missing.missingFiles} source=phone_only")
        }
    }

    /** Never start a resource download, even when a previous caller requested one. */
    internal fun prepareLocalSet(directory: File, packs: List<Pack>, report: (String) -> Unit,
                                 progress: (Long, Long) -> Unit, local: (Pack) -> Sequence<InputStream>) {
        prepareSet(directory, packs, false, report, progress, local) {
            error("Bot resource downloads are disabled")
        }
    }

    internal fun prepareSet(directory: File, packs: List<Pack>, allowDownload: Boolean,
                            report: (String) -> Unit, progress: (Long, Long) -> Unit,
                            local: (Pack) -> Sequence<InputStream>, remote: (Pack) -> InputStream) {
        val total = packs.sumOf { it.bytes }
        var completed = 0L
        val missing = mutableListOf<Pack>()
        val integrity = GameFileIntegrity(report)
        for ((index, pack) in packs.withIndex()) {
            val target = File(directory, pack.name)
            var found = target.isFile && target.inputStream().use(GameFileIntegrity::fingerprint) == pack.fingerprint
            if (!found) {
                for (input in local(pack)) {
                    try {
                        input.use { integrity.replace(bounded(it, pack.bytes), target, "local-match/${pack.name}", pack.fingerprint) }
                        report("[BOT-FILES] RESOURCE_LOCAL_COPY file=${pack.name} bytes=${pack.bytes} SHA256=${pack.sha256}")
                        found = true
                        break
                    } catch (error: java.io.IOException) {
                        report("[BOT-FILES] LOCAL_SOURCE_REJECTED file=${pack.name} error=${error.javaClass.simpleName}")
                    } catch (error: IllegalStateException) {
                        report("[BOT-FILES] LOCAL_SOURCE_REJECTED file=${pack.name} reason=size_or_hash_mismatch")
                    }
                }
            }
            if (found) completed += pack.bytes else missing.add(pack)
            report("[BOT-FILES] RESOURCE_LOCAL_CHECK checked=${index + 1}/${packs.size} available=${index + 1 - missing.size} file=${pack.name} result=${if (found) "PASS" else "MISSING_OR_DIFFERENT"}")
            progress(completed, total)
        }
        val remaining = missing.sumOf { it.bytes }
        report("[BOT-FILES] RESOURCE_DISCOVERY available=${packs.size - missing.size}/${packs.size} missing=${missing.size} missingBytes=$remaining networkAllowed=$allowDownload freshness=PINNED_TESTED_SET_NOT_LATEST_CATALOG")
        if (missing.isNotEmpty()) {
            if (!allowDownload) throw MissingResources(missing.size, remaining, packs.size, missing.map { it.name })
            ensure(directory, missing, report, { done, _ -> progress(completed + done, total) }, remote)
        }
        report("[BOT-FILES] RESOURCE_SET result=PASS files=${packs.size} bytes=$total " +
            "source=${if (allowDownload) "verified_local_copies_or_selected_download" else "verified_local_copies"}")
    }

    internal fun bounded(input: InputStream, limit: Long,
                         onSizeExceeded: () -> Nothing = { error("Resource exceeds verified size") }) = object : FilterInputStream(input) {
        private var count = 0L
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            check(!Thread.currentThread().isInterrupted) { "Match resource preparation cancelled" }
            val read = super.read(buffer, offset, length)
            if (read > 0) {
                count += read
                if (count > limit) onSizeExceeded()
            }
            return read
        }
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

    /** Validate any existing match files; only the graphics-free experiment may omit them. */
    fun verifyAndLink(context: Context, nativePaks: File, report: (String) -> Unit,
                      allowMissing: Boolean = false): Int =
        verifyAndLinkSet(directory(context), nativePaks, manifest(context), report, allowMissing)

    internal fun verifyAndLinkSet(source: File, nativePaks: File, packs: List<Pack>,
                                  report: (String) -> Unit, allowMissing: Boolean): Int {
        var missing = 0
        for (pack in packs) {
            val input = File(source, pack.name)
            val valid = input.isFile && input.inputStream().use(GameFileIntegrity::fingerprint) == pack.fingerprint
            val target = File(nativePaks, pack.name)
            if (!valid && allowMissing) {
                val nativeValid = target.isFile && target.inputStream().use(GameFileIntegrity::fingerprint) == pack.fingerprint
                if (nativeValid) {
                    report("[BOT-FILES] RESOURCE_NATIVE_COPY file=${pack.name} result=PASS")
                } else {
                    check(!target.exists() || target.delete()) { "Cannot remove damaged bot resource alias" }
                    missing++
                    report("[BOT-FILES] RESOURCE_OPTIONAL file=${pack.name} result=NOT_AVAILABLE")
                }
                continue
            }
            check(valid) { "Bot match resource missing or damaged: ${pack.name}" }
            link(input, target, pack.fingerprint, report)
            report("[BOT-FILES] RESOURCE_PASS file=${pack.name} bytes=${pack.bytes} SHA256=${pack.sha256}")
        }
        return missing
    }

    fun link(source: File, destination: File, fingerprint: GameFileIntegrity.Fingerprint, report: (String) -> Unit) {
        destination.parentFile!!.mkdirs()
        if (destination.isFile && Files.isSameFile(source.toPath(), destination.toPath())) return
        val temporary = File.createTempFile(".resource-", ".link", destination.parentFile)
        try {
            check(temporary.delete())
            val linked = try {
                Files.createLink(temporary.toPath(), source.toPath())
                true
            } catch (_: java.io.IOException) {
                false
            } catch (_: UnsupportedOperationException) {
                false
            }
            if (linked) {
                check(temporary.renameTo(destination)) { "Cannot install match resource link" }
            } else if (!destination.isFile || runCatching {
                    destination.inputStream().use(GameFileIntegrity::fingerprint)
                }.getOrNull() != fingerprint) {
                source.inputStream().use { GameFileIntegrity(report).replace(it, destination, "native/${source.name}", fingerprint) }
            }
        } finally { temporary.delete() }
    }
}
