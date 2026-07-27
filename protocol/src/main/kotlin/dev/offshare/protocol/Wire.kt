package dev.offshare.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Control-channel messages.
 *
 * Encoded as a compact binary format rather than JSON. Both ends are the same
 * app speaking a private protocol, so there is no interop argument for a
 * self-describing format, and skipping a serialization dependency keeps this
 * module at zero third-party libraries.
 */
sealed interface Message {

    /** Sender -> receiver. Opens the session. */
    data class Hello(
        val version: Int,
        val identity: DeviceIdentity,
    ) : Message

    /** Receiver -> sender. Mints the session token that authorizes data streams. */
    data class HelloAck(
        val version: Int,
        val identity: DeviceIdentity,
        val sessionToken: ByteArray,
        val streamCount: Int,
        val verifyIntegrity: Boolean,
    ) : Message {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is HelloAck &&
                    version == other.version &&
                    identity == other.identity &&
                    sessionToken.contentEquals(other.sessionToken) &&
                    streamCount == other.streamCount &&
                    verifyIntegrity == other.verifyIntegrity)

        override fun hashCode(): Int {
            var result = version
            result = 31 * result + identity.hashCode()
            result = 31 * result + sessionToken.contentHashCode()
            result = 31 * result + streamCount
            result = 31 * result + verifyIntegrity.hashCode()
            return result
        }
    }

    /** Sender -> receiver. The manifest awaiting approval. */
    data class Offer(val files: List<FileMeta>) : Message

    /** Receiver -> sender. Per-file accept/reject and resume offsets. */
    data class Accept(val decisions: List<FileDecision>) : Message

    /** Sender -> receiver. Every unit has been written to a socket. */
    data object Done : Message

    /** Receiver -> sender. Final per-file outcomes. */
    data class Result(val files: List<FileResult>) : Message

    /** Either direction. Terminal. */
    data class Error(val reason: FailureReason, val detail: String) : Message
}

/**
 * Length-prefixed framing over a byte stream.
 *
 * Every frame is a 4-byte big-endian length followed by that many bytes, the
 * first of which is the message type. Lengths are bounds-checked against
 * [Protocol.MAX_FRAME_BYTES] before any allocation happens, so a hostile peer
 * cannot make us reserve a gigabyte by lying in the header.
 */
object Wire {

    /** Writes the 9-byte connection preamble: magic, version, role. */
    fun writePreamble(out: OutputStream, role: Byte) {
        val dos = DataOutputStream(out)
        dos.writeInt(Protocol.MAGIC)
        dos.writeInt(Protocol.VERSION)
        dos.writeByte(role.toInt())
        dos.flush()
    }

    /** Reads and validates the preamble. @return the role byte. */
    fun readPreamble(input: InputStream): Byte {
        val dis = DataInputStream(input)
        val magic = dis.readInt()
        if (magic != Protocol.MAGIC) {
            throw TransferException(
                FailureReason.NETWORK,
                "Not an OfflineShare connection (magic 0x${magic.toUInt().toString(16)})",
            )
        }
        val version = dis.readInt()
        if (version != Protocol.VERSION) {
            throw TransferException(
                FailureReason.VERSION_MISMATCH,
                "Peer speaks protocol v$version, this build speaks v${Protocol.VERSION}",
            )
        }
        return dis.readByte()
    }

    /**
     * Reads the preamble straight off a channel, consuming exactly nine bytes.
     *
     * Data connections must not go through a buffered stream for this: a
     * buffer would greedily pull payload bytes in behind the preamble, and
     * those bytes are invisible to the channel reads that follow. Control
     * connections can use the stream overload, because everything after their
     * preamble is read through the same buffer.
     */
    fun readPreamble(channel: java.nio.channels.SocketChannel): Byte {
        val buffer = java.nio.ByteBuffer.allocate(PREAMBLE_BYTES)
        if (!channel.readFully(buffer)) {
            throw TransferException(FailureReason.NETWORK, "Peer closed before the handshake")
        }
        buffer.flip()

        val magic = buffer.int
        if (magic != Protocol.MAGIC) {
            throw TransferException(
                FailureReason.NETWORK,
                "Not an OfflineShare connection (magic 0x${magic.toUInt().toString(16)})",
            )
        }
        val version = buffer.int
        if (version != Protocol.VERSION) {
            throw TransferException(
                FailureReason.VERSION_MISMATCH,
                "Peer speaks protocol v$version, this build speaks v${Protocol.VERSION}",
            )
        }
        return buffer.get()
    }

    /** Writes the preamble directly to a channel. */
    fun writePreamble(channel: java.nio.channels.SocketChannel, role: Byte) {
        val buffer = java.nio.ByteBuffer.allocate(PREAMBLE_BYTES)
        buffer.putInt(Protocol.MAGIC).putInt(Protocol.VERSION).put(role)
        buffer.flip()
        channel.writeFully(buffer)
    }

    /** magic (4) + version (4) + role (1). */
    internal const val PREAMBLE_BYTES: Int = 9

    fun writeMessage(out: OutputStream, message: Message) {
        val body = encode(message)
        val dos = DataOutputStream(out)
        dos.writeInt(body.size)
        dos.write(body)
        dos.flush()
    }

    fun readMessage(input: InputStream): Message {
        val dis = DataInputStream(input)
        val length = dis.readInt()
        if (length <= 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw TransferException(FailureReason.NETWORK, "Frame length out of range: $length")
        }
        val body = ByteArray(length)
        dis.readFully(body)
        return decode(body)
    }

    internal fun encode(message: Message): ByteArray {
        val buffer = ByteArrayOutputStream(256)
        val out = DataOutputStream(buffer)
        when (message) {
            is Message.Hello -> {
                out.writeByte(MsgType.HELLO.toInt())
                out.writeInt(message.version)
                out.writeIdentity(message.identity)
            }

            is Message.HelloAck -> {
                out.writeByte(MsgType.HELLO_ACK.toInt())
                out.writeInt(message.version)
                out.writeIdentity(message.identity)
                out.writeInt(message.sessionToken.size)
                out.write(message.sessionToken)
                out.writeInt(message.streamCount)
                out.writeBoolean(message.verifyIntegrity)
            }

            is Message.Offer -> {
                out.writeByte(MsgType.OFFER.toInt())
                out.writeInt(message.files.size)
                message.files.forEach { out.writeFileMeta(it) }
            }

            is Message.Accept -> {
                out.writeByte(MsgType.ACCEPT.toInt())
                out.writeInt(message.decisions.size)
                message.decisions.forEach {
                    out.writeInt(it.fileId)
                    out.writeBoolean(it.accepted)
                    out.writeLong(it.resumeFrom)
                }
            }

            Message.Done -> out.writeByte(MsgType.DONE.toInt())

            is Message.Result -> {
                out.writeByte(MsgType.RESULT.toInt())
                out.writeInt(message.files.size)
                message.files.forEach {
                    out.writeInt(it.fileId)
                    out.writeLong(it.bytesReceived)
                    out.writeBoolean(it.ok)
                    out.writeUtf(it.error)
                }
            }

            is Message.Error -> {
                out.writeByte(MsgType.ERROR.toInt())
                out.writeUtf(message.reason.name)
                out.writeUtf(message.detail)
            }
        }
        out.flush()
        return buffer.toByteArray()
    }

    internal fun decode(body: ByteArray): Message {
        val input = DataInputStream(ByteArrayInputStream(body))
        return when (val type = input.readByte()) {
            MsgType.HELLO -> Message.Hello(
                version = input.readInt(),
                identity = input.readIdentity(),
            )

            MsgType.HELLO_ACK -> {
                val version = input.readInt()
                val identity = input.readIdentity()
                val tokenLength = input.readInt()
                if (tokenLength !in 1..64) {
                    throw TransferException(FailureReason.NETWORK, "Bad token length $tokenLength")
                }
                val token = ByteArray(tokenLength).also(input::readFully)
                Message.HelloAck(
                    version = version,
                    identity = identity,
                    sessionToken = token,
                    streamCount = input.readInt(),
                    verifyIntegrity = input.readBoolean(),
                )
            }

            MsgType.OFFER -> {
                val count = input.readBoundedCount(Protocol.MAX_FILES_PER_OFFER)
                Message.Offer(List(count) { input.readFileMeta() })
            }

            MsgType.ACCEPT -> {
                val count = input.readBoundedCount(Protocol.MAX_FILES_PER_OFFER)
                Message.Accept(
                    List(count) {
                        FileDecision(
                            fileId = input.readInt(),
                            accepted = input.readBoolean(),
                            resumeFrom = input.readLong(),
                        )
                    },
                )
            }

            MsgType.DONE -> Message.Done

            MsgType.RESULT -> {
                val count = input.readBoundedCount(Protocol.MAX_FILES_PER_OFFER)
                Message.Result(
                    List(count) {
                        FileResult(
                            fileId = input.readInt(),
                            bytesReceived = input.readLong(),
                            ok = input.readBoolean(),
                            error = input.readUtf(),
                        )
                    },
                )
            }

            MsgType.ERROR -> {
                val reasonName = input.readUtf()
                val reason = FailureReason.entries.firstOrNull { it.name == reasonName }
                    ?: FailureReason.NETWORK
                Message.Error(reason, input.readUtf())
            }

            else -> throw TransferException(FailureReason.NETWORK, "Unknown message type $type")
        }
    }

    // -- primitives -------------------------------------------------------
    //
    // DataOutputStream.writeUTF caps strings at 64 KiB and uses modified
    // UTF-8; a length-prefixed UTF-8 byte array is simpler to reason about
    // and matches what the file-name fields actually need.

    private fun DataOutputStream.writeUtf(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readUtf(): String {
        val length = readInt()
        if (length < 0 || length > Protocol.MAX_FRAME_BYTES) {
            throw TransferException(FailureReason.NETWORK, "String length out of range: $length")
        }
        val bytes = ByteArray(length).also(::readFully)
        return String(bytes, Charsets.UTF_8)
    }

    private fun DataInputStream.readBoundedCount(max: Int): Int {
        val count = readInt()
        if (count < 0 || count > max) {
            throw TransferException(FailureReason.NETWORK, "Collection size out of range: $count")
        }
        return count
    }

    private fun DataOutputStream.writeIdentity(identity: DeviceIdentity) {
        writeUtf(identity.deviceId)
        writeUtf(identity.deviceName)
        writeUtf(identity.platform)
    }

    private fun DataInputStream.readIdentity(): DeviceIdentity =
        DeviceIdentity(
            deviceId = readUtf(),
            deviceName = readUtf(),
            platform = readUtf(),
        )

    private fun DataOutputStream.writeFileMeta(meta: FileMeta) {
        writeInt(meta.id)
        writeUtf(meta.name)
        writeLong(meta.size)
        writeUtf(meta.mimeType)
        writeUtf(meta.relativePath)
    }

    private fun DataInputStream.readFileMeta(): FileMeta {
        val id = readInt()
        val name = readUtf()
        val size = readLong()
        if (size < 0) throw TransferException(FailureReason.NETWORK, "Negative file size")
        return FileMeta(
            id = id,
            name = name.ifBlank { "file-$id" },
            size = size,
            mimeType = readUtf(),
            relativePath = readUtf(),
        )
    }
}
