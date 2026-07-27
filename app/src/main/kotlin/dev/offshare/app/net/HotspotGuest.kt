package dev.offshare.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.util.Log
import dev.offshare.protocol.PairingPayload
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Socket

sealed interface JoinState {
    data object Idle : JoinState

    /** Waiting on the system's "connect to this network?" dialog. */
    data object Requesting : JoinState

    data class Joined(val network: Network, val payload: PairingPayload) : JoinState

    data class Failed(val message: String) : JoinState
}

/**
 * Joins the network a [HotspotHost] created, on the other device.
 *
 * Uses [WifiNetworkSpecifier], which connects for this app only and hands
 * back a [Network] handle. That matters for two reasons beyond convenience:
 * the user is not sent to Settings to type a passphrase, and the connection
 * is torn down automatically when the app releases it, so the phone goes back
 * to its normal Wi-Fi afterwards instead of clinging to a dead hotspot.
 */
class HotspotGuest(context: Context) {

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<JoinState>(JoinState.Idle)
    val state: StateFlow<JoinState> = _state.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Connects to the hotspot described by a scanned pairing code.
     *
     * Shows a system dialog the user has to accept, so the timeout is
     * generous -- it is bounded by how fast someone taps, not by the radio.
     */
    suspend fun join(payload: PairingPayload, timeoutMillis: Long = 60_000): JoinState {
        leave()
        _state.value = JoinState.Requesting

        val connected = CompletableDeferred<Network?>()

        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(payload.ssid)
            .setWpa2Passphrase(payload.passphrase)
            .build()

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            // This network has no internet and never will. Without removing
            // the capability the request is never satisfied, and the join
            // hangs until it times out with no explanation.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!connected.isCompleted) connected.complete(network)
            }

            override fun onUnavailable() {
                if (!connected.isCompleted) connected.complete(null)
            }

            override fun onLost(network: Network) {
                // The hotspot went away mid-transfer, or the host stopped it.
                if (_state.value is JoinState.Joined) {
                    _state.value = JoinState.Failed("The hotspot disconnected.")
                }
            }
        }
        callback = networkCallback

        val result = try {
            connectivityManager.requestNetwork(request, networkCallback)
            val network = withTimeoutOrNull(timeoutMillis) { connected.await() }

            if (network == null) {
                leave()
                JoinState.Failed(
                    "Could not join \"${payload.ssid}\". Make sure the other device is still " +
                        "showing its code, then try again.",
                )
            } else {
                bindProcessTo(network)
                JoinState.Joined(network, payload)
            }
        } catch (e: SecurityException) {
            leave()
            JoinState.Failed("Permission to join Wi-Fi networks was denied.")
        }

        _state.value = result
        return result
    }

    /**
     * Routes this process's sockets over the hotspot.
     *
     * The single most important line in the class. A network joined through a
     * [WifiNetworkSpecifier] never becomes the system default, because it has
     * no internet -- so an unbound socket goes out over cellular or whatever
     * Wi-Fi the phone was already on, and the connection to 192.168.49.1 fails
     * with no route to host. Binding the process makes every socket, including
     * the ones the transfer engine opens internally, use this network.
     */
    private fun bindProcessTo(network: Network) {
        val bound = connectivityManager.bindProcessToNetwork(network)
        if (!bound) {
            Log.w(TAG, "bindProcessToNetwork was refused; sockets may take the wrong route")
        }
    }

    /**
     * Binds a single socket to the hotspot.
     *
     * Belt-and-braces for callers that create sockets before the process-wide
     * binding is in place.
     */
    fun bindSocket(socket: Socket) {
        val network = (_state.value as? JoinState.Joined)?.network ?: return
        runCatching { network.bindSocket(socket) }
    }

    /** Disconnects and hands the phone back to its normal network. */
    fun leave() {
        runCatching { connectivityManager.bindProcessToNetwork(null) }
        callback?.let { active ->
            runCatching { connectivityManager.unregisterNetworkCallback(active) }
            callback = null
        }
        _state.value = JoinState.Idle
    }

    private companion object {
        const val TAG = "HotspotGuest"
    }
}
