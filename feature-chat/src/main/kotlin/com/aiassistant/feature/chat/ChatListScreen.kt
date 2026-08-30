/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatListScreen.kt
 * Purpose    : Redesigned conversation list screen (Task 50.4) with M3
 *              SearchBar replacing OutlinedTextField, SwipeRevealLayout
 *              on each row exposing Pin / Delete actions, and surfaceTonal1
 *              card backgrounds.
 *
 * Architecture Layer : Feature (feature-chat) — Compose UI layer.
 *                      State driven by ChatViewModel.
 *
 * Dependencies       : core-ui (SwipeRevealLayout, OfflineBanner, AppColors,
 *                      AppType, spacing), domain models.
 *
 * Requirements       : 11.1, 11.3, 11.5, 10.4, 17.6
 * ============================================================
 */
package com.aiassistant.feature.chat

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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DockedSearchBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.components.SwipeRevealLayout
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Screen entry point ────────────────────────────────────────────────────────

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
        onCreateConversation = { title -> viewModel.createConversation(title, "openai") },
    )
}

// ── Stateless screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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
    onCreateConversation: (String) -> Unit,
) {
    var showNewConversationDialog by rememberSaveable { mutableStateOf(false) }
    var showRenameDialog by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var targetConversation by remember { mutableStateOf<Conversation?>(null) }

    // SearchBar active state
    var searchActive by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewConversationDialog = true },
                modifier = Modifier.semantics { contentDescription = "New conversation" },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }

            // ── M3 DockedSearchBar ─────────────────────────────────────────
            DockedSearchBar(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                onSearch = { /* FTS fires automatically via ViewModel */ },
                active = searchActive,
                onActiveChange = { searchActive = it },
                placeholder = { Text("Search conversations…") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.xs,
                    )
                    .semantics { contentDescription = "Search conversations" },
            ) {
                // Search results rendered inside the SearchBar's expanded overlay
                if (searchResults.isEmpty() && searchQuery.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(MaterialTheme.spacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "No conversations match your search.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(
                            horizontal = MaterialTheme.spacing.screenEdge,
                            vertical = MaterialTheme.spacing.xs,
                        ),
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    ) {
                        items(
                            count = searchResults.size,
                            key = { searchResults[it].id },
                        ) { idx ->
                            val conv = searchResults[idx]
                            ConversationCard(
                                conversation = conv,
                                onClick = {
                                    searchActive = false
                                    onConversationClick(conv.id)
                                },
                                onPinClick = { onPinConversation(conv.id, !conv.isPinned) },
                                onRenameClick = {
                                    targetConversation = conv; showRenameDialog = true
                                },
                                onDeleteClick = {
                                    targetConversation = conv; showDeleteDialog = true
                                },
                            )
                        }
                    }
                }
            }

            // ── Body ───────────────────────────────────────────────────────
            when {
                uiState is ChatListUiState.Loading -> LoadingContent()
                uiState is ChatListUiState.Error -> ErrorContent(message = uiState.message)
                uiState is ChatListUiState.Empty -> EmptyContent()
                else -> PagedConversationList(
                    pagedItems = pagedItems,
                    onConversationClick = onConversationClick,
                    onPinConversation = onPinConversation,
                    onRenameRequest = { conv ->
                        targetConversation = conv; showRenameDialog = true
                    },
                    onDeleteRequest = { conv ->
                        targetConversation = conv; showDeleteDialog = true
                    },
                )
            }
        }
    }

    if (showNewConversationDialog) {
        NewConversationDialog(
            onConfirm = { title -> onCreateConversation(title); showNewConversationDialog = false },
            onDismiss = { showNewConversationDialog = false },
        )
    }
    if (showRenameDialog && targetConversation != null) {
        RenameConversationDialog(
            currentTitle = targetConversation!!.title,
            onConfirm = { newTitle ->
                onRenameConversation(targetConversation!!.id, newTitle)
                showRenameDialog = false; targetConversation = null
            },
            onDismiss = { showRenameDialog = false; targetConversation = null },
        )
    }
    if (showDeleteDialog && targetConversation != null) {
        DeleteConversationDialog(
            conversationTitle = targetConversation!!.title,
            onConfirm = {
                onDeleteConversation(targetConversation!!.id)
                showDeleteDialog = false; targetConversation = null
            },
            onDismiss = { showDeleteDialog = false; targetConversation = null },
        )
    }
}

// ── Paged list ────────────────────────────────────────────────────────────────

@Composable
private fun PagedConversationList(
    pagedItems: LazyPagingItems<ChatListItem>,
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameRequest: (Conversation) -> Unit,
    onDeleteRequest: (Conversation) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            horizontal = MaterialTheme.spacing.screenEdge,
            vertical = MaterialTheme.spacing.xs,
            bottom = MaterialTheme.spacing.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
    ) {
        items(
            count = pagedItems.itemCount,
            key = { idx ->
                when (val item = pagedItems.peek(idx)) {
                    is ChatListItem.Header -> "header:${item.label}"
                    is ChatListItem.ConversationItem -> "conv:${item.conversation.id}"
                    null -> "null:$idx"
                }
            },
        ) { idx ->
            when (val item = pagedItems[idx]) {
                is ChatListItem.Header -> SectionHeader(label = item.label)
                is ChatListItem.ConversationItem -> ConversationCard(
                    conversation = item.conversation,
                    onClick = { onConversationClick(item.conversation.id) },
                    onPinClick = {
                        onPinConversation(item.conversation.id, !item.conversation.isPinned)
                    },
                    onRenameClick = { onRenameRequest(item.conversation) },
                    onDeleteClick = { onDeleteRequest(item.conversation) },
                )
                null -> Unit
            }
        }
        if (pagedItems.loadState.append is LoadState.Loading) {
            item {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.md),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(MaterialTheme.spacing.lg))
                }
            }
        }
    }
}

// ── Conversation card with SwipeRevealLayout ──────────────────────────────────

@Composable
private fun ConversationCard(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light
    var menuExpanded by remember { mutableStateOf(false) }

    SwipeRevealLayout(
        modifier = Modifier.fillMaxWidth(),
        revealWidth = 128.dp,
        actions = {
            // Pin / Unpin action
            IconButton(
                onClick = onPinClick,
                modifier = Modifier.semantics {
                    contentDescription = if (conversation.isPinned) "Unpin conversation" else "Pin conversation"
                },
            ) {
                Icon(
                    imageVector = if (conversation.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            // Delete action
            IconButton(
                onClick = onDeleteClick,
                modifier = Modifier.semantics { contentDescription = "Delete conversation" },
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        },
    ) {
        ElevatedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = buildString {
                        append(conversation.title)
                        if (conversation.isPinned) append(", pinned")
                        append(", updated ${conversation.updatedAt.formatRelative()}")
                    }
                },
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
            colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (conversation.isPinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(14.dp)
                            .padding(end = 4.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = conversation.updatedAt.formatRelative(),
                        style = AppType.chatTimestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.semantics {
                            contentDescription = "More options for ${conversation.title}"
                        },
                    ) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                            onClick = { menuExpanded = false; onPinClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuExpanded = false; onRenameClick() },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = { menuExpanded = false; onDeleteClick() },
                        )
                    }
                }
            }
        }
    }
}

// ── Supporting composables ────────────────────────────────────────────────────

@Composable
private fun SectionHeader(label: String) {
    Text(
        text = label,
        style = AppType.sectionLabel,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.screenEdge,
                vertical = MaterialTheme.spacing.xs,
            )
            .semantics { contentDescription = "$label section" },
    )
}

@Composable
private fun LoadingContent() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading conversations" },
        )
    }
}

@Composable
private fun ErrorContent(message: String) {
    Box(
        Modifier.fillMaxSize().padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun EmptyContent() {
    Box(
        Modifier.fillMaxSize().padding(MaterialTheme.spacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No conversations yet", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Text("Tap + to start a new conversation.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Dialogs (unchanged logic, kept inline) ────────────────────────────────────

@Composable
private fun NewConversationDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Conversation") },
        text = {
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("Title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
                    .semantics { contentDescription = "Conversation title input" },
            )
        },
        confirmButton = {
            TextButton(onClick = { if (title.isNotBlank()) onConfirm(title) }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameConversationDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var newTitle by rememberSaveable(currentTitle) { mutableStateOf(currentTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Conversation") },
        text = {
            OutlinedTextField(
                value = newTitle, onValueChange = { newTitle = it },
                label = { Text("New title") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { if (newTitle.isNotBlank()) onConfirm(newTitle) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DeleteConversationDialog(
    conversationTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Conversation") },
        text = { Text("Delete \"$conversationTitle\"? This cannot be undone.") },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ── Helper ────────────────────────────────────────────────────────────────────

private fun Instant.formatRelative(): String =
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(this)
