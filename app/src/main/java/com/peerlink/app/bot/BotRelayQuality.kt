package com.peerlink.app.bot

import com.peerlink.app.core.AppState
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The relay-quality ladder -- the Kotlin port of chain3.py's
 * `get_qualitycheck_list` + `quality_report` (run #32) and the capture's
 * block 35/38-40 (login) + block 63 (in-room, bare SET) forms.
 *
 * EVERY accepted session on record reported the quality: the captured real
 * client (blocks 38-40 at login, block 63 in the room before block 97/98)
 * and all proven VM runs (login-time full report + the room-time re-send,
 * run #47 order: entry_side -> relay_qual -> check_match_enable -> ready).
 * The app sent none (qualityTemplate was always null) -- and the gate's
 * answer to `gamerelay_measure_result: "COMPLETE"` with an empty measure
 * record is ERR_EMPTY, the exact device failures of 2026-10-15 22:42 and
 * 23:53 (both endpoints shapes refused identically).
 *
 * The endpoints are Konami's UDP echo servers: the returned datagram mirrors
 * the sent payload byte-for-byte, so the round-trip time IS the quality
 * figure. Measured live -- never invented. Reuses the endpoint list fetched
 * at login for the room-time report, exactly like capture block 63 (bare
 * SET, no GET).
 */
object BotRelayQuality {

    private const val TAG = "BOT-QUALITY"

    /** The 34-byte probe the VM runs used; echo servers reflect it as-is. */
    private val PING = "PINGCHAIN3QUALITYCHECK000000000000".toByteArray(Charsets.ISO_8859_1)

    /** Echo timeout / attempts per candidate -- chain3 `_echo_rtt` defaults. */
    private const val ECHO_TIMEOUT_MS = 1_000
    private const val ECHO_TRIES = 2

    /** chain3 `quality_report(workers=24)`. */
    private const val WORKERS = 24

    /** One region's compound address row from the qualitycheck list. */
    data class Row(val region: String, val address: String)

    /** Measured report outcome -- shallow diagnostics only. */
    data class Report(
        val result: String?,
        val measured: Int,
        val expected: Int,
        val skipped: Boolean,
        val reply: Map<*, *>,
    )

    /** Endpoint rows fetched once at login, reused by the room-time report. */
    @Volatile
    private var rows: List<Row> = emptyList()

    private fun log(line: String) = AppState.appendLog("[$TAG] $line")

    /**
     * CMD_GET_GAMERELAY_QUALITYCHECK_LIST -- the request body is EMPTY
     * (chain3, builder FUN_077eac70 "writes no fields"). Returns the region
     * endpoints to measure (address is a compound of alternatives).
     */
    fun fetchRows(gate: KgsGate): Boolean {
        val r = gate.step("CMD_GET_GAMERELAY_QUALITYCHECK_LIST")
        val res = r["result"]?.toString()
        val list = r["quality_check_data_list"] as? List<*> ?: emptyList<Any?>()
        val parsed = ArrayList<Row>()
        for (e in list) {
            val m = e as? Map<*, *> ?: continue
            val region = (m["region"] ?: "").toString()
            val address = (m["address"] ?: "").toString()
            if (region.isNotBlank() && address.isNotBlank()) parsed.add(Row(region, address))
        }
        if (parsed.isEmpty()) {
            log("qualitycheck list -> $res (0 endpoints -- cannot measure)")
            return false
        }
        rows = parsed
        log("qualitycheck list -> $res (${parsed.size} regions)")
        return true
    }

    /** compound quality-check address -> [(host, port), ...] (chain3 `_addr_candidates`). */
    private fun candidates(s: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (grp in s.replace("^", ",").split(",")) {
            for (alt0 in grp.split("/")) {
                var alt = alt0.trim()
                if (alt.contains("@")) alt = alt.substringBefore("@")
                if (!alt.contains(":")) continue
                val idx = alt.lastIndexOf(':')
                if (idx <= 0) continue
                val host = alt.substring(0, idx)
                val port = alt.substring(idx + 1)
                if (port.all { it.isDigit() } && port.isNotEmpty() && host.isNotEmpty()) {
                    out.add(Pair(host, port.toInt()))
                }
            }
        }
        return out
    }

    /**
     * Round-trip time of an echoed datagram, or null if not echoed
     * (chain3 `_echo_rtt`: IPv4, 1.0 s timeout, 2 tries, min of echoes,
     * payload must come back byte-identical).
     */
    private fun echoRttMs(host: String, port: Int): Int? = runCatching {
        val ip = InetAddress.getByName(host)
        DatagramSocket().use { s ->
            s.soTimeout = ECHO_TIMEOUT_MS
            var best: Int? = null
            repeat(ECHO_TRIES) {
                try {
                    val t0 = System.nanoTime()
                    s.send(DatagramPacket(PING, PING.size, ip, port))
                    val buf = ByteArray(2048)
                    val p = DatagramPacket(buf, buf.size)
                    s.receive(p)
                    if (!p.data.copyOfRange(0, p.length).contentEquals(PING)) return@repeat
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                    best = if (best == null) ms else minOf(best!!, ms)
                } catch (_: Throwable) {
                    // timeout / ICMP noise: try once more, then give up
                }
            }
            best
        }
    }.getOrNull()

    /**
     * Measure every region and SET the results. [gate] sends the SET;
     * [label] names the moment (login / room). If the endpoint rows were
     * never fetched (login step failed), fetch them here first.
     */
    fun measureAndReport(gate: KgsGate, label: String): Report {
        if (rows.isEmpty() && !fetchRows(gate)) {
            return Report(null, 0, 0, true, linkedMapOf("result" to "_NO_ENDPOINTS"))
        }
        // The capture skips "@nogs" regions entirely and regions with no
        // measurable endpoint (chain3 quality_report row filter).
        val work = rows.filter { !it.address.contains("@nogs") && candidates(it.address).isNotEmpty() }
        if (work.isEmpty()) {
            return Report(null, 0, rows.size, true, linkedMapOf("result" to "_NO_ENDPOINTS"))
        }

        val pool = Executors.newFixedThreadPool(WORKERS)
        val measured = java.util.Collections.synchronizedList(ArrayList<Pair<String, Int>>())
        try {
            val futures = work.map { row ->
                pool.submit(java.util.concurrent.Callable {
                    var best: Int? = null
                    for ((host, port) in candidates(row.address)) {
                        val ms = echoRttMs(host, port) ?: continue
                        best = if (best == null) ms else minOf(best!!, ms)
                    }
                    if (best != null) measured.add(Pair(row.region, best!!))
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdown()
            runCatching { pool.awaitTermination(30, TimeUnit.SECONDS) }
        }

        // Capture/VM form: report the measured regions, quality = echo RTT ms.
        val now = System.currentTimeMillis() / 1000L
        val ql = measured.sortedBy { it.second }.map { (region, ms) ->
            linkedMapOf<String, Any?>("region" to region, "quality" to ms, "last_update" to now)
        } as List<Map<String, Any?>>
        log("$label: measured ${measured.size}/${work.size} regions" +
            (measured.sortedBy { it.second }.take(3)
                .joinToString(", ", "  best: ") { (rg, ms) -> "$rg=${ms}ms" }))
        val reply = gate.setRelayQuality(ql, now)
        log("$label: SET -> ${reply["result"]}")
        return Report(reply["result"]?.toString(), measured.size, work.size, false, reply)
    }

    /**
     * The login-time report (capture blocks 35 + 38-40): fetch the list,
     * measure, SET. Non-fatal -- the room-time report below is the one the
     * server binds to the room (run #32).
     */
    fun loginReport(gate: KgsGate): Report {
        rows = emptyList()
        return measureAndReport(gate, "login")
    }

    /** The room-time report (capture block 63): bare SET, rows from login. */
    fun roomReport(gate: KgsGate): Report = measureAndReport(gate, "room")
}
