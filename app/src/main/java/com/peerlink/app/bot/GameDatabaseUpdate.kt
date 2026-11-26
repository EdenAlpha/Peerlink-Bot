package com.peerlink.app.bot

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.InputStream

/** Locally supplied eFootball 11.1.1 data packs with verified engine record counts. */
internal object GameDatabaseUpdate {
    const val FILE_NAME = "dt870_mobile_and.cpk"

    data class Pack(val date: String, val fingerprint: GameFileIntegrity.Fingerprint, val playerCount: Int)

    private val previous = Pack(
        "25 Nov 2026",
        GameFileIntegrity.Fingerprint(15_816_879, "bb9903666bb3e51d0491bebc762e03d6841d98ee12ec119f1b809ae709ca317f"),
        47_370,
    )
    val current = Pack(
        "26 Nov 2026",
        GameFileIntegrity.Fingerprint(15_892_655, "e6f52d331dce7319d21887b3afbf2162dea678e60dd3260e377a4b70ebc645c3"),
        47_512,
    )
    private val supported = listOf(previous, current)

    class UnsupportedArchive : IllegalStateException("Choose a supported eFootball data file or use installed data.")

    private fun source(context: Context) = File(context.filesDir, "bot-inputs/$FILE_NAME")

    fun selectedPack(context: Context): Pack? = selectedPack(source(context), supported)

    fun import(context: Context, uri: Uri): Pack {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Android could not open the selected file.")
        return input.use { save(it, source(context), supported) }
    }

    fun remove(context: Context) {
        val file = source(context)
        check(!file.exists() || file.delete()) { "Could not remove the saved eFootball update." }
    }

    fun stage(context: Context, engineRoot: File, audit: (String) -> Unit): Pack? =
        stage(source(context), engineRoot, supported, audit)

    internal fun save(input: InputStream, destination: File, supported: List<Pack>): Pack {
        val limit = supported.maxOf { it.fingerprint.bytes }
        val bounded = GameResourcePacks.bounded(input, limit) { throw UnsupportedArchive() }
        val actual = GameFileIntegrity({}).replace(bounded, destination, "saved eFootball update", readOnly = true) {
            match(it, supported)
        }
        return match(actual, supported)
    }

    private fun match(fingerprint: GameFileIntegrity.Fingerprint, supported: List<Pack>): Pack =
        supported.firstOrNull { it.fingerprint == fingerprint } ?: throw UnsupportedArchive()

    internal fun selectedPack(source: File, supported: List<Pack>): Pack? {
        if (!source.exists()) return null
        if (!source.isFile || supported.none { it.fingerprint.bytes == source.length() }) throw UnsupportedArchive()
        return match(GameFileIntegrity.fingerprintFile(source), supported)
    }

    internal fun stage(source: File, engineRoot: File, supported: List<Pack>, audit: (String) -> Unit): Pack? {
        val target = File(engineRoot, "internal/Download/$FILE_NAME")
        val pack = selectedPack(source, supported)
        if (pack == null) {
            check(!target.exists() || target.delete()) { "Could not clear the bot's previous data pack." }
            audit("[BOT-FILES] SAVED_DATABASE_UPDATE status=not_selected")
            return null
        }
        val expected = pack.fingerprint
        GameResourcePacks.link(source, target, expected, audit)
        check(target.isFile && target.length() == expected.bytes &&
            GameFileIntegrity.fingerprintFile(target) == expected) { "Could not stage the verified eFootball update." }
        audit("[BOT-FILES] SAVED_DATABASE_UPDATE status=staged bytes=${expected.bytes} SHA256=${expected.sha256}")
        return pack
    }
}
