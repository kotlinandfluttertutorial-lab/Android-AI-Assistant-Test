package com.aiassistant.data.di

import com.aiassistant.core.common.DefaultDispatcherProvider
import com.aiassistant.core.common.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * DispatcherModule.kt — data module
 *
 * Provides [DispatcherProvider] singleton binding.
 * [DefaultDispatcherProvider] lives in core-common which deliberately has no
 * javax.inject / Hilt dependency, so it cannot carry an @Inject constructor.
 * A @Provides factory in an object module is used here to avoid companion object
 * nest host verification issues in zero-parameter singleton providers.
 */
@Module
@InstallIn(SingletonComponent::class)
object DispatcherModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()
}
