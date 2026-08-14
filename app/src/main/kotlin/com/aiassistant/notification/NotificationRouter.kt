/**
 * NotificationRouter.kt — app module
 *
 * Purpose: Stateless routing logic that maps an FCM data payload to a notification-post
 *          action, subject to per-category preference toggles. Extracted from
 *          [com.aiassistant.service.PushNotificationService] to allow pure-JVM unit
 *          testing of the routing rules without an Android [FirebaseMessagingService]
 *          subclass or a live FCM connection.
 *
 *          Routing rules (Requirement 16.1, 16.2, 16.4):
 *            - "rag_ingestion"    → [CHANNEL_ID_RAG_INGESTION]   — shown only when enabled
 *            - "message_delivery" → [CHANNEL_ID_MESSAGE_DELIVERY] — shown only when enabled
 *            - "system_alert"    → [CHANNEL_ID_SYSTEM_ALERTS]    — always shown
 *            - unknown type       → silently ignored, no crash
 *
 * Architecture: app module — notification infrastructure. Called from
 *               [PushNotificationService] via Hilt injection.
 *               Injected as a singleton; no mutable state.
 *
 * Requirements: 16.1, 16.2, 16.4
 */
package com.aiassistant.notification

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

// ─── Notification type constants (must match backend FCM data payload values) ─

/** FCM data key carrying the notification category. */
const val FCM_KEY_NOTIFICATION_TYPE = "notification_type"

/** FCM data key for the optional notification title. */
const val FCM_KEY_TITLE = "title"

/** FCM data key for the notification body text. */
const val FCM_KEY_BODY = "body"

/** FCM data payload value for a RAG ingestion-complete notification. */
const val NOTIF_TYPE_RAG_INGESTION = "rag_ingestion"

/** FCM data payload value for a queued-message-delivered notification. */
const val NOTIF_TYPE_MESSAGE_DELIVERY = "message_delivery"

/** FCM data payload value for a system or admin alert notification. */
const val NOTIF_TYPE_SYSTEM_ALERT = "system_alert"

// ─── Notification IDs (fixed so a later message replaces an earlier one) ─────

/** Stable notification ID for RAG ingestion events. */
const val NOTIF_ID_RAG = 20_001

/** Stable notification ID for message-delivery events. */
const val NOTIF_ID_MESSAGE_DELIVERED = 20_002

/** Stable notification ID for system-alert events. */
const val NOTIF_ID_SYSTEM = 20_003

// ─── Result type ──────────────────────────────────────────────────────────────

/**
 * The outcome of a single [NotificationRouter.route] call.
 *
 * Sealed class so callers and tests can exhaustively match on the result.
 */
sealed class RoutingResult {
    /** A notification was posted on [channelId] with the given [notificationId]. */
    data class Shown(val channelId: String, val notificationId: Int) : RoutingResult()

    /** The notification category was toggled off by the user — silently suppressed. */
    data class Suppressed(val reason: String) : RoutingResult()

    /** The FCM data payload was missing required fields or the type was unknown. */
    data class Ignored(val reason: String) : RoutingResult()
}

// ─── Preference snapshot ──────────────────────────────────────────────────────

/**
 * Snapshot of per-category notification preferences at the moment a push message arrives.
 *
 * Passed into [NotificationRouter.route] so the router remains free of any DataStore
 * dependencies and is trivially testable.
 *
 * @param ragIngestionEnabled   Whether the RAG ingestion category is enabled.
 * @param messageDeliveryEnabled Whether the message-delivery category is enabled.
 */
data class NotificationPreferencesSnapshot(val ragIngestionEnabled: Boolean, val messageDeliveryEnabled: Boolean)

// ─── NotificationRouter ───────────────────────────────────────────────────────

/**
 * Routes an FCM push message to the appropriate Android notification channel.
 *
 * All mutable state lives outside this class (preferences snapshot is passed per call;
 * the [NotificationManagerCompat] wrapper is provided at construction time for
 * testability). This makes the routing logic a pure function of its inputs and therefore
 * easily unit-testable.
 *
 * @param context           Application context (used to build [PendingIntent] and
 *                          [NotificationManagerCompat]).
 * @param notificationPoster Strategy for posting a built notification.  Production code
 *                           passes [DefaultNotificationPoster]; tests supply a fake.
 */
@Singleton
class NotificationRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationPoster: NotificationPoster
) {

    /**
     * Evaluates an incoming FCM data payload and posts the appropriate notification.
     *
     * @param data        The FCM data map extracted from [RemoteMessage.data].
     * @param preferences Current per-category preference snapshot.
     * @return [RoutingResult] describing what happened.
     */
    fun route(data: Map<String, String>, preferences: NotificationPreferencesSnapshot): RoutingResult {
        val notificationType = data[FCM_KEY_NOTIFICATION_TYPE]
        val title = data[FCM_KEY_TITLE]
        val body = data[FCM_KEY_BODY]

        if (notificationType == null || body == null) {
            val reason = "Missing notification_type or body in FCM payload"
            Timber.w("NotificationRouter: $reason")
            return RoutingResult.Ignored(reason)
        }

        return when (notificationType) {
            NOTIF_TYPE_RAG_INGESTION -> {
                if (!preferences.ragIngestionEnabled) {
                    Timber.d("NotificationRouter: rag_ingestion disabled — suppressing")
                    RoutingResult.Suppressed("rag_ingestion category is disabled")
                } else {
                    postNotification(
                        channelId = CHANNEL_ID_RAG_INGESTION,
                        notificationId = NOTIF_ID_RAG,
                        title = title ?: "Document processing complete",
                        body = body,
                        priority = NotificationCompat.PRIORITY_DEFAULT
                    )
                    Timber.d("NotificationRouter: posted rag_ingestion notification")
                    RoutingResult.Shown(CHANNEL_ID_RAG_INGESTION, NOTIF_ID_RAG)
                }
            }

            NOTIF_TYPE_MESSAGE_DELIVERY -> {
                if (!preferences.messageDeliveryEnabled) {
                    Timber.d("NotificationRouter: message_delivery disabled — suppressing")
                    RoutingResult.Suppressed("message_delivery category is disabled")
                } else {
                    postNotification(
                        channelId = CHANNEL_ID_MESSAGE_DELIVERY,
                        notificationId = NOTIF_ID_MESSAGE_DELIVERED,
                        title = title ?: "Message delivered",
                        body = body,
                        priority = NotificationCompat.PRIORITY_HIGH
                    )
                    Timber.d("NotificationRouter: posted message_delivery notification")
                    RoutingResult.Shown(CHANNEL_ID_MESSAGE_DELIVERY, NOTIF_ID_MESSAGE_DELIVERED)
                }
            }

            NOTIF_TYPE_SYSTEM_ALERT -> {
                // System alerts are always shown — may contain critical safety information.
                postNotification(
                    channelId = CHANNEL_ID_SYSTEM_ALERTS,
                    notificationId = NOTIF_ID_SYSTEM,
                    title = title ?: "System alert",
                    body = body,
                    priority = NotificationCompat.PRIORITY_HIGH
                )
                Timber.d("NotificationRouter: posted system_alert notification")
                RoutingResult.Shown(CHANNEL_ID_SYSTEM_ALERTS, NOTIF_ID_SYSTEM)
            }

            else -> {
                val reason = "Unknown notification_type: $notificationType"
                Timber.w("NotificationRouter: $reason")
                RoutingResult.Ignored(reason)
            }
        }
    }

    // ─── Private helpers ──────────────────────────────────────────────────────

    private fun postNotification(channelId: String, notificationId: Int, title: String, body: String, priority: Int) {
        val tapIntent = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }

        val pendingIntent = tapIntent?.let {
            PendingIntent.getActivity(
                context,
                notificationId,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(priority)
            .apply { pendingIntent?.let { setContentIntent(it) } }
            .setAutoCancel(true)
            .build()

        notificationPoster.notify(notificationId, notification)
    }
}

// ─── NotificationPoster interface (production + test implementations) ─────────

/**
 * Strategy interface for posting Android notifications.
 *
 * The production implementation wraps [NotificationManagerCompat].
 * Tests supply a [FakeNotificationPoster] that records calls without
 * needing a real Android runtime or system permissions.
 */
interface NotificationPoster {
    /**
     * Posts [notification] with [notificationId].
     * Implementations are responsible for checking POST_NOTIFICATIONS permission.
     */
    fun notify(notificationId: Int, notification: android.app.Notification)
}

/**
 * Production [NotificationPoster] backed by [NotificationManagerCompat].
 *
 * Suppress the MissingPermission lint warning here: POST_NOTIFICATIONS is declared in
 * the application's AndroidManifest.xml and runtime-requested in [feature-auth].
 */
class DefaultNotificationPoster(private val context: Context) : NotificationPoster {
    @Suppress("MissingPermission")
    override fun notify(notificationId: Int, notification: android.app.Notification) {
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
}
