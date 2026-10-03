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

import android.app.Application
import android.content.ContentResolver
import com.aiassistant.BuildConfig
import com.aiassistant.core.network.EnvironmentConfig
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

    /**
     * Provides the API base URL as a named string binding.
     *
     * [ImageAnalysisRemoteDataSourceImpl] and any other data-layer class that needs
     * the raw URL string can inject `@Named("apiBaseUrl") baseUrl: String` rather than
     * depending on the full [EnvironmentConfig].
     */
    @Provides
    @Singleton
    @Named("apiBaseUrl")
    fun provideApiBaseUrl(config: EnvironmentConfig): String = config.apiBaseUrl

    /**
     * Provides the application's [ContentResolver] for data-layer components that
     * need to read content URIs (e.g. [com.aiassistant.data.remote.image.ImageAnalysisRemoteDataSourceImpl]).
     *
     * Injecting [ContentResolver] directly (rather than [android.content.Context]) keeps
     * those classes narrowly scoped to the single capability they need.
     */
    @Provides
    @Singleton
    fun provideContentResolver(application: Application): ContentResolver =
        application.contentResolver
}
