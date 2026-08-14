/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : DevicePosture.kt
 * Purpose    : DevicePosture — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * Module     : core-ui
 * File       : DevicePosture.kt
 * Purpose    : DevicePosture — core-ui module component
 *
 * Architecture Layer : Core-UI
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
 * DevicePosture.kt
 *
 * Purpose: Foldable device posture model and composable observer.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: androidx.window:window, Compose runtime.
 * Requirements: 24.4
 *
 * Design decisions:
 * - The posture State is derived from WindowLayoutInfo using a Flow collected inside
 *   LaunchedEffect. This means posture changes trigger recomposition of observers
 *   without causing the parent composable to lose its own remembered state.
 * - State hoisting: callers own their UI state (selected item, scroll position, etc.)
 *   in ViewModels. The posture is an observation-only input â€” fold/unfold never resets
 *   caller-owned state.
 */
package com.aiassistant.core.ui.adaptive

import android.graphics.Rect
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** Sealed hierarchy representing the current physical posture of the device. */
sealed interface DevicePosture {
    /** Normal flat device â€” phone or unfolded tablet. */
    data object Normal : DevicePosture

    /**
     * Device is partially folded in book / tent mode.
     * @param hingeBounds The bounding rectangle of the hinge in window coordinates.
     */
    data class BookPosture(val hingeBounds: Rect) : DevicePosture

    /**
     * Device has a separating hinge (table-top or dual-screen mode).
     * @param hingeBounds The bounding rectangle of the hinge in window coordinates.
     * @param isVertical  True when the hinge is vertical (left-right split).
     */
    data class SeparatingHinge(val hingeBounds: Rect, val isVertical: Boolean) : DevicePosture
}

/**
 * Observes the device's physical fold/hinge posture and returns it as a [State].
 *
 * The returned state updates reactively whenever the user folds or unfolds the device.
 * The initial value is [DevicePosture.Normal]; it is updated on the first emission from
 * [WindowInfoTracker].
 *
 * IMPORTANT: This composable must be called from a composable hosted in a
 * [ComponentActivity] context (i.e., inside [setContent]). Calling it from a
 * non-Activity context silently returns [DevicePosture.Normal].
 */
@Composable
fun rememberDevicePosture(): State<DevicePosture> {
    val context = LocalContext.current
    val activity = context as? ComponentActivity

    return produceState<DevicePosture>(initialValue = DevicePosture.Normal) {
        if (activity == null) return@produceState // non-Activity context: always Normal

        WindowInfoTracker
            .getOrCreate(activity)
            .windowLayoutInfo(activity)
            .collect { layoutInfo ->
                val foldingFeature = layoutInfo.displayFeatures
                    .filterIsInstance<FoldingFeature>()
                    .firstOrNull()

                value = when {
                    foldingFeature == null -> DevicePosture.Normal

                    foldingFeature.isSeparating -> DevicePosture.SeparatingHinge(
                        hingeBounds = foldingFeature.bounds,
                        isVertical = foldingFeature.orientation ==
                            FoldingFeature.Orientation.VERTICAL
                    )

                    foldingFeature.state == FoldingFeature.State.HALF_OPENED ->
                        DevicePosture.BookPosture(
                            hingeBounds = foldingFeature.bounds
                        )

                    else -> DevicePosture.Normal
                }
            }
    }
}
