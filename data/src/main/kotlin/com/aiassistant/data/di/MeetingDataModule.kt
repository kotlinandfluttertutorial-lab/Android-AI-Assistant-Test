/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : MeetingDataModule.kt
 * Purpose    : Hilt module providing MeetingData dependencies to the DI graph.
 *              Provides MeetingApiService (Retrofit) and binds MeetingRepositoryImpl
 *              to the MeetingRepository domain interface.
 *
 * Architecture Layer : Data
 * Pattern Used       : Hilt DI Module
 *
 * Requirements: 19.1, 5.6
 * ============================================================
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.meeting.MeetingApiService
import com.aiassistant.data.repository.MeetingRepositoryImpl
import com.aiassistant.domain.repository.MeetingRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * Hilt [dagger.Module] that wires all meeting-related bindings.
 *
 * Provides:
 *   - [MeetingApiService] Retrofit implementation
 * Binds:
 *   - [MeetingRepositoryImpl] → [MeetingRepository]
 *
 * Architecture: installs into [SingletonComponent] for process-wide singletons.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeetingDataModule {

    /**
     * Binds [MeetingRepositoryImpl] to the [MeetingRepository] domain interface.
     */
    @Binds
    @Singleton
    abstract fun bindMeetingRepository(impl: MeetingRepositoryImpl): MeetingRepository

    companion object {

        /**
         * Creates the [MeetingApiService] Retrofit implementation using the application-level
         * [Retrofit] singleton provided by core-network's NetworkModule.
         */
        @Provides
        @Singleton
        fun provideMeetingApiService(retrofit: Retrofit): MeetingApiService =
            retrofit.create(MeetingApiService::class.java)
    }
}
