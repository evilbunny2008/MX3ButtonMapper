package com.odiousapps.mx3buttonmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Purely optional. Only add this if you want a persistent "Button Mapper is
 * active" notification, or need the process kept alive more aggressively
 * than the AccessibilityService alone guarantees (e.g. to keep the Shizuku
 * binder connection from dying under memory pressure).
 *
 * The AccessibilityService itself does NOT need this -- it's bound via
 * BIND_ACCESSIBILITY_SERVICE and never goes through startForegroundService,
 * so Android 14's foreground-service-type requirement doesn't apply to it.
 * This class only exists to demonstrate the pattern if you decide you want
 * a foreground service anyway.
 */
class StatusForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "button_mapper_status"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Button Mapper status",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Button Mapper active")
            .setContentText("Remapping scancode 108 -> DPAD_DOWN")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }
}
