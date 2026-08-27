/**
 * IncidentApiService.kt — data module
 *
 * Retrofit service for `/incidents/...` endpoints (Phase 11/12).
 * Consumed by [IncidentRemoteDataSource].
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.remote.devops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

// ─── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class IncidentDto(
    @SerialName("id")                val id: String,
    @SerialName("title")             val title: String,
    @SerialName("severity")          val severity: String,
    @SerialName("status")            val status: String,
    @SerialName("detection_method")  val detectionMethod: String = "rule_based",
    @SerialName("triggered_by")      val triggeredBy: String = "",
    @SerialName("metric_value")      val metricValue: Double? = null,
    @SerialName("threshold_value")   val thresholdValue: Double? = null,
    @SerialName("analysis_id")       val analysisId: String? = null,
    @SerialName("ai_summary")        val aiSummary: String? = null,
    @SerialName("ai_confidence")     val aiConfidence: Double? = null,
    @SerialName("ai_recommended_fix")val aiRecommendedFix: String? = null,
    @SerialName("rca_summary")       val rcaSummary: String? = null,
    @SerialName("rca_confidence")    val rcaConfidence: Double? = null,
    @SerialName("event_count")       val eventCount: Int = 0,
    @SerialName("window_minutes")    val windowMinutes: Int = 5,
    @SerialName("detected_at")       val detectedAt: String = "",
    @SerialName("resolved_at")       val resolvedAt: String? = null,
)

@Serializable
data class IncidentListResponse(
    @SerialName("incidents")   val incidents: List<IncidentDto>,
    @SerialName("total")       val total: Int,
    @SerialName("open_count")  val openCount: Int,
)

// ─── Service interface ────────────────────────────────────────────────────────

interface IncidentApiService {

    @GET("incidents")
    suspend fun listIncidents(
        @Query("status")   status:   String? = null,
        @Query("severity") severity: String? = null,
        @Query("limit")    limit:    Int     = 20,
    ): IncidentListResponse

    @GET("incidents/{id}")
    suspend fun getIncident(@Path("id") id: String): IncidentDto
}
