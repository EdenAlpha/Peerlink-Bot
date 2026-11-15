package com.peerlink.app.bot

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** Explicit local fixture: run the same installed APK in two Android users.
 * Both SDKs generate their own packets. No server login or kickoff is claimed.
 */
@RunWith(AndroidJUnit4::class)
class BotEnginePairPhoneTest {
    @Test(timeout = 300_000) fun opensOriginalDataChannelsWithAnotherOriginalAndroidEngine() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val args = InstrumentationRegistry.getArguments()
        val uid = args.getString("botUid")!!.toLong()
        val peer = args.getString("peerUid")!!.toLong()
        val role = args.getString("pairRole") ?: error("Supply pairRole A or B")
        require(role == "A" || role == "B")
        val start = args.getString("connectingStart")!!.toLong()
        val peerNat = args.getString("peerNat")?.toLongOrNull() ?: 271581233L
        val beforePeerDelay = args.getString("beforePeerDelayMs")?.toLongOrNull() ?: 0L
        require(peerNat in 0..0xffffffffL && beforePeerDelay in 0..180_000)
        val squad = File(context.filesDir, "early-channel-test/squad.dto").readBytes()
        val ferry = if (role == "A") PairFerry() else null
        try {
            assertTrue(EngineBridge.stageEngine(context))
            assertTrue(EngineHost.lastError, EngineHost.prepare(context, uid))
            Log.i("PeerlinkPairTest", "PAIR_$role prepared; peerNat=$peerNat beforePeerDelayMs=$beforePeerDelay")
            if (beforePeerDelay > 0) Thread.sleep(beforePeerDelay)
            val port = if (role == "A") 39201 else 39202
            val record = requireNotNull(EngineHost.buildPeerRecord(peer,
                "127.0.0.1", port, "127.0.0.1", port, peerNat,
                "::", 0, "::", 0, 0))
            assertTrue(EngineHost.setPeers(record))
            assertTrue(EngineHost.openSession(peer, start))
            val deadline = System.nanoTime() + 45_000_000_000L
            var prepared = false
            var last = ""
            while (true) {
                val state = EngineHost.snapshot()
                val line = state.values.take(8).joinToString(",") + ",${state.matchDataHandlerPresent}"
                if (line != last) {
                    Log.i("PeerlinkPairTest", "PAIR_$role state=$line")
                    last = line
                }
                if (!prepared && state.sessionConnected) {
                    Log.i("PeerlinkPairTest", "ORIGINAL_ANDROID_PAIR_${role}_BASE_SESSION_CONNECTED")
                    assertTrue(EngineHost.prepareChannels(peer, squad))
                    prepared = true
                    continue
                }
                if (prepared && state.connected) break
                assertTrue("Original engines did not connect: $line", System.nanoTime() < deadline)
                Thread.sleep(100)
            }
            Log.i("PeerlinkPairTest", "ORIGINAL_ANDROID_PAIR_${role}_CHANNELS_CONNECTED")
            Thread.sleep(5000)
            assertTrue("Original connection did not remain open", EngineHost.snapshot().connected)
            assertTrue(EngineHost.snapshot().matchDataHandlerPresent)
            assertFalse("No match ID or start request was supplied", EngineHost.snapshot().canTestPass)
        } finally {
            // Keep both peers alive through the other test's final assertion.
            Thread.sleep(1000)
            EngineHost.stop()
            ferry?.close()
        }
    }

    /** Two plain UDP sockets forward original payloads without inspecting or
     * answering them. Learn only each native SDK's ephemeral source endpoint.
     */
    private class PairFerry : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val sockets = arrayOf(39201, 39202).map {
            DatagramSocket(InetSocketAddress("127.0.0.1", it)).apply { soTimeout = 100 }
        }
        private val endpoints = arrayOfNulls<InetSocketAddress>(2)
        private val pending = arrayOf(ArrayList<ByteArray>(), ArrayList<ByteArray>())
        private var traced = 0
        private val threads = sockets.indices.map { index ->
            Thread({
                while (running.get()) {
                    try {
                        val packet = DatagramPacket(ByteArray(65535), 65535)
                        sockets[index].receive(packet)
                        synchronized(this) {
                            val source = packet.socketAddress as InetSocketAddress
                            if (endpoints[index] != source)
                                Log.i("PeerlinkPairTest", "PAIR_ENDPOINT index=$index source=$source")
                            endpoints[index] = source
                            val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            if (traced++ < 80) trace(index, bytes)
                            pending[index].add(bytes)
                            for (from in sockets.indices) {
                                val to = 1 - from
                                val destination = endpoints[to] ?: continue
                                for (bytes in pending[from])
                                    sockets[to].send(DatagramPacket(bytes, bytes.size, destination))
                                pending[from].clear()
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                    } catch (error: Exception) {
                        if (running.get()) Log.e("PeerlinkPairTest", "Fixture ferry stopped", error)
                    }
                }
            }, "owned-pair-ferry-$index").apply { isDaemon = true; start() }
        }
        private fun trace(from: Int, bytes: ByteArray) {
            fun u16(at: Int) = ((bytes[at].toInt() and 255) shl 8) or (bytes[at + 1].toInt() and 255)
            if (bytes.size < 20) return
            var at = 20
            var kind = -1
            var capability = "none"
            while (at + 4 <= bytes.size) {
                val tag = u16(at)
                val count = u16(at + 2)
                if (at + 4 + count > bytes.size) break
                if (tag == 0x9090 && count >= 1) kind = bytes[at + 4].toInt() and 255
                if (tag == 0x909d && count == 4)
                    capability = bytes.copyOfRange(at + 4, at + 8).joinToString("") { "%02x".format(it.toInt() and 255) }
                at += 4 + count
            }
            Log.i("PeerlinkPairTest", "PAIR_FRAME from=$from op=${u16(0).toString(16)} bytes=${bytes.size} kind=$kind capability=$capability")
        }
        override fun close() {
            running.set(false)
            sockets.forEach { it.close() }
            threads.forEach { it.join(1000) }
        }
    }
}
