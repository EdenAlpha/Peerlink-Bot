package com.peerlink.app.tunnel

import java.io.Closeable
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector

/** A producer may register a socket while the single reader is asleep in select. */
internal class ReadinessSelector : Closeable {
    private val selector = Selector.open()
    private val registration = Any()

    fun register(channel: SelectableChannel, ops: Int, attachment: Any): SelectionKey =
        synchronized(registration) {
            selector.wakeup()
            channel.register(selector, ops, attachment)
        }

    fun select(): Int {
        // A reader woken during register must let that registration finish before
        // entering select again. Older Android selectors lock during registration.
        synchronized(registration) { }
        return selector.select()
    }

    fun selectedKeys(): MutableSet<SelectionKey> = selector.selectedKeys()
    fun wakeup() { selector.wakeup() }
    override fun close() = selector.close()
}
