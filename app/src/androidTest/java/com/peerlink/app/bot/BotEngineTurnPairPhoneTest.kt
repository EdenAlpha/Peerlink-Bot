package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.peerlink.app.core.AppState
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
    @Test(timeout = 180_000) fun opensConfiguredTransportWithOriginalConnectionRecordsWhenNeeded() {
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
            assertTrue("File verification did not reach the exported log",
                AppState.getLogs().contains("[BOT-FILES] SUMMARY result=PASS"))
            val revisionCheck = MatchServerSettings.revisionCheck(mapOf("result" to "NOERR",
                "is_revision_check" to args.getString("revisionCheck", "YES")))
            assertTrue("Could not apply the server revision policy", EngineHost.configureRevisionCheck(revisionCheck))
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
            assertTrue(PeerConnectionPhase.openAfterNetworkIo(
                running = { true }, transportIoReady = EngineHost::transportIoReady,
                openSession = { EngineHost.openSession(peer, start) },
                progress = { Log.i("PeerlinkTurnTest", "TURN_${role}_NETWORK_IO_READY state=$it") },
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
                    assertTrue(EngineHost.prepareChannels(peer, squad, if (role == "A") uid else peer))
                    prepared = true
                }
                if (prepared && state.connected) break
                assertTrue("Configured transport did not open: $line", now < deadline)
                Thread.sleep(50)
            }
            // Original direct P2P can connect without allocating TURN; in that
            // case the engine correctly publishes no relay connection record.
            // Any record that is published still travels unchanged via mailbox.
            Log.i("PeerlinkTurnTest", "ORIGINAL_CONFIGURED_TURN_${role}_METADATA localBytes=${EngineHost.turnAddress()?.size ?: 0} peerAccepted=$acceptedPeerRecord")
            assertFalse(EngineHost.snapshot().canTestPass)
            Log.i("PeerlinkTurnTest", "ORIGINAL_CONFIGURED_TURN_${role}_CHANNELS_CONNECTED")
            Thread.sleep(3000)
            val connected = EngineHost.snapshot()
            assertTrue(connected.connected)
            assertEquals(29, connected.values.size)
            assertEquals(0L, connected.values[23])
            assertEquals(1L, connected.values[26])
            assertEquals(2L, connected.values[28])
            assertEquals("Original model did not inherit the configured server policy", if (revisionCheck) 1L else 0L, connected.values[25])
            assertTrue("Native metadata synchronization did not complete", connected.metadataReady)
            assertFalse("Changing policy during an active match must be refused", EngineHost.configureRevisionCheck(!revisionCheck))
            assertEquals(if (revisionCheck) 1L else 0L, EngineHost.snapshot().values[25])
            Log.i("PeerlinkTurnTest", "ORIGINAL_SERVER_REVISION_POLICY_${role}_VERIFIED enabled=$revisionCheck")
            val side = if (role == "A") 0 else 1
            assertTrue("Native game-side assignment disagrees with the owned room fixture",
                EngineHost.loadingDiagnostics().contains("ownSide=$side"))
            Log.i("PeerlinkTurnTest", "ORIGINAL_ROOM_SIDE_${role}_VERIFIED ownSide=$side")
            assertTrue("Independent native monitor did not reach the export log",
                AppState.getLogs().contains("[BOT-DIAG] NATIVE_STATE"))
            Log.i("PeerlinkTurnTest", "ORIGINAL_CONFIGURED_TURN_${role}_DIAGNOSTICS_ACTIVE")
            // Explicit private lab fixture for observing the later production
            // loading path. A retained context is not fresh authentication or
            // proof of a server-authorized kickoff.
            if (args.getString("matchLoadingProbe") == "true") {
                val contextInput = java.nio.ByteBuffer.wrap(File(root, "context.dto").readBytes())
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                require(contextInput.int == 0x31494752 && contextInput.int.toLong() == uid)
                val gameId = contextInput.long
                contextInput.int // team ID is read from the squad by the production adapter.
                val sidSize = contextInput.int
                val categorySize = contextInput.int
                val keywordSize = contextInput.int
                require(sidSize in 1..512 && categorySize in 1..64 && keywordSize == 8)
                require(contextInput.remaining() == sidSize + categorySize + keywordSize)
                val sid = ByteArray(sidSize).also { contextInput.get(it) }.toString(Charsets.UTF_8)
                assertTrue("Production match setup rejected the private fixture",
                    EngineHost.setupMatch(peer, gameId, sid, squad))
                assertTrue(EngineHost.startInputTest(true))
                repeat(30) {
                    Thread.sleep(1000)
                    val loading = EngineHost.snapshot()
                    Log.i("PeerlinkTurnTest", "MATCH_LOADING_PROBE_$role state=${loading.values.contentToString()}")
                    Log.i("PeerlinkTurnTest", "MATCH_LOADING_DETAILS_$role ${EngineHost.loadingDiagnostics()}")
                }
            }
            if (args.getString("diagnosticPeerStop") == "true" && role == "A") {
                // B closes its actual process after the same successful hold.
                // Observe exported logs only: no driver/RPC polling to hide
                // whether the service monitor works during an HTTP wait.
                val failureDeadline = System.nanoTime() + 60_000_000_000L
                while (!AppState.getLogs().contains("FIRST_NATIVE_FAILURE") && System.nanoTime() < failureDeadline)
                    Thread.sleep(100)
                val logs = AppState.getLogs()
                assertTrue("Peer process stopped but no native failure reached the export", logs.contains("FIRST_NATIVE_FAILURE"))
                assertTrue(logs.contains("disconnectInitiator=unresolved"))
                assertTrue(logs.contains("trigger=first_native_failure"))
                assertTrue("Database signatures missing from failure export", logs.contains("DATABASE_SIGNATURES localPlayer="))
                Log.i("PeerlinkTurnTest", "ORIGINAL_CONFIGURED_TURN_A_PEER_STOP_OBSERVED")
                logs.lineSequence().filter { it.contains("[BOT-DIAG]") || it.contains("[BOT-FLOW]") }
                    .forEach { Log.i("PeerlinkTurnTest", "EXPORT_DIAGNOSTIC $it") }
            }
        } finally {
            Thread.sleep(1000)
            EngineHost.stop("configured_pair_test_finished")
            mailbox.close()
            ferry?.close()
        }
    }
}
