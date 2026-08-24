/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-resume
 * File       : CoverLetterEditorScreen.kt
 * Purpose    : Compose UI screen for the CoverLetterEditor feature
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
 * File       : CoverLetterEditorScreen.kt
 * Purpose    : Compose UI screen for the CoverLetterEditor feature
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
 * CoverLetterEditorScreen.kt
 *
 * Purpose: Screen for generating a tailored cover letter (â‰¤ 400 words) from
 *          professional history and a target job description.
 * Architecture: feature-resume â€” Compose UI layer.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               ResumeViewModel, ResumeUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Word count is displayed beneath the generated cover letter to confirm the â‰¤ 400
 *   word constraint is met (Requirement 14.2).
 * - Loading overlay prevents interaction during AI generation.
 * - Export buttons appear only when content is available (CoverLetterGenerated state).
 * - All interactive elements carry contentDescriptions (Requirement 28.3).
 *
 * Requirements: 14.2, 14.3, 28.3
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
import com.aiassistant.core.ui.spacing

/**
 * Cover letter editor screen composable.
 *
 * Displays two multiline text fields (professional history and job description) and a
 * Generate button. Once a cover letter is generated it is displayed in a scrollable text
 * area with a word count indicator and PDF / DOCX export buttons.
 *
 * @param uiState                  Current state from [ResumeViewModel].
 * @param onGenerateCoverLetter    Invoked with (professionalHistory, jobDescription) when the
 *                                 user taps "Generate Cover Letter".
 * @param onExport                 Invoked with (content, format) when the user taps an export
 *                                 button.
 * @param onNavigateUp             Called when the user taps the back arrow.
 * @param onResetState             Called when a fresh generation should be started.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoverLetterEditorScreen(
    uiState: ResumeUiState,
    onGenerateCoverLetter: (professionalHistory: String, jobDescription: String) -> Unit,
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
    val generatedCoverLetter = (uiState as? ResumeUiState.CoverLetterGenerated)?.coverLetterText

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cover Letter Editor") },
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

                if (generatedCoverLetter == null) {
                    // â”€â”€ Input form â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Enter your details to generate a tailored cover letter (â‰¤ 400 words).",
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
                            onGenerateCoverLetter(professionalHistory, jobDescription)
                        },
                        enabled = !isLoading &&
                            professionalHistory.isNotBlank() &&
                            jobDescription.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Generate cover letter button"
                            }
                    ) {
                        Text("Generate Cover Letter")
                    }
                } else {
                    // â”€â”€ Generated cover letter â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    val wordCount = countWords(generatedCoverLetter)
                    val wordCountColor = if (wordCount > 400) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Generated Cover Letter",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "$wordCount words",
                            style = MaterialTheme.typography.labelMedium,
                            color = wordCountColor,
                            modifier = Modifier.semantics {
                                contentDescription = "Word count: $wordCount words"
                            }
                        )
                    }

                    Text(
                        text = generatedCoverLetter,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Generated cover letter content"
                            }
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
                            onClick = {
                                onExport(generatedCoverLetter, ResumeExportFormat.PDF)
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Export cover letter as PDF"
                                }
                        ) {
                            Text("PDF")
                        }

                        OutlinedButton(
                            onClick = {
                                onExport(generatedCoverLetter, ResumeExportFormat.DOCX)
                            },
                            enabled = !isLoading,
                            modifier = Modifier
                                .weight(1f)
                                .semantics {
                                    contentDescription = "Export cover letter as DOCX"
                                }
                        ) {
                            Text("DOCX")
                        }
                    }

                    OutlinedButton(
                        onClick = onResetState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription =
                                    "Start over and generate new cover letter"
                            }
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

// â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Counts the number of whitespace-delimited words in [text].
 *
 * Used to display the word count for the generated cover letter
 * (â‰¤ 400 word requirement, Requirement 14.2).
 */
private fun countWords(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
