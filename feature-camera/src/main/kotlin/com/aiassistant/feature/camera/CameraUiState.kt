/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : CameraUiState.kt
 * Purpose    : CameraUiState — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : UI State Data Class
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
 * File       : CameraUiState.kt
 * Purpose    : CameraUiState — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : UI State Data Class
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
 * CameraUiState.kt
 *
 * Purpose: Sealed class representing every observable UI state for the Camera feature,
 *          covering permission flow, image capture, OCR, barcode/QR scanning, and
 *          AI vision analysis.
 * Architecture: feature-camera â€” MVVM presentation layer; consumed by CameraCaptureScreen,
 *               ImageAnalysisScreen, and OcrResultScreen composables.
 * Dependencies: android.net.Uri (Android SDK only)
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
 *
 * Design decisions:
 * - States model the full lifecycle: permission check â†’ capture/gallery â†’ analysis
 *   â†’ OCR/barcode/vision result â†’ back to idle.
 * - Resolution validation happens before ImageSelected so the UI never holds a ref to
 *   an oversized image (Requirement 6.2).
 * - VisionUnsupported is a distinct typed state (not Error) so the UI can render a
 *   structured capability-gap card with provider suggestions (Requirement 6.6).
 * - OcrBoundingBox coordinates are normalised 0..1 relative to the image dimensions so
 *   the overlay scales correctly to any on-screen size (Requirement 6.3).
 */
package com.aiassistant.feature.camera

import android.net.Uri

/**
 * Normalised bounding box for a single OCR text region.
 *
 * All coordinates are in the range [0, 1] relative to the image's width and height.
 * Composables must scale them by the on-screen image dimensions before drawing.
 *
 * @param left   Left edge, normalised (0 = left side of image).
 * @param top    Top edge, normalised (0 = top of image).
 * @param right  Right edge, normalised (1 = right side of image).
 * @param bottom Bottom edge, normalised (1 = bottom of image).
 * @param text   The text content recognised within this bounding box.
 */
data class OcrBoundingBox(val left: Float, val top: Float, val right: Float, val bottom: Float, val text: String)

/**
 * Represents every possible UI state in the Camera feature flow.
 *
 * The [CameraViewModel] exposes a [kotlinx.coroutines.flow.StateFlow] of this sealed
 * class. Each screen observes it and renders the appropriate UI.
 */
sealed class CameraUiState {

    /**
     * Initial state â€” no image selected and no camera active.
     * Shows options to open the camera or pick from gallery.
     */
    data object Idle : CameraUiState()

    /**
     * The app is asking the user to grant [android.Manifest.permission.CAMERA] permission.
     * The permission rationale dialog is shown from this state (Requirement 6.1).
     */
    data object RequestingPermission : CameraUiState()

    /**
     * The user denied (possibly with "don't ask again") the CAMERA permission.
     * The screen shows an error banner and a "Open Settings" deep-link button
     * (Requirement 6.1).
     */
    data object PermissionDenied : CameraUiState()

    /**
     * CameraX preview is active and the user can capture a photo or enable barcode
     * scanning mode (Requirements 6.1, 6.4).
     */
    data object Capturing : CameraUiState()

    /**
     * An image has been selected (from gallery or after capture) and passed resolution
     * validation. Images exceeding 4096Ã—4096 pixels are rejected before this state
     * is reached (Requirement 6.2).
     *
     * @param uri    Content or file URI pointing to the selected image.
     * @param width  Actual image width in pixels (â‰¤ 4096).
     * @param height Actual image height in pixels (â‰¤ 4096).
     */
    data class ImageSelected(val uri: Uri, val width: Int, val height: Int) : CameraUiState()

    /**
     * The image has been submitted for analysis and the request is in progress.
     * A progress indicator is shown (Requirement 6.2).
     *
     * @param imageUri The URI of the image being analysed.
     * @param prompt   The user's prompt/question about the image.
     */
    data class Analyzing(val imageUri: Uri, val prompt: String) : CameraUiState()

    /**
     * ML Kit OCR has completed and extracted text with bounding box regions.
     * The image overlay draws [OcrBoundingBox] outlines (Requirement 6.3).
     *
     * @param imageUri      URI of the original image.
     * @param extractedText Full text extracted by the OCR engine.
     * @param boundingBoxes List of normalised bounding boxes with associated text.
     */
    data class OcrResult(val imageUri: Uri, val extractedText: String, val boundingBoxes: List<OcrBoundingBox>) :
        CameraUiState()

    /**
     * ML Kit barcode/QR scanner has decoded a symbol and returned the payload.
     * The decoded value is posted as a [com.aiassistant.domain.model.Message] in the
     * active Conversation (Requirement 6.4).
     *
     * @param payload The decoded content string (URL, text, contact data, etc.).
     * @param format  BarcodeFormat name â€” e.g. "QR_CODE", "EAN_13", "DATA_MATRIX".
     */
    data class BarcodeResult(val payload: String, val format: String) : CameraUiState()

    /**
     * A vision-capable AI provider processed the image and returned a response.
     * (Requirement 6.5 â€” AI Orchestrator accepts image + prompt).
     *
     * @param imageUri   URI of the submitted image.
     * @param aiResponse The full text response from the AI provider.
     * @param prompt     The original user prompt.
     */
    data class VisionResult(val imageUri: Uri, val aiResponse: String, val prompt: String) : CameraUiState()

    /**
     * The currently active LLM provider does not support vision/image input.
     * The UI renders a structured capability-gap error card with provider suggestions
     * (Requirement 6.6).
     *
     * @param activeProvider     Identifier of the provider that rejected the vision request.
     * @param suggestedProviders List of known vision-capable provider identifiers.
     */
    data class VisionUnsupported(val activeProvider: String, val suggestedProviders: List<String>) : CameraUiState()

    /**
     * A recoverable error occurred (e.g. image read failure, network error).
     * The user can dismiss and retry from Idle.
     *
     * @param message Human-readable error description.
     */
    data class Error(val message: String) : CameraUiState()
}
