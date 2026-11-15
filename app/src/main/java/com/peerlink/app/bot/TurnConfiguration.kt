package com.peerlink.app.bot

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.Base64

/** Actual CMD_GET_TURN_SERVER_LIST inputs for the original native service.
 * This private IPC DTO is not a game packet or a server response.
 */
internal object TurnConfiguration {
    fun encode(reply: Map<*, *>): ByteArray {
        require(reply["result"] == "NOERR") { "The server did not supply the match transport configuration" }
        val rows = requireNotNull(reply["turn_server_list"] as? List<*>)
        require(rows.size in 1..64) { "The match transport server list is empty or too large" }
        val account = requireNotNull(reply["turn_account"] as? Map<*, *>)
        val id = text(account["id"], 1024)
        val password = text(account["pw"], 1024)
        val origin = text(account["origin"] ?: "", 1024, allowEmpty = true)
        val encodedCertificate = requireNotNull(account["server_certificate"] as? String)
        val certificate = Base64.getDecoder().decode(encodedCertificate.filterNot(Char::isWhitespace))
        require(certificate.size in 1..2048 &&
            (account["server_certificate_data_size"] as? Number)?.toInt() == certificate.size) {
            "The match transport certificate length is invalid"
        }
        var tlsServers = 0
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBytes("TRN1")
                out.writeInt(rows.size)
                for (value in rows) {
                    val row = requireNotNull(value as? Map<*, *>)
                    val zone = requireNotNull(row["zone"] as? Number).toLong()
                    val address = text(row["address"], 253)
                    require(address.all { it.toInt() in 33..126 }) { "Invalid match transport hostname" }
                    val port = port(row["port"])
                    val tlsPort = port(row["tls_port"])
                    require(zone in 0..0xffffffffL)
                    if (tlsPort != 0) tlsServers++
                    out.writeInt(zone.toInt())
                    out.writeInt(port)
                    out.writeInt(tlsPort)
                    out.blob(address)
                }
                require(tlsServers > 0) { "The server supplied no TLS match transport endpoint" }
                out.blob(id)
                out.blob(password)
                out.blob(origin)
                out.blob(certificate)
            }
        }.toByteArray()
    }

    private fun text(value: Any?, maximum: Int, allowEmpty: Boolean = false): ByteArray =
        requireNotNull(value as? String).toByteArray(Charsets.UTF_8).also {
            require(it.size in (if (allowEmpty) 0 else 1)..maximum && 0.toByte() !in it) { "Invalid match transport input length" }
        }

    private fun port(value: Any?): Int = requireNotNull(value as? Number).toLong().also {
        require(it in 0..65535) { "Invalid match transport port" }
    }.toInt()

    private fun DataOutputStream.blob(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }
}
