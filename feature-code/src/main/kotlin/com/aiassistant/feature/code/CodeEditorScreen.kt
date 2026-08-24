/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-code
 * File       : CodeEditorScreen.kt
 * Purpose    : Compose UI screen for the CodeEditor feature
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
 * File       : CodeEditorScreen.kt
 * Purpose    : Compose UI screen for the CodeEditor feature
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
 * CodeEditorScreen.kt
 *
 * Purpose: Code editor composable with syntax-highlighted input supporting six languages,
 *          language selector, action selector (Explain/Fix Bug/Generate Tests), and submit.
 * Architecture: feature-code â€” MVVM presentation layer (stateless composable pattern).
 * Dependencies: core-ui (LoadingIndicator), domain (SupportedLanguage, CodeAction),
 *               Compose Material 3, Compose Foundation
 *
 * Requirements: 12.1, 12.2, 12.3, 12.4
 */
package com.aiassistant.feature.code

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.aiassistant.core.ui.components.LoadingIndicator
import com.aiassistant.core.ui.components.LoadingIndicatorStyle
import com.aiassistant.domain.model.CodeAction
import com.aiassistant.domain.model.SupportedLanguage

// â”€â”€â”€ Syntax highlighting helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/** Returns the language identifier string used for CodeBlock rendering (Requirement 12.6). */
fun SupportedLanguage.toLanguageId(): String = when (this) {
    SupportedLanguage.KOTLIN -> "kotlin"
    SupportedLanguage.JAVA -> "java"
    SupportedLanguage.PYTHON -> "python"
    SupportedLanguage.JAVASCRIPT -> "javascript"
    SupportedLanguage.CPP -> "cpp"
    SupportedLanguage.SQL -> "sql"
}

/** Human-readable display label for UI selectors. */
fun SupportedLanguage.displayName(): String = when (this) {
    SupportedLanguage.KOTLIN -> "Kotlin"
    SupportedLanguage.JAVA -> "Java"
    SupportedLanguage.PYTHON -> "Python"
    SupportedLanguage.JAVASCRIPT -> "JavaScript"
    SupportedLanguage.CPP -> "C++"
    SupportedLanguage.SQL -> "SQL"
}

/** Human-readable display label for action selectors. */
fun CodeAction.displayName(): String = when (this) {
    CodeAction.EXPLAIN -> "Explain"
    CodeAction.FIX_BUG -> "Fix Bug"
    CodeAction.GENERATE_TESTS -> "Generate Tests"
}

/** Keyword sets for simple keyword-based syntax highlighting (Requirement 12.1). */
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
        "of", "async", "await", "yield", "from", "arrow"
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
        "SELECT", "FROM", "WHERE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "ON",
        "INSERT", "INTO", "VALUES", "UPDATE", "SET", "DELETE", "CREATE", "TABLE",
        "DROP", "ALTER", "ADD", "COLUMN", "INDEX", "PRIMARY", "KEY", "FOREIGN",
        "REFERENCES", "NOT", "NULL", "UNIQUE", "DEFAULT", "AND", "OR", "IN",
        "LIKE", "BETWEEN", "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET",
        "COUNT", "SUM", "AVG", "MIN", "MAX", "DISTINCT", "AS", "CASE", "WHEN",
        "THEN", "ELSE", "END", "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION",
        // lowercase variants
        "select", "from", "where", "join", "insert", "into", "update", "delete",
        "create", "table", "drop", "alter", "and", "or", "not", "null", "order",
        "group", "having", "limit", "count", "distinct", "as", "case", "when"
    )
}

/**
 * Builds an [androidx.compose.ui.text.AnnotatedString] with simple keyword-based
 * syntax highlighting for the given [language] (Requirement 12.1).
 *
 * Colors:
 * - Keywords: [MaterialTheme.colorScheme.primary]
 * - String literals (single/double-quoted): [MaterialTheme.colorScheme.tertiary]
 * - Line comments (// and #): [MaterialTheme.colorScheme.outline]
 * - Everything else: default on-surface color
 */
@Composable
fun buildSyntaxHighlightedString(code: String, language: SupportedLanguage): androidx.compose.ui.text.AnnotatedString {
    val keywords = keywordsForLanguage(language)
    val keywordColor = MaterialTheme.colorScheme.primary
    val stringColor = MaterialTheme.colorScheme.tertiary
    val commentColor = MaterialTheme.colorScheme.outline
    val defaultColor = MaterialTheme.colorScheme.onSurface

    return buildAnnotatedString {
        val lines = code.lines()
        lines.forEachIndexed { lineIndex, line ->
            // Detect line comment prefix based on language
            val commentPrefix = when (language) {
                SupportedLanguage.PYTHON -> "#"
                SupportedLanguage.SQL -> "--"
                else -> "//"
            }

            val commentStart = line.indexOf(commentPrefix)
            val codePart = if (commentStart >= 0) line.substring(0, commentStart) else line
            val commentPart = if (commentStart >= 0) line.substring(commentStart) else null

            // Tokenise the code portion (before any comment)
            tokenizeAndAppend(
                text = codePart,
                keywords = keywords,
                keywordColor = keywordColor,
                stringColor = stringColor,
                defaultColor = defaultColor
            )

            // Append comment in comment color
            if (commentPart != null) {
                withStyle(
                    style = androidx.compose.ui.text.SpanStyle(color = commentColor)
                ) {
                    append(commentPart)
                }
            }

            if (lineIndex < lines.lastIndex) append('\n')
        }
    }
}

/** Tokenises [text] into words/symbols and colours keywords and string literals. */
private fun androidx.compose.ui.text.AnnotatedString.Builder.tokenizeAndAppend(
    text: String,
    keywords: Set<String>,
    keywordColor: androidx.compose.ui.graphics.Color,
    stringColor: androidx.compose.ui.graphics.Color,
    defaultColor: androidx.compose.ui.graphics.Color
) {
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        // String literal detection
        if (ch == '"' || ch == '\'') {
            val quote = ch
            val start = i
            i++ // skip opening quote
            while (i < text.length && text[i] != quote) {
                if (text[i] == '\\') i++ // skip escaped char
                i++
            }
            if (i < text.length) i++ // skip closing quote
            withStyle(style = androidx.compose.ui.text.SpanStyle(color = stringColor)) {
                append(text.substring(start, i))
            }
            continue
        }

        // Word / identifier detection
        if (ch.isLetterOrDigit() || ch == '_') {
            val start = i
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) {
                i++
            }
            val word = text.substring(start, i)
            if (word in keywords) {
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = keywordColor)) {
                    append(word)
                }
            } else {
                withStyle(style = androidx.compose.ui.text.SpanStyle(color = defaultColor)) {
                    append(word)
                }
            }
            continue
        }

        // All other characters â€” append with default color
        withStyle(style = androidx.compose.ui.text.SpanStyle(color = defaultColor)) {
            append(ch)
        }
        i++
    }
}

// â”€â”€â”€ Screen composable â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Code editor screen composable.
 *
 * Stateless composable â€” all state changes are delegated to ViewModel via lambda callbacks.
 *
 * @param uiState          The current [CodeUiState] observed from [CodeViewModel].
 * @param onCodeChange     Called when the user edits the code content.
 * @param onLanguageSelect Called when the user selects a language from the dropdown.
 * @param onActionSelect   Called when the user selects an analysis action chip.
 * @param onSubmit         Called when the user taps the submit FAB.
 * @param modifier         Optional [Modifier] applied to the root [Scaffold].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    uiState: CodeUiState,
    onCodeChange: (String, SupportedLanguage) -> Unit,
    onLanguageSelect: (SupportedLanguage) -> Unit,
    onActionSelect: (CodeAction) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Derive displayed values from state
    val currentCode = when (uiState) {
        is CodeUiState.Editing -> uiState.code
        is CodeUiState.Analyzing -> uiState.code
        else -> ""
    }
    val currentLanguage = when (uiState) {
        is CodeUiState.Editing -> uiState.language
        is CodeUiState.Analyzing -> uiState.language
        else -> SupportedLanguage.KOTLIN
    }
    val currentAction = when (uiState) {
        is CodeUiState.Editing -> uiState.selectedAction
        else -> CodeAction.EXPLAIN
    }
    val isAnalyzing = uiState is CodeUiState.Analyzing
    val canSubmit = currentCode.isNotBlank() && !isAnalyzing

    var languageMenuExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Code Assistant") },
                actions = {
                    // Language selector dropdown
                    Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                        OutlinedButton(
                            onClick = { languageMenuExpanded = true },
                            modifier = Modifier.semantics {
                                contentDescription = "Select language: ${currentLanguage.displayName()}"
                            }
                        ) {
                            Text(currentLanguage.displayName())
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize)
                            )
                        }
                        DropdownMenu(
                            expanded = languageMenuExpanded,
                            onDismissRequest = { languageMenuExpanded = false }
                        ) {
                            SupportedLanguage.entries.forEach { lang ->
                                DropdownMenuItem(
                                    text = { Text(lang.displayName()) },
                                    onClick = {
                                        onLanguageSelect(lang)
                                        languageMenuExpanded = false
                                    },
                                    modifier = Modifier.semantics {
                                        contentDescription = "Select ${lang.displayName()}"
                                    }
                                )
                            }
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (canSubmit) onSubmit() },
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null
                    )
                },
                text = { Text("Analyze") },
                modifier = Modifier.semantics {
                    contentDescription = if (canSubmit) "Submit code for analysis" else "Enter code to analyze"
                },
                containerColor = if (canSubmit) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // â”€â”€ Action selector chips â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CodeAction.entries.forEach { action ->
                    FilterChip(
                        selected = currentAction == action,
                        onClick = { onActionSelect(action) },
                        label = { Text(action.displayName()) },
                        leadingIcon = {
                            Icon(
                                imageVector = when (action) {
                                    CodeAction.EXPLAIN -> Icons.Filled.Info
                                    CodeAction.FIX_BUG -> Icons.Filled.BugReport
                                    CodeAction.GENERATE_TESTS -> Icons.Filled.Science
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription =
                                "${action.displayName()} action${if (currentAction == action) ", selected" else ""}"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // â”€â”€ Code editor â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = MaterialTheme.shapes.small
                    )
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    )
                    .semantics { contentDescription = "Code editor" }
            ) {
                if (isAnalyzing) {
                    // Show loading overlay while analysis is in flight
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        LoadingIndicator(
                            style = LoadingIndicatorStyle.CIRCULAR,
                            contentDescription = "Analyzing codeâ€¦"
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Analyzingâ€¦",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    val scrollState = rememberScrollState()
                    val annotatedCode = buildSyntaxHighlightedString(
                        code = currentCode,
                        language = currentLanguage
                    )
                    val codeStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    BasicTextField(
                        value = if (currentCode.isEmpty()) {
                            androidx.compose.ui.text.input.TextFieldValue("")
                        } else {
                            androidx.compose.ui.text.input.TextFieldValue(
                                annotatedString = annotatedCode,
                                selection = androidx.compose.ui.text.TextRange(currentCode.length)
                            )
                        },
                        onValueChange = { tfv ->
                            onCodeChange(tfv.text, currentLanguage)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(12.dp),
                        textStyle = codeStyle,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        decorationBox = { innerTextField ->
                            Box {
                                if (currentCode.isEmpty()) {
                                    Text(
                                        text = "Paste or type your ${currentLanguage.displayName()} code hereâ€¦",
                                        style = codeStyle.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                        )
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp)) // FAB clearance
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "CodeEditorScreen â€“ Idle")
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

@Preview(showBackground = true, name = "CodeEditorScreen â€“ Editing")
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

@Preview(showBackground = true, name = "CodeEditorScreen â€“ Analyzing")
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
