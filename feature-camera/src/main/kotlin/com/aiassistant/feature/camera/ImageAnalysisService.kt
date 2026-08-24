/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : ImageAnalysisService.kt
 * Purpose    : ImageAnalysisService — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : ImageAnalysisService.kt
 * Purpose    : ImageAnalysisService — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Kotlin Class
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
 * ImageAnalysisService.kt
 *
 * Purpose: Service interface and sealed result types for OCR and vision AI analysis.
 *          The concrete implementation lives in the data layer (injected via Hilt);
 *          this interface keeps the ViewModel testable without Android framework deps.
 * Architecture: feature-camera â€” domain-service abstraction.
 * Dependencies: android.graphics.Bitmap (Android SDK, unavoidable for camera feature)
 *
 * Requirements: 6.2, 6.3, 6.4
 */
package com.aiassistant.feature.camera

import android.graphics.Bitmap

// â”€â”€â”€ OCR result types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Result of an OCR processing operation (Requirement 6.3). */
sealed class OcrResult {
    /**
     * OCR completed successfully.
     *
     * @param text         Full extracted text string.
     * @param boundingBoxes Per-block bounding boxes for overlay rendering.
     */
    data class Success(val text: String, val boundingBoxes: List<OcrBoundingBox>) : OcrResult()

    /**
     * OCR processing failed.
     *
     * @param message Human-readable error description.
     */
    data class Failure(val message: String) : OcrResult()
}

// â”€â”€â”€ Vision analysis result types â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Result of a vision LLM analysis operation (Requirement 6.4). */
sealed class VisionAnalysisResult {
    /**
     * Analysis completed successfully.
     *
     * @param response Structured analysis response from the LLM.
     */
    data class Success(val response: String) : VisionAnalysisResult()

    /**
     * Analysis failed.
     *
     * @param message Human-readable error description.
     */
    data class Failure(val message: String) : VisionAnalysisResult()
}

// â”€â”€â”€ Service interface â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Abstraction over image analysis operations used by [CameraViewModel].
 *
 * The concrete implementation handles:
 * - ML Kit on-device OCR for text extraction + bounding boxes (Requirement 6.3)
 * - Multipart image upload to the AI Orchestrator for vision LLM analysis (Requirement 6.4)
 *
 * All functions are suspending â€” call them from a coroutine dispatched on IO.
 */
interface ImageAnalysisService {

    /**
     * Performs OCR on [bitmap] using ML Kit on-device text recognition.
     *
     * Returns extracted text along with per-block bounding box coordinates
     * (Requirement 6.3). This is an on-device operation and does not require network
     * connectivity.
     *
     * @param bitmap The image to process (must already be resolution-enforced).
     * @return [OcrResult.Success] with text and bounding boxes, or [OcrResult.Failure].
     */
    suspend fun performOcr(bitmap: Bitmap): OcrResult

    /**
     * Sends [bitmap] and [userPrompt] to the AI Orchestrator for vision LLM analysis
     * (Requirement 6.4).
     *
     * The image is base64-encoded and included in the multipart request to the backend.
     * The backend routes the request to the active vision-capable [provider].
     *
     * @param bitmap         The image to analyse.
     * @param userPrompt     The user-supplied text prompt.
     * @param provider       The active LLM provider identifier.
     * @return [VisionAnalysisResult.Success] with the structured response, or [VisionAnalysisResult.Failure].
     */
    suspend fun analyzeWithVisionLLM(bitmap: Bitmap, userPrompt: String, provider: String): VisionAnalysisResult
}
