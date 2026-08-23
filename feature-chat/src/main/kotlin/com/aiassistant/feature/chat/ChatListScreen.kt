/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatListScreen.kt
 * Purpose    : Compose UI screen for the ChatList feature
 *
 * Architecture Layer : Feature (feature-chat)
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
 * Module     : feature-chat
 * File       : ChatListScreen.kt
 * Purpose    : Compose UI screen for the ChatList feature
 *
 * Architecture Layer : Feature (feature-chat)
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
 * ChatListScreen.kt
 *
 * Purpose: Jetpack Compose screen displaying a paginated, date-grouped list of
 *          conversations with offline banner, search, FAB for new conversations,
 *          and per-item actions (pin, rename, delete).
 * Architecture: feature-chat â€” Compose UI layer; state driven by [ChatViewModel].
 * Dependencies: Compose Material 3, Paging 3 Compose, Hilt Navigation Compose,
 *               core-ui (AppTheme, OfflineBanner, MaterialTheme.spacing)
 *
 * Requirements: 11.1, 11.3, 11.5, 10.4, 17.6
 */
package com.aiassistant.feature.chat

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.flowOf

// â”€â”€â”€ Screen entry point â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateful entry point for the conversation list screen. Collects state from
 * [ChatViewModel] and delegates rendering to the stateless overload.
 *
 * @param viewModel          The Hilt-provided [ChatViewModel].
 * @param onConversationClick Callback invoked when the user taps a conversation row.
 */
@Composable
fun ChatListScreen(viewModel: ChatViewModel, onConversationClick: (String) -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isOffline by viewModel.isOffline.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val pagedItems = viewModel.pagedConversations.collectAsLazyPagingItems()

    ChatListScreenContent(
        uiState = uiState,
        isOffline = isOffline,
        searchQuery = searchQuery,
        searchResults = searchResults,
        pagedItems = pagedItems,
        onSearchQueryChange = viewModel::setSearchQuery,
        onConversationClick = onConversationClick,
        onPinConversation = { id, pinned -> viewModel.pinConversation(id, pinned) },
        onRenameConversation = { id, title -> viewModel.renameConversation(id, title) },
        onDeleteConversation = viewModel::deleteConversation,
        onCreateConversation = { title -> viewModel.createConversation(title, "openai") }
    )
}

// â”€â”€â”€ Stateless screen â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Stateless conversation list screen. All state is passed in; side effects are
 * communicated via callbacks.
 */
@Composable
internal fun ChatListScreenContent(
    uiState: ChatListUiState,
    isOffline: Boolean,
    searchQuery: String,
    searchResults: List<Conversation>,
    pagedItems: LazyPagingItems<ChatListItem>,
    onSearchQueryChange: (String) -> Unit,
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onCreateConversation: (String) -> Unit
) {
    // â”€â”€ Dialog state â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    var showNewConversationDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }

    // Conversation currently targeted by a context action
    var targetConversation by remember { mutableStateOf<Conversation?>(null) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewConversationDialog = true },
                modifier = Modifier.semantics {
                    contentDescription = "New conversation"
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // â”€â”€ Persistent offline banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOffline) {
                OfflineBanner(
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // â”€â”€ Search bar â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md)
                    .padding(
                        top = MaterialTheme.spacing.sm,
                        bottom = MaterialTheme.spacing.xs
                    )
                    .semantics { contentDescription = "Search conversations" },
                placeholder = { Text("Search conversationsâ€¦") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
                    )
                },
                singleLine = true
            )

            // â”€â”€ Body â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            when {
                searchQuery.isNotBlank() -> {
                    // Show search results
                    SearchResultsList(
                        results = searchResults,
                        onConversationClick = onConversationClick,
                        onPinConversation = onPinConversation,
                        onRenameRequest = { conversation ->
                            targetConversation = conversation
                            showRenameDialog = true
                        },
                        onDeleteRequest = { conversation ->
                            targetConversation = conversation
                            showDeleteDialog = true
                        }
                    )
                }
                uiState is ChatListUiState.Loading -> {
                    LoadingContent()
                }
                uiState is ChatListUiState.Error -> {
                    ErrorContent(message = uiState.message)
                }
                uiState is ChatListUiState.Empty -> {
                    EmptyContent()
                }
                else -> {
                    // Success: show paged list
                    PagedConversationList(
                        pagedItems = pagedItems,
                        onConversationClick = onConversationClick,
                        onPinConversation = onPinConversation,
                        onRenameRequest = { conversation ->
                            targetConversation = conversation
                            showRenameDialog = true
                        },
                        onDeleteRequest = { conversation ->
                            targetConversation = conversation
                            showDeleteDialog = true
                        }
                    )
                }
            }
        }
    }

    // â”€â”€ New conversation dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showNewConversationDialog) {
        NewConversationDialog(
            onConfirm = { title ->
                onCreateConversation(title)
                showNewConversationDialog = false
            },
            onDismiss = { showNewConversationDialog = false }
        )
    }

    // â”€â”€ Rename dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (showRenameDialog && targetConversation != null) {
        RenameConversationDialog(
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
        DeleteConversationDialog(
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

// â”€â”€â”€ List components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Paged [LazyColumn] backed by [LazyPagingItems].
 */
@Composable
private fun PagedConversationList(
    pagedItems: LazyPagingItems<ChatListItem>,
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameRequest: (Conversation) -> Unit,
    onDeleteRequest: (Conversation) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.xl)
    ) {
        items(
            count = pagedItems.itemCount,
            key = { index ->
                when (val item = pagedItems.peek(index)) {
                    is ChatListItem.Header -> "header:${item.label}"
                    is ChatListItem.ConversationItem -> "conv:${item.conversation.id}"
                    null -> "null:$index"
                }
            }
        ) { index ->
            when (val item = pagedItems[index]) {
                is ChatListItem.Header -> SectionHeader(label = item.label)
                is ChatListItem.ConversationItem -> ConversationRow(
                    conversation = item.conversation,
                    onClick = { onConversationClick(item.conversation.id) },
                    onPinClick = { onPinConversation(item.conversation.id, !item.conversation.isPinned) },
                    onRenameClick = { onRenameRequest(item.conversation) },
                    onDeleteClick = { onDeleteRequest(item.conversation) }
                )
                null -> Unit
            }
        }

        if (pagedItems.loadState.append is LoadState.Loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.md),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(MaterialTheme.spacing.lg))
                }
            }
        }
    }
}

/**
 * Non-paged list used when showing FTS search results.
 */
@Composable
private fun SearchResultsList(
    results: List<Conversation>,
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameRequest: (Conversation) -> Unit,
    onDeleteRequest: (Conversation) -> Unit
) {
    if (results.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "No conversations match your search.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.xl)
    ) {
        items(
            count = results.size,
            key = { index -> results[index].id }
        ) { index ->
            val conversation = results[index]
            ConversationRow(
                conversation = conversation,
                onClick = { onConversationClick(conversation.id) },
                onPinClick = { onPinConversation(conversation.id, !conversation.isPinned) },
                onRenameClick = { onRenameRequest(conversation) },
                onDeleteClick = { onDeleteRequest(conversation) }
            )
        }
    }
}

// â”€â”€â”€ Row components â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Non-interactive section header row (e.g. "Today", "Yesterday").
 */
@Composable
private fun SectionHeader(label: String) {
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
 * A single conversation row showing title, last-updated date, pin indicator, and a
 * trailing [DropdownMenu] with Pin/Unpin, Rename, and Delete actions.
 */
@Composable
private fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit
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
            Text(
                text = conversation.updatedAt.formatRelative(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                    DropdownMenuItem(
                        text = {
                            Text(if (conversation.isPinned) "Unpin" else "Pin")
                        },
                        onClick = {
                            menuExpanded = false
                            onPinClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = if (conversation.isPinned) "Unpin conversation" else "Pin conversation"
                        }
                    )
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
                    append(", updated ${conversation.updatedAt.formatRelative()}")
                }
            },
        colors = ListItemDefaults.colors()
    )
}

// â”€â”€â”€ Content state placeholders â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.semantics {
                contentDescription = "Loading conversations"
            }
        )
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
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
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            Text(
                text = "Tap + to start a new conversation.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// â”€â”€â”€ Dialogs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Dialog for creating a new conversation. Contains a [OutlinedTextField] for the title.
 */
@Composable
private fun NewConversationDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Conversation") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                placeholder = { Text("Enter a titleâ€¦") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Conversation title input" }
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (title.isNotBlank()) onConfirm(title) },
                modifier = Modifier.semantics { contentDescription = "Create conversation" }
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel" }
            ) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Dialog for renaming a conversation. Pre-fills [currentTitle] in the text field.
 */
@Composable
private fun RenameConversationDialog(currentTitle: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
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
private fun DeleteConversationDialog(conversationTitle: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Conversation") },
        text = {
            Text(
                text = "Delete \"$conversationTitle\"? This action cannot be undone.",
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

/** Formats an [Instant] as a human-readable date string using the system default timezone. */
private fun Instant.formatRelative(): String {
    val formatter = DateTimeFormatter
        .ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
    return formatter.format(this)
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "ChatList â€“ Empty")
@Composable
private fun ChatListEmptyPreview() {
    AppTheme(dynamicColor = false) {
        ChatListScreenContent(
            uiState = ChatListUiState.Empty(isOffline = false),
            isOffline = false,
            searchQuery = "",
            searchResults = emptyList(),
            pagedItems = flowOf(PagingData.empty<ChatListItem>()).collectAsLazyPagingItems(),
            onSearchQueryChange = {},
            onConversationClick = {},
            onPinConversation = { _, _ -> },
            onRenameConversation = { _, _ -> },
            onDeleteConversation = {},
            onCreateConversation = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatList â€“ Offline")
@Composable
private fun ChatListOfflinePreview() {
    AppTheme(dynamicColor = false) {
        ChatListScreenContent(
            uiState = ChatListUiState.Empty(isOffline = true),
            isOffline = true,
            searchQuery = "",
            searchResults = emptyList(),
            pagedItems = flowOf(PagingData.empty<ChatListItem>()).collectAsLazyPagingItems(),
            onSearchQueryChange = {},
            onConversationClick = {},
            onPinConversation = { _, _ -> },
            onRenameConversation = { _, _ -> },
            onDeleteConversation = {},
            onCreateConversation = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatList â€“ Loading")
@Composable
private fun ChatListLoadingPreview() {
    AppTheme(dynamicColor = false) {
        ChatListScreenContent(
            uiState = ChatListUiState.Loading,
            isOffline = false,
            searchQuery = "",
            searchResults = emptyList(),
            pagedItems = flowOf(PagingData.empty<ChatListItem>()).collectAsLazyPagingItems(),
            onSearchQueryChange = {},
            onConversationClick = {},
            onPinConversation = { _, _ -> },
            onRenameConversation = { _, _ -> },
            onDeleteConversation = {},
            onCreateConversation = {}
        )
    }
}

@Preview(showBackground = true, name = "ChatList â€“ Error")
@Composable
private fun ChatListErrorPreview() {
    AppTheme(dynamicColor = false) {
        ChatListScreenContent(
            uiState = ChatListUiState.Error("Unable to load conversations."),
            isOffline = false,
            searchQuery = "",
            searchResults = emptyList(),
            pagedItems = flowOf(PagingData.empty<ChatListItem>()).collectAsLazyPagingItems(),
            onSearchQueryChange = {},
            onConversationClick = {},
            onPinConversation = { _, _ -> },
            onRenameConversation = { _, _ -> },
            onDeleteConversation = {},
            onCreateConversation = {}
        )
    }
}
