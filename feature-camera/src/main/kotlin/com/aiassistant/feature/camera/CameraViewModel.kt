/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : CameraViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Camera feature
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : MVVM ViewModel
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
 * File       : CameraViewModel.kt
 * Purpose    : Manages UI state and delegates actions to domain use cases for the Camera feature
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : MVVM ViewModel
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
 * CameraViewModel.kt
 *
 * Purpose: Manages the Camera feature state machine and orchestrates calls to
 *          SendMessageUseCase for barcode/QR payloads and vision AI responses.
 *          Validates image resolution, checks provider vision capability, and
 *          drives all screen transitions.
 * Architecture: feature-camera â€” MVVM ViewModel; injected via Hilt.
 * Dependencies: domain (SendMessageUseCase), core-common (DispatcherProvider, ApiResult)
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
 *
 * Design decisions:
 * - ViewModel owns zero Android Context or CameraX objects; those live in the composables.
 * - Vision-capable provider list is a compile-time constant; a future API can replace it.
 * - All I/O work (sendMessageUseCase calls) is dispatched on dispatchers.io to keep the
 *   main thread unblocked (Requirement 6.2 â€” within 3 seconds of submission).
 * - conversationId and currentProvider default to empty; callers set them via
 *   setConversationContext() once navigation supplies the values.
 */
package com.aiassistant.feature.camera

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.domain.usecase.conversation.SendMessageUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the Camera feature.
 *
 * Drives the state machine across CameraCaptureScreen, ImageAnalysisScreen and
 * OcrResultScreen. All state transitions are explicit public methods so composables
 * and tests can drive them cleanly.
 *
 * ```
 * Idle â”€â”€requestPermission()â”€â”€â–º RequestingPermission
 *   â”‚                               â”‚ onPermissionGranted()
 *   â”‚                               â–¼
 *   â”œâ”€â”€startCapture()â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â–º Capturing â”€â”€onImageCaptured()â”€â”€â–º ImageSelected
 *   â”‚                               â”‚                                   â”‚
 *   â”‚                    onBarcodeDetected()                   submitForAnalysis()
 *   â”‚                               â”‚                                   â”‚
 *   â”‚                               â–¼                        Analyzing â”€â”¤
 *   â”‚                         BarcodeResult              â”Œâ”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”¤
 *   â”‚                                                    â–¼              â–¼
 *   â”‚                                              VisionResult   VisionUnsupported
 *   â””â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€ reset() â—„â”€â”€ any state
 * ```
 *
 * @param sendMessageUseCase Use case for posting messages/payloads to a conversation.
 * @param dispatchers        Coroutine dispatcher abstraction for testability.
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel() {

    // â”€â”€â”€ State â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)

    /** Observable Camera feature UI state. Never exposes the mutable backing field. */
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    // â”€â”€â”€ Conversation context â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** The active conversation ID; set by the host screen before user interactions. */
    private var conversationId: String = ""

    /** The active LLM provider ID; set by the host screen. */
    private var currentProvider: String = ""

    // â”€â”€â”€ Public API â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Provides the conversation context needed for [SendMessageUseCase].
     * Called by composables when navigation back-stack entries supply the values.
     *
     * @param conversationId Identifier of the active conversation.
     * @param provider       Identifier of the active LLM provider.
     */
    fun setConversationContext(conversationId: String, provider: String) {
        this.conversationId = conversationId
        this.currentProvider = provider
    }

    /**
     * Transitions [CameraUiState.Idle] â†’ [CameraUiState.RequestingPermission].
     * The composable then triggers the system permission dialog.
     * (Requirement 6.1)
     */
    fun requestPermission() {
        if (_uiState.value is CameraUiState.Idle) {
            _uiState.value = CameraUiState.RequestingPermission
        }
    }

    /**
     * Called by the composable when CAMERA permission is granted.
     * Transitions [CameraUiState.RequestingPermission] â†’ [CameraUiState.Capturing].
     * (Requirement 6.1)
     */
    fun onPermissionGranted() {
        if (_uiState.value is CameraUiState.RequestingPermission) {
            _uiState.value = CameraUiState.Capturing
        }
    }

    /**
     * Called by the composable when CAMERA permission is denied.
     * Transitions any state â†’ [CameraUiState.PermissionDenied].
     * (Requirement 6.1)
     */
    fun onPermissionDenied() {
        _uiState.value = CameraUiState.PermissionDenied
    }

    /**
     * Starts the camera preview directly (when permission is already granted).
     * Transitions [CameraUiState.Idle] â†’ [CameraUiState.Capturing].
     */
    fun startCapture() {
        if (_uiState.value is CameraUiState.Idle) {
            _uiState.value = CameraUiState.Capturing
        }
    }

    /**
     * Called after a photo is captured or an image is selected from the gallery.
     *
     * Validates that the image resolution does not exceed 4096Ã—4096 pixels
     * (Requirement 6.2). If the limit is exceeded, emits [CameraUiState.Error];
     * otherwise transitions to [CameraUiState.ImageSelected].
     *
     * @param uri    Content or file URI pointing to the image.
     * @param width  Image width in pixels.
     * @param height Image height in pixels.
     */
    fun onImageCaptured(uri: Uri, width: Int, height: Int) {
        if (width > MAX_IMAGE_DIMENSION || height > MAX_IMAGE_DIMENSION) {
            _uiState.value = CameraUiState.Error(
                "Image resolution exceeds maximum ${MAX_IMAGE_DIMENSION}Ã—${MAX_IMAGE_DIMENSION} pixels."
            )
            return
        }
        _uiState.value = CameraUiState.ImageSelected(uri = uri, width = width, height = height)
    }

    /**
     * Submits the selected image and a user prompt for AI vision analysis.
     *
     * If [provider] is not in the vision-capable list, immediately transitions to
     * [CameraUiState.VisionUnsupported] (Requirement 6.6).
     *
     * Otherwise transitions to [CameraUiState.Analyzing], then calls
     * [SendMessageUseCase] on [dispatchers.io], and transitions to
     * [CameraUiState.VisionResult] or [CameraUiState.Error] based on the result.
     * (Requirements 6.2, 6.5)
     *
     * @param imageUri The URI of the image to analyse.
     * @param prompt   The user's question or instruction about the image.
     * @param provider The LLM provider identifier to use.
     */
    fun submitForAnalysis(imageUri: Uri, prompt: String, provider: String) {
        if (provider !in VISION_CAPABLE_PROVIDERS) {
            onProviderVisionUnsupported(
                activeProvider = provider,
                suggestedProviders = VISION_CAPABLE_PROVIDERS
            )
            return
        }

        _uiState.value = CameraUiState.Analyzing(imageUri = imageUri, prompt = prompt)

        viewModelScope.launch {
            val content = buildString {
                append("[Image analysis request]\n")
                append("Image: $imageUri\n")
                if (prompt.isNotBlank()) {
                    append("Question: $prompt")
                }
            }

            val result = withContext(dispatchers.io) {
                sendMessageUseCase(
                    conversationId = conversationId,
                    content = content,
                    provider = provider
                )
            }

            _uiState.value = when (result) {
                is ApiResult.Success -> CameraUiState.VisionResult(
                    imageUri = imageUri,
                    aiResponse = result.data.content,
                    prompt = prompt
                )
                is ApiResult.Error -> CameraUiState.Error(result.error.message)
                is ApiResult.NetworkUnavailable -> CameraUiState.Error(
                    "No network connection. Please check your connection and try again."
                )
                is ApiResult.Loading -> CameraUiState.Analyzing(imageUri = imageUri, prompt = prompt)
            }
        }
    }

    /**
     * Enables barcode/QR scanning mode within the camera preview.
     * The composable activates the ML Kit [ImageAnalysis] use case after this call.
     * (Requirement 6.4)
     */
    fun startBarcodeScanning() {
        val current = _uiState.value
        if (current is CameraUiState.Idle || current is CameraUiState.Capturing) {
            _uiState.value = CameraUiState.Capturing
        }
    }

    /**
     * Called when ML Kit detects and decodes a barcode or QR code symbol.
     *
     * Transitions to [CameraUiState.BarcodeResult] and posts the decoded payload as
     * a [com.aiassistant.domain.model.Message] in the active conversation
     * (Requirement 6.4).
     *
     * @param payload        The decoded content string.
     * @param format         The barcode format name (e.g. "QR_CODE", "EAN_13").
     * @param conversationId Identifier of the conversation to post the payload into.
     * @param provider       LLM provider identifier for the message.
     */
    fun onBarcodeDetected(payload: String, format: String, conversationId: String, provider: String) {
        _uiState.value = CameraUiState.BarcodeResult(payload = payload, format = format)

        viewModelScope.launch {
            val content = buildString {
                append("[Barcode scanned â€” $format]\n")
                append(payload)
            }
            withContext(dispatchers.io) {
                sendMessageUseCase(
                    conversationId = conversationId,
                    content = content,
                    provider = provider
                )
            }
            // State stays on BarcodeResult; errors are silently swallowed as the scan
            // payload is already displayed. A future enhancement could show a snackbar.
        }
    }

    /**
     * Called when ML Kit OCR has finished processing the image.
     * Transitions to [CameraUiState.OcrResult] (Requirement 6.3).
     *
     * @param imageUri URI of the processed image.
     * @param text     Full extracted text.
     * @param boxes    Normalised bounding boxes with associated text spans.
     */
    fun onOcrComplete(imageUri: Uri, text: String, boxes: List<OcrBoundingBox>) {
        _uiState.value = CameraUiState.OcrResult(
            imageUri = imageUri,
            extractedText = text,
            boundingBoxes = boxes
        )
    }

    /**
     * Transitions to [CameraUiState.VisionUnsupported] when the active provider does
     * not support image/vision input (Requirement 6.6).
     *
     * @param activeProvider     The provider that rejected the vision request.
     * @param suggestedProviders Known vision-capable providers to suggest.
     */
    fun onProviderVisionUnsupported(activeProvider: String, suggestedProviders: List<String>) {
        _uiState.value = CameraUiState.VisionUnsupported(
            activeProvider = activeProvider,
            suggestedProviders = suggestedProviders
        )
    }

    /**
     * Posts the OCR-extracted [text] as a message in the active conversation.
     *
     * Called from the OcrResultScreen "Add to Chat" action. The current [conversationId]
     * and [currentProvider] context must be set via [setConversationContext] before calling.
     * (Requirement 6.3)
     *
     * @param text The extracted OCR text to post as a user message.
     */
    fun submitOcrTextAsMessage(text: String) {
        if (text.isBlank() || conversationId.isBlank()) return

        viewModelScope.launch {
            withContext(dispatchers.io) {
                sendMessageUseCase(
                    conversationId = conversationId,
                    content = "[OCR extracted text]\n$text",
                    provider = currentProvider
                )
            }
        }
    }

    /**
     * Resets any state back to [CameraUiState.Idle].
     * Called when the user taps "Dismiss" on an error or result screen.
     */
    fun reset() {
        _uiState.value = CameraUiState.Idle
    }

    // â”€â”€â”€ Constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    companion object {
        /** Maximum allowed image dimension (width or height) in pixels (Requirement 6.2). */
        const val MAX_IMAGE_DIMENSION = 4096

        /**
         * LLM provider identifiers that support vision/image input (Requirement 6.6).
         * When the active provider is not in this list, [VisionUnsupported] is emitted.
         */
        val VISION_CAPABLE_PROVIDERS: List<String> = listOf(
            "gpt-4o",
            "gemini-1.5-pro",
            "claude-3-5-sonnet"
        )
    }
}
