/**
 * DevOpsChatResult.kt — domain module
 *
 * Domain model for a response from the Phase 13 AI DevOps Assistant.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.model

data class DevOpsChatResult(
    val sessionId: String,
    val question: String,
    val answer: String,
    val citations: List<String> = emptyList(),
    val toolsUsed: List<String> = emptyList(),
    val roundsUsed: Int = 0,
    val llmProvider: String = ""
)
