package com.mobilemcp.pro.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.mobilemcp.pro.MainActivity
import com.mobilemcp.pro.R
import com.mobilemcp.pro.server.RemoteBridgeClient

class RemoteBridgeForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.mobilemcp.pro.REMOTE_BRIDGE_START"
        const val ACTION_STOP = "com.mobilemcp.pro.REMOTE_BRIDGE_STOP"
        const val EXTRA_RELAY_URL = "relay_url"
        const val EXTRA_CREDENTIAL = "credential"

        private const val CHANNEL_ID = "prime_remote_bridge_channel"
        private const val NOTIFICATION_ID = 1002
    }

    private var client: RemoteBridgeClient? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.remote_bridge_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.remote_bridge_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            client?.stop()
            client = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val relayUrl = intent?.getStringExtra(EXTRA_RELAY_URL).orEmpty()
        val credential = intent?.getStringExtra(EXTRA_CREDENTIAL).orEmpty()
        if (relayUrl.isBlank() || credential.length < 32) {
            stopSelf()
            return START_NOT_STICKY
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.remote_bridge_notification_title))
            .setContentText(getString(R.string.remote_bridge_notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)

        client?.stop()
        client = RemoteBridgeClient(relayUrl, credential).also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        client?.stop()
        client = null
        super.onDestroy()
    }
}
