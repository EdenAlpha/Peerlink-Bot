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
        // SEND includes address_data_size, but GET/WATCH may return only the
        // Base64 address. Its decoded byte count is the native input length.
        // If a reply explicitly supplies a size, still reject a mismatch.
        if (reply.containsKey("address_data_size")) {
            val size = reply["address_data_size"] as? Number
            require(size != null && size.toLong() == bytes.size.toLong()) {
                "The server's peer connection record size does not match its decoded length " +
                    "(declared=${size?.toLong() ?: "non-numeric"}, decoded=${bytes.size})"
            }
        }
        if (!bytes.contentEquals(received)) {
            check(acceptPeer(bytes)) { "The bot engine rejected the peer's connection record" }
            received = bytes.copyOf()
            log("peer connection record supplied to original engine (${bytes.size} bytes)")
        }
    }
}
