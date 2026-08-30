/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : NetworkModule.kt
 * Purpose    : Hilt module providing Network dependencies to the DI graph
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : NetworkModule.kt
 * Purpose    : Hilt module providing Network dependencies to the DI graph
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : Hilt DI Module
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * NetworkModule.kt â€” core-network module
 *
 * Purpose: Hilt [dagger.Module] that assembles and exposes the fully configured
 *          [OkHttpClient] and [Retrofit] instance used by all remote data sources in the
 *          `data` module.
 *
 * Architecture: core-network â€” installs into [dagger.hilt.components.SingletonComponent]
 *               so every binding is a process-wide singleton.
 * Dependencies: Hilt, OkHttp, Retrofit, kotlinx.serialization, core-security, core-network
 *
 * Component wiring:
 * ```
 * OkHttpClient
 *   â”œâ”€â”€ addInterceptor(AuthInterceptor)             â€” attaches Bearer JWT
 *   â”œâ”€â”€ addInterceptor(CertificatePinningInterceptor) â€” rejects un-pinned certs
 *   â”œâ”€â”€ authenticator(RefreshTokenInterceptor)      â€” handles 401 / token refresh
 *   â””â”€â”€ addInterceptor(HttpLoggingInterceptor)      â€” BODY in debug, NONE in release
 *
 * Retrofit
 *   â”œâ”€â”€ baseUrl(BASE_URL)
 *   â”œâ”€â”€ addConverterFactory(kotlinx.serialization)
 *   â””â”€â”€ client(OkHttpClient)
 *
 * AuthRefreshApi
 *   â””â”€â”€ Retrofit.create(AuthRefreshApi::class.java)
 * ```
 *
 * Certificate pinning:
 * The production pin set is read from [com.aiassistant.core.network.BuildConfig.CERTIFICATE_PINS]
 * (a semicolon-separated list of Base64 SHA-256 SPKI hashes configured in the module's
 * `buildConfigField`). In debug builds `bypass = true` is passed to
 * [CertificatePinningInterceptor] so local and staging servers are reachable without a
 * pinned certificate.
 *
 * Requirements: 9.5 (certificate pinning), 1.3 (JWT refresh).
 */
package com.aiassistant.core.network.di

import com.aiassistant.core.network.AuthInterceptor
import com.aiassistant.core.network.AuthRefreshApi
import com.aiassistant.core.network.BuildConfig
import com.aiassistant.core.network.CertificatePinningInterceptor
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.network.LogoutEventBus
import com.aiassistant.core.network.NetworkConnectivityObserver
import com.aiassistant.core.network.RefreshTokenInterceptor
import com.aiassistant.core.network.observability.NetworkObservabilityInterceptor
import com.aiassistant.core.network.observability.ObservabilityBaseUrl
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    // â”€â”€â”€ Configuration constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Base URL for all Backend API calls.
     *
     * Override via BuildConfig for staging / production environments.
     * Must end with a trailing slash for Retrofit to resolve relative paths correctly.
     */
    private val BASE_URL: String get() = BuildConfig.BASE_URL
    // Override per build variant via the `base_url` Gradle property:
    //   debug   default → http://10.0.2.2:8000/  (Android emulator localhost)
    //   release default → https://ai-assistant-backend-106071012091.asia-south1.run.app/
    //   ci/staging      → pass -Pbase_url="https://your-cloud-run-url.run.app/"

    private const val CONNECT_TIMEOUT_SECONDS = 30L

    // 90 s gives Cloud Run cold-start containers (typically 10–60 s) time to respond
    // before the client gives up. The previous value of 60 s was too tight.
    private const val READ_TIMEOUT_SECONDS = 90L
    private const val WRITE_TIMEOUT_SECONDS = 60L

    @Provides
    @Singleton
    @ObservabilityBaseUrl
    fun provideObservabilityBaseUrl(): String = BASE_URL

    // â”€â”€â”€ JSON serializer â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    // â”€â”€â”€ Logging interceptor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideLoggingInterceptor(@Named("isDebugBuild") isDebug: Boolean): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (isDebug) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
            if (isDebug) {
                redactHeader("Authorization")
            }
        }

    // â”€â”€â”€ Certificate pinning interceptor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideCertificatePinningInterceptor(@Named("isDebugBuild") isDebug: Boolean): CertificatePinningInterceptor =
        if (isDebug) {
            // In debug builds bypass pinning entirely so local/staging servers work.
            // NOTE: do NOT use BuildConfig.DEBUG from core-network here — library module
            // BuildConfig.DEBUG is always false regardless of the app's build type. The
            // correct value is injected via @Named("isDebugBuild") from AppModule, which
            // reads from the *app* module's BuildConfig.DEBUG.
            CertificatePinningInterceptor(pinnedSha256Hashes = emptySet(), bypass = true)
        } else {
            // In release builds read the pin set from BuildConfig.
            // Configure via: buildConfigField("String", "CERTIFICATE_PINS", "\"hash1;hash2\"")
            val pinsCsv: String = BuildConfig.CERTIFICATE_PINS
            val pins = pinsCsv
                .split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .toSet()
            CertificatePinningInterceptor(pinnedSha256Hashes = pins, bypass = false)
        }

    // â”€â”€â”€ OkHttpClient â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        certificatePinningInterceptor: CertificatePinningInterceptor,
        refreshTokenInterceptor: RefreshTokenInterceptor,
        observabilityInterceptor: NetworkObservabilityInterceptor,
        loggingInterceptor: HttpLoggingInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        // Application-level interceptors run for every request (including retries).
        .addInterceptor(authInterceptor)
        .addInterceptor(certificatePinningInterceptor)
        // Authenticator is called only when the server returns HTTP 401.
        .authenticator(refreshTokenInterceptor)
        // Observability: captures latency, status codes, and errors as structured events.
        // Positioned after auth/pinning so the final request state is measured,
        // and before logging so all events are emitted even if the logger is NONE.
        .addInterceptor(observabilityInterceptor)
        // For binary request/response bodies (multipart uploads, PDF downloads etc.)
        // temporarily drop the logging level to HEADERS to prevent raw bytes flooding
        // logcat. Level is restored after the call completes.
        .addInterceptor { chain ->
            val isBinaryRequest = chain.request().body?.contentType()?.let { ct ->
                ct.type == "multipart" ||
                    ct.subtype == "pdf" ||
                    ct.type == "image" ||
                    ct.subtype == "octet-stream"
            } ?: false

            if (isBinaryRequest && loggingInterceptor.level == HttpLoggingInterceptor.Level.BODY) {
                loggingInterceptor.level = HttpLoggingInterceptor.Level.HEADERS
                try {
                    chain.proceed(chain.request())
                } finally {
                    loggingInterceptor.level = HttpLoggingInterceptor.Level.BODY
                }
            } else {
                chain.proceed(chain.request())
            }
        }
        // Logging last so it captures the final (possibly modified) request/response.
        .addInterceptor(loggingInterceptor)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    // â”€â”€â”€ Retrofit â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit {
        val contentType = "application/json".toMediaType()
        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory(contentType))
            .build()
    }

    // â”€â”€â”€ AuthRefreshApi â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideAuthRefreshApi(retrofit: Retrofit): AuthRefreshApi = retrofit.create(AuthRefreshApi::class.java)

    // â”€â”€â”€ ConnectivityObserver binding â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideConnectivityObserver(impl: NetworkConnectivityObserver): ConnectivityObserver = impl

    // â”€â”€â”€ LogoutEventBus â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    @Provides
    @Singleton
    fun provideLogoutEventBus(): LogoutEventBus = LogoutEventBus()
}
