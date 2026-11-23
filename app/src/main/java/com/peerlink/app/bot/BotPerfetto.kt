package com.peerlink.app.bot

import android.content.Context
import android.os.Process
import com.peerlink.app.godmode.GodModeManager
import java.io.File

internal object BotPerfetto {
    fun shell(command: String, timeoutMs: Int = 15_000) = GodModeManager.runUserShell(command, timeoutMs)

    fun asApp(ctx: Context) = "run-as ${ctx.packageName} --user ${Process.myUid() / 100000}"

    fun tracePath(key: String) = "/data/misc/perfetto-traces/$key.pftrace"

    fun start(key: String, config: String) = shell(
        // On Android, Perfetto can read the shell's pipe. Its domain cannot read /data/local/tmp.
        "printf '%s\\n' '${config.replace("'", "'\"'\"'")}' | perfetto --txt -c - --detach=$key -o ${tracePath(key)}"
    )

    fun stop(key: String): Boolean {
        // Exit 2 also covers a session that reached its duration or file-size limit.
        return shell("perfetto --attach=$key --stop >/dev/null 2>&1; rc=\$?; [ \"\$rc\" = 0 ] || [ \"\$rc\" = 2 ]").ok
    }

    fun copyPrefix(ctx: Context, key: String, dirName: String, output: File, maxBytes: Long, timeoutMs: Int = 15_000): Boolean {
        val source = tracePath(key)
        // Stream the file between the shell and app. Prime only returns the byte count over IPC.
        val command = """
            n=$(wc -c < $source) || exit 1
            [ "${'$'}n" -gt 0 ] && [ "${'$'}n" -le $maxBytes ] || exit 1
            head -c "${'$'}n" $source | ${asApp(ctx)} sh -c 'umask 077; cat > files/$dirName/${output.name}' || exit 1
            printf '%s' "${'$'}n"
        """.trimIndent()
        val result = shell(command, timeoutMs)
        val expected = result.output.trim().toLongOrNull() ?: return false
        return result.ok && expected in 1..maxBytes && output.isFile && output.length() == expected
    }
}
