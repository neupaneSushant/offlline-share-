package dev.offshare.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import dev.offshare.app.net.HotspotPermissions

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Results are re-checked at the point of use. */ }

    private val pickFiles = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        // Persist read access: the picker's grant would otherwise expire when
        // the activity is recreated, which for a long transfer is a real risk.
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        viewModel.onFilesSelected(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestPermissions.launch(
            (HotspotPermissions.required() + HotspotPermissions.camera()).toTypedArray(),
        )

        handleShareIntent(intent)

        setContent {
            OfflineShareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    val screen by viewModel.screen.collectAsState()
                    val phase by viewModel.phase.collectAsState()
                    val files by viewModel.selectedFiles.collectAsState()
                    val scanError by viewModel.scanError.collectAsState()

                    OfflineShareApp(
                        screen = screen,
                        phase = phase,
                        selectedFiles = files,
                        scanError = scanError,
                        onReceive = viewModel::startReceiving,
                        onSend = { pickFiles.launch(arrayOf("*/*")) },
                        onQrScanned = viewModel::onQrScanned,
                        onDismissScanError = viewModel::dismissScanError,
                        onRespondToOffer = viewModel::respondToOffer,
                        onCancel = viewModel::cancel,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    /** Picks up files sent here from another app's share sheet. */
    private fun handleShareIntent(intent: Intent?) {
        val uris = when (intent?.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(
                    IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM),
                )

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM)

            else -> null
        } ?: return

        if (uris.isNotEmpty()) viewModel.onFilesSelected(uris)
    }
}

/**
 * Typed extras without the deprecated raw calls.
 *
 * androidx.core.content.IntentCompat covers this from a recent enough core-ktx,
 * but spelling it out keeps the minimum dependency surface small and makes the
 * API-33 split explicit.
 */
private object IntentCompat {

    fun getParcelableExtra(intent: Intent, name: String): android.net.Uri? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(name, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(name)
        }

    fun getParcelableArrayListExtra(intent: Intent, name: String): List<android.net.Uri>? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(name, android.net.Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableArrayListExtra(name)
        }
}
