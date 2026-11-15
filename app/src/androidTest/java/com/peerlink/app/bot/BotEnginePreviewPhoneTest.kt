package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Verify dispatch on the actual native actor tick, without manufacturing a
 * preview, network agreement or server-authorized match. */
@RunWith(AndroidJUnit4::class)
class BotEnginePreviewPhoneTest {
    @Test(timeout = 160_000) fun dispatchesOnTheOriginalMenuTickAndRestoresIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val uid = InstrumentationRegistry.getArguments().getString("botUid", "2032791990").toLong()
        try {
            assertTrue(EngineBridge.stageEngine(context))
            assertTrue(EngineHost.lastError, EngineHost.prepare(context, uid))
            val before = EngineHost.snapshot().values[20]
            val deadline = System.nanoTime() + 15_000_000_000L
            var details: String
            do {
                assertTrue(EngineHost.probePreviewCallback())
                Thread.sleep(100)
                details = EngineHost.loadingDiagnostics()
                assertTrue("Original menu tick never serviced the callback: $details", System.nanoTime() < deadline)
            } while (!details.contains("previewProbeDone=1"))
            assertTrue(details.contains("previewCallbackRestored=1"))
            assertTrue(details.contains("previewCallbackActive=0"))
            assertTrue(details.contains("previewMenuRetries=0"))
            assertTrue(details.contains("previewContinueQueued=0"))
            fun field(name: String) = Regex("(?:^| )$name=(\\d+)").find(details)!!.groupValues[1].toInt()
            assertTrue(field("previewRequestTid") > 0)
            assertTrue(field("previewCallbackTid") > 0)
            assertNotEquals("Callback ran on the requesting runtime thread", field("previewRequestTid"), field("previewCallbackTid"))
            Thread.sleep(1000)
            assertTrue("Original frame loop stopped after callback restoration", EngineHost.snapshot().values[20] > before)
            Log.i("PeerlinkPreviewTest", "ORIGINAL_PREVIEW_TICK_DISPATCH_AND_RESTORE_VERIFIED $details")
        } finally { EngineHost.stop("preview_callback_test_finished") }
    }
}
