package com.peerlink.app.bot

import java.util.Base64

/** Carry opaque records between the original engine and the authenticated gate.
 * Addresses are generated and interpreted by the engine, never by this adapter.
 */
internal class NativeTurnExchange(
    private val localAddress: () -> ByteArray?,
    private val acceptPeer: (ByteArray) -> Boolean,
    private val send: (String, Int) -> Map<*, *>,
    private val get: () -> Map<*, *>,
    private val watch: () -> Map<*, *>,
    private val log: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var nextPoll = 0L
    private var sent: ByteArray? = null
    private var received: ByteArray? = null
    private var getStarted = false

    fun pump() {
        val now = clock()
        if (now < nextPoll) return
        nextPoll = now + 1000
        val local = localAddress()
        if (local != null && !local.contentEquals(sent)) {
            require(local.size in 1..2048) { "The engine's connection record has an invalid length" }
            val reply = send(Base64.getEncoder().encodeToString(local), local.size)
            if (reply["result"] == "NOERR") {
                sent = local.copyOf()
                log("original connection record announced (${local.size} bytes)")
            }
        }
        // GET delivers queued peer data; WATCH handles subsequent updates.
        // Both responses go to the original peer-address input API.
        if (!getStarted) {
            val reply = get()
            accept(reply)
            getStarted = reply["result"] == "NOERR"
        }
        accept(watch())
    }

    private fun accept(reply: Map<*, *>) {
        if (reply["result"] != "NOERR") return
        val encoded = reply["address"] as? String ?: return
        if (encoded.isBlank()) return
        val bytes = Base64.getDecoder().decode(encoded.filterNot(Char::isWhitespace))
        require(bytes.size in 1..2048) {
            "The server's peer connection record has an invalid decoded length (${bytes.size} bytes)"
        }
        // The game's callback 0x7e8f444 passes the declared payload length
        // to 0x7e23714, separately from the decoded fixed-capacity buffer.
        // An IPv4 record can contain 7 useful bytes in a 20-byte buffer.
        val count = if (reply.containsKey("address_data_size")) {
            val size = (reply["address_data_size"] as? Number)?.toLong()
            require(size != null && size in 0..bytes.size.toLong()) {
                "The server's peer connection record length exceeds its available data " +
                    "(declared=${size ?: "non-numeric"}, decoded=${bytes.size})"
            }
            size.toInt()
        } else bytes.size
        // The original native input treats a zero-length record as no data.
        if (count == 0) return
        val record = if (count == bytes.size) bytes else bytes.copyOf(count)
        if (!record.contentEquals(received)) {
            check(acceptPeer(record)) { "The bot engine rejected the peer's connection record" }
            received = record.copyOf()
            log("peer connection record supplied to original engine (${record.size} bytes, buffer=${bytes.size})")
        }
    }
}
