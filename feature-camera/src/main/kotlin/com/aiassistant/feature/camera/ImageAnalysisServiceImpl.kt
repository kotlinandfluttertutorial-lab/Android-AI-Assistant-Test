/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : ImageAnalysisServiceImpl.kt
 * Purpose    : ImageAnalysisServiceImpl — feature-camera module component
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
 * File       : ImageAnalysisServiceImpl.kt
 * Purpose    : ImageAnalysisServiceImpl — feature-camera module component
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
 * ImageAnalysisServiceImpl.kt
 *
 * Purpose: Concrete implementation of [ImageAnalysisService] backed by ML Kit on-device
 *          OCR (text recognition) and Retrofit multipart upload to the AI Orchestrator.
 * Architecture: feature-camera â€” data/service layer within the feature module.
 * Dependencies: ML Kit Text Recognition, Retrofit (via core-network pattern), Hilt
 *
 * Requirements: 6.2, 6.3, 6.4
 */
package com.aiassistant.feature.camera

import android.graphics.Bitmap
import android.util.Base64
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Implementation of [ImageAnalysisService].
 *
 * OCR uses the ML Kit on-device Latin text recogniser â€” no network required.
 * Vision LLM analysis uploads a base64-encoded JPEG to the backend `/vision/analyze`
 * endpoint and returns the structured response.
 *
 * @param okHttpClient Authenticated OkHttpClient provided by the `core-network` module.
 * @param backendBaseUrl Base URL of the FastAPI backend (e.g. `https://api.example.com`).
 */
class ImageAnalysisServiceImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val backendBaseUrl: String
) : ImageAnalysisService {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // â”€â”€â”€ OCR â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Uses ML Kit on-device text recognition to extract text and bounding boxes from
     * [bitmap] (Requirement 6.3).
     */
    override suspend fun performOcr(bitmap: Bitmap): OcrResult = suspendCancellableCoroutine { cont ->
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val boxes = visionText.textBlocks.flatMap { block ->
                    block.lines.map { line ->
                        val rect = line.boundingBox
                        OcrBoundingBox(
                            left = rect?.left?.toFloat() ?: 0f,
                            top = rect?.top?.toFloat() ?: 0f,
                            right = rect?.right?.toFloat() ?: 0f,
                            bottom = rect?.bottom?.toFloat() ?: 0f,
                            text = line.text
                        )
                    }
                }
                cont.resume(OcrResult.Success(text = fullText, boundingBoxes = boxes))
            }
            .addOnFailureListener { e ->
                cont.resume(OcrResult.Failure(message = e.message ?: "OCR failed"))
            }
    }

    // â”€â”€â”€ Vision LLM â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Sends [bitmap] and [userPrompt] to the backend `/vision/analyze` endpoint for
     * LLM-powered vision analysis (Requirement 6.4).
     *
     * The image is JPEG-compressed and base64-encoded before being included in the JSON
     * request body alongside the user prompt and provider identifier.
     */
    override suspend fun analyzeWithVisionLLM(
        bitmap: Bitmap,
        userPrompt: String,
        provider: String
    ): VisionAnalysisResult = try {
        val imageBase64 = bitmapToBase64(bitmap)
        val json = JSONObject().apply {
            put("image_base64", imageBase64)
            put("prompt", userPrompt)
            put("provider", provider)
        }
        val requestBody = json.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$backendBaseUrl/vision/analyze")
            .post(requestBody)
            .build()

        val response = okHttpClient.newCall(request).execute()
        if (response.isSuccessful) {
            val body = response.body?.string() ?: ""
            val responseJson = JSONObject(body)
            val analysisText = responseJson.optString("analysis", body)
            VisionAnalysisResult.Success(response = analysisText)
        } else {
            VisionAnalysisResult.Failure(
                message = "Backend returned HTTP ${response.code}: ${response.message}"
            )
        }
    } catch (e: Exception) {
        VisionAnalysisResult.Failure(message = e.message ?: "Vision analysis failed")
    }

    // â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    companion object {
        /** JPEG compression quality (0â€“100) for images uploaded to the backend. */
        private const val JPEG_QUALITY = 85
    }
}
