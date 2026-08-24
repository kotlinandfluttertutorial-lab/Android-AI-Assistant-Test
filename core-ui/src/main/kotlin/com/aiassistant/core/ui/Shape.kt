/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Shape.kt
 * Purpose    : Shape — core-ui module component
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
 * File       : Shape.kt
 * Purpose    : Shape — core-ui module component
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
 * Shape.kt
 *
 * Purpose: Material Design 3 shape system for the AI Assistant application.
 * Architecture: core-ui â€” shared design system.
 * Dependencies: Compose Material 3 shape APIs.
 *
 * Design decisions:
 * - Five shape tokens map to M3's five standard sizes: Extra Small, Small, Medium,
 *   Large, and Extra Large. These correspond to the component-size shape roles used by
 *   Material 3 components automatically; overriding individual components is only needed
 *   for non-standard shapes.
 * - RoundedCornerShape is used throughout for a friendly, modern aesthetic consistent
 *   with Material You's emphasis on organic, rounded forms.
 * - The [MaterialShapes] constant is exported as the single source of truth.
 * - Requirements: 24.1
 */

package com.aiassistant.core.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material Design 3 shape scale used throughout the application.
 *
 * These tokens map directly to the M3 shape roles and are automatically applied by
 * Material 3 components (e.g., [Button] uses [Shapes.small], [Card] uses [Shapes.medium]).
 *
 * Usage in a custom composable:
 * ```kotlin
 * Box(modifier = Modifier.clip(MaterialTheme.shapes.medium)) { ... }
 * ```
 */
val MaterialShapes = Shapes(
    /**
     * Extra Small â€” used for very small components such as chips, tooltips, and text fields.
     * 4 dp corner radius.
     */
    extraSmall = RoundedCornerShape(4.dp),

    /**
     * Small â€” used for buttons, snackbars, and similar compact elements.
     * 8 dp corner radius.
     */
    small = RoundedCornerShape(8.dp),

    /**
     * Medium â€” used for cards, dialogs, and bottom sheets.
     * 12 dp corner radius.
     */
    medium = RoundedCornerShape(12.dp),

    /**
     * Large â€” used for navigation drawers and large containers.
     * 16 dp corner radius.
     */
    large = RoundedCornerShape(16.dp),

    /**
     * Extra Large â€” used for full-screen bottom sheets and large modal surfaces.
     * 28 dp corner radius.
     */
    extraLarge = RoundedCornerShape(28.dp)
)
