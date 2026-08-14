/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-email
 * File       : GrammarCorrectionScreen.kt
 * Purpose    : Compose UI screen for the GrammarCorrection feature
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
 * File       : GrammarCorrectionScreen.kt
 * Purpose    : Compose UI screen for the GrammarCorrection feature
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
 * GrammarCorrectionScreen.kt
 *
 * Purpose: Screen showing an inline word-level diff between the original draft and
 *          the grammar-corrected email, plus the full corrected text.
 * Architecture: feature-email â€” Compose UI layer.
 * Dependencies: core-ui (ErrorBanner, MaterialTheme.spacing),
 *               EmailViewModel, EmailUiState, EmailDiff.
 *
 * Design decisions:
 * - Stateless composable: all state and callbacks are passed as parameters.
 * - AnnotatedString renders the diff inline â€” REMOVED words are struck through in red,
 *   ADDED words are green; UNCHANGED words are rendered in the default surface colour.
 * - "Copy Corrected Text" writes the plain corrected text to the system clipboard.
 * - All interactive elements carry contentDescriptions (Requirement 28.3).
 *
 * Requirements: 14.5, 28.3
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import com.aiassistant.core.ui.components.ErrorBanner
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

// â”€â”€â”€ Diff colours â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private val ColorRemoved = Color(0xFFC62828) // red â€” deleted words
private val ColorAdded = Color(0xFF2E7D32) // green â€” inserted words
private val ColorAddedBackground = Color(0x332E7D32) // 20 % green tint background

/**
 * Grammar correction screen composable.
 *
 * When [uiState] is [EmailUiState.GrammarCorrected] this screen shows:
 *  1. An inline diff view highlighting removed words (red, strikethrough) and
 *     added words (green, background tint).
 *  2. The full corrected text in a scrollable card.
 *  3. A "Copy Corrected Text" button.
 *
 * @param uiState       Current state from [EmailViewModel].
 * @param onNavigateUp  Called when the user taps the back arrow.
 * @param onResetState  Called when the user taps "Start Over".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GrammarCorrectionScreen(uiState: EmailUiState, onNavigateUp: () -> Unit, onResetState: () -> Unit) {
    val clipboardManager = LocalClipboardManager.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val isLoading = uiState is EmailUiState.Loading
    val loadingMessage = (uiState as? EmailUiState.Loading)?.message ?: ""
    val errorMessage = (uiState as? EmailUiState.Error)?.message
    val grammarState = uiState as? EmailUiState.GrammarCorrected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Grammar Correction") },
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

                if (grammarState != null) {
                    // â”€â”€ Inline diff view â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Changes",
                        style = MaterialTheme.typography.titleMedium
                    )

                    val diffAnnotated = buildDiffAnnotatedString(grammarState.diffSpans)

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = diffAnnotated,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.md)
                                .semantics {
                                    contentDescription = "Grammar correction diff view"
                                }
                        )
                    }

                    // â”€â”€ Full corrected text â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Text(
                        text = "Corrected Email",
                        style = MaterialTheme.typography.titleMedium
                    )

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = grammarState.corrected,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(MaterialTheme.spacing.md)
                                .semantics {
                                    contentDescription = "Corrected email content"
                                }
                        )
                    }

                    // â”€â”€ Copy button â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
                    Button(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(grammarState.corrected))
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar("Corrected email copied to clipboard")
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Copy corrected text button" }
                    ) {
                        Text("Copy Corrected Text")
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

// â”€â”€â”€ Private helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Builds an [AnnotatedString] from a list of [DiffSpan] objects for the inline diff view.
 *
 * - [DiffType.UNCHANGED]: normal text in [MaterialTheme.colorScheme.onSurface].
 * - [DiffType.REMOVED]: red ([ColorRemoved]) with [TextDecoration.LineThrough] strikethrough.
 * - [DiffType.ADDED]: green ([ColorAdded]) with a semi-transparent green background tint.
 *
 * Words are joined with a space between each span.
 */
@Composable
private fun buildDiffAnnotatedString(diffSpans: List<DiffSpan>): AnnotatedString {
    val onSurface = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        diffSpans.forEachIndexed { index, span ->
            if (index > 0) append(" ")
            when (span.type) {
                DiffType.UNCHANGED -> {
                    withStyle(SpanStyle(color = onSurface)) {
                        append(span.text)
                    }
                }
                DiffType.REMOVED -> {
                    withStyle(
                        SpanStyle(
                            color = ColorRemoved,
                            textDecoration = TextDecoration.LineThrough
                        )
                    ) {
                        append(span.text)
                    }
                }
                DiffType.ADDED -> {
                    withStyle(
                        SpanStyle(
                            color = ColorAdded,
                            background = ColorAddedBackground
                        )
                    ) {
                        append(span.text)
                    }
                }
            }
        }
    }
}
