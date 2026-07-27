package dev.offshare.app.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.offshare.app.Phase
import dev.offshare.app.qr.QrAnalyzer
import dev.offshare.app.qr.QrEncoder
import dev.offshare.protocol.HotspotBand
import dev.offshare.protocol.PairingPayload
import dev.offshare.protocol.formatBytes
import dev.offshare.protocol.formatRate
import java.util.concurrent.Executors

@Composable
fun OfflineShareApp(
    screen: Screen,
    phase: Phase,
    selectedFiles: List<Uri>,
    scanError: String?,
    onReceive: () -> Unit,
    onSend: () -> Unit,
    onQrScanned: (String) -> Unit,
    onDismissScanError: () -> Unit,
    onRespondToOffer: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize().systemBarsPadding()) {
        when {
            phase is Phase.Error -> ErrorScreen(phase.message, onCancel)
            phase is Phase.Finished -> FinishedScreen(phase, onCancel)
            phase is Phase.Transferring -> TransferScreen(phase, onCancel)
            phase is Phase.Joining -> BusyScreen("Joining the other device's network…", onCancel)
            phase is Phase.PreparingNetwork -> BusyScreen("Starting a Wi-Fi network…", onCancel)
            phase is Phase.WaitingForPeer -> ReceiveScreen(phase, onCancel)
            screen == Screen.SCAN -> ScanScreen(selectedFiles.size, onQrScanned, onCancel)
            else -> HomeScreen(onReceive, onSend)
        }

        if (phase is Phase.AwaitingApproval) {
            OfferDialog(phase, onRespondToOffer)
        }
        scanError?.let { message ->
            AlertDialog(
                onDismissRequest = onDismissScanError,
                confirmButton = { TextButton(onClick = onDismissScanError) { Text("OK") } },
                title = { Text("Can't read that code") },
                text = { Text(message) },
            )
        }
    }
}

@Composable
private fun HomeScreen(onReceive: () -> Unit, onSend: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OfflineShare", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Send files at full Wi-Fi speed with no router, no mobile data, and no internet. " +
                "One phone creates the network, the other scans a code to join it.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(40.dp))

        Button(onClick = onSend, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Send files", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = onReceive, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text("Receive files", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Shows the pairing code the other device scans. */
@Composable
private fun ReceiveScreen(phase: Phase.WaitingForPeer, onCancel: () -> Unit) {
    val payload = phase.payload
    var qr by remember(payload) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(payload) {
        qr = runCatching { QrEncoder.encode(payload.toUri(), QR_SIZE_PX) }.getOrNull()
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Scan this to connect", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            if (phase.connectedClients > 0) {
                "${phase.connectedClients} device connected · waiting for files"
            } else {
                "Open OfflineShare on the other phone, tap Send, pick files, then scan."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        Box(
            Modifier
                .size(280.dp)
                .background(Color.White, RoundedCornerShape(12.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            qr?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Pairing QR code",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } ?: CircularProgressIndicator()
        }

        Spacer(Modifier.height(24.dp))
        ManualCredentials(payload, phase.hotspotMethod)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * The typed-in fallback.
 *
 * Worth keeping even though the QR is the happy path: a cracked camera, a
 * laptop with no scanner app, or a receiver that is not running this app at
 * all can still join the network by hand.
 */
@Composable
private fun ManualCredentials(payload: PairingPayload, method: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Or join manually", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(12.dp))
            CredentialRow("Network", payload.ssid)
            CredentialRow("Password", payload.passphrase)
            CredentialRow("Address", "${payload.host}:${payload.port}")
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                bandDescription(payload.band, method),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun bandDescription(band: HotspotBand, method: String): String = when {
    band == HotspotBand.BAND_5_GHZ ->
        "Running on 5 GHz — expect the fastest transfers. Keep the phones close together; " +
            "5 GHz does not travel as far through walls."

    band == HotspotBand.BAND_2_4_GHZ ->
        "Running on 2.4 GHz. This device or your region would not allow 5 GHz, so transfers " +
            "will be slower than the hardware can manage."

    method == "LOCAL_ONLY_HOTSPOT" ->
        "Using a local-only hotspot because Wi-Fi Direct was unavailable. The band is chosen " +
            "by the system and is usually 2.4 GHz."

    else -> "Band chosen automatically by the system."
}

@Composable
private fun CredentialRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(80.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Live camera preview that reports the first pairing code it sees. */
@Composable
private fun ScanScreen(fileCount: Int, onQrScanned: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val analyzer = remember { QrAnalyzer(onQrScanned) }

    DisposableEffect(Unit) {
        onDispose {
            analyzer.stop()
            executor.shutdown()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp)) {
            Text("Scan the other phone", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "$fileCount ${if (fileCount == 1) "file" else "files"} ready to send. " +
                    "Point the camera at the code on the receiving device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    val previewView = PreviewView(viewContext).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(viewContext)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            // Dropping stale frames keeps the decoder on what
                            // the camera sees now rather than a backlog.
                            .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST,
                            )
                            .build()
                            .also { it.setAnalyzer(executor, analyzer) }

                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }
                    }, androidx.core.content.ContextCompat.getMainExecutor(viewContext))
                    previewView
                },
            )
        }

        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun TransferScreen(phase: Phase.Transferring, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            phase.peer?.deviceName ?: "Connected",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(24.dp))

        if (phase.bytesTotal > 0) {
            LinearProgressIndicator(
                progress = { phase.fraction },
                modifier = Modifier.fillMaxWidth().height(8.dp),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(8.dp))
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "${formatBytes(phase.bytesCompleted)} of ${formatBytes(phase.bytesTotal)}",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            formatRate(phase.bytesPerSecond),
            style = MaterialTheme.typography.headlineMedium,
            fontFamily = FontFamily.Monospace,
        )

        Spacer(Modifier.height(40.dp))
        OutlinedButton(onClick = onCancel) { Text("Cancel transfer") }
    }
}

@Composable
private fun FinishedScreen(phase: Phase.Finished, onDone: () -> Unit) {
    val summary = phase.summary
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (summary.succeeded) "Transfer complete" else "Transfer failed",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))

        if (summary.succeeded) {
            Text(
                "${formatBytes(summary.bytesTransferred)} in " +
                    "${summary.elapsedMillis / 1000}s · average " +
                    formatRate(summary.bytesPerSecond),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            phase.destination?.let { destination ->
                Spacer(Modifier.height(12.dp))
                Text(
                    "Saved to $destination",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                summary.files.firstOrNull { !it.ok }?.error
                    ?: summary.failure?.name
                    ?: "Unknown error",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        Spacer(Modifier.height(40.dp))
        Button(onClick = onDone) { Text("Done") }
    }
}

@Composable
private fun OfferDialog(phase: Phase.AwaitingApproval, onRespond: (Boolean) -> Unit) {
    val total = phase.files.sumOf { it.size }
    AlertDialog(
        onDismissRequest = { onRespond(false) },
        title = { Text("Accept files?") },
        text = {
            Text(
                "${phase.peer.deviceName} wants to send ${phase.files.size} " +
                    "${if (phase.files.size == 1) "file" else "files"} " +
                    "(${formatBytes(total)}).",
            )
        },
        confirmButton = { TextButton(onClick = { onRespond(true) }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { onRespond(false) }) { Text("Decline") } },
    )
}

@Composable
private fun BusyScreen(message: String, onCancel: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(24.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
private fun ErrorScreen(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Something went wrong", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(40.dp))
        Button(onClick = onDismiss) { Text("Back") }
    }
}

private const val QR_SIZE_PX = 720
