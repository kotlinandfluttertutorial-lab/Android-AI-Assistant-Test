package com.aiassistant.data.di

import com.aiassistant.core.common.DefaultDispatcherProvider
import com.aiassistant.core.common.DispatcherProvider
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
 * [DispatcherProvider] via factory (core-common has no javax.inject dependency),
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

        /**
         * Provides [DispatcherProvider] via factory method.
         *
         * [DefaultDispatcherProvider] lives in core-common which deliberately has no
         * javax.inject / Hilt dependency, so it cannot carry an @Inject constructor.
         * A @Provides factory is the correct pattern here.
         */
        @Provides
        @Singleton
        fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
    }
}
