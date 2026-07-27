package dev.offshare.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WireTest {

    private val identity = DeviceIdentity("abc123", "Sushant's Pixel", "android-35")

    @Test
    fun `hello round trips`() {
        val message = Message.Hello(Protocol.VERSION, identity)
        assertEquals(message, roundTrip(message))
    }

    @Test
    fun `hello ack round trips including token bytes`() {
        val message = Message.HelloAck(
            version = Protocol.VERSION,
            identity = identity,
            sessionToken = ByteArray(Protocol.SESSION_TOKEN_BYTES) { it.toByte() },
            streamCount = 4,
            verifyIntegrity = true,
        )
        val decoded = roundTrip(message) as Message.HelloAck
        assertEquals(message, decoded)
        assertTrue(message.sessionToken.contentEquals(decoded.sessionToken))
    }

    @Test
    fun `offer round trips with unicode names and nested paths`() {
        val message = Message.Offer(
            listOf(
                FileMeta(1, "холодно.txt", 42, "text/plain"),
                FileMeta(2, "photo 🌄.jpg", 8_000_000, "image/jpeg", "trip/day one"),
                FileMeta(3, "empty.bin", 0),
            ),
        )
        assertEquals(message, roundTrip(message))
    }

    @Test
    fun `accept and result round trip`() {
        val accept = Message.Accept(
            listOf(
                FileDecision(1, accepted = true),
                FileDecision(2, accepted = true, resumeFrom = 4_194_304L),
                FileDecision(3, accepted = false),
            ),
        )
        assertEquals(accept, roundTrip(accept))

        val result = Message.Result(
            listOf(
                FileResult(1, 42, ok = true),
                FileResult(2, 0, ok = false, error = "No space left on device"),
            ),
        )
        assertEquals(result, roundTrip(result))
    }

    @Test
    fun `done and error round trip`() {
        assertEquals(Message.Done, roundTrip(Message.Done))
        val error = Message.Error(FailureReason.UNAUTHORIZED, "bad token")
        assertEquals(error, roundTrip(error))
    }

    @Test
    fun `unknown failure reason decodes to network rather than throwing`() {
        // A newer peer may name a reason this build has never heard of. That
        // must not turn a readable error into an unreadable crash.
        val body = ByteArrayOutputStream()
        java.io.DataOutputStream(body).apply {
            writeByte(MsgType.ERROR.toInt())
            val reason = "QUANTUM_FLUX".toByteArray(Charsets.UTF_8)
            writeInt(reason.size)
            write(reason)
            val detail = "from the future".toByteArray(Charsets.UTF_8)
            writeInt(detail.size)
            write(detail)
        }
        val decoded = Wire.decode(body.toByteArray()) as Message.Error
        assertEquals(FailureReason.NETWORK, decoded.reason)
        assertEquals("from the future", decoded.detail)
    }

    @Test
    fun `preamble rejects a foreign protocol`() {
        val buffer = ByteArrayOutputStream()
        buffer.write(byteArrayOf(0x47, 0x45, 0x54, 0x20)) // "GET " -- an HTTP probe
        buffer.write(ByteArray(8))

        val failure = assertFailsWith<TransferException> {
            Wire.readPreamble(ByteArrayInputStream(buffer.toByteArray()))
        }
        assertEquals(FailureReason.NETWORK, failure.reason)
    }

    @Test
    fun `preamble rejects a mismatched version`() {
        val buffer = ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).apply {
            writeInt(Protocol.MAGIC)
            writeInt(Protocol.VERSION + 1)
            writeByte(Protocol.Role.CONTROL.toInt())
        }
        val failure = assertFailsWith<TransferException> {
            Wire.readPreamble(ByteArrayInputStream(buffer.toByteArray()))
        }
        assertEquals(FailureReason.VERSION_MISMATCH, failure.reason)
    }

    @Test
    fun `oversized frame length is refused before allocating`() {
        val buffer = ByteArrayOutputStream()
        java.io.DataOutputStream(buffer).writeInt(Int.MAX_VALUE)
        val failure = assertFailsWith<TransferException> {
            Wire.readMessage(ByteArrayInputStream(buffer.toByteArray()))
        }
        assertTrue(failure.message!!.contains("out of range"))
    }

    @Test
    fun `absurd collection count is refused before allocating`() {
        val body = ByteArrayOutputStream()
        java.io.DataOutputStream(body).apply {
            writeByte(MsgType.OFFER.toInt())
            writeInt(Int.MAX_VALUE)
        }
        assertFailsWith<TransferException> { Wire.decode(body.toByteArray()) }
    }

    @Test
    fun `path traversal in a file name cannot escape the destination`() {
        val meta = FileMeta(1, "passwd", 10, relativePath = "../../../../etc")
        assertEquals("etc/passwd", meta.safeRelativePath())

        val sneaky = FileMeta(2, "../../evil.sh", 10)
        assertTrue(!sneaky.safeRelativePath().contains(".."))
    }

    @Test
    fun `absolute paths are flattened to relative ones`() {
        val meta = FileMeta(1, "shadow", 10, relativePath = "/etc")
        assertEquals("etc/shadow", meta.safeRelativePath())
    }

    private fun roundTrip(message: Message): Message {
        val buffer = ByteArrayOutputStream()
        Wire.writeMessage(buffer, message)
        return Wire.readMessage(ByteArrayInputStream(buffer.toByteArray()))
    }
}
