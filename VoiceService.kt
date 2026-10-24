package com.nexa.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Shared flags between the voice screen, the activity and the floating bubble.
object VoiceFlag {
    @Volatile var active = false          // a voice chat session is running
    @Volatile var bubbleStarted = false   // the floating bubble was opened automatically for it
    var stopTick by mutableStateOf(0)     // the "End conversation" button in the notification bumps this
}

// Android only lets an app keep using the microphone in the background while a
// "microphone" foreground service is running. This service shows the "Nexa is listening"
// notification (with an End button) and keeps the app alive.
class VoiceService : Service() {
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "com.nexa.ai.STOP_VOICE") {
            VoiceFlag.stopTick += 1
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("nexa_voice", "Nexa voice chat", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, VoiceService::class.java).setAction("com.nexa.ai.STOP_VOICE"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "nexa_voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Nexa is listening")
            .setContentText("Tap to open, or tap End to stop the conversation.")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), "End conversation", stop).build())
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
