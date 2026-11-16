package com.peerlink.app.bot

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class BotWireCaptureTest {
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
