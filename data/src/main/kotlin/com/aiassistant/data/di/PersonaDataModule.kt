package com.aiassistant.data.di

import com.aiassistant.data.remote.persona.PersonaApiService
import com.aiassistant.data.repository.PersonaPreferencesRepositoryImpl
import com.aiassistant.data.repository.PersonaRepositoryImpl
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import com.aiassistant.domain.repository.PersonaRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class PersonaDataModule {

    @Binds
    @Singleton
    abstract fun bindPersonaRepository(impl: PersonaRepositoryImpl): PersonaRepository

    @Binds
    @Singleton
    abstract fun bindPersonaPreferencesRepository(
        impl: PersonaPreferencesRepositoryImpl,
    ): PersonaPreferencesRepository

    companion object {
        @Provides
        @Singleton
        fun providePersonaApiService(retrofit: Retrofit): PersonaApiService =
            retrofit.create(PersonaApiService::class.java)
    }
}
