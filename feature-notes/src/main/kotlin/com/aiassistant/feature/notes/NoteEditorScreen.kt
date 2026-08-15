/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NoteEditorScreen.kt
 * Purpose    : Compose UI screen for the NoteEditor feature
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
 * File       : NoteEditorScreen.kt
 * Purpose    : Compose UI screen for the NoteEditor feature
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
 * NoteEditorScreen.kt
 *
 * Purpose: Stateless Compose screen for creating and editing notes, with Markdown
 *          preview, tag management, and AI summarise / rewrite actions.
 * Architecture: feature-notes â€” Compose UI layer; stateless composable driven by
 *               NotesUiState from NotesViewModel.
 * Dependencies: core-ui (ErrorBanner, MarkdownText, MaterialTheme.spacing),
 *               domain (Note, SyncStatus), NotesUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Edit/Preview tabs let the user toggle between raw Markdown input and rendered preview.
 * - AI actions (Summarize, Rewrite) are placed in a dropdown menu to keep the toolbar lean.
 * - A full-screen loading overlay is shown during AI processing.
 * - AI result is shown in an AlertDialog with Apply / Dismiss choices.
 * - All interactive elements carry contentDescriptions (requirement 28.3).
 *
 * Requirements: 13.1, 13.2, 13.3, 28.3
 */
package com.aiassistant.feature.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.ContextSuggestion
import com.aiassistant.domain.model.Note
import com.aiassistant.domain.model.SuggestionType
import kotlinx.coroutines.delay

/**
 * Note editor screen composable.
 *
 * Handles [NotesUiState.NoteEditor], [NotesUiState.AiProcessing], and
 * [NotesUiState.AiResult] states. In [NotesUiState.Loading] or [NotesUiState.NotesList]
 * states, nothing meaningful is rendered â€” the navigation layer guards those transitions.
 *
 * @param uiState          Current state from [NotesViewModel].
 * @param onUpdateDraft    Invoked whenever title, content, or tags change in the editor.
 * @param onSave           Invoked with the current note when the user taps Save.
 * @param onBack           Invoked when the user taps the back arrow.
 * @param onTogglePreview  Invoked to toggle Markdown preview mode.
 * @param onSummarize      Invoked with the note id to request an AI summary.
 * @param onRewrite        Invoked with the note id to request an AI rewrite.
 * @param onApplyAiResult  Invoked with the AI result text to apply it to the note content.
 * @param onDismissAiResult Invoked to dismiss the AI result dialog without applying.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    uiState: NotesUiState,
    onUpdateDraft: (title: String, content: String, tags: List<String>) -> Unit,
    onSave: (Note) -> Unit,
    onBack: () -> Unit,
    onTogglePreview: () -> Unit,
    onSummarize: (noteId: String) -> Unit,
    onRewrite: (noteId: String) -> Unit,
    onApplyAiResult: (String) -> Unit,
    onDismissAiResult: () -> Unit,
    onIdleAfter5Seconds: (noteId: String, content: String) -> Unit = { _, _ -> },
    onSuggestionTapped: (ContextSuggestion) -> Unit = {},
    onSuggestionDismissed: (SuggestionType) -> Unit = {}
) {
    // Resolve the note and flags from the current state
    val editorState = when (uiState) {
        is NotesUiState.NoteEditor -> uiState
        is NotesUiState.AiProcessing -> NotesUiState.NoteEditor(uiState.note, false)
        is NotesUiState.AiResult -> NotesUiState.NoteEditor(uiState.note, false)
        else -> null
    }

    val note = editorState?.note
    val isSaving = (uiState as? NotesUiState.NoteEditor)?.isSaving == true
    val isNew = (uiState as? NotesUiState.NoteEditor)?.isNew == true
    val previewMode = (uiState as? NotesUiState.NoteEditor)?.previewMode == true
    val isAiProcessing = uiState is NotesUiState.AiProcessing
    val aiResult = uiState as? NotesUiState.AiResult
    val contextSuggestions = (uiState as? NotesUiState.NoteEditor)?.contextSuggestions ?: emptyList()

    // ── 5-second idle debounce for context suggestions (Requirement 33.1) ─────
    // Re-triggered whenever note content changes. After 5 seconds with no further
    // changes, fires onIdleAfter5Seconds. No loading indicator is shown (Req 33.6).
    LaunchedEffect(note?.content, note?.id) {
        if (note != null && note.content.isNotBlank()) {
            delay(5_000L)
            onIdleAfter5Seconds(note.id, note.content)
        }
    }

    // Local tag input state
    var tagInput by remember { mutableStateOf("") }
    var menuExpanded by remember { mutableStateOf(false) }

    // â”€â”€ AI result dialog â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    if (aiResult != null) {
        val operationLabel = when (aiResult.operation) {
            AiOperation.SUMMARIZE -> "Summary"
            AiOperation.REWRITE -> "Rewritten Note"
        }
        AlertDialog(
            onDismissRequest = onDismissAiResult,
            title = { Text("AI $operationLabel") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    MarkdownText(
                        markdown = aiResult.result,
                        contentDescription = "AI generated $operationLabel"
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onApplyAiResult(aiResult.result) },
                    modifier = Modifier.semantics {
                        contentDescription = "Apply AI result to note"
                    }
                ) {
                    Text("Apply")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissAiResult,
                    modifier = Modifier.semantics {
                        contentDescription = "Dismiss AI result"
                    }
                ) {
                    Text("Dismiss")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (isNew) "New Note" else "Edit Note")
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    // Preview toggle
                    IconButton(
                        onClick = onTogglePreview,
                        enabled = !isSaving && !isAiProcessing,
                        modifier = Modifier.semantics {
                            contentDescription = if (previewMode) {
                                "Switch to edit mode"
                            } else {
                                "Switch to preview mode"
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Preview,
                            contentDescription = null
                        )
                    }

                    // Save button
                    IconButton(
                        onClick = { note?.let { onSave(it) } },
                        enabled = !isSaving && !isAiProcessing && note != null,
                        modifier = Modifier.semantics {
                            contentDescription = "Save note"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = null
                        )
                    }

                    // AI actions dropdown
                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            enabled = !isSaving && !isAiProcessing && note != null,
                            modifier = Modifier.semantics {
                                contentDescription = "More actions"
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
                                text = { Text("Summarize") },
                                onClick = {
                                    menuExpanded = false
                                    note?.let { onSummarize(it.id) }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Summarize note with AI"
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rewrite") },
                                onClick = {
                                    menuExpanded = false
                                    note?.let { onRewrite(it.id) }
                                },
                                modifier = Modifier.semantics {
                                    contentDescription = "Rewrite note with AI"
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (note != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(MaterialTheme.spacing.md),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
                ) {
                    // â”€â”€ Title field â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    OutlinedTextField(
                        value = note.title,
                        onValueChange = { newTitle ->
                            onUpdateDraft(newTitle, note.content, note.tags)
                        },
                        label = { Text("Title") },
                        singleLine = true,
                        enabled = !isSaving && !isAiProcessing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Note title input" }
                    )

                    // â”€â”€ Tag input row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Column(
                        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                        ) {
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it },
                                label = { Text("Add tag") },
                                singleLine = true,
                                enabled = !isSaving && !isAiProcessing,
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics { contentDescription = "Tag input field" }
                            )
                            IconButton(
                                onClick = {
                                    val trimmed = tagInput.trim()
                                    if (trimmed.isNotEmpty() && trimmed !in note.tags) {
                                        onUpdateDraft(
                                            note.title,
                                            note.content,
                                            note.tags + trimmed
                                        )
                                        tagInput = ""
                                    }
                                },
                                enabled = !isSaving && !isAiProcessing && tagInput.isNotBlank(),
                                modifier = Modifier.semantics {
                                    contentDescription = "Add tag"
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null
                                )
                            }
                        }

                        // Current tags as removable chips
                        if (note.tags.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                note.tags.forEach { tag ->
                                    FilterChip(
                                        selected = true,
                                        onClick = {
                                            onUpdateDraft(
                                                note.title,
                                                note.content,
                                                note.tags - tag
                                            )
                                        },
                                        label = { Text(tag) },
                                        modifier = Modifier.semantics {
                                            contentDescription = "Remove tag: $tag"
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // â”€â”€ Edit / Preview tabs â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    // ── Context suggestion chips (Requirement 33.1) ──────────────────
                    // Displayed above the keyboard when suggestions are available.
                    // No loading indicator shown while suggestions are being fetched.
                    if (contextSuggestions.isNotEmpty()) {
                        NoteSuggestionChipsRow(
                            suggestions = contextSuggestions,
                            onTap = onSuggestionTapped,
                            onDismiss = { type ->
                                note?.let { onSuggestionDismissed(type) }
                            }
                        )
                    }

                    val selectedTab = if (previewMode) 1 else 0
                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { if (previewMode) onTogglePreview() },
                            text = { Text("Edit") },
                            modifier = Modifier.semantics {
                                contentDescription = "Edit tab"
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { if (!previewMode) onTogglePreview() },
                            text = { Text("Preview") },
                            modifier = Modifier.semantics {
                                contentDescription = "Preview tab"
                            }
                        )
                    }

                    // â”€â”€ Tab content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    if (!previewMode) {
                        OutlinedTextField(
                            value = note.content,
                            onValueChange = { newContent ->
                                onUpdateDraft(note.title, newContent, note.tags)
                            },
                            label = { Text("Content (Markdown supported)") },
                            minLines = 8,
                            enabled = !isSaving && !isAiProcessing,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "Note content input" }
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            MarkdownText(
                                markdown = note.content.ifBlank { "*No content yet.*" },
                                contentDescription = "Note content preview",
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // â”€â”€ AI processing overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isAiProcessing) {
                val processingLabel = when ((uiState as? NotesUiState.AiProcessing)?.operation) {
                    AiOperation.SUMMARIZE -> "Summarizingâ€¦"
                    AiOperation.REWRITE -> "Rewritingâ€¦"
                    null -> "Processingâ€¦"
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(MaterialTheme.spacing.xl)
                                    .semantics { contentDescription = processingLabel }
                            )
                            Text(
                                text = processingLabel,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Note suggestion chips row ────────────────────────────────────────────────

/**
 * A horizontal row of dismissible [AssistChip]s for context-aware AI suggestions
 * (Requirement 33.1). Displayed above the keyboard area in [NoteEditorScreen].
 *
 * Each chip has a tap action ([onTap]) and a separate dismiss [IconButton] ([onDismiss]).
 * Dismissing suppresses that suggestion type for the session (Requirement 33.5).
 *
 * @param suggestions Non-empty list of suggestions to render.
 * @param onTap       Invoked when the user taps a suggestion chip to act on it.
 * @param onDismiss   Invoked with the [SuggestionType] when the user taps the X icon.
 */
@Composable
private fun NoteSuggestionChipsRow(
    suggestions: List<ContextSuggestion>,
    onTap: (ContextSuggestion) -> Unit,
    onDismiss: (SuggestionType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Context-aware AI suggestions" },
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        suggestions.forEach { suggestion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = "AI suggestion: ${suggestion.displayText}"
                }
            ) {
                AssistChip(
                    onClick = { onTap(suggestion) },
                    label = {
                        Text(
                            text = suggestion.displayText,
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Tap to use suggestion: ${suggestion.displayText}"
                    }
                )
                IconButton(
                    onClick = { onDismiss(suggestion.type) },
                    modifier = Modifier
                        .size(MaterialTheme.spacing.lg)
                        .semantics {
                            contentDescription = "Dismiss suggestion: ${suggestion.displayText}"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(MaterialTheme.spacing.md)
                    )
                }
            }
        }
    }
}
