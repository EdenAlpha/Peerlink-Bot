package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit ARM64 test: requires the original supported game installed and an owner-supplied account ID. */
@RunWith(AndroidJUnit4::class)
class BotEnginePhoneTest {
    @Test(timeout = 300_000) fun startsOriginalGameAndOwnControllerWithoutTouchingTheHumanGame() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = InstrumentationRegistry.getArguments().getString("botUid")?.toLongOrNull()
            ?: error("Supply the owned test account's botUid instrumentation argument")
        try {
            assertTrue("Could not stage the installed engine", EngineBridge.stageEngine(context))
            val started = EngineHost.prepare(context, uid)
            assertTrue(EngineHost.lastError, started)
            val state = EngineHost.snapshot()
            Log.i("PeerlinkPhoneTest", "ORIGINAL_ENGINE_STARTED state=${state.values.contentToString()} memoryMiB=${state.memoryMiB}")
            assertFalse("No opponent or match was started by this startup test", state.canTestPass)
            assertTrue("The bot's own software controller was not enumerated", EngineHost.probeController())
            Log.i("PeerlinkPhoneTest", "OWN_BOT_CONTROLLER_ENUMERATED")
        } finally { EngineHost.stop() }
    }
}
