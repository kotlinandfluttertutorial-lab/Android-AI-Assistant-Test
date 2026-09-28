/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : data
 * File       : remote/image/ImageAnalysisRemoteDataSourceImpl.kt
 * Purpose    : Calls POST /images/analyze (multipart) and maps the
 *              response to the domain ImageAnalysisResult type,
 *              normalising bounding boxes from absolute pixels to 0..1.
 *
 * Architecture Layer : Data — remote data source
 * Pattern Used       : Repository implementation
 *
 * Key Concepts:
 *   - Sends multipart/form-data to /images/analyze (the real endpoint)
 *   - Base64 → bytes conversion when imageBase64 is provided
 *   - Normalises backend BoundingBox (absolute px) → NormalisedBoundingBox
 *   - Does NOT call the nonexistent /vision/analyze endpoint
 *   - Preserves existing CameraViewModel + ImageAnalysisServiceImpl
 *     flows unchanged; this is an additional data path only
 *
 * Dependencies: okhttp3, kotlinx.serialization, domain network contract
 * ============================================================
 */

package com.aiassistant.data.remote.image

import android.content.ContentResolver
import android.net.Uri
import com.aiassistant.domain.agent.ImageAnalysisResult
import com.aiassistant.domain.agent.NormalisedBoundingBox
import com.aiassistant.domain.network.ImageAnalysisRemoteDataSource
import java.util.Base64
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Singleton
class ImageAnalysisRemoteDataSourceImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    @Named("apiBaseUrl") private val baseUrl: String,
    private val contentResolver: ContentResolver,
) : ImageAnalysisRemoteDataSource {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override suspend fun analyzeImage(
        imageBase64: String?,
        imageUri: String?,
        prompt: String?,
        provider: String?,
        imageWidth: Int?,
        imageHeight: Int?,
    ): ImageAnalysisResult {

        val imageBytes: ByteArray = when {
            !imageBase64.isNullOrBlank() -> Base64.getDecoder().decode(imageBase64)
            !imageUri.isNullOrBlank() -> readUriBytes(imageUri)
            else -> throw IllegalArgumentException("imageBase64 or imageUri must be provided")
        }

        val multipartBuilder = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                name = "file",
                filename = "image.jpg",
                body = imageBytes.toRequestBody("image/jpeg".toMediaType()),
            )

        if (!prompt.isNullOrBlank()) multipartBuilder.addFormDataPart("prompt", prompt)
        if (!provider.isNullOrBlank()) multipartBuilder.addFormDataPart("provider", provider)

        val httpRequest = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/images/analyze")
            .post(multipartBuilder.build())
            .build()

        val response = okHttpClient.newCall(httpRequest).execute()

        val body = response.body?.string()
            ?: throw RuntimeException("Empty response from /images/analyze")

        if (!response.isSuccessful) {
            throw RuntimeException("Image analysis failed (HTTP ${response.code}): $body")
        }

        val dto = json.decodeFromString<ImageAnalyzeResponseDto>(body)

        val w = imageWidth?.takeIf { it > 0 }?.toFloat()
        val h = imageHeight?.takeIf { it > 0 }?.toFloat()

        val normalisedBoxes = dto.boundingBoxes.map { box ->
            NormalisedBoundingBox(
                text = box.text,
                left = if (w != null) box.left.toFloat() / w else 0f,
                top = if (h != null) box.top.toFloat() / h else 0f,
                right = if (w != null) (box.left + box.width).toFloat() / w else 1f,
                bottom = if (h != null) (box.top + box.height).toFloat() / h else 1f,
                confidence = box.confidence / 100f, // backend returns 0–100; domain expects 0–1
            )
        }

        return ImageAnalysisResult(
            extractedText = dto.extractedText,
            boundingBoxes = normalisedBoxes,
            noTextFound = dto.noTextFound,
            visionAnalysis = dto.visionAnalysis,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    private fun readUriBytes(uriString: String): ByteArray =
        contentResolver.openInputStream(Uri.parse(uriString))?.use { it.readBytes() }
            ?: throw RuntimeException("Cannot read image from URI: $uriString")
}

// ── Wire DTOs ─────────────────────────────────────────────────────────────────

@Serializable
private data class BoundingBoxDto(
    val text: String = "",
    val left: Int = 0,
    val top: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val confidence: Float = 0f,
)

@Serializable
private data class ImageAnalyzeResponseDto(
    val extracted_text: String = "",
    val bounding_boxes: List<BoundingBoxDto> = emptyList(),
    val no_text_found: Boolean = false,
    val vision_analysis: String? = null,
) {
    val extractedText: String get() = extracted_text
    val boundingBoxes: List<BoundingBoxDto> get() = bounding_boxes
    val noTextFound: Boolean get() = no_text_found
    val visionAnalysis: String? get() = vision_analysis
}
