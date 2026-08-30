/**
 * RemediationApiService.kt — data module
 *
 * Retrofit service for Phase 15 remediation endpoints.
 *
 * Phase 15 — AIOps
 */
package com.aiassistant.data.remote.devops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@Serializable
data class RemediationActionDto(
    @SerialName("id") val id: String,
    @SerialName("incident_id") val incidentId: String,
    @SerialName("title") val title: String,
    @SerialName("action_type") val actionType: String,
    @SerialName("risk_tier") val riskTier: String,
    @SerialName("reasoning") val reasoning: String,
    @SerialName("confidence") val confidence: Double? = null,
    @SerialName("rank") val rank: Int = 1,
    @SerialName("status") val status: String,
    @SerialName("reviewed_by") val reviewedBy: String? = null,
    @SerialName("rejection_reason") val rejectionReason: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("reviewed_at") val reviewedAt: String? = null
)

@Serializable
data class RemediationPlanDto(
    @SerialName("incident_id") val incidentId: String,
    @SerialName("incident_title") val incidentTitle: String,
    @SerialName("ai_summary") val aiSummary: String,
    @SerialName("actions") val actions: List<RemediationActionDto>,
    @SerialName("low_confidence_warning") val lowConfidenceWarning: String? = null
)

@Serializable
data class RejectRequest(@SerialName("reason") val reason: String = "")

interface RemediationApiService {

    @POST("incidents/{incidentId}/remediation/recommend")
    suspend fun recommend(@Path("incidentId") incidentId: String): RemediationPlanDto

    @GET("incidents/{incidentId}/remediation")
    suspend fun listActions(@Path("incidentId") incidentId: String): List<RemediationActionDto>

    @POST("incidents/{incidentId}/remediation/{actionId}/approve")
    suspend fun approve(
        @Path("incidentId") incidentId: String,
        @Path("actionId") actionId: String
    ): RemediationActionDto

    @POST("incidents/{incidentId}/remediation/{actionId}/reject")
    suspend fun reject(
        @Path("incidentId") incidentId: String,
        @Path("actionId") actionId: String,
        @Body body: RejectRequest
    ): RemediationActionDto
}
