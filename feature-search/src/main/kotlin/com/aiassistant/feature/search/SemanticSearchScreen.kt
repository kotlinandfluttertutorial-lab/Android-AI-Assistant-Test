/**
 * SemanticSearchScreen.kt
 *
 * Purpose: Compose UI screen for AI-powered semantic search across conversations, notes,
 *          documents, and memories. Groups results by source type with excerpt highlighting.
 * Architecture: feature-search — Compose UI layer; stateless composable driven by
 *               SemanticSearchUiState from SemanticSearchViewModel.
 * Dependencies: core-ui, domain (SemanticSearchResult), SemanticSearchUiState.
 *
 * Requirements: 36.1, 36.3, 36.4, 36.5, 36.8
 */
package com.aiassistant.feature.search

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.SemanticSearchResult

/**
 * AI-powered semantic search screen.
 *
 * Provides a query input field; submits via [SemanticSearchViewModel.search].
 * Results are grouped by source type with a header showing the type name and count.
 * Tapping a result navigates via its [SemanticSearchResult.deepLinkUri].
 *
 * @param viewModel          Hilt-injected ViewModel.
 * @param onNavigateToResult Callback invoked with the deep-link URI when a result is tapped.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SemanticSearchScreen(viewModel: SemanticSearchViewModel = hiltViewModel(), onNavigateToResult: (String) -> Unit) {
    val uiState by viewModel.uiState.collectAsState()
    var queryText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Semantic Search") }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Query input field ─────────────────────────────────────────────
            OutlinedTextField(
                value = queryText,
                onValueChange = { queryText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search query input" },
                label = { Text("Ask anything…") },
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.search(queryText) },
                        modifier = Modifier.semantics { contentDescription = "Submit search" }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { viewModel.search(queryText) }
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── State-driven content ──────────────────────────────────────────
            when (val state = uiState) {
                is SemanticSearchUiState.Idle -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Enter a query to search across your content.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is SemanticSearchUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Searching…"
                            }
                        )
                    }
                }

                is SemanticSearchUiState.Success -> {
                    SearchResultList(
                        groupedResults = state.groupedResults,
                        query = queryText,
                        onResultClick = { onNavigateToResult(it.deepLinkUri) }
                    )
                }

                is SemanticSearchUiState.Empty -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "No results found",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics {
                                    contentDescription = "No results found"
                                }
                            )
                            Text(
                                text = "Try rephrasing your search query",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                is SemanticSearchUiState.Error -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

// ─── Result list ─────────────────────────────────────────────────────────────

/**
 * Renders the full result list grouped by source type.
 *
 * Each group has a section header showing the type label and count. Groups with
 * no results are omitted (guaranteed by ViewModel). Results within each group are
 * sorted by relevance score descending.
 */
@Composable
private fun SearchResultList(
    groupedResults: Map<SemanticSearchResult.SourceType, List<SemanticSearchResult>>,
    query: String,
    onResultClick: (SemanticSearchResult) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Preserve a deterministic order for source types
        val typeOrder = listOf(
            SemanticSearchResult.SourceType.CONVERSATION,
            SemanticSearchResult.SourceType.NOTE,
            SemanticSearchResult.SourceType.DOCUMENT,
            SemanticSearchResult.SourceType.MEMORY
        )

        typeOrder.forEach { sourceType ->
            val results = groupedResults[sourceType] ?: return@forEach

            // Group header
            item(key = "header_${sourceType.name}") {
                SearchGroupHeader(sourceType = sourceType, count = results.size)
            }

            // Result cards
            items(results, key = { it.deepLinkUri }) { result ->
                SearchResultCard(
                    result = result,
                    query = query,
                    onClick = { onResultClick(result) }
                )
            }
        }
    }
}

// ─── Group header ─────────────────────────────────────────────────────────────

/**
 * Section header for a source-type group.
 */
@Composable
private fun SearchGroupHeader(sourceType: SemanticSearchResult.SourceType, count: Int) {
    val label = when (sourceType) {
        SemanticSearchResult.SourceType.CONVERSATION -> "Conversations"
        SemanticSearchResult.SourceType.NOTE -> "Notes"
        SemanticSearchResult.SourceType.DOCUMENT -> "Documents"
        SemanticSearchResult.SourceType.MEMORY -> "Memories"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "$label group header"
            }
        )
        Text(
            text = "$count result${if (count == 1) "" else "s"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Result card ──────────────────────────────────────────────────────────────

/**
 * Card displaying a single search result.
 *
 * Shows:
 * - Source name (title)
 * - Excerpt with matched query text highlighted in bold
 * - Relevance score formatted to two decimal places
 *
 * Tapping the card triggers [onClick].
 */
@Composable
private fun SearchResultCard(result: SemanticSearchResult, query: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Search result: ${result.sourceName}" },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Source name
            Text(
                text = result.sourceName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium
            )

            // Excerpt with highlighted matched text
            val highlightedExcerpt = buildAnnotatedString {
                val excerpt = result.excerpt
                val lowerExcerpt = excerpt.lowercase()
                val lowerQuery = query.lowercase().trim()

                if (lowerQuery.isNotBlank() && lowerExcerpt.contains(lowerQuery)) {
                    var searchStart = 0
                    var matchIndex = lowerExcerpt.indexOf(lowerQuery, searchStart)
                    while (matchIndex >= 0) {
                        append(excerpt.substring(searchStart, matchIndex))
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(excerpt.substring(matchIndex, matchIndex + lowerQuery.length))
                        }
                        searchStart = matchIndex + lowerQuery.length
                        matchIndex = lowerExcerpt.indexOf(lowerQuery, searchStart)
                    }
                    append(excerpt.substring(searchStart))
                } else {
                    append(excerpt)
                }
            }

            Text(
                text = highlightedExcerpt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Relevance score
            Text(
                text = "Score: ${"%.2f".format(result.relevanceScore)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = "Relevance score: ${"%.2f".format(result.relevanceScore)}"
                }
            )
        }
    }
}
