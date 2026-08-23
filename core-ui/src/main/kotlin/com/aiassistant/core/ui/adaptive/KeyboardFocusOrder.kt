/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : KeyboardFocusOrder.kt
 * Purpose    : KeyboardFocusOrder — core-ui module component
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
 * File       : KeyboardFocusOrder.kt
 * Purpose    : KeyboardFocusOrder — core-ui module component
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
 * KeyboardFocusOrder.kt
 *
 * Purpose: Keyboard and D-pad navigation utilities with explicit logical focus order.
 * Architecture: core-ui â€” shared design system adaptive layer.
 * Dependencies: Compose UI Focus.
 * Requirements: 23.5
 *
 * Design decisions:
 * - Explicit focus order is set via focusProperties { next / previous } rather than
 *   relying on the platform's default spatial focus algorithm. This guarantees a
 *   predictable, logical tab order on every screen regardless of layout position.
 * - rememberFocusGroup creates a stable list of FocusRequester instances remembered
 *   across recompositions. The size must match the number of focusable items on screen.
 *
 * Usage example (a login form with three fields):
 * ```kotlin
 * val focusGroup = rememberFocusGroup(size = 3)
 * TextField(modifier = Modifier.logicalFocusOrder(focusGroup, index = 0), ...)
 * TextField(modifier = Modifier.logicalFocusOrder(focusGroup, index = 1), ...)
 * Button(modifier = Modifier.logicalFocusOrder(focusGroup, index = 2), ...)
 * ```
 */
package com.aiassistant.core.ui.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

/**
 * Creates and remembers a list of [FocusRequester] instances for a logical focus group.
 *
 * The returned list is stable across recompositions. Use one requester per focusable
 * element in the group, then wire them together with [logicalFocusOrder].
 *
 * @param size Number of focusable elements in the group.
 * @return A remembered list of [FocusRequester] of the requested [size].
 */
@Composable
fun rememberFocusGroup(size: Int): List<FocusRequester> = remember(size) { List(size) { FocusRequester() } }

/**
 * Applies a logical keyboard/D-pad focus order for an element at [index] within [group].
 *
 * - Tab / D-pad-down moves focus to `group[index + 1]` (wraps to 0 at the end).
 * - Shift+Tab / D-pad-up moves focus to `group[index - 1]` (wraps to last at the start).
 *
 * @param group The [FocusRequester] list created by [rememberFocusGroup].
 * @param index The zero-based position of this element in the focus order.
 */
fun Modifier.logicalFocusOrder(group: List<FocusRequester>, index: Int): Modifier {
    require(group.isNotEmpty()) { "Focus group must not be empty." }
    require(index in group.indices) { "Index $index is out of bounds for group of size ${group.size}." }

    val nextIndex = (index + 1) % group.size
    val previousIndex = (index - 1 + group.size) % group.size

    return this
        .focusRequester(group[index])
        .focusProperties {
            next = group[nextIndex]
            previous = group[previousIndex]
        }
}

/**
 * Convenience modifier that assigns a [FocusRequester] to this element without
 * linking it to a group. Use when only a single element needs programmatic focus.
 *
 * @param requester The [FocusRequester] to attach.
 */
fun Modifier.keyboardFocusTarget(requester: FocusRequester): Modifier = this.focusRequester(requester)
