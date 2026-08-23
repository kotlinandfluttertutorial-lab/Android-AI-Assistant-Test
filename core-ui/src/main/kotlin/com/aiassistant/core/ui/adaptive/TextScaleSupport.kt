/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : TextScaleSupport.kt
 * Purpose    : TextScaleSupport — core-ui module component
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
 * File       : TextScaleSupport.kt
 * Purpose    : TextScaleSupport — core-ui module component
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
 * TextScaleSupport.kt
 *
 * Purpose: Text scaling utilities ensuring 200% font scale causes no truncation/overflow.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: Compose UI, Compose Foundation.
 * Requirements: 24.5
 *
 * Design decisions:
 * - AutoResizeText uses softWrap=true and TextOverflow.Visible so text always wraps
 *   rather than being clipped at any font scale.
 * - All container modifiers use wrapContentHeight() instead of fixed dp heights when
 *   content includes text. This file documents and enforces that convention.
 */
package com.aiassistant.core.ui.adaptive

import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow

/**
 * Returns the current system font scale factor from [LocalDensity].
 *
 * A value of 1.0 is default; 2.0 means the user has set 200% text size.
 * Use this to make conditional layout decisions for very large text sizes.
 */
val rememberFontScale: Float
    @Composable
    @ReadOnlyComposable
    get() = LocalDensity.current.fontScale

/**
 * True when the system font scale is >= 1.5 (150% or larger).
 * Useful for switching to a more spacious layout variant at large text sizes.
 */
val isLargeTextScale: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalDensity.current.fontScale >= 1.5f

/**
 * Wraps [Modifier.wrapContentSize] to make containers accommodate text at any scale.
 *
 * Apply to any Box/Column/Row that directly contains scaled text to prevent overflow:
 * ```kotlin
 * Box(modifier = Modifier.wrapContentScaled()) { Text("...") }
 * ```
 */
fun Modifier.wrapContentScaled(): Modifier = this.wrapContentSize()

/**
 * A [Text] wrapper that never truncates or clips regardless of system font scale.
 *
 * Uses [TextOverflow.Visible] and [softWrap]=true to ensure text wraps to additional
 * lines rather than being cut off, satisfying the 200% text scale requirement.
 *
 * **Containers must use [wrapContentHeight] or weight-based modifiers â€” never fixed dp
 * heights â€” so this composable can expand vertically as the font scale increases.**
 *
 * @param text        The string to display.
 * @param modifier    Optional modifier. Prefer [Modifier.wrapContentScaled] on the
 *                    containing Box/Column/Row rather than on this Text itself.
 * @param color       Optional text color override.
 * @param style       Text style; defaults to [LocalTextStyle].
 * @param maxLines    Maximum number of lines before wrapping stops. Defaults to
 *                    [Int.MAX_VALUE] (unlimited). Setting a finite value is valid â€”
 *                    text will still wrap within those lines rather than being
 *                    clipped, because [TextOverflow.Visible] is always applied.
 *                    Note: at 200% font scale a finite [maxLines] may still cause
 *                    visible clipping if the container uses a fixed height; always
 *                    pair a finite [maxLines] with [wrapContentHeight] on the parent.
 */
@Composable
fun AutoResizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        overflow = TextOverflow.Visible,
        softWrap = true,
        maxLines = maxLines,
        style = style
    )
}
