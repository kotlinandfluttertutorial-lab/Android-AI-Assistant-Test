/**
 * DevOpsDataModule.kt — data module
 *
 * Hilt module wiring the DevOps data layer: Retrofit services, remote
 * data sources, and repository bindings.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.di

import com.aiassistant.data.remote.devops.DevOpsApiService
import com.aiassistant.data.remote.devops.IncidentApiService
import com.aiassistant.data.repository.DevOpsRepositoryImpl
import com.aiassistant.data.repository.IncidentRepositoryImpl
import com.aiassistant.domain.repository.DevOpsRepository
import com.aiassistant.domain.repository.IncidentRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class DevOpsDataModule {

    @Binds
    @Singleton
    abstract fun bindIncidentRepository(impl: IncidentRepositoryImpl): IncidentRepository

    @Binds
    @Singleton
    abstract fun bindDevOpsRepository(impl: DevOpsRepositoryImpl): DevOpsRepository

    companion object {

        @Provides
        @Singleton
        fun provideIncidentApiService(retrofit: Retrofit): IncidentApiService =
            retrofit.create(IncidentApiService::class.java)

        @Provides
        @Singleton
        fun provideDevOpsApiService(retrofit: Retrofit): DevOpsApiService =
            retrofit.create(DevOpsApiService::class.java)
    }
}
