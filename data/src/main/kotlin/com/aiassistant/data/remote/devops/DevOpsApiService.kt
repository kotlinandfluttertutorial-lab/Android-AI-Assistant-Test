/**
 * DevOpsApiService.kt — data module
 *
 * Retrofit service for `/devops/chat` and `/analysis/errors` endpoints (Phase 13).
 * Consumed by [DevOpsRemoteDataSource].
 *
 * Phase 14 — Android AI DevOps Dashboard
 */
package com.aiassistant.data.remote.devops

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.POST

// ─── DTOs ─────────────────────────────────────────────────────────────────────

@Serializable
data class DevOpsChatRequest(
    @SerialName("question") val question: String,
    @SerialName("provider") val provider: String? = null
)

@Serializable
data class ToolCallDto(
    @SerialName("tool_name") val toolName: String,
    @SerialName("params") val params: Map<String, String> = emptyMap(),
    @SerialName("result") val result: Map<String, String> = emptyMap()
)

@Serializable
data class DevOpsChatResponse(
    @SerialName("session_id") val sessionId: String,
    @SerialName("question") val question: String,
    @SerialName("answer") val answer: String,
    @SerialName("citations") val citations: List<String> = emptyList(),
    @SerialName("tool_calls") val toolCalls: List<ToolCallDto> = emptyList(),
    @SerialName("rounds_used") val roundsUsed: Int = 0,
    @SerialName("llm_provider") val llmProvider: String = ""
)

@Serializable
data class ErrorAnalysisRequest(
    @SerialName("lookback_minutes") val lookbackMinutes: Int = 30,
    @SerialName("session_id") val sessionId: String? = null
)

@Serializable
data class FactsVsInferenceDto(
    @SerialName("facts") val facts: List<String> = emptyList(),
    @SerialName("inferences") val inferences: List<String> = emptyList()
)

@Serializable
data class ErrorAnalysisResponse(
    @SerialName("analysis_id") val analysisId: String,
    @SerialName("severity") val severity: String,
    @SerialName("summary") val summary: String,
    @SerialName("evidence") val evidence: List<String> = emptyList(),
    @SerialName("possible_causes") val possibleCauses: List<String> = emptyList(),
    @SerialName("likely_root_cause") val likelyRootCause: String,
    @SerialName("confidence") val confidence: Double,
    @SerialName("recommended_fix") val recommendedFix: String,
    @SerialName("related_documentation") val relatedDocumentation: List<String> = emptyList(),
    @SerialName("facts_vs_inference") val factsVsInference: FactsVsInferenceDto = FactsVsInferenceDto(),
    @SerialName("low_confidence_warning")val lowConfidenceWarning: String? = null,
    @SerialName("events_analysed") val eventsAnalysed: Int = 0,
    @SerialName("llm_provider") val llmProvider: String = ""
)

// ─── Service interface ────────────────────────────────────────────────────────

interface DevOpsApiService {

    @POST("devops/chat")
    suspend fun chat(@Body request: DevOpsChatRequest): DevOpsChatResponse

    @POST("analysis/errors")
    suspend fun analyseErrors(@Body request: ErrorAnalysisRequest): ErrorAnalysisResponse
}
