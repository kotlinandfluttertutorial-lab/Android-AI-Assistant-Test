/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : ProfileScreen.kt
 * Purpose    : Profile and Memory Management screen — displays and edits user profile
 *              (name, avatar), shows stored memories list with per-item edit and delete,
 *              data export request, and account deletion flow with confirmation dialog.
 *
 * Architecture Layer : Feature (feature-profile)
 * Pattern Used       : Jetpack Compose Screen
 *
 * Requirements: 7.3, 7.4, 28.1, 28.2
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.User
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// ─── Date formatter ───────────────────────────────────────────────────────────

private val memoryDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM dd, yyyy").withZone(ZoneId.systemDefault())

// ─── Root screen ──────────────────────────────────────────────────────────────

/**
 * Root composable for the Profile and Memory Management screen.
 *
 * Shows user profile (name/avatar editing), stored memories list with per-item edit and
 * delete, data export request, and account deletion flow with confirmation dialog.
 *
 * All state mutations are delegated to callbacks; composables hold zero state.
 *
 * Requirements: 7.3, 7.4, 28.1, 28.2
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onNavigateUp: () -> Unit,
    // Navigation callback for the standalone memory list screen
    onNavigateToMemoryList: () -> Unit = {},
    // Memory edit callbacks
    onStartEditMemory: (Memory) -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    // Name edit callbacks
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    // Data export callback
    onRequestDataExport: () -> Unit,
    onDismissExportStatus: () -> Unit,
    // Account deletion callbacks
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    // General
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
        topBar = { ProfileTopBar(onNavigateUp = onNavigateUp) },
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
            is ProfileUiState.Loading ->
                ProfileLoadingContent(modifier = Modifier.padding(innerPadding))
            is ProfileUiState.Content ->
                ProfileContentBody(
                    state = uiState,
                    onNavigateToMemoryList = onNavigateToMemoryList,
                    onStartEditMemory = onStartEditMemory,
                    onUpdateEditContent = onUpdateEditContent,
                    onCancelEdit = onCancelEdit,
                    onSaveEdit = onSaveEdit,
                    onDeleteMemory = onDeleteMemory,
                    onStartEditName = onStartEditName,
                    onUpdateEditingName = onUpdateEditingName,
                    onCancelEditName = onCancelEditName,
                    onSaveDisplayName = onSaveDisplayName,
                    onRequestDataExport = onRequestDataExport,
                    onDismissExportStatus = onDismissExportStatus,
                    onInitiateAccountDeletion = onInitiateAccountDeletion,
                    onUpdateDeletionInput = onUpdateDeletionInput,
                    onCancelAccountDeletion = onCancelAccountDeletion,
                    onConfirmAccountDeletion = onConfirmAccountDeletion,
                    onDismissDeletionError = onDismissDeletionError,
                    modifier = Modifier.padding(innerPadding)
                )
            is ProfileUiState.Error ->
                ProfileErrorContent(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier.padding(innerPadding)
                )
        }
    }
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileTopBar(onNavigateUp: () -> Unit) {
    TopAppBar(
        title = { Text(text = "Profile & Settings") },
        navigationIcon = {
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.semantics {
                    contentDescription = "Navigate back from Profile"
                }
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
        }
    )
}

// ─── Loading ──────────────────────────────────────────────────────────────────

@Composable
private fun ProfileLoadingContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Loading profile" }
        )
    }
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun ProfileErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier.fillMaxSize().padding(spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Unable to load profile",
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
            modifier = Modifier.semantics { contentDescription = "Retry loading profile" }
        ) { Text(text = "Retry") }
    }
}

// ─── Main scrollable body ─────────────────────────────────────────────────────

@Composable
private fun ProfileContentBody(
    state: ProfileUiState.Content,
    onNavigateToMemoryList: () -> Unit,
    onStartEditMemory: (Memory) -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    onRequestDataExport: () -> Unit,
    onDismissExportStatus: () -> Unit,
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        UserProfileCard(
            user = state.user,
            isEditingName = state.isEditingName,
            editingName = state.editingName,
            isSavingName = state.isSavingName,
            onStartEditName = onStartEditName,
            onUpdateEditingName = onUpdateEditingName,
            onCancelEditName = onCancelEditName,
            onSaveDisplayName = onSaveDisplayName
        )

        SectionCard(title = "Memories") {
            MemoriesSection(
                memories = state.memories,
                deletingMemoryIds = state.deletingMemoryIds,
                onStartEditMemory = onStartEditMemory,
                onDeleteMemory = onDeleteMemory,
                onViewAll = onNavigateToMemoryList
            )
        }

        SectionCard(title = "Data Export") {
            DataExportSection(
                status = state.dataExportStatus,
                onRequestExport = onRequestDataExport,
                onDismissStatus = onDismissExportStatus
            )
        }

        SectionCard(title = "Account Management") {
            AccountDeletionSection(
                state = state.accountDeletionState,
                onInitiate = onInitiateAccountDeletion,
                onDismissError = onDismissDeletionError
            )
        }

        Spacer(modifier = Modifier.height(spacing.lg))
    }

    if (state.editingMemory != null) {
        EditMemoryDialog(
            memory = state.editingMemory,
            editContent = state.editContent,
            isSaving = state.isSavingEdit,
            onContentChange = onUpdateEditContent,
            onConfirm = onSaveEdit,
            onDismiss = onCancelEdit
        )
    }

    if (state.accountDeletionState is AccountDeletionState.Confirming ||
        state.accountDeletionState is AccountDeletionState.Deleting
    ) {
        AccountDeletionDialog(
            state = state.accountDeletionState,
            onUpdateInput = onUpdateDeletionInput,
            onConfirm = onConfirmAccountDeletion,
            onDismiss = onCancelAccountDeletion
        )
    }

    if (state.accountDeletionState is AccountDeletionState.Failed) {
        AccountDeletionErrorDialog(
            message = (state.accountDeletionState as AccountDeletionState.Failed).message,
            onDismiss = onDismissDeletionError
        )
    }
}

// ─── Section card ─────────────────────────────────────────────────────────────

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            content()
        }
    }
}

// ─── User Profile Card ────────────────────────────────────────────────────────

/**
 * Displays the user avatar, display name, and email with an option to edit the display
 * name inline. Requirement 28.1: display and edit user profile.
 */
@Composable
private fun UserProfileCard(
    user: User?,
    isEditingName: Boolean,
    editingName: String,
    isSavingName: Boolean,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(spacing.md),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Avatar ──────────────────────────────────────────────────────
            if (!user?.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = user?.avatarUrl,
                    contentDescription = "User avatar for ${user?.displayName}",
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = "Default avatar",
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(spacing.sm))

            // ── Display name (with edit toggle) ────────────────────────────
            if (isEditingName) {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = onUpdateEditingName,
                    label = { Text("Display name") },
                    singleLine = true,
                    enabled = !isSavingName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Display name input" }
                )
                Spacer(modifier = Modifier.height(spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onCancelEditName,
                        enabled = !isSavingName,
                        modifier = Modifier.semantics { contentDescription = "Cancel name edit" }
                    ) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Button(
                        onClick = onSaveDisplayName,
                        enabled = !isSavingName && editingName.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Save display name" }
                    ) {
                        if (isSavingName) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        } else {
                            Text("Save")
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user?.displayName ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    IconButton(
                        onClick = onStartEditName,
                        modifier = Modifier
                            .size(24.dp)
                            .semantics { contentDescription = "Edit display name" }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (!user?.email.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(spacing.xs))
                Text(
                    text = user?.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ─── Memories section ─────────────────────────────────────────────────────────

/**
 * Displays the list of stored memories with per-item edit and delete controls,
 * plus a "View all" button that navigates to the standalone [MemoryListScreen].
 * Requirement 7.3: display, edit, and delete individual memories.
 */
@Composable
private fun MemoriesSection(
    memories: List<Memory>,
    deletingMemoryIds: Set<String>,
    onStartEditMemory: (Memory) -> Unit,
    onDeleteMemory: (String) -> Unit,
    onViewAll: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    if (memories.isEmpty()) {
        Text(
            text = "No memories stored yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { contentDescription = "No memories stored" }
        )
    } else {
        // Show a preview of the first 3 memories; full list is on MemoryListScreen.
        val preview = memories.take(3)
        Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
            preview.forEachIndexed { index, memory ->
                MemoryItem(
                    memory = memory,
                    isDeleting = memory.id in deletingMemoryIds,
                    onEdit = { onStartEditMemory(memory) },
                    onDelete = { onDeleteMemory(memory.id) }
                )
                if (index < preview.lastIndex) {
                    HorizontalDivider()
                }
            }
            if (memories.size > 3) {
                TextButton(
                    onClick = onViewAll,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "View all ${memories.size} memories"
                        }
                ) {
                    Text("View all ${memories.size} memories")
                }
            }
        }
    }
}

/**
 * A single memory row with type chip, content preview, date, and edit/delete actions.
 */
@Composable
private fun MemoryItem(
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
            .padding(vertical = spacing.xs)
            .semantics(mergeDescendants = true) {
                contentDescription = "Memory: ${memory.content}. Type: ${memoryTypeLabel(memory.memoryType)}"
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
                text = memoryDateFormatter.format(Instant.ofEpochMilli(memory.createdAt)),
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
                modifier = Modifier.semantics { contentDescription = "Edit memory: ${memory.content}" }
            ) {
                Icon(imageVector = Icons.Default.Edit, contentDescription = null)
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics { contentDescription = "Delete memory: ${memory.content}" }
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
 * Dialog for editing the text content of a single memory. Requirement 7.3.
 */
@Composable
private fun EditMemoryDialog(
    memory: Memory,
    editContent: String,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Memory") },
        text = {
            Column {
                Text(
                    text = "Type: ${memoryTypeLabel(memory.memoryType)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
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

// ─── Data export section ──────────────────────────────────────────────────────

/**
 * Data export request section. Requirement 28.1: data export request with status display.
 *
 * Shows a button to initiate the export; once requested, shows a status banner explaining
 * the archive may take up to 24 hours to be prepared.
 */
@Composable
private fun DataExportSection(
    status: DataExportStatus,
    onRequestExport: () -> Unit,
    onDismissStatus: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Request a copy of all your data stored in this application.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(spacing.sm))

        when (status) {
            is DataExportStatus.Idle -> {
                FilledTonalButton(
                    onClick = onRequestExport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Request data export" }
                ) { Text("Request Data Export") }
            }
            is DataExportStatus.Requesting -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Export request in progress" }
                    )
                    Spacer(modifier = Modifier.width(spacing.sm))
                    Text(
                        text = "Sending request…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            is DataExportStatus.Requested -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(spacing.sm)) {
                        Text(
                            text = "Export requested",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Your data archive is being prepared and may take up to " +
                                "24 hours. You will be notified when it's ready.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        TextButton(
                            onClick = onDismissStatus,
                            modifier = Modifier.semantics { contentDescription = "Dismiss export status" }
                        ) { Text("Dismiss") }
                    }
                }
            }
            is DataExportStatus.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(spacing.sm)) {
                        Text(
                            text = "Export request failed",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = status.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row {
                            TextButton(
                                onClick = onDismissStatus,
                                modifier = Modifier.semantics { contentDescription = "Dismiss export error" }
                            ) { Text("Dismiss") }
                            TextButton(
                                onClick = onRequestExport,
                                modifier = Modifier.semantics { contentDescription = "Retry data export" }
                            ) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

// ─── Account deletion section ─────────────────────────────────────────────────

/**
 * Account deletion section displayed inside "Account Management" card.
 *
 * Shows a destructive "Delete Account" button. When [AccountDeletionState.Failed]
 * the error card is surfaced here so the user can see the current failure message.
 *
 * Requirement 28.2: account deletion flow with confirmation dialog.
 */
@Composable
private fun AccountDeletionSection(
    state: AccountDeletionState,
    onInitiate: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Permanently delete your account and all associated data. This action cannot be undone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(spacing.sm))

        when (state) {
            is AccountDeletionState.Idle -> {
                OutlinedButton(
                    onClick = onInitiate,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Delete account" }
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text("Delete Account")
                }
            }

            is AccountDeletionState.Confirming,
            is AccountDeletionState.Deleting -> {
                // Dialog is shown as an overlay — show disabled button as placeholder
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Account deletion in progress" }
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(spacing.xs))
                    Text("Processing…")
                }
            }

            is AccountDeletionState.Deleted -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Account deletion in progress. You will be signed out shortly.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(spacing.sm)
                    )
                }
            }

            is AccountDeletionState.Failed -> {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(spacing.sm)) {
                        Text(
                            text = "Account deletion failed",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Row {
                            TextButton(
                                onClick = onDismissError,
                                modifier = Modifier.semantics {
                                    contentDescription = "Dismiss deletion error"
                                }
                            ) { Text("Dismiss") }
                            TextButton(
                                onClick = onInitiate,
                                modifier = Modifier.semantics {
                                    contentDescription = "Retry account deletion"
                                }
                            ) { Text("Retry") }
                        }
                    }
                }
            }
        }
    }
}

// ─── Account deletion confirmation dialog ────────────────────────────────────

/**
 * Confirmation dialog for account deletion (Requirement 28.2).
 *
 * The user must type "DELETE" exactly (case-sensitive) to enable the confirm button.
 * While deletion is in-flight ([AccountDeletionState.Deleting]) the confirm button shows
 * a spinner and both controls are disabled.
 */
@Composable
private fun AccountDeletionDialog(
    state: AccountDeletionState,
    onUpdateInput: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    val isDeleting = state is AccountDeletionState.Deleting
    val confirmationInput = (state as? AccountDeletionState.Confirming)?.confirmationInput ?: ""
    val canConfirm = confirmationInput == ProfileViewModel.DELETION_CONFIRMATION_PHRASE

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = {
            Text(
                text = "Delete Account",
                color = MaterialTheme.colorScheme.error
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = "This will permanently delete your account and all associated " +
                        "data (conversations, memories, documents, notes). This action " +
                        "cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "To confirm, type \"${ProfileViewModel.DELETION_CONFIRMATION_PHRASE}\" below:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = confirmationInput,
                    onValueChange = onUpdateInput,
                    label = { Text("Type DELETE to confirm") },
                    enabled = !isDeleting,
                    singleLine = true,
                    isError = confirmationInput.isNotEmpty() && !canConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Account deletion confirmation input" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = canConfirm && !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.semantics { contentDescription = "Confirm account deletion" }
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        color = MaterialTheme.colorScheme.onError
                    )
                } else {
                    Text("Delete Account")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
                modifier = Modifier.semantics { contentDescription = "Cancel account deletion" }
            ) {
                Text("Cancel")
            }
        }
    )
}

// ─── Account deletion error dialog ───────────────────────────────────────────

/**
 * Error dialog displayed when [AccountDeletionState.Failed] is set.
 *
 * Offers "Dismiss" to clear the error state.
 */
@Composable
private fun AccountDeletionErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deletion Failed") },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss deletion error dialog" }
            ) {
                Text("OK")
            }
        }
    )
}
