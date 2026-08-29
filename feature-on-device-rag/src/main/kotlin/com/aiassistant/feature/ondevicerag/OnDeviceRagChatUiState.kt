/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceRagChatUiState.kt
 * Purpose    : Sealed class representing every observable UI state for
 *              OnDeviceRagChatScreen — routing decision display, streaming
 *              tokens, citations, fallback notification, and error/retry.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — presentation layer.
 *
 * Requirements: 35.1, 35.4, 35.5, 35.8, 35.9, 36.5, 36.6, 36.7, 36.8
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import com.aiassistant.domain.model.ChunkCitation
import com.aiassistant.domain.model.OnDeviceInferencePath

/**
 * All possible states of the OnDeviceRagChatScreen.
 *
 * [OnDeviceRagViewModel] exposes a `StateFlow<OnDeviceRagChatUiState>`.
 */
sealed class OnDeviceRagChatUiState {

    /** Initial idle state — no query submitted yet. */
    data object Idle : OnDeviceRagChatUiState()

    /**
     * Router is evaluating capability signals.
     * Show a brief "Checking capabilities…" indicator.
     */
    data object Routing : OnDeviceRagChatUiState()

    /**
     * Query is being processed — either searching the local index or
     * waiting for the first token from the cloud.
     *
     * @param activePath       The selected inference path shown in the toolbar badge.
     * @param fallbackBanner   True when a cloud→on-device fallback occurred.
     */
    data class Searching(
        val activePath: OnDeviceInferencePath,
        val fallbackBanner: Boolean = false,
    ) : OnDeviceRagChatUiState()

    /**
     * Tokens streaming in from the on-device or cloud inference engine.
     *
     * @param activePath          Inference path shown in the chat toolbar.
     * @param accumulatedText     All tokens received so far.
     * @param fallbackBanner      True when a fallback occurred during this query.
     */
    data class Streaming(
        val activePath: OnDeviceInferencePath,
        val accumulatedText: String,
        val fallbackBanner: Boolean = false,
    ) : OnDeviceRagChatUiState()

    /**
     * Generation complete — response ready for display with citations.
     *
     * @param activePath      Inference path used.
     * @param responseText    Full generated response.
     * @param citations       Source chunk references for the "Show sources" control.
     * @param fallbackBanner  True when a fallback occurred.
     */
    data class Done(
        val activePath: OnDeviceInferencePath,
        val responseText: String,
        val citations: List<ChunkCitation>,
        val fallbackBanner: Boolean = false,
    ) : OnDeviceRagChatUiState()

    /**
     * No chunks in the local vector index met the 0.40 similarity threshold.
     * Display "No relevant content found in local documents."
     */
    data object NoRelevantContent : OnDeviceRagChatUiState()

    /**
     * Query pipeline failed.
     *
     * @param message   Human-readable description.
     * @param stage     Which stage failed ("embedding"|"search"|"generation"|"router").
     * @param canRetry  True — show "Retry via cloud" action button.
     */
    data class Error(
        val message: String,
        val stage: String,
        val canRetry: Boolean = true,
    ) : OnDeviceRagChatUiState()
}
