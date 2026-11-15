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

    fun prepare(owner: Context, status: (String) -> Unit, audit: (String) -> Unit = {}): Prepared {
        try { return prepareChecked(owner, status, audit) }
        catch (error: Exception) {
            audit("[BOT-FILES] SUMMARY result=FAIL error=${error.javaClass.simpleName}: ${error.message}")
            throw error
        }
    }

    private fun prepareChecked(owner: Context, status: (String) -> Unit, audit: (String) -> Unit): Prepared {
        val game = owner.createPackageContext("jp.konami.pesam", Context.CONTEXT_IGNORE_SECURITY)
        fun installation(): String {
            val info = owner.packageManager.getPackageInfo("jp.konami.pesam", 0)
            val app = owner.packageManager.getApplicationInfo("jp.konami.pesam", 0)
            return "${info.versionName}:${info.longVersionCode}:${info.lastUpdateTime}:" +
                (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString("|")
        }
        val identity = installation()
        audit("[BOT-FILES] SOURCE installation=$identity")
        audit("[BOT-FILES] SCOPE installed_APK_libraries_and_assets; downloaded/private_game_updates=NOT_CHECKED; " +
            "copy_integrity_does_not_prove_same_live_database_as_running_game")
        val integrity = GameFileIntegrity(audit)
        val root = File(owner.filesDir, "bot-game-11.1.1")
        check(root.isDirectory || root.mkdirs()) { "Cannot create bot working files" }
        val prepared = Prepared(game, root)
        val assets = game.assets
        val names = assets.list("").orEmpty().filter {
            it.endsWith("_all.cpk") || it.endsWith("_and.cpk") || it.contains("_eng_") && it.endsWith(".cpk")
        }.sorted()
        check("dt200_mobile_all.cpk" in names) { "Installed game's database pack is missing" }
        val assetDir = File(root, "assets")
        for ((index, name) in names.withIndex()) {
            status("Checking bot files ${index + 1}/${names.size}")
            integrity.ensure(File(assetDir, name), "assets/$name") { assets.open(name) }
        }
        status("Checking bot match resources")
        val projectRoot = File(root, "project")
        val resources = linkedMapOf<String, GameFileIntegrity.Fingerprint>()
        val repairs = mutableSetOf<String>()
        // One comparison pass, then a second pass only when a copy needs repair.
        // Do not inflate the whole ZIP or repeatedly scan it for each resource.
        ZipInputStream(assets.open("pc0000_mobile_and.pak").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = resourceDestination(projectRoot, entry.name)
                if (!entry.isDirectory) {
                    check(entry.name !in resources) { "Duplicate game resource ${entry.name}" }
                    val (expected, matches) = integrity.inspect(zip, destination, "project/${entry.name}")
                    resources[entry.name] = expected
                    if (!matches) repairs.add(entry.name)
                }
                zip.closeEntry()
            }
        }
        check(resources.size == 5) { "Unsupported game resource pack: ${resources.size} files, expected 5" }
        if (repairs.isNotEmpty()) ZipInputStream(assets.open("pc0000_mobile_and.pak").buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val destination = resourceDestination(projectRoot, entry.name)
                if (entry.name in repairs) {
                    integrity.replace(zip, destination, "project/${entry.name}", resources.getValue(entry.name))
                    repairs.remove(entry.name)
                }
                zip.closeEntry()
            }
        }
        check(repairs.isEmpty()) { "Source resource pack changed during verification" }
        prepared.database.mkdirs()
        var databaseTables = 0
        GameArchive(File(assetDir, "dt200_mobile_all.cpk")).use { archive ->
            val tables = archive.names().filter { it.startsWith("common/etc/pesdb/") && it.endsWith(".bin") }
            val found = tables.map { it.substringAfterLast('/') }.toSet()
            check(found.containsAll(GameDatabaseSchema.strides.keys) && tables.size == found.size) {
                "Required database tables missing or duplicated: missing=${GameDatabaseSchema.strides.keys - found}"
            }
            databaseTables = tables.size
            audit("[BOT-FILES] DATABASE_SET nativeRequired=44 present=${tables.size} " +
                "additional=${found - GameDatabaseSchema.strides.keys}")
            for (name in tables.sorted()) {
                val decoded = GameArchive.decodeDatabase(archive.read(name))
                val table = name.substringAfterLast('/')
                val stride = GameDatabaseSchema.strides[table]
                val records = if (stride != null) GameDatabaseSchema.validate(table, decoded.size) else null
                val outputName = table.removeSuffix(".bin") + ".decoded"
                audit("[BOT-FILES] DATABASE table=$table decodedBytes=${decoded.size} " +
                    "recordStride=${stride ?: "not_in_native_builder"} records=${records ?: "not_checked"} container=VALID")
                integrity.ensure(File(prepared.database, outputName), "database/$outputName") { decoded.inputStream() }
            }
        }
        val command = "-opengl -nosound -unattended -NoSplash -culture=en -featureleveles31 " +
            "-ResX=320 -ResY=180 -ProjectDir=${prepared.project.absolutePath}/\n"
        for (relative in listOf("internal/UE4CommandLine.txt", "internal/UE4Game/PesMobile/UE4CommandLine.txt",
            "external/UE4Game/PesMobile/UE4CommandLine.txt", "external/PesMobile/UE4CommandLine.txt")) {
            integrity.ensure(File(root, relative), relative) { command.byteInputStream() }
        }
        // These are the actual private paths resolved by FAndroidPlatformFile.
        val nativeRoot = File(root, "internal/UE4Game/PesMobile")
        for (name in resources.keys) {
            val source = File(projectRoot, name)
            integrity.ensure(File(nativeRoot, name), "native/$name") { source.inputStream() }
        }
        check(installation() == identity) { "Installed game changed during verification; retry with the game update finished" }
        audit("[BOT-FILES] SUMMARY result=PASS verified=${integrity.verified} repaired=${integrity.repaired} " +
            "cpk=${names.size} resources=${resources.size} nativeResources=${resources.size} databaseTables=$databaseTables nativeDatabaseTables=44 " +
            "downloadedUpdates=NOT_CHECKED")
        return prepared
    }

    private fun resourceDestination(root: File, name: String): File {
        check(name.startsWith("PesMobile/Content/Paks/")) { "Unexpected game resource path $name" }
        val destination = File(root, name)
        check(destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Unsafe game resource path" }
        return destination
    }
}
