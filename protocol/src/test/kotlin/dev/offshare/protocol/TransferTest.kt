package dev.offshare.protocol

import java.io.File
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.util.Random
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end transfers over loopback.
 *
 * These exercise the real socket path -- parallel streams, work stealing,
 * sendfile, positional writes -- rather than mocking the transport, because
 * every interesting bug in this module lives in exactly those interactions.
 * Loopback throughput is not a Wi-Fi number; it bounds the engine, and tells
 * us the framing overhead is not what limits a transfer on real hardware.
 */
class TransferTest {

    private val sender = DeviceIdentity("sender-01", "Pixel 8", "android-35")
    private val receiver = DeviceIdentity("receiver-01", "ThinkPad", "linux")

    private lateinit var sourceDir: File
    private lateinit var destinationDir: File
    private val servers = CopyOnWriteArrayList<TransferServer>()

    @BeforeTest
    fun setUpDirs() {
        sourceDir = createTempDir("ofsh-src")
        destinationDir = createTempDir("ofsh-dst")
    }

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
        if (::sourceDir.isInitialized) sourceDir.deleteRecursively()
        if (::destinationDir.isInitialized) destinationDir.deleteRecursively()
    }

    @Test
    fun `transfers a mixed set of files byte for byte`() {
        val files = listOf(
            randomFile("notes.txt", 1_024),
            randomFile("clip.mp4", 32 * 1024 * 1024),
            randomFile("empty.bin", 0),
            randomFile("photo.jpg", 3 * 1024 * 1024 + 7), // deliberately not unit-aligned
        )

        val summary = runTransfer(files)

        assertTrue(summary.succeeded, "transfer failed: ${summary.failure}")
        assertEquals(files.sumOf { it.length() }, summary.bytesTransferred)
        files.forEach { source ->
            val received = File(destinationDir, source.name)
            assertTrue(received.isFile, "${source.name} did not arrive")
            assertEquals(source.length(), received.length(), "${source.name} wrong size")
            assertContentEquals(
                sha256(source),
                sha256(received),
                "${source.name} arrived corrupted",
            )
        }
        assertTrue(
            destinationDir.walkTopDown().none { it.name.endsWith(".ofsh-part") },
            "partial files were left behind after a clean transfer",
        )
    }

    @Test
    fun `preserves folder structure without escaping the destination`() {
        val file = randomFile("inner.bin", 4_096)
        val sources = listOf(
            LocalFileSource(
                FileMeta(1, "inner.bin", file.length(), relativePath = "trip/day one"),
                file,
            ),
        )

        val summary = runTransferWithSources(sources)

        assertTrue(summary.succeeded)
        val landed = File(destinationDir, "trip/day one/inner.bin")
        assertTrue(landed.isFile, "nested file did not land at the expected path")
        assertContentEquals(sha256(file), sha256(landed))
    }

    @Test
    fun `resumes a file that a dropped hotspot left half written`() {
        val source = randomFile("big.iso", 12 * 1024 * 1024)
        val alreadyHave = 5 * 1024 * 1024L

        // Simulate the leftovers of an interrupted run: a .part file holding
        // a correct prefix of the file.
        val partial = File(destinationDir, "big.iso.ofsh-part")
        RandomAccessFile(source, "r").use { input ->
            val prefix = ByteArray(alreadyHave.toInt())
            input.readFully(prefix)
            partial.writeBytes(prefix)
        }

        var offeredResumeFrom = -1L
        val summary = runTransfer(
            listOf(source),
            onDecision = { decisions -> offeredResumeFrom = decisions.single().resumeFrom },
        )

        assertTrue(summary.succeeded, "resumed transfer failed: ${summary.failure}")
        assertEquals(alreadyHave, offeredResumeFrom, "receiver did not report the resume offset")
        assertEquals(
            source.length() - alreadyHave,
            summary.bytesTransferred,
            "sender re-sent bytes the receiver already had",
        )
        assertContentEquals(
            sha256(source),
            sha256(File(destinationDir, "big.iso")),
            "resumed file is corrupt at the seam",
        )
    }

    @Test
    fun `a declined offer moves no bytes and leaves no files`() {
        val files = listOf(randomFile("secret.zip", 2 * 1024 * 1024))
        val summary = runTransfer(files, approve = false)

        assertFalse(summary.succeeded)
        assertEquals(FailureReason.REJECTED, summary.failure)
        assertEquals(0, summary.bytesTransferred)
        assertEquals(
            emptyList(),
            destinationDir.listFiles()?.toList() ?: emptyList(),
            "a declined transfer created files anyway",
        )
    }

    @Test
    fun `integrity verification passes over a clean link`() {
        val files = listOf(randomFile("checked.bin", 9 * 1024 * 1024))
        val summary = runTransfer(
            files,
            config = TransferConfig(verifyIntegrity = true, streamCount = 3),
        )

        assertTrue(summary.succeeded, "verified transfer failed: ${summary.failure}")
        assertContentEquals(
            sha256(files.single()),
            sha256(File(destinationDir, "checked.bin")),
        )
    }

    @Test
    fun `works with a single stream`() {
        val files = listOf(randomFile("solo.bin", 6 * 1024 * 1024))
        val summary = runTransfer(files, config = TransferConfig(streamCount = 1))

        assertTrue(summary.succeeded, "single-stream transfer failed: ${summary.failure}")
        assertContentEquals(sha256(files.single()), sha256(File(destinationDir, "solo.bin")))
    }

    @Test
    fun `a data stream bearing an unissued token is refused`() {
        val errors = CopyOnWriteArrayList<TransferException>()
        val seen = CountDownLatch(1)
        val server = startServer(
            listener = object : TransferListener {
                override fun onError(error: TransferException) {
                    errors.add(error)
                    seen.countDown()
                }
            },
        )

        // A device that joined the hotspot but was never invited into this
        // session: right port, right framing, token it made up.
        Socket("127.0.0.1", server.boundPort).use { socket ->
            val out = socket.getOutputStream()
            Wire.writePreamble(out, Protocol.Role.DATA)
            out.write(ByteArray(Protocol.SESSION_TOKEN_BYTES) { 0x41 })
            out.flush()
            assertTrue(seen.await(5, TimeUnit.SECONDS), "server never rejected the stream")
        }

        assertEquals(FailureReason.UNAUTHORIZED, errors.first().reason)
    }

    @Test
    fun `reports throughput for a large transfer`() {
        val size = 192 * 1024 * 1024L
        val file = randomFile("bulk.bin", size)

        val rates = CopyOnWriteArrayList<Long>()
        val summary = runTransfer(
            listOf(file),
            listener = object : TransferListener {
                override fun onProgress(
                    bytesCompleted: Long,
                    bytesTotal: Long,
                    bytesPerSecond: Long,
                ) {
                    if (bytesPerSecond > 0) rates.add(bytesPerSecond)
                }
            },
        )

        assertTrue(summary.succeeded, "bulk transfer failed: ${summary.failure}")
        assertEquals(size, summary.bytesTransferred)
        println(
            "loopback: ${formatBytes(size)} in ${summary.elapsedMillis} ms " +
                "= ${formatRate(summary.bytesPerSecond)} " +
                "(engine ceiling, not a Wi-Fi measurement)",
        )
        assertTrue(rates.isNotEmpty(), "progress was never reported during a 192 MB transfer")
    }

    // -- harness ----------------------------------------------------------

    private fun runTransfer(
        files: List<File>,
        approve: Boolean = true,
        config: TransferConfig = TransferConfig(),
        listener: TransferListener = TransferListener.NONE,
        onDecision: (List<FileDecision>) -> Unit = {},
    ): TransferSummary {
        val sources = files.mapIndexed { index, file -> LocalFileSource.of(index + 1, file) }
        return runTransferWithSources(sources, approve, config, listener, onDecision)
    }

    private fun runTransferWithSources(
        sources: List<FileSource>,
        approve: Boolean = true,
        config: TransferConfig = TransferConfig(),
        listener: TransferListener = TransferListener.NONE,
        onDecision: (List<FileDecision>) -> Unit = {},
    ): TransferSummary {
        val decisionsSeen = CopyOnWriteArrayList<FileDecision>()
        val server = startServer(
            config = config,
            approver = { _, files ->
                files.map { FileDecision(it.id, accepted = approve) }
            },
            listener = object : TransferListener {
                override fun onSessionStarted(peer: DeviceIdentity, files: List<FileMeta>) {}
            },
            captureDecisions = decisionsSeen,
        )

        val summary = TransferClient(sender, config, listener)
            .send(InetSocketAddress("127.0.0.1", server.boundPort), sources)

        onDecision(decisionsSeen.toList())
        return summary
    }

    private fun startServer(
        config: TransferConfig = TransferConfig(),
        approver: OfferApprover = OfferApprover { _, files ->
            files.map { FileDecision(it.id, accepted = true) }
        },
        listener: TransferListener = TransferListener.NONE,
        captureDecisions: MutableList<FileDecision>? = null,
    ): TransferServer {
        val factory = object : FileSinkFactory {
            private val delegate = DirectoryFileSinkFactory(destinationDir)

            override fun existingBytes(meta: FileMeta): Long = delegate.existingBytes(meta)

            override fun open(meta: FileMeta, resumeFrom: Long): FileSink {
                captureDecisions?.add(FileDecision(meta.id, accepted = true, resumeFrom))
                return delegate.open(meta, resumeFrom)
            }
        }

        val server = TransferServer(receiver, factory, approver, config, listener)
        server.start(port = 0, bindAddress = "127.0.0.1")
        servers.add(server)
        return server
    }

    private fun randomFile(name: String, size: Long): File {
        val file = File(sourceDir, name)
        // Incompressible content, seeded for reproducibility. Compressible
        // filler would let a filesystem or the loopback path flatter us.
        val random = Random(name.hashCode().toLong())
        val chunk = ByteArray(1 shl 20)
        file.outputStream().buffered().use { out ->
            var written = 0L
            while (written < size) {
                random.nextBytes(chunk)
                val take = minOf(chunk.size.toLong(), size - written).toInt()
                out.write(chunk, 0, take)
                written += take
            }
        }
        return file
    }

    private fun sha256(file: File): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest()
    }

    private fun createTempDir(prefix: String): File =
        java.nio.file.Files.createTempDirectory(prefix).toFile()
}
