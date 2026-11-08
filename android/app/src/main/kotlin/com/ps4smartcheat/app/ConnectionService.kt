package com.ps4smartcheat.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch

/**
 * Keeps the connection to the PS4 alive while the app is in the background (switching to the game, another app or
 * the home screen): a foreground service stops Android from killing the process or cutting its network, and a
 * Wi-Fi lock keeps the Wi-Fi radio from sleeping, which otherwise stalls the socket until the keep-alive gives up.
 * Runs only while connected; the notification has a Disconnect action. Nothing is sent anywhere else.
 */
class ConnectionService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            val c = (application as SmartCheatApp).container
            c.appScope.launch { c.userDisconnect() }
            stopSelf()
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra(EXTRA_HOST).orEmpty()
        val notification = notification(host)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: RuntimeException) {
            // Not allowed right now (e.g. started from the background on Android 12+): the connection still works,
            // it is just not protected while the app is in the background.
            stopSelf()
            return START_NOT_STICKY
        }
        acquireWifiLock()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = runCatching { wm.createWifiLock(mode, "PS4SmartCheat:connection").apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    private fun notification(host: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.connection_channel), NotificationManager.IMPORTANCE_LOW).apply {
                    description = getString(R.string.connection_channel_description)
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val disconnect = PendingIntent.getService(
            this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle(getString(R.string.connection_notification_title))
            .setContentText(if (host.isBlank()) getString(R.string.connection_notification_text) else getString(R.string.connection_notification_host, host))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.disconnect), disconnect)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "connection"
        private const val NOTIFICATION_ID = 744
        private const val EXTRA_HOST = "host"
        private const val ACTION_DISCONNECT = "com.ps4smartcheat.app.DISCONNECT"

        fun start(context: Context, host: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, ConnectionService::class.java).putExtra(EXTRA_HOST, host))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ConnectionService::class.java)) }
        }
    }
}
