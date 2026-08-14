/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderAlarmReceiver.kt
 * Purpose    : ReminderAlarmReceiver — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderAlarmReceiver.kt
 * Purpose    : ReminderAlarmReceiver — feature-productivity module component
 *
 * Architecture Layer : Feature (feature-productivity)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ReminderAlarmReceiver.kt
 *
 * Purpose: BroadcastReceiver that fires when an exact AlarmManager alarm triggers for a
 *          scheduled Reminder. Posts the local notification via NotificationManagerCompat.
 * Architecture: feature-productivity â€” Android infrastructure component.
 * Dependencies: Android NotificationManagerCompat, NotificationCompat, PendingIntent.
 *
 * Requirements: 16.3
 */
package com.aiassistant.feature.productivity.reminder

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Receives alarm broadcasts from [AlarmManager] and posts a local notification for the
 * corresponding reminder.
 *
 * The receiver is registered in the feature module's [AndroidManifest.xml] with
 * [ALARM_ACTION] as the intent action. The app manifest merges this entry at build time.
 *
 * Thread-safety: [onReceive] runs on the main thread; work is intentionally brief
 * (posting a notification is non-blocking).
 */
class ReminderAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ALARM_ACTION) return

        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "Reminder"

        postNotification(context, reminderId, title)
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Posts a high-priority notification for the given reminder.
     *
     * On Android 13+ (API 33) the POST_NOTIFICATIONS permission must be granted. This
     * method checks the permission before attempting to notify, so it silently no-ops
     * when the user has declined notifications (Requirement 16.3).
     */
    private fun postNotification(context: Context, reminderId: String, title: String) {
        val notificationManager = NotificationManagerCompat.from(context)

        // On Android 13+, verify POST_NOTIFICATIONS is granted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        // Tap action â€” deep-link into the app (using generic launch intent as fallback)
        val tapIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
            ?: Intent()

        val tapPendingIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, REMINDER_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText("Your reminder is due.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(tapPendingIntent)
            .build()

        notificationManager.notify(reminderId.hashCode(), notification)
    }
}
