/**
 * FirebaseAnalyticsHelper.kt — app module
 *
 * Purpose: Centralised wrapper around FirebaseAnalytics that provides a type-safe API for
 *          the four mandatory analytics events required by the spec:
 *            - screen_view      (Req 18.7)
 *            - feature_used     (Req 18.7)
 *            - message_sent     (Req 18.7)
 *            - error_occurred   (Req 18.7)
 *
 *          Keeping all Firebase Analytics calls here prevents leakage of Firebase SDK
 *          symbols into feature modules and makes the analytics implementation swappable
 *          without modifying each screen.
 *
 * Architecture: app module — analytics layer. Injected as a singleton via Hilt.
 *               Feature modules call this through a thin interface so they don't need a
 *               direct Firebase dependency.
 *
 * Requirements: 18.7
 */
package com.aiassistant.analytics

import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface exposing the required analytics events.
 *
 * A thin abstraction so callers in feature modules can be tested without a live Firebase
 * instance. The production binding is [FirebaseAnalyticsHelperImpl].
 */
interface AnalyticsEventTracker {

    /**
     * Records a [FirebaseAnalytics.Event.SCREEN_VIEW] event (Requirement 18.7).
     *
     * @param screenName  Human-readable name shown in the Firebase console (e.g. "Login").
     * @param screenClass Simple name of the primary Composable (e.g. "LoginScreen").
     */
    fun logScreenView(screenName: String, screenClass: String)

    /**
     * Records a `feature_used` custom event (Requirement 18.7).
     *
     * Use this whenever a user taps into a named feature (e.g. "voice_assistant",
     * "camera_analysis", "code_editor").
     *
     * @param featureName Identifier for the feature, e.g. "rag_document_query".
     * @param extra       Optional key/value parameters appended to the event bundle.
     */
    fun logFeatureUsed(featureName: String, extra: Map<String, String> = emptyMap())

    /**
     * Records a `message_sent` custom event (Requirement 18.7).
     *
     * Log this immediately after the user taps "send" on a chat or voice message.
     *
     * @param conversationId The conversation the message belongs to.
     * @param provider       The active LLM provider identifier (e.g. "openai_gpt4o").
     * @param isOffline      Whether the message was queued for offline delivery.
     */
    fun logMessageSent(conversationId: String, provider: String, isOffline: Boolean = false)

    /**
     * Records an `error_occurred` custom event (Requirement 18.7).
     *
     * Use for user-visible, non-fatal errors (e.g. network failures, validation errors).
     * Fatal/uncaught exceptions are captured automatically by Crashlytics.
     *
     * @param errorCode   A stable machine-readable code (e.g. "network_unavailable").
     * @param errorSource Where the error occurred (e.g. "ChatViewModel", "SyncWorker").
     * @param extra       Optional additional context appended to the event bundle.
     */
    fun logErrorOccurred(errorCode: String, errorSource: String, extra: Map<String, String> = emptyMap())
}

// ─── Custom event and parameter names ────────────────────────────────────────

private const val EVENT_FEATURE_USED = "feature_used"
private const val EVENT_MESSAGE_SENT = "message_sent"
private const val EVENT_ERROR_OCCURRED = "error_occurred"

private const val PARAM_FEATURE_NAME = "feature_name"
private const val PARAM_CONVERSATION_ID = "conversation_id"
private const val PARAM_PROVIDER = "provider"
private const val PARAM_IS_OFFLINE = "is_offline"
private const val PARAM_ERROR_CODE = "error_code"
private const val PARAM_ERROR_SOURCE = "error_source"

// ─── Production implementation ────────────────────────────────────────────────

/**
 * Production implementation of [AnalyticsEventTracker] backed by [FirebaseAnalytics].
 *
 * Injected as a [Singleton] via Hilt. [FirebaseAnalytics] is itself a process-wide
 * singleton; wrapping it here simply enforces a clean API boundary.
 *
 * @param analytics Pre-initialised [FirebaseAnalytics] instance injected by Hilt.
 */
@Singleton
class FirebaseAnalyticsHelperImpl @Inject constructor(private val analytics: FirebaseAnalytics) :
    AnalyticsEventTracker {

    override fun logScreenView(screenName: String, screenClass: String) {
        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            putString(FirebaseAnalytics.Param.SCREEN_CLASS, screenClass)
        }
        analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
    }

    override fun logFeatureUsed(featureName: String, extra: Map<String, String>) {
        val bundle = Bundle().apply {
            putString(PARAM_FEATURE_NAME, featureName)
            extra.forEach { (key, value) -> putString(key, value) }
        }
        analytics.logEvent(EVENT_FEATURE_USED, bundle)
    }

    override fun logMessageSent(conversationId: String, provider: String, isOffline: Boolean) {
        val bundle = Bundle().apply {
            putString(PARAM_CONVERSATION_ID, conversationId)
            putString(PARAM_PROVIDER, provider)
            putInt(PARAM_IS_OFFLINE, if (isOffline) 1 else 0)
        }
        analytics.logEvent(EVENT_MESSAGE_SENT, bundle)
    }

    override fun logErrorOccurred(errorCode: String, errorSource: String, extra: Map<String, String>) {
        val bundle = Bundle().apply {
            putString(PARAM_ERROR_CODE, errorCode)
            putString(PARAM_ERROR_SOURCE, errorSource)
            extra.forEach { (key, value) -> putString(key, value) }
        }
        analytics.logEvent(EVENT_ERROR_OCCURRED, bundle)
    }
}
