/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : WebSearchProvider.kt
 * Purpose    : Provider-independent contract for web search, plus
 *              the WebSearchResult domain value object.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Interface (Strategy / Provider)
 *
 * Key Concepts:
 *   - Pure Kotlin, zero Android/framework dependencies
 *   - WebAgent depends only on this interface — never on a concrete
 *     search API (Google, Bing, DuckDuckGo, etc.)
 *   - Every concrete provider lives in :data and is injected via Hilt
 *   - StubWebSearchProvider is the default safe implementation that
 *     returns an empty result set without making any network calls
 *
 * Design Decision:
 *   The provider returns List<WebSearchResult> rather than a single
 *   String so that citations can be attached to individual results.
 *   WebAgent is responsible for assembling the results into a
 *   final answer and populating AgentResult.citations.
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * A single search result returned by a [WebSearchProvider].
 *
 * @param title     Page title as shown in search engine results.
 * @param url       Canonical URL of the page.
 * @param snippet   Short excerpt from the page content.
 * @param source    Domain name of the source (e.g. "wikipedia.org").
 * @param timestamp ISO-8601 publication date/time when available; null otherwise.
 */
@Serializable
data class WebSearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String = "",
    val timestamp: String? = null,
)

/**
 * Error types surfaced by [WebSearchProvider.search].
 */
sealed class WebSearchError {
    /** Provider is not configured (API key missing). */
    data object NotConfigured : WebSearchError()

    /** Network call failed. */
    data class NetworkError(val message: String) : WebSearchError()

    /** Provider returned a rate-limit or quota error. */
    data class RateLimited(val retryAfterSeconds: Int = 60) : WebSearchError()

    /** Provider returned a non-retryable error. */
    data class ProviderError(val message: String) : WebSearchError()
}

/**
 * Provider-independent contract for performing web searches.
 *
 * ## Implementing a provider
 *
 * ```kotlin
 * @Singleton
 * class GoogleCustomSearchProvider @Inject constructor(
 *     @Named("googleApiKey") private val apiKey: String,
 *     private val httpClient: OkHttpClient,
 * ) : WebSearchProvider {
 *     override val providerName = "google_custom_search"
 *     override val isConfigured get() = apiKey.isNotBlank()
 *
 *     override suspend fun search(query: String, maxResults: Int): Result<List<WebSearchResult>> {
 *         // call Google Custom Search JSON API
 *     }
 * }
 * ```
 *
 * ## Threading
 * `search()` is a suspending function; implementations must not block.
 */
interface WebSearchProvider {

    /**
     * Canonical identifier for this provider (e.g. `"google"`, `"bing"`, `"stub"`).
     */
    val providerName: String

    /**
     * True when the provider has been configured with the required credentials
     * (API keys, CX IDs, etc.) and can make live network calls.
     */
    val isConfigured: Boolean

    /**
     * Execute a web search for [query] and return up to [maxResults] results.
     *
     * Returns `Result.success(emptyList())` when no results are found.
     * Returns `Result.failure(WebSearchException)` on error — never throws.
     *
     * @param query      The search query string. Must not be blank.
     * @param maxResults Maximum number of results to return (1–20).
     */
    suspend fun search(
        query: String,
        maxResults: Int = DEFAULT_MAX_RESULTS,
    ): Result<List<WebSearchResult>>

    companion object {
        const val DEFAULT_MAX_RESULTS: Int = 5
    }
}

/**
 * Typed exception wrapper for [WebSearchProvider.search] failures.
 */
class WebSearchException(
    val error: WebSearchError,
    cause: Throwable? = null,
) : Exception(
    when (error) {
        is WebSearchError.NotConfigured -> "Web search provider is not configured."
        is WebSearchError.NetworkError -> "Web search network error: ${error.message}"
        is WebSearchError.RateLimited -> "Web search rate limited. Retry after ${error.retryAfterSeconds}s."
        is WebSearchError.ProviderError -> "Web search provider error: ${error.message}"
    },
    cause,
)
