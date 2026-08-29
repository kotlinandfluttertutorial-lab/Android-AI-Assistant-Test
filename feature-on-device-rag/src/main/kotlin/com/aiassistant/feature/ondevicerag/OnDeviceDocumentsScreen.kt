/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceDocumentsScreen.kt
 * Purpose    : Stateful entry point + stateless content composable for the
 *              on-device document list screen.  Allows users to add, view, and
 *              delete locally ingested documents.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — Compose UI layer.
 *
 * Dependencies       : OnDeviceDocumentViewModel, OnDeviceDocumentUiState,
 *                      core-ui design tokens, domain model
 *
 * Requirements: 33.1, 33.2, 33.3, 33.6, 33.7, 33.9, 33.10
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.IngestionProgress
import com.aiassistant.domain.model.OnDeviceDocument
import com.aiassistant.domain.model.OnDeviceIngestionStatus

// ── Stateful entry point ─────────────────────────────────────────────────────

/**
 * Stateful screen that wires [OnDeviceDocumentViewModel] to [OnDeviceDocumentsContent].
 *
 * @param onNavigateToChat  Called when the user taps a READY document to open RAG chat.
 * @param viewModel         Hilt-injected ViewModel.
 */
@Composable
fun OnDeviceDocumentsScreen(
    onNavigateToChat: (documentId: String) -> Unit,
    viewModel: OnDeviceDocumentViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Show file-size rejection as a snackbar then reset state
    LaunchedEffect(uiState) {
        if (uiState is OnDeviceDocumentUiState.FileSizeRejection) {
            val state = uiState as OnDeviceDocumentUiState.FileSizeRejection
            snackbarHostState.showSnackbar(
                "\"${state.fileName}\" is too large. Maximum file size is 50 MB."
            )
            viewModel.clearFileSizeRejection()
        }
    }

    OnDeviceDocumentsContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onAddDocumentClick = { /* File picker launched from here in production */ },
        onDocumentClick = { doc ->
            if (doc.ingestionStatus == OnDeviceIngestionStatus.READY) {
                onNavigateToChat(doc.id)
            }
        },
        onDeleteDocument = viewModel::deleteDocument,
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Stateless content composable ─────────────────────────────────────────────

/**
 * Stateless content composable for the on-device documents screen.
 * Accepts all data and callbacks as parameters — no ViewModel reference.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceDocumentsContent(
    uiState: OnDeviceDocumentUiState,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onAddDocumentClick: () -> Unit,
    onDocumentClick: (OnDeviceDocument) -> Unit,
    onDeleteDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "On-Device Documents",
                        semantics = { contentDescription = "On-Device Documents title" },
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onAddDocumentClick,
                modifier = Modifier.semantics { contentDescription = "Add document" },
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add document")
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (uiState) {
                is OnDeviceDocumentUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading documents" }
                        )
                    }
                }

                is OnDeviceDocumentUiState.DocumentList -> {
                    DocumentListContent(
                        documents = uiState.documents,
                        lowStorageWarning = uiState.lowStorageWarning,
                        onDocumentClick = onDocumentClick,
                        onDeleteDocument = onDeleteDocument,
                    )
                }

                is OnDeviceDocumentUiState.IngestionRunning -> {
                    Column {
                        IngestionProgressBanner(
                            fileName = uiState.fileName,
                            progress = uiState.progress,
                        )
                        DocumentListContent(
                            documents = uiState.documents,
                            lowStorageWarning = false,
                            onDocumentClick = onDocumentClick,
                            onDeleteDocument = onDeleteDocument,
                        )
                    }
                }

                is OnDeviceDocumentUiState.FileSizeRejection -> {
                    // Handled by LaunchedEffect snackbar in the stateful wrapper
                    DocumentListContent(
                        documents = emptyList(),
                        lowStorageWarning = false,
                        onDocumentClick = onDocumentClick,
                        onDeleteDocument = onDeleteDocument,
                    )
                }

                is OnDeviceDocumentUiState.Error -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = uiState.message,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.semantics {
                                contentDescription = "Error: ${uiState.message}"
                            },
                        )
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun DocumentListContent(
    documents: List<OnDeviceDocument>,
    lowStorageWarning: Boolean,
    onDocumentClick: (OnDeviceDocument) -> Unit,
    onDeleteDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        if (lowStorageWarning) {
            LowStorageWarningBanner()
        }

        if (documents.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No documents yet. Tap + to add a PDF, TXT, or Markdown file.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = "No documents available"
                    },
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(documents, key = { it.id }) { doc ->
                    DocumentListItem(
                        document = doc,
                        onDocumentClick = { onDocumentClick(doc) },
                        onDeleteDocument = { onDeleteDocument(doc.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DocumentListItem(
    document: OnDeviceDocument,
    onDocumentClick: () -> Unit,
    onDeleteDocument: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onDocumentClick,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Document: ${document.fileName}" },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.fileName,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))

                // Status badge
                val (badgeText, badgeColor) = when (document.ingestionStatus) {
                    OnDeviceIngestionStatus.READY -> "Ready" to MaterialTheme.colorScheme.primary
                    OnDeviceIngestionStatus.PROCESSING -> "Processing" to MaterialTheme.colorScheme.tertiary
                    OnDeviceIngestionStatus.PENDING -> "Pending" to MaterialTheme.colorScheme.outline
                    OnDeviceIngestionStatus.FAILED -> {
                        val label = "Failed${document.failureStage?.let { " ($it)" } ?: ""}"
                        label to MaterialTheme.colorScheme.error
                    }
                }
                Text(
                    text = badgeText,
                    style = MaterialTheme.typography.labelSmall,
                    color = badgeColor,
                    modifier = Modifier.semantics {
                        contentDescription = "Status: $badgeText"
                    },
                )

                if (document.ingestionStatus == OnDeviceIngestionStatus.READY && document.totalChunks > 0) {
                    Text(
                        text = "${document.totalChunks} chunks",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(
                onClick = onDeleteDocument,
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${document.fileName}"
                },
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete document",
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun IngestionProgressBanner(
    fileName: String,
    progress: IngestionProgress,
    modifier: Modifier = Modifier,
) {
    val progressText = when (progress) {
        is IngestionProgress.Parsing -> "Parsing $fileName…"
        is IngestionProgress.Chunking -> "Splitting $fileName into chunks…"
        is IngestionProgress.Embedding -> "Generating embeddings ${progress.current}/${progress.total}…"
        else -> "Processing $fileName…"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Ingestion in progress: $progressText" },
    ) {
        Text(
            text = progressText,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        val fraction = (progress as? IngestionProgress.Embedding)
            ?.let { it.current.toFloat() / it.total.toFloat() }
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun LowStorageWarningBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "Low storage warning: ingestion paused" },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = "Storage is almost full. Free up space to continue ingesting documents.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// Extension for Text semantics convenience
@Composable
private fun Text(
    text: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    color: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Unspecified,
    semantics: (androidx.compose.ui.semantics.SemanticsPropertyReceiver.() -> Unit)? = null,
) {
    val finalModifier = if (semantics != null) modifier.semantics(block = semantics) else modifier
    androidx.compose.material3.Text(
        text = text,
        modifier = finalModifier,
        style = style,
        color = color,
    )
}
