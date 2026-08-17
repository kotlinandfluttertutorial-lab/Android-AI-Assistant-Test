/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentItem.kt
 * Purpose    : DocumentItem — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
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
 * Module     : feature-rag
 * File       : DocumentItem.kt
 * Purpose    : DocumentItem — feature-rag module component
 *
 * Architecture Layer : Feature (feature-rag)
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
 * DocumentItem.kt
 *
 * Purpose: Composable rendering a single row in the Document List, showing the file name,
 *          size, creation date, and an ingestion-status badge with per-status color.
 * Architecture: feature-rag â€” Compose UI layer.
 * Dependencies: Compose Material 3, core-ui (AppTheme, spacing tokens), domain (Document,
 *               IngestionStatus)
 *
 * Design decisions:
 * - Status badge uses both color AND text/icon so the state is never conveyed by color
 *   alone, satisfying the no-color-only indicator rule (requirement 23.4).
 * - Each [IngestionStatus] maps to a distinct M3 color token pair (container +
 *   onContainer) drawn from the palette, ensuring contrast compliance.
 * - The file size is formatted as a human-readable string (e.g. "1.2 MB") to match the
 *   expectations of non-technical users.
 * - A trailing delete icon button is provided so users can remove documents in-line.
 *
 * Requirements: 4.1, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A card row displaying a single [Document]'s metadata and ingestion status badge.
 *
 * @param document       The domain model to display.
 * @param onDocumentClick Called when the user taps the row (navigates to DocumentChat).
 * @param onDeleteClick  Called when the user taps the delete icon.
 * @param modifier       Optional [Modifier] applied to the root card.
 */
@Composable
fun DocumentItem(
    document: Document,
    onDocumentClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val a11yLabel = buildString {
        append(document.fileName)
        append(", ")
        append(document.sizeBytes.formatFileSize())
        append(", ")
        append(document.ingestionStatus.displayLabel())
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = { onDocumentClick(document.id) })
            .semantics(mergeDescendants = true) {
                contentDescription = a11yLabel
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
            // File icon
            Icon(
                imageVector = Icons.Filled.Article,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )

            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))

            // File metadata
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
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
                        text = "Â·",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = document.createdAt.formatDate(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                // Ingestion status badge
                IngestionStatusBadge(status = document.ingestionStatus)
            }

            // Delete action
            IconButton(
                onClick = { onDeleteClick(document.id) },
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${document.fileName}"
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

// â”€â”€â”€ Status badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A colored chip showing the current [IngestionStatus] with both color and icon/text,
 * so the state is never conveyed by color alone.
 *
 * Status â†’ color mapping:
 * - PENDING    â†’ surfaceVariant (neutral)
 * - PROCESSING â†’ secondaryContainer (informational)
 * - READY      â†’ tertiaryContainer (success-like)
 * - FAILED     â†’ errorContainer (error)
 */
@Composable
fun IngestionStatusBadge(status: IngestionStatus, modifier: Modifier = Modifier) {
    val (containerColor, contentColor, icon, label) = when (status) {
        IngestionStatus.PENDING -> StatusChipConfig(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            icon = Icons.Filled.HourglassEmpty,
            label = "Pending"
        )
        IngestionStatus.PROCESSING -> StatusChipConfig(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            icon = Icons.Filled.Sync,
            label = "Processing"
        )
        IngestionStatus.READY -> StatusChipConfig(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            icon = Icons.Filled.CheckCircle,
            label = "Ready"
        )
        IngestionStatus.FAILED -> StatusChipConfig(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            icon = Icons.Filled.Error,
            label = "Failed"
        )
    }

    AssistChip(
        onClick = { /* non-interactive */ },
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(AssistChipDefaults.IconSize)
            )
        },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = containerColor,
            labelColor = contentColor,
            leadingIconContentColor = contentColor
        ),
        border = AssistChipDefaults.assistChipBorder(enabled = false),
        modifier = modifier.semantics {
            contentDescription = "Status: $label"
        }
    )
}

/** Data holder for [IngestionStatusBadge] visual parameters. */
private data class StatusChipConfig(
    val containerColor: Color,
    val contentColor: Color,
    val icon: ImageVector,
    val label: String
)

// â”€â”€â”€ Display helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Returns a human-readable display label for this status. */
private fun IngestionStatus.displayLabel(): String = when (this) {
    IngestionStatus.PENDING -> "Pending"
    IngestionStatus.PROCESSING -> "Processing"
    IngestionStatus.READY -> "Ready"
    IngestionStatus.FAILED -> "Failed"
}

/** Formats [Long] bytes as a human-readable string (e.g. "1.2 MB"). */
internal fun Long.formatFileSize(): String = when {
    this < 1_024L -> "$this B"
    this < 1_024L * 1_024L -> String.format(java.util.Locale.getDefault(), "%.1f KB", this / 1_024.0)
    this < 1_024L * 1_024L * 1_024L -> String.format(java.util.Locale.getDefault(), "%.1f MB", this / (1_024.0 * 1_024.0))
    else -> String.format(java.util.Locale.getDefault(), "%.1f GB", this / (1_024.0 * 1_024.0 * 1_024.0))
}

/** Formats an epoch-milliseconds timestamp as "MMM d, yyyy". */
private fun Long.formatDate(): String {
    val formatter = DateTimeFormatter
        .ofPattern("MMM d, yyyy")
        .withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(this))
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "DocumentItem â€“ Pending")
@Composable
private fun DocumentItemPendingPreview() {
    AppTheme(dynamicColor = false) {
        DocumentItem(
            document = Document(
                id = "1",
                userId = "user1",
                fileName = "quarterly_report.pdf",
                mimeType = "application/pdf",
                sizeBytes = 2_456_789L,
                ingestionStatus = IngestionStatus.PENDING,
                jobId = "job-123",
                createdAt = System.currentTimeMillis()
            ),
            onDocumentClick = {},
            onDeleteClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, name = "DocumentItem â€“ Processing")
@Composable
private fun DocumentItemProcessingPreview() {
    AppTheme(dynamicColor = false) {
        DocumentItem(
            document = Document(
                id = "2",
                userId = "user1",
                fileName = "company_handbook.docx",
                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                sizeBytes = 890_123L,
                ingestionStatus = IngestionStatus.PROCESSING,
                jobId = "job-456",
                createdAt = System.currentTimeMillis()
            ),
            onDocumentClick = {},
            onDeleteClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, name = "DocumentItem â€“ Ready")
@Composable
private fun DocumentItemReadyPreview() {
    AppTheme(dynamicColor = false) {
        DocumentItem(
            document = Document(
                id = "3",
                userId = "user1",
                fileName = "meeting_notes.txt",
                mimeType = "text/plain",
                sizeBytes = 12_345L,
                ingestionStatus = IngestionStatus.READY,
                pageCount = 3,
                createdAt = System.currentTimeMillis()
            ),
            onDocumentClick = {},
            onDeleteClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, name = "DocumentItem â€“ Failed")
@Composable
private fun DocumentItemFailedPreview() {
    AppTheme(dynamicColor = false) {
        DocumentItem(
            document = Document(
                id = "4",
                userId = "user1",
                fileName = "corrupted_file.pdf",
                mimeType = "application/pdf",
                sizeBytes = 50_123_456L,
                ingestionStatus = IngestionStatus.FAILED,
                createdAt = System.currentTimeMillis()
            ),
            onDocumentClick = {},
            onDeleteClick = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
