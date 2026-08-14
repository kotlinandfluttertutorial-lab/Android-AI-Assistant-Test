/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : CostDashboard.kt
 * Purpose    : Domain models for the AI Cost Dashboard feature
 *
 * Architecture Layer : Domain
 * Pattern Used       : Immutable data classes (no Android/framework deps)
 *
 * Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6
 * ============================================================
 */

package com.aiassistant.domain.model

/**
 * Aggregated cost for one (feature, provider, calendar-day) combination.
 *
 * @param feature       AI feature name (chat, rag, code, voice, comparison, suggestions).
 * @param provider      LLM provider identifier (openai, anthropic, gemini, etc.).
 * @param day           ISO-8601 calendar date string (UTC), e.g. "2025-01-15".
 * @param inputTokens   Total input tokens for this feature/provider/day combination.
 * @param outputTokens  Total output tokens for this feature/provider/day combination.
 * @param costUsd       Estimated cost in USD.
 */
data class DailyCostRow(
    val feature: String,
    val provider: String,
    val day: String,
    val inputTokens: Int,
    val outputTokens: Int,
    val costUsd: Double
)

/**
 * Top-level cost summary for the Cost Dashboard screen.
 *
 * Contains 90-day totals and per-(feature, provider, day) breakdown rows.
 *
 * Requirements: 34.1, 34.2
 */
data class CostSummary(
    val totalInputTokens: Int,
    val totalOutputTokens: Int,
    val totalCostUsd: Double,
    val rows: List<DailyCostRow>,
    val windowDays: Int = 90
)

/**
 * A single user-defined spending alert threshold.
 *
 * @param id            Unique identifier (UUID string).
 * @param userId        UUID string of the owning user.
 * @param thresholdUsd  Alert fires when accumulated daily cost reaches this amount.
 * @param isTriggered   True when the alert monitor has fired a notification.
 * @param triggeredAt   ISO-8601 UTC timestamp when the threshold was first crossed, or null.
 * @param dismissedAt   ISO-8601 UTC timestamp when the user dismissed the banner, or null.
 * @param createdAt     ISO-8601 UTC timestamp when this alert was created.
 *
 * Requirements: 34.4, 34.5, 34.6
 */
data class SpendingAlert(
    val id: String,
    val userId: String,
    val thresholdUsd: Double,
    val isTriggered: Boolean,
    val triggeredAt: String? = null,
    val dismissedAt: String? = null,
    val createdAt: String
)
