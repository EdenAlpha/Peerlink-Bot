package com.peerlink.app.bot

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

internal data class FerryReceipt(val receiveCall: Long, val elapsed: Long, val wall: Long, val cpu: Long) {
    companion object {
        fun now(receiveCall: Long) = FerryReceipt(receiveCall, System.nanoTime(),
            System.currentTimeMillis(), android.os.Debug.threadCpuTimeNanos())
    }
}

/** A completed forward is queued as one item. Packet threads never wait on the file writer. */
internal class BotWireCapture(
    private val file: File,
    private val maxBytes: Long = 16L * 1024 * 1024,
    private val queueCapacity: Int = 1024,
    private val wallMs: () -> Long = System::currentTimeMillis,
    private val elapsedNs: () -> Long = System::nanoTime,
) : Closeable {
    private sealed interface Item {
        class Packet(val point: PacketFlowTrace.Point, val vpn: Boolean,
                     val wall: Long, val elapsed: Long, val bytes: ByteArray,
                     val receipt: FerryReceipt? = null, val sendStart: Long = 0,
                     val sent: Long = 0, val sentWall: Long = 0, val cpuDelta: Long = -1) : Item {
            val rows get() = if (sent == 0L) 1L else 2L
        }
        class Flush(val done: CountDownLatch) : Item
    }
    private val queue = ConcurrentLinkedQueue<Item>()
    private val slots = Semaphore(queueCapacity)
    private val bytesAvailable = Semaphore(4 * 1024 * 1024)
    private val available = Semaphore(0)
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
        val header = "# bot_transport v2; payload_hex is the unchanged UDP payload at the named ferry boundary\n" +
            "# captureStartedAtMs=${wallMs()}\n" +
            "# route: host=direct socket, vpn=fabricated-address carrier; TX means local socket acceptance\n" +
            "# RX is the return from receive; TX is the return from send. Both rows of a forward share rx_ns.\n" +
            "ts_ms,elapsed_ns,point,route,payload_len,payload_hex,receive_call_ns,rx_ns,send_start_ns,ferry_cpu_ns\n"
        writer.write(header)
        writer.flush()
        bytesWritten = header.length.toLong()
        thread = Thread(::writeLoop, "bot-wire-capture").apply { isDaemon = true; start() }
    }

    fun record(point: PacketFlowTrace.Point, vpn: Boolean, payload: ByteArray) {
        if (!accepting) return
        enqueue(Item.Packet(point, vpn, wallMs(), elapsedNs(), payload.copyOf()))
    }

    // PeerSlot owns these payloads and never mutates them after handing them over.
    fun recordTransfer(point: PacketFlowTrace.Point, vpn: Boolean, bytes: ByteArray, receipt: FerryReceipt,
                       sendStart: Long, sent: Long, sentWall: Long, cpuDelta: Long) {
        enqueue(Item.Packet(point, vpn, receipt.wall, receipt.elapsed, bytes, receipt, sendStart, sent, sentWall, cpuDelta))
    }

    private fun enqueue(packet: Item.Packet) {
        if (!accepting) return
        if (capped || packet.bytes.size !in 1..65535 || !slots.tryAcquire()) {
            dropped.addAndGet(packet.rows)
            return
        }
        if (!bytesAvailable.tryAcquire(packet.bytes.size)) {
            slots.release()
            dropped.addAndGet(packet.rows)
            if (!accepting) available.release()
            return
        }
        if (!accepting) {
            bytesAvailable.release(packet.bytes.size)
            slots.release()
            available.release()
            return
        }
        queue.add(packet)
        available.release()
    }

    fun stats(): String = "packets=${written.get()} queueOrCapDrops=${dropped.get()} " +
        "writeErrors=${errors.get()} capped=$capped bytes=$bytesWritten"

    /** Called on the export worker. A stable copy prevents a live capture
     * changing while ZIP assembly is reading it. */
    fun snapshot(destination: File): File {
        val done = CountDownLatch(1)
        val live = accepting
        if (live) {
            queue.add(Item.Flush(done))
            available.release()
        }
        val flushed = live && done.await(2, TimeUnit.SECONDS)
        synchronized(io) {
            if (accepting) writer.flush()
            file.copyTo(destination, overwrite = true)
            destination.appendText("# snapshot ${stats()} flushCompleted=$flushed\n")
        }
        return destination
    }

    override fun close() {
        accepting = false
        available.release()
        thread.join(2000)
    }

    private fun writeLoop() {
        val line = StringBuilder(512)
        var characters = CharArray(512)
        try {
            while (accepting || slots.availablePermits() < queueCapacity || queue.isNotEmpty()) {
                available.acquire()
                when (val item = queue.poll()) {
                    is Item.Flush -> {
                        synchronized(io) { writer.flush() }
                        item.done.countDown()
                    }
                    is Item.Packet -> {
                        bytesAvailable.release(item.bytes.size)
                        slots.release()
                        line.setLength(0)
                        appendRow(line, item, item.point, item.wall, item.elapsed, false)
                        if (item.sent != 0L) {
                            val tx = if (item.point == PacketFlowTrace.Point.GAME_RX)
                                PacketFlowTrace.Point.ENGINE_TX else PacketFlowTrace.Point.GAME_TX
                            appendRow(line, item, tx, item.sentWall, item.sent, true)
                        }
                        if (capped || bytesWritten + line.length + FINAL_STATUS_RESERVE > maxBytes) {
                            capped = true
                            dropped.addAndGet(item.rows)
                        } else synchronized(io) {
                            if (characters.size < line.length) characters = CharArray(line.capacity())
                            line.toCharArray(characters, 0, 0, line.length)
                            writer.write(characters, 0, line.length)
                            bytesWritten += line.length
                            if ((written.getAndAdd(item.rows) % 64L) + item.rows >= 64L) writer.flush()
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
                    is Item.Packet -> {
                        bytesAvailable.release(item.bytes.size)
                        slots.release()
                        dropped.addAndGet(item.rows)
                    }
                }
            }
            synchronized(io) {
                runCatching {
                    writer.use {
                        val trailer = "# final ${stats()}\n"
                        if (bytesWritten + trailer.length <= maxBytes) it.write(trailer)
                    }
                }.onFailure { errors.incrementAndGet() }
            }
        }
    }

    private fun appendRow(line: StringBuilder, item: Item.Packet, point: PacketFlowTrace.Point,
                          wall: Long, elapsed: Long, tx: Boolean) = with(line) {
        append(wall).append(',').append(elapsed).append(',').append(point.name).append(',')
        append(if (item.vpn) "vpn" else "host").append(',').append(item.bytes.size).append(',')
        for (byte in item.bytes) {
            val v = byte.toInt() and 255
            append(HEX[v ushr 4]).append(HEX[v and 15])
        }
        append(',')
        item.receipt?.let { append(it.receiveCall) }
        append(',')
        item.receipt?.let { append(it.elapsed) }
        append(',')
        if (tx) append(item.sendStart)
        append(',')
        if (tx && item.cpuDelta >= 0) append(item.cpuDelta)
        append('\n')
    }

    private companion object {
        const val HEX = "0123456789abcdef"
        const val FINAL_STATUS_RESERVE = 160
    }
}

/** Main-process lifecycle; failure to record must never stop forwarding. */
internal object BotWireRecorder {
    @Volatile private var capture: BotWireCapture? = null
    @Synchronized fun start(context: android.content.Context) {
        if (capture != null) return
        val file = File(context.filesDir, "bot_transport.csv")
        capture = runCatching {
            archive(file, File(context.filesDir, "bot_transport_previous.csv"))
            BotWireCapture(file)
        }.onFailure {
            com.peerlink.app.core.AppState.appendLog("[BOT-WIRE] capture unavailable=${it.javaClass.simpleName}")
        }.getOrNull()
    }
    internal fun archive(current: File, previous: File) {
        if (!current.isFile) return
        Files.move(current.toPath(), previous.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    }
    fun recordTransfer(point: PacketFlowTrace.Point, vpn: Boolean, payload: ByteArray, receipt: FerryReceipt,
                       sendStart: Long = 0, sent: Long = 0, sentWall: Long = 0, cpuDelta: Long = -1) {
        capture?.recordTransfer(point, vpn, payload, receipt, sendStart, sent, sentWall, cpuDelta)
    }
    @Synchronized fun stop() { capture?.close(); capture = null }
    @Synchronized fun snapshots(context: android.content.Context): List<File> {
        val live = capture
        val current = File(context.cacheDir, "bot_transport.csv")
        return listOfNotNull(
            if (live != null) live.snapshot(current) else copyTrace(context, current),
            copyTrace(context, File(context.cacheDir, "bot_transport_previous.csv")),
        )
    }
    private fun copyTrace(context: android.content.Context, destination: File): File? {
        return File(context.filesDir, destination.name).takeIf { it.isFile }?.copyTo(destination, overwrite = true)
    }
}
