/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-notes
 * File       : NotesNavigation.kt
 * Purpose    : NotesNavigation — feature-notes module component
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : Navigation Graph / Destinations
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
 * File       : NotesNavigation.kt
 * Purpose    : NotesNavigation — feature-notes module component
 *
 * Architecture Layer : Feature (feature-notes)
 * Pattern Used       : Navigation Graph / Destinations
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
 * NotesNavigation.kt
 *
 * Purpose: Navigation graph for the notes feature, exposing NotesList and NoteEditor
 *          screens backed by a single shared NotesViewModel scoped to the nav graph.
 * Architecture: feature-notes â€” Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-notes screens, NotesViewModel (Hilt),
 *               AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [NotesRoute] for type-safety and easy refactoring.
 * - [notesNavGraph] is a [NavGraphBuilder] extension so the app module embeds the notes
 *   graph into its root [NavHost] without importing screen composables directly.
 * - A single [NotesViewModel] instance is scoped to the notes navigation graph so state
 *   is shared between list and editor screens without being leaked to the app scope.
 * - The editor route uses an optional `noteId` query parameter; its absence signals a
 *   new note flow (openNewNote) rather than editing an existing one (openNote).
 *
 * Requirements: 13.1, 13.5
 */
package com.aiassistant.feature.notes

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navigation

/**
 * Route string constants for the notes navigation sub-graph.
 */
object NotesRoute {
    /** Notes root navigation graph route. */
    const val GRAPH = "notes"

    /** Notes list screen route. */
    const val LIST = "notes/list"

    /** Note editor screen route — [noteId] query param is optional. */
    const val EDITOR = "notes/editor?noteId={noteId}"
}

/**
 * Embeds the notes navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "notes") {
 *     notesNavGraph(navController = navController)
 * }
 * ```
 *
 * @param navController The root [NavHostController] shared with the app module.
 * @param onNavigateUp  Called when the user navigates back out of the notes graph.
 */
fun NavGraphBuilder.notesNavGraph(
    navController: NavHostController,
    onNavigateUp: () -> Unit = { navController.popBackStack() }
) {
    navigation(
        startDestination = NotesRoute.LIST,
        route = NotesRoute.GRAPH
    ) {
        // ── Notes List ────────────────────────────────────────────────────────────
        composable(route = NotesRoute.LIST) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(NotesRoute.GRAPH)
            }
            val viewModel: NotesViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            NotesListScreen(
                uiState = uiState,
                onNoteClick = { note ->
                    viewModel.openNote(note)
                    navController.navigate("notes/editor?noteId=${note.id}")
                },
                onNewNote = {
                    viewModel.openNewNote()
                    navController.navigate("notes/editor")
                },
                onDeleteNote = { noteId -> viewModel.deleteNote(noteId) },
                onTagFilter = { tag -> viewModel.selectTagFilter(tag) }
            )
        }

        // ── Note Editor ───────────────────────────────────────────────────────────
        composable(
            route = NotesRoute.EDITOR,
            arguments = listOf(
                navArgument("noteId") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(NotesRoute.GRAPH)
            }
            val viewModel: NotesViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            NoteEditorScreen(
                uiState = uiState,
                onUpdateDraft = { title, content, tags ->
                    viewModel.updateDraft(title, content, tags)
                },
                onSave = { note ->
                    viewModel.saveNote(note)
                    onNavigateUp()
                },
                onBack = {
                    viewModel.backToList()
                    onNavigateUp()
                },
                onTogglePreview = { viewModel.togglePreviewMode() },
                onSummarize = { noteId -> viewModel.summarizeNote(noteId) },
                onRewrite = { noteId -> viewModel.rewriteNote(noteId) },
                onApplyAiResult = { result -> viewModel.applyAiResult(result) },
                onDismissAiResult = { viewModel.dismissAiResult() },
                // ── Context suggestion callbacks (Requirement 33.1, 33.5) ────────
                onIdleAfter5Seconds = { noteId, content ->
                    viewModel.requestContextSuggestions(noteId, content)
                },
                onSuggestionTapped = { suggestion ->
                    // Pre-fill can be wired to a future AI action; for now it is a
                    // no-op at the navigation layer — the ViewModel handles AI calls.
                },
                onSuggestionDismissed = { type ->
                    val currentNote = (uiState as? NotesUiState.NoteEditor)?.note
                    currentNote?.let { viewModel.dismissSuggestion(it.id, type) }
                }
            )
        }
    }
}
