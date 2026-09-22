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

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppTheme
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
 *
 * When [isDeleting] is `true` the swipe action is disabled and a
 * [CircularProgressIndicator] overlays the card to indicate the pending deletion.
 */
@Composable
fun DocumentItem(
    document: Document,
    onDocumentClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    isDeleting: Boolean = false
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    val a11yLabel = buildString {
        append(document.fileName)
        append(", ")
        append(document.sizeBytes.formatFileSize())
        append(", ")
        append(document.ingestionStatus.displayLabel())
        if (isDeleting) append(", deleting")
    }

    // The card content is extracted so it can be used both inside and outside
    // SwipeRevealLayout without duplication.
    val cardContent: @Composable () -> Unit = {
        Box {
            ElevatedCard(
                onClick = { if (!isDeleting) onDocumentClick(document.id) },
                enabled = !isDeleting,
                modifier = Modifier
                    .fillMaxWidth()
                    .pressScale()
                    .semantics(mergeDescendants = true) { this.contentDescription = a11yLabel },
                elevation = CardDefaults.elevatedCardElevation(
                    defaultElevation = MaterialTheme.elevation.low
                ),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = cardColor)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = MaterialTheme.spacing.md,
                            vertical = MaterialTheme.spacing.sm
                        ),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Article,
                        contentDescription = null,
                        tint = if (isDeleting)
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        else
                            MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )

                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Text(
                            text = document.fileName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (isDeleting)
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            else
                                MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = document.sizeBytes.formatFileSize(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "·",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = document.createdAt.formatDateTime(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.height(MaterialTheme.spacing.xs))

                        AnimatedContent(
                            targetState = document.ingestionStatus,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "statusBadge_${document.id}"
                        ) { status ->
                            IngestionStatusBadge(status = status)
                        }
                    }
                }
            }

            // Deletion in-progress spinner — overlays the card trailing edge.
            AnimatedVisibility(
                visible = isDeleting,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier.matchParentSize(),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = MaterialTheme.spacing.md)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }

    if (isDeleting) {
        // Skip SwipeRevealLayout while deleting — no swipe gesture, no delete button.
        Box(modifier = modifier.fillMaxWidth()) { cardContent() }
    } else {
        SwipeRevealLayout(
            modifier = modifier.fillMaxWidth(),
            revealWidth = 72.dp,
            actions = {
                IconButton(
                    onClick = { onDeleteClick(document.id) },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete ${document.fileName}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        ) {
            cardContent()
        }
    }
}

// ── Status badge ──────────────────────────────────────────────────────────────

private data class StatusChipConfig(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val label: String
)

@Composable
fun IngestionStatusBadge(status: IngestionStatus, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val config = getStatusChipConfig(status, isDark)

    AssistChip(
        onClick = { },
        label = {
            Text(
                text = config.label,
                style = MaterialTheme.typography.labelSmall,
                color = config.contentColor
            )
        },
        leadingIcon = {
            Icon(
                imageVector = config.icon,
                contentDescription = null,
                tint = config.contentColor,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = config.containerColor,
            labelColor = config.contentColor,
            leadingIconContentColor = config.contentColor
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = false),
        modifier = modifier.semantics { contentDescription = "Status: ${config.label}" }
    )
}

@Composable
private fun getStatusChipConfig(status: IngestionStatus, isDark: Boolean): StatusChipConfig {
    return when (status) {
        IngestionStatus.PENDING -> getPendingConfig(isDark)
        IngestionStatus.PROCESSING -> getProcessingConfig(isDark)
        IngestionStatus.READY -> getReadyConfig(isDark)
        IngestionStatus.FAILED -> getFailedConfig(isDark)
    }
}

@Composable
private fun getPendingConfig(isDark: Boolean) = StatusChipConfig(
    containerColor = if (isDark) AppColors.surfaceTonal2Dark else AppColors.surfaceTonal2Light,
    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    icon = Icons.Filled.HourglassEmpty,
    label = "Pending"
)

@Composable
private fun getProcessingConfig(isDark: Boolean) = StatusChipConfig(
    containerColor = if (isDark) {
        AppColors.ragAmberDark.copy(alpha = 0.25f)
    } else {
        AppColors.ragAmberLight.copy(alpha = 0.18f)
    },
    contentColor = if (isDark) AppColors.ragAmberDark else AppColors.ragAmberLight,
    icon = Icons.Filled.Sync,
    label = "Processing"
)

@Composable
private fun getReadyConfig(isDark: Boolean) = StatusChipConfig(
    containerColor = if (isDark) {
        AppColors.ragGreenDark.copy(alpha = 0.22f)
    } else {
        AppColors.ragGreenLight.copy(alpha = 0.15f)
    },
    contentColor = if (isDark) AppColors.ragGreenDark else AppColors.ragGreenLight,
    icon = Icons.Filled.CheckCircle,
    label = "Ready"
)

@Composable
private fun getFailedConfig(isDark: Boolean) = StatusChipConfig(
    containerColor = if (isDark) {
        AppColors.ragRedDark.copy(alpha = 0.22f)
    } else {
        AppColors.ragRedLight.copy(alpha = 0.15f)
    },
    contentColor = if (isDark) AppColors.ragRedDark else AppColors.ragRedLight,
    icon = Icons.Filled.Error,
    label = "Failed"
)

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

private fun Long.formatDateTime(): String = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a")
    .withZone(ZoneId.systemDefault())
    .format(Instant.ofEpochMilli(this))

@Preview(showBackground = true, name = "Light Mode")
@Preview(showBackground = true, name = "Dark Mode", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DocumentItemPreview() {
    val sampleDocument = Document(
        id = "doc1",
        userId = "user123",
        fileName = "Annual_Report_2023.pdf",
        mimeType = "application/pdf",
        sizeBytes = 1024L * 1024L * 3 + 512 * 1024, // 3.5 MB
        ingestionStatus = IngestionStatus.READY,
        createdAt = System.currentTimeMillis() - (1000 * 60 * 60 * 24) // 1 day ago
    )

    AppTheme(dynamicColor = false) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DocumentItem(
                document = sampleDocument,
                onDocumentClick = {},
                onDeleteClick = {}
            )
            DocumentItem(
                document = sampleDocument.copy(
                    id = "doc2",
                    fileName = "Knowledge_Base.docx",
                    ingestionStatus = IngestionStatus.PROCESSING
                ),
                onDocumentClick = {},
                onDeleteClick = {}
            )
            DocumentItem(
                document = sampleDocument.copy(
                    id = "doc3",
                    fileName = "Budget_Draft.pdf",
                    ingestionStatus = IngestionStatus.FAILED
                ),
                onDocumentClick = {},
                onDeleteClick = {}
            )
            DocumentItem(
                document = sampleDocument.copy(
                    id = "doc4",
                    fileName = "System_Logs.txt",
                    ingestionStatus = IngestionStatus.PENDING
                ),
                onDocumentClick = {},
                onDeleteClick = {}
            )
            DocumentItem(
                document = sampleDocument,
                onDocumentClick = {},
                onDeleteClick = {},
                isDeleting = true
            )
        }
    }
}
