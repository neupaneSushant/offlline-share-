package dev.offshare.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PairingTest {

    @Test
    fun `pairing payload round trips`() {
        val payload = PairingPayload(
            ssid = "DIRECT-Xy-OfflineShare",
            passphrase = "hK3mQ9zPr2Tw",
            host = Protocol.WIFI_DIRECT_GROUP_OWNER_ADDRESS,
            port = 5330,
            deviceName = "Pixel 8",
            deviceId = "abc123",
            band = HotspotBand.BAND_5_GHZ,
            hostReceives = true,
        )
        assertEquals(payload, PairingPayload.parse(payload.toUri()))
    }

    @Test
    fun `ssid and passphrase survive characters that would break a query string`() {
        // WPA2 allows almost anything; an SSID with & or = in it must not be
        // able to smuggle extra parameters into the parsed payload.
        val payload = PairingPayload(
            ssid = "my&net=work #1",
            passphrase = "p=a&s s?w/o+rd",
            deviceName = "Ali's Phone",
        )
        val parsed = PairingPayload.parse(payload.toUri())
        assertEquals(payload.ssid, parsed.ssid)
        assertEquals(payload.passphrase, parsed.passphrase)
        assertEquals(payload.deviceName, parsed.deviceName)
    }

    @Test
    fun `host receives flag survives both ways`() {
        listOf(true, false).forEach { hostReceives ->
            val payload = PairingPayload("s", "passphrase", hostReceives = hostReceives)
            assertEquals(hostReceives, PairingPayload.parse(payload.toUri()).hostReceives)
        }
    }

    @Test
    fun `an unrelated barcode is rejected cleanly`() {
        // A scanner in a room full of QR codes will decode anything in frame.
        listOf(
            "https://example.com",
            "WIFI:S:HomeNet;T:WPA;P:hunter2;;",
            "",
            "offshare://",
            "offshare://v1",
        ).forEach { candidate ->
            assertFailsWith<TransferException>("accepted junk: $candidate") {
                PairingPayload.parse(candidate)
            }
        }
    }

    @Test
    fun `a pairing code from another protocol version is called out by name`() {
        val future = "offshare://v99?s=net&p=passphrase"
        val failure = assertFailsWith<TransferException> { PairingPayload.parse(future) }
        assertEquals(FailureReason.VERSION_MISMATCH, failure.reason)
        assertTrue(failure.message!!.contains("v99"))
    }

    @Test
    fun `a code missing credentials is rejected`() {
        assertFailsWith<TransferException> { PairingPayload.parse("offshare://v1?h=1.2.3.4") }
        assertFailsWith<TransferException> { PairingPayload.parse("offshare://v1?s=net") }
    }

    @Test
    fun `an out of range port falls back to the default`() {
        val parsed = PairingPayload.parse("offshare://v1?s=net&p=passphrase&t=99999")
        assertEquals(Protocol.DEFAULT_PORT, parsed.port)
    }

    @Test
    fun `generated passphrases are WPA2 legal and not repeated`() {
        val seen = (1..500).map { PassphraseGenerator.generate() }
        seen.forEach { passphrase ->
            assertEquals(12, passphrase.length)
            assertTrue(passphrase.all { it.code in 32..126 }, "non-ASCII in $passphrase")
        }
        assertTrue(seen.toSet().size > 490, "passphrase generator is not random enough")
    }

    @Test
    fun `passphrase length is bounded by what WPA2 accepts`() {
        assertFailsWith<IllegalArgumentException> { PassphraseGenerator.generate(7) }
        assertFailsWith<IllegalArgumentException> { PassphraseGenerator.generate(64) }
        assertEquals(63, PassphraseGenerator.generate(63).length)
    }

    @Test
    fun `discovery announcements round trip`() {
        val identity = DeviceIdentity("id-1", "Pixel 8", "android-35")
        val encoded = encodeAnnouncement(identity, 5330)
        val (decoded, port) = decodeAnnouncement(encoded, encoded.size)
        assertEquals(identity, decoded)
        assertEquals(5330, port)
    }

    @Test
    fun `stray udp traffic on the discovery port is ignored`() {
        val noise = "hello world, wrong protocol entirely".toByteArray()
        assertFailsWith<TransferException> { decodeAnnouncement(noise, noise.size) }
    }
}
