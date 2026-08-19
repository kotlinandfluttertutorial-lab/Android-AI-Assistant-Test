/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : MemoryListScreen.kt
 * Purpose    : Standalone Memory List screen — displays all stored memories for a user
 *              with per-item edit and delete actions, and an empty state.
 *
 * Architecture Layer : Feature (feature-profile)
 * Pattern Used       : Jetpack Compose Screen
 *
 * Requirements: 7.3, 7.4
 * ============================================================
 */
package com.aiassistant.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.outlined.MemoryAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Memory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─── Date formatter ───────────────────────────────────────────────────────────

private val memoryListDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd, yyyy").withZone(ZoneId.systemDefault())

// ─── Route ────────────────────────────────────────────────────────────────────

/**
 * Route constant for the Memory List screen.
 */
object MemoryListRoute {
    const val SCREEN = "memory_list"
}

// ─── Navigation graph extension ───────────────────────────────────────────────

/**
 * Embeds the Memory List screen into the caller's [NavGraphBuilder].
 *
 * Shares the same [ProfileViewModel] from the parent back-stack entry so memory
 * edits and deletes are reflected immediately when navigating back to ProfileScreen.
 *
 * @param navController  The root [NavHostController].
 * @param onNavigateUp   Called when the user taps the back arrow.
 */
fun NavGraphBuilder.memoryListNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    composable(route = MemoryListRoute.SCREEN) {
        val viewModel: ProfileViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        MemoryListScreen(
            uiState = uiState,
            onNavigateUp = onNavigateUp,
            onStartEditMemory = { memory -> viewModel.startEditMemory(memory) },
            onUpdateEditContent = { content -> viewModel.updateEditContent(content) },
            onCancelEdit = { viewModel.cancelEditMemory() },
            onSaveEdit = { viewModel.saveMemoryEdit() },
            onDeleteMemory = { memoryId -> viewModel.deleteMemory(memoryId) },
            onDismissError = { viewModel.dismissError() },
            onRetry = { viewModel.retry() }
        )
    }
}

// ─── Root screen ──────────────────────────────────────────────────────────────

/**
 * Standalone Memory List screen.
 *
 * Displays all stored memories with per-item edit and delete controls.
 * All state mutations are delegated to callbacks; composables hold zero state.
 *
 * Requirements: 7.3, 7.4
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryListScreen(
    uiState: ProfileUiState,
    onNavigateUp: () -> Unit,
    onStartEditMemory: (Memory) -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }

    val errorMessage = (uiState as? ProfileUiState.Content)?.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(message = errorMessage)
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memories") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back from Memory List"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.semantics {
                        contentDescription = "Error notification: ${data.visuals.message}"
                    }
                )
            }
        }
    ) { innerPadding ->
        when (uiState) {
            is ProfileUiState.Loading -> MemoryListLoadingContent(
                modifier = Modifier.padding(innerPadding)
            )

            is ProfileUiState.Content -> {
                MemoryListContent(
                    memories = uiState.memories,
                    deletingMemoryIds = uiState.deletingMemoryIds,
                    onStartEditMemory = onStartEditMemory,
                    onDeleteMemory = onDeleteMemory,
                    modifier = Modifier.padding(innerPadding)
                )

                // Edit dialog overlay
                if (uiState.editingMemory != null) {
                    MemoryEditDialog(
                        memory = uiState.editingMemory,
                        editContent = uiState.editContent,
                        isSaving = uiState.isSavingEdit,
                        onContentChange = onUpdateEditContent,
                        onConfirm = onSaveEdit,
                        onDismiss = onCancelEdit
                    )
                }
            }

            is ProfileUiState.Error -> MemoryListErrorContent(
                message = uiState.message,
                onRetry = onRetry,
                modifier = Modifier.padding(innerPadding)
            )
        }
    }
}

// ─── Loading ──────────────────────────────────────────────────────────────────

@Composable
private fun MemoryListLoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Loading memories" }
        )
    }
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun MemoryListErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Unable to load memories",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(spacing.md))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry loading memories" }
        ) { Text("Retry") }
    }
}

// ─── Memory list ──────────────────────────────────────────────────────────────

/**
 * Scrollable list of all memories, or an empty state illustration when there are none.
 * Requirement 7.3: display, edit, and delete individual memories.
 */
@Composable
private fun MemoryListContent(
    memories: List<Memory>,
    deletingMemoryIds: Set<String>,
    onStartEditMemory: (Memory) -> Unit,
    onDeleteMemory: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing

    if (memories.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize().padding(spacing.md),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.MemoryAlt,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(spacing.md))
                Text(
                    text = "No memories yet",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = "Memories are created automatically as you chat.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics {
                        contentDescription = "No memories stored"
                    }
                )
            }
        }
    } else {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = spacing.md,
                vertical = spacing.sm
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            itemsIndexed(
                items = memories,
                key = { _, memory -> memory.id }
            ) { index, memory ->
                MemoryListItem(
                    memory = memory,
                    isDeleting = memory.id in deletingMemoryIds,
                    onEdit = { onStartEditMemory(memory) },
                    onDelete = { onDeleteMemory(memory.id) }
                )
                if (index < memories.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = spacing.xs))
                }
            }
        }
    }
}

// ─── Memory list item ─────────────────────────────────────────────────────────

/**
 * A single memory row with type chip, content preview, date, and edit/delete actions.
 * Requirement 7.3: edit and delete individual memories.
 */
@Composable
private fun MemoryListItem(
    memory: Memory,
    isDeleting: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Memory: ${memory.content}. Type: ${memoryTypeLabel(memory.memoryType)}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AssistChip(
                onClick = {},
                label = {
                    Text(
                        text = memoryTypeLabel(memory.memoryType),
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Memory type: ${memoryTypeLabel(memory.memoryType)}"
                }
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = memory.content,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(spacing.xs))
            Text(
                text = memoryListDateFormatter.format(Instant.ofEpochMilli(memory.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isDeleting) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(24.dp)
                    .semantics { contentDescription = "Deleting memory" }
            )
        } else {
            IconButton(
                onClick = onEdit,
                modifier = Modifier.semantics {
                    contentDescription = "Edit memory: ${memory.content}"
                }
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "Delete memory: ${memory.content}"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Edit memory dialog ───────────────────────────────────────────────────────

/**
 * Dialog for editing the text content of a single memory.
 * Requirement 7.3: edit individual memory content.
 */
@Composable
private fun MemoryEditDialog(
    memory: Memory,
    editContent: String,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Memory") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                Text(
                    text = "Type: ${memoryTypeLabel(memory.memoryType)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = editContent,
                    onValueChange = onContentChange,
                    label = { Text("Memory content") },
                    enabled = !isSaving,
                    minLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Edit memory content" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSaving && editContent.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Save memory edit" }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.semantics { contentDescription = "Cancel memory edit" }
            ) { Text("Cancel") }
        }
    )
}
