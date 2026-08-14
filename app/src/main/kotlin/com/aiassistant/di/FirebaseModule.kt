/**
 * FirebaseModule.kt — app module
 *
 * Purpose: Hilt [dagger.Module] that exposes Firebase service singletons to the DI graph.
 *          Centralising all Firebase bindings here ensures that every ViewModel and
 *          service that needs Crashlytics, Analytics, or Remote Config gets the same
 *          process-wide instance without calling getInstance() scattered throughout the
 *          codebase.
 *
 * Architecture: app module — DI wiring layer. Installs into [SingletonComponent].
 *
 * Design decisions:
 * - [FirebaseAnalytics] is provided as a singleton to avoid creating multiple instances;
 *   the SDK itself is a singleton, but surfacing it through Hilt keeps the dependency graph
 *   explicit and testable.
 * - [FirebaseCrashlytics] is included for future non-fatal error logging calls from
 *   ViewModels / repositories.
 * - Remote Config is also provided here as an alternative to SettingsModule; SettingsModule
 *   continues to own the settings-specific configuration (minimumFetchIntervalInSeconds)
 *   while this module provides the raw singleton for callers that don't go through
 *   SettingsModule.
 *
 * Requirements: 18.7 (Crashlytics, Analytics, Remote Config)
 */
package com.aiassistant.di

import android.content.Context
import com.aiassistant.analytics.AnalyticsEventTracker
import com.aiassistant.analytics.FirebaseAnalyticsHelperImpl
import com.aiassistant.notification.DefaultNotificationPoster
import com.aiassistant.notification.NotificationPoster
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.ktx.Firebase
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfig
import com.google.firebase.remoteconfig.ktx.remoteConfigSettings
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FirebaseModule {

    /**
     * Binds the production [FirebaseAnalyticsHelperImpl] to the [AnalyticsEventTracker]
     * interface. Feature modules that import [AnalyticsEventTracker] will receive this
     * implementation at runtime; tests can supply a no-op fake.
     */
    @Binds
    @Singleton
    abstract fun bindAnalyticsEventTracker(impl: FirebaseAnalyticsHelperImpl): AnalyticsEventTracker

    companion object {

        /**
         * Provides the process-wide [FirebaseAnalytics] singleton.
         *
         * [FirebaseApp.initializeApp] is called in [com.aiassistant.AIAssistantApplication.onCreate]
         * before any Hilt component is resolved, so this provider is always safe to call.
         *
         * @param context Application context required by the Firebase SDK.
         */
        @Provides
        @Singleton
        fun provideFirebaseAnalytics(@ApplicationContext context: Context): FirebaseAnalytics =
            FirebaseAnalytics.getInstance(context)

        /**
         * Provides the process-wide [FirebaseCrashlytics] singleton.
         *
         * Uncaught exception reporting is enabled automatically by the Crashlytics Gradle plugin.
         * This binding allows ViewModels and repositories to log non-fatal errors imperatively
         * via [FirebaseCrashlytics.recordException].
         */
        @Provides
        @Singleton
        fun provideFirebaseCrashlytics(): FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

        /**
         * Provides the production [NotificationPoster] backed by [NotificationManagerCompat].
         *
         * Injected into [com.aiassistant.notification.NotificationRouter]. Tests supply a
         * [FakeNotificationPoster] instead to capture posted notifications without needing
         * a real Android runtime.
         */
        @Provides
        @Singleton
        fun provideNotificationPoster(@ApplicationContext context: Context): NotificationPoster =
            DefaultNotificationPoster(context)

        /**
         * Provides the [FirebaseRemoteConfig] singleton configured with a minimum fetch interval
         * of 3600 seconds (1 hour) in production.
         *
         * Centralizing this here satisfies Requirement 18.7 and resolves duplicate binding
         * issues between core-network and feature-settings.
         */
        @Provides
        @Singleton
        fun provideFirebaseRemoteConfig(): FirebaseRemoteConfig {
            val config = Firebase.remoteConfig
            val settings = remoteConfigSettings {
                // 1-hour minimum fetch interval (Firebase default is 12 hours).
                minimumFetchIntervalInSeconds = 3600L
            }
            config.setConfigSettingsAsync(settings)
            return config
        }
    }
}
