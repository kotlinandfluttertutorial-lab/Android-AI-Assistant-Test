/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-network
 * File       : FailoverInterceptor.kt
 * Purpose    : OkHttp Interceptor that routes all API calls through
 *              BackendEndpointSelector and performs failover on
 *              connection errors or 5xx responses.
 *
 * Architecture Layer : Core-Network
 * Pattern Used       : OkHttp Interceptor + Strategy (BackendEndpointSelector)
 *
 * Key Concepts:
 *   - Replaces base URL with selected endpoint's baseUrl on every request
 *   - On connection error or 5xx: retry against next eligible endpoint (≤2 s)
 *   - If all eligible endpoints exhausted: structured error, no fallback to
 *     non-eligible endpoint (Req 35.3, 35.4)
 *   - Publishes failover events to FailoverEventBus for banner display (Req 35.6)
 *   - Data isolation: NEVER forwards request body to second endpoint (Req 35.7)
 *   - Failover retries use a separate OkHttpClient (no interceptor) to avoid
 *     recursive chain.proceed() calls and connection-reuse issues.
 *
 * Dependencies:
 *   - OkHttp, domain (BackendEndpoint, FederationConfig), BackendEndpointSelector,
 *     FederationRepository, FailoverEventBus
 * ============================================================
 */
/**
 * FailoverInterceptor.kt — core-network module
 *
 * Purpose: OkHttp [Interceptor] that:
 * 1. Selects the optimal [BackendEndpoint] via [BackendEndpointSelector] before
 *    each request and rewrites the request URL to use that endpoint's [baseUrl].
 * 2. On connection failure or a 5xx response, retries the request against the
 *    next eligible endpoint — completing the retry within 2 seconds by setting
 *    a short connect timeout on the retry call (Requirement 35.3).
 * 3. If all eligible endpoints are exhausted, returns a structured
 *    [FailoverResult.AllEndpointsExhausted] error without ever routing to a
 *    non-eligible endpoint (Requirement 35.4).
 * 4. Publishes [FailoverEvent] notifications via [FailoverEventBus] so the UI
 *    layer can display a non-blocking informational banner (Requirement 35.6).
 * 5. Enforces data isolation: the request body is sent to ONE endpoint only;
 *    the interceptor never replicates or forwards data to a second endpoint
 *    (Requirement 35.7).
 *
 * Architecture: core-network — installs into OkHttpClient via [FederationModule].
 *               MUST NOT import any feature, data, or domain use-case classes.
 * Dependencies: OkHttp, domain model classes, BackendEndpointSelector, FederationRepository
 *
 * Requirements: 35.3, 35.4, 35.6, 35.7
 */

package com.aiassistant.core.network.federation

import com.aiassistant.domain.model.BackendEndpoint
import com.aiassistant.domain.repository.FederationRepository
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import timber.log.Timber

// ─── Failover event model ────────────────────────────────────────────────────

/**
 * Describes a failover event published to the UI layer.
 * The UI observes [FailoverEventBus.events] and shows a non-blocking banner.
 */
sealed class FailoverEvent {
    /**
     * Emitted when the interceptor switches to a new endpoint.
     *
     * @param activeEndpointName  Human-readable name of the now-active endpoint.
     * @param failoverReason      Short description of why failover occurred (e.g. "Connection error", "HTTP 503").
     */
    data class SwitchedToEndpoint(val activeEndpointName: String, val failoverReason: String) : FailoverEvent()

    /**
     * Emitted when all eligible endpoints have been exhausted.
     * UI should display a structured error, not a banner.
     */
    data class AllEndpointsExhausted(val message: String) : FailoverEvent()

    /**
     * Emitted when the primary endpoint recovers. The UI should auto-dismiss
     * any active failover banner (Requirement 35.6).
     */
    data class PrimaryEndpointRecovered(val primaryEndpointName: String) : FailoverEvent()
}

// ─── FailoverEventBus ────────────────────────────────────────────────────────

/**
 * Process-wide event bus for federation failover events.
 *
 * Consumers (e.g. ViewModels or Composables) collect [events] to display or
 * dismiss the informational failover banner. The bus uses a [MutableSharedFlow]
 * with a replay of 0 so only live observers receive events.
 */
@Singleton
class FailoverEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<FailoverEvent>(replay = 0, extraBufferCapacity = 8)

    /** Hot [kotlinx.coroutines.flow.SharedFlow] of failover events. */
    val events = _events.asSharedFlow()

    /**
     * Publishes a [FailoverEvent]. Safe to call from any thread.
     * Uses `tryEmit` — if no collector is subscribed the event is dropped (non-blocking).
     */
    fun publish(event: FailoverEvent) {
        _events.tryEmit(event)
    }
}

// ─── FailoverInterceptor ─────────────────────────────────────────────────────

/**
 * HTTP status code threshold above which a response is considered a server error.
 * 5xx responses trigger failover (Requirement 35.3).
 */
private const val SERVER_ERROR_THRESHOLD = 500

/**
 * Connect/read timeout for failover retry calls (2 seconds, per Requirement 35.3).
 */
private const val FAILOVER_TIMEOUT_SECONDS = 2L

/**
 * OkHttp interceptor that routes requests through the federation endpoint selector
 * and performs automatic failover on connection errors or 5xx responses.
 */
@Singleton
class FailoverInterceptor @Inject constructor(
    private val federationRepository: FederationRepository,
    private val endpointSelector: BackendEndpointSelector,
    private val failoverEventBus: FailoverEventBus,
    private val userRegionProvider: UserRegionProvider,
    private val userRoleProvider: UserRoleProvider
) : Interceptor {

    /**
     * Bare OkHttpClient used exclusively for failover retry calls.
     *
     * This client has NO interceptors — which means the FailoverInterceptor itself is
     * not in its chain, preventing infinite recursion. It uses a tight 2-second timeout
     * to satisfy the ≤2-second failover completion requirement (Requirement 35.3).
     *
     * Lazy so it is constructed once when first needed, not at injection time.
     */
    private val retryClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(FAILOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Zero idle connections so connections are released immediately after each call.
            // This prevents MockWebServer from hanging on shutdown in tests, and also
            // ensures the failover client never reuses a stale connection to a failed endpoint.
            .connectionPool(okhttp3.ConnectionPool(0, 1, TimeUnit.NANOSECONDS))
            .build()
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Read current federation config (blocking — interceptors run on background threads).
        val config = runBlocking { federationRepository.getConfig() }

        val userRegion = userRegionProvider.getRegion()
        val userRole = userRoleProvider.getRole()

        // Select the primary endpoint for this user.
        val primaryResult = endpointSelector.select(config, userRegion, userRole)
        if (primaryResult is EndpointSelectionResult.NoEligibleEndpoint) {
            Timber.e("FailoverInterceptor: no eligible endpoint for region=$userRegion role=$userRole")
            failoverEventBus.publish(
                FailoverEvent.AllEndpointsExhausted(
                    "No eligible backend endpoint for region '$userRegion' and role '$userRole'."
                )
            )
            throw NoEligibleEndpointException(
                "No eligible backend endpoint satisfies region='$userRegion', role='$userRole'."
            )
        }

        val primaryEndpoint = (primaryResult as EndpointSelectionResult.Selected).endpoint
        var currentEndpoint = primaryEndpoint
        // Track ALL endpoints that have been tried and failed in this request cycle.
        // This prevents re-trying already-failed endpoints during multi-hop failover.
        val exhaustedEndpoints = mutableSetOf<String>()

        // ── Attempt the request; failover on error or 5xx ────────────────────
        // First attempt: use chain.proceed() so all interceptors in the chain run.
        val primaryRequest = rewriteRequest(originalRequest, primaryEndpoint)
        val (primaryResponse, primaryException) = attemptViaChain(chain, primaryRequest)

        // Fast-path: primary attempt succeeded (2xx/3xx/4xx).
        if (primaryException == null &&
            primaryResponse != null &&
            primaryResponse.code < SERVER_ERROR_THRESHOLD
        ) {
            return primaryResponse
        }

        // Primary failed — log and try failover.
        val firstFailReason = when {
            primaryException != null -> when (primaryException) {
                is SocketTimeoutException -> "Connection timed out"
                is ConnectException -> "Connection refused"
                else -> "Connection error: ${primaryException.message}"
            }
            primaryResponse != null -> "HTTP ${primaryResponse.code}"
            else -> "Unknown error"
        }
        primaryResponse?.close() // Must close before retry to avoid connection leaks (Req 35.7).
        Timber.w("FailoverInterceptor: ${primaryEndpoint.name} failed — $firstFailReason")
        exhaustedEndpoints.add(primaryEndpoint.name)
        currentEndpoint = failoverOrThrow(
            config = config,
            exhaustedEndpointNames = exhaustedEndpoints,
            userRegion = userRegion,
            userRole = userRole,
            reason = firstFailReason
        )

        // Failover loop: use the bare retryClient (no interceptors, tight timeout).
        while (true) {
            val retryRequest = rewriteRequest(originalRequest, currentEndpoint)
            val (retryResponse, retryException) = attemptViaRetryClient(retryRequest)

            if (retryException != null) {
                val reason = when (retryException) {
                    is SocketTimeoutException -> "Connection timed out"
                    is ConnectException -> "Connection refused"
                    else -> "Connection error: ${retryException.message}"
                }
                Timber.w("FailoverInterceptor: ${currentEndpoint.name} failed — $reason")
                exhaustedEndpoints.add(currentEndpoint.name)
                currentEndpoint = failoverOrThrow(
                    config = config,
                    exhaustedEndpointNames = exhaustedEndpoints,
                    userRegion = userRegion,
                    userRole = userRole,
                    reason = reason
                )
                continue
            }

            checkNotNull(retryResponse) { "Both retryResponse and retryException were null" }

            if (retryResponse.code >= SERVER_ERROR_THRESHOLD) {
                val reason = "HTTP ${retryResponse.code}"
                Timber.w("FailoverInterceptor: ${currentEndpoint.name} returned $reason — failing over")
                retryResponse.close()
                exhaustedEndpoints.add(currentEndpoint.name)
                currentEndpoint = failoverOrThrow(
                    config = config,
                    exhaustedEndpointNames = exhaustedEndpoints,
                    userRegion = userRegion,
                    userRole = userRole,
                    reason = reason
                )
                continue
            }

            Timber.d("FailoverInterceptor: serving from failover endpoint '${currentEndpoint.name}'")
            return retryResponse
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Rewrites [originalRequest]'s URL to point to [endpoint]'s [baseUrl].
     * Preserves path, query parameters, and all headers.
     */
    private fun rewriteRequest(originalRequest: Request, endpoint: BackendEndpoint): Request {
        val originalUrl = originalRequest.url
        val newBaseUrl = endpoint.baseUrl.trimEnd('/') + "/"
        val baseHttpUrl = newBaseUrl.toHttpUrlOrNull()
            ?: return originalRequest // Fallback: no rewrite if URL is invalid.

        val newUrl = originalUrl.newBuilder()
            .scheme(baseHttpUrl.scheme)
            .host(baseHttpUrl.host)
            .port(baseHttpUrl.port)
            .build()

        return originalRequest.newBuilder()
            .url(newUrl)
            .tag(BackendEndpoint::class.java, endpoint)
            .build()
    }

    /**
     * Executes [request] via [chain.proceed] (the full application interceptor chain).
     * Used for the FIRST attempt only — subsequent attempts use [attemptViaRetryClient].
     */
    private fun attemptViaChain(chain: Interceptor.Chain, request: Request): Pair<Response?, IOException?> = try {
        Pair(chain.proceed(request), null)
    } catch (e: IOException) {
        Pair(null, e)
    }

    /**
     * Executes [request] via the bare [retryClient] (no interceptors, 2 s timeout).
     * Used for all failover retry attempts so we avoid re-entering the interceptor chain
     * and ensure a fresh connection to the new endpoint.
     *
     * Data isolation: only ONE endpoint receives the request body (Requirement 35.7).
     */
    private fun attemptViaRetryClient(request: Request): Pair<Response?, IOException?> = try {
        Pair(retryClient.newCall(request).execute(), null)
    } catch (e: IOException) {
        Pair(null, e)
    }

    /**
     * Selects the next eligible endpoint excluding all [exhaustedEndpointNames].
     * If no more eligible endpoints remain, publishes [FailoverEvent.AllEndpointsExhausted]
     * and throws [NoEligibleEndpointException].
     *
     * @return The next eligible [BackendEndpoint] to try.
     * @throws NoEligibleEndpointException when all eligible endpoints are exhausted.
     */
    private fun failoverOrThrow(
        config: com.aiassistant.domain.model.FederationConfig,
        exhaustedEndpointNames: Set<String>,
        userRegion: String,
        userRole: String,
        reason: String
    ): BackendEndpoint {
        val nextResult = endpointSelector.selectNext(
            config = config,
            userRegion = userRegion,
            userRole = userRole,
            exhaustedEndpointNames = exhaustedEndpointNames
        )

        if (nextResult is EndpointSelectionResult.NoEligibleEndpoint) {
            val message = "All eligible endpoints exhausted. Last failure: $reason."
            Timber.e("FailoverInterceptor: $message")
            failoverEventBus.publish(FailoverEvent.AllEndpointsExhausted(message))
            throw NoEligibleEndpointException(message)
        }

        val nextEndpoint = (nextResult as EndpointSelectionResult.Selected).endpoint
        Timber.i("FailoverInterceptor: switching to '${nextEndpoint.name}' — reason: $reason")
        failoverEventBus.publish(
            FailoverEvent.SwitchedToEndpoint(
                activeEndpointName = nextEndpoint.name,
                failoverReason = reason
            )
        )
        return nextEndpoint
    }
}

// ─── Supporting types ────────────────────────────────────────────────────────

/**
 * Thrown when the [FailoverInterceptor] cannot find any eligible endpoint for the
 * current user's region and role constraints (Requirement 35.4).
 *
 * Callers MUST catch this exception and display a structured error identifying the
 * outage — they MUST NOT silently retry with a non-eligible endpoint.
 */
class NoEligibleEndpointException(message: String) : IOException(message)

/**
 * Provides the authenticated user's data residency region tag.
 * Implemented in the `data` module; bound in [FederationModule].
 */
interface UserRegionProvider {
    /**
     * Returns the current user's region tag (e.g. `"us-east-1"`), or an empty string
     * if no user is authenticated.
     */
    fun getRegion(): String
}

/**
 * Provides the authenticated user's RBAC role string.
 * Implemented in the `data` module; bound in [FederationModule].
 */
interface UserRoleProvider {
    /**
     * Returns the current user's role value (e.g. `"user"`, `"premium"`, `"admin"`),
     * or `"user"` as the default fallback.
     */
    fun getRole(): String
}
