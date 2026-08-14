/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaListScreen.kt
 * Purpose    : Compose UI screen displaying the list of personas
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : Jetpack Compose Screen (stateless)
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Stateless composable driven by PersonaUiState from PersonaViewModel
 *   - RBAC-filtered list with adminLocked visual indicator
 *   - 20-persona limit inline error (Requirement 32.3)
 *
 * Dependencies:
 *   - core-ui (ErrorBanner, OfflineBanner, MaterialTheme.spacing)
 *   - domain (Persona, PersonaTone)
 *   - PersonaUiState
 * ============================================================
 */

/**
 * PersonaListScreen.kt
 *
 * Purpose: Stateless Compose screen displaying the RBAC-filtered list of personas with
 *          lock icon on admin-locked entries, edit/delete controls for user-owned personas,
 *          persona selection, and a FAB for creating new personas.
 * Architecture: feature-persona — Compose UI layer; stateless composable driven by
 *               PersonaUiState from PersonaViewModel.
 * Dependencies: core-ui (ErrorBanner, OfflineBanner, MaterialTheme.spacing),
 *               domain (Persona, PersonaTone), PersonaUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Admin-locked personas show a lock icon and NO edit/delete controls (Requirement 32.5).
 * - The currently selected persona is indicated by a checkmark icon (Requirement 32.6).
 * - FAB is visually disabled (lower alpha) when the 20-persona limit is reached (Req 32.3).
 * - Delete confirmation dialog prevents accidental deletion (Requirement 32.1).
 * - All interactive elements carry contentDescriptions (TalkBack accessibility).
 *
 * Requirements: 32.1, 32.3, 32.5, 32.6
 */
package com.aiassistant.feature.persona

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.OfflineBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Persona

/**
 * Persona list screen composable.
 *
 * Renders the list of personas visible to the current user (RBAC-filtered), with lock
 * icons for admin-locked entries, edit/delete controls for unlocked entries, and a FAB
 * for creating new personas.
 *
 * @param uiState         Current state from [PersonaViewModel].
 * @param onPersonaEdit   Invoked with the [Persona] when the user taps the edit button.
 * @param onPersonaDelete Invoked with the persona ID when the user confirms deletion.
 * @param onPersonaSelect Invoked with the persona ID (or null) when the user selects/deselects.
 * @param onNewPersona    Invoked when the user taps the FAB to create a new persona.
 * @param isOffline       When true, an [OfflineBanner] is shown at the top.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaListScreen(
    uiState: PersonaUiState,
    onPersonaEdit: (Persona) -> Unit,
    onPersonaDelete: (String) -> Unit,
    onPersonaSelect: (String?) -> Unit,
    onNewPersona: () -> Unit,
    isOffline: Boolean = false
) {
    // Derive whether the FAB should be disabled (limit reached)
    val limitReached = (uiState as? PersonaUiState.PersonaList)?.limitError != null
    val fabAlpha = if (limitReached) 0.38f else 1f

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Personas") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { if (!limitReached) onNewPersona() },
                modifier = Modifier
                    .alpha(fabAlpha)
                    .semantics {
                        contentDescription = if (limitReached) {
                            "Create new persona (limit reached)"
                        } else {
                            "Create new persona"
                        }
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
            // ── Offline banner ───────────────────────────────────────────────
            if (isOffline) {
                OfflineBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // ── Loading state ────────────────────────────────────────────────
            if (uiState is PersonaUiState.Loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = "Loading personas"
                        }
                    )
                }
                return@Column
            }

            // ── Error state ──────────────────────────────────────────────────
            if (uiState is PersonaUiState.Error) {
                ErrorBanner(
                    message = uiState.message,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
                return@Column
            }

            if (uiState !is PersonaUiState.PersonaList) return@Column

            // ── 20-persona limit error banner ────────────────────────────────
            if (uiState.limitError != null) {
                ErrorBanner(
                    message = uiState.limitError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MaterialTheme.spacing.md)
                )
            }

            // ── Empty state ──────────────────────────────────────────────────
            if (uiState.personas.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No personas yet. Tap + to create one.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                return@Column
            }

            // ── Personas list ────────────────────────────────────────────────
            LazyColumn(
                contentPadding = PaddingValues(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.sm
                ),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
            ) {
                items(uiState.personas, key = { it.id }) { persona ->
                    PersonaCard(
                        persona = persona,
                        isSelected = uiState.selectedPersonaId == persona.id,
                        onEdit = { onPersonaEdit(persona) },
                        onDelete = { onPersonaDelete(persona.id) },
                        onSelect = { onPersonaSelect(persona.id) }
                    )
                }
            }
        }
    }
}

// ─── Persona card ──────────────────────────────────────────────────────────────────

/**
 * A card displaying the summary of a single [persona].
 *
 * - Admin-locked personas: show lock icon, no edit/delete controls (Requirement 32.5).
 * - Selected persona: show checkmark icon.
 * - Tapping the card selects the persona (if not admin-locked).
 *
 * @param persona    The persona to display.
 * @param isSelected True when this is the currently active persona.
 * @param onEdit     Invoked when the user taps the edit button.
 * @param onDelete   Invoked after the user confirms deletion.
 * @param onSelect   Invoked when the user taps the card body to select this persona.
 */
@Composable
private fun PersonaCard(
    persona: Persona,
    isSelected: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete persona?") },
            text = { Text("\"${persona.name}\" will be permanently deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        onDelete()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Confirm delete persona ${persona.name}"
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    modifier = Modifier.semantics {
                        contentDescription = "Cancel delete persona"
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Card(
        onClick = { if (!persona.adminLocked) onSelect() },
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = buildString {
                    append("Persona: ${persona.name}")
                    if (isSelected) append(", selected")
                    if (persona.adminLocked) append(", admin locked")
                }
            },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            // ── Header row: name + status icons + action buttons ─────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = persona.name.ifBlank { "Unnamed" },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

                // Selection checkmark
                if (isSelected) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = "Currently selected persona",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Selected" }
                    )
                }

                // Admin lock icon (or edit/delete buttons)
                if (persona.adminLocked) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = "Admin locked — cannot edit or delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .size(20.dp)
                            .semantics { contentDescription = "Admin locked" }
                    )
                } else {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.semantics {
                            contentDescription = "Edit persona: ${persona.name}"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.semantics {
                            contentDescription = "Delete persona: ${persona.name}"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Tone badge ───────────────────────────────────────────────────
            SuggestionChip(
                onClick = {},
                label = {
                    Text(
                        text = persona.tone.value.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                modifier = Modifier.semantics {
                    contentDescription = "Tone: ${persona.tone.value}"
                }
            )

            // ── Scope description ────────────────────────────────────────────
            val scope = persona.scopeDescription
            if (!scope.isNullOrBlank()) {
                Text(
                    text = scope,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}
