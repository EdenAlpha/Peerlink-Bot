package com.peerlink.app.bot

import android.content.Context
import android.content.pm.ApplicationInfo
import java.io.File

internal object BotRendering {
    /** The debug-only marker permits a normally rendered peer in lab tests. */
    fun headless(context: Context): Boolean {
        val lab = context.packageName.endsWith(".enginetest") &&
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return !(lab && File(context.filesDir, "early-channel-test/reference-render").isFile)
    }
}
