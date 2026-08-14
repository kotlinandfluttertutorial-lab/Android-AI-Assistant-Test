/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : SearchHistoryScreen.kt
 * Purpose    : Compose UI screen for the SearchHistory feature
 *
 * Architecture Layer : Feature (feature-history)
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
 * Module     : feature-history
 * File       : SearchHistoryScreen.kt
 * Purpose    : Compose UI screen for the SearchHistory feature
 *
 * Architecture Layer : Feature (feature-history)
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
 * SearchHistoryScreen.kt
 *
 * Purpose: Jetpack Compose screen for full-text search over conversation history,
 *          showing a search text field, FTS results list, and an empty-state message.
 * Architecture: feature-history â€” Compose UI layer; state driven by [HistoryViewModel].
 * Dependencies: Compose Material 3,
 *               core-ui (OfflineBanner, MaterialTheme.spacing),
 *               domain (Conversation, ExportFormat)
 *
 * Design decisions:
 * - Stateless composable: all state flows in as parameters.
 * - Search query is owned by the caller (ViewModel) to survive configuration changes.
 * - Results are rendered as plain [LazyColumn] (not paged) because FTS typically
 *   returns a bounded result set within 300 ms (Requirement 11.2).
 * - Back navigation via the leading IconButton returns the user to [HistoryListScreen].
 * - All interactive elements carry [contentDescription] semantics (Requirement 23.1).
 *
 * Requirements: 11.2, 23.1
 */
package com.aiassistant.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// â”€â”€â”€ Screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless search screen.
 *
 * @param searchQuery         Current query string managed by the ViewModel.
 * @param searchResults       FTS result list from [HistoryViewModel.searchResults] /
 *                            [HistoryUiState.SearchResults].
 * @param isLoading           Whether a search operation is in flight.
 * @param onSearchQueryChange Invoked on each keystroke to update the ViewModel query.
 * @param onConversationClick Invoked when the user taps a result row.
 * @param onBack              Invoked when the user taps the back button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchHistoryScreen(
    searchQuery: String,
    searchResults: List<Conversation>,
    isLoading: Boolean = false,
    onSearchQueryChange: (String) -> Unit = {},
    onConversationClick: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field when the screen is first composed
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Back to conversation history"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                title = {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = "Search conversations input" },
                        placeholder = { Text("Search historyâ€¦") },
                        trailingIcon = if (searchQuery.isNotEmpty()) {
                            {
                                IconButton(
                                    onClick = { onSearchQueryChange("") },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Clear search query"
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Clear,
                                        contentDescription = null
                                    )
                                }
                            }
                        } else {
                            null
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { /* query already live */ })
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                // â”€â”€ In-flight indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics {
                                contentDescription = "Searching conversations"
                            }
                        )
                    }
                }

                // â”€â”€ Prompt: not yet typed anything â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                searchQuery.isBlank() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Type to search your conversation history.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // â”€â”€ Empty results â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                searchResults.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No conversations match \u201c$searchQuery\u201d.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // â”€â”€ Results list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                else -> {
                    SearchResultsList(
                        results = searchResults,
                        onConversationClick = onConversationClick
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Results list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Non-paged [LazyColumn] rendering FTS search results.
 *
 * Each row shows the conversation title and last-updated timestamp. Results are already
 * ordered by the domain layer (most-recently-updated first).
 *
 * @param results              The matching [Conversation] list.
 * @param onConversationClick  Invoked with the conversation id when a row is tapped.
 */
@Composable
private fun SearchResultsList(results: List<Conversation>, onConversationClick: (String) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.xl),
        verticalArrangement = Arrangement.spacedBy(0.dp) // ListItem handles its own spacing
    ) {
        itemsIndexed(
            items = results,
            key = { _, conversation -> conversation.id }
        ) { _, conversation ->
            SearchResultRow(
                conversation = conversation,
                onClick = { onConversationClick(conversation.id) }
            )
        }
    }
}

/**
 * A single search result row showing the conversation title, provider label, and date.
 *
 * @param conversation The matching conversation.
 * @param onClick      Invoked when the row is tapped.
 */
@Composable
private fun SearchResultRow(conversation: Conversation, onClick: () -> Unit) {
    ListItem(
        headlineContent = {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
        },
        supportingContent = {
            Text(
                text = "${conversation.provider} Â· ${conversation.updatedAt.formatRelative()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(conversation.title)
                    append(", provider: ${conversation.provider}")
                    append(", updated ${conversation.updatedAt.formatRelative()}")
                }
            },
        colors = ListItemDefaults.colors()
    )
}

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Formats an [Instant] using the device's system default timezone. */
private fun Instant.formatRelative(): String {
    val formatter = DateTimeFormatter
        .ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(this)
}
