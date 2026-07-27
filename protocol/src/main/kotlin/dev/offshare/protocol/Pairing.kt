package dev.offshare.protocol

import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.net.URLEncoder

/** Which radio band the host asked for when it created the group. */
enum class HotspotBand {
    /** 2.4 GHz. Longer range, far slower, and crowded almost everywhere. */
    BAND_2_4_GHZ,

    /** 5 GHz. What you want; roughly 5-10x the throughput at close range. */
    BAND_5_GHZ,

    /** The host could not choose, or did not report one. */
    UNKNOWN,
}

/**
 * Everything the joining device needs, encoded for a QR code.
 *
 * Typing a WPA2 passphrase by hand is the slowest part of an otherwise
 * instant flow, and a per-session random passphrase -- which is what keeps
 * the link private -- is exactly the kind nobody wants to type. So the host
 * renders this as a QR and the guest scans it.
 */
data class PairingPayload(
    /** Network name of the Wi-Fi Direct group or local-only hotspot. */
    val ssid: String,
    /** WPA2 passphrase. Regenerated for every session. */
    val passphrase: String,
    /** Address the transfer server is listening on, once joined. */
    val host: String = Protocol.WIFI_DIRECT_GROUP_OWNER_ADDRESS,
    val port: Int = Protocol.DEFAULT_PORT,
    /** Human-readable name of the hosting device. */
    val deviceName: String = "",
    val deviceId: String = "",
    val band: HotspotBand = HotspotBand.UNKNOWN,
    /** True when the host is the one that will receive files. */
    val hostReceives: Boolean = true,
) {
    fun toUri(): String {
        val params = buildList {
            add("s" to ssid)
            add("p" to passphrase)
            add("h" to host)
            add("t" to port.toString())
            if (deviceName.isNotEmpty()) add("n" to deviceName)
            if (deviceId.isNotEmpty()) add("i" to deviceId)
            if (band != HotspotBand.UNKNOWN) add("b" to bandCode(band))
            add("r" to if (hostReceives) "1" else "0")
        }
        val query = params.joinToString("&") { (key, value) -> "$key=${encode(value)}" }
        return "$SCHEME://v${Protocol.VERSION}?$query"
    }

    companion object {
        const val SCHEME: String = "offshare"

        /**
         * Parses a scanned pairing URI.
         *
         * @throws TransferException if the payload is malformed or from an
         *   incompatible protocol version. QR scanners happily decode any
         *   barcode in frame, so this has to reject unrelated codes cleanly
         *   rather than half-configure a connection.
         */
        fun parse(uri: String): PairingPayload {
            val trimmed = uri.trim()
            if (!trimmed.startsWith("$SCHEME://", ignoreCase = true)) {
                throw TransferException(
                    FailureReason.NETWORK,
                    "Not an OfflineShare pairing code",
                )
            }

            val withoutScheme = trimmed.substring("$SCHEME://".length)
            val separator = withoutScheme.indexOf('?')
            if (separator <= 0) {
                throw TransferException(FailureReason.NETWORK, "Pairing code has no parameters")
            }

            val versionPart = withoutScheme.substring(0, separator)
            val version = versionPart.removePrefix("v").toIntOrNull()
                ?: throw TransferException(
                    FailureReason.NETWORK,
                    "Pairing code has an unreadable version: $versionPart",
                )
            if (version != Protocol.VERSION) {
                throw TransferException(
                    FailureReason.VERSION_MISMATCH,
                    "Pairing code is for protocol v$version; this build speaks " +
                        "v${Protocol.VERSION}. Update both devices.",
                )
            }

            val params = withoutScheme.substring(separator + 1)
                .split('&')
                .filter { it.isNotEmpty() }
                .mapNotNull { pair ->
                    val index = pair.indexOf('=')
                    if (index <= 0) null
                    else pair.substring(0, index) to decode(pair.substring(index + 1))
                }
                .toMap()

            val ssid = params["s"]
                ?: throw TransferException(FailureReason.NETWORK, "Pairing code has no network name")
            val passphrase = params["p"]
                ?: throw TransferException(FailureReason.NETWORK, "Pairing code has no passphrase")

            return PairingPayload(
                ssid = ssid,
                passphrase = passphrase,
                host = params["h"] ?: Protocol.WIFI_DIRECT_GROUP_OWNER_ADDRESS,
                port = params["t"]?.toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: Protocol.DEFAULT_PORT,
                deviceName = params["n"].orEmpty(),
                deviceId = params["i"].orEmpty(),
                band = parseBand(params["b"]),
                hostReceives = params["r"] != "0",
            )
        }

        private fun bandCode(band: HotspotBand): String = when (band) {
            HotspotBand.BAND_2_4_GHZ -> "24"
            HotspotBand.BAND_5_GHZ -> "5"
            HotspotBand.UNKNOWN -> ""
        }

        private fun parseBand(code: String?): HotspotBand = when (code) {
            "24" -> HotspotBand.BAND_2_4_GHZ
            "5" -> HotspotBand.BAND_5_GHZ
            else -> HotspotBand.UNKNOWN
        }

        private fun encode(value: String): String =
            try {
                URLEncoder.encode(value, "UTF-8")
            } catch (_: UnsupportedEncodingException) {
                value
            }

        private fun decode(value: String): String =
            try {
                URLDecoder.decode(value, "UTF-8")
            } catch (_: Exception) {
                value
            }
    }
}

/**
 * Generates a WPA2 passphrase for a single session.
 *
 * Deliberately not a memorable word list. Nobody types this -- it travels by
 * QR code -- so there is no reason to trade entropy for readability, and the
 * network stays up only for the length of one transfer.
 */
object PassphraseGenerator {
    // No l/1/I/0/O: the passphrase does end up on screen as a manual fallback
    // when a camera is unavailable, and those are the characters people
    // reliably get wrong.
    private const val ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** @param length WPA2 requires 8..63 characters. */
    fun generate(length: Int = 12): String {
        require(length in 8..63) { "WPA2 passphrases must be 8..63 characters" }
        val random = java.security.SecureRandom()
        return buildString(length) {
            repeat(length) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        }
    }
}
