/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DismissSuggestionUseCase.kt
 * Purpose    : Encapsulates the 'DismissSuggestion' business operation
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
 * DismissSuggestionUseCase.kt
 *
 * Purpose: Records a dismissed suggestion type per screen instance in memory.
 *          Prevents the same suggestion type from being shown again on the same
 *          screen instance for the remainder of the session.
 * Architecture: domain module — pure Kotlin, zero Android or third-party framework dependencies.
 * Dependencies: domain model (SuggestionType)
 *
 * Requirements: 33.5
 *
 * Design decisions:
 * - Entirely in-memory — no persistence or database involvement (session-scoped).
 * - Keyed by screenInstanceId so dismissals on one screen (e.g., Note A) do not
 *   affect a different screen instance (e.g., Note B).
 * - Non-suspend operations: all state reads and writes are in-memory and synchronous.
 * - Thread-safe: all access to the shared map is guarded with synchronized(this).
 * - clearSession() is provided for logout / test teardown.
 */

package com.aiassistant.domain.usecase.suggestions

import com.aiassistant.domain.model.SuggestionType
import javax.inject.Inject

/**
 * Use case for dismissing context-aware suggestion types on a specific screen instance.
 *
 * WHEN the User dismisses a suggestion chip or card, THE AI_Assistant SHALL suppress
 * suggestions of the same type for that screen instance for the remainder of the session
 * (Requirement 33.5).
 *
 * All state is held in memory and is NOT persisted to disk or database. The dismissed
 * set is automatically cleared when [clearSession] is called or when the process is
 * restarted.
 */
class DismissSuggestionUseCase @Inject constructor() {

    /**
     * Session-scoped dismissed suggestions map.
     *
     * Key: screenInstanceId — uniquely identifies a screen instance (e.g., note ID, event ID).
     * Value: set of [SuggestionType] values that have been dismissed on that screen instance.
     *
     * Guarded by `synchronized(this)` on all access.
     */
    private val dismissedByScreen: MutableMap<String, MutableSet<SuggestionType>> = mutableMapOf()

    /**
     * Records that the given [type] has been dismissed on the screen identified by [screenInstanceId].
     *
     * Subsequent calls to [isDismissed] for the same [screenInstanceId] and [type] will
     * return `true` until [clearSession] is called.
     *
     * This is a pure in-memory operation and does NOT suspend.
     *
     * @param screenInstanceId The unique identifier of the screen instance.
     * @param type             The suggestion type that was dismissed.
     */
    fun invoke(screenInstanceId: String, type: SuggestionType) {
        synchronized(this) {
            dismissedByScreen
                .getOrPut(screenInstanceId) { mutableSetOf() }
                .add(type)
        }
    }

    /**
     * Returns `true` if the given [type] has been dismissed on the screen identified
     * by [screenInstanceId] during this session.
     *
     * @param screenInstanceId The unique identifier of the screen instance.
     * @param type             The suggestion type to check.
     * @return `true` if dismissed; `false` otherwise.
     */
    fun isDismissed(screenInstanceId: String, type: SuggestionType): Boolean = synchronized(this) {
        dismissedByScreen[screenInstanceId]?.contains(type) ?: false
    }

    /**
     * Clears all dismissed suggestion state for all screen instances.
     *
     * Call this on logout or during test teardown to reset the session state.
     */
    fun clearSession() {
        synchronized(this) {
            dismissedByScreen.clear()
        }
    }
}
