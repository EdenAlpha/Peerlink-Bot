package com.peerlink.app.bot

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class TurnConfigurationTest {
    private val certificate = byteArrayOf(0, 0xff.toByte(), 0x30, 0x48)
    private fun response() = mapOf(
        "result" to "NOERR",
        "turn_server_list" to listOf(mapOf("zone" to 2, "address" to "turn.example.test", "port" to 3478, "tls_port" to 5349)),
        "turn_account" to mapOf("id" to "owned-fixture-user", "pw" to "owned-fixture-password", "origin" to "",
            "server_certificate" to Base64.getEncoder().encodeToString(certificate),
            "server_certificate_data_size" to certificate.size),
    )

    @Test fun preservesTlsEndpointAndBinaryCertificateFromTheServerSchema() {
        DataInputStream(ByteArrayInputStream(TurnConfiguration.encode(response()))).use { input ->
            assertEquals(0x54524e31, input.readInt())
            assertEquals(1, input.readInt())
            assertEquals(2, input.readInt())
            assertEquals(3478, input.readInt())
            assertEquals(5349, input.readInt())
            fun blob() = ByteArray(input.readInt()).also(input::readFully)
            assertEquals("turn.example.test", blob().toString(Charsets.UTF_8))
            assertEquals("owned-fixture-user", blob().toString(Charsets.UTF_8))
            assertEquals("owned-fixture-password", blob().toString(Charsets.UTF_8))
            assertEquals(0, blob().size)
            assertArrayEquals(certificate, blob())
            assertEquals(-1, input.read())
        }
    }

    @Test fun rejectsAnEmptyListInsteadOfSilentlySelectingTheFallback() {
        val invalid = response().toMutableMap().also { it["turn_server_list"] = emptyList<Any>() }
        assertThrows(IllegalArgumentException::class.java) { TurnConfiguration.encode(invalid) }
    }

    @Test fun rejectsIncorrectCertificateLength() {
        val invalid = response().toMutableMap()
        invalid["turn_account"] = (invalid["turn_account"] as Map<*, *>).entries
            .associate { it.key.toString() to it.value }.toMutableMap().also { it["server_certificate_data_size"] = 100 }
        assertThrows(IllegalArgumentException::class.java) { TurnConfiguration.encode(invalid) }
    }
}
