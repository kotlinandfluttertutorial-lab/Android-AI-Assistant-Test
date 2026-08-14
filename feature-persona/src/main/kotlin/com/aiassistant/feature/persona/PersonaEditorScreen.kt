/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-persona
 * File       : PersonaEditorScreen.kt
 * Purpose    : Compose UI form screen for creating and editing personas
 *
 * Architecture Layer : Feature (feature-persona)
 * Pattern Used       : Jetpack Compose Screen (stateless)
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Stateless composable driven by PersonaUiState.PersonaEditor
 *   - Character counters with 4,000-char system prompt limit (Requirement 32.5)
 *   - Inline field validation errors from domain layer
 *
 * Dependencies:
 *   - core-ui (ErrorBanner, MaterialTheme.spacing)
 *   - domain (Persona, PersonaTone)
 *   - PersonaUiState
 * ============================================================
 */

/**
 * PersonaEditorScreen.kt
 *
 * Purpose: Stateless Compose form screen for creating and editing AI personas, with
 *          field validation, character counters, tone selector chips, and inline errors.
 * Architecture: feature-persona — Compose UI layer; stateless composable driven by
 *               PersonaUiState.PersonaEditor from PersonaViewModel.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing), domain (Persona, PersonaTone),
 *               PersonaUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Character counters are displayed below each field; system prompt counter turns error
 *   color when exceeding 4,000 characters (Requirement 32.5).
 * - Tone selector uses FilterChip row for all PersonaTone values.
 * - Save button is replaced by CircularProgressIndicator while isSaving=true.
 * - All interactive elements carry contentDescriptions (TalkBack accessibility).
 *
 * Requirements: 32.1, 32.3, 32.5
 */
package com.aiassistant.feature.persona

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.PersonaTone

private const val MAX_NAME_LENGTH = 80
private const val MAX_SYSTEM_PROMPT_LENGTH = 4_000
private const val MAX_SCOPE_DESCRIPTION_LENGTH = 500

/**
 * Persona editor screen composable.
 *
 * Renders the form for creating or editing a persona. Handles only the
 * [PersonaUiState.PersonaEditor] state; the navigation layer ensures this composable
 * is only invoked in that state.
 *
 * @param uiState        Current editor state from [PersonaViewModel].
 * @param onBack         Invoked when the user taps the back arrow.
 * @param onFieldChange  Invoked whenever any field value changes; the caller (ViewModel)
 *                       updates the draft without persisting.
 * @param onSave         Invoked when the user taps the Save button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonaEditorScreen(
    uiState: PersonaUiState.PersonaEditor,
    onBack: () -> Unit,
    onFieldChange: (name: String, systemPrompt: String, tone: PersonaTone, scopeDescription: String) -> Unit,
    onSave: () -> Unit
) {
    val persona = uiState.persona
    val isSaving = uiState.isSaving
    val fieldErrors = uiState.fieldErrors
    val generalError = uiState.generalError

    // Derive local field values from the draft
    val name = persona.name
    val systemPrompt = persona.systemPrompt
    val tone = persona.tone
    val scopeDescription = persona.scopeDescription ?: ""

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (uiState.isNew) "New Persona" else "Edit Persona")
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
                    if (isSaving) {
                        Box(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .semantics { contentDescription = "Saving persona" },
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    } else {
                        TextButton(
                            onClick = onSave,
                            enabled = !isSaving,
                            modifier = Modifier.semantics {
                                contentDescription = "Save persona"
                            }
                        ) {
                            Text("Save")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.md),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            // ── General / limit error banner ─────────────────────────────────
            if (generalError != null) {
                ErrorBanner(
                    message = generalError,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // ── Name field ───────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { newName ->
                        onFieldChange(newName, systemPrompt, tone, scopeDescription)
                    },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = fieldErrors.containsKey("name"),
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Persona name input" },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = fieldErrors["name"] ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${name.length}/$MAX_NAME_LENGTH",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (name.length > MAX_NAME_LENGTH) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                )
            }

            // ── System Prompt field ──────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { newPrompt ->
                        onFieldChange(name, newPrompt, tone, scopeDescription)
                    },
                    label = { Text("System Prompt") },
                    minLines = 4,
                    maxLines = 10,
                    isError = fieldErrors.containsKey("systemPrompt"),
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "System prompt input" },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = fieldErrors["systemPrompt"] ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${systemPrompt.length}/$MAX_SYSTEM_PROMPT_LENGTH",
                                style = MaterialTheme.typography.bodySmall,
                                // Counter turns error color when length exceeds limit (Req 32.5)
                                color = if (systemPrompt.length > MAX_SYSTEM_PROMPT_LENGTH) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                )
            }

            // ── Tone selector ─────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                Text(
                    text = "Tone",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                ) {
                    PersonaTone.entries.forEach { toneOption ->
                        FilterChip(
                            selected = tone == toneOption,
                            onClick = {
                                if (!isSaving) onFieldChange(name, systemPrompt, toneOption, scopeDescription)
                            },
                            label = {
                                Text(toneOption.value.replaceFirstChar { it.uppercase() })
                            },
                            modifier = Modifier.semantics {
                                contentDescription =
                                    "Tone: ${toneOption.value}, ${if (tone == toneOption) "selected" else "not selected"}"
                            }
                        )
                    }
                }
            }

            // ── Scope description field ──────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                OutlinedTextField(
                    value = scopeDescription,
                    onValueChange = { newScope ->
                        onFieldChange(name, systemPrompt, tone, newScope)
                    },
                    label = { Text("Scope Description (optional)") },
                    minLines = 2,
                    maxLines = 4,
                    isError = fieldErrors.containsKey("scopeDescription"),
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Scope description input" },
                    supportingText = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = fieldErrors["scopeDescription"] ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "${scopeDescription.length}/$MAX_SCOPE_DESCRIPTION_LENGTH",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (scopeDescription.length > MAX_SCOPE_DESCRIPTION_LENGTH) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                )
            }
        }
    }
}
