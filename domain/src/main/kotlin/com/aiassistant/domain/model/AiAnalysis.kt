/**
 * AiAnalysis.kt — domain module
 *
 * Domain model for the Phase 10 AI error analysis result.
 * Surfaced on the DevOps dashboard as the "AI Analysis" card.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.model

data class AiAnalysis(
    val analysisId:          String,
    val severity:            String,
    val summary:             String,
    val likelyRootCause:     String,
    val confidence:          Double,
    val recommendedFix:      String,
    val evidence:            List<String> = emptyList(),
    val possibleCauses:      List<String> = emptyList(),
    val relatedDocs:         List<String> = emptyList(),
    val lowConfidenceWarning: String? = null,
    val eventsAnalysed:      Int = 0,
)
