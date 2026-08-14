/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-rag
 * File       : DocumentListScreen.kt
 * Purpose    : Compose UI screen for the DocumentList feature
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Jetpack Compose Screen
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
 * File       : DocumentListScreen.kt
 * Purpose    : Compose UI screen for the DocumentList feature
 *
 * Architecture Layer : Feature (feature-rag)
 * Pattern Used       : Jetpack Compose Screen
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
 * DocumentListScreen.kt
 *
 * Purpose: Jetpack Compose screen displaying the paginated list of RAG documents with
 *          per-document ingestion status badges, an FAB to open the file picker sheet,
 *          and an offline banner.
 * Architecture: feature-rag â€” Compose UI layer; state driven by [RAGViewModel].
 * Dependencies: Compose Material 3, Paging 3 Compose, Hilt Navigation Compose,
 *               core-ui (AppTheme, OfflineBanner, ErrorBanner, LoadingIndicator)
 *
 * Requirements: 4.1, 27.2, 27.5
 */
package com.aiassistant.feature.rag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Document
import com.aiassistant.domain.model.IngestionStatus
import kotlinx.coroutines.flow.flowOf

// â”€â”€â”€ Screen entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateful entry point for the Document List screen. Collects state from [RAGViewModel]
 * and delegates rendering to the stateless overload.
 *
 * @param viewModel           The Hilt-provided [RAGViewModel].
 * @param onDocumentClick     Callback invoked when the user taps a document row (navigates
 *                            to DocumentChat).
 */
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

// â”€â”€â”€ Stateless screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless Document List screen. All state is passed in; side-effects are communicated
 * via callbacks.
 */
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

    // Show a snackbar when upload completes or errors.
    LaunchedEffect(uiState) {
        when (uiState) {
            is RAGUiState.UploadSuccess -> {
                snackbarHostState.showSnackbar(
                    "\"${uiState.document.fileName}\" uploaded â€” ingestion in progress."
                )
            }
            is RAGUiState.UploadError -> {
                // Inline error is also shown in the sheet. This snackbar handles the case
                // where the sheet has already been dismissed.
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
                title = {
                    Text(
                        text = "Documents",
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showFilePickerSheet = true },
                modifier = Modifier.semantics {
                    contentDescription = "Upload a new document"
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null
                )
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Persistent offline banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }

            // â”€â”€ Body â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            when (uiState) {
                is RAGUiState.Loading -> LoadingContent()
                is RAGUiState.Error -> ErrorContent(
                    message = uiState.message,
                    onRetry = null // Refresh handled by Paging 3
                )
                else -> {
                    // DocumentList, UploadInProgress, UploadSuccess, UploadError â€”
                    // all show the paged document list as the background.
                    PagedDocumentList(
                        pagedDocuments = pagedDocuments,
                        onDocumentClick = onDocumentClick,
                        onDeleteRequest = { documentId ->
                            pendingDeleteId = documentId
                        }
                    )
                }
            }
        }
    }

    // â”€â”€ File picker bottom sheet â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showFilePickerSheet) {
        FilePickerBottomSheet(
            uiState = uiState,
            onUpload = { uri, fileName, mimeType, sizeBytes ->
                onUploadDocument(uri, fileName, mimeType, sizeBytes)
            },
            onDismiss = {
                showFilePickerSheet = false
                // If there was an upload error in the sheet, clear it when the sheet closes.
                if (uiState is RAGUiState.UploadError) onClearUploadError()
            },
            sheetState = sheetState
        )
    }

    // â”€â”€ Delete confirmation dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
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

// â”€â”€â”€ List components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Paged [LazyColumn] of [DocumentItem] composables backed by [LazyPagingItems].
 */
@Composable
private fun PagedDocumentList(
    pagedDocuments: LazyPagingItems<Document>,
    onDocumentClick: (String) -> Unit,
    onDeleteRequest: (String) -> Unit
) {
    when {
        pagedDocuments.loadState.refresh is LoadState.Loading &&
            pagedDocuments.itemCount == 0 -> {
            LoadingContent()
        }
        pagedDocuments.loadState.refresh is LoadState.Error -> {
            val error = pagedDocuments.loadState.refresh as LoadState.Error
            ErrorContent(
                message = error.error.localizedMessage ?: "Failed to load documents.",
                onRetry = { pagedDocuments.retry() }
            )
        }
        pagedDocuments.itemCount == 0 &&
            pagedDocuments.loadState.refresh is LoadState.NotLoading -> {
            EmptyContent()
        }
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
                    key = { index -> pagedDocuments.peek(index)?.id ?: "null:$index" }
                ) { index ->
                    pagedDocuments[index]?.let { document ->
                        DocumentItem(
                            document = document,
                            onDocumentClick = onDocumentClick,
                            onDeleteClick = onDeleteRequest
                        )
                    }
                }

                // Append loading indicator while fetching the next page.
                if (pagedDocuments.loadState.append is LoadState.Loading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.md),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }

                // Append error row for next-page failures.
                if (pagedDocuments.loadState.append is LoadState.Error) {
                    item {
                        val error = pagedDocuments.loadState.append as LoadState.Error
                        ErrorBanner(
                            message = error.error.localizedMessage ?: "Failed to load more documents.",
                            onRetry = { pagedDocuments.retry() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MaterialTheme.spacing.md)
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Content state placeholders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        LoadingIndicator(
            style = LoadingIndicatorStyle.CIRCULAR,
            contentDescription = "Loading documents"
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        ErrorBanner(
            message = message,
            onRetry = onRetry,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EmptyContent() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
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
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = "No documents yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Tap + to upload a PDF, DOCX, TXT, or Markdown file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// â”€â”€â”€ Dialogs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun DeleteDocumentDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Document") },
        text = {
            Text(
                text = "Delete this document? All associated data will be permanently removed from the RAG index.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete document" }
            ) {
                Text(
                    text = "Delete",
                    color = MaterialTheme.colorScheme.error
                )
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

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private val previewDocuments = listOf(
    Document(
        id = "1",
        userId = "u1",
        fileName = "annual_report.pdf",
        mimeType = "application/pdf",
        sizeBytes = 4_567_890L,
        ingestionStatus = IngestionStatus.READY,
        pageCount = 24,
        createdAt = System.currentTimeMillis() - 86_400_000L
    ),
    Document(
        id = "2",
        userId = "u1",
        fileName = "product_spec.docx",
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        sizeBytes = 987_654L,
        ingestionStatus = IngestionStatus.PROCESSING,
        jobId = "job-abc",
        createdAt = System.currentTimeMillis() - 3_600_000L
    ),
    Document(
        id = "3",
        userId = "u1",
        fileName = "notes.md",
        mimeType = "text/markdown",
        sizeBytes = 23_456L,
        ingestionStatus = IngestionStatus.PENDING,
        jobId = "job-xyz",
        createdAt = System.currentTimeMillis()
    ),
    Document(
        id = "4",
        userId = "u1",
        fileName = "broken.pdf",
        mimeType = "application/pdf",
        sizeBytes = 1_234L,
        ingestionStatus = IngestionStatus.FAILED,
        createdAt = System.currentTimeMillis() - 172_800_000L
    )
)

@Preview(showBackground = true, name = "DocumentList â€“ Success")
@Composable
private fun DocumentListSuccessPreview() {
    AppTheme(dynamicColor = false) {
        DocumentListScreenContent(
            uiState = RAGUiState.DocumentList(isOffline = false),
            isOffline = false,
            pagedDocuments = flowOf(PagingData.from(previewDocuments)).collectAsLazyPagingItems(),
            onDocumentClick = {},
            onDeleteDocument = {},
            onUploadDocument = { _, _, _, _ -> },
            onClearUploadError = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentList â€“ Offline")
@Composable
private fun DocumentListOfflinePreview() {
    AppTheme(dynamicColor = false) {
        DocumentListScreenContent(
            uiState = RAGUiState.DocumentList(isOffline = true),
            isOffline = true,
            pagedDocuments = flowOf(PagingData.from(previewDocuments)).collectAsLazyPagingItems(),
            onDocumentClick = {},
            onDeleteDocument = {},
            onUploadDocument = { _, _, _, _ -> },
            onClearUploadError = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentList â€“ Empty")
@Composable
private fun DocumentListEmptyPreview() {
    AppTheme(dynamicColor = false) {
        DocumentListScreenContent(
            uiState = RAGUiState.DocumentList(isOffline = false),
            isOffline = false,
            pagedDocuments = flowOf(PagingData.empty<Document>()).collectAsLazyPagingItems(),
            onDocumentClick = {},
            onDeleteDocument = {},
            onUploadDocument = { _, _, _, _ -> },
            onClearUploadError = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentList â€“ Loading")
@Composable
private fun DocumentListLoadingPreview() {
    AppTheme(dynamicColor = false) {
        DocumentListScreenContent(
            uiState = RAGUiState.Loading,
            isOffline = false,
            pagedDocuments = flowOf(PagingData.empty<Document>()).collectAsLazyPagingItems(),
            onDocumentClick = {},
            onDeleteDocument = {},
            onUploadDocument = { _, _, _, _ -> },
            onClearUploadError = {}
        )
    }
}

@Preview(showBackground = true, name = "DocumentList â€“ Error")
@Composable
private fun DocumentListErrorPreview() {
    AppTheme(dynamicColor = false) {
        DocumentListScreenContent(
            uiState = RAGUiState.Error("Failed to load documents. Please try again."),
            isOffline = false,
            pagedDocuments = flowOf(PagingData.empty<Document>()).collectAsLazyPagingItems(),
            onDocumentClick = {},
            onDeleteDocument = {},
            onUploadDocument = { _, _, _, _ -> },
            onClearUploadError = {}
        )
    }
}
