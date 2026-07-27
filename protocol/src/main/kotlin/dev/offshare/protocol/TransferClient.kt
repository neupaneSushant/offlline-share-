package dev.offshare.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.CRC32
import kotlin.math.min

/** One contiguous slice of one file, handed to whichever stream is free. */
internal data class WorkUnit(
    val fileId: Int,
    val offset: Long,
    val length: Int,
)

/**
 * The sending end.
 *
 * Opens one control connection to negotiate, then N payload connections that
 * pull from a shared work queue. Work is claimed rather than pre-assigned, so
 * a stream that lands on a slow moment does not hold up the others -- with
 * static partitioning, one stalled stream leaves the transfer waiting on its
 * share while the rest sit idle.
 */
class TransferClient(
    private val identity: DeviceIdentity,
    private val config: TransferConfig = TransferConfig(),
    private val listener: TransferListener = TransferListener.NONE,
) {

    private val cancelled = AtomicBoolean(false)
    private val openChannels = ConcurrentLinkedQueue<SocketChannel>()

    /** Aborts an in-flight transfer. Safe to call from any thread. */
    fun cancel() {
        cancelled.set(true)
        while (true) {
            val channel = openChannels.poll() ?: break
            runCatching { channel.close() }
        }
    }

    /**
     * Runs a complete transfer and returns once the receiver has confirmed.
     *
     * Blocking by design: callers on Android should wrap this in a coroutine
     * on [kotlinx.coroutines.Dispatchers.IO] inside a foreground service.
     */
    fun send(target: InetSocketAddress, sources: List<FileSource>): TransferSummary {
        val startedAt = System.currentTimeMillis()
        val meter = RateMeter()
        var peer: DeviceIdentity? = null
        var ticker: Thread? = null

        try {
            SocketChannel.open().use { control ->
                openChannels.add(control)
                Tuning.prepareConnection(control, config, bulk = false)
                control.socket().connect(target, config.handshakeTimeoutMillis)
                Tuning.applyTimeout(control.socket(), config.handshakeTimeoutMillis)

                val input = BufferedInputStream(control.socket().getInputStream(), IO_BUFFER)
                val output = BufferedOutputStream(control.socket().getOutputStream(), IO_BUFFER)

                Wire.writePreamble(output, Protocol.Role.CONTROL)
                Wire.writeMessage(output, Message.Hello(Protocol.VERSION, identity))

                val ack = when (val response = Wire.readMessage(input)) {
                    is Message.HelloAck -> response
                    is Message.Error -> throw TransferException(response.reason, response.detail)
                    else -> throw TransferException(FailureReason.NETWORK, "Expected HELLO_ACK")
                }
                peer = ack.identity

                val byId = sources.associateBy { it.meta.id }
                require(byId.size == sources.size) { "Duplicate file ids in offer" }
                Wire.writeMessage(output, Message.Offer(sources.map { it.meta }))

                val accept = when (val response = Wire.readMessage(input)) {
                    is Message.Accept -> response
                    is Message.Error -> throw TransferException(response.reason, response.detail)
                    else -> throw TransferException(FailureReason.NETWORK, "Expected ACCEPT")
                }

                val accepted = accept.decisions.filter { it.accepted && byId.containsKey(it.fileId) }
                if (accepted.isEmpty()) {
                    return TransferSummary(
                        peer = peer,
                        files = emptyList(),
                        bytesTransferred = 0,
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                        failure = FailureReason.REJECTED,
                    )
                }

                val units = buildWorkUnits(accepted, byId)
                val totalBytes = units.sumOf { it.length.toLong() }
                listener.onSessionStarted(ack.identity, accepted.mapNotNull { byId[it.fileId]?.meta })

                if (totalBytes > 0) {
                    ticker = startProgressTicker(meter, totalBytes)
                    // The receiver derives the same stream count from the same
                    // decisions, so it knows how many connections to expect.
                    pumpStreams(
                        target = target,
                        token = ack.sessionToken,
                        streamCount = ack.streamCount,
                        verifyIntegrity = ack.verifyIntegrity,
                        units = ConcurrentLinkedQueue(units),
                        sources = byId,
                        meter = meter,
                    )
                }
                ticker?.interrupt()
                listener.onProgress(meter.total(), totalBytes, meter.sample())

                Tuning.applyTimeout(control.socket(), config.transferTimeoutMillis)
                Wire.writeMessage(output, Message.Done)

                val result = when (val response = Wire.readMessage(input)) {
                    is Message.Result -> response
                    is Message.Error -> throw TransferException(response.reason, response.detail)
                    else -> throw TransferException(FailureReason.NETWORK, "Expected RESULT")
                }

                val summary = TransferSummary(
                    peer = peer,
                    files = result.files,
                    bytesTransferred = meter.total(),
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    failure = if (result.files.all { it.ok }) null else FailureReason.STORAGE,
                )
                listener.onSessionFinished(summary)
                return summary
            }
        } catch (e: Exception) {
            ticker?.interrupt()
            val reason = when {
                cancelled.get() -> FailureReason.CANCELLED
                e is TransferException -> e.reason
                else -> FailureReason.NETWORK
            }
            val error = e as? TransferException
                ?: TransferException(reason, e.message ?: "Transfer failed", e)
            listener.onError(error)

            val summary = TransferSummary(
                peer = peer,
                files = emptyList(),
                bytesTransferred = meter.total(),
                elapsedMillis = System.currentTimeMillis() - startedAt,
                failure = reason,
            )
            listener.onSessionFinished(summary)
            return summary
        } finally {
            ticker?.interrupt()
            openChannels.clear()
        }
    }

    /**
     * Slices accepted files into work units.
     *
     * Largest file first. With a mixed set, finishing the big file last means
     * every other stream is idle while one grinds through the tail; front
     * loading it keeps all streams busy right to the end.
     */
    private fun buildWorkUnits(
        decisions: List<FileDecision>,
        sources: Map<Int, FileSource>,
    ): List<WorkUnit> {
        val units = mutableListOf<WorkUnit>()
        decisions
            .mapNotNull { decision -> sources[decision.fileId]?.let { decision to it.meta } }
            .sortedByDescending { (_, meta) -> meta.size }
            .forEach { (decision, meta) ->
                var offset = decision.resumeFrom.coerceIn(0L, meta.size)
                while (offset < meta.size) {
                    val length = min(config.unitSize.toLong(), meta.size - offset).toInt()
                    units += WorkUnit(meta.id, offset, length)
                    offset += length
                }
            }
        return units
    }

    private fun pumpStreams(
        target: InetSocketAddress,
        token: ByteArray,
        streamCount: Int,
        verifyIntegrity: Boolean,
        units: ConcurrentLinkedQueue<WorkUnit>,
        sources: Map<Int, FileSource>,
        meter: RateMeter,
    ) {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (0 until streamCount).map { index ->
            Thread({
                try {
                    runStream(target, token, verifyIntegrity, units, sources, meter)
                } catch (e: Throwable) {
                    errors.add(e)
                    // One dead stream dooms the transfer; drain the queue so
                    // the others stop rather than pushing bytes at a receiver
                    // that will never see a complete file.
                    units.clear()
                }
            }, "ofsh-send-$index")
        }
        threads.forEach { it.isDaemon = true; it.start() }
        threads.forEach { it.join() }

        errors.peek()?.let { first ->
            throw first as? TransferException
                ?: TransferException(FailureReason.NETWORK, "Data stream failed", first)
        }
    }

    /** One payload connection: claim a unit, send it, repeat until drained. */
    private fun runStream(
        target: InetSocketAddress,
        token: ByteArray,
        verifyIntegrity: Boolean,
        units: ConcurrentLinkedQueue<WorkUnit>,
        sources: Map<Int, FileSource>,
        meter: RateMeter,
    ) {
        SocketChannel.open().use { channel ->
            openChannels.add(channel)
            Tuning.prepareConnection(channel, config, bulk = true)
            channel.socket().connect(target, config.handshakeTimeoutMillis)
            Tuning.applyTimeout(channel.socket(), config.transferTimeoutMillis)

            // Straight to the channel, mirroring the receiver: no stream
            // buffer sits between the preamble and the payload that follows.
            Wire.writePreamble(channel, Protocol.Role.DATA)
            channel.writeFully(ByteBuffer.wrap(token))

            val header = ByteBuffer.allocate(UNIT_HEADER_BYTES)
            val payload =
                if (verifyIntegrity) ByteBuffer.allocateDirect(PAYLOAD_BUFFER) else null
            val crc = if (verifyIntegrity) CRC32() else null

            OpenFile().use { current ->
                while (!cancelled.get()) {
                    val unit = units.poll() ?: break
                    val source = sources[unit.fileId]
                        ?: throw TransferException(
                            FailureReason.STORAGE,
                            "No source for file id ${unit.fileId}",
                        )

                    val fileChannel = current.channelFor(source)
                    writeUnitHeader(channel, header, unit, verifyIntegrity)

                    if (verifyIntegrity) {
                        crc!!.reset()
                        sendBuffered(channel, fileChannel, payload!!, unit, crc)
                        writeCrc(channel, header, crc)
                    } else {
                        sendZeroCopy(channel, fileChannel, unit)
                    }
                    meter.add(unit.length.toLong())
                }
            }

            if (cancelled.get()) throw TransferException(FailureReason.CANCELLED, "Cancelled")

            // Explicit end marker, so the receiver can tell an orderly finish
            // from a hotspot that dropped out mid-unit.
            header.clear()
            header.putInt(0).putLong(0).putInt(0).putInt(UnitFlags.END_OF_STREAM)
            header.flip()
            channel.writeFully(header)
            openChannels.remove(channel)
        }
    }

    private fun writeUnitHeader(
        channel: SocketChannel,
        header: ByteBuffer,
        unit: WorkUnit,
        verifyIntegrity: Boolean,
    ) {
        header.clear()
        header.putInt(unit.fileId)
        header.putLong(unit.offset)
        header.putInt(unit.length)
        header.putInt(if (verifyIntegrity) UnitFlags.HAS_CRC else 0)
        header.flip()
        channel.writeFully(header)
    }

    private fun writeCrc(channel: SocketChannel, scratch: ByteBuffer, crc: CRC32) {
        scratch.clear()
        scratch.limit(Int.SIZE_BYTES)
        scratch.putInt(crc.value.toInt())
        scratch.flip()
        channel.writeFully(scratch)
    }

    /**
     * The fast path: [FileChannel.transferTo] straight into the socket.
     *
     * On Linux this becomes a sendfile syscall, so payload never crosses into
     * user space. It is the reason a mid-range phone can keep a 5 GHz link
     * saturated without pinning a core.
     */
    private fun sendZeroCopy(channel: SocketChannel, file: FileChannel, unit: WorkUnit) {
        var position = unit.offset
        var remaining = unit.length.toLong()
        var idleRounds = 0
        while (remaining > 0) {
            val sent = file.transferTo(position, remaining, channel)
            if (sent <= 0) {
                // transferTo is allowed to return 0 on a socket whose send
                // buffer is momentarily full. Give up only if it never moves.
                if (++idleRounds > MAX_IDLE_ROUNDS) {
                    throw TransferException(
                        FailureReason.NETWORK,
                        "Socket stopped accepting data with $remaining bytes left",
                    )
                }
                Thread.sleep(IDLE_BACKOFF_MILLIS)
                continue
            }
            idleRounds = 0
            position += sent
            remaining -= sent
        }
    }

    /** The verified path: read into a direct buffer, checksum, then write. */
    private fun sendBuffered(
        channel: SocketChannel,
        file: FileChannel,
        payload: ByteBuffer,
        unit: WorkUnit,
        crc: CRC32,
    ) {
        var position = unit.offset
        var remaining = unit.length
        while (remaining > 0) {
            payload.clear()
            payload.limit(min(payload.capacity(), remaining))
            val read = file.read(payload, position)
            if (read <= 0) {
                throw TransferException(
                    FailureReason.STORAGE,
                    "Source file ended $remaining bytes early",
                )
            }
            payload.flip()
            crc.update(payload.duplicate())
            channel.writeFully(payload)
            position += read
            remaining -= read
        }
    }

    private fun startProgressTicker(meter: RateMeter, totalBytes: Long): Thread =
        Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(PROGRESS_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                listener.onProgress(meter.total(), totalBytes, meter.sample())
            }
        }.apply {
            isDaemon = true
            name = "ofsh-send-progress"
            start()
        }

    /**
     * Holds the one file a stream is currently reading.
     *
     * Units are drained roughly in file order, so a single-slot cache hits
     * almost every time. Caching every file instead would mean up to
     * files x streams open descriptors, which a large folder send blows
     * straight through.
     */
    private class OpenFile : Closeable {
        private var fileId: Int = -1
        private var channel: FileChannel? = null

        fun channelFor(source: FileSource): FileChannel {
            val existing = channel
            if (existing != null && fileId == source.meta.id && existing.isOpen) return existing
            runCatching { existing?.close() }
            return try {
                source.openChannel().also {
                    channel = it
                    fileId = source.meta.id
                }
            } catch (e: Exception) {
                throw TransferException(
                    FailureReason.STORAGE,
                    "Could not read ${source.meta.name}",
                    e,
                )
            }
        }

        override fun close() {
            runCatching { channel?.close() }
            channel = null
        }
    }

    private companion object {
        const val IO_BUFFER = 64 * 1024
        const val PAYLOAD_BUFFER = 256 * 1024
        const val PROGRESS_INTERVAL_MILLIS = 200L
        const val MAX_IDLE_ROUNDS = 600
        const val IDLE_BACKOFF_MILLIS = 5L
    }
}

/** Writes a buffer in full, looping over short writes. */
internal fun SocketChannel.writeFully(buffer: ByteBuffer) {
    while (buffer.hasRemaining()) {
        if (write(buffer) < 0) {
            throw TransferException(FailureReason.NETWORK, "Socket closed during write")
        }
    }
}
