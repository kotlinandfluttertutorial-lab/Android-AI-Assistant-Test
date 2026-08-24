/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : FederationModule.kt
 * Purpose    : Hilt module that wires federation dependencies and extends
 *              NetworkModule to route all API calls through the federation
 *              endpoint selector.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Provides FederationRepository, FailoverInterceptor, FailoverEventBus
 *   - Schedules FederationHealthCheckWorker via WorkManager (every 30 s)
 *   - Provides a Retrofit instance whose OkHttpClient includes FailoverInterceptor
 *
 * Dependencies:
 *   - Hilt, WorkManager, OkHttp, Firebase Remote Config, kotlinx.serialization,
 *     domain (FederationRepository), BackendEndpointSelector, FailoverInterceptor
 * ============================================================
 */
/**
 * FederationModule.kt — core-network module
 *
 * Purpose: Hilt [dagger.Module] that:
 * 1. Provides [FederationConfigRepository] (bound to [FederationRepository]).
 * 2. Provides [FailoverEventBus].
 * 3. Provides [FailoverInterceptor] with its [BackendEndpointSelector],
 *    [FederationRepository], [UserRegionProvider], and [UserRoleProvider] deps.
 * 4. Provides a **federation-aware** [okhttp3.OkHttpClient] and [retrofit2.Retrofit]
 *    instance (qualified with [@FederationRetrofit]) whose interceptor chain includes
 *    [FailoverInterceptor], routing all API calls through the selector.
 * 5. Schedules [FederationHealthCheckWorker] on first run using WorkManager's
 *    KEEP ExistingPeriodicWorkPolicy so only one periodic instance runs at a time.
 *
 * Retrofit qualification:
 * - The default [NetworkModule.provideRetrofit] binds the plain Retrofit instance
 *   (no federation). Use [@FederationRetrofit] when injecting the federation-aware
 *   instance in data-layer remote data sources that need backend routing.
 *
 * Architecture: core-network — installs into [dagger.hilt.components.SingletonComponent].
 * Dependencies: Hilt, WorkManager, OkHttp, Firebase Remote Config
 *
 * Requirements: 35.1, 35.3, 35.4, 35.5, 35.6, 35.7, 35.8
 */

package com.aiassistant.core.network.di

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.aiassistant.core.network.AuthInterceptor
import com.aiassistant.core.network.BuildConfig
import com.aiassistant.core.network.CertificatePinningInterceptor
import com.aiassistant.core.network.RefreshTokenInterceptor
import com.aiassistant.core.network.federation.BackendEndpointSelector
import com.aiassistant.core.network.federation.FailoverBannerStateProvider
import com.aiassistant.core.network.federation.FailoverEventBus
import com.aiassistant.core.network.federation.FailoverInterceptor
import com.aiassistant.core.network.federation.FederationConfigRepository
import com.aiassistant.core.network.federation.FederationHealthCheckWorker
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

// ─── Qualifier annotation ────────────────────────────────────────────────────

/**
 * Qualifier for the federation-aware [Retrofit] instance whose OkHttpClient includes
 * the [FailoverInterceptor] for automatic endpoint selection and failover.
 *
 * Inject this instance in data-layer remote data sources that need backend routing.
 *
 * Example:
 * ```kotlin
 * class ChatRemoteDataSource @Inject constructor(
 *     @FederationRetrofit retrofit: Retrofit,
 * ) { ... }
 * ```
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class FederationRetrofit

// ─── WorkManager periodic work tag ───────────────────────────────────────────

private const val HEALTH_CHECK_WORK_NAME = "federation_health_check"

@Module
@InstallIn(SingletonComponent::class)
object FederationModule {

    // ─── FederationConfigRepository (bound to FederationRepository) ──────────

    @Provides
    @Singleton
    fun provideFederationConfigRepository(remoteConfig: FirebaseRemoteConfig, json: Json): FederationConfigRepository =
        FederationConfigRepository(
            remoteConfig = remoteConfig,
            json = json,
            isDebugBuild = BuildConfig.DEBUG
        )

    @Provides
    @Singleton
    fun provideFederationRepository(
        impl: FederationConfigRepository
    ): com.aiassistant.domain.repository.FederationRepository = impl

    // ─── FailoverEventBus ────────────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideFailoverEventBus(): FailoverEventBus = FailoverEventBus()

    // ─── BackendEndpointSelector ─────────────────────────────────────────────

    @Provides
    @Singleton
    fun provideBackendEndpointSelector(): BackendEndpointSelector = BackendEndpointSelector()

    // ─── FailoverBannerStateProvider ─────────────────────────────────────────

    /**
     * Provides a singleton [FailoverBannerStateProvider] that exposes a
     * [kotlinx.coroutines.flow.StateFlow]<[com.aiassistant.core.network.federation.FailoverBannerState]>
     * for UI-layer ViewModels to observe.
     *
     * The banner becomes visible when a failover occurs ([FailoverEvent.SwitchedToEndpoint])
     * and auto-dismisses when the primary endpoint recovers
     * ([FailoverEvent.PrimaryEndpointRecovered]) — satisfying Requirement 35.6.
     */
    @Provides
    @Singleton
    fun provideFailoverBannerStateProvider(eventBus: FailoverEventBus): FailoverBannerStateProvider =
        FailoverBannerStateProvider(eventBus)

    // ─── Federation-aware OkHttpClient ───────────────────────────────────────

    /**
     * Provides a dedicated [OkHttpClient] that includes [FailoverInterceptor] in its
     * interceptor chain. All other interceptors (auth, certificate pinning, refresh) are
     * also included to preserve the full security stack.
     */
    @Provides
    @Singleton
    @FederationRetrofit
    fun provideFederationOkHttpClient(
        authInterceptor: AuthInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
        refreshTokenInterceptor: RefreshTokenInterceptor,
        loggingInterceptor: HttpLoggingInterceptor,
        failoverInterceptor: FailoverInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        // FailoverInterceptor MUST come first to rewrite the base URL before auth/pinning.
        .addInterceptor(failoverInterceptor)
        .addInterceptor(authInterceptor)
        .addInterceptor(certificatePinningInterceptor)
        .authenticator(refreshTokenInterceptor)
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30L, TimeUnit.SECONDS)
        .readTimeout(60L, TimeUnit.SECONDS)
        .writeTimeout(60L, TimeUnit.SECONDS)
        .build()

    // ─── Federation-aware Retrofit ───────────────────────────────────────────

    /**
     * Provides a [Retrofit] instance backed by the federation-aware [OkHttpClient].
     * Uses a placeholder base URL — [FailoverInterceptor] dynamically rewrites it for
     * every request based on the selected endpoint.
     */
    @Provides
    @Singleton
    @FederationRetrofit
    fun provideFederationRetrofit(@FederationRetrofit okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            // Placeholder base URL — overridden at request time by FailoverInterceptor.
            .baseUrl(BuildConfig.BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    // ─── WorkManager: FederationHealthCheckWorker ────────────────────────────

    /**
     * Schedules [FederationHealthCheckWorker] to run every 30 seconds when the device
     * has network connectivity. Uses [ExistingPeriodicWorkPolicy.KEEP] to avoid
     * duplicating work requests if [FederationModule] is instantiated more than once.
     *
     * This function is called from the Application's [onCreate] via an [ApplicationContext]
     * injection rather than being a @Provides function, because WorkManager requires
     * [Context] and returns [Unit]. In practice, the scheduling is triggered by the
     * HiltWorkerFactory-aware Application class.
     *
     * @param context Application [Context] used to get the [WorkManager] instance.
     */
    fun scheduleFederationHealthCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<FederationHealthCheckWorker>(
            30L,
            TimeUnit.SECONDS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            HEALTH_CHECK_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
