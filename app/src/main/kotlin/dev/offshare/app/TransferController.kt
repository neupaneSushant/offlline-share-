package dev.offshare.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import dev.offshare.app.net.AndroidFiles
import dev.offshare.app.net.HotspotHost
import dev.offshare.app.net.HotspotGuest
import dev.offshare.app.net.HotspotState
import dev.offshare.app.net.JoinState
import dev.offshare.app.service.TransferNotifications
import dev.offshare.app.service.TransferService
import dev.offshare.protocol.DeviceIdentity
import dev.offshare.protocol.DirectoryFileSinkFactory
import dev.offshare.protocol.DiscoveredPeer
import dev.offshare.protocol.DiscoveryBeacon
import dev.offshare.protocol.DiscoveryScanner
import dev.offshare.protocol.FileDecision
import dev.offshare.protocol.FileMeta
import dev.offshare.protocol.FileSource
import dev.offshare.protocol.PairingPayload
import dev.offshare.protocol.Protocol
import dev.offshare.protocol.TransferClient
import dev.offshare.protocol.TransferConfig
import dev.offshare.protocol.TransferException
import dev.offshare.protocol.TransferListener
import dev.offshare.protocol.TransferServer
import dev.offshare.protocol.TransferSummary
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress

/** Where the flow currently is, for the UI to render. */
sealed interface Phase {
    data object Idle : Phase

    data object PreparingNetwork : Phase

    /** Hotspot is up; showing a pairing code and waiting for the other device. */
    data class WaitingForPeer(
        val payload: PairingPayload,
        val connectedClients: Int,
        val hotspotMethod: String,
    ) : Phase

    data object Joining : Phase

    data class AwaitingApproval(val peer: DeviceIdentity, val files: List<FileMeta>) : Phase

    data class Transferring(
        val peer: DeviceIdentity?,
        val bytesCompleted: Long,
        val bytesTotal: Long,
        val bytesPerSecond: Long,
    ) : Phase {
        val fraction: Float
            get() = if (bytesTotal <= 0) 0f else (bytesCompleted.toFloat() / bytesTotal)
    }

    data class Finished(val summary: TransferSummary, val destination: String?) : Phase

    data class Error(val message: String) : Phase
}

/**
 * Orchestrates a whole exchange: bring up a network, find the peer, move the
 * files, tear everything down.
 *
 * Hosting the hotspot and receiving the files are separate decisions here.
 * The common case is that the receiver hosts -- it has somewhere to put the
 * files and nothing to pick first -- but a laptop or a phone whose Wi-Fi
 * Direct stack refuses to form a group can be the guest and still receive,
 * with the sender hosting instead.
 */
class TransferController(
    private val context: Context,
    private val scope: CoroutineScope,
) {

    private val appContext = context.applicationContext
    val hotspotHost = HotspotHost(appContext)
    val hotspotGuest = HotspotGuest(appContext)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val identity: DeviceIdentity = loadIdentity()
    private val config = TransferConfig()

    private var server: TransferServer? = null
    private var client: TransferClient? = null
    private var beacon: DiscoveryBeacon? = null
    private var scanner: DiscoveryScanner? = null

    /** Set when an incoming offer is waiting on the user. */
    private var pendingApproval: CompletableDeferred<Boolean>? = null

    // -- receiving --------------------------------------------------------

    /**
     * Hosts a network and waits for someone to send files to it.
     *
     * The server starts before the hotspot is advertised, so a peer that joins
     * the instant the QR appears cannot beat the listener to it.
     */
    fun hostAndReceive(autoAccept: Boolean = false) {
        scope.launch {
            try {
                _phase.value = Phase.PreparingNetwork
                startServer(autoAccept)

                when (val state = hotspotHost.start(hostReceives = true, port = Protocol.DEFAULT_PORT)) {
                    is HotspotState.Active -> {
                        _phase.value = Phase.WaitingForPeer(
                            payload = state.payload,
                            connectedClients = state.connectedClients,
                            hotspotMethod = state.method.name,
                        )
                        observeHotspot()
                    }

                    is HotspotState.Failed -> {
                        stopEverything()
                        _phase.value = Phase.Error(state.message)
                    }

                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "hostAndReceive failed", e)
                stopEverything()
                _phase.value = Phase.Error(e.friendlyMessage())
            }
        }
    }

    /**
     * Joins a hosted network and receives files onto this device.
     *
     * The guest cannot be dialled directly -- the host has no way to learn its
     * address -- so it announces itself over UDP broadcast and lets the host
     * find it.
     */
    fun joinAndReceive(payload: PairingPayload, autoAccept: Boolean = false) {
        scope.launch {
            try {
                _phase.value = Phase.Joining
                when (val joined = hotspotGuest.join(payload)) {
                    is JoinState.Joined -> {
                        val port = startServer(autoAccept)
                        beacon = DiscoveryBeacon(identity, port).also { it.start() }
                        _phase.value = Phase.Transferring(null, 0, 0, 0)
                    }

                    is JoinState.Failed -> _phase.value = Phase.Error(joined.message)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "joinAndReceive failed", e)
                stopEverything()
                _phase.value = Phase.Error(e.friendlyMessage())
            }
        }
    }

    // -- sending ----------------------------------------------------------

    /** Joins a hosted network and sends [uris] to whoever is hosting it. */
    fun joinAndSend(payload: PairingPayload, uris: List<Uri>) {
        scope.launch {
            try {
                _phase.value = Phase.Joining
                when (val joined = hotspotGuest.join(payload)) {
                    is JoinState.Joined -> {
                        val target = InetSocketAddress(payload.host, payload.port)
                        runSend(target, uris)
                    }

                    is JoinState.Failed -> _phase.value = Phase.Error(joined.message)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "joinAndSend failed", e)
                _phase.value = Phase.Error(e.friendlyMessage())
            } finally {
                hotspotGuest.leave()
            }
        }
    }

    /**
     * Hosts a network and sends to whoever joins.
     *
     * For receivers that cannot host: laptops, or phones whose Wi-Fi Direct
     * stack will not form a group. The host has no address for the guest, so
     * it waits for the guest's discovery announcement before connecting.
     */
    fun hostAndSend(uris: List<Uri>) {
        scope.launch {
            try {
                _phase.value = Phase.PreparingNetwork
                when (val state = hotspotHost.start(hostReceives = false)) {
                    is HotspotState.Active -> {
                        _phase.value = Phase.WaitingForPeer(
                            payload = state.payload,
                            connectedClients = state.connectedClients,
                            hotspotMethod = state.method.name,
                        )
                        val peer = awaitPeerAnnouncement()
                        if (peer == null) {
                            _phase.value = Phase.Error(
                                "No device joined in time. Make sure the other phone scanned " +
                                    "the code and chose Receive.",
                            )
                            return@launch
                        }
                        runSend(peer.socketAddress, uris)
                    }

                    is HotspotState.Failed -> _phase.value = Phase.Error(state.message)
                    else -> Unit
                }
            } catch (e: Exception) {
                Log.e(TAG, "hostAndSend failed", e)
                _phase.value = Phase.Error(e.friendlyMessage())
            } finally {
                stopEverything()
            }
        }
    }

    private suspend fun runSend(target: InetSocketAddress, uris: List<Uri>) {
        val sources: List<FileSource> = withContext(Dispatchers.IO) {
            uris.mapIndexed { index, uri -> AndroidFiles.sourceFor(appContext, uri, index + 1) }
        }
        val totalBytes = sources.sumOf { it.meta.size }
        _phase.value = Phase.Transferring(null, 0, totalBytes, 0)

        val transferClient = TransferClient(identity, config, progressListener())
        client = transferClient

        val summary = withContext(Dispatchers.IO) { transferClient.send(target, sources) }
        _phase.value = Phase.Finished(summary, destination = null)
    }

    // -- shared plumbing --------------------------------------------------

    private fun startServer(autoAccept: Boolean): Int {
        stopServer()
        val destination = AndroidFiles.downloadDirectory(appContext)
        val transferServer = TransferServer(
            identity = identity,
            sinkFactory = DirectoryFileSinkFactory(destination),
            approver = { peer, files ->
                val accepted = if (autoAccept) true else askUser(peer, files)
                files.map { FileDecision(it.id, accepted = accepted) }
            },
            config = config,
            listener = progressListener(destination.absolutePath),
        )
        val port = transferServer.start(port = Protocol.DEFAULT_PORT)
        server = transferServer
        return port
    }

    /**
     * Blocks the approving thread until the user answers.
     *
     * Called on a server worker thread, never the main thread: the protocol
     * holds the sender at the offer step until this returns, which is exactly
     * the behaviour we want -- no bytes move before consent.
     */
    private fun askUser(peer: DeviceIdentity, files: List<FileMeta>): Boolean {
        val gate = CompletableDeferred<Boolean>()
        pendingApproval = gate
        _phase.value = Phase.AwaitingApproval(peer, files)
        return runCatching {
            kotlinx.coroutines.runBlocking {
                withTimeoutOrNull(APPROVAL_TIMEOUT_MILLIS) { gate.await() } ?: false
            }
        }.getOrDefault(false)
    }

    /** Answers a pending [Phase.AwaitingApproval]. */
    fun respondToOffer(accept: Boolean) {
        pendingApproval?.complete(accept)
        pendingApproval = null
    }

    private fun progressListener(destination: String? = null) = object : TransferListener {
        override fun onSessionStarted(peer: DeviceIdentity, files: List<FileMeta>) {
            _phase.value = Phase.Transferring(peer, 0, files.sumOf { it.size }, 0)
            // The transfer is now long-running and must outlive the screen.
            TransferService.start(appContext, notificationTitle(destination))
        }

        override fun onProgress(bytesCompleted: Long, bytesTotal: Long, bytesPerSecond: Long) {
            val peer = (_phase.value as? Phase.Transferring)?.peer
            _phase.value = Phase.Transferring(peer, bytesCompleted, bytesTotal, bytesPerSecond)
            TransferNotifications.postProgress(
                appContext,
                notificationTitle(destination),
                bytesCompleted,
                bytesTotal,
                bytesPerSecond,
            )
        }

        override fun onSessionFinished(summary: TransferSummary) {
            _phase.value = Phase.Finished(summary, destination)
            // Releases the Wi-Fi and CPU locks; leaving them held would drain
            // the battery long after the transfer is over.
            TransferService.stop(appContext)
        }

        override fun onError(error: TransferException) {
            Log.w(TAG, "transfer error: ${error.reason}", error)
        }
    }

    private fun notificationTitle(destination: String?): String =
        if (destination != null) "Receiving files" else "Sending files"

    /** Waits for a guest to announce itself over UDP after joining. */
    private suspend fun awaitPeerAnnouncement(): DiscoveredPeer? {
        val found = CompletableDeferred<DiscoveredPeer>()
        val discoveryScanner = DiscoveryScanner(ignoreDeviceId = identity.deviceId) { peer ->
            if (!found.isCompleted) found.complete(peer)
        }
        scanner = discoveryScanner
        discoveryScanner.start()
        return withTimeoutOrNull(PEER_DISCOVERY_TIMEOUT_MILLIS) { found.await() }
            .also { discoveryScanner.close(); scanner = null }
    }

    private fun observeHotspot() {
        scope.launch {
            hotspotHost.state.collect { state ->
                val current = _phase.value
                if (state is HotspotState.Active && current is Phase.WaitingForPeer) {
                    _phase.value = current.copy(connectedClients = state.connectedClients)
                }
            }
        }
    }

    /** Cancels anything in flight and returns the device to its normal state. */
    fun stopEverything() {
        TransferService.stop(appContext)
        runCatching { client?.cancel() }
        client = null
        stopServer()
        runCatching { beacon?.close() }
        beacon = null
        runCatching { scanner?.close() }
        scanner = null
        pendingApproval?.complete(false)
        pendingApproval = null
        hotspotGuest.leave()
        hotspotHost.stop()
    }

    fun reset() {
        stopEverything()
        _phase.value = Phase.Idle
    }

    private fun stopServer() {
        runCatching { server?.close() }
        server = null
    }

    private fun loadIdentity(): DeviceIdentity {
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val id = prefs.getString(KEY_DEVICE_ID, null) ?: DeviceIdentity.randomId().also {
            prefs.edit().putString(KEY_DEVICE_ID, it).apply()
        }
        val name = prefs.getString(KEY_DEVICE_NAME, null)
            ?: listOfNotNull(Build.MANUFACTURER, Build.MODEL)
                .joinToString(" ")
                .ifBlank { "Android device" }
        return DeviceIdentity(
            deviceId = id,
            deviceName = name,
            platform = "android-${Build.VERSION.SDK_INT}",
        )
    }

    private fun Exception.friendlyMessage(): String = when {
        this is TransferException -> message ?: "Transfer failed."
        this is SecurityException ->
            "OfflineShare needs the nearby-devices permission to create a Wi-Fi network."

        else -> message ?: "Something went wrong."
    }

    private companion object {
        const val TAG = "TransferController"
        const val PREFS = "offlineshare"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_DEVICE_NAME = "device_name"
        const val APPROVAL_TIMEOUT_MILLIS = 120_000L
        const val PEER_DISCOVERY_TIMEOUT_MILLIS = 120_000L
    }
}
