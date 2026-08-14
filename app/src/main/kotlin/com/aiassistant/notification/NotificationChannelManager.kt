/**
 * NotificationChannelManager.kt — app module
 *
 * Purpose: Creates and owns the three FCM-backed notification channels required by the
 *          spec (Requirement 16.2):
 *            - rag_ingestion   : RAG ingestion complete notifications
 *            - message_delivery: Queued message delivered notifications
 *            - system_alerts   : System / admin alert notifications
 *
 *          Channel creation is idempotent on Android 8+ (Oreo) — calling
 *          [NotificationManager.createNotificationChannel] with the same ID is a no-op
 *          if the channel already exists. This means [ensureChannelsCreated] is safe to
 *          call on every application start.
 *
 * Architecture: app module — notification infrastructure. Called once from
 *               [AIAssistantApplication.onCreate]. Feature modules reference the public
 *               channel-ID constants when building [NotificationCompat.Builder] instances.
 *
 * Requirements: 16.1, 16.2
 */
package com.aiassistant.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Notification channel for RAG document ingestion completion (Requirement 16.2). */
const val CHANNEL_ID_RAG_INGESTION = "rag_ingestion"

/** Notification channel for offline-queued message delivery confirmation (Requirement 16.2). */
const val CHANNEL_ID_MESSAGE_DELIVERY = "message_delivery"

/** Notification channel for system / admin alert notifications (Requirement 16.2). */
const val CHANNEL_ID_SYSTEM_ALERTS = "system_alerts"

/**
 * Creates all required FCM notification channels on Android 8+.
 *
 * Inject this class and call [ensureChannelsCreated] once during application startup
 * (e.g. in [com.aiassistant.AIAssistantApplication.onCreate]).
 *
 * @param context Application context used to obtain the [NotificationManager].
 */
@Singleton
class NotificationChannelManager @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Creates all notification channels required by the application.
     *
     * Safe to call on every application start — channel creation is idempotent.
     * This function is a no-op on Android 7 (API 25) and below, where channels
     * do not exist.
     */
    fun ensureChannelsCreated() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        notificationManager.createNotificationChannels(
            listOf(
                ragIngestionChannel(),
                messageDeliveryChannel(),
                systemAlertsChannel()
            )
        )
    }

    // ─── Private channel builders ─────────────────────────────────────────────

    /**
     * Builds the RAG document-ingestion notification channel.
     *
     * Importance: [NotificationManager.IMPORTANCE_DEFAULT] — audible but not intrusive;
     * the user asked for the ingestion but doesn't need an alarm-level interrupt.
     */
    private fun ragIngestionChannel(): NotificationChannel = NotificationChannel(
        CHANNEL_ID_RAG_INGESTION,
        "Document Processing",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
        description = "Notifies when a document has finished processing and is ready for Q&A."
    }

    /**
     * Builds the message-delivery notification channel.
     *
     * Importance: [NotificationManager.IMPORTANCE_HIGH] — messages queued while offline
     * are time-sensitive; the user should see a heads-up notification when delivery
     * succeeds.
     */
    private fun messageDeliveryChannel(): NotificationChannel = NotificationChannel(
        CHANNEL_ID_MESSAGE_DELIVERY,
        "Message Delivery",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Notifies when a queued offline message has been delivered."
    }

    /**
     * Builds the system-alerts notification channel.
     *
     * Importance: [NotificationManager.IMPORTANCE_HIGH] — system alerts may require
     * immediate user attention (e.g. maintenance windows, forced logout).
     */
    private fun systemAlertsChannel(): NotificationChannel = NotificationChannel(
        CHANNEL_ID_SYSTEM_ALERTS,
        "System Alerts",
        NotificationManager.IMPORTANCE_HIGH
    ).apply {
        description = "Important system and admin alerts from the AI Assistant platform."
    }
}
