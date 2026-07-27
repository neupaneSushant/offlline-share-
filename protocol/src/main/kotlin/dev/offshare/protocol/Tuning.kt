package dev.offshare.protocol

import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.StandardSocketOptions
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicLong

/**
 * Socket tuning for a short, fat, private link.
 *
 * The default 64 KiB receive buffer is the single most common reason a
 * "gigabit-capable" transfer tops out around 100 Mbit/s. TCP cannot have more
 * bytes in flight than the receive window, so throughput is capped at
 * `window / RTT` no matter what the radio can do. A 5 GHz Wi-Fi Direct link
 * doing ~600 Mbit/s at 3 ms RTT needs roughly 225 KiB in flight; asking for
 * 2 MiB leaves comfortable headroom for the retransmit stalls that Wi-Fi
 * inevitably produces.
 */
internal object Tuning {

    /**
     * Applies the receive buffer to a listening socket.
     *
     * This has to happen before bind: the window scale factor is negotiated in
     * the SYN handshake, so a buffer size set on an already-accepted socket
     * cannot grow the window past 64 KiB.
     */
    fun prepareListener(channel: ServerSocketChannel, config: TransferConfig) {
        runCatching {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, config.socketBufferBytes)
        }
        runCatching {
            channel.setOption(StandardSocketOptions.SO_REUSEADDR, true)
        }
    }

    /** Same, for the plain-socket API. */
    fun prepareListener(socket: ServerSocket, config: TransferConfig) {
        runCatching { socket.receiveBufferSize = config.socketBufferBytes }
        runCatching { socket.reuseAddress = true }
    }

    /**
     * Applies per-connection tuning.
     *
     * @param bulk true for payload streams, false for the control channel.
     *   Control frames are tiny and latency-sensitive, so Nagle's algorithm is
     *   disabled there to stop the handshake stalling ~40 ms per round trip.
     *   Bulk streams keep Nagle on: they write in multi-megabyte units, so
     *   coalescing costs nothing and avoids dribbling out undersized segments.
     */
    fun prepareConnection(channel: SocketChannel, config: TransferConfig, bulk: Boolean) {
        runCatching {
            channel.setOption(StandardSocketOptions.SO_RCVBUF, config.socketBufferBytes)
        }
        runCatching {
            channel.setOption(StandardSocketOptions.SO_SNDBUF, config.socketBufferBytes)
        }
        runCatching {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, !bulk)
        }
        runCatching {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, true)
        }
    }

    /** Sets a read timeout, tolerating platforms that refuse. */
    fun applyTimeout(socket: Socket, millis: Int) {
        try {
            socket.soTimeout = millis
        } catch (_: SocketException) {
            // Non-fatal: we simply fall back to blocking until the peer closes.
        }
    }
}

/**
 * Exponentially-weighted throughput estimate.
 *
 * A plain "total bytes / total elapsed" average is useless in a progress bar:
 * it barely moves once a transfer has been running a while, so a stall reads
 * as "still fast". Smoothing over a short window tracks what the link is
 * doing right now, which is what a user watching a spinner actually wants.
 */
class RateMeter(
    private val smoothing: Double = 0.25,
    private val clock: () -> Long = System::nanoTime,
) {
    private val totalBytes = AtomicLong(0)
    private var lastSampleNanos = clock()
    private var lastSampleBytes = 0L
    private var smoothedBytesPerSecond = 0.0

    fun add(bytes: Long) {
        totalBytes.addAndGet(bytes)
    }

    fun total(): Long = totalBytes.get()

    /**
     * Folds the bytes seen since the previous call into the estimate.
     * Intended to be called on a timer, roughly a few times a second.
     */
    @Synchronized
    fun sample(): Long {
        val now = clock()
        val elapsedNanos = now - lastSampleNanos
        if (elapsedNanos < MIN_SAMPLE_NANOS) return smoothedBytesPerSecond.toLong()

        val current = totalBytes.get()
        val deltaBytes = current - lastSampleBytes
        val instantaneous = deltaBytes * NANOS_PER_SECOND / elapsedNanos.toDouble()

        smoothedBytesPerSecond =
            if (smoothedBytesPerSecond == 0.0) instantaneous
            else smoothedBytesPerSecond * (1 - smoothing) + instantaneous * smoothing

        lastSampleNanos = now
        lastSampleBytes = current
        return smoothedBytesPerSecond.toLong()
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000.0
        const val MIN_SAMPLE_NANOS = 50_000_000L // 50 ms
    }
}

/** Formats a byte count for display, e.g. "1.4 GB". */
fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.lastIndex) {
        value /= 1024
        unitIndex++
    }
    return if (value >= 100) "%.0f %s".format(value, units[unitIndex])
    else "%.1f %s".format(value, units[unitIndex])
}

/** Formats a rate for display, e.g. "42.3 MB/s". */
fun formatRate(bytesPerSecond: Long): String =
    if (bytesPerSecond <= 0) "--" else "${formatBytes(bytesPerSecond)}/s"
