/**
 * AppModule.kt — app module
 *
 * Purpose: Provides app-level primitive bindings that library modules cannot supply
 *          themselves because they do not have access to the application's BuildConfig.
 *
 *          The canonical example is the `isDebugBuild` flag: a library module's own
 *          BuildConfig.DEBUG is **always false** at compile time regardless of the
 *          app's build type, because the Android Gradle plugin sets `DEBUG = false` in
 *          library BuildConfig fields. The only reliable source of the truth is the
 *          *app* module's BuildConfig, which is what this module exposes.
 *
 * Architecture: app module — DI wiring. Installs into [SingletonComponent].
 */
package com.aiassistant.di

import com.aiassistant.BuildConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    /**
     * Exposes whether the current build is a debug build.
     *
     * Library modules (core-network, core-security, etc.) MUST NOT read their own
     * `BuildConfig.DEBUG` for runtime behaviour decisions — that field is always `false`
     * in library modules. Inject this binding instead:
     *
     * ```kotlin
     * @Named("isDebugBuild") isDebug: Boolean
     * ```
     *
     * Used by:
     * - [com.aiassistant.core.network.di.NetworkModule] — bypasses certificate pinning
     *   in debug so local/staging servers are reachable without pinned certificates.
     */
    @Provides
    @Singleton
    @Named("isDebugBuild")
    fun provideIsDebugBuild(): Boolean = BuildConfig.DEBUG
}
