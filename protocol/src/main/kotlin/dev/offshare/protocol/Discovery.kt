package dev.offshare.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** A peer heard on the local link. */
data class DiscoveredPeer(
    val identity: DeviceIdentity,
    val address: InetAddress,
    val port: Int,
    val lastSeenMillis: Long = System.currentTimeMillis(),
) {
    val socketAddress: InetSocketAddress get() = InetSocketAddress(address, port)
}

/**
 * UDP presence announcements on the hotspot subnet.
 *
 * A device that joined the Wi-Fi Direct group can skip this entirely -- the
 * group owner is always at [Protocol.WIFI_DIRECT_GROUP_OWNER_ADDRESS], so the
 * QR code already carries a working address. Discovery covers the cases the
 * QR code does not: a third device joining an existing group, reconnecting
 * after the app was killed, or a local-only hotspot whose gateway address
 * varies by vendor.
 *
 * Broadcast rather than multicast on purpose. Multicast needs a
 * `MulticastLock` on Android and is dropped outright by some Wi-Fi Direct
 * driver stacks; subnet broadcast works on everything that routes IP.
 */
class DiscoveryBeacon(
    private val identity: DeviceIdentity,
    private val port: Int,
    private val discoveryPort: Int = Protocol.DISCOVERY_PORT,
    private val intervalMillis: Long = 1_000L,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val datagramSocket = DatagramSocket().apply { broadcast = true }
        socket = datagramSocket

        thread = Thread {
            val payload = encodeAnnouncement(identity, port)
            while (running.get()) {
                broadcastTargets().forEach { target ->
                    runCatching {
                        datagramSocket.send(
                            DatagramPacket(payload, payload.size, target, discoveryPort),
                        )
                    }
                }
                try {
                    Thread.sleep(intervalMillis)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }.apply {
            isDaemon = true
            name = "ofsh-beacon"
            start()
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        runCatching { socket?.close() }
    }
}

/** Listens for [DiscoveryBeacon] announcements. */
class DiscoveryScanner(
    private val discoveryPort: Int = Protocol.DISCOVERY_PORT,
    private val ignoreDeviceId: String? = null,
    private val onPeer: (DiscoveredPeer) -> Unit,
) : Closeable {

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = SOCKET_TIMEOUT_MILLIS
            bind(InetSocketAddress(discoveryPort))
        }
        socket = datagramSocket

        thread = Thread {
            val buffer = ByteArray(MAX_PACKET_BYTES)
            while (running.get()) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    datagramSocket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                } catch (_: IOException) {
                    if (running.get()) continue else return@Thread
                }

                val announcement = runCatching {
                    decodeAnnouncement(packet.data, packet.length)
                }.getOrNull() ?: continue

                if (announcement.first.deviceId == ignoreDeviceId) continue
                onPeer(
                    DiscoveredPeer(
                        identity = announcement.first,
                        address = packet.address,
                        port = announcement.second,
                    ),
                )
            }
        }.apply {
            isDaemon = true
            name = "ofsh-scanner"
            start()
        }
    }

    override fun close() {
        running.set(false)
        thread?.interrupt()
        runCatching { socket?.close() }
    }

    private companion object {
        const val SOCKET_TIMEOUT_MILLIS = 500
        const val MAX_PACKET_BYTES = 1024
    }
}

/**
 * Broadcast addresses to announce on.
 *
 * 255.255.255.255 alone is unreliable: some Android builds drop it, and it
 * never leaves the primary interface. Enumerating each up interface's own
 * directed broadcast address reaches the hotspot subnet specifically, which
 * is the one that matters here.
 */
internal fun broadcastTargets(): List<InetAddress> {
    val targets = mutableListOf<InetAddress>()
    runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { networkInterface ->
            if (!networkInterface.isUp || networkInterface.isLoopback) return@forEach
            networkInterface.interfaceAddresses.forEach { address ->
                address.broadcast?.let(targets::add)
            }
        }
    }
    runCatching { targets.add(InetAddress.getByName("255.255.255.255")) }
    return targets.distinct()
}

private const val ANNOUNCE_MAGIC = 0x4F534441 // "OSDA"

internal fun encodeAnnouncement(identity: DeviceIdentity, port: Int): ByteArray {
    val buffer = ByteArrayOutputStream(128)
    DataOutputStream(buffer).apply {
        writeInt(ANNOUNCE_MAGIC)
        writeInt(Protocol.VERSION)
        writeInt(port)
        writeUTF(identity.deviceId)
        writeUTF(identity.deviceName)
        writeUTF(identity.platform)
        flush()
    }
    return buffer.toByteArray()
}

internal fun decodeAnnouncement(data: ByteArray, length: Int): Pair<DeviceIdentity, Int> {
    val input = DataInputStream(ByteArrayInputStream(data, 0, length))
    if (input.readInt() != ANNOUNCE_MAGIC) {
        throw TransferException(FailureReason.NETWORK, "Not a discovery announcement")
    }
    val version = input.readInt()
    if (version != Protocol.VERSION) {
        throw TransferException(
            FailureReason.VERSION_MISMATCH,
            "Announcement from protocol v$version",
        )
    }
    val port = input.readInt()
    if (port !in 1..65535) {
        throw TransferException(FailureReason.NETWORK, "Announcement has invalid port $port")
    }
    val identity = DeviceIdentity(
        deviceId = input.readUTF(),
        deviceName = input.readUTF(),
        platform = input.readUTF(),
    )
    return identity to port
}
