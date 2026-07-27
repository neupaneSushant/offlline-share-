package dev.offshare.protocol

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.min

/**
 * The receiving end. Listens for control connections, approves offers, and
 * fans incoming payload streams into files.
 *
 * Typically runs on whichever device created the hotspot, but nothing here
 * requires that -- the hotspot host and the file receiver are independent
 * roles, so a phone can host the network and still be the one sending.
 */
class TransferServer(
    private val identity: DeviceIdentity,
    private val sinkFactory: FileSinkFactory,
    private val approver: OfferApprover,
    private val config: TransferConfig = TransferConfig(),
    private val listener: TransferListener = TransferListener.NONE,
) : Closeable {

    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "ofsh-server").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<String, Session>()
    private val running = AtomicBoolean(false)
    private var serverChannel: ServerSocketChannel? = null

    /** The port actually bound, valid after [start]. */
    @Volatile
    var boundPort: Int = 0
        private set

    /**
     * Binds and begins accepting.
     *
     * @param port 0 picks an ephemeral port, which the tests rely on.
     * @param bindAddress null binds all interfaces. On Android, prefer binding
     *   to the hotspot interface address so the listener is not also exposed
     *   on any other network the device happens to be on.
     */
    fun start(port: Int = Protocol.DEFAULT_PORT, bindAddress: String? = null): Int {
        check(running.compareAndSet(false, true)) { "Server already started" }

        val channel = ServerSocketChannel.open()
        Tuning.prepareListener(channel, config)
        val address =
            if (bindAddress == null) InetSocketAddress(port)
            else InetSocketAddress(bindAddress, port)
        channel.bind(address, BACKLOG)
        serverChannel = channel
        boundPort = (channel.localAddress as InetSocketAddress).port

        executor.execute { acceptLoop(channel) }
        return boundPort
    }

    private fun acceptLoop(channel: ServerSocketChannel) {
        while (running.get()) {
            val client = try {
                channel.accept()
            } catch (_: ClosedChannelException) {
                return
            } catch (e: IOException) {
                if (running.get()) {
                    listener.onError(
                        TransferException(FailureReason.NETWORK, "Accept failed", e),
                    )
                }
                return
            }
            executor.execute { dispatch(client) }
        }
    }

    /**
     * Reads the preamble and routes the connection to the right handler.
     *
     * Both control and data connections land on the same port. That is not
     * just tidiness: a single listening port is one firewall hole, one
     * discovery record, and one thing that can be wrong.
     */
    private fun dispatch(channel: SocketChannel) {
        try {
            Tuning.prepareConnection(channel, config, bulk = true)
            Tuning.applyTimeout(channel.socket(), config.handshakeTimeoutMillis)

            // Read the preamble off the raw channel. Buffering here would pull
            // payload bytes in behind it, where the channel reads that follow
            // can never see them.
            when (val role = Wire.readPreamble(channel)) {
                Protocol.Role.CONTROL -> handleControl(
                    channel,
                    BufferedInputStream(channel.socket().getInputStream(), IO_BUFFER),
                )

                Protocol.Role.DATA -> handleData(channel)
                else -> throw TransferException(FailureReason.NETWORK, "Unknown role $role")
            }
        } catch (e: TransferException) {
            listener.onError(e)
            runCatching { channel.close() }
        } catch (e: Exception) {
            listener.onError(TransferException(FailureReason.NETWORK, "Connection failed", e))
            runCatching { channel.close() }
        }
    }

    // -- control channel --------------------------------------------------

    private fun handleControl(channel: SocketChannel, input: InputStream) {
        val output = BufferedOutputStream(channel.socket().getOutputStream(), IO_BUFFER)
        val startedAt = System.currentTimeMillis()
        var session: Session? = null

        try {
            val hello = Wire.readMessage(input) as? Message.Hello
                ?: throw TransferException(FailureReason.NETWORK, "Expected HELLO")

            val token = ByteArray(Protocol.SESSION_TOKEN_BYTES).also(RANDOM::nextBytes)
            Wire.writeMessage(
                output,
                Message.HelloAck(
                    version = Protocol.VERSION,
                    identity = identity,
                    sessionToken = token,
                    streamCount = config.streamCount,
                    verifyIntegrity = config.verifyIntegrity,
                ),
            )

            val offer = Wire.readMessage(input) as? Message.Offer
                ?: throw TransferException(FailureReason.NETWORK, "Expected OFFER")

            val decisions = resolveDecisions(hello.identity, offer.files)
            val accepted = offer.files.associateBy { it.id }
                .filterKeys { id -> decisions.any { it.fileId == id && it.accepted } }

            if (accepted.isEmpty()) {
                Wire.writeMessage(output, Message.Accept(decisions))
                listener.onSessionFinished(
                    TransferSummary(
                        peer = hello.identity,
                        files = emptyList(),
                        bytesTransferred = 0,
                        elapsedMillis = System.currentTimeMillis() - startedAt,
                        failure = FailureReason.REJECTED,
                    ),
                )
                return
            }

            session = Session(
                token = token.toHex(),
                peer = hello.identity,
                files = accepted,
                decisions = decisions.associateBy { it.fileId },
                config = config,
                sinkFactory = sinkFactory,
                listener = listener,
            )
            sessions[session.token] = session
            session.openSinks()

            listener.onSessionStarted(hello.identity, accepted.values.toList())

            // Sinks are open before ACCEPT goes out, so the first data stream
            // cannot arrive before there is somewhere to put it.
            Wire.writeMessage(output, Message.Accept(decisions))

            session.startProgressTicker()

            // Bulk transfer can take minutes; the control socket is silent for
            // all of it, so the handshake timeout must not apply here.
            Tuning.applyTimeout(channel.socket(), config.transferTimeoutMillis)

            val done = Wire.readMessage(input)
            if (done !is Message.Done) {
                throw TransferException(FailureReason.NETWORK, "Expected DONE, got $done")
            }

            session.awaitStreams()
            val results = session.finish()

            Wire.writeMessage(output, Message.Result(results))
            listener.onSessionFinished(
                TransferSummary(
                    peer = hello.identity,
                    files = results,
                    bytesTransferred = session.bytesReceived(),
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    failure = if (results.all { it.ok }) null else FailureReason.STORAGE,
                ),
            )
        } catch (e: Exception) {
            val failure = (e as? TransferException)?.reason ?: FailureReason.NETWORK
            session?.abort()
            runCatching {
                Wire.writeMessage(
                    output,
                    Message.Error(failure, e.message ?: e::class.java.simpleName),
                )
            }
            listener.onSessionFinished(
                TransferSummary(
                    peer = session?.peer,
                    files = emptyList(),
                    bytesTransferred = session?.bytesReceived() ?: 0L,
                    elapsedMillis = System.currentTimeMillis() - startedAt,
                    failure = failure,
                ),
            )
            if (e !is TransferException) {
                listener.onError(TransferException(failure, "Session failed", e))
            } else {
                listener.onError(e)
            }
        } finally {
            session?.let { sessions.remove(it.token) }
            session?.close()
            runCatching { channel.close() }
        }
    }

    /**
     * Runs the approver, then fills in resume offsets.
     *
     * The approver answers the policy question -- do we want these files --
     * and the sink factory answers the mechanical one: how much of each is
     * already on disk from a run the hotspot dropped halfway through. An
     * approver that sets a non-zero offset itself is left alone.
     */
    private fun resolveDecisions(peer: DeviceIdentity, files: List<FileMeta>): List<FileDecision> {
        val approved = approver.approve(peer, files).associateBy { it.fileId }
        return files.map { meta ->
            val decision = approved[meta.id]
                ?: return@map FileDecision(meta.id, accepted = false)
            if (!decision.accepted || decision.resumeFrom > 0) return@map decision

            val existing = runCatching { sinkFactory.existingBytes(meta) }.getOrDefault(0L)
            decision.copy(resumeFrom = existing.coerceIn(0L, meta.size))
        }
    }

    // -- data channels ----------------------------------------------------

    private fun handleData(channel: SocketChannel) {
        val tokenBuffer = ByteBuffer.allocate(Protocol.SESSION_TOKEN_BYTES)
        if (!channel.readFully(tokenBuffer)) {
            throw TransferException(FailureReason.NETWORK, "Data stream closed before its token")
        }
        tokenBuffer.flip()
        val token = ByteArray(Protocol.SESSION_TOKEN_BYTES).also(tokenBuffer::get)

        val session = sessions[token.toHex()]
            ?: throw TransferException(
                FailureReason.UNAUTHORIZED,
                "Data stream presented an unknown session token",
            )

        Tuning.applyTimeout(channel.socket(), config.transferTimeoutMillis)
        try {
            session.receiveStream(channel)
        } finally {
            runCatching { channel.close() }
        }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverChannel?.close() }
        sessions.values.forEach { runCatching { it.abort() } }
        sessions.clear()
        executor.shutdownNow()
    }

    private companion object {
        const val BACKLOG = 32
        const val IO_BUFFER = 64 * 1024
        val RANDOM = SecureRandom()
    }
}

/**
 * Server-side state for one transfer.
 *
 * Data streams write into shared file channels at absolute offsets, so no
 * ordering guarantees are needed between them -- which is the whole reason
 * parallel streams are cheap here.
 */
private class Session(
    val token: String,
    val peer: DeviceIdentity,
    val files: Map<Int, FileMeta>,
    val decisions: Map<Int, FileDecision>,
    val config: TransferConfig,
    val sinkFactory: FileSinkFactory,
    val listener: TransferListener,
) : Closeable {

    private val sinks = ConcurrentHashMap<Int, FileSink>()
    private val perFileBytes = ConcurrentHashMap<Int, AtomicLong>()
    private val meter = RateMeter()
    private val streamsRemaining: CountDownLatch
    private val failure = AtomicBoolean(false)
    private var ticker: Thread? = null

    /** Bytes still owed by the sender, after resume offsets are applied. */
    private val bytesExpected: Long = files.values.sumOf { meta ->
        (meta.size - (decisions[meta.id]?.resumeFrom ?: 0L)).coerceAtLeast(0L)
    }

    init {
        // Both ends derive the stream count from the same decisions, so the
        // receiver knows exactly how many data connections to wait for
        // without another round trip.
        val expected = if (bytesExpected > 0) config.streamCount else 0
        streamsRemaining = CountDownLatch(expected)
    }

    fun openSinks() {
        files.values.forEach { meta ->
            val resumeFrom = decisions[meta.id]?.resumeFrom ?: 0L
            try {
                sinks[meta.id] = sinkFactory.open(meta, resumeFrom)
                perFileBytes[meta.id] = AtomicLong(resumeFrom)
            } catch (e: Exception) {
                throw TransferException(
                    FailureReason.STORAGE,
                    "Could not open destination for ${meta.name}",
                    e,
                )
            }
        }
    }

    fun startProgressTicker() {
        ticker = Thread {
            while (!Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(PROGRESS_INTERVAL_MILLIS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                listener.onProgress(bytesReceived(), totalBytes(), meter.sample())
            }
        }.apply {
            isDaemon = true
            name = "ofsh-progress"
            start()
        }
    }

    /** Reads work units off one data connection until the sender signals EOS. */
    fun receiveStream(channel: SocketChannel) {
        val headerBuffer = ByteBuffer.allocate(UNIT_HEADER_BYTES)
        val payload = ByteBuffer.allocateDirect(PAYLOAD_BUFFER)
        val crc = if (config.verifyIntegrity) CRC32() else null

        try {
            while (true) {
                headerBuffer.clear()
                if (!channel.readFully(headerBuffer)) break // peer closed cleanly
                headerBuffer.flip()

                val fileId = headerBuffer.int
                val offset = headerBuffer.long
                val length = headerBuffer.int
                val flags = headerBuffer.int

                if (flags and UnitFlags.END_OF_STREAM != 0) break

                val sink = sinks[fileId] ?: throw TransferException(
                    FailureReason.NETWORK,
                    "Sender referenced unknown file id $fileId",
                )
                if (length < 0) {
                    throw TransferException(FailureReason.NETWORK, "Negative unit length")
                }

                crc?.reset()
                receiveUnit(channel, sink, payload, offset, length, crc)

                if (crc != null) {
                    val expected = ByteBuffer.allocate(Int.SIZE_BYTES)
                    if (!channel.readFully(expected)) {
                        throw TransferException(FailureReason.NETWORK, "Truncated CRC trailer")
                    }
                    expected.flip()
                    val expectedValue = expected.int
                    if (expectedValue != crc.value.toInt()) {
                        throw TransferException(
                            FailureReason.INTEGRITY,
                            "Checksum mismatch in ${files[fileId]?.name} at offset $offset",
                        )
                    }
                }

                val received = perFileBytes.getValue(fileId).addAndGet(length.toLong())
                meter.add(length.toLong())
                files[fileId]?.let { meta ->
                    if (received >= meta.size) listener.onFileCompleted(meta)
                }
            }
        } catch (e: Exception) {
            failure.set(true)
            if (e is TransferException) throw e
            throw TransferException(FailureReason.NETWORK, "Data stream failed", e)
        } finally {
            streamsRemaining.countDown()
        }
    }

    /**
     * Moves one unit from socket to file.
     *
     * Uses a direct buffer rather than [java.nio.channels.FileChannel.transferFrom]:
     * the JDK has no sendfile equivalent for socket-to-file, so transferFrom
     * would do this same copy internally while hiding EOF from us and making
     * checksumming impossible without re-reading the file. A direct buffer
     * keeps payload off the JVM heap, which is what actually matters -- no
     * garbage, no heap-to-native copy on the write.
     */
    private fun receiveUnit(
        channel: SocketChannel,
        sink: FileSink,
        payload: ByteBuffer,
        offset: Long,
        length: Int,
        crc: CRC32?,
    ) {
        var position = offset
        var remaining = length
        while (remaining > 0) {
            payload.clear()
            payload.limit(min(payload.capacity(), remaining))
            val read = channel.read(payload)
            if (read < 0) {
                throw TransferException(
                    FailureReason.NETWORK,
                    "Peer closed with $remaining bytes outstanding",
                )
            }
            payload.flip()
            crc?.update(payload.duplicate())
            while (payload.hasRemaining()) {
                position += sink.channel.write(payload, position)
            }
            remaining -= read
        }
    }

    fun awaitStreams() {
        if (!streamsRemaining.await(config.transferTimeoutMillis.toLong(), TimeUnit.MILLISECONDS)) {
            throw TransferException(
                FailureReason.NETWORK,
                "Timed out waiting for data streams to finish",
            )
        }
    }

    /** Commits every sink and reports per-file outcomes. */
    fun finish(): List<FileResult> {
        ticker?.interrupt()
        listener.onProgress(bytesReceived(), totalBytes(), meter.sample())

        return files.values.map { meta ->
            val received = perFileBytes[meta.id]?.get() ?: 0L
            val sink = sinks[meta.id]
            when {
                sink == null ->
                    FileResult(meta.id, received, ok = false, error = "No destination was opened")

                received != meta.size -> {
                    sink.abort()
                    FileResult(
                        meta.id,
                        received,
                        ok = false,
                        error = "Expected ${meta.size} bytes, wrote $received",
                    )
                }

                else -> runCatching { sink.commit() }.fold(
                    onSuccess = { FileResult(meta.id, received, ok = true) },
                    onFailure = { error ->
                        FileResult(
                            meta.id,
                            received,
                            ok = false,
                            error = error.message ?: "Commit failed",
                        )
                    },
                )
            }
        }
    }

    fun abort() {
        ticker?.interrupt()
        sinks.values.forEach { runCatching { it.abort() } }
    }

    fun bytesReceived(): Long = perFileBytes.values.sumOf { it.get() }

    fun totalBytes(): Long = files.values.sumOf { it.size }

    override fun close() {
        ticker?.interrupt()
        sinks.values.forEach { runCatching { it.close() } }
        sinks.clear()
    }

    private companion object {
        const val PROGRESS_INTERVAL_MILLIS = 200L

        /**
         * 256 KiB per read. Big enough to amortize the syscall across a fast
         * link, small enough that four of these stay inside a phone's cache.
         */
        const val PAYLOAD_BUFFER = 256 * 1024
    }
}

/**
 * Fills [buffer] completely.
 *
 * @return false if the peer closed before any byte of it arrived, which is a
 *   clean shutdown; a close partway through a header is not, and throws.
 */
internal fun SocketChannel.readFully(buffer: ByteBuffer): Boolean {
    var total = 0
    while (buffer.hasRemaining()) {
        val read = read(buffer)
        if (read < 0) {
            if (total == 0) return false
            throw TransferException(
                FailureReason.NETWORK,
                "Peer closed mid-frame after $total of ${buffer.limit()} bytes",
            )
        }
        total += read
    }
    return true
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
