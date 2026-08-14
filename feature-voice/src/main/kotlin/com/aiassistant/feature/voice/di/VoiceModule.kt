package com.aiassistant.feature.voice.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * VoiceModule.kt — feature-voice module
 *
 * [SendMessageUseCase] is provided by feature-chat's ChatModule (ViewModelComponent),
 * which is shared across the whole app. No duplicate binding needed here.
 *
 * Requirements: 5.1, 5.2
 */
@Module
@InstallIn(ViewModelComponent::class)
object VoiceModule
