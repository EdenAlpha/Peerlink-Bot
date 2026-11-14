package com.peerlink.app.bot

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/** App-private working files; the installed game and its saved data remain read-only. */
internal object GameAssets {
    data class Prepared(val game: Context, val root: File) {
        val project get() = File(root, "project/PesMobile")
        val database get() = File(root, "database")
    }

    fun prepare(owner: Context, status: (String) -> Unit): Prepared {
        val game = owner.createPackageContext("jp.konami.pesam", Context.CONTEXT_IGNORE_SECURITY)
        val root = File(owner.filesDir, "bot-game-11.1.1")
        check(root.isDirectory || root.mkdirs()) { "Cannot create bot working files" }
        val prepared = Prepared(game, root)
        val marker = File(root, "assets-v1.ready")
        val packageInfo = owner.packageManager.getPackageInfo("jp.konami.pesam", 0)
        val installation = "${packageInfo.lastUpdateTime}:${game.applicationInfo.sourceDir}"
        if (marker.isFile && marker.readText() == installation) {
            prepareNativePakLayout(prepared, installation)
            return prepared
        }
        val assets = game.assets
        val names = assets.list("").orEmpty().filter {
            it.endsWith("_all.cpk") || it.endsWith("_and.cpk") || it.contains("_eng_") && it.endsWith(".cpk")
        }
        check("dt200_mobile_all.cpk" in names) { "Game data packs are missing; finish the game's download first" }
        val assetDir = File(root, "assets").apply { mkdirs() }
        for ((index, name) in names.withIndex()) {
            status("Preparing bot files ${index + 1}/${names.size}")
            assets.open(name).use { input -> atomicFile(File(assetDir, name)) { input.copyTo(it, 128 * 1024) } }
        }
        status("Preparing bot match resources")
        val projectRoot = File(root, "project").apply { mkdirs() }
        ZipInputStream(assets.open("pc0000_mobile_and.pak").buffered()).use { zip ->
            var count = 0
            while (true) {
                val entry = zip.nextEntry ?: break
                check(entry.name.startsWith("PesMobile/Content/Paks/")) { "Unexpected game resource path" }
                val destination = File(projectRoot, entry.name)
                check(destination.canonicalPath.startsWith(projectRoot.canonicalPath + File.separator))
                if (!entry.isDirectory) {
                    destination.parentFile?.mkdirs()
                    atomicFile(destination) { zip.copyTo(it, 128 * 1024) }
                    count++
                }
                zip.closeEntry()
            }
            check(count == 5) { "Unsupported game resource pack" }
        }
        prepared.database.mkdirs()
        GameArchive(File(assetDir, "dt200_mobile_all.cpk")).use { archive ->
            for (name in archive.names()) {
                if (!name.startsWith("common/etc/pesdb/") || !name.endsWith(".bin")) continue
                val decoded = GameArchive.decodeDatabase(archive.read(name))
                val destination = File(prepared.database, name.substringAfterLast('/').removeSuffix(".bin") + ".decoded")
                atomicFile(destination) { it.write(decoded) }
            }
        }
        val command = "-opengl -nosound -unattended -NoSplash -culture=en -featureleveles31 " +
            "-ResX=320 -ResY=180 -ProjectDir=${prepared.project.absolutePath}/\n"
        for (relative in listOf("internal/UE4CommandLine.txt", "internal/UE4Game/PesMobile/UE4CommandLine.txt",
            "external/UE4Game/PesMobile/UE4CommandLine.txt", "external/PesMobile/UE4CommandLine.txt")) {
            val destination = File(root, relative)
            destination.parentFile?.mkdirs()
            atomicFile(destination) { it.write(command.toByteArray()) }
        }
        prepareNativePakLayout(prepared, installation)
        atomicFile(marker) { it.write(installation.toByteArray()) }
        return prepared
    }

    private fun prepareNativePakLayout(prepared: Prepared, installation: String) {
        // FAndroidPlatformFile maps ../../../PesMobile/... into this private
        // UE4Game tree. -ProjectDir alone does not expose the staged files there.
        // Verified with the original library's failed global.utoc stat on ARM64.
        val source = File(prepared.project, "Content/Paks")
        val target = File(prepared.root, "internal/UE4Game/PesMobile/PesMobile/Content/Paks")
        val marker = File(prepared.root, "native-paks-v2.ready")
        val files = source.listFiles().orEmpty().filter { it.isFile }
        check(files.size == 5) { "Bot match resource files are incomplete" }
        if (marker.isFile && marker.readText() == installation && files.all {
                File(target, it.name).length() == it.length()
            }) return
        check(target.isDirectory || target.mkdirs()) { "Cannot create native bot resource directory" }
        for (file in files) file.inputStream().use { input ->
            atomicFile(File(target, file.name)) { input.copyTo(it, 128 * 1024) }
        }
        atomicFile(marker) { it.write(installation.toByteArray()) }
    }

    private fun atomicFile(destination: File, write: (java.io.OutputStream) -> Unit) {
        val temporary = File.createTempFile(".bot-", ".tmp", destination.parentFile)
        try {
            temporary.outputStream().use { output -> write(output); output.fd.sync() }
            check(temporary.renameTo(destination)) { "Could not save bot files" }
        } finally { temporary.delete() }
    }
}
