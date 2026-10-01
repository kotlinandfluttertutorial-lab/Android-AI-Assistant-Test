/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/QuickActionGrid.kt
 * Purpose    : Reusable 4-column quick-action card grid used on HomeScreen and
 *              ToolsScreen.  Each cell contains a tinted icon container with a
 *              short label beneath.
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-dashboard HomeDashboard and
 *                      ToolsScreen (feature-dashboard or app module).
 *
 * Design reference   : HomeScreen.tsx — "Quick Actions" section
 *                      ToolsScreen.tsx — "All Tools" 2-column grid
 *
 * Design decisions   :
 * - [QuickAction] is a pure data class — no Android dependencies — so callers
 *   can construct lists from domain/UI models without importing Compose.
 * - [columns] is configurable (default 4) so the same component works for both
 *   the compact 4-column home grid and the 2-column tools grid.
 * - Icon is represented as an [androidx.compose.ui.graphics.vector.ImageVector]
 *   so all icons are sourced from [AppIcons]; no emoji or drawable resources.
 * - [iconBg] and [iconTint] allow per-item colour without embedding colour logic
 *   in the component itself — keeps the component colour-agnostic.
 * - Press scale (0.96f) provides tactile feedback with no third-party library.
 * - [contentDescription] on every icon satisfies WCAG 1.1.1 (non-text content).
 *
 * Requirements       : 23.1 (accessibility), 24.1 (token usage)
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.StitchType
import com.aiassistant.core.ui.spacing

// ── Data model ────────────────────────────────────────────────────────────────

/**
 * Represents a single action item in [QuickActionGrid].
 *
 * @param id          Caller-defined identifier — passed back in [onActionClick].
 * @param label       Short display label (≤ 10 chars recommended).
 * @param icon        Icon sourced from [AppIcons] — no emoji.
 * @param iconBg      Background colour of the 40 × 40 icon container.
 * @param iconTint    Foreground colour of the icon.
 * @param actionDescription Accessibility description for the whole card, e.g.
 *                          "Open voice assistant". Defaults to [label] when null.
 */
data class QuickAction(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val iconBg: Color,
    val iconTint: Color,
    val actionDescription: String? = null,
)

// ── Composables ───────────────────────────────────────────────────────────────

/**
 * Lazy vertical grid of [QuickActionCard] cells.
 *
 * The grid height is driven by its content — wrap it in a [Column] with
 * `verticalScroll` disabled when embedding inside a scrollable parent to avoid
 * nested-scroll conflicts.  For a non-lazy fixed grid use [QuickActionFixedGrid].
 *
 * @param actions      Ordered list of [QuickAction] items to display.
 * @param onActionClick Callback receiving the [QuickAction.id] of the tapped item.
 * @param columns      Number of columns (default 4 for home; use 2 for tools).
 * @param modifier     Optional layout modifier for the grid container.
 */
@Composable
fun QuickActionGrid(
    actions: List<QuickAction>,
    onActionClick: (id: String) -> Unit,
    columns: Int = 4,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier,
        contentPadding = PaddingValues(0.dp),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
        userScrollEnabled = false, // parent scroll handles scrolling
    ) {
        items(actions, key = { it.id }) { action ->
            QuickActionCard(
                action = action,
                onClick = { onActionClick(action.id) },
            )
        }
    }
}

/**
 * Non-lazy fixed grid — use when the item count is small and stable (≤ 8 items).
 * Renders all items immediately without measuring a LazyGrid, which avoids
 * nested-scroll warnings inside a vertical scrollable parent.
 */
@Composable
fun QuickActionFixedGrid(
    actions: List<QuickAction>,
    onActionClick: (id: String) -> Unit,
    columns: Int = 4,
    modifier: Modifier = Modifier,
) {
    val rows = (actions.size + columns - 1) / columns
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
    ) {
        repeat(rows) { rowIndex ->
            androidx.compose.foundation.layout.Row(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                modifier = Modifier.fillMaxWidth(),
            ) {
                val startIndex = rowIndex * columns
                val endIndex = minOf(startIndex + columns, actions.size)
                for (i in startIndex until endIndex) {
                    QuickActionCard(
                        action = actions[i],
                        onClick = { onActionClick(actions[i].id) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // Fill remaining cells with invisible spacers to maintain grid alignment
                repeat(columns - (endIndex - startIndex)) {
                    Box(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

// ── Single card ───────────────────────────────────────────────────────────────

/**
 * Single quick-action card: tinted icon container + label.
 *
 * Provides press-scale feedback (0.96f) and full accessibility semantics.
 */
@Composable
fun QuickActionCard(
    action: QuickAction,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "quickActionScale_${action.id}",
    )

    val a11yDesc = action.actionDescription ?: action.label

    Surface(
        shape = MaterialTheme.shapes.large, // 16 dp — matches Stitch r16
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = modifier
            .scale(scale)
            .semantics {
                contentDescription = a11yDesc
                role = Role.Button
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null, // scale handles visual feedback
                onClick = onClick,
            ),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .aspectRatio(1f) // square cards
                .padding(vertical = MaterialTheme.spacing.sm),
        ) {
            // Tinted icon container (40 × 40, radius 14 dp — matches Stitch)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(40.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                    .background(action.iconBg),
            ) {
                Icon(
                    imageVector = action.icon,
                    contentDescription = null, // described at card level
                    tint = action.iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }

            Text(
                text = action.label,
                style = StitchType.quickActionLabel,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "QuickActionGrid — 4 columns (Home)")
@Composable
private fun QuickActionGrid4Preview() {
    AppTheme(dynamicColor = false) {
        QuickActionFixedGrid(
            actions = previewActions(),
            onActionClick = {},
            columns = 4,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "QuickActionGrid — 2 columns (Tools)")
@Composable
private fun QuickActionGrid2Preview() {
    AppTheme(dynamicColor = false) {
        QuickActionFixedGrid(
            actions = previewActions().take(4),
            onActionClick = {},
            columns = 2,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "QuickActionCard — single")
@Composable
private fun QuickActionCardPreview() {
    AppTheme(dynamicColor = false) {
        QuickActionCard(
            action = QuickAction(
                id = "chat",
                label = "Chat",
                icon = AppIcons.Destinations.ChatFilled,
                iconBg = StitchColors.qaChat,
                iconTint = StitchColors.qaIconChat,
                actionDescription = "Open AI chat",
            ),
            onClick = {},
            modifier = Modifier
                .padding(16.dp)
                .size(80.dp),
        )
    }
}

private fun previewActions() = listOf(
    QuickAction("chat",      "Chat",      AppIcons.Destinations.ChatFilled,    StitchColors.qaChat,      StitchColors.qaIconChat),
    QuickAction("voice",     "Voice",     AppIcons.Chat.Mic,                   StitchColors.qaVoice,     StitchColors.qaIconVoice),
    QuickAction("pdf",       "PDF",       AppIcons.Documents.Document,          StitchColors.qaPdf,       StitchColors.qaIconPdf),
    QuickAction("image",     "Image",     AppIcons.Chat.Camera,                 StitchColors.qaImage,     StitchColors.qaIconImage),
    QuickAction("code",      "Code",      AppIcons.Chat.Code,                   StitchColors.qaCode,      StitchColors.qaIconCode),
    QuickAction("translate", "Translate", AppIcons.Ai.Assistant,                StitchColors.qaTranslate, StitchColors.qaIconTranslate),
    QuickAction("notes",     "Notes",     AppIcons.Chat.Rename,                 StitchColors.qaNotes,     StitchColors.qaIconNotes),
    QuickAction("tools",     "Tools",     AppIcons.Settings.Appearance,         StitchColors.qaTools,     StitchColors.qaIconTools),
)
