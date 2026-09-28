/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : ImageAnalysisResult.kt
 * Purpose    : Domain model for image analysis output from ImageAgent.
 *
 * Architecture Layer : Domain — agent sub-package
 * Pattern Used       : Value object
 *
 * Design Decision:
 *   - Bounding boxes stored in NORMALISED coordinates (0.0–1.0
 *     relative to image width/height) so the representation is
 *     screen-resolution-independent.  ImageAgent is responsible
 *     for converting backend absolute-pixel coordinates to
 *     normalised values using the image dimensions.
 *   - This model is separate from CameraUiState.OcrBoundingBox
 *     (which lives in feature-camera and has an Android Uri dep)
 *     to maintain Clean Architecture layer separation.
 *
 * Dependencies: kotlinx.serialization
 * ============================================================
 */

package com.aiassistant.domain.agent

import kotlinx.serialization.Serializable

/**
 * A single OCR text detection bounding box.
 *
 * All coordinates are normalised 0.0–1.0 relative to image width/height.
 *
 * @param text       Text detected in this region.
 * @param left       Left edge (0.0 = left of image, 1.0 = right of image).
 * @param top        Top edge (0.0 = top of image, 1.0 = bottom of image).
 * @param right      Right edge.
 * @param bottom     Bottom edge.
 * @param confidence Detection confidence 0.0–1.0.
 */
@Serializable
data class NormalisedBoundingBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val confidence: Float = 1.0f,
)

/**
 * The complete result of an image analysis request.
 *
 * @param extractedText  All OCR text extracted from the image, concatenated.
 * @param boundingBoxes  Individual text regions with normalised coordinates.
 * @param noTextFound    True when OCR ran successfully but found no text.
 * @param visionAnalysis AI-generated answer from a vision LLM, if requested.
 * @param imageWidth     Original image width in pixels (for denormalising boxes).
 * @param imageHeight    Original image height in pixels.
 */
@Serializable
data class ImageAnalysisResult(
    val extractedText: String,
    val boundingBoxes: List<NormalisedBoundingBox> = emptyList(),
    val noTextFound: Boolean = false,
    val visionAnalysis: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
) {
    /** True when the result carries any useful content. */
    val hasContent: Boolean
        get() = extractedText.isNotBlank() || visionAnalysis != null
}
