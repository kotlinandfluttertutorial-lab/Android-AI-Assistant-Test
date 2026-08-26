/**
 * NetworkObservabilityInterceptor.kt — core-network module
 *
 * Purpose: OkHttp [Interceptor] that captures structured observability data for every
 *          outgoing HTTP request and emits it as an [ObservabilityEvent] via
 *          [ObservabilityEventBus].
 *
 * Architecture: core-network — sits inside the OkHttp application interceptor chain.
 *               Positioned AFTER [AuthInterceptor] and [CertificatePinningInterceptor]
 *               but BEFORE [HttpLoggingInterceptor] so it captures the final request
 *               state (with auth header already attached) and measures the true
 *               round-trip latency including any TLS overhead.
 *
 * What is captured per request:
 *   - HTTP method + path (PII-filtered)
 *   - Request ID (UUID, attached as X-Request-ID header for backend correlation)
 *   - Trace ID (from [SessionManager.currentTraceId], if a trace is active)
 *   - Session ID (from [SessionManager.sessionId])
 *   - HTTP response code
 *   - Round-trip latency in milliseconds
 *   - DomainError subtype name on failure
 *
 * What is NOT captured:
 *   - Request or response bodies (binary safety; PII risk)
 *   - Full URL with query parameters (may contain tokens or IDs)
 *   - Authorization header value (always redacted)
 *
 * Design decisions:
 * - The interceptor is an OkHttp [Interceptor] (not [okhttp3.Authenticator]) so it
 *   runs for every request, not just 401 responses.
 * - [ObservabilityEventBus.emit] uses [tryEmit] internally — the interceptor thread
 *   never suspends, so [chain.proceed] timing is unaffected.
 * - [X-Request-ID] is added to the outgoing request so the backend can log the same
 *   ID and the two log streams can be joined in the AI analysis pipeline (Phase 10).
 * - Paths are stripped of UUID-like segments in query strings but kept in the path
 *   itself — path segments uniquely identify endpoints, not users.
 * - All string values passed to metadata are run through [PiiFilter] as a
 *   defence-in-depth measure even though paths and error messages should not contain
 *   PII by design.
 *
 * Phase 2 — Android Observability
 */

package com.aiassistant.core.network.observability

import com.aiassistant.core.common.observability.EventLevel
import com.aiassistant.core.common.observability.EventType
import com.aiassistant.core.common.observability.ObservabilityEvent
import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.PiiFilter
import com.aiassistant.core.common.observability.SessionManager
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.Interceptor
import okhttp3.Response
import timber.log.Timber

/** Header name used to correlate Android log entries with backend log entries. */
private const val HEADER_REQUEST_ID = "X-Request-ID"

/** Header name for trace propagation. Follows the B3 single-header format name. */
private const val HEADER_TRACE_ID = "X-Trace-ID"

/** HTTP status threshold above which a response is treated as a server error. */
private const val SERVER_ERROR_THRESHOLD = 500

/** HTTP status range start for client errors. */
private const val CLIENT_ERROR_THRESHOLD = 400

/**
 * OkHttp interceptor that captures structured observability data for every outgoing
 * HTTP request and emits an [ObservabilityEvent] to [ObservabilityEventBus].
 *
 * Installed in both the plain [OkHttpClient] (via [NetworkModule]) and the
 * federation-aware [OkHttpClient] (via [FederationModule]) so all traffic is covered.
 *
 * @param bus            Target bus; events are emitted via [ObservabilityEventBus.emit].
 * @param sessionManager Source of sessionId, traceId, and new requestId values.
 */
@Singleton
class NetworkObservabilityInterceptor @Inject constructor(
    private val bus: ObservabilityEventBus,
    private val sessionManager: SessionManager,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // ── Generate correlation identifiers ──────────────────────────────────
        val requestId = sessionManager.newRequestId()
        val traceId = sessionManager.currentTraceId   // null if no trace is active
        val sessionId = sessionManager.sessionId

        // ── Attach correlation headers to the outgoing request ────────────────
        val requestWithHeaders = originalRequest.newBuilder()
            .header(HEADER_REQUEST_ID, requestId)
            .apply { if (traceId != null) header(HEADER_TRACE_ID, traceId) }
            .build()

        // ── Sanitize the path for use in event metadata ───────────────────────
        // Use only the path segment (no host, no query string) to avoid leaking
        // IDs or tokens that may appear in query parameters.
        val endpoint = PiiFilter.filter(originalRequest.url.encodedPath)
        val method = originalRequest.method

        val startMs = System.currentTimeMillis()

        return try {
            val response = chain.proceed(requestWithHeaders)
            val latencyMs = System.currentTimeMillis() - startMs
            val statusCode = response.code

            val (eventType, level) = when {
                statusCode >= SERVER_ERROR_THRESHOLD -> EventType.HTTP_ERROR to EventLevel.ERROR
                statusCode >= CLIENT_ERROR_THRESHOLD -> EventType.HTTP_ERROR to EventLevel.WARN
                else -> EventType.HTTP_SUCCESS to EventLevel.INFO
            }

            val message = PiiFilter.filter(
                "$method $endpoint → HTTP $statusCode (${latencyMs}ms)",
            )

            val metadata = PiiFilter.filterMap(
                buildMap {
                    put("http_status", statusCode.toString())
                    put("endpoint", endpoint)
                    put("method", method)
                    put("latency_ms", latencyMs.toString())
                    put("request_id", requestId)
                    if (traceId != null) put("trace_id", traceId)
                },
            )

            bus.emit(
                ObservabilityEvent(
                    timestamp = startMs,
                    level = level,
                    eventType = eventType,
                    message = message,
                    requestId = requestId,
                    traceId = traceId,
                    sessionId = sessionId,
                    metadata = metadata,
                ),
            )

            // Separately emit an API_LATENCY event for slow calls (>1 s) so the
            // AI analysis pipeline can detect latency anomalies independently of
            // error events.
            if (latencyMs > SLOW_CALL_THRESHOLD_MS) {
                Timber.w("NetworkObservabilityInterceptor: slow call %s %s %dms", method, endpoint, latencyMs)
                bus.emit(
                    ObservabilityEvent(
                        timestamp = startMs,
                        level = EventLevel.WARN,
                        eventType = EventType.API_LATENCY,
                        message = PiiFilter.filter("Slow call: $method $endpoint took ${latencyMs}ms"),
                        requestId = requestId,
                        traceId = traceId,
                        sessionId = sessionId,
                        metadata = PiiFilter.filterMap(
                            mapOf(
                                "endpoint" to endpoint,
                                "method" to method,
                                "latency_ms" to latencyMs.toString(),
                                "request_id" to requestId,
                            ),
                        ),
                    ),
                )
            }

            response
        } catch (e: SocketTimeoutException) {
            val latencyMs = System.currentTimeMillis() - startMs
            val message = PiiFilter.filter("Timeout: $method $endpoint after ${latencyMs}ms")

            bus.emit(
                ObservabilityEvent(
                    timestamp = startMs,
                    level = EventLevel.ERROR,
                    eventType = EventType.NETWORK_TIMEOUT,
                    message = message,
                    requestId = requestId,
                    traceId = traceId,
                    sessionId = sessionId,
                    metadata = PiiFilter.filterMap(
                        buildMap {
                            put("endpoint", endpoint)
                            put("method", method)
                            put("latency_ms", latencyMs.toString())
                            put("request_id", requestId)
                            put("error_type", "SocketTimeoutException")
                            if (traceId != null) put("trace_id", traceId)
                        },
                    ),
                ),
            )
            throw e
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            val message = PiiFilter.filter(
                "Network error: $method $endpoint — ${e.javaClass.simpleName}",
            )

            bus.emit(
                ObservabilityEvent(
                    timestamp = startMs,
                    level = EventLevel.ERROR,
                    eventType = EventType.NETWORK_ERROR,
                    message = message,
                    requestId = requestId,
                    traceId = traceId,
                    sessionId = sessionId,
                    metadata = PiiFilter.filterMap(
                        buildMap {
                            put("endpoint", endpoint)
                            put("method", method)
                            put("latency_ms", latencyMs.toString())
                            put("request_id", requestId)
                            put("error_type", e.javaClass.simpleName)
                            if (traceId != null) put("trace_id", traceId)
                        },
                    ),
                ),
            )
            throw e
        }
    }

    private companion object {
        /** Requests taking longer than this are flagged with an [EventType.API_LATENCY] event. */
        const val SLOW_CALL_THRESHOLD_MS = 1_000L
    }
}
