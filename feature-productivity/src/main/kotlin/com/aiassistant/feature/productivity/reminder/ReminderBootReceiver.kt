/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-productivity
 * File       : ReminderBootReceiver.kt
 * Purpose    : ReminderBootReceiver — feature-productivity module component
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
 * File       : ReminderBootReceiver.kt
 * Purpose    : ReminderBootReceiver — feature-productivity module component
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
 * ReminderBootReceiver.kt
 *
 * Purpose: BroadcastReceiver that listens for BOOT_COMPLETED and QUICKBOOT_POWERON
 *          intents so that AlarmManager alarms (which are erased on device reboot) can
 *          be rescheduled from the locally persisted Reminder records in Room.
 *
 * Architecture: feature-productivity â€” Android infrastructure component.
 * Dependencies: ProductivityRepository (via Hilt EntryPoint), ReminderNotificationManager.
 *
 * Design decisions:
 * - Uses Hilt's EntryPointAccessors to obtain the repository from the BroadcastReceiver
 *   context without needing constructor injection (BroadcastReceivers have no Hilt
 *   lifecycle by default).
 * - Uses goAsync() to keep the process alive while the coroutine fetches reminders from
 *   Room and reschedules alarms; the PendingResult is finished on completion.
 *
 * Requirements: 16.3
 */
package com.aiassistant.feature.productivity.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.ProductivityRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

/**
 * Hilt entry point granting BroadcastReceiver access to the [ProductivityRepository]
 * and [ReminderNotificationManager] from the [SingletonComponent].
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ReminderBootReceiverEntryPoint {
    fun productivityRepository(): ProductivityRepository
    fun reminderNotificationManager(): ReminderNotificationManager
}

/**
 * Reschedules all pending (non-completed) reminders after device reboot.
 *
 * AlarmManager alarms do not survive a device reboot. This receiver catches the
 * BOOT_COMPLETED broadcast and re-creates an exact alarm for every [Reminder] whose
 * [Reminder.triggerTime] is still in the future and whose [Reminder.isCompleted] is
 * false (Requirement 16.3).
 */
class ReminderBootReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }

        val pendingResult = goAsync()

        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReminderBootReceiverEntryPoint::class.java
        )
        val repository = entryPoint.productivityRepository()
        val notificationManager = entryPoint.reminderNotificationManager()

        scope.launch {
            try {
                // Ensure the notification channel exists after reboot
                notificationManager.ensureNotificationChannel()

                // Fetch the first emission of all reminders from Room
                val result = repository.getReminders().firstOrNull()
                if (result is ApiResult.Success) {
                    val now = System.currentTimeMillis()
                    result.data
                        .filter { reminder -> !reminder.isCompleted && reminder.triggerTime > now }
                        .forEach { reminder -> notificationManager.scheduleAlarm(reminder) }
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
