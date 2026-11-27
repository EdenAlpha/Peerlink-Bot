package com.peerlink.app.bot

import android.content.Context
import com.peerlink.app.BuildConfig
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONObject

/** Immutable capture generations let the UI export while the engine writes a later attempt. */
internal object ReceivedSquadFiles {
    private val validId = Regex("[0-9]{13}-[0-9]+")
    private val validFile = Regex("([0-9]{13}-[0-9]+)-[0-9]+\\.(json|dto|capture\\.bin)")
    private fun directory(context: Context) = File(context.filesDir, "received-squads")

    fun start(context: Context, id: String) {
        require(validId.matches(id))
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs())
        val old = dir.listFiles().orEmpty().mapNotNull { validFile.matchEntire(it.name)?.groupValues?.get(1) }
            .distinct().sortedDescending().filter { it != id }.drop(2).toSet()
        dir.listFiles().orEmpty().filter {
            validFile.matchEntire(it.name)?.groupValues?.get(1) in old
        }.forEach { it.delete() }
        save(context, id, 0, null)
    }

    fun save(context: Context, id: String, attempt: Int, capture: ReceivedSquadCapture?,
             error: String? = null, durationMs: Long = 0) {
        require(validId.matches(id) && attempt in 0..99)
        val dir = directory(context)
        check(dir.isDirectory || dir.mkdirs())
        val name = "$id-$attempt"
        if (capture != null) {
            atomic(File(dir, "$name.capture.bin"), capture.raw)
            if (capture.engineCompatible) atomic(File(dir, "$name.dto"), capture.engineInput!!)
        }
        val report = linkedMapOf<String, Any?>(
            "capture_id" to id, "attempt" to attempt, "recorded_at_ms" to System.currentTimeMillis(),
            "app_version" to BuildConfig.VERSION_NAME, "source_commit" to BuildConfig.SOURCE_COMMIT,
            "engine_sha256" to EngineRuntime.ENGINE_SHA256, "capture_duration_ms" to durationMs,
            "capture_error" to error,
        )
        report.putAll(capture?.report() ?: mapOf(
            "schema" to "peerlink.received_squad.v1",
            "status" to if (error == null) "waiting_for_received_squad" else "capture_failed",
            "engine_input_compatible" to false))
        atomic(File(dir, "$name.json"), JSONObject(report).toString(2).toByteArray(Charsets.UTF_8))
    }

    /** A JSON report commits each generation after its binary companions have been closed. */
    fun exportedFiles(context: Context): List<File> {
        val dir = directory(context)
        return dir.listFiles().orEmpty().filter { file ->
            val match = validFile.matchEntire(file.name)
            match != null && file.isFile && file.length() <= 512 * 1024 &&
                File(dir, file.name.removeSuffix(".capture.bin").removeSuffix(".dto").removeSuffix(".json") + ".json").isFile
        }.sortedBy { it.name }
    }

    private fun atomic(destination: File, bytes: ByteArray) {
        val temporary = File.createTempFile(".squad-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { output -> output.write(bytes); output.fd.sync() }
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }
}
