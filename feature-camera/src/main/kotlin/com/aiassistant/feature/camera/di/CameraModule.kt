package com.aiassistant.feature.camera.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ViewModelComponent

/**
 * CameraModule.kt — feature-camera module
 *
 * [SendMessageUseCase] is provided by feature-chat's ChatModule (ViewModelComponent),
 * which is shared across the whole app. No duplicate binding needed here.
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
 */
@Module
@InstallIn(ViewModelComponent::class)
object CameraModule
