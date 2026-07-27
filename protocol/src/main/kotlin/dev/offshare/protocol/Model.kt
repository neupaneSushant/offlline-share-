package dev.offshare.protocol

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.security.SecureRandom

/** Who we are, as advertised to the peer during the handshake. */
data class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val platform: String,
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId must not be blank" }
        require(deviceName.isNotBlank()) { "deviceName must not be blank" }
    }

    companion object {
        /** A stable random id; callers should persist this across launches. */
        fun randomId(): String {
            val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * One file in an offer.
 *
 * [id] is assigned by the sender and is only meaningful within a session; it
 * is what data-unit headers reference, so it stays a plain int rather than a
 * string to keep the hot-path header small.
 */
data class FileMeta(
    val id: Int,
    val name: String,
    val size: Long,
    val mimeType: String = "application/octet-stream",
    /** Relative directory path for folder sends, or empty for a flat file. */
    val relativePath: String = "",
) {
    init {
        require(size >= 0) { "size must not be negative" }
        require(name.isNotBlank()) { "name must not be blank" }
    }

    /** Path the receiver should write to, with traversal escapes stripped. */
    fun safeRelativePath(): String {
        val segments = (if (relativePath.isEmpty()) name else "$relativePath/$name")
            .split('/', '\\')
            .filter { it.isNotEmpty() && it != "." && it != ".." }
        return if (segments.isEmpty()) sanitizedName() else segments.joinToString("/")
    }

    private fun sanitizedName(): String =
        name.replace(Regex("""[/\\]"""), "_").ifBlank { "file-$id" }
}

/**
 * A readable source of file bytes.
 *
 * Deliberately hands back a [FileChannel] rather than an InputStream: the
 * sender uses [FileChannel.transferTo] so payload bytes go straight from the
 * page cache to the socket without ever being copied into the JVM heap. On
 * Android a `content://` URI satisfies this via
 * `contentResolver.openFileDescriptor(uri, "r")` -> `FileInputStream.channel`.
 */
interface FileSource {
    val meta: FileMeta

    /** Opens an independent channel. Called once per parallel stream. */
    fun openChannel(): FileChannel
}

/** A [FileSource] backed by a regular filesystem path. */
class LocalFileSource(
    override val meta: FileMeta,
    private val file: File,
) : FileSource {
    override fun openChannel(): FileChannel = FileInputStream(file).channel

    companion object {
        fun of(id: Int, file: File, relativePath: String = ""): LocalFileSource =
            LocalFileSource(
                FileMeta(
                    id = id,
                    name = file.name,
                    size = file.length(),
                    relativePath = relativePath,
                ),
                file,
            )
    }
}

/**
 * A writable destination for one incoming file.
 *
 * Writes arrive out of order and from several threads at once, addressed by
 * absolute offset -- hence a positional [FileChannel] rather than a stream.
 */
interface FileSink : Closeable {
    val channel: FileChannel

    /** Called once every byte has landed. Move into place, flush, publish. */
    fun commit()

    /** Called on failure or cancellation. Delete partial output. */
    fun abort()
}

/** Decides where incoming files go, and whether a partial file can resume. */
interface FileSinkFactory {
    /**
     * Opens a sink for [meta].
     *
     * @param resumeFrom byte offset already present locally, echoed back from
     *   the offset this factory reported via [existingBytes].
     */
    fun open(meta: FileMeta, resumeFrom: Long): FileSink

    /**
     * Bytes of [meta] already on disk from an interrupted run, or 0 to start
     * over. Returning a value equal to `meta.size` skips the file entirely.
     */
    fun existingBytes(meta: FileMeta): Long = 0L
}

/** A [FileSinkFactory] that drops files into a directory on the filesystem. */
class DirectoryFileSinkFactory(
    private val root: File,
    private val resumable: Boolean = true,
) : FileSinkFactory {

    override fun existingBytes(meta: FileMeta): Long {
        if (!resumable) return 0L
        val partial = partialFileFor(meta)
        return if (partial.isFile) partial.length() else 0L
    }

    override fun open(meta: FileMeta, resumeFrom: Long): FileSink {
        val target = File(root, meta.safeRelativePath())
        target.parentFile?.mkdirs()
        val partial = partialFileFor(meta)
        partial.parentFile?.mkdirs()

        val raf = RandomAccessFile(partial, "rw")
        if (resumeFrom == 0L) {
            raf.setLength(0)
        }
        // Preallocating keeps the filesystem from fragmenting the file as four
        // streams write into it at scattered offsets.
        raf.setLength(meta.size)

        return object : FileSink {
            override val channel: FileChannel = raf.channel

            override fun commit() {
                channel.force(true)
                raf.close()
                if (target.exists() && !target.delete()) {
                    throw TransferException(
                        FailureReason.STORAGE,
                        "Could not replace existing file ${target.absolutePath}",
                    )
                }
                if (!partial.renameTo(target)) {
                    throw TransferException(
                        FailureReason.STORAGE,
                        "Could not move ${partial.name} into place",
                    )
                }
            }

            override fun abort() {
                runCatching { raf.close() }
                // Leave the .part file behind so the next run can resume it.
            }

            override fun close() {
                runCatching { raf.close() }
            }
        }
    }

    private fun partialFileFor(meta: FileMeta): File =
        File(root, meta.safeRelativePath() + PARTIAL_SUFFIX)

    private companion object {
        const val PARTIAL_SUFFIX = ".ofsh-part"
    }
}

/** The receiver's verdict on one offered file. */
data class FileDecision(
    val fileId: Int,
    val accepted: Boolean,
    /** Bytes already held locally; the sender starts from here. */
    val resumeFrom: Long = 0L,
)

/** Asked to approve an incoming offer before any bytes move. */
fun interface OfferApprover {
    /**
     * @return one decision per file. Returning an empty list, or all-rejected
     *   decisions, declines the transfer.
     */
    fun approve(peer: DeviceIdentity, files: List<FileMeta>): List<FileDecision>
}

/** Per-file outcome reported back to the sender when the session ends. */
data class FileResult(
    val fileId: Int,
    val bytesReceived: Long,
    val ok: Boolean,
    val error: String = "",
)

/** Tunables for both ends of a transfer. */
data class TransferConfig(
    /**
     * Parallel TCP connections carrying payload.
     *
     * One stream cannot saturate a 5 GHz Wi-Fi Direct link: every stall for a
     * lost frame drains that stream's congestion window while the radio sits
     * idle. Four independent streams keep the pipe full through those stalls
     * and are worth roughly 1.5-2x over a single connection in practice.
     * Beyond about eight the per-connection overhead starts winning.
     */
    val streamCount: Int = 4,

    /**
     * Bytes handed to one stream at a time. Large enough that the 20-byte unit
     * header and the queue handoff vanish into the noise, small enough that
     * work stays evenly spread when one file dominates the set.
     */
    val unitSize: Int = 4 * 1024 * 1024,

    /**
     * Socket buffer request, in bytes.
     *
     * The bandwidth-delay product of a 5 GHz link at ~3 ms RTT is well past
     * the usual 64 KB default, so leaving this alone caps throughput no matter
     * how fast the radio is. The OS may clamp the request; that is fine.
     */
    val socketBufferBytes: Int = 2 * 1024 * 1024,

    /**
     * Verify each unit with a CRC32 the sender computes.
     *
     * Off by default, and that is a deliberate trade. With it off the sender
     * uses [FileChannel.transferTo], which on Linux is a real sendfile: bytes
     * go from page cache to socket without ever being copied into user space.
     * Turning it on forces the sender to read every byte into a buffer to
     * checksum it, giving up sendfile entirely -- typically the difference
     * between "as fast as the radio" and noticeably slower on phone CPUs.
     *
     * The receiver pays less: there is no socket-to-file sendfile on the JVM,
     * so it copies through a direct buffer either way and only adds the
     * checksum arithmetic.
     *
     * TCP already checksums every segment and WPA2 authenticates every frame,
     * so this is aimed at flaky storage rather than a flaky network.
     */
    val verifyIntegrity: Boolean = false,

    /** Handshake and connect timeout. */
    val handshakeTimeoutMillis: Int = 15_000,

    /** Read timeout on an idle data stream mid-transfer. */
    val transferTimeoutMillis: Int = 60_000,
) {
    init {
        require(streamCount in 1..16) { "streamCount must be 1..16" }
        require(unitSize >= 64 * 1024) { "unitSize must be at least 64 KiB" }
        require(socketBufferBytes >= 64 * 1024) { "socketBufferBytes must be at least 64 KiB" }
    }
}

/** Progress and lifecycle callbacks. Invoked from transfer threads. */
interface TransferListener {
    fun onSessionStarted(peer: DeviceIdentity, files: List<FileMeta>) {}

    /** Cumulative bytes across the whole session, plus an instantaneous rate. */
    fun onProgress(bytesCompleted: Long, bytesTotal: Long, bytesPerSecond: Long) {}

    fun onFileCompleted(meta: FileMeta) {}

    fun onSessionFinished(summary: TransferSummary) {}

    fun onError(error: TransferException) {}

    companion object {
        val NONE: TransferListener = object : TransferListener {}
    }
}

/** What actually happened, once a session is over. */
data class TransferSummary(
    val peer: DeviceIdentity?,
    val files: List<FileResult>,
    val bytesTransferred: Long,
    val elapsedMillis: Long,
    val failure: FailureReason? = null,
) {
    val succeeded: Boolean get() = failure == null && files.all { it.ok }

    /** Mean throughput over the session, in bytes per second. */
    val bytesPerSecond: Long
        get() = if (elapsedMillis <= 0) 0L else bytesTransferred * 1000L / elapsedMillis
}
