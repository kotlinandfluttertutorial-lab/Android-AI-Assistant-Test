/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesListScreen.kt
 * Purpose    : Compose UI screen for the NotesList feature
 *
 * Architecture Layer : Feature (feature-notes)
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
 * Module     : feature-notes
 * File       : NotesListScreen.kt
 * Purpose    : Compose UI screen for the NotesList feature
 *
 * Architecture Layer : Feature (feature-notes)
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
 * NotesListScreen.kt
 *
 * Purpose: Stateless Compose screen displaying the list of notes with tag filtering,
 *          note cards with sync status indicators, swipe-to-delete, and a FAB for
 *          creating new notes.
 * Architecture: feature-notes â€” Compose UI layer; stateless composable driven by
 *               NotesUiState from NotesViewModel.
 * Dependencies: core-ui (ErrorBanner, OfflineBanner, MarkdownText, MaterialTheme.spacing),
 *               domain (Note, SyncStatus), NotesUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Tag filter chips appear in a horizontally scrollable row at the top.
 * - Each note card shows the title, first 100 chars of content, tag chips, and sync
 *   status badge (icon-based, not color-only â€” requirement 23.4).
 * - Delete confirmation dialog prevents accidental deletion.
 * - All interactive elements carry contentDescriptions (requirement 28.3).
 *
 * Requirements: 13.1, 13.4, 13.5, 23.4, 28.3
 */
package com.aiassistant.feature.notes

import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SyncStatus

/**
 * Notes list screen composable.
 *
 * Renders the top-level notes list with tag filter chips, note cards, and a FAB for
 * creating new notes.
 *
 * @param uiState      Current state from [NotesViewModel].
 * @param onNoteClick  Invoked when the user taps a note card.
 * @param onNewNote    Invoked when the user taps the FAB to create a new note.
 * @param onDeleteNote Invoked with the note id when the user confirms note deletion.
 * @param onTagFilter  Invoked with the selected tag (or empty string for "All").
 * @param isOffline    When true, an [OfflineBanner] is shown at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    uiState: NotesUiState,
    onNoteClick: (Note) -> Unit,
    onNewNote: () -> Unit,
    onDeleteNote: (String) -> Unit,
    onTagFilter: (String) -> Unit,
    isOffline: Boolean = false
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNewNote,
                modifier = Modifier.semantics {
                    contentDescription = "Create new note"
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
            // â”€â”€ Offline / error banners â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isOffline) {
                OfflineBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            if (uiState is NotesUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // â”€â”€ Loading â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState is NotesUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading notes"
                        }
                    )
                }
                return@Column
            }

            if (uiState !is NotesUiState.NotesList) return@Column

            // â”€â”€ Tag filter chips â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            val allTagsWithAll = listOf("") + uiState.allTags
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = MaterialTheme.spacing.md),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                allTagsWithAll.forEach { tag ->
                    val label = tag.ifBlank { "All" }
                    val isSelected = uiState.selectedTag == tag
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTagFilter(tag) },
                        label = { Text(label) },
                        modifier = Modifier.semantics {
                            contentDescription = "Filter by tag: $label"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

            // â”€â”€ Notes list â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (uiState.notes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (uiState.selectedTag.isBlank()) {
                            "No notes yet. Tap + to create one."
                        } else {
                            "No notes tagged \"${uiState.selectedTag}\"."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm
                    ),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                ) {
                    items(uiState.notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = { onNoteClick(note) },
                            onDelete = { onDeleteNote(note.id) }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Note card â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A card displaying the summary of a single [note].
 *
 * Shows the title, first 100 characters of the content, tag chips, a sync status
 * badge, and a trailing delete button that triggers a confirmation dialog before
 * calling [onDelete].
 *
 * @param note     The note to display.
 * @param onClick  Invoked when the card body is tapped.
 * @param onDelete Invoked after the user confirms deletion.
 */
@Composable
private fun NoteCard(note: Note, onClick: () -> Unit, onDelete: () -> Unit) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete note?") },
            text = { Text("\"${note.title}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete note ${note.title}"
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete note"
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Note: ${note.title}" },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            // â”€â”€ Header row: title + sync badge + delete â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

                // Sync status badge (icon-based â€” not color-only, requirement 23.4)
                SyncStatusBadge(syncStatus = note.syncStatus)

                IconButton(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier.semantics {
                        contentDescription = "Delete note: ${note.title}"
                    }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // â”€â”€ Content preview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (note.content.isNotBlank()) {
                Text(
                    text = note.content.take(100),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }

            // â”€â”€ Tag chips â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (note.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    note.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.semantics {
                                contentDescription = "Tag: $tag"
                            }
                        )
                    }
                }
            }
        }
    }
}

// â”€â”€â”€ Sync status badge â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * A small icon that visually communicates the note's [syncStatus].
 *
 * Uses icons (not color alone) so users with color-blindness can distinguish states
 * (Requirement 23.4):
 * - SYNCED â†’ check circle (success)
 * - PENDING â†’ clock (in-progress)
 * - FAILED â†’ error outline (problem)
 */
@Composable
private fun SyncStatusBadge(syncStatus: SyncStatus) {
    val (icon, description, tint) = when (syncStatus) {
        SyncStatus.SYNCED -> Triple(
            Icons.Filled.CheckCircle,
            "Synced",
            MaterialTheme.colorScheme.primary
        )
        SyncStatus.PENDING -> Triple(
            Icons.Filled.Schedule,
            "Sync pending",
            MaterialTheme.colorScheme.tertiary
        )
        SyncStatus.FAILED -> Triple(
            Icons.Filled.ErrorOutline,
            "Sync failed",
            MaterialTheme.colorScheme.error
        )
    }

    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = tint,
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description }
    )
}
