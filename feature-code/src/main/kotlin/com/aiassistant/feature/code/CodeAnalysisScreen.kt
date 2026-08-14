/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeAnalysisScreen.kt
 * Purpose    : Compose UI screen for the CodeAnalysis feature
 *
 * Architecture Layer : Feature (feature-code)
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
 * Module     : feature-code
 * File       : CodeAnalysisScreen.kt
 * Purpose    : Compose UI screen for the CodeAnalysis feature
 *
 * Architecture Layer : Feature (feature-code)
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
 * CodeAnalysisScreen.kt
 *
 * Purpose: Displays the AI analysis result with syntax-highlighted code using the
 *          languageId from the AI response, copy-to-clipboard, and back-to-editor navigation.
 * Architecture: feature-code â€” MVVM presentation layer (stateless composable pattern).
 * Dependencies: core-ui (CodeBlock, MarkdownText), domain (CodeAction, CodeAnalysisResult),
 *               Compose Material 3
 *
 * Requirements: 12.2, 12.3, 12.4, 12.5, 12.6
 */
package com.aiassistant.feature.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.components.CodeBlock
import com.aiassistant.core.ui.components.MarkdownText
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.CodeAnalysisRequest
import com.aiassistant.domain.model.CodeAnalysisResult
import com.aiassistant.domain.model.SupportedLanguage

/**
 * Code analysis result screen composable.
 *
 * Stateless composable â€” all state changes are delegated to ViewModel via lambda callbacks.
 *
 * Rendering strategy by action (Requirement 12.2, 12.3, 12.4, 12.6):
 * - [CodeAction.EXPLAIN]: renders [CodeAnalysisResult.content] as [MarkdownText] (structured
 *   explanation with what/why/improvements sections).
 * - [CodeAction.FIX_BUG]: renders [CodeAnalysisResult.content] as [CodeBlock] with
 *   [CodeAnalysisResult.languageId] (corrected code + inline change comments).
 * - [CodeAction.GENERATE_TESTS]: renders [CodeAnalysisResult.content] as [CodeBlock] with
 *   [CodeAnalysisResult.languageId] (complete test suite in the same language).
 *
 * @param uiState         The [CodeUiState.AnalysisResult] containing the request and AI result.
 * @param onBackToEditor  Called when the user taps "Back to Editor".
 * @param modifier        Optional [Modifier] applied to the root [Scaffold].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeAnalysisScreen(uiState: CodeUiState.AnalysisResult, onBackToEditor: () -> Unit, modifier: Modifier = Modifier) {
    val clipboardManager = LocalClipboardManager.current
    val result = uiState.result
    val action = uiState.request.action

    val actionLabel = action.displayName()
    val actionIcon = when (action) {
        CodeAction.EXPLAIN -> Icons.Filled.Info
        CodeAction.FIX_BUG -> Icons.Filled.BugReport
        CodeAction.GENERATE_TESTS -> Icons.Filled.Science
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Code Assistant") },
                navigationIcon = {
                    IconButton(
                        onClick = onBackToEditor,
                        modifier = Modifier.semantics {
                            contentDescription = "Back to editor"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
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
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // â”€â”€ Action header â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Analysis result: $actionLabel"
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // â”€â”€ Result content â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            // Requirement 12.6: use languageId from AI response for syntax highlighting.
            // Requirement 12.2: EXPLAIN uses MarkdownText for structured explanation.
            // Requirements 12.3, 12.4: FIX_BUG and GENERATE_TESTS use CodeBlock.
            when (action) {
                CodeAction.EXPLAIN -> {
                    MarkdownText(
                        markdown = result.content,
                        contentDescription = "Code explanation",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                CodeAction.FIX_BUG -> {
                    CodeBlock(
                        code = result.content,
                        language = result.languageId,
                        contentDescription = "Fixed code in ${result.languageId}",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                CodeAction.GENERATE_TESTS -> {
                    CodeBlock(
                        code = result.content,
                        language = result.languageId,
                        contentDescription = "Generated test suite in ${result.languageId}",
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // â”€â”€ Action buttons â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Copy button â€” single-tap copies result content to clipboard (Requirement 12.5)
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(result.content))
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Copy result to clipboard"
                        }
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 4.dp)
                    )
                    Text("Copy")
                }

                Spacer(modifier = Modifier.padding(horizontal = 8.dp))

                // Back to editor button
                OutlinedButton(
                    onClick = onBackToEditor,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "Back to code editor"
                        }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        modifier = Modifier
                            .size(18.dp)
                            .padding(end = 4.dp)
                    )
                    Text("Back to Editor")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "CodeAnalysisScreen â€“ Explain")
@Composable
private fun CodeAnalysisExplainPreview() {
    AppTheme(dynamicColor = false) {
        CodeAnalysisScreen(
            uiState = CodeUiState.AnalysisResult(
                request = CodeAnalysisRequest(
                    code = "fun hello() = println(\"Hello\")",
                    language = SupportedLanguage.KOTLIN,
                    action = CodeAction.EXPLAIN
                ),
                result = CodeAnalysisResult(
                    languageId = "kotlin",
                    originalCode = "fun hello() = println(\"Hello\")",
                    action = CodeAction.EXPLAIN,
                    content = "## What it does\nPrints \"Hello\" to stdout.\n\n## Why\nDemonstrates a minimal Kotlin function.\n\n## Improvements\n- Add a parameter to make it configurable."
                )
            ),
            onBackToEditor = {}
        )
    }
}

@Preview(showBackground = true, name = "CodeAnalysisScreen â€“ Fix Bug")
@Composable
private fun CodeAnalysisFixBugPreview() {
    AppTheme(dynamicColor = false) {
        CodeAnalysisScreen(
            uiState = CodeUiState.AnalysisResult(
                request = CodeAnalysisRequest(
                    code = "fun divide(a: Int, b: Int) = a / b",
                    language = SupportedLanguage.KOTLIN,
                    action = CodeAction.FIX_BUG
                ),
                result = CodeAnalysisResult(
                    languageId = "kotlin",
                    originalCode = "fun divide(a: Int, b: Int) = a / b",
                    action = CodeAction.FIX_BUG,
                    content = "// FIX: Guard against division by zero\nfun divide(a: Int, b: Int): Int? {\n    if (b == 0) return null // return null instead of throwing\n    return a / b\n}"
                )
            ),
            onBackToEditor = {}
        )
    }
}
