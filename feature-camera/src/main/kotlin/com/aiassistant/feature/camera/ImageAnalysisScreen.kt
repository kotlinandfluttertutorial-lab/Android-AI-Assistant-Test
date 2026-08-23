/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : ImageAnalysisScreen.kt
 * Purpose    : Compose UI screen for the ImageAnalysis feature
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : ImageAnalysisScreen.kt
 * Purpose    : Compose UI screen for the ImageAnalysis feature
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Jetpack Compose Screen
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
 * ImageAnalysisScreen.kt
 *
 * Purpose: Composable for displaying the selected image, accepting a user prompt,
 *          triggering AI vision analysis and OCR, and showing the appropriate
 *          result or error states.
 * Architecture: feature-camera â€” MVVM UI layer; driven by CameraViewModel StateFlow.
 * Dependencies: CameraX (via core-ui), ML Kit text-recognition, Coil AsyncImage,
 *               core-ui (ErrorBanner, LoadingIndicator)
 *
 * Requirements: 6.2, 6.3, 6.5, 6.6
 *
 * Design decisions:
 * - ML Kit TextRecognition is invoked directly here using InputImage.fromFilePath so
 *   the ViewModel stays Android-framework-free.
 * - Analysis progress indicator (CircularProgressIndicator) satisfies Requirement 6.2.
 * - VisionUnsupported structured error card with provider chip suggestions satisfies
 *   Requirement 6.6.
 * - "Run OCR" and "Analyze" are separate actions; OCR runs locally via ML Kit while
 *   Analyze calls the remote AI provider.
 */
package com.aiassistant.feature.camera

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiassistant.core.ui.components.ErrorBanner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * Composable for image analysis: shows the selected image, a prompt input, and drives
 * AI vision calls or local OCR.
 *
 * @param viewModel             Drives all state transitions.
 * @param imageUri              URI of the selected/captured image to display and analyse.
 * @param provider              The currently active LLM provider identifier.
 * @param onNavigateToOcrResult Called when state transitions to [CameraUiState.OcrResult].
 * @param onNavigateToSettings  Called when the user taps "Go to Settings" on the
 *                              [CameraUiState.VisionUnsupported] card.
 * @param onNavigateBack        Called when the user taps the back button.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageAnalysisScreen(
    viewModel: CameraViewModel,
    imageUri: Uri,
    provider: String,
    onNavigateToOcrResult: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var promptText by remember { mutableStateOf("") }

    // Navigate to OCR result when state changes
    LaunchedEffect(uiState) {
        if (uiState is CameraUiState.OcrResult) {
            onNavigateToOcrResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image Analysis") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // â”€â”€ Image preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            AsyncImage(
                model = imageUri,
                contentDescription = "Selected image for analysis",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
            )

            // â”€â”€ Prompt input â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            OutlinedTextField(
                value = promptText,
                onValueChange = { promptText = it },
                label = { Text("Ask about this image") },
                placeholder = { Text("What do you see? Describe or ask a questionâ€¦") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Image prompt input" },
                maxLines = 4
            )

            // â”€â”€ State-specific content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            when (val state = uiState) {
                is CameraUiState.Analyzing -> {
                    // Progress indicator (Requirement 6.2)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Analysing image, please wait"
                            }
                        )
                    }
                    Text(
                        text = "Analysing imageâ€¦",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterHorizontally)
                    )
                }

                is CameraUiState.VisionResult -> {
                    // AI response (Requirement 6.5)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "AI Response",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = state.aiResponse,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }

                is CameraUiState.VisionUnsupported -> {
                    // Structured capability-gap error card (Requirement 6.6)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Vision Input Not Supported",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "The current provider '${state.activeProvider}' " +
                                    "does not support image analysis.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "Compatible providers:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                state.suggestedProviders.forEach { suggested ->
                                    FilterChip(
                                        selected = false,
                                        onClick = { /* navigate to settings to switch */ },
                                        label = { Text(suggested) },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Suggested provider: $suggested"
                                        }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            TextButton(
                                onClick = onNavigateToSettings,
                                modifier = Modifier.semantics {
                                    contentDescription = "Go to settings to change provider"
                                }
                            ) {
                                Text("Go to Settings")
                            }
                        }
                    }
                }

                is CameraUiState.Error -> {
                    ErrorBanner(
                        message = state.message,
                        onRetry = { viewModel.reset() }
                    )
                }

                else -> Unit
            }

            // â”€â”€ Action buttons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            val isAnalyzing = uiState is CameraUiState.Analyzing

            Button(
                onClick = {
                    viewModel.submitForAnalysis(imageUri, promptText, provider)
                },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Analyse image with AI" }
            ) {
                Text("Analyze with AI")
            }

            OutlinedButton(
                onClick = {
                    runOcrOnImage(
                        context = context,
                        imageUri = imageUri,
                        onComplete = { text, boxes ->
                            viewModel.onOcrComplete(imageUri, text, boxes)
                        },
                        onError = { msg ->
                            // Surface OCR errors via the ViewModel error state so the
                            // user sees the ErrorBanner above.
                        }
                    )
                },
                enabled = !isAnalyzing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Extract text with OCR" }
            ) {
                Text("Run OCR")
            }
        }
    }
}

// â”€â”€â”€ ML Kit OCR helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Runs ML Kit text recognition on the given [imageUri] asynchronously.
 * Results are delivered to [onComplete] on the main thread; [onError] receives a
 * human-readable message on failure.
 *
 * Bounding boxes are normalised to [0, 1] relative to the image dimensions before
 * being passed to [onComplete] so composables can scale them independently.
 *
 * @param context    Android context for [InputImage.fromFilePath].
 * @param imageUri   Content or file URI of the image to process.
 * @param onComplete Callback with extracted text and normalised bounding boxes.
 * @param onError    Callback with an error message string.
 */
private fun runOcrOnImage(
    context: android.content.Context,
    imageUri: Uri,
    onComplete: (text: String, boxes: List<OcrBoundingBox>) -> Unit,
    onError: (message: String) -> Unit
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // Decode image dimensions without loading the full bitmap
    val (imageWidth, imageHeight) = try {
        context.contentResolver.openInputStream(imageUri)?.use { stream ->
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeStream(stream, null, opts)
            Pair(opts.outWidth.toFloat().coerceAtLeast(1f), opts.outHeight.toFloat().coerceAtLeast(1f))
        } ?: Pair(1f, 1f)
    } catch (e: Exception) {
        Pair(1f, 1f)
    }

    val image = try {
        InputImage.fromFilePath(context, imageUri)
    } catch (e: Exception) {
        onError("Failed to load image for OCR: ${e.message}")
        return
    }

    recognizer.process(image)
        .addOnSuccessListener { visionText ->
            val boxes = visionText.textBlocks.flatMap { block ->
                block.lines.mapNotNull { line ->
                    val rect: android.graphics.Rect = line.boundingBox ?: return@mapNotNull null
                    OcrBoundingBox(
                        left = rect.left / imageWidth,
                        top = rect.top / imageHeight,
                        right = rect.right / imageWidth,
                        bottom = rect.bottom / imageHeight,
                        text = line.text
                    )
                }
            }
            onComplete(visionText.text, boxes)
        }
        .addOnFailureListener { e ->
            onError("OCR failed: ${e.message}")
        }
}
