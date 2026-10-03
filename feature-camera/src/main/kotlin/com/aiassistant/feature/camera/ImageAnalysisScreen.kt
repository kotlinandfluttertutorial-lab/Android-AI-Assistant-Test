/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : ImageAnalysisScreen.kt
 * Purpose    : Image analysis screen — Phase E UI update.
 *
 *              Aligned to Stitch design reference (ImageScreen.tsx):
 *              - Analyzing state: 4-step analysis progress list
 *                (progress icons: check / loading dots / empty circle)
 *              - Result state: ResultCard components (tinted header + body)
 *                for Description, and "Ask about this" / "New Image" footer buttons
 *              - VisionUnsupported and Error states unchanged
 *              - ML Kit OCR helper unchanged
 *
 * Preserved from prior version:
 *              - ImageAnalysisScreen signature + all ViewModel callbacks
 *              - ML Kit OCR helper (runOcrOnImage)
 *              - All CameraUiState handling
 *
 * Architecture Layer : Feature (feature-camera) — Compose UI layer.
 * Requirements       : 6.2, 6.3, 6.5, 6.6
 * ============================================================
 */
package com.aiassistant.feature.camera

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ImageAnalysisScreen(
    viewModel: CameraViewModel,
    imageUri: Uri,
    provider: String,
    onNavigateToOcrResult: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context  = LocalContext.current
    var promptText by remember { mutableStateOf("") }

    LaunchedEffect(uiState) {
        if (uiState is CameraUiState.OcrResult) onNavigateToOcrResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Image Assistant") },
                navigationIcon = {
                    IconButton(
                        onClick  = onNavigateBack,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Image preview ─────────────────────────────────────────────────
            AsyncImage(
                model            = imageUri,
                contentDescription = "Selected image for analysis",
                contentScale     = ContentScale.Fit,
                modifier         = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .clip(RoundedCornerShape(20.dp)),
            )

            // ── Prompt input ──────────────────────────────────────────────────
            OutlinedTextField(
                value         = promptText,
                onValueChange = { promptText = it },
                label         = { Text("Ask about this image") },
                placeholder   = { Text("What do you see? Describe or ask a question\u2026") },
                modifier      = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Image prompt input" },
                maxLines = 4,
            )

            // ── State-specific content ────────────────────────────────────────
            when (val state = uiState) {
                is CameraUiState.Analyzing -> {
                    // Stitch: 4-step analysis progress list
                    AnalysisProgressList(modifier = Modifier.fillMaxWidth())
                }

                is CameraUiState.VisionResult -> {
                    // Stitch: ResultCard + Ask about this / New Image buttons
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ResultCard(
                            title     = "Description",
                            icon      = "\uD83D\uDCDD",
                            bgColor   = StitchColors.qaChat,
                            textColor = StitchColors.qaIconChat,
                            content   = state.aiResponse,
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Button(
                                onClick  = { /* navigate to chat with image context */ },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Ask AI about this image" },
                            ) { Text("Ask about this") }
                            OutlinedButton(
                                onClick  = onNavigateBack,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Analyze a new image" },
                            ) { Text("New Image") }
                        }
                    }
                }

                is CameraUiState.VisionUnsupported -> {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Vision Input Not Supported", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(8.dp))
                            Text("The current provider '${state.activeProvider}' does not support image analysis.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(12.dp))
                            Text("Compatible providers:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
                            Spacer(Modifier.height(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                state.suggestedProviders.forEach { suggested ->
                                    FilterChip(selected = false, onClick = {}, label = { Text(suggested) }, modifier = Modifier.semantics { contentDescription = "Suggested provider: $suggested" })
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            TextButton(onClick = onNavigateToSettings, modifier = Modifier.semantics { contentDescription = "Go to settings" }) { Text("Go to Settings") }
                        }
                    }
                }

                is CameraUiState.Error -> {
                    ErrorBanner(message = state.message, onRetry = { viewModel.reset() })
                }

                else -> Unit
            }

            // ── Primary action buttons (shown when no result yet) ─────────────
            val isAnalyzing = uiState is CameraUiState.Analyzing
            val hasResult   = uiState is CameraUiState.VisionResult

            if (!hasResult) {
                Button(
                    onClick  = { viewModel.submitForAnalysis(imageUri, promptText, provider) },
                    enabled  = !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Analyze image with AI" },
                ) { Text("Analyze with AI") }

                OutlinedButton(
                    onClick = {
                        runOcrOnImage(
                            context    = context,
                            imageUri   = imageUri,
                            onComplete = { text, boxes -> viewModel.onOcrComplete(imageUri, text, boxes) },
                            onError    = { /* surfaced via ErrorBanner */ },
                        )
                    },
                    enabled  = !isAnalyzing,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Extract text with OCR" },
                ) { Text("Run OCR") }
            }
        }
    }
}

// ── Stitch-aligned UI helpers ─────────────────────────────────────────────────

/**
 * 4-step analysis progress list matching ImageScreen.tsx analyzing state.
 * Completed steps show a green check, the current step shows a loading indicator,
 * and pending steps show an empty circle.
 *
 * In Phase E this is purely presentational (static completed=3, loading=1).
 * Phase F wiring will connect it to a real step state from CameraViewModel.
 */
@Composable
private fun AnalysisProgressList(modifier: Modifier = Modifier) {
    val steps = listOf(
        Triple("Object detection",             true,  false),
        Triple("Text extraction (OCR)",        true,  false),
        Triple("Scene analysis",               true,  false),
        Triple("Generating description",       false, true),
    )

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text  = "Analyzing image\u2026",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "Analyzing image" },
        )
        Spacer(Modifier.height(4.dp))
        steps.forEach { (label, done, loading) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp, horizontal = 14.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(10.dp),
            ) {
                when {
                    done    -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Completed",
                        tint     = StitchColors.voiceProcessing, // green
                        modifier = Modifier.size(20.dp),
                    )
                    loading -> LoadingIndicator(
                        style = LoadingIndicatorStyle.CIRCULAR,
                        modifier = Modifier.size(20.dp),
                        size = 20.dp,
                    )
                    else    -> Icon(
                        imageVector = Icons.Filled.RadioButtonUnchecked,
                        contentDescription = "Pending",
                        tint     = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text  = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (loading) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Stitch-style result card: tinted header bar + white/surface body with description text.
 */
@Composable
private fun ResultCard(
    title: String,
    icon: String,
    bgColor: Color,
    textColor: Color,
    content: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp)),
    ) {
        // Tinted header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = icon, fontSize = 16.sp)
            Text(
                text  = title,
                style = MaterialTheme.typography.labelLarge,
                color = textColor,
                fontWeight = FontWeight.Bold,
            )
        }
        // White body
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color    = MaterialTheme.colorScheme.surface,
        ) {
            Text(
                text  = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
    }
}

// ── ML Kit OCR helper ─────────────────────────────────────────────────────────

/**
 * Runs ML Kit text recognition on the given [imageUri] asynchronously.
 * Results are delivered to [onComplete] on the main thread; [onError] receives a
 * human-readable message on failure.
 */
private fun runOcrOnImage(
    context: android.content.Context,
    imageUri: Uri,
    onComplete: (text: String, boxes: List<OcrBoundingBox>) -> Unit,
    onError: (message: String) -> Unit,
) {
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

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
                        left   = rect.left   / imageWidth,
                        top    = rect.top    / imageHeight,
                        right  = rect.right  / imageWidth,
                        bottom = rect.bottom / imageHeight,
                        text   = line.text,
                    )
                }
            }
            onComplete(visionText.text, boxes)
        }
        .addOnFailureListener { e ->
            onError("OCR failed: ${e.message}")
        }
}
