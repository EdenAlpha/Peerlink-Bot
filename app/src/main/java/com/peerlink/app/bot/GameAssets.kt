package com.peerlink.app.bot

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipInputStream

/** App-private working files; the installed game and its saved data remain read-only. */
internal object GameAssets {
    data class Prepared(val game: Context, val root: File, val missingMatchResources: Int = 0,
                        val savedDatabasePack: GameDatabaseUpdate.Pack? = null) {
        val project get() = File(root, "project/PesMobile")
        val database get() = File(root, "database")
    }

    fun prepare(owner: Context, status: (String) -> Unit, audit: (String) -> Unit = {},
                allowMissingMatchResources: Boolean = false,
                releaseVerifiedCache: (File) -> Long = { 0 },
                fingerprintPrivateFile: (File) -> GameFileIntegrity.Fingerprint = GameFileIntegrity::fingerprintFile): Prepared {
        val report = StringBuilder("PeerLink startup file audit\nstartedAtMillis=${System.currentTimeMillis()}\n" +
            "This is the last startup audit; exporting or reusing an engine does not rerun it.\n")
        val record: (String) -> Unit = { line -> report.appendLine(line); audit(line) }
        try { return prepareChecked(owner, status, record, allowMissingMatchResources, releaseVerifiedCache, fingerprintPrivateFile) }
        catch (error: Exception) {
            record("[BOT-FILES] SUMMARY result=FAIL error=${error.javaClass.simpleName}: ${error.message}")
            throw error
        } finally {
            report.appendLine("finishedAtMillis=${System.currentTimeMillis()}")
            runCatching {
                val temporary = File.createTempFile(".file-audit-", ".tmp", owner.filesDir)
                try {
                    temporary.outputStream().use { it.write(report.toString().toByteArray()); it.fd.sync() }
                    check(temporary.renameTo(auditReportFile(owner))) { "Cannot save startup file audit" }
                } finally { temporary.delete() }
            }.onFailure { audit("[BOT-FILES] REPORT_SAVE_FAILED error=${it.javaClass.simpleName}") }
        }
    }

    fun auditReportFile(owner: Context): File = File(owner.filesDir, "bot-startup-file-audit.txt")

    private fun prepareChecked(owner: Context, status: (String) -> Unit, audit: (String) -> Unit,
                               allowMissingMatchResources: Boolean,
                               releaseVerifiedCache: (File) -> Long,
                               fingerprintPrivateFile: (File) -> GameFileIntegrity.Fingerprint): Prepared {
        val game = owner.createPackageContext("jp.konami.pesam", Context.CONTEXT_IGNORE_SECURITY)
        fun installation(): String {
            val info = owner.packageManager.getPackageInfo("jp.konami.pesam", 0)
            val app = owner.packageManager.getApplicationInfo("jp.konami.pesam", 0)
            return "${info.versionName}:${info.longVersionCode}:${info.lastUpdateTime}:" +
                (listOf(app.sourceDir) + app.splitSourceDirs.orEmpty()).joinToString("|")
        }
        val identity = installation()
        audit("[BOT-FILES] SOURCE installation=$identity")
        audit("[BOT-FILES] SCOPE installed_APK_libraries_and_assets plus optional owner_imported_database_update")
        var advisedFiles = 0
        var advisedBytes = 0L
        val integrity = GameFileIntegrity(audit, { file ->
            if (file.length() >= 1024L * 1024) {
                val bytes = releaseVerifiedCache(file)
                if (bytes > 0) {
                    advisedFiles++
                    advisedBytes += bytes
                }
            }
        }, fingerprintPrivateFile)
        val root = File(owner.filesDir, "bot-game-11.1.1")
        check(root.isDirectory || root.mkdirs()) { "Cannot create bot working files" }
        val fingerprints = InstalledAssetFingerprints(File(owner.cacheDir, "bot-installed-assets-v1"), identity)
        var reusedSourceBytes = 0L
        fun ensureInstalled(destination: File, label: String, key: String = label,
                            open: () -> java.io.InputStream): GameFileIntegrity.Fingerprint {
            var sourceOpened = false
            val verified = integrity.ensure(destination, label, installedSource = fingerprints[key]) {
                sourceOpened = true
                open()
            }
            fingerprints[key] = verified
            if (!sourceOpened) reusedSourceBytes += verified.bytes
            return verified
        }
        val prepared = Prepared(game, root)
        val assets = game.assets
        val names = assets.list("").orEmpty().filter {
            it.endsWith("_all.cpk") || it.endsWith("_and.cpk") || it.contains("_eng_") && it.endsWith(".cpk")
        }.sorted()
        check("dt200_mobile_all.cpk" in names) { "Installed game's database pack is missing" }
        val assetDir = File(root, "assets")
        var databasePackHash = ""
        for ((index, name) in names.withIndex()) {
            status("Checking installed game packs ${index + 1}/${names.size}")
            val pack = ensureInstalled(File(assetDir, name), "assets/$name") { assets.open(name) }
            if (name == "dt200_mobile_all.cpk") databasePackHash = pack.sha256
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
                val table = name.substringAfterLast('/')
                val stride = GameDatabaseSchema.strides[table]
                val outputName = table.removeSuffix(".bin") + ".decoded"
                val decoded by lazy(LazyThreadSafetyMode.NONE) {
                    GameArchive.decodeDatabase(archive.read(name)).also {
                        if (stride != null) GameDatabaseSchema.validate(table, it.size)
                    }
                }
                val verified = ensureInstalled(File(prepared.database, outputName), "database/$outputName",
                    "decoded-v1/$databasePackHash/$outputName") { decoded.inputStream() }
                val records = if (stride != null) GameDatabaseSchema.validate(table, Math.toIntExact(verified.bytes)) else null
                audit("[BOT-FILES] DATABASE table=$table decodedBytes=${verified.bytes} " +
                    "recordStride=${stride ?: "not_in_native_builder"} records=${records ?: "not_checked"} container=VALID")
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
        for ((name, fingerprint) in resources) {
            val source = File(projectRoot, name)
            val target = File(nativeRoot, name)
            GameResourcePacks.link(source, target, fingerprint, audit)
            audit("[BOT-FILES] NATIVE_RESOURCE result=PASS file=native/$name shared=${Files.isSameFile(source.toPath(), target.toPath())} " +
                "bytes=${fingerprint.bytes} SHA256=${fingerprint.sha256}")
        }
        val nativePaks = File(nativeRoot, "PesMobile/Content/Paks")
        // The base ZIP has only five files. Other installed IoStore triplets
        // and the original downloaded match packs are separate resources.
        for (name in assets.list("").orEmpty().filter {
            Regex("pc[0-9]{4}_mobile_and\\.(pak|utoc|ucas)").matches(it) && it != "pc0000_mobile_and.pak"
        }.sorted()) {
            val source = File(prepared.project, "Content/Paks/$name")
            val fingerprint = ensureInstalled(source, "packaged/$name") { assets.open(name) }
            GameResourcePacks.link(source, File(nativePaks, name), fingerprint, audit)
        }
        val missingMatchResources = GameResourcePacks.verifyAndLink(owner, nativePaks, audit, allowMissingMatchResources)
        val savedDatabasePack = GameDatabaseUpdate.stage(owner, root, audit)
        check(installation() == identity) { "Installed game changed during verification; retry with the game update finished" }
        val cacheSaved = fingerprints.save()
        audit("[BOT-FILES] INSTALLATION_CACHE reusedSourceBytes=$reusedSourceBytes saved=$cacheSaved")
        audit("BOT_MEMORY_CACHE phase=verified_copies advisedFiles=$advisedFiles advisedBytes=$advisedBytes")
        audit("[BOT-FILES] SUMMARY result=PASS verified=${integrity.verified} repaired=${integrity.repaired} " +
            "cpk=${names.size} resources=${resources.size} nativeResources=${resources.size} databaseTables=$databaseTables nativeDatabaseTables=44 " +
            "savedDatabaseUpdateStaged=${savedDatabasePack != null}")
        return prepared.copy(missingMatchResources = missingMatchResources, savedDatabasePack = savedDatabasePack)
    }

    private fun resourceDestination(root: File, name: String): File {
        check(name.startsWith("PesMobile/Content/Paks/")) { "Unexpected game resource path $name" }
        val destination = File(root, name)
        check(destination.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "Unsafe game resource path" }
        return destination
    }
}
