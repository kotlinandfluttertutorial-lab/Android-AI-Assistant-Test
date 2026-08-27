/**
 * Incident.kt — domain module
 *
 * Immutable domain model for a production incident detected by the
 * anomaly detection pipeline (Phase 11/12) or created manually.
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.domain.model

data class Incident(
    val id:               String,
    val title:            String,
    val severity:         IncidentSeverity,
    val status:           IncidentStatus,
    val detectionMethod:  String = "rule_based",
    val triggeredBy:      String = "",
    val metricValue:      Double? = null,
    val thresholdValue:   Double? = null,
    // Phase 10 error analysis results
    val aiSummary:        String? = null,
    val aiConfidence:     Double? = null,
    val aiRecommendedFix: String? = null,
    // Phase 12 RCA results
    val rcaSummary:       String? = null,
    val rcaConfidence:    Double? = null,
    val eventCount:       Int = 0,
    val detectedAt:       String = "",
    val resolvedAt:       String? = null,
)

enum class IncidentSeverity {
    CRITICAL, HIGH, MEDIUM, LOW;

    companion object {
        fun fromString(value: String): IncidentSeverity =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
    }
}

enum class IncidentStatus {
    OPEN, INVESTIGATING, RESOLVED, DISMISSED;

    companion object {
        fun fromString(value: String): IncidentStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: OPEN
    }
}
