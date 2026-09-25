package com.odiousapps.mx3buttonmapper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Prompts the user to grant Shizuku permission when a mapped button is
 * pressed but injection isn't ready yet. Tries opening MainActivity
 * directly first (MainActivity's onCreate calls Shizuku.requestPermission()
 * itself, which is what actually shows Shizuku's permission dialog -- that
 * call needs a real Activity, it can't be triggered from a Service). Falls
 * back to a tap-to-open notification if the direct launch is blocked (some
 * OEM builds enforce Android 10+ background-activity-launch restrictions
 * more strictly than others).
 */
object SetupNotifier {

    private const val TAG = "SetupNotifier"
    private const val CHANNEL_ID = "button_mapper_setup"
    private const val NOTIFICATION_ID = 2
    private const val COOLDOWN_MS = 60_000L // don't re-prompt more than once a minute

    @Volatile
    private var lastPromptedAt = 0L

    fun promptIfNeeded(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPromptedAt < COOLDOWN_MS) return
        lastPromptedAt = now

        if (!tryDirectLaunch(context)) {
            showNotification(context)
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }

    private fun tryDirectLaunch(context: Context): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            context.startActivity(intent)
            AppLog.i(TAG, "Opened MainActivity directly to prompt for Shizuku permission")
            true
        } catch (e: Exception) {
            // Most likely a background-activity-launch SecurityException on
            // a stricter OEM build -- fall back to the notification instead.
            AppLog.w(TAG, "Direct launch of MainActivity failed, falling back to notification", e)
            false
        }
    }

    private fun showNotification(context: Context) {
        createChannelIfNeeded(context)

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Button Mapper setup needed")
            .setContentText("Open the app to grant Shizuku permission")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }

    private fun createChannelIfNeeded(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Button Mapper setup",
            NotificationManager.IMPORTANCE_HIGH
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}
