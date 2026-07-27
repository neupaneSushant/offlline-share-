package dev.offshare.protocol

/**
 * Wire constants shared by both ends.
 *
 * The transport assumes it is running on a link that is already encrypted and
 * already private: a WPA2 Wi-Fi Direct group or a local-only hotspot created
 * for this transfer, with a passphrase that never leaves the pairing QR code.
 * That assumption is what lets the bulk data path stay zero-copy -- see
 * [TransferConfig.verifyIntegrity] for the one knob that trades it away.
 */
object Protocol {

    /** ASCII "OFSH". First four bytes of every connection. */
    const val MAGIC: Int = 0x4F465348

    /**
     * Bumped on any incompatible change to framing or message layout.
     * Both ends refuse to proceed on mismatch rather than guessing.
     */
    const val VERSION: Int = 1

    /** Default TCP port for the control listener. Also carries data streams. */
    const val DEFAULT_PORT: Int = 5330

    /** UDP port for presence announcements on the hotspot subnet. */
    const val DISCOVERY_PORT: Int = 5331

    /**
     * The Wi-Fi Direct group owner always sits at this address. Android
     * hardcodes it, so a client that joined the group can skip discovery
     * entirely and dial straight in.
     */
    const val WIFI_DIRECT_GROUP_OWNER_ADDRESS: String = "192.168.49.1"

    /** Typical gateway address when hosting via a local-only hotspot instead. */
    const val LOCAL_ONLY_HOTSPOT_ADDRESS: String = "192.168.43.1"

    /** Connection roles, sent as one byte right after the magic and version. */
    object Role {
        const val CONTROL: Byte = 1
        const val DATA: Byte = 2
    }

    /** Length in bytes of the session token minted by the receiver. */
    const val SESSION_TOKEN_BYTES: Int = 16

    /** Guard against a malicious or corrupt peer asking us to allocate wildly. */
    const val MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

    /** Upper bound on files in a single offer. */
    const val MAX_FILES_PER_OFFER: Int = 20_000
}

/** Message discriminators on the control channel. */
internal object MsgType {
    const val HELLO: Byte = 1
    const val HELLO_ACK: Byte = 2
    const val OFFER: Byte = 3
    const val ACCEPT: Byte = 4
    const val DONE: Byte = 5
    const val RESULT: Byte = 6
    const val ERROR: Byte = 7
}

/** Flag bits in a data-unit header. */
internal object UnitFlags {
    /** No more units on this stream; the sender is about to close it. */
    const val END_OF_STREAM: Int = 1 shl 0

    /** Header is followed by a 4-byte CRC32 of the payload. */
    const val HAS_CRC: Int = 1 shl 1
}

/**
 * Fixed-size header preceding every block of file bytes on a data stream.
 *
 * fileId (4) + offset (8) + length (4) + flags (4) = 20 bytes. Kept fixed and
 * small because it is read once per work unit on the hot path.
 */
internal const val UNIT_HEADER_BYTES: Int = 20

/** Reasons a transfer ended without moving every byte. */
enum class FailureReason {
    /** Peer spoke a protocol version we do not implement. */
    VERSION_MISMATCH,

    /** Peer declined the offer outright. */
    REJECTED,

    /** Socket died, host unreachable, or the hotspot dropped mid-transfer. */
    NETWORK,

    /** Local disk full, unreadable source, or a sink that refused to open. */
    STORAGE,

    /** A data connection presented a token we never issued. */
    UNAUTHORIZED,

    /** Received bytes did not match the sender's checksum. */
    INTEGRITY,

    /** A human or the app cancelled it. */
    CANCELLED,
}

/** Thrown for protocol-level faults; carries a [FailureReason] for the UI. */
class TransferException(
    val reason: FailureReason,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
