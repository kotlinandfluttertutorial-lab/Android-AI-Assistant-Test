/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ImageAnalysisRemoteDataSource.kt
 * Purpose    : Domain contract for the backend image analysis call.
 *              Decouples ImageAgent from HTTP/OkHttp details.
 *
 * Architecture Layer : Domain — network sub-package
 * Pattern Used       : Repository interface (Remote Data Source)
 * ============================================================
 */

package com.aiassistant.domain.network

import com.aiassistant.domain.agent.ImageAnalysisResult

/**
 * Remote data source contract for `POST /images/analyze`.
 *
 * The implementation in :data uses OkHttp multipart to call the backend.
 * [ImageAgent] depends on this interface so it can be tested without HTTP.
 *
 * @throws Exception on any network or parsing failure (caller handles).
 */
interface ImageAnalysisRemoteDataSource {

    /**
     * Analyse an image and return the extracted text plus optional vision response.
     *
     * Exactly one of [imageBase64] or [imageUri] must be non-null.
     * [prompt] is optional and only used for vision Q&A action.
     *
     * @param imageBase64   Base64-encoded JPEG bytes.
     * @param imageUri      Android content:// or file:// URI string.
     * @param prompt        Vision question for the LLM.
     * @param provider      LLM provider (e.g. "gemini"). Null for OCR-only.
     * @param imageWidth    Image width in pixels, used for bounding-box normalisation.
     * @param imageHeight   Image height in pixels, used for bounding-box normalisation.
     * @return              [ImageAnalysisResult] with normalised bounding boxes.
     */
    suspend fun analyzeImage(
        imageBase64: String? = null,
        imageUri: String? = null,
        prompt: String? = null,
        provider: String? = null,
        imageWidth: Int? = null,
        imageHeight: Int? = null,
    ): ImageAnalysisResult
}
