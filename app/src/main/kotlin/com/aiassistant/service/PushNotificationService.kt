/**
 * PushNotificationService.kt — app module
 *
 * Purpose: Firebase Cloud Messaging [FirebaseMessagingService] that:
 *   1. Receives new FCM registration tokens and uploads them to the backend, with an
 *      automatic retry counter stored in [SecureStorage]. If the immediate upload
 *      fails, the counter is reset so that the next 10 successful API calls will
 *      attempt the upload via the piggyback mechanism in [UserRepositoryImpl].
 *      (Requirements 16.4, 16.5, 16.7)
 *   2. Delegates incoming FCM push messages to [NotificationRouter] which maps each
 *      notification type to the correct channel, subject to per-category preferences.
 *      (Requirements 16.1, 16.2, 16.4)
 *
 * Architecture: app module — Android service layer; @AndroidEntryPoint for Hilt injection.
 *               Network calls are dispatched on a [CoroutineScope] with a [SupervisorJob]
 *               so one failed coroutine cannot cancel sibling work.
 *
 * Requirements: 16.1, 16.2, 16.4, 16.5, 16.7
 */
package com.aiassistant.service

import com.aiassistant.core.security.SecureStorage
import com.aiassistant.domain.repository.UserRepository
import com.aiassistant.feature.settings.SettingsPreferences
import com.aiassistant.notification.FCM_KEY_NOTIFICATION_TYPE
import com.aiassistant.notification.NotificationPreferencesSnapshot
import com.aiassistant.notification.NotificationRouter
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * FCM service that handles token refresh and routes incoming push messages.
 *
 * @param secureStorage       Encrypted storage for FCM token and pending-sync state.
 * @param settingsPreferences DataStore wrapper for per-category notification toggles.
 * @param userRepository      Domain repository used to upload FCM tokens to the backend.
 * @param notificationRouter  Routes FCM payload to the appropriate notification channel.
 */
@AndroidEntryPoint
class PushNotificationService : FirebaseMessagingService() {

    @Inject
    lateinit var secureStorage: SecureStorage

    @Inject
    lateinit var settingsPreferences: SettingsPreferences

    @Inject
    lateinit var userRepository: UserRepository

    @Inject
    lateinit var notificationRouter: NotificationRouter

    /**
     * Service-scoped coroutine scope. Cancelled in [onDestroy] to prevent leaks.
     * [SupervisorJob] ensures that a failed token upload does not kill the scope.
     */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ─── FirebaseMessagingService overrides ───────────────────────────────────

    /**
     * Called when the Firebase SDK issues a new FCM registration token.
     *
     * Steps:
     *   1. Save the token to encrypted local storage (marks it as pending-sync).
     *   2. Immediately attempt to upload it to the backend.
     *   3. On failure the pending-sync flag stays set; [UserRepositoryImpl] piggybacks
     *      the retry on the next 10 successful authenticated API calls. (Req 16.7)
     *
     * @param token The new FCM registration token.
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Timber.d("PushNotificationService: FCM token refreshed — storing and uploading")

        secureStorage.saveFcmToken(token)

        serviceScope.launch {
            try {
                userRepository.updateFcmToken(token)
                Timber.d("PushNotificationService: FCM token uploaded successfully")
            } catch (e: Exception) {
                // Non-fatal: the lazy piggyback mechanism in UserRepositoryImpl will retry.
                Timber.w(e, "PushNotificationService: FCM token upload failed — will retry lazily")
            }
        }
    }

    /**
     * Called for data-only FCM messages or foreground push messages.
     *
     * Reads per-category preferences from [SettingsPreferences] and delegates routing to
     * [NotificationRouter]. Unknown notification types are silently ignored.
     * (Requirements 16.1, 16.4)
     *
     * @param message The incoming [RemoteMessage] from Firebase.
     */
    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val notificationType = data[FCM_KEY_NOTIFICATION_TYPE]

        Timber.d(
            "PushNotificationService: message received — type=$notificationType, " +
                "messageId=${message.messageId}"
        )

        serviceScope.launch {
            val preferences = NotificationPreferencesSnapshot(
                ragIngestionEnabled = settingsPreferences.ragIngestionEnabled.firstOrNull() ?: true,
                messageDeliveryEnabled = settingsPreferences.syncStatusEnabled.firstOrNull() ?: true
            )
            val result = notificationRouter.route(data, preferences)
            Timber.d("PushNotificationService: routing result — $result")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
