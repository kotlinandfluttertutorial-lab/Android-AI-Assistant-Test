/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : EmailComposerScreen.kt
 * Purpose    : Compose UI screen for the EmailComposer feature
 *
 * Architecture Layer : Feature (feature-email)
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
 * Module     : feature-email
 * File       : EmailComposerScreen.kt
 * Purpose    : Compose UI screen for the EmailComposer feature
 *
 * Architecture Layer : Feature (feature-email)
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
 * EmailComposerScreen.kt
 *
 * Purpose: Screen for composing an email context/intent and viewing the AI-generated
 *          professional email.
 * Architecture: feature-email â€” Compose UI layer.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               EmailViewModel, EmailUiState.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - Loading overlay prevents interaction during AI generation.
 * - When the email is generated, the user can trigger grammar correction or start over.
 * - All interactive elements carry contentDescriptions (Requirement 28.3).
 *
 * Requirements: 14.4, 14.5, 28.3
 */
package com.aiassistant.feature.email

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
 * Email composer screen composable.
 *
 * Shows two text fields (Email Context, Intent/Goal) and a Generate button in the idle/
 * error state. Once an email is generated, displays it in a scrollable card with
 * "Correct Grammar" and "Start Over" actions.
 *
 * @param uiState           Current state from [EmailViewModel].
 * @param onGenerateEmail   Invoked with (context, intent) when the user taps "Generate Email".
 * @param onCorrectGrammar  Invoked with the generated email draft when the user taps
 *                          "Correct Grammar".
 * @param onNavigateUp      Called when the user taps the back arrow.
 * @param onResetState      Called when the user taps "Start Over".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailComposerScreen(
    uiState: EmailUiState,
    onGenerateEmail: (context: String, intent: String) -> Unit,
    onCorrectGrammar: (draftEmail: String) -> Unit,
    onNavigateUp: () -> Unit,
    onResetState: () -> Unit
) {
    var emailContext by remember { mutableStateOf("") }
    var emailIntent by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

    val isLoading = uiState is EmailUiState.Loading
    val loadingMessage = (uiState as? EmailUiState.Loading)?.message ?: ""
    val errorMessage = (uiState as? EmailUiState.Error)?.message
    val generatedEmail = (uiState as? EmailUiState.EmailGenerated)?.emailText

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Email Composer") },
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

                if (generatedEmail == null) {
                    // â”€â”€ Input form â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Provide context and goal to generate a professional email.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedTextField(
                        value = emailContext,
                        onValueChange = { emailContext = it },
                        label = { Text("Email Context") },
                        placeholder = {
                            Text("Describe the situation and relevant backgroundâ€¦")
                        },
                        minLines = 4,
                        maxLines = 10,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Email context input field"
                            }
                    )

                    OutlinedTextField(
                        value = emailIntent,
                        onValueChange = { emailIntent = it },
                        label = { Text("Intent / Goal") },
                        placeholder = {
                            Text("What is the purpose of this email?")
                        },
                        minLines = 3,
                        maxLines = 6,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Email intent input field"
                            }
                    )

                    Button(
                        onClick = { onGenerateEmail(emailContext, emailIntent) },
                        enabled = !isLoading &&
                            emailContext.isNotBlank() &&
                            emailIntent.isNotBlank(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Generate email button" }
                    ) {
                        Text("Generate Email")
                    }
                } else {
                    // â”€â”€ Generated email â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Generated Email",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = generatedEmail,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.md)
                                .semantics {
                                    contentDescription = "Generated email content"
                                }
                        )
                    }

                    Button(
                        onClick = { onCorrectGrammar(generatedEmail) },
                        enabled = !isLoading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Correct grammar button" }
                    ) {
                        Text("Correct Grammar")
                    }

                    OutlinedButton(
                        onClick = onResetState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "Start over and compose new email"
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
