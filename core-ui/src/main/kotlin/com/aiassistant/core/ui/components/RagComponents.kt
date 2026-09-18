/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/RagComponents.kt
 * Purpose    : RAG UI components (Phases 9.4–9.7).
 *
 *              Includes:
 *              - DocumentIndexStatus — enum for ingestion pipeline states
 *              - DocumentStatusBadge — status badge chip for documents
 *              - CitationCard        — full citation card with doc name + ref
 *              - SourceChip          — compact inline citation badge
 *              - SourcesBottomSheet  — expandable bottom sheet listing sources
 *              - DocumentReferenceCard — sidebar/inline reference card
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-rag and feature-on-device-rag.
 *
 * Requirements       : 17.1–17.5
 * ============================================================
 */
package com.aiassistant.core.ui.components

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.AppTypeExtended
import com.aiassistant.core.ui.spacing

// ── Document index status ─────────────────────────────────────────────────────

enum class DocumentIndexStatus {
    UPLOADING, PROCESSING, CHUNKING, EMBEDDING, INDEXED, FAILED
}

// ── DocumentStatusBadge ───────────────────────────────────────────────────────

@Composable
fun DocumentStatusBadge(
    status: DocumentIndexStatus,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val (bg, fg, label) = documentStatusConfig(status, isDark)

    Surface(
        modifier = modifier.semantics { contentDescription = "Status: $label" },
        color = bg,
        shape = AppShapes.pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            val icon = when (status) {
                DocumentIndexStatus.INDEXED -> AppIcons.Documents.Indexed
                DocumentIndexStatus.FAILED  -> AppIcons.Documents.Failed
                else                        -> AppIcons.Documents.Processing
            }
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = fg,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = fg
            )
        }
    }
}

private data class StatusConfig(val bg: Color, val fg: Color, val label: String)

private fun documentStatusConfig(status: DocumentIndexStatus, isDark: Boolean): StatusConfig =
    when (status) {
        DocumentIndexStatus.INDEXED    -> StatusConfig(
            bg = if (isDark) AppColors.gemmaContainerDark else AppColors.gemmaContainerLight,
            fg = if (isDark) AppColors.gemmaOnContainerDark else AppColors.gemmaOnContainerLight,
            label = "Indexed"
        )
        DocumentIndexStatus.FAILED     -> StatusConfig(
            bg = Color(if (isDark) 0xFF93000A else 0xFFFFDAD6),
            fg = Color(if (isDark) 0xFFFFDAD6 else 0xFF410002),
            label = "Failed"
        )
        DocumentIndexStatus.UPLOADING  -> StatusConfig(
            bg = if (isDark) AppColors.cloudContainerDark else AppColors.cloudContainerLight,
            fg = if (isDark) AppColors.cloudOnContainerDark else AppColors.cloudOnContainerLight,
            label = "Uploading"
        )
        DocumentIndexStatus.PROCESSING -> StatusConfig(
            bg = if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight,
            fg = if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight,
            label = "Processing"
        )
        DocumentIndexStatus.CHUNKING   -> StatusConfig(
            bg = if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight,
            fg = if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight,
            label = "Chunking"
        )
        DocumentIndexStatus.EMBEDDING  -> StatusConfig(
            bg = if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight,
            fg = if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight,
            label = "Embedding"
        )
    }

// ── CitationCard ──────────────────────────────────────────────────────────────

/**
 * Full citation card used inside [SourcesBottomSheet].
 *
 * @param documentName   Name of the source document.
 * @param pageOrChunk    Human-readable reference, e.g. "Page 12" or "Chunk 3".
 * @param relevanceLabel Optional relevance label, e.g. "High relevance".
 * @param onTap          Called when the card is tapped.
 */
@Composable
fun CitationCard(
    documentName: String,
    pageOrChunk: String? = null,
    relevanceLabel: String? = null,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onTap?.invoke() },
        enabled = onTap != null,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Source: $documentName. ${pageOrChunk ?: ""}" },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Documents.Citation,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = documentName,
                    style = AppTypeExtended.documentTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (pageOrChunk != null) {
                    Text(
                        text = pageOrChunk,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (relevanceLabel != null) {
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = AppShapes.pill
                ) {
                    Text(
                        text = relevanceLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

// ── SourceChip ────────────────────────────────────────────────────────────────

/**
 * Compact inline citation badge — tappable to open [SourcesBottomSheet].
 *
 * @param sourceCount Number of source documents.
 * @param onTap       Called when the chip is tapped.
 */
@Composable
fun SourceChip(
    sourceCount: Int,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    Surface(
        onClick = onTap,
        modifier = modifier.semantics {
            contentDescription = "View $sourceCount sources"
        },
        color = if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight,
        shape = AppShapes.pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = AppIcons.Ai.Rag,
                contentDescription = null,
                tint = if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = if (sourceCount == 1) "1 source" else "$sourceCount sources",
                style = MaterialTheme.typography.labelSmall,
                color = if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight
            )
        }
    }
}

// ── SourcesBottomSheet ────────────────────────────────────────────────────────

/**
 * Bottom sheet listing all RAG sources for a given assistant response.
 *
 * @param sources      List of (documentName, pageOrChunk) pairs.
 * @param onDismiss    Called when the sheet is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesBottomSheet(
    sources: List<Pair<String, String?>>,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = AppShapes.sheet,
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = MaterialTheme.spacing.md,
                    end = MaterialTheme.spacing.md,
                    bottom = MaterialTheme.spacing.xl
                )
        ) {
            Text(
                text = "Sources (${sources.size})",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.sm)
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                items(sources) { (name, ref) ->
                    CitationCard(
                        documentName = name,
                        pageOrChunk = ref
                    )
                }
            }
        }
    }
}

// ── DocumentReferenceCard ─────────────────────────────────────────────────────

/**
 * Compact reference card for sidebar or inline use in DocumentChatScreen.
 *
 * @param documentName Document name.
 * @param fileSizeLabel Formatted file size, e.g. "2.4 MB".
 * @param status        Current indexing status.
 * @param onTap         Called when the card is tapped.
 */
@Composable
fun DocumentReferenceCard(
    documentName: String,
    fileSizeLabel: String? = null,
    status: DocumentIndexStatus = DocumentIndexStatus.INDEXED,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = { onTap?.invoke() },
        enabled = onTap != null,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$documentName — ${status.name.lowercase()}" },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Documents.Document,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = documentName,
                    style = AppTypeExtended.documentTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (fileSizeLabel != null) {
                    Text(
                        text = fileSizeLabel,
                        style = AppTypeExtended.cacheTimestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Show progress bar while processing
                if (status != DocumentIndexStatus.INDEXED && status != DocumentIndexStatus.FAILED) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
            DocumentStatusBadge(status = status)
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun DocumentReferenceCardPreview() {
    AppTheme(dynamicColor = false) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DocumentReferenceCard(
                documentName = "Android Architecture Guide.pdf",
                fileSizeLabel = "2.4 MB",
                status = DocumentIndexStatus.INDEXED
            )
            DocumentReferenceCard(
                documentName = "API Specification.md",
                fileSizeLabel = "84 KB",
                status = DocumentIndexStatus.EMBEDDING
            )
            DocumentReferenceCard(
                documentName = "Meeting Notes.docx",
                fileSizeLabel = "120 KB",
                status = DocumentIndexStatus.FAILED
            )
        }
    }
}
