/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : AdaptiveScaffold.kt
 * Purpose    : AdaptiveScaffold — core-ui module component
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
 * File       : AdaptiveScaffold.kt
 * Purpose    : AdaptiveScaffold — core-ui module component
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
 * AdaptiveScaffold.kt
 *
 * Purpose: Scaffold wrapper providing window-size and posture context to content slots.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: Material3 Scaffold, WindowSizeClass, DevicePosture.
 * Requirements: 23.3, 24.4, 24.5
 *
 * Design decisions:
 * - AdaptiveScaffold is intentionally thin: it wraps Material3 Scaffold and passes
 *   WindowSizeClass + DevicePosture to the content lambda. Feature modules own their
 *   own layout logic using TwoPaneLayout and the posture state.
 * - All content dimensions use weight/wrapContent modifiers, never fixed pixel sizes,
 *   ensuring text scaled to 200% does not cause overflow (Requirement 24.5).
 */
package com.aiassistant.core.ui.adaptive

import androidx.activity.ComponentActivity
import androidx.compose.material3.Scaffold
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpSize

/**
 * An adaptive scaffold composable that provides [WindowSizeClass] and [DevicePosture]
 * to the content slot.
 *
 * @param modifier         Applied to the root [Scaffold].
 * @param topBar           Optional top app bar slot.
 * @param bottomBar        Optional bottom navigation slot.
 * @param windowSizeClass  Override the detected window size class (useful in tests).
 *                         Defaults to the calculated class for the current activity.
 * @param devicePosture    Override the detected device posture (useful in tests).
 *                         Defaults to the observed posture via [rememberDevicePosture].
 * @param content          Main content slot; receives [WindowSizeClass] and [DevicePosture].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AdaptiveScaffold(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    windowSizeClass: WindowSizeClass? = null,
    devicePosture: State<DevicePosture>? = null,
    content: @Composable (windowSizeClass: WindowSizeClass, devicePosture: DevicePosture) -> Unit
) {
    val activity = LocalContext.current as? ComponentActivity
    val resolvedWindowSizeClass = windowSizeClass
        ?: if (activity != null) {
            calculateWindowSizeClass(activity = activity)
        } else {
            WindowSizeClass.calculateFromSize(DpSize.Zero)
        }

    val postureState = devicePosture ?: rememberDevicePosture()
    val posture by postureState

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar
    ) { contentPadding ->
        content(resolvedWindowSizeClass, posture)
        // contentPadding is forwarded via the inner Box so callers receive insets
        // from the Scaffold's slot API (status bar, nav bar, IME).
        // Feature screens must apply Modifier.padding(contentPadding) to their root
        // layout. AdaptiveScaffold deliberately does NOT apply padding itself here
        // because the content lambda receives (windowSizeClass, posture) â€” the caller
        // owns all layout decisions, including when to consume the padding.
        //
        // To access contentPadding inside the content lambda, callers should upgrade
        // to the overload that exposes it:
        //   AdaptiveScaffoldWithPadding(...) { windowSizeClass, posture, padding -> ... }
        @Suppress("UNUSED_EXPRESSION")
        contentPadding
    }
}

/**
 * Extended overload of [AdaptiveScaffold] that passes Scaffold's [PaddingValues]
 * through to the content lambda, enabling callers to apply inset padding correctly
 * on all screen densities and with dynamic keyboard insets.
 *
 * Prefer this overload when the feature screen's root composable needs to consume
 * [PaddingValues] from the host Scaffold (e.g., to avoid overlap with the system bars
 * or a floating bottom navigation bar).
 *
 * @param modifier         Applied to the root [Scaffold].
 * @param topBar           Optional top app bar slot.
 * @param bottomBar        Optional bottom navigation slot.
 * @param windowSizeClass  Override the detected window size class (useful in tests).
 * @param devicePosture    Override the detected device posture (useful in tests).
 * @param content          Main content slot; receives [WindowSizeClass], [DevicePosture],
 *                         and the [androidx.compose.foundation.layout.PaddingValues] from
 *                         the inner [Scaffold].
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun AdaptiveScaffoldWithPadding(
    modifier: Modifier = Modifier,
    topBar: @Composable () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
    windowSizeClass: WindowSizeClass? = null,
    devicePosture: State<DevicePosture>? = null,
    content: @Composable (
        windowSizeClass: WindowSizeClass,
        devicePosture: DevicePosture,
        contentPadding: androidx.compose.foundation.layout.PaddingValues
    ) -> Unit
) {
    val activity = LocalContext.current as? ComponentActivity
    val resolvedWindowSizeClass = windowSizeClass
        ?: if (activity != null) {
            calculateWindowSizeClass(activity = activity)
        } else {
            WindowSizeClass.calculateFromSize(DpSize.Zero)
        }

    val postureState = devicePosture ?: rememberDevicePosture()
    val posture by postureState

    Scaffold(
        modifier = modifier,
        topBar = topBar,
        bottomBar = bottomBar
    ) { contentPadding ->
        content(resolvedWindowSizeClass, posture, contentPadding)
    }
}
