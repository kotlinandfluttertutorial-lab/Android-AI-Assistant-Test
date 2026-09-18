/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : Shape.kt
 * Purpose    : Shape — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Object / val
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Single source of truth for all shape tokens
 *
 * Updated    : Phase 2.3 — Production UI/UX Upgrade
 *   Added AppShapes extended token set (pill, bubble, sheet, codeBlock, avatar)
 * ============================================================
 */
/**
 * Shape.kt
 *
 * Purpose: Material Design 3 shape system for the AI Assistant application.
 * Architecture: core-ui — shared design system.
 * Dependencies: Compose Material 3 shape APIs.
 *
 * Design decisions:
 * - Five shape tokens map to M3's five standard sizes: Extra Small, Small, Medium,
 *   Large, and Extra Large. These correspond to the component-size shape roles used by
 *   Material 3 components automatically.
 * - [AppShapes] extends the standard M3 set with application-specific tokens for chat
 *   bubbles, pill chips, bottom sheets, code blocks, and avatars.
 * - Requirements: 24.1
 */

package com.aiassistant.core.ui

import androidx.compose.foundation.shape.CircleShape
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
     * Extra Small — used for very small components such as chips, tooltips, and text fields.
     * 4 dp corner radius.
     */
    extraSmall = RoundedCornerShape(4.dp),

    /**
     * Small — used for buttons, snackbars, and similar compact elements.
     * 8 dp corner radius.
     */
    small = RoundedCornerShape(8.dp),

    /**
     * Medium — used for cards, dialogs, and bottom sheets.
     * 12 dp corner radius.
     */
    medium = RoundedCornerShape(12.dp),

    /**
     * Large — used for navigation drawers and large containers.
     * 16 dp corner radius.
     */
    large = RoundedCornerShape(16.dp),

    /**
     * Extra Large — used for full-screen bottom sheets and large modal surfaces.
     * 28 dp corner radius.
     */
    extraLarge = RoundedCornerShape(28.dp)
)

// ── Extended shape tokens (Phase 2.3 — Production UI/UX Upgrade) ─────────────

/**
 * Additional shape tokens for components that need precise control outside the
 * five standard M3 roles.
 *
 * | Token        | Value        | Use case                                           |
 * |--------------|--------------|----------------------------------------------------|
 * | [pill]       | 50% (circle) | Chips, AiModeIndicator, message input bar, badges  |
 * | [bubble]     | 18 dp        | Chat message bubbles (large smooth corners)        |
 * | [bubbleTail] | 4 dp         | The "tail" corner on asymmetric chat bubbles       |
 * | [sheet]      | top-28 dp    | Bottom sheets / modal drawers (top corners only)   |
 * | [codeBlock]  | 8 dp         | Code block and inline code snippet container       |
 * | [avatar]     | CircleShape  | Avatar and circular icon containers                |
 * | [statusBadge]| 6 dp         | Connectivity / cache status inline badge           |
 * | [inputBar]   | 28 dp        | Pill-shaped message input bar                      |
 */
object AppShapes {

    /** Fully rounded pill — used for chips, badges, AiModeIndicator, and input bars. */
    val pill = RoundedCornerShape(percent = 50)

    /**
     * Standard chat bubble — large radius on three corners, small on the tail corner.
     * The actual bubble shape is constructed per-side in ChatBubble.kt using
     * [bubbleTail] for the directional corner.
     */
    val bubble = RoundedCornerShape(18.dp)

    /**
     * The "tail" corner radius for asymmetric chat bubbles.
     * User bubble: bottom-end = [bubbleTail]
     * Assistant bubble: top-start = [bubbleTail]
     */
    val bubbleTail = 4.dp

    /** Top-only rounding for bottom sheets and modal drawers. */
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** Code block and inline code snippet container. */
    val codeBlock = RoundedCornerShape(8.dp)

    /** Avatar and circular icon containers (e.g., AI provider avatar, user avatar). */
    val avatar = CircleShape

    /** Connectivity / cache status bar — small rounding for subtle inline badge. */
    val statusBadge = RoundedCornerShape(6.dp)

    /** Message input bar — large pill matching send button curvature. */
    val inputBar = RoundedCornerShape(28.dp)
}
