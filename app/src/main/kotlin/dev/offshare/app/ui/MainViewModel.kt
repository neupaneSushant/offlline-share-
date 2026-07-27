package dev.offshare.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.offshare.app.Phase
import dev.offshare.app.TransferController
import dev.offshare.protocol.PairingPayload
import dev.offshare.protocol.TransferException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which screen the user is looking at. */
enum class Screen { HOME, RECEIVE, SCAN, TRANSFER }

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val controller = TransferController(application, viewModelScope)

    val phase: StateFlow<Phase> = controller.phase

    private val _screen = MutableStateFlow(Screen.HOME)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _selectedFiles = MutableStateFlow<List<Uri>>(emptyList())
    val selectedFiles: StateFlow<List<Uri>> = _selectedFiles.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    /** Start receiving: host a network and show a pairing code. */
    fun startReceiving() {
        _screen.value = Screen.RECEIVE
        controller.hostAndReceive()
    }

    /** Files chosen from the picker or handed over by the share sheet. */
    fun onFilesSelected(uris: List<Uri>) {
        _selectedFiles.value = uris
        _screen.value = if (uris.isEmpty()) Screen.HOME else Screen.SCAN
    }

    /**
     * Handles a scanned QR code.
     *
     * A scanner will happily decode any barcode in frame, so an unreadable
     * code is a normal event to report back to the user, not a crash.
     */
    fun onQrScanned(text: String) {
        val payload = try {
            PairingPayload.parse(text)
        } catch (e: TransferException) {
            _scanError.value = e.message
            return
        }
        _scanError.value = null
        _screen.value = Screen.TRANSFER

        if (payload.hostReceives) {
            controller.joinAndSend(payload, _selectedFiles.value)
        } else {
            // The host is the one sending, so this device joins and listens.
            controller.joinAndReceive(payload)
        }
    }

    fun dismissScanError() {
        _scanError.value = null
    }

    fun respondToOffer(accept: Boolean) = controller.respondToOffer(accept)

    fun cancel() {
        controller.reset()
        _selectedFiles.value = emptyList()
        _screen.value = Screen.HOME
    }

    override fun onCleared() {
        // Leaving a Wi-Fi Direct group running would keep the radio hot and
        // block the next transfer from forming a group at all.
        controller.stopEverything()
        super.onCleared()
    }
}
