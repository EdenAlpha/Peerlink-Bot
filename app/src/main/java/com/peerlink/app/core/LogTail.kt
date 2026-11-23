package com.peerlink.app.core

import java.util.ArrayDeque

/** Last [capacity] characters, with copying deferred until a reader requests the text. */
internal class LogTail(private val capacity: Int) {
    private val chunks = ArrayDeque<String>()
    private var firstOffset = 0
    private var size = 0

    init { require(capacity > 0) }

    fun append(line: String) {
        if (line.isEmpty()) return
        if (line.length >= capacity) {
            clear()
            chunks.addLast(line.takeLast(capacity))
            size = capacity
            return
        }
        var excess = size - (capacity - line.length)
        while (excess > 0) {
            val available = chunks.first.length - firstOffset
            val removed = minOf(excess, available)
            size -= removed
            excess -= removed
            firstOffset += removed
            if (removed == available) {
                chunks.removeFirst()
                firstOffset = 0
            }
        }
        chunks.addLast(line)
        size += line.length
    }

    fun clear() {
        chunks.clear()
        size = 0
        firstOffset = 0
    }

    override fun toString(): String = buildString(size) {
        var offset = firstOffset
        for (chunk in chunks) {
            append(chunk, offset, chunk.length)
            offset = 0
        }
    }
}
