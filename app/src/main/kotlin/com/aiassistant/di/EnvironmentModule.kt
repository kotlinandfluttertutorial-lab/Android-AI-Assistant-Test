/**
 * EnvironmentModule.kt — app module
 *
 * Purpose: Hilt module that provides the [EnvironmentConfig] singleton to the DI graph.
 *
 * Architecture: app module — DI wiring. Installs into [SingletonComponent].
 *
 * Why here and not in core-network?
 *   Hilt modules in library modules are always included — there is no way to swap the
 *   binding per variant from within the library. By placing the @Provides method here
 *   (in the app module), a future test module or debug override can replace the binding
 *   via a [@TestInstallIn] or a separate debug-flavour Hilt module without touching
 *   production code.
 *
 *   This also mirrors the precedent set by [AppModule] for `isDebugBuild`.
 *
 * Consuming modules (NetworkModule, FederationModule, AIStreamClientImpl) depend on
 * [EnvironmentConfig], never on BuildConfig directly.
 *
 * Security note: [EnvironmentConfig] exposes only URLs and environment flags — no API
 * keys, JWT secrets, or credentials. Sensitive backend secrets belong in GCP Secret
 * Manager and are never embedded in the APK.
 */
package com.aiassistant.di

import com.aiassistant.core.network.BuildConfigEnvironmentConfig
import com.aiassistant.core.network.EnvironmentConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EnvironmentModule {

    /**
     * Provides the single [EnvironmentConfig] instance for the process lifetime.
     *
     * [BuildConfigEnvironmentConfig] reads flavor-specific [com.aiassistant.core.network.BuildConfig]
     * fields set in `core-network/build.gradle.kts`. Because the fields are compile-time
     * constants, this object is effectively immutable after construction.
     *
     * To override in tests:
     * ```kotlin
     * @TestInstallIn(components = [SingletonComponent::class], replaces = [EnvironmentModule::class])
     * @Module
     * object FakeEnvironmentModule {
     *     @Provides @Singleton
     *     fun provideEnvironmentConfig(): EnvironmentConfig = FakeEnvironmentConfig(
     *         apiBaseUrl = "http://localhost:8080/",
     *         websocketUrl = "ws://localhost:8080",
     *         environmentName = "test",
     *         isProduction = false
     *     )
     * }
     * ```
     */
    @Provides
    @Singleton
    fun provideEnvironmentConfig(): EnvironmentConfig = BuildConfigEnvironmentConfig()
}
