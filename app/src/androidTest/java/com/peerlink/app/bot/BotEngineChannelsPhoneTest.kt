package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.DatagramSocket
import java.net.InetSocketAddress

/** Explicit owned native-input test; no room, game ID or connectivity is fabricated. */
@RunWith(AndroidJUnit4::class)
class BotEngineChannelsPhoneTest {
    @Test(timeout = 300_000) fun refusesMatchDataConstructionBeforeNativeSessionConnects() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val uid = args.getString("botUid")?.toLongOrNull() ?: error("Supply owned botUid")
        val peer = args.getString("peerUid")?.toLongOrNull() ?: error("Supply owned peerUid")
        val squad = File(context.filesDir, "early-channel-test/squad.dto").readBytes()
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { sink ->
            try {
                assertTrue("Could not stage the installed engine", EngineBridge.stageEngine(context))
                assertTrue(EngineHost.lastError, EngineHost.prepare(context, uid))
                val record = requireNotNull(EngineHost.buildPeerRecord(peer,
                    "127.0.0.1", sink.localPort, "127.0.0.1", sink.localPort, 271581233,
                    "::", 0, "::", 0, 0))
                assertTrue(EngineHost.setPeers(record))
                assertTrue(EngineHost.openSession(peer, System.currentTimeMillis() / 1000 + 2))
                val before = EngineHost.snapshot()
                Log.i("PeerlinkChannelTest", "BEFORE_CHANNEL_MODEL state=${before.values.contentToString()}")
                assertFalse("Handler already existed before the tested setup", before.matchDataHandlerPresent)
                assertFalse("An unanswered socket is not a connected session", before.sessionConnected)
                assertFalse("Premature construction must be rejected", EngineHost.prepareChannels(peer, squad))
                val after = EngineHost.snapshot()
                Log.i("PeerlinkChannelTest", "PREMATURE_CHANNEL_SETUP_REJECTED state=${after.values.contentToString()}")
                assertFalse("No handler should be created for an unconnected session", after.matchDataHandlerPresent)
                assertFalse("A local sink is not a peer connection", after.connected)
                assertFalse("No match was started", after.canTestPass)
                assertFalse("Repeated premature preparation must remain rejected", EngineHost.prepareChannels(peer, squad))
                Thread.sleep(5000)
                val unanswered = EngineHost.snapshot()
                Log.i("PeerlinkChannelTest", "UNANSWERED_PEER_STATE state=${unanswered.values.contentToString()}")
                assertFalse("An unanswered socket cannot establish a native connection", unanswered.connected)
                assertFalse("An unanswered socket cannot start a match", unanswered.canTestPass)
                assertTrue(EngineHost.probeController())
                assertFalse(unanswered.matchDataHandlerPresent)
                Log.i("PeerlinkChannelTest", "UNANSWERED_PEER_NOT_REPORTED_AS_CONNECTED")
            } finally { EngineHost.stop() }
        }
    }
}
