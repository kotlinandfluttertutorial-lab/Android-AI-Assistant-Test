/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : SyncModule.kt
 * Purpose    : Hilt module providing Sync dependencies to the DI graph
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
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
 * Module     : data
 * File       : SyncModule.kt
 * Purpose    : Hilt module providing Sync dependencies to the DI graph
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
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
 * SyncModule.kt â€” data module
 *
 * Purpose: Hilt [dagger.Module] that wires WorkManager-related dependencies required by
 *          [SyncMessagesWorker]. Specifically, it provides the application-scoped
 *          [WorkManager] singleton so that caller sites can request it via constructor
 *          injection without referencing the Android framework directly.
 *
 * Architecture: data module â€” installs into [dagger.hilt.components.SingletonComponent].
 *               Worker injection itself is handled automatically by Hilt + HiltWorkerFactory
 *               (registered in AIAssistantApplication). This module only needs to provide
 *               the [WorkManager] instance that scheduling helpers like
 *               [SyncMessagesWorker.enqueue] consume.
 *
 * Design decisions:
 * - [WorkManager.getInstance] is idempotent and thread-safe; it is safe to call from
 *   any thread, so no dispatcher wrapping is required here.
 * - Providing [WorkManager] as a singleton avoids repeated getInstance() lookups
 *   scattered through the codebase and makes the dependency explicit and replaceable in
 *   tests.
 *
 * Requirements: 10.2, 10.6 (WorkManager offline queue scheduling)
 */
package com.aiassistant.data.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {

    /**
     * Provides the application-scoped [WorkManager] singleton.
     *
     * [WorkManager] is already initialised by [com.aiassistant.AIAssistantApplication]
     * (which implements [androidx.work.Configuration.Provider] with a custom
     * [androidx.hilt.work.HiltWorkerFactory]). This provider simply exposes the
     * already-initialised instance through the DI graph.
     *
     * @param context Android application context supplied by Hilt.
     * @return The process-wide [WorkManager] instance.
     */
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
