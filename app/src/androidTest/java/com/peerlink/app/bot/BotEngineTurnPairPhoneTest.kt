package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Two original engines and an explicitly owned local TLS TURN server.
 * The separate fixture mailbox carries their opaque connection records.
 * No Konami authentication, server match ID or kickoff is asserted here.
 */
@RunWith(AndroidJUnit4::class)
class BotEngineTurnPairPhoneTest {
    @Test(timeout = 180_000) fun opensConfiguredTransportUsingOriginalConnectionRecords() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val uid = args.getString("botUid")!!.toLong()
        val peer = args.getString("peerUid")!!.toLong()
        val role = requireNotNull(args.getString("pairRole"))
        require(role == "A" || role == "B")
        val start = requireNotNull(args.getString("connectingStart")).toLong()
        val root = File(context.filesDir, "early-channel-test")
        val squad = File(root, "squad.dto").readBytes()
        val input = File(root, "turn.dto").readBytes()
        val mailbox = DatagramSocket(InetSocketAddress("127.0.0.1", if (role == "A") 39205 else 39206))
            .apply { soTimeout = 25 }
        val ferry = if (role == "A") ProductionPairFerry(uid, peer) else null
        try {
            assertTrue(EngineBridge.stageEngine(context))
            assertTrue(EngineHost.lastError, EngineHost.prepare(context, uid))
            assertTrue(EngineHost.turnRequest().size in 5..1028)
            assertTrue(EngineHost.configureTurn(input))
            val record = requireNotNull(EngineHost.buildPeerRecord(peer,
                "127.0.0.1", if (role == "A") PeerSlot.ENGINE_PORT else 39202,
                "127.0.0.2", if (role == "A") PeerSlot.ENGINE_REFLEXIVE_PORT else 39204,
                if (role == "A") 271581328 else 270532787,
                "::", 0, "::", 0, 0))
            assertTrue(EngineHost.setPeers(record))
            if (role == "A") PeerSlot.engineAttached = true
            assertTrue(EngineHost.beginIntroduction(peer, start))
            assertTrue(PeerConnectionPhase.openAfterIntroduction(
                running = { true }, sdkVerdict = EngineHost::check,
                openSession = { EngineHost.openSession(peer, start) },
                progress = { Log.i("PeerlinkTurnTest", "TURN_${role}_INTRODUCTION verdict=$it") },
                timeoutSeconds = 45, pause = { Thread.sleep(100) },
            ))
            var prepared = false
            var acceptedPeerRecord = false
            var nextAnnouncement = 0L
            var lastState = ""
            val deadline = System.nanoTime() + 90_000_000_000L
            while (true) {
                val ownAddress = EngineHost.turnAddress()
                val now = System.nanoTime()
                if (ownAddress != null && now >= nextAnnouncement) {
                    mailbox.send(DatagramPacket(ownAddress, ownAddress.size,
                        InetSocketAddress("127.0.0.1", if (role == "A") 39206 else 39205)))
                    nextAnnouncement = now + 250_000_000
                }
                try {
                    val incoming = DatagramPacket(ByteArray(2048), 2048)
                    mailbox.receive(incoming)
                    assertTrue(EngineHost.peerTurnAddress(incoming.data.copyOf(incoming.length)))
                    if (!acceptedPeerRecord)
                        Log.i("PeerlinkTurnTest", "ORIGINAL_TURN_${role}_PEER_RECORD_ACCEPTED bytes=${incoming.length}")
                    acceptedPeerRecord = true
                } catch (_: SocketTimeoutException) { }
                val state = EngineHost.snapshot()
                val line = state.values.contentToString()
                if (line != lastState) {
                    Log.i("PeerlinkTurnTest", "TURN_$role state=$line")
                    lastState = line
                }
                assertTrue("The engine selected its fallback transport: $line", state.configuredTurnTransport)
                if (!prepared && state.sessionConnected) {
                    assertTrue(EngineHost.prepareChannels(peer, squad))
                    prepared = true
                }
                if (prepared && state.connected) break
                assertTrue("Configured transport did not open: $line", now < deadline)
                Thread.sleep(50)
            }
            assertTrue(acceptedPeerRecord)
            assertNotNull(EngineHost.turnAddress())
            assertFalse(EngineHost.snapshot().canTestPass)
            Log.i("PeerlinkTurnTest", "ORIGINAL_CONFIGURED_TURN_${role}_CHANNELS_CONNECTED")
            Thread.sleep(3000)
            assertTrue(EngineHost.snapshot().connected)
        } finally {
            Thread.sleep(1000)
            EngineHost.stop()
            mailbox.close()
            ferry?.close()
        }
    }
}
