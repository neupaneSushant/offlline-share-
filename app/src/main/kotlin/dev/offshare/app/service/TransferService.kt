package dev.offshare.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.offshare.app.R
import dev.offshare.app.ui.MainActivity
import dev.offshare.protocol.formatBytes
import dev.offshare.protocol.formatRate

/**
 * Keeps a transfer alive while the screen is off, and stops the system
 * throttling the radio underneath it.
 *
 * Without this the OS is entitled to suspend the app seconds after the screen
 * goes dark, which turns a large transfer into one that only finishes if you
 * sit and watch it.
 */
class TransferService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.transfer_in_progress)
        startForeground(
            TransferNotifications.NOTIFICATION_ID,
            TransferNotifications.build(this, title, null, 0),
        )
        acquireLocks()
        return START_STICKY
    }

    /**
     * Takes the locks that keep throughput up with the screen off.
     *
     * The Wi-Fi lock is the one that matters. Android puts the Wi-Fi chip into
     * a power-saving mode when the screen is off, which parks the radio
     * between beacons and can cost most of the link's throughput.
     * WIFI_MODE_FULL_LOW_LATENCY holds it awake. It costs battery, which is
     * why it is scoped to an active transfer and released the moment one ends.
     */
    private fun acquireLocks() {
        if (wifiLock != null) return

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager
            .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, WIFI_LOCK_TAG)
            .apply {
                setReferenceCounted(false)
                runCatching { acquire() }
            }

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
            setReferenceCounted(false)
            // Bounded so a crashed transfer cannot hold the CPU awake forever.
            runCatching { acquire(MAX_LOCK_MILLIS) }
        }
        Log.d(TAG, "Acquired Wi-Fi and CPU locks for transfer")
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "dev.offshare.app.action.STOP"
        const val EXTRA_TITLE = "title"

        private const val TAG = "TransferService"
        private const val WIFI_LOCK_TAG = "OfflineShare:transfer"
        private const val WAKE_LOCK_TAG = "OfflineShare:transfer"
        private const val MAX_LOCK_MILLIS = 60L * 60L * 1000L

        fun start(context: Context, title: String) {
            runCatching {
                context.startForegroundService(
                    Intent(context, TransferService::class.java).putExtra(EXTRA_TITLE, title),
                )
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, TransferService::class.java)) }
        }
    }
}

/**
 * Builds and posts the ongoing-transfer notification.
 *
 * Kept out of the Service so progress can be posted from wherever the
 * transfer is actually running, without binding to the service just to reach
 * an instance method.
 */
object TransferNotifications {

    const val NOTIFICATION_ID = 42
    private const val CHANNEL_ID = "transfers"

    /** Updates the ongoing notification. Cheap enough to call a few times a second. */
    fun postProgress(
        context: Context,
        title: String,
        bytesCompleted: Long,
        bytesTotal: Long,
        bytesPerSecond: Long,
    ) {
        val progress = if (bytesTotal > 0) {
            ((bytesCompleted * 100) / bytesTotal).toInt().coerceIn(0, 100)
        } else {
            0
        }
        val detail = "${formatBytes(bytesCompleted)} of ${formatBytes(bytesTotal)}" +
            "  ·  ${formatRate(bytesPerSecond)}"

        runCatching {
            context.getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, build(context, title, detail, progress))
        }
    }

    fun build(context: Context, title: String, detail: String?, progress: Int): Notification {
        ensureChannel(context)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            1,
            Intent(context, TransferService::class.java).setAction(TransferService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detail)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.stop), stop)
            .apply { if (detail != null) setProgress(100, progress, false) }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.transfer_channel_description)
                setShowBadge(false)
            },
        )
    }
}
