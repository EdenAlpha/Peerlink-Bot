package com.peerlink.app.bot

import android.content.Context
import android.os.Environment
import android.os.Process
import com.peerlink.app.godmode.PrimeClient
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** Read only the allowlisted packs from the installed game or its saved files.
 * The helper is used only if already available; no new permission is requested.
 */
internal class GameResourceLocalCopies(
    private val context: Context,
    private val names: Set<String>,
    private val report: (String) -> Unit,
) {
    private val game = context.createPackageContext("jp.konami.pesam", Context.CONTEXT_IGNORE_SECURITY)
    private val roots = listOf(
        File(Environment.getExternalStorageDirectory(), "Android/data/jp.konami.pesam/files"),
        File("/data/user/${Process.myUid() / 100000}/jp.konami.pesam/files"),
    )
    private val direct by lazy {
        roots.flatMap { root ->
            if (!root.canRead()) emptyList() else root.walkTopDown().maxDepth(10)
                .filter { it.isFile && it.name in names && it.canRead() }.take(512).toList()
        }.distinctBy { it.absolutePath }.groupBy { it.name }
    }
    private val helper by lazy {
        PrimeClient.init(context)
        if (!PrimeClient.isAlive()) {
            report("[BOT-FILES] LOCAL_HELPER status=UNAVAILABLE downloaded_game_files=NOT_CHECKED")
            emptyMap()
        } else {
            val commands = roots.joinToString("\n") { root ->
                "if [ -r ${quote(root.path)} ] && [ -x ${quote(root.path)} ]; then " +
                    "find ${quote(root.path)} -type f -name 'pc????_mobile_and.*' -print 2>/dev/null; " +
                    "else echo __ROOT_UNREADABLE__; fi"
            }
            val result = PrimeClient.execute("$commands\ntrue", 20_000)
            val paths = result.orEmpty().lineSequence().filter { path ->
                roots.any { path.startsWith(it.path + "/") } &&
                    !path.contains("/../") && File(path).name in names
            }.take(512).toList()
            report("[BOT-FILES] LOCAL_HELPER status=${if (result == null) "UNAVAILABLE" else "CHECK_COMPLETE"} candidates=${paths.size} " +
                "unreadableRoots=${result?.lineSequence()?.count { it == "__ROOT_UNREADABLE__" } ?: roots.size}")
            paths.groupBy { File(it).name }
        }
    }

    fun open(pack: GameResourcePacks.Pack): Sequence<InputStream> = sequence {
        runCatching { game.assets.open(pack.name) }.getOrNull()?.let { yield(it) }
        for (file in direct[pack.name].orEmpty()) {
            if (file.length() == pack.bytes)
                runCatching { file.inputStream() }.getOrNull()?.let { yield(it) }
        }
        for (path in helper[pack.name].orEmpty()) {
            val stream = copyThroughHelper(path, pack)
            if (stream != null) yield(stream)
        }
    }

    private fun copyThroughHelper(path: String, pack: GameResourcePacks.Pack): InputStream? {
        val sourceBytes = PrimeClient.execute("stat -c %s ${quote(path)}", 5000)?.trim()?.toLongOrNull()
        if (sourceBytes != pack.bytes) {
            report("[BOT-FILES] LOCAL_SOURCE_REJECTED file=${pack.name} reason=unreadable_or_size_mismatch")
            return null
        }
        val temporary = File.createTempFile(".game-resource-", ".tmp", context.cacheDir)
        var retained = false
        try {
            val uri = GameResourceImportProvider.grant(context, temporary)
            val result = try {
                // The provider grants only this temporary file, write-only.
                // Private account/save files are never copied or exported.
                PrimeClient.execute("content write --user ${Process.myUid() / 100000} --uri ${quote(uri.toString())} < ${quote(path)}", 120_000)
            } finally { GameResourceImportProvider.revoke(context, uri) }
            if (result == null || temporary.length() != pack.bytes) {
                report("[BOT-FILES] LOCAL_SOURCE_REJECTED file=${pack.name} reason=unreadable_or_size_mismatch")
                return null
            }
            val input = temporary.inputStream()
            retained = true
            return object : FilterInputStream(input) {
                override fun close() { try { super.close() } finally { temporary.delete() } }
            }
        } catch (error: Exception) {
            report("[BOT-FILES] LOCAL_SOURCE_UNAVAILABLE file=${pack.name} error=${error.javaClass.simpleName}")
            return null
        } finally { if (!retained) temporary.delete() }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"
}
