/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : web/StubWebSearchProvider.kt
 * Purpose    : Safe default WebSearchProvider that returns an
 *              empty result set without making any network calls.
 *              Used until a real search API key is configured.
 *
 * Architecture Layer : Data — agent web sub-package
 * Pattern Used       : Null Object / Stub
 *
 * Key Concepts:
 *   - Zero network calls — safe to use in any environment
 *   - Returns Result.success(emptyList()) so WebAgent can still
 *     construct a valid (but search-less) response
 *   - isConfigured = false signals WebAgent to surface the
 *     "not yet configured" message to the user
 *
 * Dependencies: domain (WebSearchProvider, WebSearchResult)
 * ============================================================
 */

package com.aiassistant.data.agent.web

import com.aiassistant.domain.agent.WebSearchProvider
import com.aiassistant.domain.agent.WebSearchResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safe stub [WebSearchProvider] that never makes outbound calls.
 *
 * Injected by default until a concrete provider (Google, Bing, etc.)
 * is configured.  [isConfigured] returns `false` so callers can
 * detect the unconfigured state and show appropriate UI.
 */
@Singleton
class StubWebSearchProvider @Inject constructor() : WebSearchProvider {

    override val providerName: String = "stub"

    override val isConfigured: Boolean = false

    override suspend fun search(
        query: String,
        maxResults: Int,
    ): Result<List<WebSearchResult>> = Result.success(emptyList())
}
