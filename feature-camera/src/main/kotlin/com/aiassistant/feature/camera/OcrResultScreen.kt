/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : OcrResultScreen.kt
 * Purpose    : Compose UI screen for the OcrResult feature
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
 * File       : OcrResultScreen.kt
 * Purpose    : Compose UI screen for the OcrResult feature
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
 * OcrResultScreen.kt
 *
 * Purpose: Composable that displays OCR extracted text in a scrollable card and renders
 *          bounding box overlays on the source image using Canvas. Provides copy-to-
 *          clipboard and "Add to Chat" actions.
 * Architecture: feature-camera â€” MVVM UI layer; driven by CameraViewModel StateFlow.
 * Dependencies: Compose Canvas, Coil AsyncImage, core-ui (ErrorBanner)
 *
 * Requirements: 6.3
 *
 * Design decisions:
 * - OcrBoundingBox coordinates are normalised [0,1]; they are scaled by the measured
 *   image composable size at draw time for accurate overlay placement (Requirement 6.3).
 * - Bounding box drawing uses Canvas drawRect outlines + drawText labels so there is no
 *   dependency on a third-party annotation library.
 * - The "Add to Chat" button calls sendMessageUseCase through the ViewModel to post the
 *   extracted text as a new message in the active conversation.
 * - Clipboard access uses ClipboardManager via LocalContext so no ViewModel dependency
 *   on Android is introduced.
 */
package com.aiassistant.feature.camera

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.aiassistant.core.ui.components.ErrorBanner

/**
 * Composable that presents OCR results with an annotated image overlay.
 *
 * Shows:
 * - The source image with coloured bounding-box rectangles drawn over each recognised
 *   text line (Requirement 6.3).
 * - A scrollable card containing the full extracted text.
 * - A copy-to-clipboard button.
 * - An "Add to Chat" button that posts the extracted text as a message.
 *
 * @param viewModel      Drives state; provides the [CameraUiState.OcrResult] payload.
 * @param onNavigateBack Called when the user taps the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrResultScreen(viewModel: CameraViewModel, onNavigateBack: () -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OCR Result") },
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
        when (val state = uiState) {
            is CameraUiState.OcrResult -> {
                OcrResultContent(
                    state = state,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    onCopyToClipboard = {
                        val clip = ClipData.newPlainText("OCR Text", state.extractedText)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                            as ClipboardManager
                        clipboard.setPrimaryClip(clip)
                    },
                    onAddToChat = {
                        viewModel.onOcrComplete(
                            imageUri = state.imageUri,
                            text = state.extractedText,
                            boxes = state.boundingBoxes
                        )
                        // Post the OCR text as a message in the active conversation
                        // by delegating to the ViewModel (which calls sendMessageUseCase).
                        viewModel.submitOcrTextAsMessage(state.extractedText)
                    }
                )
            }

            is CameraUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorBanner(
                        message = state.message,
                        onRetry = { viewModel.reset() }
                    )
                }
            }

            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No OCR result available.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ OCR result content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * The main content area for an [CameraUiState.OcrResult] state.
 *
 * @param state             The OCR result to display.
 * @param onCopyToClipboard Called when the copy button is tapped.
 * @param onAddToChat       Called when the "Add to Chat" button is tapped.
 * @param modifier          Applied to the root Column.
 */
@Composable
private fun OcrResultContent(
    state: CameraUiState.OcrResult,
    onCopyToClipboard: () -> Unit,
    onAddToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // â”€â”€ Annotated image â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        AnnotatedImage(
            imageUri = state.imageUri,
            boundingBoxes = state.boundingBoxes,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(4f / 3f)
        )

        // â”€â”€ Extracted text card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Extracted Text",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    IconButton(
                        onClick = onCopyToClipboard,
                        modifier = Modifier.semantics {
                            contentDescription = "Copy extracted text to clipboard"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = state.extractedText.ifBlank { "No text detected in this image." },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "Extracted text: ${state.extractedText}"
                    }
                )
            }
        }

        // â”€â”€ Add to Chat button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        Button(
            onClick = onAddToChat,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Add OCR text to chat conversation" },
            enabled = state.extractedText.isNotBlank()
        ) {
            Text("Add to Chat")
        }

        OutlinedButton(
            onClick = onCopyToClipboard,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Copy extracted text to clipboard" }
        ) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = null
            )
            Text(
                text = "  Copy Text",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

// â”€â”€â”€ Annotated image composable â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Displays an image with bounding box overlays drawn using [Canvas].
 *
 * Normalised [OcrBoundingBox] coordinates ([0,1]) are scaled by the composable's
 * measured size on each draw pass so the overlay matches the rendered image.
 *
 * @param imageUri     URI of the image to display.
 * @param boundingBoxes Normalised bounding boxes to draw as outlines + labels.
 * @param modifier     Applied to the root [Box].
 */
@Composable
private fun AnnotatedImage(imageUri: Uri, boundingBoxes: List<OcrBoundingBox>, modifier: Modifier = Modifier) {
    var imageSize by remember { mutableStateOf(IntSize.Zero) }
    val overlayColor = Color(0xFF00BCD4) // teal for good contrast on most images

    Box(modifier = modifier) {
        AsyncImage(
            model = imageUri,
            contentDescription = "Image with OCR bounding box overlays",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { imageSize = it }
        )

        if (imageSize != IntSize.Zero && boundingBoxes.isNotEmpty()) {
            val w = imageSize.width.toFloat()
            val h = imageSize.height.toFloat()

            Canvas(modifier = Modifier.fillMaxSize()) {
                val nativePaint = android.graphics.Paint().apply {
                    color = overlayColor.copy(alpha = 0.9f).toArgb()
                    textSize = 10.sp.toPx()
                    isAntiAlias = true
                }

                boundingBoxes.forEach { box ->
                    val left = box.left * w
                    val top = box.top * h
                    val right = box.right * w
                    val bottom = box.bottom * h

                    // Draw bounding box rectangle outline
                    drawRect(
                        color = overlayColor,
                        topLeft = Offset(left, top),
                        size = Size(right - left, bottom - top),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }

                // Draw label text using native canvas access
                drawIntoCanvas { canvas ->
                    boundingBoxes.forEach { box ->
                        val left = box.left * w
                        val top = box.top * h
                        canvas.nativeCanvas.drawText(
                            box.text.take(30),
                            left,
                            (top - 4.dp.toPx()).coerceAtLeast(0f),
                            nativePaint
                        )
                    }
                }
            }
        }
    }
}
