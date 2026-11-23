package com.peerlink.app.bot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BotWireCaptureTest {
    @Test fun aRetryRetainsTheCompletedTraceAndItsStatus() {
        val folder = Files.createTempDirectory("bot-wire-retry").toFile()
        try {
            val current = File(folder, "bot_transport.csv")
            val previous = File(folder, "bot_transport_previous.csv")
            previous.writeText("older attempt")
            BotWireCapture(current, wallMs = { 123 }, elapsedNs = { 456 }).use { capture ->
                capture.record(PacketFlowTrace.Point.GAME_RX, false, byteArrayOf(0x42))
            }

            BotWireRecorder.archive(current, previous)
            assertFalse(current.exists())
            BotWireCapture(current, wallMs = { 789 }, elapsedNs = { 900 }).use { capture ->
                capture.record(PacketFlowTrace.Point.GAME_RX, false, byteArrayOf(0x43))
            }
            assertTrue(previous.readText().contains("123,456,GAME_RX,host,1,42"))
            assertTrue(previous.readText().contains("# final packets=1 queueOrCapDrops=0"))
            assertTrue(current.readText().contains("789,900,GAME_RX,host,1,43"))
        } finally { folder.deleteRecursively() }
    }

    @Test fun directAndVpnPayloadsSurviveEveryBoundaryAndBufferReuse() {
        val folder = Files.createTempDirectory("bot-wire").toFile()
        try {
            BotWireCapture(File(folder, "live.csv"), wallMs = { 123 }, elapsedNs = { 456 }).use { capture ->
                val payload = ByteArray(256) { it.toByte() }
                for (vpn in listOf(false, true)) for (point in PacketFlowTrace.Point.entries)
                    capture.record(point, vpn, payload)
                payload.fill(0)
                val snapshot = capture.snapshot(File(folder, "export.csv"))
                val rows = snapshot.readLines().filter { it.firstOrNull()?.isDigit() == true }
                assertEquals(8, rows.size)
                for (row in rows) {
                    val columns = row.split(',')
                    assertEquals("123", columns[0]); assertEquals("456", columns[1])
                    assertEquals("256", columns[4])
                    assertArrayEquals(ByteArray(256) { it.toByte() }, columns[5].chunked(2).map { it.toInt(16).toByte() }.toByteArray())
                }
                assertEquals(PacketFlowTrace.Point.entries.map { it.name }.toSet(), rows.map { it.split(',')[2] }.toSet())
                assertEquals(setOf("host", "vpn"), rows.map { it.split(',')[3] }.toSet())
                assertTrue(snapshot.readText().contains("queueOrCapDrops=0 writeErrors=0"))
                assertTrue(snapshot.readText().contains("flushCompleted=true"))
                val stable = snapshot.readText()
                capture.record(PacketFlowTrace.Point.GAME_RX, false, byteArrayOf(99))
                assertEquals(stable, snapshot.readText())
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun forwardUsesTheSocketTimestampsEvenIfTheWriterRunsLater() {
        val folder = Files.createTempDirectory("bot-wire-forward").toFile()
        try {
            BotWireCapture(File(folder, "live.csv"), wallMs = { 99999 }, elapsedNs = { 99999 }).use { capture ->
                val received = FerryReceipt(10, 20, 1000, 2)
                val data = byteArrayOf(0xca.toByte(), 0xfe.toByte())
                capture.recordTransfer(PacketFlowTrace.Point.GAME_RX, false, data, received, 30, 45, 1001, 7)
                capture.recordTransfer(PacketFlowTrace.Point.ENGINE_RX, true, data, received, 35, 47, 1002, 8)
                capture.recordTransfer(PacketFlowTrace.Point.GAME_RX, true, data, received, 0, 0, 0, -1)
                val rows = capture.snapshot(File(folder, "export.csv")).readLines()
                    .filter { it.firstOrNull()?.isDigit() == true }
                assertEquals(listOf(
                    "1000,20,GAME_RX,host,2,cafe,10,20,,", "1001,45,ENGINE_TX,host,2,cafe,10,20,30,7",
                    "1000,20,ENGINE_RX,vpn,2,cafe,10,20,,", "1002,47,GAME_TX,vpn,2,cafe,10,20,35,8",
                    "1000,20,GAME_RX,vpn,2,cafe,10,20,,"
                ), rows)
            }
        } finally { folder.deleteRecursively() }
    }

    @Test fun sizeLimitReportsIncompleteCaptureWithoutTruncatingPackets() {
        val folder = Files.createTempDirectory("bot-wire-cap").toFile()
        try {
            val file = File(folder, "live.csv")
            BotWireCapture(file, maxBytes = 512).use { capture ->
                capture.record(PacketFlowTrace.Point.GAME_RX, false, ByteArray(4096))
                val text = capture.snapshot(File(folder, "export.csv")).readText()
                assertFalse(text.lineSequence().any { it.firstOrNull()?.isDigit() == true })
                assertTrue(text.contains("packets=0 queueOrCapDrops=1 writeErrors=0 capped=true"))
                assertTrue(file.length() <= 512)
            }
        } finally { folder.deleteRecursively() }
    }
}
