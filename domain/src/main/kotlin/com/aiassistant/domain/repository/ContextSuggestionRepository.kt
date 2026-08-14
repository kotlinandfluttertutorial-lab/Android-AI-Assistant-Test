/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ContextSuggestionRepository.kt
 * Purpose    : Domain contract for context-aware AI suggestion data access
 *
 * Architecture Layer : Domain
 * Pattern Used       : Repository Interface
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
 * ContextSuggestionRepository.kt
 *
 * Purpose: Domain-layer repository interface for fetching context-aware AI suggestions.
 *          Implemented in the data module; injected into use cases at runtime.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult), domain model (ContextSuggestion, ScreenContext)
 *
 * Requirements: 33.1, 33.2, 33.3
 */

package com.aiassistant.domain.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext

/**
 * Contract for fetching context-aware AI suggestions from the AI orchestrator.
 *
 * The data module provides a concrete implementation that calls the backend AI endpoint.
 * All rate-gating and privacy checks are enforced in
 * [com.aiassistant.domain.usecase.suggestions.GetContextSuggestionsUseCase] before
 * this repository is invoked.
 */
interface ContextSuggestionRepository {

    /**
     * Fetches a list of context-aware suggestions for the given screen context.
     *
     * The caller (use case layer) is responsible for ensuring:
     * - Privacy mode is not active.
     * - Suggestions are globally enabled.
     * - The rate-gate window has elapsed.
     *
     * The repository implementation is responsible for:
     * - Calling the AI orchestrator with the relevant context fields.
     * - Returning a list of 1–3 [ContextSuggestion] objects, or an empty list when the
     *   AI returns no applicable suggestions.
     * - Respecting the 3-second timeout defined in Requirement 33.6 at the network layer;
     *   returning [ApiResult.Success] with an empty list on timeout (silent suppression).
     *
     * @param context The screen-specific context carrying the content to analyse.
     * @return [ApiResult.Success] with the list of suggestions (0–3 items) on success,
     *         [ApiResult.NetworkUnavailable] when the device has no connectivity,
     *         [ApiResult.Error] with a [com.aiassistant.core.common.DomainError] subtype
     *         on server or transport failures.
     */
    suspend fun getSuggestions(context: ScreenContext): ApiResult<List<ContextSuggestion>>
}
