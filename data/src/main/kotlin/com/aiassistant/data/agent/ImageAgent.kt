/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : ImageAgent.kt
 * Purpose    : Agent that performs image analysis (OCR, barcode,
 *              vision Q&A) by wrapping the existing ImageAnalysisService
 *              and the backend /images/analyze multipart endpoint.
 *
 * Architecture Layer : Data — agent sub-package
 * Pattern Used       : Adapter (implements Agent from domain)
 *
 * Key Concepts:
 *   - Provider-independent: concrete image provider injected via Hilt
 *   - Does NOT call /vision/analyze (nonexistent) — uses the real
 *     backend /images/analyze (multipart) via OkHttp when possible,
 *     or delegates OCR to ML Kit on-device via ImageAnalysisService
 *   - Bounding-box normalisation: backend returns absolute pixel coords;
 *     we normalise to 0..1 using reported image dimensions
 *   - Feature modules (feature-camera) continue to work unchanged —
 *     ImageAgent is an additional path, not a replacement
 *
 * Request metadata keys:
 *   "image_action"  — "ocr" | "barcode" | "vision" (default "ocr")
 *   "image_uri"     — Android content:// or file:// URI string
 *   "image_base64"  — Base64-encoded JPEG bytes (alternative to URI)
 *   "prompt"        — User's vision question (for "vision" action)
 *   "provider"      — LLM provider for vision analysis
 *   "image_width"   — Image width in pixels (for normalisation)
 *   "image_height"  — Image height in pixels (for normalisation)
 *
 * Streaming protocol:
 *   Started → StatusChanged(RUNNING) → Thinking(0, ...) → Token → Completed
 * ============================================================
 */

package com.aiassistant.data.agent

import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.agent.Agent
import com.aiassistant.domain.agent.AgentCapability
import com.aiassistant.domain.agent.AgentError
import com.aiassistant.domain.agent.AgentEvent
import com.aiassistant.domain.agent.AgentExecution
import com.aiassistant.domain.agent.AgentRequest
import com.aiassistant.domain.agent.AgentResult
import com.aiassistant.domain.agent.AgentStatus
import com.aiassistant.domain.agent.ImageAnalysisResult
import com.aiassistant.domain.agent.NormalisedBoundingBox
import com.aiassistant.domain.network.ImageAnalysisRemoteDataSource
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * [Agent] that analyses images — OCR, barcode detection, vision Q&A.
 *
 * The agent is intentionally thin: it delegates to the injected
 * [ImageAnalysisRemoteDataSource] for backend calls, keeping provider
 * concerns out of this class.
 */
@Singleton
class ImageAgent @Inject constructor(
    private val remoteDataSource: ImageAnalysisRemoteDataSource,
    private val dispatchers: DispatcherProvider,
) : Agent {

    override val name: String = NAME

    override val description: String =
        "Image analysis agent: OCR text extraction, barcode scanning, " +
            "and vision LLM question answering."

    override val capabilities: Set<AgentCapability> = setOf(
        AgentCapability.IMAGE_UNDERSTANDING,
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
    )

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return request.capabilities.isEmpty() ||
            AgentCapability.IMAGE_UNDERSTANDING in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {

        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        val action = request.metadata[METADATA_IMAGE_ACTION]?.trim()?.lowercase() ?: "ocr"
        val prompt = request.metadata[METADATA_PROMPT]?.trim()
            ?: request.input.trim().takeIf { it.isNotBlank() }
        val provider = request.metadata[METADATA_PROVIDER]?.trim() ?: "gemini"
        val imageBase64 = request.metadata[METADATA_IMAGE_BASE64]?.trim()
        val imageUri = request.metadata[METADATA_IMAGE_URI]?.trim()
        val imageWidth = request.metadata[METADATA_IMAGE_WIDTH]?.toIntOrNull()
        val imageHeight = request.metadata[METADATA_IMAGE_HEIGHT]?.toIntOrNull()

        // Need at least one image source
        if (imageBase64.isNullOrBlank() && imageUri.isNullOrBlank()) {
            emit(failed(execution, request, "MISSING_IMAGE",
                "Provide 'image_base64' or 'image_uri' in request metadata."))
            return@flow
        }

        emit(AgentEvent.Thinking(0, "Processing image ($action)…"))

        val analysisResult = withContext(dispatchers.io) {
            runCatching {
                remoteDataSource.analyzeImage(
                    imageBase64 = imageBase64,
                    imageUri = imageUri,
                    prompt = if (action == "vision") prompt else null,
                    provider = if (action == "vision") provider else null,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                )
            }
        }

        if (analysisResult.isFailure) {
            val ex = analysisResult.exceptionOrNull()
            Timber.w(ex, "ImageAgent: analysis failed")
            emit(failed(execution, request, "ANALYSIS_ERROR",
                ex?.message ?: "Image analysis failed. Please try again."))
            return@flow
        }

        val result = analysisResult.getOrThrow()

        // Build response text based on action
        val content = when (action) {
            "vision" -> buildVisionResponse(result, prompt)
            "ocr" -> buildOcrResponse(result)
            else -> buildOcrResponse(result)
        }

        emit(AgentEvent.Token(content))

        emit(
            AgentEvent.Completed(
                AgentResult(
                    executionId = execution.executionId,
                    requestId = request.requestId,
                    agentName = name,
                    status = AgentStatus.COMPLETED,
                    content = content,
                    metadata = buildMap {
                        put("action", action)
                        put("no_text_found", result.noTextFound.toString())
                        put("bounding_box_count", result.boundingBoxes.size.toString())
                        if (result.imageWidth != null) put("image_width", result.imageWidth.toString())
                        if (result.imageHeight != null) put("image_height", result.imageHeight.toString())
                    },
                )
            )
        )
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildOcrResponse(result: ImageAnalysisResult): String =
        when {
            result.noTextFound || result.extractedText.isBlank() ->
                "No text was detected in the image."
            else -> buildString {
                appendLine("**Extracted text:**")
                appendLine(result.extractedText)
                if (result.boundingBoxes.isNotEmpty()) {
                    appendLine()
                    appendLine("_(${result.boundingBoxes.size} text region(s) detected)_")
                }
            }.trimEnd()
        }

    private fun buildVisionResponse(result: ImageAnalysisResult, prompt: String?): String =
        buildString {
            if (!result.visionAnalysis.isNullOrBlank()) {
                appendLine(result.visionAnalysis)
            }
            if (result.extractedText.isNotBlank() && result.visionAnalysis.isNullOrBlank()) {
                appendLine("**Extracted text:**")
                appendLine(result.extractedText)
            }
            if (result.noTextFound && result.visionAnalysis.isNullOrBlank()) {
                appendLine("No text was detected in the image.")
            }
        }.trimEnd().ifBlank { "Image analysis complete. No content could be extracted." }

    private fun failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: String,
        message: String,
    ) = AgentEvent.Failed(
        AgentResult(
            executionId = execution.executionId,
            requestId = request.requestId,
            agentName = name,
            status = AgentStatus.FAILED,
            error = AgentError(code = code, message = message),
        )
    )

    companion object {
        const val NAME = "image-analysis"
        const val METADATA_AGENT_NAME = "agent_name"
        const val METADATA_IMAGE_ACTION = "image_action"
        const val METADATA_IMAGE_URI = "image_uri"
        const val METADATA_IMAGE_BASE64 = "image_base64"
        const val METADATA_PROMPT = "prompt"
        const val METADATA_PROVIDER = "provider"
        const val METADATA_IMAGE_WIDTH = "image_width"
        const val METADATA_IMAGE_HEIGHT = "image_height"
    }
}
