/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : HistoryListScreen.kt
 * Purpose    : Compose UI screen for the HistoryList feature
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
 * File       : HistoryListScreen.kt
 * Purpose    : Compose UI screen for the HistoryList feature
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
 * HistoryListScreen.kt
 *
 * Purpose: Jetpack Compose screen displaying a paginated, date-grouped conversation
 *          history list with an inline search toggle, per-item export actions (Markdown
 *          / PDF), pin, rename and delete controls, and an offline banner.
 * Architecture: feature-history â€” Compose UI layer; state driven by [HistoryViewModel].
 * Dependencies: Compose Material 3, Paging 3 Compose,
 *               core-ui (OfflineBanner, ErrorBanner, MaterialTheme.spacing),
 *               domain (Conversation, ExportFormat, GroupedConversations)
 *
 * Design decisions:
 * - Stateless composable: all state flows in as parameters; side effects go out via
 *   callbacks. This allows previewing without a real ViewModel.
 * - Paging 3 is used via [collectAsLazyPagingItems] so at most 20 items are in memory
 *   at any time (Requirement 17.6).
 * - Export actions are surfaced in a per-item [DropdownMenu] rather than a swipe
 *   action so they remain accessible via TalkBack (Requirement 23.1).
 * - All interactive elements carry [contentDescription] semantics (Requirement 23.1).
 * - Provider badge is rendered as an icon-free label chip â€” does not rely on color
 *   alone (Requirement 23.4).
 *
 * Requirements: 11.1, 11.2, 11.5, 11.6, 10.4, 17.6, 23.1, 23.4
 */
package com.aiassistant.feature.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// â”€â”€â”€ Screen entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless entry point for the history list screen.
 *
 * @param uiState               Current [HistoryUiState] from [HistoryViewModel].
 * @param pagedItems            Paging 3 items for the [LazyColumn].
 * @param isOffline             Whether the device has no network connectivity.
 * @param onSearchClick         Invoked when the user taps the search icon to open search.
 * @param onConversationClick   Invoked when a conversation row is tapped.
 * @param onPinConversation     Invoked with (id, newPinnedState) when pin/unpin is selected.
 * @param onRenameConversation  Invoked with (id, newTitle) after the rename dialog confirms.
 * @param onDeleteConversation  Invoked with the conversation id after delete confirmation.
 * @param onExportConversation  Invoked with (id, format) when an export option is selected.
 * @param onDismissExportResult Invoked when the export success snackbar is dismissed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryListScreen(
    uiState: HistoryUiState,
    pagedItems: LazyPagingItems<HistoryListItem>,
    isOffline: Boolean = false,
    onSearchClick: () -> Unit = {},
    onConversationClick: (String) -> Unit = {},
    onPinConversation: (String, Boolean) -> Unit = { _, _ -> },
    onRenameConversation: (String, String) -> Unit = { _, _ -> },
    onDeleteConversation: (String) -> Unit = {},
    onExportConversation: (String, ExportFormat) -> Unit = { _, _ -> },
    onDismissExportResult: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show export success snackbar
    val exportSuccess = uiState as? HistoryUiState.ExportSuccess
    LaunchedEffect(exportSuccess) {
        if (exportSuccess != null) {
            val label = when (exportSuccess.format) {
                ExportFormat.MARKDOWN -> "Markdown"
                ExportFormat.PDF -> "PDF"
            }
            snackbarHostState.showSnackbar("Exported as $label successfully.")
            onDismissExportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                actions = {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.semantics {
                            contentDescription = "Search conversation history"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Offline banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOffline) {
                OfflineBanner(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // â”€â”€ Error banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HistoryUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // â”€â”€ Export in-progress indicator â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HistoryUiState.Exporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "Exporting conversation\u2026"
                        }
                )
            }

            // â”€â”€ Initial loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HistoryUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading conversation history"
                        }
                    )
                }
                return@Column
            }

            // â”€â”€ Empty state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is HistoryUiState.HistoryList &&
                uiState.groupedConversations.isEmpty &&
                pagedItems.itemCount == 0 &&
                pagedItems.loadState.refresh !is LoadState.Loading
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No conversations yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            // â”€â”€ Paged conversation list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            PagedHistoryList(
                pagedItems = pagedItems,
                onConversationClick = onConversationClick,
                onPinConversation = onPinConversation,
                onRenameConversation = onRenameConversation,
                onDeleteConversation = onDeleteConversation,
                onExportConversation = onExportConversation
            )
        }
    }
}

// â”€â”€â”€ Paged list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * [LazyColumn] backed by [LazyPagingItems] for the history list.
 *
 * Renders [HistoryListItem.Header] rows as non-interactive section headers and
 * [HistoryListItem.ConversationItem] rows as tappable conversation rows.
 */
@Composable
private fun PagedHistoryList(
    pagedItems: LazyPagingItems<HistoryListItem>,
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onExportConversation: (String, ExportFormat) -> Unit
) {
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var targetConversation by remember { mutableStateOf<Conversation?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.xl)
    ) {
        items(
            count = pagedItems.itemCount,
            key = { index ->
                when (val item = pagedItems.peek(index)) {
                    is HistoryListItem.Header -> "header:${item.label}"
                    is HistoryListItem.ConversationItem -> "conv:${item.conversation.id}"
                    null -> "null:$index"
                }
            }
        ) { index ->
            when (val item = pagedItems[index]) {
                is HistoryListItem.Header -> HistorySectionHeader(label = item.label)
                is HistoryListItem.ConversationItem -> HistoryConversationRow(
                    conversation = item.conversation,
                    onClick = { onConversationClick(item.conversation.id) },
                    onPinClick = {
                        onPinConversation(item.conversation.id, !item.conversation.isPinned)
                    },
                    onRenameClick = {
                        targetConversation = item.conversation
                        showRenameDialog = true
                    },
                    onDeleteClick = {
                        targetConversation = item.conversation
                        showDeleteDialog = true
                    },
                    onExportClick = { format ->
                        onExportConversation(item.conversation.id, format)
                    }
                )
                null -> Unit
            }
        }

        // Append loading indicator
        if (pagedItems.loadState.append is LoadState.Loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(MaterialTheme.spacing.lg)
                            .semantics { contentDescription = "Loading more conversations" }
                    )
                }
            }
        }
    }

    // â”€â”€ Rename dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showRenameDialog && targetConversation != null) {
        RenameDialog(
            currentTitle = targetConversation!!.title,
            onConfirm = { newTitle ->
                onRenameConversation(targetConversation!!.id, newTitle)
                showRenameDialog = false
                targetConversation = null
            },
            onDismiss = {
                showRenameDialog = false
                targetConversation = null
            }
        )
    }

    // â”€â”€ Delete confirmation dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showDeleteDialog && targetConversation != null) {
        DeleteDialog(
            conversationTitle = targetConversation!!.title,
            onConfirm = {
                onDeleteConversation(targetConversation!!.id)
                showDeleteDialog = false
                targetConversation = null
            },
            onDismiss = {
                showDeleteDialog = false
                targetConversation = null
            }
        )
    }
}

// â”€â”€â”€ Row components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Non-interactive section header (e.g. "Today", "Last 7 Days").
 */
@Composable
private fun HistorySectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.md,
                vertical = MaterialTheme.spacing.xs
            )
            .semantics { contentDescription = "$label section" }
    )
}

/**
 * Tappable row for a single conversation, showing title, last-updated date,
 * provider badge, pin indicator, and a trailing [DropdownMenu] with per-item actions.
 *
 * Actions available in the overflow menu:
 * - Pin / Unpin
 * - Rename
 * - Export as Markdown
 * - Export as PDF
 * - Delete
 *
 * @param conversation   The conversation to render.
 * @param onClick        Invoked when the row is tapped.
 * @param onPinClick     Invoked when pin/unpin is selected.
 * @param onRenameClick  Invoked when rename is selected.
 * @param onDeleteClick  Invoked when delete is selected.
 * @param onExportClick  Invoked with the chosen [ExportFormat] when an export option is selected.
 */
@Composable
private fun HistoryConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onExportClick: (ExportFormat) -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Text(
                    text = conversation.updatedAt.formatRelative(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // Provider badge â€” not color-only (Requirement 23.4)
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = conversation.provider,
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    modifier = Modifier
                        .height(24.dp)
                        .semantics { contentDescription = "Provider: ${conversation.provider}" }
                )
            }
        },
        leadingContent = if (conversation.isPinned) {
            {
                Icon(
                    imageVector = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(MaterialTheme.spacing.md)
                )
            }
        } else {
            null
        },
        trailingContent = {
            Box {
                IconButton(
                    onClick = { menuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = "More options for ${conversation.title}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = null
                    )
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    // Pin / Unpin
                    DropdownMenuItem(
                        text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                        onClick = {
                            menuExpanded = false
                            onPinClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription =
                                if (conversation.isPinned) "Unpin conversation" else "Pin conversation"
                        }
                    )
                    // Rename
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuExpanded = false
                            onRenameClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Rename conversation"
                        }
                    )
                    // Export as Markdown
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.IosShare,
                                contentDescription = null
                            )
                        },
                        text = { Text("Export as Markdown") },
                        onClick = {
                            menuExpanded = false
                            onExportClick(ExportFormat.MARKDOWN)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Export conversation as Markdown"
                        }
                    )
                    // Export as PDF
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Filled.IosShare,
                                contentDescription = null
                            )
                        },
                        text = { Text("Export as PDF") },
                        onClick = {
                            menuExpanded = false
                            onExportClick(ExportFormat.PDF)
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Export conversation as PDF"
                        }
                    )
                    // Delete
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            onDeleteClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "Delete conversation"
                        }
                    )
                }
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = buildString {
                    append(conversation.title)
                    if (conversation.isPinned) append(", pinned")
                    append(", provider: ${conversation.provider}")
                    append(", updated ${conversation.updatedAt.formatRelative()}")
                }
            },
        colors = ListItemDefaults.colors()
    )
}

// â”€â”€â”€ Dialogs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Dialog for renaming a conversation. Pre-fills [currentTitle] in the text field.
 */
@Composable
private fun RenameDialog(currentTitle: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newTitle by rememberSaveable(currentTitle) { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Conversation") },
        text = {
            OutlinedTextField(
                value = newTitle,
                onValueChange = { newTitle = it },
                label = { Text("New title") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New conversation title input" }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (newTitle.isNotBlank()) onConfirm(newTitle) },
                modifier = Modifier.semantics { contentDescription = "Save new title" }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel rename" }
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Confirmation dialog before soft-deleting a conversation.
 */
@Composable
private fun DeleteDialog(conversationTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Conversation") },
        text = {
            Text(
                text = "Delete \u201c$conversationTitle\u201d? This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete conversation" }
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
                modifier = Modifier.semantics { contentDescription = "Cancel delete" }
            ) {
                Text("Cancel")
            }
        }
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
