package dev.offshare.app.net

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.offshare.protocol.HotspotBand
import dev.offshare.protocol.PairingPayload
import dev.offshare.protocol.PassphraseGenerator
import dev.offshare.protocol.Protocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.random.Random

/** How the local network got created. */
enum class HotspotMethod {
    /**
     * A Wi-Fi Direct autonomous group. Preferred: it is the only route that
     * lets the app choose 5 GHz, and the group owner runs a plain WPA2 access
     * point, so the other device can join as an ordinary Wi-Fi client without
     * needing Wi-Fi Direct itself.
     */
    WIFI_DIRECT,

    /**
     * A local-only hotspot. Fallback for devices whose Wi-Fi Direct stack
     * refuses to form a group. Usually lands on 2.4 GHz, so expect a fraction
     * of the throughput.
     */
    LOCAL_ONLY_HOTSPOT,
}

sealed interface HotspotState {
    data object Idle : HotspotState

    data object Starting : HotspotState

    data class Active(
        val payload: PairingPayload,
        val method: HotspotMethod,
        val connectedClients: Int = 0,
    ) : HotspotState

    data class Failed(val message: String) : HotspotState
}

/**
 * Creates the Wi-Fi network that a transfer runs over.
 *
 * This is the piece that makes the app work with no router, no existing
 * Wi-Fi, and no internet: one device stands up an access point, the other
 * joins it, and both are on a private subnet for as long as the transfer
 * takes.
 *
 * Callers must hold the permissions in [HotspotPermissions] before calling
 * [start]; the Wi-Fi Direct APIs throw [SecurityException] otherwise.
 */
class HotspotHost(private val context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<HotspotState>(HotspotState.Idle)
    val state: StateFlow<HotspotState> = _state.asStateFlow()

    private var p2pManager: WifiP2pManager? = null
    private var p2pChannel: WifiP2pManager.Channel? = null
    private var hotspotReservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var connectionReceiver: BroadcastReceiver? = null

    /**
     * Brings up a network and returns what the peer needs to join it.
     *
     * @param hostReceives whether this device will be receiving files. Only
     *   affects what the QR code tells the other side to do; hosting the
     *   network and receiving files are independent roles.
     * @param preferFiveGigahertz ask for the 5 GHz band. Worth roughly 5-10x
     *   the throughput, but not every device or regulatory domain allows it,
     *   so a refusal falls back rather than failing.
     */
    suspend fun start(
        hostReceives: Boolean = true,
        preferFiveGigahertz: Boolean = true,
        port: Int = Protocol.DEFAULT_PORT,
    ): HotspotState {
        _state.value = HotspotState.Starting

        val viaDirect = runCatching {
            startWifiDirect(hostReceives, preferFiveGigahertz, port)
        }.getOrElse { error ->
            Log.w(TAG, "Wi-Fi Direct group failed", error)
            null
        }
        if (viaDirect != null) {
            _state.value = viaDirect
            watchGroupMembership()
            return viaDirect
        }

        val viaHotspot = runCatching {
            startLocalOnlyHotspot(hostReceives, port)
        }.getOrElse { error ->
            Log.w(TAG, "Local-only hotspot failed", error)
            null
        }

        val result = viaHotspot ?: HotspotState.Failed(
            "Could not create a Wi-Fi network. Check that Wi-Fi is switched on, " +
                "then try again.",
        )
        _state.value = result
        return result
    }

    // -- Wi-Fi Direct -----------------------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun startWifiDirect(
        hostReceives: Boolean,
        preferFiveGigahertz: Boolean,
        port: Int,
    ): HotspotState.Active? {
        val manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return null
        val channel = manager.initialize(appContext, Looper.getMainLooper(), null) ?: return null
        p2pManager = manager
        p2pChannel = channel

        // A group left over from a previous run -- or from another app -- makes
        // createGroup fail with BUSY. Clearing first is cheaper than parsing
        // the failure and retrying.
        removeGroupQuietly(manager, channel)

        val passphrase = PassphraseGenerator.generate()
        val networkName = generateNetworkName()

        val requestedBand =
            if (preferFiveGigahertz) HotspotBand.BAND_5_GHZ else HotspotBand.BAND_2_4_GHZ

        var created = createGroup(manager, channel, networkName, passphrase, requestedBand)
        var band = requestedBand

        if (!created && preferFiveGigahertz) {
            // 5 GHz is refused in some regulatory domains and on some chipsets.
            // A slower group beats no group.
            Log.i(TAG, "5 GHz group refused; retrying on the automatic band")
            removeGroupQuietly(manager, channel)
            band = HotspotBand.UNKNOWN
            created = createGroup(manager, channel, networkName, passphrase, band)
        }
        if (!created) return null

        val group = awaitGroupInfo(manager, channel) ?: run {
            removeGroupQuietly(manager, channel)
            return null
        }

        // Trust the group's own credentials over the ones we asked for: on
        // some devices the framework overrides the requested network name.
        val actualSsid = group.networkName ?: networkName
        val actualPassphrase = group.passphrase ?: passphrase

        return HotspotState.Active(
            payload = PairingPayload(
                ssid = actualSsid,
                passphrase = actualPassphrase,
                host = Protocol.WIFI_DIRECT_GROUP_OWNER_ADDRESS,
                port = port,
                deviceName = Build.MODEL ?: "Android",
                band = observedBand(group, band),
                hostReceives = hostReceives,
            ),
            method = HotspotMethod.WIFI_DIRECT,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun createGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        networkName: String,
        passphrase: String,
        band: HotspotBand,
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onFailure(reason: Int) {
                Log.w(TAG, "createGroup failed: ${describeP2pFailure(reason)}")
                if (continuation.isActive) continuation.resume(false)
            }
        }

        val config = WifiP2pConfig.Builder()
            .setNetworkName(networkName)
            .setPassphrase(passphrase)
            // Persistent groups linger across app restarts and are a common
            // source of BUSY failures later. This network exists for one
            // transfer; it should not outlive it.
            .enablePersistentMode(false)
            .apply {
                when (band) {
                    HotspotBand.BAND_5_GHZ ->
                        setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)

                    HotspotBand.BAND_2_4_GHZ ->
                        setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)

                    HotspotBand.UNKNOWN ->
                        setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO)
                }
            }
            .build()

        try {
            manager.createGroup(channel, config, listener)
        } catch (e: Exception) {
            Log.w(TAG, "createGroup threw", e)
            if (continuation.isActive) continuation.resume(false)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitGroupInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pGroup? = withTimeoutOrNull(GROUP_INFO_TIMEOUT_MILLIS) {
        // The group is not necessarily queryable the instant createGroup
        // reports success, and without credentials there is nothing to put in
        // the QR code, so poll briefly rather than racing it.
        repeat(GROUP_INFO_ATTEMPTS) { attempt ->
            val group = requestGroupInfo(manager, channel)
            if (group != null && group.isGroupOwner && group.networkName != null) {
                return@withTimeoutOrNull group
            }
            delay(GROUP_INFO_POLL_MILLIS)
            Log.d(TAG, "Waiting for group info (attempt ${attempt + 1})")
        }
        null
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestGroupInfo(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ): WifiP2pGroup? = suspendCancellableCoroutine { continuation ->
        try {
            manager.requestGroupInfo(channel) { group ->
                if (continuation.isActive) continuation.resume(group)
            }
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resume(null)
        }
    }

    /**
     * Refines the reported band from the channel the group actually landed on.
     *
     * The requested band is only a request; the framework may place the group
     * elsewhere. Showing the user "5 GHz" when they are on a 2.4 GHz channel
     * would make a slow transfer look like a bug in the app.
     */
    private fun observedBand(group: WifiP2pGroup, requested: HotspotBand): HotspotBand =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                when (group.frequency) {
                    0 -> requested
                    in 4900..5900 -> HotspotBand.BAND_5_GHZ
                    in 2400..2500 -> HotspotBand.BAND_2_4_GHZ
                    else -> requested
                }
            }.getOrDefault(requested)
        } else {
            requested
        }

    /** Notes when a peer joins or leaves, so the UI can say "1 device connected". */
    @SuppressLint("MissingPermission")
    private fun watchGroupMembership() {
        if (connectionReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                val manager = p2pManager ?: return
                val channel = p2pChannel ?: return
                runCatching {
                    manager.requestGroupInfo(channel) { group ->
                        val current = _state.value
                        if (current is HotspotState.Active) {
                            _state.value =
                                current.copy(connectedClients = group?.clientList?.size ?: 0)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
        connectionReceiver = receiver
    }

    // -- local-only hotspot fallback --------------------------------------

    @SuppressLint("MissingPermission")
    private suspend fun startLocalOnlyHotspot(
        hostReceives: Boolean,
        port: Int,
    ): HotspotState.Active? {
        val wifiManager =
            appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
        val result = CompletableDeferred<WifiManager.LocalOnlyHotspotReservation?>()

        try {
            wifiManager.startLocalOnlyHotspot(
                object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                        result.complete(reservation)
                    }

                    override fun onFailed(reason: Int) {
                        Log.w(TAG, "startLocalOnlyHotspot failed: $reason")
                        result.complete(null)
                    }

                    override fun onStopped() {
                        result.complete(null)
                    }
                },
                mainHandler,
            )
        } catch (e: Exception) {
            Log.w(TAG, "startLocalOnlyHotspot threw", e)
            return null
        }

        val reservation = withTimeoutOrNull(HOTSPOT_TIMEOUT_MILLIS) { result.await() } ?: return null
        hotspotReservation = reservation

        val credentials = readHotspotCredentials(reservation) ?: run {
            runCatching { reservation.close() }
            return null
        }

        return HotspotState.Active(
            payload = PairingPayload(
                ssid = credentials.first,
                passphrase = credentials.second,
                host = Protocol.LOCAL_ONLY_HOTSPOT_ADDRESS,
                port = port,
                deviceName = Build.MODEL ?: "Android",
                // This path gives no say over the band and almost always
                // lands on 2.4 GHz, so claiming otherwise would mislead.
                band = HotspotBand.UNKNOWN,
                hostReceives = hostReceives,
            ),
            method = HotspotMethod.LOCAL_ONLY_HOTSPOT,
        )
    }

    /**
     * Pulls SSID and passphrase out of a reservation.
     *
     * Two shapes exist across versions and the newer getters are not reliably
     * public on every OEM build, so both are attempted before giving up.
     */
    @Suppress("DEPRECATION")
    private fun readHotspotCredentials(
        reservation: WifiManager.LocalOnlyHotspotReservation,
    ): Pair<String, String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val config = reservation.softApConfiguration
                val ssid = config.ssid
                val passphrase = config.passphrase
                if (!ssid.isNullOrBlank() && !passphrase.isNullOrBlank()) {
                    return ssid.trim('"') to passphrase
                }
            }
        }
        runCatching {
            val config = reservation.wifiConfiguration
            val ssid = config?.SSID
            val passphrase = config?.preSharedKey
            if (!ssid.isNullOrBlank() && !passphrase.isNullOrBlank()) {
                return ssid.trim('"') to passphrase.trim('"')
            }
        }
        return null
    }

    // -- teardown ---------------------------------------------------------

    /**
     * Tears the network down.
     *
     * Worth being thorough about: a Wi-Fi Direct group left running keeps the
     * radio in a high-power state and blocks the next createGroup with BUSY,
     * so a leak here shows up as "the app worked once".
     */
    @SuppressLint("MissingPermission")
    fun stop() {
        connectionReceiver?.let { receiver ->
            runCatching { appContext.unregisterReceiver(receiver) }
            connectionReceiver = null
        }

        val manager = p2pManager
        val channel = p2pChannel
        if (manager != null && channel != null) {
            runCatching { manager.removeGroup(channel, null) }
            // Channel.close() exists from API 27 and minSdk is 29, so no
            // version guard is needed here.
            runCatching { channel.close() }
        }
        p2pManager = null
        p2pChannel = null

        hotspotReservation?.let { reservation ->
            runCatching { reservation.close() }
            hotspotReservation = null
        }

        _state.value = HotspotState.Idle
    }

    @SuppressLint("MissingPermission")
    private suspend fun removeGroupQuietly(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
    ) {
        runCatching {
            suspendCancellableCoroutine { continuation ->
                manager.removeGroup(
                    channel,
                    object : WifiP2pManager.ActionListener {
                        override fun onSuccess() {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onFailure(reason: Int) {
                            // No group to remove is the common case, not an error.
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                    },
                )
            }
        }
        delay(GROUP_TEARDOWN_SETTLE_MILLIS)
    }

    private fun generateNetworkName(): String {
        // The P2P spec requires the "DIRECT-xy" prefix; the readable suffix is
        // what the user sees in their Wi-Fi list if they ever join by hand.
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val suffix = (1..2).map { alphabet[Random.nextInt(alphabet.length)] }.joinToString("")
        return "DIRECT-$suffix-OfflineShare"
    }

    private fun describeP2pFailure(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this device"
        WifiP2pManager.BUSY -> "the Wi-Fi Direct stack is busy"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "no service requests"
        WifiP2pManager.ERROR -> "internal error"
        else -> "unknown reason $reason"
    }

    private companion object {
        const val TAG = "HotspotHost"
        const val GROUP_INFO_TIMEOUT_MILLIS = 10_000L
        const val GROUP_INFO_ATTEMPTS = 20
        const val GROUP_INFO_POLL_MILLIS = 400L
        const val GROUP_TEARDOWN_SETTLE_MILLIS = 400L
        const val HOTSPOT_TIMEOUT_MILLIS = 15_000L
    }
}

/** The runtime permissions [HotspotHost] and [HotspotGuest] need. */
object HotspotPermissions {

    /** Permissions required to create or join a network on this OS version. */
    fun required(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Pre-13, Wi-Fi Direct is treated as a location capability and
            // silently returns nothing without this. Both have to be asked
            // for together: since Android 12 the system prompt offers the
            // user a COARSE-only choice, and requesting FINE alone is
            // rejected. Discovery needs FINE, so a COARSE-only grant leaves
            // the Wi-Fi Direct path unavailable and we fall back.
            add(android.Manifest.permission.ACCESS_FINE_LOCATION)
            add(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    /** Additionally needed to scan a pairing QR code. */
    fun camera(): List<String> = listOf(android.Manifest.permission.CAMERA)
}
