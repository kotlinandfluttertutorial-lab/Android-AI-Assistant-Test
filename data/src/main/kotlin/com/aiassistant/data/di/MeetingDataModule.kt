package com.aiassistant.data.di

import com.aiassistant.data.repository.MeetingRepositoryImpl
import com.aiassistant.domain.repository.MeetingRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * MeetingDataModule.kt — data module
 *
 * Purpose: Hilt [dagger.Module] that binds [MeetingRepositoryImpl] to the
 *          [MeetingRepository] domain interface.
 *
 * Architecture: data module — installs into [SingletonComponent] for process-wide singletons.
 *
 * Requirements: 19.1, 5.6
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
}
