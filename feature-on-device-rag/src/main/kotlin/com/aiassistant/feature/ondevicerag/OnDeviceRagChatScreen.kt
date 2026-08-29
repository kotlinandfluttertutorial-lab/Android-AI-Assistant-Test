/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : OnDeviceRagChatScreen.kt
 * Purpose    : Stateful entry point + stateless content composable for the
 *              on-device RAG chat screen.  Shows the active inference path
 *              badge, streams tokens, expandable citations, fallback banner,
 *              NoRelevantContent state, and error + retry action.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — Compose UI layer.
 *
 * Requirements: 35.1, 35.4, 35.5, 35.8, 35.9, 36.5, 36.6, 36.7, 36.8
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.ChunkCitation
import com.aiassistant.domain.model.OnDeviceInferencePath

// ── Stateful entry point ─────────────────────────────────────────────────────

@Composable
fun OnDeviceRagChatScreen(
    onNavigateUp: () -> Unit,
    viewModel: OnDeviceRagViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf("") }

    OnDeviceRagChatContent(
        uiState = uiState,
        query = query,
        onQueryChange = { query = it },
        onSubmitQuery = {
            if (query.isNotBlank()) {
                viewModel.submitQuery(query)
            }
        },
        onRetryViaCloud = { viewModel.retryViaCloud(query) },
        onReset = viewModel::reset,
        onNavigateUp = onNavigateUp,
        modifier = Modifier.fillMaxSize(),
    )
}

// ── Stateless content composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnDeviceRagChatContent(
    uiState: OnDeviceRagChatUiState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: () -> Unit,
    onRetryViaCloud: () -> Unit,
    onReset: () -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Derive active path label for the toolbar badge
    val activePathLabel = when (uiState) {
        is OnDeviceRagChatUiState.Searching -> uiState.activePath.toLabel()
        is OnDeviceRagChatUiState.Streaming -> uiState.activePath.toLabel()
        is OnDeviceRagChatUiState.Done -> uiState.activePath.toLabel()
        else -> null
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("On-Device RAG Chat")
                        activePathLabel?.let { label ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = if (label == "Running on device")
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.semantics {
                                    contentDescription = "Inference path: $label"
                                },
                            ) {
                                Text(
                                    text = label,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate up" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ── Fallback notification banner ──────────────────────────────
            val showFallback = when (uiState) {
                is OnDeviceRagChatUiState.Searching -> uiState.fallbackBanner
                is OnDeviceRagChatUiState.Streaming -> uiState.fallbackBanner
                is OnDeviceRagChatUiState.Done -> uiState.fallbackBanner
                else -> false
            }
            AnimatedVisibility(visible = showFallback) {
                FallbackNotificationBanner()
            }

            // ── Main content area ─────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                when (uiState) {
                    is OnDeviceRagChatUiState.Idle -> {
                        IdlePrompt()
                    }

                    is OnDeviceRagChatUiState.Routing -> {
                        CenteredIndicator("Checking capabilities…")
                    }

                    is OnDeviceRagChatUiState.Searching -> {
                        CenteredIndicator("Searching local documents…")
                    }

                    is OnDeviceRagChatUiState.Streaming -> {
                        ResponseArea(
                            text = uiState.accumulatedText,
                            citations = emptyList(),
                            isStreaming = true,
                        )
                    }

                    is OnDeviceRagChatUiState.Done -> {
                        ResponseArea(
                            text = uiState.responseText,
                            citations = uiState.citations,
                            isStreaming = false,
                        )
                    }

                    is OnDeviceRagChatUiState.NoRelevantContent -> {
                        NoRelevantContentState()
                    }

                    is OnDeviceRagChatUiState.Error -> {
                        ErrorState(
                            message = uiState.message,
                            canRetry = uiState.canRetry,
                            onRetryViaCloud = onRetryViaCloud,
                            onReset = onReset,
                        )
                    }

                    is OnDeviceRagChatUiState.FileSizeRejection -> Unit // not used in chat
                    is OnDeviceRagChatUiState.DocumentList -> Unit
                    is OnDeviceRagChatUiState.Loading -> Unit
                    is OnDeviceRagChatUiState.IngestionRunning -> Unit
                }
            }

            // ── Query input ───────────────────────────────────────────────
            val isProcessing = uiState is OnDeviceRagChatUiState.Routing ||
                uiState is OnDeviceRagChatUiState.Searching ||
                uiState is OnDeviceRagChatUiState.Streaming

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Query input field" },
                placeholder = { Text("Ask a question about your documents…") },
                enabled = !isProcessing,
                maxLines = 4,
            )

            Button(
                onClick = onSubmitQuery,
                enabled = query.isNotBlank() && !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Submit query" },
            ) {
                Text("Ask")
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ResponseArea(
    text: String,
    citations: List<ChunkCitation>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    var showCitations by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = if (isStreaming) "Streaming response" else "Response" },
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (isStreaming) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.End)
                            .semantics { contentDescription = "Generating response" },
                        strokeWidth = 2.dp,
                    )
                }
            }
        }

        if (citations.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showCitations = !showCitations },
                modifier = Modifier.semantics {
                    contentDescription = if (showCitations) "Hide sources" else "Show sources"
                },
            ) {
                Icon(Icons.Default.Info, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(if (showCitations) "Hide sources" else "Show sources (${citations.size})")
            }

            AnimatedVisibility(visible = showCitations) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    citations.forEach { citation ->
                        CitationCard(citation = citation)
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationCard(
    citation: ChunkCitation,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Source: ${citation.documentName}, chunk ${citation.chunkIndex}"
            },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = citation.documentName +
                    (citation.pageNumber?.let { " · page $it" } ?: "") +
                    " · chunk ${citation.chunkIndex}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Similarity: ${"%.2f".format(citation.cosineSimilarity)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = citation.excerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CenteredIndicator(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator()
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun IdlePrompt(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Type a question below to search your on-device documents.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "Enter a query to get started" },
        )
    }
}

@Composable
private fun NoRelevantContentState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "No relevant content found in local documents.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = "No relevant content found in local documents"
            },
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    canRetry: Boolean,
    onRetryViaCloud: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.semantics { contentDescription = "Error: $message" },
        )
        Spacer(Modifier.height(16.dp))
        if (canRetry) {
            Button(
                onClick = onRetryViaCloud,
                modifier = Modifier.semantics { contentDescription = "Retry via cloud" },
            ) {
                Text("Retry via cloud")
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.semantics { contentDescription = "Start new query" },
        ) {
            Text("New query")
        }
    }
}

@Composable
private fun FallbackNotificationBanner(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Cloud fallback occurred" },
    ) {
        Text(
            text = "On-device inference unavailable — using cloud AI.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

private fun OnDeviceInferencePath.toLabel(): String = when (this) {
    OnDeviceInferencePath.ON_DEVICE -> "Running on device"
    OnDeviceInferencePath.CLOUD -> "Using cloud AI"
}
