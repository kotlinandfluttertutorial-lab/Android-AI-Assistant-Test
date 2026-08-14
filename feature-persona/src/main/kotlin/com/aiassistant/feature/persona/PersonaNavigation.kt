/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaNavigation.kt
 * Purpose    : Navigation graph for the persona feature
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : Navigation Graph / Destinations
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Single PersonaViewModel shared across persona nav graph
 *   - onPersonaSelected callback for feature-chat timeline integration (Requirement 32.7)
 *
 * Dependencies:
 *   - feature-persona screens, PersonaViewModel (Hilt)
 *   - AndroidX Navigation Compose
 * ============================================================
 */

/**
 * PersonaNavigation.kt
 *
 * Purpose: Navigation graph for the persona feature, exposing PersonaList and PersonaEditor
 *          screens backed by a single shared PersonaViewModel scoped to the nav graph.
 * Architecture: feature-persona — Navigation layer; consumed by the app module's root NavHost.
 * Dependencies: feature-persona screens, PersonaViewModel (Hilt), AndroidX Navigation Compose.
 *
 * Design decisions:
 * - Route strings are defined on [PersonaNavigation] for type-safety and easy refactoring.
 * - [personaNavGraph] is a [NavGraphBuilder] extension so the app module embeds the persona
 *   graph into its root NavHost without importing screen composables directly.
 * - A single [PersonaViewModel] instance is scoped to the persona navigation graph so state
 *   is shared between list and editor screens without leaking to the app scope.
 * - The [onPersonaSelected] callback is the integration point for feature-chat: when the user
 *   selects a persona, this callback fires and the calling code can trigger conversation
 *   timeline insertion (Requirement 32.7).
 *
 * Requirements: 32.1, 32.5, 32.6, 32.7
 */
package com.aiassistant.feature.persona

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navigation

/**
 * Route string constants for the persona navigation sub-graph.
 */
object PersonaNavigation {
    /** Persona list screen route. */
    const val ROUTE_LIST = "persona_list"

    /** Persona editor screen route. */
    const val ROUTE_EDITOR = "persona_editor"

    /** Persona root navigation graph route. */
    const val ROUTE_GRAPH = "persona_graph"
}

/**
 * Embeds the persona navigation sub-graph into the caller's [NavGraphBuilder].
 *
 * Usage in the app module's root [NavHost]:
 * ```kotlin
 * NavHost(navController = navController, startDestination = "persona_graph") {
 *     personaNavGraph(
 *         onPersonaSelected = { personaId -> /* insert timeline message */ },
 *         navController = navController,
 *     )
 * }
 * ```
 *
 * @param onPersonaSelected Callback fired when the user activates a persona. Receives
 *                          the selected persona ID (or null when deselected). Used by
 *                          [feature-chat] to insert a system message in the conversation
 *                          timeline with the persona name and timestamp (Requirement 32.7).
 * @param navController     The [NavController] shared with the app module.
 */
fun NavGraphBuilder.personaNavGraph(onPersonaSelected: (String?) -> Unit, navController: NavController) {
    navigation(
        startDestination = PersonaNavigation.ROUTE_LIST,
        route = PersonaNavigation.ROUTE_GRAPH
    ) {
        // ── Persona List ──────────────────────────────────────────────────────
        composable(route = PersonaNavigation.ROUTE_LIST) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(PersonaNavigation.ROUTE_GRAPH)
            }
            val viewModel: PersonaViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            PersonaListScreen(
                uiState = uiState,
                onPersonaEdit = { persona ->
                    viewModel.openEditor(persona)
                    navController.navigate(PersonaNavigation.ROUTE_EDITOR)
                },
                onPersonaDelete = { personaId ->
                    // isAdmin defaults to false; the app module should pass real role info
                    // via a higher-order function or ViewModel if RBAC info is available
                    viewModel.deletePersona(personaId, isAdmin = false)
                },
                onPersonaSelect = { personaId ->
                    viewModel.selectPersona(personaId)
                    onPersonaSelected(personaId)
                },
                onNewPersona = {
                    viewModel.openNewPersona()
                    navController.navigate(PersonaNavigation.ROUTE_EDITOR)
                }
            )
        }

        // ── Persona Editor ────────────────────────────────────────────────────
        composable(route = PersonaNavigation.ROUTE_EDITOR) { backStackEntry ->
            val parentEntry = remember(backStackEntry) {
                navController.getBackStackEntry(PersonaNavigation.ROUTE_GRAPH)
            }
            val viewModel: PersonaViewModel = hiltViewModel(parentEntry)
            val uiState by viewModel.uiState.collectAsState()

            // Guard: only render if state is PersonaEditor (list navigation handles routing)
            val editorState = uiState as? PersonaUiState.PersonaEditor ?: return@composable

            PersonaEditorScreen(
                uiState = editorState,
                onBack = {
                    viewModel.backToList()
                    navController.popBackStack()
                },
                onFieldChange = { name, systemPrompt, tone, scopeDescription ->
                    viewModel.updateDraft(name, systemPrompt, tone, scopeDescription)
                },
                onSave = {
                    viewModel.savePersona(editorState.persona, editorState.isNew)
                    if (!editorState.isSaving) {
                        navController.popBackStack()
                    }
                }
            )
        }
    }
}
