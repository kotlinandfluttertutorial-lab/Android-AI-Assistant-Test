/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : AIAssistantApplication.kt
 * Purpose    : AIAssistantApplication — app module component
 *
 * Architecture Layer : App
 * Pattern Used       : Hilt Application Entry Point
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
 * Module     : app
 * File       : AIAssistantApplication.kt
 * Purpose    : AIAssistantApplication — app module component
 *
 * Architecture Layer : App
 * Pattern Used       : Hilt Application Entry Point
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
package com.aiassistant

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.aiassistant.analytics.RemoteConfigManager
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import com.aiassistant.core.network.observability.scheduleObservabilityUpload
import com.aiassistant.notification.NotificationChannelManager
import com.aiassistant.observability.AppLifecycleObserver
import com.google.firebase.FirebaseApp
import com.google.firebase.crashlytics.FirebaseCrashlytics
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import androidx.lifecycle.ProcessLifecycleOwner

@HiltAndroidApp
class AIAssistantApplication :
    Application(),
    Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /** Observability event bus — injected so AppLifecycleObserver can emit events. */
    @Inject
    lateinit var observabilityEventBus: ObservabilityEventBus

    /** Session manager — provides sessionId/traceId for observability events. */
    @Inject
    lateinit var sessionManager: SessionManager

    /**
     * Creates notification channels required by FCM and the offline queue worker.
     * Injected here so channels exist before any notification can be posted.
     * (Requirements: 16.1, 16.2)
     */
    @Inject
    lateinit var notificationChannelManager: NotificationChannelManager

    /**
     * Fetches Firebase Remote Config values on every app launch so that
     * Admin_Dashboard-published parameters are applied without an app update.
     * (Requirement 15.8)
     */
    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager

    /**
     * Application-scoped coroutine scope for fire-and-forget startup work.
     * Uses [SupervisorJob] so a failed child (e.g. Remote Config fetch) does
     * not cancel other children.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // Initialise Firebase (required before any Firebase service is used).
        FirebaseApp.initializeApp(this)

        // Configure Crashlytics: disable automatic crash collection in debug builds
        // so stack traces surface directly in Logcat rather than being swallowed by
        // the Crashlytics dashboard. In release builds collection is enabled by default.
        // Uncaught exceptions are reported automatically via the Crashlytics Gradle plugin.
        // (Requirement 18.7)
        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(!BuildConfig.DEBUG)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // Create FCM notification channels once per install (idempotent on repeat calls).
        // Must be called before any notification can be posted. (Requirements 16.1, 16.2)
        notificationChannelManager.ensureChannelsCreated()

        // Schedule the periodic observability event upload to the backend.
        // Uses WorkManager so it survives process death and respects network availability.
        // ExistingPeriodicWorkPolicy.KEEP makes this idempotent on re-launch.
        scheduleObservabilityUpload(this)

        // Register the process-level lifecycle observer to emit APP_FOREGROUND /
        // APP_BACKGROUND events to the observability pipeline.
        // ProcessLifecycleOwner tracks the app process, not individual Activities,
        // so it does NOT fire on screen rotations.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            AppLifecycleObserver(observabilityEventBus, sessionManager)
        )

        // Fetch and activate Remote Config values in the background.
        // The result is applied on this launch; no app restart required. (Requirement 15.8)
        applicationScope.launch {
            remoteConfigManager.fetchAndActivate()
        }
    }
}
