package com.nexa.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

// Shared flags between the voice screen, the activity and the floating bubble.
object VoiceFlag {
    @Volatile var active = false          // a voice chat session is running
    @Volatile var bubbleStarted = false   // the floating bubble was opened automatically for it
}

// Android only lets an app keep using the microphone in the background while a
// "microphone" foreground service is running. This service does nothing else:
// it just shows the "Nexa is listening" notification and keeps the app alive.
class VoiceService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nexa_voice", "Nexa voice chat", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "nexa_voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nexa is listening")
            .setContentText("Tap to open. Tap End inside the app to stop.")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        try {
            startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: Exception) {
            stopSelf()
        }
        return START_NOT_STICKY
    }
}
