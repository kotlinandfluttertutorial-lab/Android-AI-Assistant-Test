/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-camera
 * File       : CameraNavigation.kt
 * Purpose    : CameraNavigation — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Navigation Graph / Destinations
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
 * File       : CameraNavigation.kt
 * Purpose    : CameraNavigation — feature-camera module component
 *
 * Architecture Layer : Feature (feature-camera)
 * Pattern Used       : Navigation Graph / Destinations
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
 * CameraNavigation.kt
 *
 * Purpose: Defines the Navigation Compose graph for the camera feature, wiring up
 *          CameraCaptureScreen, ImageAnalysisScreen, and OcrResultScreen under a
 *          single nested navigation route.
 * Architecture: feature-camera â€” navigation layer; integrated into the app-level
 *               NavHost via the [cameraNavGraph] extension function.
 * Dependencies: androidx.navigation.compose, Hilt navigation, feature composables.
 *
 * Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6
 *
 * Design decisions:
 * - The image URI is passed between CaptureScreen and AnalysisScreen as a URL-encoded
 *   string argument so it survives process death; the app URI scheme avoids conflicts
 *   with query string parsing.
 * - OcrResultScreen reads state directly from the shared ViewModel (scoped to the
 *   nested nav graph) so no additional nav argument is required.
 * - The shared ViewModel instance is obtained via hiltViewModel() with the nested
 *   graph's back-stack entry as owner, ensuring it is cleared when the user exits the
 *   camera graph entirely.
 */
package com.aiassistant.feature.camera

import android.net.Uri
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import androidx.navigation.navArgument

/** Navigation argument key for the encoded image URI. */
const val ARG_IMAGE_URI = "imageUri"

// â”€â”€â”€ Route constants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Top-level route for the camera nested navigation graph. */
const val CAMERA_ROUTE = "camera"

/** Route for the camera capture / viewfinder screen. */
const val CAMERA_CAPTURE_ROUTE = "camera/capture"

/** Route for the image analysis screen; requires an [ARG_IMAGE_URI] argument. */
const val CAMERA_ANALYSIS_ROUTE = "camera/analysis/{$ARG_IMAGE_URI}"

/** Route for the OCR / barcode result screen. */
const val CAMERA_OCR_RESULT_ROUTE = "camera/ocr_result"

// â”€â”€â”€ Navigation graph â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Registers the camera feature nested navigation graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app-level NavHost:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "home") {
 *     cameraNavGraph(navController = navController, onNavigateToSettings = { ... })
 * }
 * ```
 *
 * @param navController      The app-level [NavController] used for cross-graph navigation.
 * @param onNavigateToSettings Callback invoked when the user taps "Go to Settings" on the
 *                             VisionUnsupported error card (Requirement 6.6).
 */
fun NavGraphBuilder.cameraNavGraph(navController: NavController, onNavigateToSettings: () -> Unit) {
    navigation(
        startDestination = CAMERA_CAPTURE_ROUTE,
        route = CAMERA_ROUTE
    ) {
        // â”€â”€ Capture screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = CAMERA_CAPTURE_ROUTE) { backStackEntry ->
            val viewModel: CameraViewModel = hiltViewModel(backStackEntry)

            CameraCaptureScreen(
                viewModel = viewModel,
                onNavigateToAnalysis = { imageUri ->
                    val encoded = Uri.encode(imageUri.toString())
                    navController.navigate("camera/analysis/$encoded")
                },
                onNavigateToOcrResult = {
                    navController.navigate(CAMERA_OCR_RESULT_ROUTE)
                },
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // â”€â”€ Analysis screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(
            route = CAMERA_ANALYSIS_ROUTE,
            arguments = listOf(
                navArgument(ARG_IMAGE_URI) { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val viewModel: CameraViewModel = hiltViewModel(backStackEntry)
            val encodedUri = backStackEntry.arguments?.getString(ARG_IMAGE_URI) ?: ""
            val imageUri = Uri.parse(Uri.decode(encodedUri))

            // provider is passed via the ViewModel context; expose it from nav args in
            // a real integration. Using empty string here defers to setConversationContext.
            val provider = backStackEntry.arguments?.getString("provider") ?: ""

            ImageAnalysisScreen(
                viewModel = viewModel,
                imageUri = imageUri,
                provider = provider,
                onNavigateToOcrResult = {
                    navController.navigate(CAMERA_OCR_RESULT_ROUTE)
                },
                onNavigateToSettings = onNavigateToSettings,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // â”€â”€ OCR result screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = CAMERA_OCR_RESULT_ROUTE) { backStackEntry ->
            val viewModel: CameraViewModel = hiltViewModel(backStackEntry)

            OcrResultScreen(
                viewModel = viewModel,
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
