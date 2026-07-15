package com.moonlight.matrixmessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Android requires a visible foreground notification while screen capture
 * (MediaProjection) is active — this is a platform requirement, not
 * optional, so the person always knows their screen is being shared.
 */
class ScreenShareService : Service() {

    private val channelId = "screen_share_channel"

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannelIfNeeded()

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Matrix Messenger")
            .setContentText("Your screen is being shared")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
        return START_NOT_STICKY
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Screen Sharing",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
