package com.peerlink.app.bot

import java.io.Closeable
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Full UDP payloads at each ferry boundary. Packet threads only offer a copy
 * to a bounded queue; file I/O and hex formatting belong to the writer. */
internal class BotWireCapture(
    private val file: File,
    private val maxBytes: Long = 16L * 1024 * 1024,
    queueCapacity: Int = 1024,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val elapsedNs: () -> Long = System::nanoTime,
) : Closeable {
    private sealed interface Item {
        class Packet(val point: PacketFlowTrace.Point, val vpn: Boolean,
                     val wall: Long, val elapsed: Long, val bytes: ByteArray) : Item
        class Flush(val done: CountDownLatch) : Item
    }
    private val queue = ArrayBlockingQueue<Item>(queueCapacity)
    private val io = Any()
    private val writer = file.bufferedWriter()
    private val written = AtomicLong()
    private val dropped = AtomicLong()
    private val errors = AtomicLong()
    @Volatile private var accepting = true
    @Volatile private var capped = false
    @Volatile private var bytesWritten = 0L
    private val thread: Thread

    init {
        val header = "# bot_transport v1; payload_hex is the unchanged UDP payload at the named ferry boundary\n" +
            "# route: host=direct socket, vpn=fabricated-address carrier; TX means local socket acceptance\n" +
            "ts_ms,elapsed_ns,point,route,payload_len,payload_hex\n"
        writer.write(header)
        writer.flush()
        bytesWritten = header.length.toLong()
        thread = Thread(::writeLoop, "bot-wire-capture").apply { isDaemon = true; start() }
    }

    fun record(point: PacketFlowTrace.Point, vpn: Boolean, payload: ByteArray) {
        if (!accepting) return
        if (capped || payload.size > 4096 || payload.isEmpty()) {
            dropped.incrementAndGet()
            return
        }
        val item = Item.Packet(point, vpn, wallMs(), elapsedNs(), payload.copyOf())
        if (!queue.offer(item)) dropped.incrementAndGet()
    }

    fun stats(): String = "packets=${written.get()} queueOrCapDrops=${dropped.get()} " +
        "writeErrors=${errors.get()} capped=$capped bytes=$bytesWritten"

    /** Called on the export worker. A stable copy prevents a live capture
     * changing while ZIP assembly is reading it. */
    fun snapshot(destination: File): File {
        val done = CountDownLatch(1)
        val flushed = accepting && queue.offer(Item.Flush(done), 2, TimeUnit.SECONDS) &&
            done.await(2, TimeUnit.SECONDS)
        synchronized(io) {
            if (accepting) writer.flush()
            file.copyTo(destination, overwrite = true)
            destination.appendText("# snapshot ${stats()} flushCompleted=$flushed\n")
        }
        return destination
    }

    override fun close() {
        accepting = false
        thread.join(2000)
    }

    private fun writeLoop() {
        try {
            while (accepting || queue.isNotEmpty()) {
                when (val item = queue.poll(100, TimeUnit.MILLISECONDS)) {
                    is Item.Flush -> {
                        synchronized(io) { writer.flush() }
                        item.done.countDown()
                    }
                    is Item.Packet -> {
                        val line = buildString(item.bytes.size * 2 + 100) {
                            append(item.wall).append(',').append(item.elapsed).append(',')
                            append(item.point.name).append(',').append(if (item.vpn) "vpn" else "host")
                            append(',').append(item.bytes.size).append(',')
                            for (byte in item.bytes) {
                                val v = byte.toInt() and 255
                                append(HEX[v ushr 4]).append(HEX[v and 15])
                            }
                            append('\n')
                        }
                        if (capped || bytesWritten + line.length > maxBytes) {
                            capped = true
                            dropped.incrementAndGet()
                        } else synchronized(io) {
                            writer.write(line)
                            bytesWritten += line.length
                            if (written.incrementAndGet() % 64L == 0L) writer.flush()
                        }
                    }
                    null -> Unit
                }
            }
        } catch (_: Exception) {
            errors.incrementAndGet()
            accepting = false
        } finally {
            while (true) {
                when (val item = queue.poll() ?: break) {
                    is Item.Flush -> item.done.countDown()
                    is Item.Packet -> dropped.incrementAndGet()
                }
            }
            synchronized(io) { runCatching { writer.close() }.onFailure { errors.incrementAndGet() } }
        }
    }

    private companion object { const val HEX = "0123456789abcdef" }
}

/** Main-process lifecycle; failure to record must never stop forwarding. */
internal object BotWireRecorder {
    @Volatile private var capture: BotWireCapture? = null
    @Synchronized fun start(context: android.content.Context) {
        if (capture != null) return
        val file = File(context.filesDir, "bot_transport.csv")
        capture = runCatching { BotWireCapture(file) }.onFailure {
            com.peerlink.app.core.AppState.appendLog("[BOT-WIRE] capture unavailable=${it.javaClass.simpleName}")
        }.getOrNull()
    }
    fun record(point: PacketFlowTrace.Point, vpn: Boolean, payload: ByteArray) {
        capture?.record(point, vpn, payload)
    }
    @Synchronized fun stop() { capture?.close(); capture = null }
    fun snapshot(context: android.content.Context): File? {
        val live = capture
        val out = File(context.cacheDir, "bot_transport_export.csv")
        return if (live != null) live.snapshot(out) else {
            File(context.filesDir, "bot_transport.csv").takeIf { it.isFile }?.copyTo(out, overwrite = true)
        }
    }
}
