/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeEditorScreen.kt
 * Purpose    : Code editor composable — Phase E UI update.
 *
 *              Aligned to Stitch design reference (CodeScreen.tsx):
 *              - Dark #1e1e2e editor container with macOS traffic-light dots header
 *              - Inline horizontal-scroll language picker (pill chips)
 *              - 3-column action card grid with per-action active fill colour
 *                (Explain=purple / Fix Bug=red / Generate Tests=green)
 *              - BasicTextField text editor with syntax highlighting (unchanged)
 *              - ExtendedFAB "Analyze" submit button (unchanged)
 *
 * Preserved from prior version:
 *              - All syntax highlighting helpers (keywordsForLanguage, buildSyntaxHighlightedString)
 *              - CodeEditorScreen signature + all ViewModel callbacks
 *              - SupportedLanguage / CodeAction extension functions
 *
 * Architecture Layer : Feature (feature-code) — Compose UI layer.
 * Requirements       : 12.1, 12.2, 12.3, 12.4
 * ============================================================
 */
package com.aiassistant.feature.code

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.SupportedLanguage

// ── Language / action extension functions ────────────────────────────────────

fun SupportedLanguage.toLanguageId(): String = when (this) {
    SupportedLanguage.KOTLIN     -> "kotlin"
    SupportedLanguage.JAVA       -> "java"
    SupportedLanguage.PYTHON     -> "python"
    SupportedLanguage.JAVASCRIPT -> "javascript"
    SupportedLanguage.CPP        -> "cpp"
    SupportedLanguage.SQL        -> "sql"
}

fun SupportedLanguage.displayName(): String = when (this) {
    SupportedLanguage.KOTLIN     -> "Kotlin"
    SupportedLanguage.JAVA       -> "Java"
    SupportedLanguage.PYTHON     -> "Python"
    SupportedLanguage.JAVASCRIPT -> "JavaScript"
    SupportedLanguage.CPP        -> "C++"
    SupportedLanguage.SQL        -> "SQL"
}

fun CodeAction.displayName(): String = when (this) {
    CodeAction.EXPLAIN        -> "Explain"
    CodeAction.FIX_BUG        -> "Fix Bug"
    CodeAction.GENERATE_TESTS -> "Gen Tests"
}

// ── Syntax highlighting helpers ───────────────────────────────────────────────

private fun keywordsForLanguage(language: SupportedLanguage): Set<String> = when (language) {
    SupportedLanguage.KOTLIN -> setOf(
        "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum",
        "when", "if", "else", "for", "while", "do", "return", "import", "package",
        "override", "open", "abstract", "companion", "by", "in", "is", "as", "null",
        "true", "false", "this", "super", "try", "catch", "finally", "throw", "suspend",
        "inline", "reified", "typealias", "init", "constructor", "private", "public",
        "protected", "internal", "lateinit", "lazy", "it", "let", "run", "also", "apply"
    )
    SupportedLanguage.JAVA -> setOf(
        "public", "private", "protected", "static", "final", "abstract", "class",
        "interface", "extends", "implements", "new", "return", "import", "package",
        "if", "else", "for", "while", "do", "switch", "case", "break", "continue",
        "try", "catch", "finally", "throw", "throws", "void", "int", "long", "double",
        "float", "boolean", "char", "byte", "short", "null", "true", "false", "this",
        "super", "instanceof", "enum", "synchronized", "volatile", "transient"
    )
    SupportedLanguage.PYTHON -> setOf(
        "def", "class", "import", "from", "as", "return", "if", "elif", "else",
        "for", "while", "break", "continue", "pass", "try", "except", "finally",
        "raise", "with", "lambda", "yield", "global", "nonlocal", "in", "not", "and",
        "or", "is", "del", "assert", "True", "False", "None", "self", "super",
        "async", "await"
    )
    SupportedLanguage.JAVASCRIPT -> setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while",
        "do", "switch", "case", "break", "continue", "try", "catch", "finally",
        "throw", "class", "extends", "import", "export", "default", "new", "this",
        "super", "null", "undefined", "true", "false", "typeof", "instanceof", "in",
        "of", "async", "await", "yield", "from"
    )
    SupportedLanguage.CPP -> setOf(
        "auto", "bool", "break", "case", "catch", "char", "class", "const",
        "continue", "default", "delete", "do", "double", "else", "enum", "explicit",
        "extern", "false", "float", "for", "friend", "if", "inline", "int", "long",
        "mutable", "namespace", "new", "null", "nullptr", "operator", "private",
        "protected", "public", "return", "short", "signed", "sizeof", "static",
        "struct", "switch", "template", "this", "throw", "true", "try", "typedef",
        "unsigned", "using", "virtual", "void", "volatile", "while"
    )
    SupportedLanguage.SQL -> setOf(
        "SELECT", "FROM", "WHERE", "JOIN", "INSERT", "INTO", "VALUES", "UPDATE",
        "SET", "DELETE", "CREATE", "TABLE", "DROP", "ALTER", "AND", "OR", "NOT",
        "NULL", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "COUNT", "DISTINCT",
        "AS", "CASE", "WHEN", "THEN", "ELSE", "END", "select", "from", "where",
        "join", "insert", "update", "delete", "create", "table", "and", "or",
        "not", "null", "order", "group", "having", "limit", "count", "distinct"
    )
}

@Composable
fun buildSyntaxHighlightedString(
    code: String,
    language: SupportedLanguage,
): androidx.compose.ui.text.AnnotatedString {
    val keywords     = keywordsForLanguage(language)
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor  = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.outline
    val defaultColor = MaterialTheme.colorScheme.onSurface

    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { lineIndex, line ->
            val commentPrefix = when (language) {
                SupportedLanguage.PYTHON -> "#"
                SupportedLanguage.SQL    -> "--"
                else                     -> "//"
            }
            val commentStart = line.indexOf(commentPrefix)
            val codePart    = if (commentStart >= 0) line.substring(0, commentStart) else line
            val commentPart = if (commentStart >= 0) line.substring(commentStart) else null

            tokenizeAndAppend(codePart, keywords, keywordColor, stringColor, defaultColor)

            if (commentPart != null) {
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = commentColor)) {
                    append(commentPart)
                }
            }
            if (lineIndex < lines.lastIndex) append('\n')
        }
    }
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.tokenizeAndAppend(
    text: String,
    keywords: Set<String>,
    keywordColor: androidx.compose.ui.graphics.Color,
    stringColor: androidx.compose.ui.graphics.Color,
    defaultColor: androidx.compose.ui.graphics.Color,
) {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '"' || ch == '\'') {
            val quote = ch; val start = i; i++
            while (i < text.length && text[i] != quote) { if (text[i] == '\\') i++; i++ }
            if (i < text.length) i++
            withStyle(style = androidx.compose.ui.text.SpanStyle(color = stringColor)) {
                append(text.substring(start, i))
            }
            continue
        }
        if (ch.isLetterOrDigit() || ch == '_') {
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
            val word = text.substring(start, i)
            val color = if (word in keywords) keywordColor else defaultColor
            withStyle(style = androidx.compose.ui.text.SpanStyle(color = color)) { append(word) }
            continue
        }
        withStyle(style = androidx.compose.ui.text.SpanStyle(color = defaultColor)) { append(ch) }
        i++
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    uiState: CodeUiState,
    onCodeChange: (String, SupportedLanguage) -> Unit,
    onLanguageSelect: (SupportedLanguage) -> Unit,
    onActionSelect: (CodeAction) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentCode = when (uiState) {
        is CodeUiState.Editing   -> uiState.code
        is CodeUiState.Analyzing -> uiState.code
        else                     -> ""
    }
    val currentLanguage = when (uiState) {
        is CodeUiState.Editing   -> uiState.language
        is CodeUiState.Analyzing -> uiState.language
        else                     -> SupportedLanguage.KOTLIN
    }
    val currentAction = when (uiState) {
        is CodeUiState.Editing -> uiState.selectedAction
        else                   -> CodeAction.EXPLAIN
    }
    val isAnalyzing = uiState is CodeUiState.Analyzing
    val canSubmit   = currentCode.isNotBlank() && !isAnalyzing

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text("Code Assistant") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (canSubmit) onSubmit() },
                icon    = { Icon(Icons.AutoMirrored.Filled.Send, null) },
                text    = { Text("Analyze") },
                modifier = Modifier.semantics {
                    contentDescription = if (canSubmit) "Submit code for analysis"
                                         else "Enter code to analyze"
                },
                containerColor = if (canSubmit) MaterialTheme.colorScheme.primaryContainer
                                 else MaterialTheme.colorScheme.surfaceVariant,
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = MaterialTheme.spacing.md),
        ) {
            // ── Inline language picker — horizontal scroll pill chips ──────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(vertical = MaterialTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                SupportedLanguage.entries.forEach { lang ->
                    val sel = currentLanguage == lang
                    Surface(
                        onClick = { onLanguageSelect(lang) },
                        shape   = RoundedCornerShape(percent = 50),
                        color   = if (sel) MaterialTheme.colorScheme.primary
                                  else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.semantics {
                            contentDescription = "Select ${lang.displayName()}" +
                                if (sel) ", selected" else ""
                        },
                    ) {
                        Text(
                            text     = lang.displayName(),
                            style    = MaterialTheme.typography.labelMedium,
                            color    = if (sel) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        )
                    }
                }
            }

            // ── 3-col action grid with per-action active colour ───────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MaterialTheme.spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
            ) {
                data class Cfg(val action: CodeAction, val activeColor: Color)
                listOf(
                    Cfg(CodeAction.EXPLAIN,        StitchColors.voiceIdle),
                    Cfg(CodeAction.FIX_BUG,        StitchColors.voiceListening),
                    Cfg(CodeAction.GENERATE_TESTS, StitchColors.voiceProcessing),
                ).forEach { (action, activeColor) ->
                    val sel = currentAction == action
                    Surface(
                        onClick  = { onActionSelect(action) },
                        shape    = RoundedCornerShape(14.dp),
                        color    = if (sel) activeColor
                                   else MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.weight(1f).semantics {
                            contentDescription = action.displayName() + if (sel) ", selected" else ""
                        },
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier            = Modifier.padding(vertical = MaterialTheme.spacing.sm),
                        ) {
                            Icon(
                                imageVector = when (action) {
                                    CodeAction.EXPLAIN        -> Icons.Filled.Info
                                    CodeAction.FIX_BUG        -> Icons.Filled.BugReport
                                    CodeAction.GENERATE_TESTS -> Icons.Filled.Science
                                },
                                contentDescription = null,
                                tint    = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(
                                text  = action.displayName(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (sel) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            // ── Dark editor container (Stitch: #1e1e2e + traffic-light header) ─
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(StitchColors.codeEditorBg)
                    .semantics { contentDescription = "Code editor" },
            ) {
                // Traffic-light dots header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(
                        StitchColors.codeEditorTrafficRed,
                        StitchColors.codeEditorTrafficAmber,
                        StitchColors.codeEditorTrafficGreen,
                    ).forEach { dotColor ->
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(dotColor),
                        )
                    }
                    Spacer(Modifier.width(MaterialTheme.spacing.sm))
                    Text(
                        text  = "main.${currentLanguage.toLanguageId()}",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = Color.White.copy(alpha = 0.5f),
                    )
                }

                // Editor body
                if (isAnalyzing) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            LoadingIndicator(
                                style = LoadingIndicatorStyle.CIRCULAR,
                                contentDescription = "Analyzing code\u2026",
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "Analyzing\u2026",
                                style = MaterialTheme.typography.bodyMedium,
                                color = StitchColors.codeEditorFg.copy(alpha = 0.7f),
                            )
                        }
                    }
                } else {
                    val scrollState = rememberScrollState()
                    val annotatedCode = buildSyntaxHighlightedString(currentCode, currentLanguage)
                    val codeStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 13.sp,
                        lineHeight = 22.sp,
                        color      = StitchColors.codeEditorFg,
                    )
                    BasicTextField(
                        value = if (currentCode.isEmpty())
                            androidx.compose.ui.text.input.TextFieldValue("")
                        else
                            androidx.compose.ui.text.input.TextFieldValue(
                                annotatedString = annotatedCode,
                                selection = androidx.compose.ui.text.TextRange(currentCode.length),
                            ),
                        onValueChange = { tfv -> onCodeChange(tfv.text, currentLanguage) },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(14.dp),
                        textStyle    = codeStyle,
                        cursorBrush  = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { inner ->
                            Box {
                                if (currentCode.isEmpty()) {
                                    Text(
                                        "Paste or type your ${currentLanguage.displayName()} code here\u2026",
                                        style = codeStyle.copy(
                                            color = StitchColors.codeEditorFg.copy(alpha = 0.4f),
                                        ),
                                    )
                                }
                                inner()
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "CodeEditorScreen — Idle")
@Composable
private fun CodeEditorIdlePreview() {
    AppTheme(dynamicColor = false) {
        CodeEditorScreen(
            uiState = CodeUiState.Idle,
            onCodeChange = { _, _ -> },
            onLanguageSelect = {},
            onActionSelect = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, name = "CodeEditorScreen — Editing")
@Composable
private fun CodeEditorEditingPreview() {
    AppTheme(dynamicColor = false) {
        CodeEditorScreen(
            uiState = CodeUiState.Editing(
                code = "fun hello() = println(\"Hello, World!\")",
                language = SupportedLanguage.KOTLIN,
                selectedAction = CodeAction.EXPLAIN
            ),
            onCodeChange = { _, _ -> },
            onLanguageSelect = {},
            onActionSelect = {},
            onSubmit = {}
        )
    }
}

@Preview(showBackground = true, name = "CodeEditorScreen — Analyzing")
@Composable
private fun CodeEditorAnalyzingPreview() {
    AppTheme(dynamicColor = false) {
        CodeEditorScreen(
            uiState = CodeUiState.Analyzing(
                code = "fun hello() = println(\"Hello, World!\")",
                language = SupportedLanguage.KOTLIN,
                action = CodeAction.EXPLAIN
            ),
            onCodeChange = { _, _ -> },
            onLanguageSelect = {},
            onActionSelect = {},
            onSubmit = {}
        )
    }
}
