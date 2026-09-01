/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentListScreen.kt
 * Purpose    : Redesigned RAG document list (Task 50.5) with StorageSummaryCard,
 *              per-document SwipeRevealLayout + AnimatedContent status badges,
 *              M3 LinearProgressIndicator storage meter, and surfaceTonal1 cards.
 *
 * Architecture Layer : Feature (feature-rag) — Compose UI layer.
 *                      State driven by RAGViewModel.
 *
 * Dependencies       : core-ui (AppColors, SwipeRevealLayout, OfflineBanner,
 *                      ErrorBanner, LoadingIndicator, AppType, spacing, elevation),
 *                      domain (Document, IngestionStatus).
 *
 * Requirements       : 4.1, 27.2, 27.5
 * ============================================================
 */
package com.aiassistant.feature.rag

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Document

// ── Max storage limit shown in the progress meter (50 MB × 20 docs) ──────────
private const val STORAGE_SOFT_LIMIT_BYTES = 50L * 1024 * 1024 * 20

// ── Screen entry point ────────────────────────────────────────────────────────

@Composable
fun DocumentListScreen(viewModel: RAGViewModel, onDocumentClick: (String) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val pagedDocuments = viewModel.documents.collectAsLazyPagingItems()

    DocumentListScreenContent(
        uiState = uiState,
        isOffline = isOffline,
        pagedDocuments = pagedDocuments,
        onDocumentClick = onDocumentClick,
        onDeleteDocument = viewModel::deleteDocument,
        onUploadDocument = viewModel::uploadDocument,
        onClearUploadError = viewModel::clearUploadError
    )
}

// ── Stateless screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocumentListScreenContent(
    uiState: RAGUiState,
    isOffline: Boolean,
    pagedDocuments: LazyPagingItems<Document>,
    onDocumentClick: (String) -> Unit,
    onDeleteDocument: (String) -> Unit,
    onUploadDocument: (uri: String, fileName: String, mimeType: String, sizeBytes: Long) -> Unit,
    onClearUploadError: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    var showFilePickerSheet by rememberSaveable { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(uiState) {
        when (uiState) {
            is RAGUiState.UploadSuccess ->
                snackbarHostState.showSnackbar("\"${uiState.document.fileName}\" uploaded — ingestion in progress.")
            is RAGUiState.UploadError -> {
                if (!showFilePickerSheet) {
                    snackbarHostState.showSnackbar(uiState.message)
                    onClearUploadError()
                }
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Documents", style = MaterialTheme.typography.titleLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showFilePickerSheet = true },
                modifier = Modifier.semantics { contentDescription = "Upload a new document" }
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Persistent offline banner ─────────────────────────────────
            if (isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }

            // ── Storage summary card (Task 50.5) ──────────────────────────
            StorageSummaryCard(
                pagedDocuments = pagedDocuments,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.xs
                    )
            )

            // ── Body ──────────────────────────────────────────────────────
            when (uiState) {
                is RAGUiState.Loading -> LoadingContent()
                is RAGUiState.Error -> ErrorContent(
                    message = uiState.message,
                    onRetry = null
                )
                else -> {
                    PagedDocumentList(
                        pagedDocuments = pagedDocuments,
                        onDocumentClick = onDocumentClick,
                        onDeleteRequest = { pendingDeleteId = it }
                    )
                }
            }
        }
    }

    if (showFilePickerSheet) {
        FilePickerBottomSheet(
            uiState = uiState,
            onUpload = onUploadDocument,
            onDismiss = {
                showFilePickerSheet = false
                if (uiState is RAGUiState.UploadError) onClearUploadError()
            },
            sheetState = sheetState
        )
    }

    if (pendingDeleteId != null) {
        DeleteDocumentDialog(
            onConfirm = {
                onDeleteDocument(pendingDeleteId!!)
                pendingDeleteId = null
            },
            onDismiss = { pendingDeleteId = null }
        )
    }
}

// ── Storage summary card ──────────────────────────────────────────────────────

/**
 * Card showing total document count and a [LinearProgressIndicator] storage meter.
 *
 * The meter fills relative to [STORAGE_SOFT_LIMIT_BYTES] (20 × 50 MB = 1 GB).
 * Once the total exceeds 80% of the limit the indicator turns amber; above 100% it
 * turns red.  These thresholds are visual only — the backend enforces per-file limits.
 */
@Composable
private fun StorageSummaryCard(pagedDocuments: LazyPagingItems<Document>, modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    // Compute totals from the current loaded items
    val totalDocs = pagedDocuments.itemCount
    val totalBytes = (0 until totalDocs).sumOf { idx ->
        pagedDocuments.peek(idx)?.sizeBytes ?: 0L
    }
    val fraction = (totalBytes.toFloat() / STORAGE_SOFT_LIMIT_BYTES).coerceIn(0f, 1f)

    val barColor = when {
        fraction > 1f -> if (isDark) AppColors.ragRedDark else AppColors.ragRedLight
        fraction > 0.8f -> if (isDark) AppColors.ragAmberDark else AppColors.ragAmberLight
        else -> MaterialTheme.colorScheme.primary
    }

    ElevatedCard(
        modifier = modifier.semantics {
            contentDescription = "$totalDocs documents, ${totalBytes.formatFileSize()} used"
        },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "$totalDocs document${if (totalDocs != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "${totalBytes.formatFileSize()} used",
                    style = AppType.sectionLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription =
                            "${(fraction * 100).toInt()}% of recommended storage used"
                    },
                color = barColor
            )
        }
    }
}

// ── Paged list ────────────────────────────────────────────────────────────────

@Composable
private fun PagedDocumentList(
    pagedDocuments: LazyPagingItems<Document>,
    onDocumentClick: (String) -> Unit,
    onDeleteRequest: (String) -> Unit
) {
    when {
        pagedDocuments.loadState.refresh is LoadState.Loading &&
            pagedDocuments.itemCount == 0 -> LoadingContent()
        pagedDocuments.loadState.refresh is LoadState.Error -> {
            val error = pagedDocuments.loadState.refresh as LoadState.Error
            ErrorContent(
                message = error.error.localizedMessage ?: "Failed to load documents.",
                onRetry = { pagedDocuments.retry() }
            )
        }
        pagedDocuments.itemCount == 0 &&
            pagedDocuments.loadState.refresh is LoadState.NotLoading -> EmptyContent()
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                items(
                    count = pagedDocuments.itemCount,
                    key = { idx -> pagedDocuments.peek(idx)?.id ?: "null:$idx" }
                ) { idx ->
                    pagedDocuments[idx]?.let { document ->
                        DocumentItem(
                            document = document,
                            onDocumentClick = onDocumentClick,
                            onDeleteClick = onDeleteRequest
                        )
                    }
                }

                if (pagedDocuments.loadState.append is LoadState.Loading) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(MaterialTheme.spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
                if (pagedDocuments.loadState.append is LoadState.Error) {
                    item {
                        val error = pagedDocuments.loadState.append as LoadState.Error
                        ErrorBanner(
                            message = error.error.localizedMessage ?: "Failed to load more.",
                            onRetry = { pagedDocuments.retry() },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = MaterialTheme.spacing.md)
                        )
                    }
                }
            }
        }
    }
}

// ── Content placeholders ──────────────────────────────────────────────────────

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        LoadingIndicator(
            style = LoadingIndicatorStyle.CIRCULAR,
            contentDescription = "Loading documents"
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: (() -> Unit)?) {
    Box(
        Modifier.fillMaxSize().padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        ErrorBanner(message = message, onRetry = onRetry, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EmptyContent() {
    Box(
        Modifier.fillMaxSize().padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            Icon(
                imageVector = Icons.Filled.FolderOff,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            Text(
                "No documents yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "Tap + to upload a PDF, DOCX, TXT, or Markdown file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Delete dialog ─────────────────────────────────────────────────────────────

@Composable
private fun DeleteDocumentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Document") },
        text = {
            Text(
                "Delete this document? All associated data will be permanently removed from the RAG index.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete document" }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel delete document" }
            ) {
                Text("Cancel")
            }
        }
    )
}
