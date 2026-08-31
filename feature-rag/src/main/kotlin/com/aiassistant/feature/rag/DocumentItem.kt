/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentItem.kt
 * Purpose    : Redesigned document row (Task 50.5) with surfaceTonal1 card
 *              background, SwipeRevealLayout delete action, and AnimatedContent
 *              ingestion-status badge transitions.
 *
 * Architecture Layer : Feature (feature-rag) — Compose UI layer.
 *
 * Dependencies       : core-ui (AppColors, SwipeRevealLayout, spacing, elevation),
 *                      domain (Document, IngestionStatus).
 *
 * Requirements       : 4.1, 23.4, 27.2, 27.5
 * ============================================================
 */
package com.aiassistant.feature.rag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.components.SwipeRevealLayout
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.pressScale
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Redesigned document row with surfaceTonal1 card, SwipeRevealLayout delete,
 * and AnimatedContent status badge.
 */
@Composable
fun DocumentItem(
    document: Document,
    onDocumentClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    val a11yLabel = buildString {
        append(document.fileName)
        append(", ")
        append(document.sizeBytes.formatFileSize())
        append(", ")
        append(document.ingestionStatus.displayLabel())
    }

    SwipeRevealLayout(
        modifier = modifier.fillMaxWidth(),
        revealWidth = 72.dp,
        actions = {
            IconButton(
                onClick = { onDeleteClick(document.id) },
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${document.fileName}"
                },
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        ElevatedCard(
            onClick = { onDocumentClick(document.id) },
            modifier = Modifier
                .fillMaxWidth()
                .pressScale()
                .semantics(mergeDescendants = true) { this.contentDescription = a11yLabel },
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = MaterialTheme.elevation.low,
            ),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // File icon
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Article,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp),
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))

                // Metadata
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    Text(
                        text = document.fileName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = document.sizeBytes.formatFileSize(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = document.createdAt.formatDate(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))

                    // AnimatedContent status badge — crossfades between states
                    AnimatedContent(
                        targetState = document.ingestionStatus,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "statusBadge_${document.id}",
                    ) { status ->
                        IngestionStatusBadge(status = status)
                    }
                }
            }
        }
    }
}

// ── Status badge ──────────────────────────────────────────────────────────────

private data class StatusChipConfig(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val label: String,
)

@Composable
fun IngestionStatusBadge(status: IngestionStatus, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()

    val config = when (status) {
        IngestionStatus.PENDING -> StatusChipConfig(
            containerColor = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Filled.HourglassEmpty,
            label = "Pending",
        )
        IngestionStatus.PROCESSING -> StatusChipConfig(
            containerColor = if (isDark) AppColors.ragAmberDark.copy(alpha = 0.25f)
                             else AppColors.ragAmberLight.copy(alpha = 0.18f),
            contentColor = if (isDark) AppColors.ragAmberDark else AppColors.ragAmberLight,
            icon = Icons.Filled.Sync,
            label = "Processing",
        )
        IngestionStatus.READY -> StatusChipConfig(
            containerColor = if (isDark) AppColors.ragGreenDark.copy(alpha = 0.22f)
                             else AppColors.ragGreenLight.copy(alpha = 0.15f),
            contentColor = if (isDark) AppColors.ragGreenDark else AppColors.ragGreenLight,
            icon = Icons.Filled.CheckCircle,
            label = "Ready",
        )
        IngestionStatus.FAILED -> StatusChipConfig(
            containerColor = if (isDark) AppColors.ragRedDark.copy(alpha = 0.22f)
                             else AppColors.ragRedLight.copy(alpha = 0.15f),
            contentColor = if (isDark) AppColors.ragRedDark else AppColors.ragRedLight,
            icon = Icons.Filled.Error,
            label = "Failed",
        )
    }

    AssistChip(
        onClick = { /* non-interactive */ },
        label = {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelSmall,
                color = config.contentColor,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = config.icon,
                contentDescription = null,
                tint = config.contentColor,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = config.containerColor,
            labelColor = config.contentColor,
            leadingIconContentColor = config.contentColor,
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = false),
        modifier = modifier.semantics { contentDescription = "Status: ${config.label}" },
    )
}

// ── Display helpers ───────────────────────────────────────────────────────────

private fun IngestionStatus.displayLabel(): String = when (this) {
    IngestionStatus.PENDING -> "Pending"
    IngestionStatus.PROCESSING -> "Processing"
    IngestionStatus.READY -> "Ready"
    IngestionStatus.FAILED -> "Failed"
}

internal fun Long.formatFileSize(): String = when {
    this < 1_024L -> "$this B"
    this < 1_024L * 1_024L ->
        "%.1f KB".format(this / 1_024.0)
    this < 1_024L * 1_024L * 1_024L ->
        "%.1f MB".format(this / (1_024.0 * 1_024.0))
    else ->
        "%.1f GB".format(this / (1_024.0 * 1_024.0 * 1_024.0))
}

private fun Long.formatDate(): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(this))
