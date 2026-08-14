/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : GetContextSuggestionsUseCase.kt
 * Purpose    : Encapsulates the 'GetContextSuggestions' business operation
 *
 * Architecture Layer : Domain
 * Pattern Used       : Clean Architecture Use Case
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
 * GetContextSuggestionsUseCase.kt
 *
 * Purpose: Generates a set of 1–3 context-aware AI suggestions for the currently active screen.
 *          Enforces privacy-mode gating, global suggestions toggle, and rate-gating to at most
 *          one generation request per screen per 5-second idle window.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: core-common (ApiResult, DispatcherProvider), domain repository
 *               (ContextSuggestionRepository), domain model (ContextSuggestion, ScreenContext)
 *
 * Requirements: 33.1, 33.4, 33.7, 33.8
 *
 * Design decisions:
 * - Privacy mode and global toggle checks are evaluated first with no repository call.
 * - Rate-gate is keyed by screenInstanceId using a ConcurrentHashMap so concurrent
 *   coroutines on different screen instances are isolated without contention.
 * - Access to the rate-gate map for a single screenInstanceId is protected via
 *   synchronized(this) to provide a consistent read-modify-write under concurrency.
 * - The result list is clamped to at most MAX_SUGGESTIONS items regardless of
 *   what the repository returns.
 * - A Success with 0 items is returned as-is (not as an error) — the UI hides the
 *   suggestion area when the list is empty.
 */

package com.aiassistant.domain.usecase.suggestions

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.ScreenContext
import com.aiassistant.domain.repository.ContextSuggestionRepository
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Use case for fetching context-aware AI suggestions for the active screen.
 *
 * THE AI_Assistant SHALL limit context-aware suggestion generation to at most one generation
 * request per screen per 5-second idle window (Requirement 33.4).
 *
 * THE AI_Orchestrator SHALL NOT generate context-aware suggestions when Privacy Mode is
 * enabled (Requirement 33.7).
 *
 * THE AI_Assistant SHALL allow the User to disable context-aware suggestions globally;
 * when disabled, the AI_Orchestrator SHALL not be invoked (Requirement 33.8).
 *
 * @param repository         Repository that calls the AI orchestrator for suggestions.
 * @param dispatcherProvider Coroutine dispatcher provider for testability.
 */
class GetContextSuggestionsUseCase @Inject constructor(
    private val repository: ContextSuggestionRepository,
    private val dispatcherProvider: DispatcherProvider
) {

    /**
     * Thread-safe map of screen instance ID → timestamp (millis) of the last generation request.
     *
     * Uses [ConcurrentHashMap] for non-blocking reads across different screen instances.
     * Individual key access is further guarded with [synchronized] to ensure the
     * read-check-write sequence is atomic per screen instance.
     */
    private val lastRequestTimestamps: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    /**
     * Generates context-aware suggestions for the given [context].
     *
     * Returns [ApiResult.Success] with an empty list (without calling the repository) when:
     * - [isPrivacyModeEnabled] is `true` (Requirement 33.7)
     * - [isSuggestionsEnabled] is `false` (Requirement 33.8)
     * - The rate-gate window has not yet elapsed for this screen instance (Requirement 33.4)
     *
     * Returns [ApiResult.Success] with a list of 1–[MAX_SUGGESTIONS] items when the
     * repository returns results. Clamps the list silently if the repository returns more
     * than [MAX_SUGGESTIONS] items.
     *
     * Returns [ApiResult.Success] with an empty list when the repository returns zero items.
     *
     * Propagates [ApiResult.NetworkUnavailable] and [ApiResult.Error] from the repository.
     *
     * @param context               Screen-specific context data (note content / event / conversation).
     * @param isPrivacyModeEnabled  `true` if the user has Privacy Mode active.
     * @param isSuggestionsEnabled  `false` if the user has globally disabled context suggestions.
     * @return [ApiResult] containing the suggestion list or an error.
     */
    suspend operator fun invoke(
        context: ScreenContext,
        isPrivacyModeEnabled: Boolean,
        isSuggestionsEnabled: Boolean
    ): ApiResult<List<ContextSuggestion>> {
        // ── 1. Privacy Mode guard (Requirement 33.7) ──────────────────────────
        if (isPrivacyModeEnabled) {
            return ApiResult.Success(emptyList())
        }

        // ── 2. Global suggestions toggle guard (Requirement 33.8) ─────────────
        if (!isSuggestionsEnabled) {
            return ApiResult.Success(emptyList())
        }

        // ── 3. Rate-gate per screen instance (Requirement 33.4) ───────────────
        val screenId = context.screenInstanceId
        val nowMillis = System.currentTimeMillis()

        val allowRequest = synchronized(this) {
            val lastTimestamp = lastRequestTimestamps[screenId]
            if (lastTimestamp != null && (nowMillis - lastTimestamp) < RATE_GATE_WINDOW_MS) {
                false
            } else {
                // Update the timestamp before calling the repository so that a concurrent
                // invocation on the same screen instance is immediately gated.
                lastRequestTimestamps[screenId] = nowMillis
                true
            }
        }

        if (!allowRequest) {
            return ApiResult.Success(emptyList())
        }

        // ── 4. Delegate to repository ──────────────────────────────────────────
        val result = repository.getSuggestions(context)

        // ── 5. Validate and clamp the result ──────────────────────────────────
        return when (result) {
            is ApiResult.Success -> ApiResult.Success(result.data.take(MAX_SUGGESTIONS))
            else -> result
        }
    }

    /**
     * Resets the rate-gate map.
     *
     * Intended for use in tests and on logout to clear all in-memory state.
     */
    fun resetRateGate() {
        synchronized(this) {
            lastRequestTimestamps.clear()
        }
    }

    internal companion object {
        /** Rate-gate window in milliseconds: at most one request per screen per 5 seconds (Requirement 33.4). */
        const val RATE_GATE_WINDOW_MS = 5_000L

        /** Maximum number of suggestions returned per invocation (Requirements 33.1, 33.2). */
        const val MAX_SUGGESTIONS = 3
    }
}
