/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : ResumeBuilderScreen.kt
 * Purpose    : Compose UI screen for the ResumeBuilder feature
 *
 * Architecture Layer : Feature (feature-resume)
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
 * Module     : feature-resume
 * File       : ResumeBuilderScreen.kt
 * Purpose    : Compose UI screen for the ResumeBuilder feature
 *
 * Architecture Layer : Feature (feature-resume)
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
 * ResumeBuilderScreen.kt
 *
 * Purpose: Screen for generating an ATS-optimised resume in Markdown format from
 *          professional history and a target job description.
 * Architecture: feature-resume â€” Compose UI layer.
 * Dependencies: core-ui (ErrorBanner, MarkdownText, MaterialTheme.spacing),
 *               ResumeViewModel, ResumeUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Loading overlay prevents interaction during AI generation (may take up to 30 s).
 * - Generated Markdown is displayed using the shared [MarkdownText] component.
 * - Export buttons appear only when content is available (ResumeGenerated state).
 * - All interactive elements carry contentDescriptions (Requirement 28.3).
 *
 * Requirements: 14.1, 14.3, 28.3
 */
package com.aiassistant.feature.resume

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.core.ui.spacing

/**
 * Resume builder screen composable.
 *
 * Displays two multiline text fields (professional history and job description) and a
 * Generate button. Once a resume is generated it is displayed as rendered Markdown with
 * PDF and DOCX export options.
 *
 * @param uiState              Current state from [ResumeViewModel].
 * @param onGenerateResume     Invoked with (professionalHistory, jobDescription) when the
 *                             user taps "Generate Resume".
 * @param onExport             Invoked with (content, format) when the user taps an export
 *                             button.
 * @param onNavigateUp         Called when the user taps the back arrow.
 * @param onResetState         Called when a fresh generation should be started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResumeBuilderScreen(
    uiState: ResumeUiState,
    onGenerateResume: (professionalHistory: String, jobDescription: String) -> Unit,
    onExport: (content: String, format: ResumeExportFormat) -> Unit,
    onNavigateUp: () -> Unit,
    onResetState: () -> Unit
) {
    var professionalHistory by remember { mutableStateOf("") }
    var jobDescription by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    // Show export success snackbar
    LaunchedEffect(uiState) {
        if (uiState is ResumeUiState.ExportSuccess) {
            val formatLabel = when (uiState.format) {
                ResumeExportFormat.PDF -> "PDF"
                ResumeExportFormat.DOCX -> "DOCX"
            }
            snackbarHostState.showSnackbar("Saved as $formatLabel: ${uiState.filePath}")
        }
    }

    val isLoading = uiState is ResumeUiState.Loading || uiState is ResumeUiState.Exporting
    val loadingMessage = when (uiState) {
        is ResumeUiState.Loading -> uiState.message
        is ResumeUiState.Exporting -> "Exportingâ€¦"
        else -> ""
    }
    val errorMessage = (uiState as? ResumeUiState.Error)?.message
    val generatedResume = (uiState as? ResumeUiState.ResumeGenerated)?.resumeMarkdown

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Resume Builder") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics {
                            contentDescription = "Navigate back"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(MaterialTheme.spacing.md),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
            ) {
                // â”€â”€ Error banner â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                if (errorMessage != null) {
                    ErrorBanner(
                        message = errorMessage,
                        onRetry = onResetState,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (generatedResume == null) {
                    // â”€â”€ Input form â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Enter your details below to generate an ATS-optimised resume.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = professionalHistory,
                        onValueChange = { professionalHistory = it },
                        label = { Text("Professional History") },
                        placeholder = {
                            Text("Your work experience, education, skillsâ€¦")
                        },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "Professional history input field"
                            }
                    )

                    OutlinedTextField(
                        value = jobDescription,
                        onValueChange = { jobDescription = it },
                        label = { Text("Target Job Description") },
                        placeholder = {
                            Text("Paste the target job descriptionâ€¦")
                        },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "Target job description input field"
                            }
                    )

                    Button(
                        onClick = {
                            onGenerateResume(professionalHistory, jobDescription)
                        },
                        enabled = !isLoading &&
                            professionalHistory.isNotBlank() &&
                            jobDescription.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Generate resume button" }
                    ) {
                        Text("Generate Resume")
                    }
                } else {
                    // â”€â”€ Generated resume â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Generated Resume",
                        style = MaterialTheme.typography.titleMedium
                    )

                    MarkdownText(
                        markdown = generatedResume,
                        contentDescription = "Generated resume content",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

                    Text(
                        text = "Export as",
                        style = MaterialTheme.typography.labelLarge
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
                    ) {
                        OutlinedButton(
                            onClick = { onExport(generatedResume, ResumeExportFormat.PDF) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Export resume as PDF" }
                        ) {
                            Text("PDF")
                        }

                        OutlinedButton(
                            onClick = { onExport(generatedResume, ResumeExportFormat.DOCX) },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "Export resume as DOCX" }
                        ) {
                            Text("DOCX")
                        }
                    }

                    OutlinedButton(
                        onClick = onResetState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Start over and generate new resume" }
                    ) {
                        Text("Start Over")
                    }
                }
            }

            // â”€â”€ Loading overlay â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            if (isLoading) {
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
                                modifier = Modifier.semantics {
                                    contentDescription = "Loading, please wait"
                                }
                            )
                            if (loadingMessage.isNotEmpty()) {
                                Text(
                                    text = loadingMessage,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
