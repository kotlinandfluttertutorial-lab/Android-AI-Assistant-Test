/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-history
 * File       : HistoryListScreen.kt
 * Purpose    : Paginated conversation history list with inline search, filter
 *              chips (All / Pinned), date-grouped sections, per-item actions
 *              (pin/rename/export/delete), offline banner, and a new-chat FAB.
 *
 *              Phase D updates (Stitch alignment):
 *              - Inline SearchInputBar (replaces search-icon → separate screen)
 *              - FilterChipRow: All Chats / Pinned
 *              - "Chat History" title (was "History")
 *              - FloatingActionButton → new conversation
 *              - StitchType.sectionLabel for date-group headers
 *              - Improved empty state (emoji + title + subtitle)
 *
 *              All existing callbacks, paging logic, dialogs, and exports are
 *              unchanged.
 *
 * Architecture Layer : Feature (feature-history) — Compose UI layer.
 * Requirements       : 11.1, 11.2, 11.5, 11.6, 10.4, 17.6, 23.1, 23.4
 * ============================================================
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
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
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.components.SearchInputBar
import com.aiassistant.core.ui.components.SearchInputBarMode
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import com.aiassistant.domain.model.ExportFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ── Screen entry point ────────────────────────────────────────────────────────

/**
 * Stateless history list screen.
 *
 * @param uiState               Current [HistoryUiState].
 * @param pagedItems            Paging 3 items for the [LazyColumn].
 * @param isOffline             Whether the device has no network connectivity.
 * @param onSearchClick         Opens full-text search (phone: pushes SearchHistoryScreen).
 * @param onConversationClick   Tapping a row opens the conversation.
 * @param onPinConversation     Called with (id, newPinnedState) to pin/unpin.
 * @param onRenameConversation  Called with (id, newTitle) after rename dialog confirms.
 * @param onDeleteConversation  Called with conversation id after delete confirmation.
 * @param onExportConversation  Called with (id, format) for export actions.
 * @param onDismissExportResult Called after the export-success snackbar is dismissed.
 * @param onNewChat             Navigates to a new empty conversation.
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
    onDismissExportResult: () -> Unit = {},
    onNewChat: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Stitch: local filter + search state (no extra ViewModel needed for Phase D)
    var showPinnedOnly by rememberSaveable { mutableStateOf(false) }
    var inlineSearch   by rememberSaveable { mutableStateOf("") }

    val exportSuccess = uiState as? HistoryUiState.ExportSuccess
    LaunchedEffect(exportSuccess) {
        if (exportSuccess != null) {
            val label = when (exportSuccess.format) {
                ExportFormat.MARKDOWN -> "Markdown"
                ExportFormat.PDF      -> "PDF"
            }
            snackbarHostState.showSnackbar("Exported as $label successfully.")
            onDismissExportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat History") },
                actions = {
                    IconButton(
                        onClick = onSearchClick,
                        modifier = Modifier.semantics {
                            contentDescription = "Full-text search conversation history"
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    }
                }
            )
        },
        // Stitch: FAB navigates to a new empty conversation
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewChat,
                modifier = Modifier.semantics { contentDescription = "New conversation" },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
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
            if (isOffline) {
                OfflineBanner(modifier = Modifier.fillMaxWidth())
            }
            if (uiState is HistoryUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }
            if (uiState is HistoryUiState.Exporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Exporting conversation\u2026" }
                )
            }

            // ── Stitch: inline search bar ─────────────────────────────────────
            SearchInputBar(
                value = inlineSearch,
                onValueChange = { inlineSearch = it },
                placeholder = "Search conversations\u2026",
                mode = SearchInputBarMode.EDITABLE,
                onClear = { inlineSearch = "" },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical   = MaterialTheme.spacing.sm,
                    ),
            )

            // ── Stitch: All Chats / Pinned filter chips ───────────────────────
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start  = MaterialTheme.spacing.md,
                        end    = MaterialTheme.spacing.md,
                        bottom = MaterialTheme.spacing.xs,
                    ),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
            ) {
                item {
                    FilterChip(
                        selected = !showPinnedOnly,
                        onClick  = { showPinnedOnly = false },
                        label    = { Text("All Chats") },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor     = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Show all chats" },
                    )
                }
                item {
                    FilterChip(
                        selected = showPinnedOnly,
                        onClick  = { showPinnedOnly = true },
                        label    = { Text("\uD83D\uDCCC Pinned") },
                        colors   = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor     = MaterialTheme.colorScheme.onPrimary,
                        ),
                        modifier = Modifier.semantics { contentDescription = "Show pinned chats only" },
                    )
                }
            }

            // ── Initial loading ───────────────────────────────────────────────
            if (uiState is HistoryUiState.Loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics { contentDescription = "Loading conversation history" }
                    )
                }
                return@Column
            }

            // ── Empty state ───────────────────────────────────────────────────
            if (uiState is HistoryUiState.HistoryList &&
                uiState.groupedConversations.isEmpty &&
                pagedItems.itemCount == 0 &&
                pagedItems.loadState.refresh !is LoadState.Loading
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "\uD83D\uDCAC",
                            style = MaterialTheme.typography.displaySmall,
                        )
                        Text(
                            text = "No chats found",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.sm),
                        )
                        Text(
                            text = if (inlineSearch.isNotBlank()) "Try a different search term"
                                   else "Start your first conversation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
                        )
                    }
                }
                return@Column
            }

            // ── Paged list ────────────────────────────────────────────────────
            PagedHistoryList(
                pagedItems           = pagedItems,
                showPinnedOnly       = showPinnedOnly,
                inlineSearch         = inlineSearch,
                onConversationClick  = onConversationClick,
                onPinConversation    = onPinConversation,
                onRenameConversation = onRenameConversation,
                onDeleteConversation = onDeleteConversation,
                onExportConversation = onExportConversation,
            )
        }
    }
}

// ── Paged list ────────────────────────────────────────────────────────────────

/**
 * [LazyColumn] backed by [LazyPagingItems].
 * Client-side pin filter and inline search are applied before rendering.
 */
@Composable
private fun PagedHistoryList(
    pagedItems: LazyPagingItems<HistoryListItem>,
    showPinnedOnly: Boolean = false,
    inlineSearch: String = "",
    onConversationClick: (String) -> Unit,
    onPinConversation: (String, Boolean) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onExportConversation: (String, ExportFormat) -> Unit,
) {
    var showRenameDialog  by rememberSaveable { mutableStateOf(false) }
    var showDeleteDialog  by rememberSaveable { mutableStateOf(false) }
    var targetConversation by remember { mutableStateOf<Conversation?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = MaterialTheme.spacing.xl),
    ) {
        items(
            count = pagedItems.itemCount,
            key = { index ->
                when (val item = pagedItems.peek(index)) {
                    is HistoryListItem.Header           -> "header:${item.label}"
                    is HistoryListItem.ConversationItem -> "conv:${item.conversation.id}"
                    null                               -> "null:$index"
                }
            }
        ) { index ->
            when (val item = pagedItems[index]) {
                is HistoryListItem.Header -> HistorySectionHeader(label = item.label)
                is HistoryListItem.ConversationItem -> {
                    // Client-side pin filter
                    val conv = item.conversation
                    if (showPinnedOnly && !conv.isPinned) return@items
                    // Client-side inline search filter
                    if (inlineSearch.isNotBlank() &&
                        !conv.title.contains(inlineSearch, ignoreCase = true)
                    ) return@items

                    HistoryConversationRow(
                        conversation = conv,
                        onClick      = { onConversationClick(conv.id) },
                        onPinClick   = { onPinConversation(conv.id, !conv.isPinned) },
                        onRenameClick = {
                            targetConversation = conv
                            showRenameDialog = true
                        },
                        onDeleteClick = {
                            targetConversation = conv
                            showDeleteDialog = true
                        },
                        onExportClick = { format -> onExportConversation(conv.id, format) },
                    )
                }
                null -> Unit
            }
        }

        if (pagedItems.loadState.append is LoadState.Loading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(MaterialTheme.spacing.md),
                    contentAlignment = Alignment.Center,
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

    if (showRenameDialog && targetConversation != null) {
        RenameDialog(
            currentTitle = targetConversation!!.title,
            onConfirm = { newTitle ->
                onRenameConversation(targetConversation!!.id, newTitle)
                showRenameDialog   = false
                targetConversation = null
            },
            onDismiss = {
                showRenameDialog   = false
                targetConversation = null
            }
        )
    }

    if (showDeleteDialog && targetConversation != null) {
        DeleteDialog(
            conversationTitle = targetConversation!!.title,
            onConfirm = {
                onDeleteConversation(targetConversation!!.id)
                showDeleteDialog   = false
                targetConversation = null
            },
            onDismiss = {
                showDeleteDialog   = false
                targetConversation = null
            }
        )
    }
}

// ── Row components ────────────────────────────────────────────────────────────

/**
 * Non-interactive date-group section header (e.g. "Today", "Yesterday").
 * Uses [StitchType.sectionLabel] — 12 sp, Medium weight, uppercase letter-spacing.
 */
@Composable
private fun HistorySectionHeader(label: String) {
    Text(
        text     = label.uppercase(),
        style    = AppType.sectionLabel,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = MaterialTheme.spacing.md,
                vertical   = MaterialTheme.spacing.xs,
            )
            .semantics { contentDescription = "$label section" }
    )
}

/**
 * Tappable conversation row with title, timestamp, provider badge, pin icon, and
 * a trailing overflow [DropdownMenu] (Pin / Rename / Export Markdown / Export PDF / Delete).
 */
@Composable
private fun HistoryConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onRenameClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onExportClick: (ExportFormat) -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = {
            Text(
                text     = conversation.title,
                style    = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
            )
        },
        supportingContent = {
            Row(
                verticalAlignment    = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                Text(
                    text  = conversation.updatedAt.formatRelative(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Provider badge — not color-only (Requirement 23.4)
                AssistChip(
                    onClick = {},
                    label   = {
                        Text(conversation.provider, style = MaterialTheme.typography.labelSmall)
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
                    imageVector        = Icons.Filled.PushPin,
                    contentDescription = "Pinned",
                    tint               = MaterialTheme.colorScheme.primary,
                    modifier           = Modifier.size(MaterialTheme.spacing.md),
                )
            }
        } else null,
        trailingContent = {
            Box {
                IconButton(
                    onClick  = { menuExpanded = true },
                    modifier = Modifier.semantics {
                        contentDescription = "More options for ${conversation.title}"
                    }
                ) {
                    Icon(imageVector = Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(
                    expanded          = menuExpanded,
                    onDismissRequest  = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text    = { Text(if (conversation.isPinned) "Unpin" else "Pin") },
                        onClick = { menuExpanded = false; onPinClick() },
                        modifier = Modifier.semantics {
                            contentDescription = if (conversation.isPinned) "Unpin conversation"
                                                 else "Pin conversation"
                        }
                    )
                    DropdownMenuItem(
                        text    = { Text("Rename") },
                        onClick = { menuExpanded = false; onRenameClick() },
                        modifier = Modifier.semantics { contentDescription = "Rename conversation" }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                        text    = { Text("Export as Markdown") },
                        onClick = { menuExpanded = false; onExportClick(ExportFormat.MARKDOWN) },
                        modifier = Modifier.semantics { contentDescription = "Export conversation as Markdown" }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Filled.IosShare, null) },
                        text    = { Text("Export as PDF") },
                        onClick = { menuExpanded = false; onExportClick(ExportFormat.PDF) },
                        modifier = Modifier.semantics { contentDescription = "Export conversation as PDF" }
                    )
                    DropdownMenuItem(
                        text    = {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        },
                        onClick = { menuExpanded = false; onDeleteClick() },
                        modifier = Modifier.semantics { contentDescription = "Delete conversation" }
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
        colors = ListItemDefaults.colors(),
    )
}

// ── Dialogs ───────────────────────────────────────────────────────────────────

@Composable
private fun RenameDialog(
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
                value         = newTitle,
                onValueChange = { newTitle = it },
                label         = { Text("New title") },
                singleLine    = true,
                modifier      = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "New conversation title input" }
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (newTitle.isNotBlank()) onConfirm(newTitle) },
                modifier = Modifier.semantics { contentDescription = "Save new title" }
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel rename" }
            ) { Text("Cancel") }
        }
    )
}

@Composable
private fun DeleteDialog(
    conversationTitle: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Conversation") },
        text  = {
            Text(
                text  = "Delete \u201c$conversationTitle\u201d? This action cannot be undone.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            TextButton(
                onClick  = onConfirm,
                modifier = Modifier.semantics { contentDescription = "Confirm delete conversation" }
            ) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(
                onClick  = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel delete" }
            ) { Text("Cancel") }
        }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun Instant.formatRelative(): String =
    DateTimeFormatter
        .ofPattern("MMM d, yyyy h:mm a")
        .withZone(ZoneId.systemDefault())
        .format(this)
