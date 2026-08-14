/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ComparisonModeUiState.kt
 * Purpose    : UI state for the Comparison Mode screen
 *
 * Architecture Layer : Feature (feature-chat)
 * Pattern Used       : UI State Data Classes
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
 * ComparisonModeUiState.kt
 *
 * Purpose: Data classes representing the complete observable UI state for the
 *          ComparisonMode screen. One [ProviderPanelState] per provider, all
 *          collected in [ComparisonModeUiState].
 * Architecture: feature-chat — MVVM presentation layer.
 * Dependencies: feature-settings (LlmProvider)
 *
 * Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8
 */
package com.aiassistant.feature.chat

import com.aiassistant.core.ai.LlmProvider

/**
 * The lifecycle status of a single provider panel in Comparison Mode.
 *
 * Transitions:
 *   [Loading] → [Streaming] (on first token) → [Complete] (on Done event)
 *   [Loading] → [Error] (on Error event or 30-second timeout)
 *   [Streaming] → [Error] (on stream error mid-flight)
 */
sealed class ProviderPanelStatus {
    /** Waiting for the first token from the provider. */
    data object Loading : ProviderPanelStatus()

    /** First token received; tokens are being appended. */
    data object Streaming : ProviderPanelStatus()

    /** Stream finished; [ProviderPanelState.responseText] is the final response. */
    data object Complete : ProviderPanelStatus()

    /**
     * Provider returned an error or timed out after 30 seconds.
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : ProviderPanelStatus()

    /**
     * Provider timed out (no response within 30 seconds).
     */
    data object Timeout : ProviderPanelStatus()
}

/**
 * State for a single provider panel in the Comparison Mode screen.
 *
 * @param providerId       Canonical provider ID string (matches [LlmProvider.id]).
 * @param providerName     Human-readable provider name (matches [LlmProvider.display]).
 * @param status           Current lifecycle status of this panel.
 * @param responseText     Accumulated response text (grows during [ProviderPanelStatus.Streaming];
 *                         final value when [ProviderPanelStatus.Complete]).
 * @param tokenCount       Total output token count reported by the provider (0 while not complete).
 * @param latencyMs        Milliseconds from dispatch to the first token (-1 until first token
 *                         is received).
 * @param estimatedCostUsd Estimated USD cost calculated from token count and configured pricing
 *                         (0.0 until complete).
 * @param qualityScore     Composite quality score 0–100. Null until the panel reaches
 *                         [ProviderPanelStatus.Complete].
 *                         Components: response length (0–40) + coherence (0–40) + latency (0–20).
 */
data class ProviderPanelState(
    val providerId: String,
    val providerName: String,
    val status: ProviderPanelStatus = ProviderPanelStatus.Loading,
    val responseText: String = "",
    val tokenCount: Int = 0,
    val latencyMs: Long = -1L,
    val estimatedCostUsd: Double = 0.0,
    val qualityScore: Int? = null
)

/**
 * Full UI state for the Comparison Mode screen.
 *
 * @param prompt          The prompt that was dispatched to all providers.
 * @param panels          One [ProviderPanelState] per selected provider (2–4).
 * @param dispatchedAt    Epoch-ms timestamp of when the first dispatch was issued. Used
 *                        to compute latency per panel.
 * @param canonicalPanelId The [ProviderPanelState.providerId] of the panel the user
 *                         selected via "Use This Response" (null until selected).
 * @param isComparisonModeAvailable True when ≥2 providers are configured (Req 30.8).
 * @param unavailableTooltip Message shown when Comparison Mode control is disabled.
 */
data class ComparisonModeUiState(
    val prompt: String = "",
    val panels: List<ProviderPanelState> = emptyList(),
    val dispatchedAt: Long = 0L,
    val canonicalPanelId: String? = null,
    val isComparisonModeAvailable: Boolean = false,
    val unavailableTooltip: String = "At least 2 active providers are required for Comparison Mode."
)
