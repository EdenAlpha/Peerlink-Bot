package com.peerlink.app.bot

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** UDP send success means the local socket accepted bytes, not that the
 * destination game consumed them. Payloads are never inspected or retained. */
internal class PacketFlowTrace(private val now: () -> Long) {
    enum class Point { GAME_RX, ENGINE_TX, ENGINE_RX, GAME_TX }
    private class Counter {
        val packets = AtomicLong()
        val bytes = AtomicLong()
        val lastAt = AtomicLong(-1)
    }
    private val counters = Array(2) { Array(Point.entries.size) { Counter() } }
    private val drops = ConcurrentHashMap<String, AtomicLong>()

    fun record(point: Point, reflexive: Boolean, bytes: Int) {
        val counter = counters[if (reflexive) 1 else 0][point.ordinal]
        counter.bytes.addAndGet(bytes.toLong())
        counter.lastAt.set(now())
        counter.packets.incrementAndGet()
    }

    fun drop(reason: String): Long = drops.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()

    fun summary(): String {
        val time = now()
        val routes = counters.mapIndexed { route, counts ->
            "${if (route == 0) "host" else "vpn"}={" + Point.entries.joinToString(" ") { point ->
                val counter = counts[point.ordinal]
                val last = counter.lastAt.get()
                "${point.name.lowercase()}=${counter.packets.get()}/${counter.bytes.get()}B/ageMs:${if (last < 0) -1 else time - last}"
            } + "}"
        }.joinToString(" ")
        return "observedAtMs=$time $routes drops={" +
            drops.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value.get()}" } +
            "} txMeans=accepted_by_local_socket"
    }
}
