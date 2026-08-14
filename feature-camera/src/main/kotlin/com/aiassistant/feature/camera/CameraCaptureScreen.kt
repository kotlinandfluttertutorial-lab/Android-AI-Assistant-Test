/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : CameraCaptureScreen.kt
 * Purpose    : Compose UI screen for the CameraCapture feature
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
 * File       : CameraCaptureScreen.kt
 * Purpose    : Compose UI screen for the CameraCapture feature
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
 * CameraCaptureScreen.kt
 *
 * Purpose: Full-screen camera capture composable. Handles CAMERA permission, shows a live
 *          CameraX preview, provides a shutter button and gallery picker, and activates
 *          ML Kit barcode/QR scanning via an ImageAnalysis use case.
 * Architecture: feature-camera â€” MVVM UI layer; driven by CameraViewModel StateFlow.
 * Dependencies: CameraX (Preview, ImageCapture, ImageAnalysis), ML Kit barcode-scanning,
 *               Hilt, Compose, core-ui (ErrorBanner)
 *
 * Requirements: 6.1, 6.4
 *
 * Design decisions:
 * - All ContentDescription attributes are set for accessibility (requirement 23.4).
 * - CameraX lifecycle binding is scoped to LocalLifecycleOwner so it is automatically
 *   unbound when the composable leaves composition.
 * - Gallery picker uses ActivityResultContracts.GetContent for image/star MIME type.
 * - ImageCapture stores results to a temp file in cacheDir; the URI is then decoded to
 *   obtain width/height before passing to the ViewModel for resolution validation.
 * - Barcode scanning uses a plain ImageAnalysis.Analyzer feeding frames to ML Kit
 *   BarcodeScanner â€” avoids the camera-mlkit-vision artifact dependency which is not
 *   published for CameraX 1.3.4.
 */
package com.aiassistant.feature.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.aiassistant.core.ui.components.ErrorBanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Full-screen composable for camera capture, gallery selection, and barcode scanning.
 *
 * Observes [CameraViewModel.uiState] and reacts to all permission and capture state
 * transitions. Navigation callbacks are called at the appropriate state transitions.
 *
 * @param viewModel              Drives all state transitions for the camera flow.
 * @param onNavigateToAnalysis   Called with the image [Uri] when an image is ready.
 * @param onNavigateToOcrResult  Called when a barcode result is ready.
 * @param onNavigateBack         Called when the user taps the back/close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraCaptureScreen(
    viewModel: CameraViewModel,
    onNavigateToAnalysis: (Uri) -> Unit,
    onNavigateToOcrResult: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // â”€â”€ Gallery picker â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { selectedUri ->
            val (w, h) = context.resolveImageDimensions(selectedUri)
            viewModel.onImageCaptured(selectedUri, w, h)
        }
    }

    // â”€â”€ Permission request â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.onPermissionGranted() else viewModel.onPermissionDenied()
    }

    // â”€â”€ Navigate on ImageSelected / BarcodeResult â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is CameraUiState.ImageSelected -> onNavigateToAnalysis(state.uri)
            is CameraUiState.BarcodeResult -> onNavigateToOcrResult()
            is CameraUiState.RequestingPermission -> {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Camera") },
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (uiState) {
                is CameraUiState.PermissionDenied -> {
                    // â”€â”€ Permission denied state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ErrorBanner(
                            message = "Camera permission is required to use this feature.",
                            onRetry = {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            },
                            contentDescription = "Camera permission denied. Open Settings to grant it."
                        )
                    }
                }

                is CameraUiState.Capturing, CameraUiState.Idle -> {
                    // â”€â”€ Camera preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    var isScanningBarcodes by remember { mutableStateOf(false) }
                    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
                    val imageCapture = remember { ImageCapture.Builder().build() }

                    // Guard prevents multiple barcode callbacks for the same symbol
                    val barcodeDetectedLatch = remember { AtomicBoolean(false) }

                    AndroidView(
                        factory = { ctx ->
                            val previewView = PreviewView(ctx)
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val barcodeScanner = BarcodeScanning.getClient(
                                    BarcodeScannerOptions.Builder()
                                        .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                                        .build()
                                )

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                            if (isScanningBarcodes) {
                                                processImageForBarcodes(
                                                    imageProxy = imageProxy,
                                                    barcodeScanner = barcodeScanner,
                                                    detectedLatch = barcodeDetectedLatch,
                                                    onBarcodeDetected = { payload, format ->
                                                        viewModel.onBarcodeDetected(
                                                            payload = payload,
                                                            format = format,
                                                            conversationId = "",
                                                            provider = ""
                                                        )
                                                    }
                                                )
                                            } else {
                                                imageProxy.close()
                                            }
                                        }
                                    }

                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture,
                                        imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    viewModel.reset()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                            previewView
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Camera preview" }
                    )

                    // â”€â”€ Shutter button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    FloatingActionButton(
                        onClick = {
                            val outputFile = File.createTempFile("capture_", ".jpg", context.cacheDir)
                            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
                            imageCapture.takePicture(
                                outputOptions,
                                ContextCompat.getMainExecutor(context),
                                object : ImageCapture.OnImageSavedCallback {
                                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                        val uri = Uri.fromFile(outputFile)
                                        val (w, h) = context.resolveImageDimensions(uri)
                                        viewModel.onImageCaptured(uri, w, h)
                                    }

                                    override fun onError(exception: ImageCaptureException) {
                                        viewModel.reset()
                                    }
                                }
                            )
                        },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                            .size(72.dp)
                            .border(4.dp, Color.White, CircleShape)
                            .semantics { contentDescription = "Capture photo" },
                        containerColor = MaterialTheme.colorScheme.primary
                    ) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // â”€â”€ Gallery picker button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    SmallFloatingActionButton(
                        onClick = { galleryLauncher.launch("image/*") },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 36.dp, end = 16.dp)
                            .semantics { contentDescription = "Pick from gallery" },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Image,
                            contentDescription = null
                        )
                    }

                    // â”€â”€ Barcode scan toggle â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    SmallFloatingActionButton(
                        onClick = {
                            isScanningBarcodes = !isScanningBarcodes
                            barcodeDetectedLatch.set(false)
                            if (isScanningBarcodes) viewModel.startBarcodeScanning()
                        },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 36.dp, start = 16.dp)
                            .semantics {
                                contentDescription = if (isScanningBarcodes) {
                                    "Stop barcode scanning"
                                } else {
                                    "Start barcode scanning"
                                }
                            },
                        containerColor = if (isScanningBarcodes) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QrCodeScanner,
                            contentDescription = null
                        )
                    }
                }

                is CameraUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        ErrorBanner(
                            message = (uiState as CameraUiState.Error).message,
                            onRetry = { viewModel.reset() }
                        )
                    }
                }

                else -> {
                    // Analyzing / OcrResult / VisionResult etc. handled in sibling screens
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Loading"
                            }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Barcode analysis helper â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Feeds a single [ImageProxy] frame into the ML Kit barcode scanner.
 *
 * Uses [AtomicBoolean] as a one-shot latch to prevent duplicate callbacks for the
 * same physical barcode across multiple consecutive frames.
 *
 * @param imageProxy       Frame from CameraX [ImageAnalysis].
 * @param barcodeScanner   Configured ML Kit barcode scanner client.
 * @param detectedLatch    Atomic flag; once a barcode is detected this prevents re-firing.
 * @param onBarcodeDetected Callback with decoded (payload, format) strings.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
private fun processImageForBarcodes(
    imageProxy: ImageProxy,
    barcodeScanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    detectedLatch: AtomicBoolean,
    onBarcodeDetected: (payload: String, format: String) -> Unit
) {
    val mediaImage = imageProxy.image
    if (mediaImage == null) {
        imageProxy.close()
        return
    }
    val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
    barcodeScanner.process(image)
        .addOnSuccessListener { barcodes ->
            val first = barcodes.firstOrNull { it.rawValue != null }
            if (first != null && detectedLatch.compareAndSet(false, true)) {
                onBarcodeDetected(
                    first.rawValue ?: "",
                    first.format.toBarcodeFormatName()
                )
            }
        }
        .addOnCompleteListener {
            imageProxy.close()
        }
}

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Resolves the pixel dimensions of an image at [uri] without fully loading it into memory.
 *
 * @return Pair of (width, height) in pixels, or (0, 0) if the URI cannot be decoded.
 */
private fun Context.resolveImageDimensions(uri: Uri): Pair<Int, Int> = try {
    contentResolver.openInputStream(uri)?.use { stream ->
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        Pair(opts.outWidth, opts.outHeight)
    } ?: Pair(0, 0)
} catch (e: Exception) {
    Pair(0, 0)
}

/**
 * Maps a ML Kit [Barcode.BarcodeFormat] integer constant to a human-readable name string.
 */
private fun Int.toBarcodeFormatName(): String = when (this) {
    Barcode.FORMAT_QR_CODE -> "QR_CODE"
    Barcode.FORMAT_AZTEC -> "AZTEC"
    Barcode.FORMAT_CODE_128 -> "CODE_128"
    Barcode.FORMAT_CODE_39 -> "CODE_39"
    Barcode.FORMAT_CODE_93 -> "CODE_93"
    Barcode.FORMAT_CODABAR -> "CODABAR"
    Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
    Barcode.FORMAT_EAN_13 -> "EAN_13"
    Barcode.FORMAT_EAN_8 -> "EAN_8"
    Barcode.FORMAT_ITF -> "ITF"
    Barcode.FORMAT_PDF417 -> "PDF417"
    Barcode.FORMAT_UPC_A -> "UPC_A"
    Barcode.FORMAT_UPC_E -> "UPC_E"
    else -> "UNKNOWN"
}
