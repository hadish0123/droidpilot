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

/**
 * Keeps the PRIME hands-free voice session eligible to use the microphone
 * while the assistant temporarily opens and operates other apps.
 *
 * The service does not record or process audio itself. SpeechRecognizer stays
 * in MainActivity; this foreground service only keeps the user-visible voice
 * session active across app switches.
 */
class VoiceSessionForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "prime_voice_session"
        const val NOTIFICATION_ID = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.voice_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.voice_notification_channel_description)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.voice_notification_title))
            .setContentText(getString(R.string.voice_notification_text))
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }
}
