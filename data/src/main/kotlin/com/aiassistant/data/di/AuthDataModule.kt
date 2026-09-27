package com.aiassistant.data.di

import com.aiassistant.data.remote.auth.AuthApiService
import com.aiassistant.data.repository.AuthRepositoryImpl
import com.aiassistant.domain.repository.AuthRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

/**
 * AuthDataModule.kt — data module
 *
 * Wires authentication-related bindings: [AuthRepository] → [AuthRepositoryImpl],
 * and the [AuthApiService] Retrofit factory.
 *
 * Requirements: 1.1, 1.2, 1.3, 1.10
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthDataModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    companion object {

        @Provides
        @Singleton
        fun provideAuthApiService(retrofit: Retrofit): AuthApiService = retrofit.create(AuthApiService::class.java)
    }
}
