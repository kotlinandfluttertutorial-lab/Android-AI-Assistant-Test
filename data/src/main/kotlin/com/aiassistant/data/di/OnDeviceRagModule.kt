/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : OnDeviceRagModule.kt
 * Purpose    : Hilt bindings for all On-Device RAG repository interfaces.
 *              Wires domain interfaces to their data-module implementations
 *              so use cases receive the correct singletons via @Inject.
 *
 * Architecture Layer : Data — DI wiring layer.
 *                      Installed in SingletonComponent for process-wide singletons
 *                      matching the AppDatabase lifecycle.
 *
 * Dependencies       : Hilt, all On-Device RAG repository impl classes
 *
 * Design Decision    : All four on-device RAG repositories are bound in one
 *                      module to keep the on-device RAG data path self-contained.
 *                      Splitting into separate modules would not reduce compile
 *                      times (these classes are always compiled together) and
 *                      would scatter the feature's DI wiring.
 *
 * Requirements: 33.5, 33.6, 36.10
 * ============================================================
 */
package com.aiassistant.data.di

import com.aiassistant.data.repository.ModelFileRepositoryImpl
import com.aiassistant.data.repository.OnDeviceDocumentRepositoryImpl
import com.aiassistant.data.repository.QueryMetricsRepositoryImpl
import com.aiassistant.data.repository.QueryRoutingLogRepositoryImpl
import com.aiassistant.domain.repository.ModelFileRepository
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import com.aiassistant.domain.repository.QueryMetricsRepository
import com.aiassistant.domain.repository.QueryRoutingLogRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceRagModule {

    /** Binds offline-first document persistence to the domain interface. */
    @Binds
    @Singleton
    abstract fun bindOnDeviceDocumentRepository(impl: OnDeviceDocumentRepositoryImpl): OnDeviceDocumentRepository

    /** Binds WorkManager-backed model file management to the domain interface. */
    @Binds
    @Singleton
    abstract fun bindModelFileRepository(impl: ModelFileRepositoryImpl): ModelFileRepository

    /** Binds Room-backed routing audit log to the domain interface. */
    @Binds
    @Singleton
    abstract fun bindQueryRoutingLogRepository(impl: QueryRoutingLogRepositoryImpl): QueryRoutingLogRepository

    /** Binds in-memory metrics store to the domain interface. */
    @Binds
    @Singleton
    abstract fun bindQueryMetricsRepository(impl: QueryMetricsRepositoryImpl): QueryMetricsRepository
}
