/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-meeting
 * File       : MeetingModule.kt
 * Purpose    : Hilt module providing Meeting dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * Module     : feature-meeting
 * File       : MeetingModule.kt
 * Purpose    : Hilt module providing Meeting dependencies to the DI graph
 *
 * Architecture Layer : Feature (feature-meeting)
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
 * MeetingModule.kt
 *
 * Purpose: Hilt [dagger.Module] providing domain use case instances needed by
 *          [MeetingViewModel]. The use cases do not carry @Inject annotations in the
 *          domain module (pure-Kotlin, no DI framework dependency by design), so they
 *          are provided here via @Provides factory methods.
 * Architecture: feature-meeting â€” installs into [dagger.hilt.components.SingletonComponent];
 *               only feature-meeting and the app module depend on these bindings.
 * Dependencies: domain (StartMeetingRecordingUseCase, StopMeetingRecordingUseCase,
 *               GetMeetingSummaryUseCase, MeetingRepository)
 *
 * Requirements: 19.1
 *
 * Design decisions:
 * - Scoped to Singleton so the single MeetingRepository instance is shared across all
 *   ViewModel instances over the app lifecycle.
 * - Feature module MUST NOT depend on :data â€” MeetingRepository is resolved from the
 *   data module's binding registered at the app/data level, not imported here directly.
 */
package com.aiassistant.feature.meeting.di

import com.aiassistant.domain.repository.MeetingRepository
import com.aiassistant.domain.usecase.meeting.GetMeetingSummaryUseCase
import com.aiassistant.domain.usecase.meeting.StartMeetingRecordingUseCase
import com.aiassistant.domain.usecase.meeting.StopMeetingRecordingUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for the feature-meeting module.
 *
 * Provides all three meeting use cases using the application-level [MeetingRepository]
 * singleton that is bound by the data module's Hilt component.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeetingModule {

    /**
     * Provides [StartMeetingRecordingUseCase] for use in [MeetingViewModel].
     *
     * @param meetingRepository Application-level meeting repository singleton.
     * @return A singleton [StartMeetingRecordingUseCase] instance.
     */
    @Provides
    @Singleton
    fun provideStartMeetingRecordingUseCase(meetingRepository: MeetingRepository): StartMeetingRecordingUseCase =
        StartMeetingRecordingUseCase(meetingRepository)

    /**
     * Provides [StopMeetingRecordingUseCase] for use in [MeetingViewModel].
     *
     * @param meetingRepository Application-level meeting repository singleton.
     * @return A singleton [StopMeetingRecordingUseCase] instance.
     */
    @Provides
    @Singleton
    fun provideStopMeetingRecordingUseCase(meetingRepository: MeetingRepository): StopMeetingRecordingUseCase =
        StopMeetingRecordingUseCase(meetingRepository)

    /**
     * Provides [GetMeetingSummaryUseCase] for use in [MeetingViewModel].
     *
     * @param meetingRepository Application-level meeting repository singleton.
     * @return A singleton [GetMeetingSummaryUseCase] instance.
     */
    @Provides
    @Singleton
    fun provideGetMeetingSummaryUseCase(meetingRepository: MeetingRepository): GetMeetingSummaryUseCase =
        GetMeetingSummaryUseCase(meetingRepository)
}
