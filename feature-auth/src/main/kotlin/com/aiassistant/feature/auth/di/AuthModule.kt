package com.aiassistant.feature.auth.di

import com.aiassistant.domain.repository.AuthRepository
import com.aiassistant.domain.usecase.auth.LoginUseCase
import com.aiassistant.domain.usecase.auth.LoginWithGoogleUseCase
import com.aiassistant.domain.usecase.auth.RegisterUseCase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AuthModule.kt — feature-auth module
 *
 * Provides domain use case instances for [AuthViewModel]. Domain use cases have no
 * @Inject constructor (pure Kotlin, no DI framework dependency), so they are wired
 * here via @Provides factory methods.
 *
 * Requirements: 1.1, 1.2, 1.6
 */
@Module
@InstallIn(SingletonComponent::class)
object AuthModule {

    @Provides
    @Singleton
    fun provideLoginUseCase(authRepository: AuthRepository): LoginUseCase = LoginUseCase(authRepository)

    @Provides
    @Singleton
    fun provideRegisterUseCase(authRepository: AuthRepository): RegisterUseCase = RegisterUseCase(authRepository)

    /** Provides [LoginWithGoogleUseCase] for Google OAuth2 sign-in (Requirement 1.6). */
    @Provides
    @Singleton
    fun provideLoginWithGoogleUseCase(authRepository: AuthRepository): LoginWithGoogleUseCase =
        LoginWithGoogleUseCase(authRepository)
}
