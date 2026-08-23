/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderNotificationManager.kt
 * Purpose    : ReminderNotificationManager — feature-productivity module component
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
 * File       : ReminderNotificationManager.kt
 * Purpose    : ReminderNotificationManager — feature-productivity module component
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
 * ReminderNotificationManager.kt
 *
 * Purpose: Manages the creation of the "reminders" notification channel, scheduling
 *          exact AlarmManager alarms, and cancellation of alarms for the Reminder
 *          feature. On Android 12+ it guards exact alarm scheduling behind a
 *          canScheduleExactAlarms() check and provides a deep-link to the system
 *          settings page when the permission is missing.
 *
 * Architecture: feature-productivity â€” Android infrastructure; injected by Hilt into
 *               ProductivityViewModel so the domain layer stays free of Android types.
 * Dependencies: Android AlarmManager, NotificationManager, NotificationChannel, PendingIntent.
 *
 * Requirements: 16.3, 16.4
 */
package com.aiassistant.feature.productivity.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import com.aiassistant.domain.model.Reminder
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notification channel id used for all reminder notifications.
 */
const val REMINDER_CHANNEL_ID = "reminders"

/**
 * Intent action broadcast when an alarm fires.
 */
const val ALARM_ACTION = "com.aiassistant.feature.productivity.REMINDER_ALARM"

/**
 * Intent extra key carrying the reminder id.
 */
const val EXTRA_REMINDER_ID = "reminder_id"

/**
 * Intent extra key carrying the reminder title.
 */
const val EXTRA_REMINDER_TITLE = "reminder_title"

/**
 * Manages the "reminders" [NotificationChannel], exact-alarm scheduling, and cancellation.
 *
 * This class is the single point of contact for all alarm/notification infrastructure
 * in the Reminders sub-feature. The ViewModel calls it after persisting a reminder so
 * that domain use cases remain free of Android framework types.
 *
 * Thread-safety: all methods are safe to call from any thread; they only interact with
 * Android system services which are themselves thread-safe.
 */
@Singleton
class ReminderNotificationManager @Inject constructor(@ApplicationContext private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context)

    // â”€â”€ Channel initialisation â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Creates the "reminders" notification channel with HIGH importance.
     *
     * Safe to call multiple times; Android ignores duplicate channel registrations.
     * Should be called during application startup or before the first notification.
     */
    fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            REMINDER_CHANNEL_ID,
            "Reminders",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Scheduled reminder notifications"
            enableVibration(true)
            enableLights(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    // â”€â”€ Exact alarm scheduling â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Returns true when the app is permitted to schedule exact alarms.
     *
     * On Android 12+ (API 31+) [AlarmManager.canScheduleExactAlarms] must return true
     * before using [AlarmManager.setExactAndAllowWhileIdle]. On earlier versions exact
     * alarms are always permitted (Requirement 16.3).
     */
    fun canScheduleExactAlarms(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        alarmManager.canScheduleExactAlarms()
    } else {
        true
    }

    /**
     * Schedules an exact alarm for the given [reminder].
     *
     * Uses [AlarmManager.setExactAndAllowWhileIdle] which fires even when the device is
     * in Doze mode, ensuring reliable delivery for scheduled reminders (Requirement 16.3).
     *
     * On Android 12+ this method is a no-op if [canScheduleExactAlarms] returns false â€”
     * the caller should have already checked and shown a rationale before invoking this.
     *
     * @param reminder The [Reminder] for which to schedule an alarm. [Reminder.triggerTime]
     *                 must be in the future; stale times are silently ignored.
     */
    fun scheduleAlarm(reminder: Reminder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            return
        }
        if (reminder.triggerTime <= System.currentTimeMillis()) return

        val pendingIntent = buildAlarmPendingIntent(reminder) ?: return
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.triggerTime,
            pendingIntent
        )
    }

    /**
     * Cancels any previously scheduled exact alarm for the given [reminderId].
     *
     * Should be called when a reminder is deleted or its trigger time is changed.
     *
     * @param reminderId The unique identifier of the reminder whose alarm should be cancelled.
     * @param title      Title used when building the matching [PendingIntent].
     */
    fun cancelAlarm(reminderId: String, title: String = "") {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ALARM_ACTION
            putExtra(EXTRA_REMINDER_ID, reminderId)
            putExtra(EXTRA_REMINDER_TITLE, title)
        }
        // Use the same request code / flags to match the scheduled intent
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    // â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Builds the [PendingIntent] used for both scheduling and cancelling an alarm.
     */
    private fun buildAlarmPendingIntent(reminder: Reminder): PendingIntent? {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            action = ALARM_ACTION
            putExtra(EXTRA_REMINDER_ID, reminder.id)
            putExtra(EXTRA_REMINDER_TITLE, reminder.title)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
