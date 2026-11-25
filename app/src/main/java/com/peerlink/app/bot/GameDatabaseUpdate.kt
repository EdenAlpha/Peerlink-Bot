package com.peerlink.app.bot

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

/** A locally supplied data pack checked against the recorded 11.1.1 match. */
internal object GameDatabaseUpdate {
    private const val FILE_NAME = "dt870_mobile_and.cpk"
    private val expected = GameFileIntegrity.Fingerprint(
        15_816_879,
        "bb9903666bb3e51d0491bebc762e03d6841d98ee12ec119f1b809ae709ca317f",
    )

    private fun source(context: Context) = File(context.filesDir, "bot-inputs/$FILE_NAME")

    fun isImported(context: Context) = source(context).isFile

    fun import(context: Context, uri: Uri) {
        val input = context.contentResolver.openInputStream(uri)
            ?: error("Android could not open the selected file.")
        input.use { save(it, source(context), expected) }
    }

    fun remove(context: Context) {
        val file = source(context)
        check(!file.exists() || file.delete()) { "Could not remove the saved eFootball update." }
    }

    fun stage(context: Context, engineRoot: File, audit: (String) -> Unit): Boolean =
        stage(source(context), engineRoot, expected, audit)

    internal fun save(input: InputStream, destination: File, expected: GameFileIntegrity.Fingerprint) {
        GameFileIntegrity({}).replace(GameResourcePacks.bounded(input, expected.bytes), destination,
            "saved eFootball update", expected, readOnly = true)
    }

    internal fun stage(source: File, engineRoot: File, expected: GameFileIntegrity.Fingerprint,
                       audit: (String) -> Unit): Boolean {
        val target = File(engineRoot, "internal/Download/$FILE_NAME")
        if (!source.exists()) {
            check(!target.exists() || target.delete()) { "Could not clear the bot's previous data pack." }
            audit("[BOT-FILES] SAVED_DATABASE_UPDATE status=not_selected")
            return false
        }
        check(source.isFile && source.length() == expected.bytes &&
            GameFileIntegrity.fingerprintFile(source) == expected) {
            "Import the verified eFootball update again or remove it."
        }
        GameResourcePacks.link(source, target, expected, audit)
        check(target.isFile && target.length() == expected.bytes &&
            GameFileIntegrity.fingerprintFile(target) == expected) { "Could not stage the verified eFootball update." }
        audit("[BOT-FILES] SAVED_DATABASE_UPDATE status=staged bytes=${expected.bytes} SHA256=${expected.sha256}")
        return true
    }
}
