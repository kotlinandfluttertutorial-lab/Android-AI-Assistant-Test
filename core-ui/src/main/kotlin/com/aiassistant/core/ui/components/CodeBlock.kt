/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : CodeBlock.kt
 * Purpose    : CodeBlock — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * Module     : core-ui
 * File       : CodeBlock.kt
 * Purpose    : CodeBlock — core-ui module component
 *
 * Architecture Layer : Core-UI
 * Pattern Used       : Kotlin Class
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
 * CodeBlock.kt
 *
 * Purpose: A standalone syntax-highlighted code block composable used when AI responses
 *          contain code snippets outside of an inline Markdown context (e.g., the
 *          CodeEditor and CodeAnalysis screens in feature-code).
 * Architecture: core-ui â€” shared design system; consumed by feature-chat, feature-code,
 *               and any module that needs to display standalone code.
 * Dependencies: Compose Material 3, AppTheme tokens.
 *
 * Design decisions:
 * - Renders code in a monospace font on a [MaterialTheme.colorScheme.surfaceVariant]
 *   background so the block is visually distinct from surrounding prose without relying
 *   on color alone â€” the monospace font family provides a second differentiator.
 * - A language badge (icon + label) is always rendered in the header row when
 *   [language] is non-null or non-blank, satisfying the "no color-only" requirement.
 *   The badge uses a short text abbreviation so TalkBack reads it meaningfully.
 * - A single-tap copy-to-clipboard action is provided via a trailing [IconButton] in the
 *   header row. A brief visual feedback (icon swap) confirms the copy without a Toast,
 *   keeping the UX lightweight.
 * - [contentDescription] on the outer container gives TalkBack a semantic label for the
 *   entire block; the copy button has its own independent description.
 * - Colors come exclusively from [MaterialTheme.colorScheme]; no hardcoded hex values.
 * - Requirements: 2.5, 12.5, 12.6, 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A standalone syntax-highlighted code block.
 *
 * @param code               The raw code string to display.
 * @param language           Optional language identifier (e.g., "kotlin", "python",
 *                           "javascript"). Displayed as a badge in the header row and
 *                           used to select the correct syntax highlighting. Pass `null`
 *                           or blank to omit the language badge.
 * @param contentDescription TalkBack label for the block. Defaults to
 *                           "[language] code block" (or "code block" if no language).
 * @param modifier           Optional [Modifier] applied to the root [Column].
 */
@Composable
fun CodeBlock(
    code: String,
    language: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val languageLabel = language?.trim()?.takeIf { it.isNotBlank() }
    val a11yLabel = contentDescription
        ?: if (languageLabel != null) "$languageLabel code block" else "code block"

    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    val backgroundColor = MaterialTheme.colorScheme.surfaceVariant
    val onBackgroundColor = MaterialTheme.colorScheme.onSurfaceVariant
    val headerColor = MaterialTheme.colorScheme.surface

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = backgroundColor,
                shape = MaterialTheme.shapes.small
            )
            .semantics { this.contentDescription = a11yLabel }
    ) {
        // â”€â”€â”€ Header row â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Displays language badge (icon + text) on the left and copy button on the right.
        // Language is never indicated by color alone: the monospace font + "Code" icon
        // provide two non-color cues.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = headerColor,
                    shape = MaterialTheme.shapes.small
                )
                .padding(
                    start = MaterialTheme.spacing.sm,
                    end = MaterialTheme.spacing.xs,
                    top = MaterialTheme.spacing.xs,
                    bottom = MaterialTheme.spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language badge â€” icon + text label so language is not color-only
            Icon(
                imageVector = Icons.Filled.Code,
                contentDescription = null, // described by the badge text below
                tint = onBackgroundColor.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text(
                text = languageLabel ?: "code",
                style = MaterialTheme.typography.labelSmall,
                color = onBackgroundColor.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.weight(1f))

            // Copy to clipboard button with brief "Copied!" visual feedback
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                    coroutineScope.launch {
                        delay(2_000)
                        copied = false
                    }
                },
                modifier = Modifier.semantics {
                    this.contentDescription = if (copied) "Copied to clipboard" else "Copy code"
                }
            ) {
                AnimatedContent(
                    targetState = copied,
                    transitionSpec = {
                        fadeIn(animationSpec = tween(150)) togetherWith
                            fadeOut(animationSpec = tween(150))
                    },
                    label = "copy_icon"
                ) { isCopied ->
                    if (isCopied) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.ContentCopy,
                            contentDescription = null,
                            tint = onBackgroundColor.copy(alpha = 0.7f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // â”€â”€â”€ Code body â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        // Horizontally scrollable so wide code lines do not wrap.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.sm)
        ) {
            Text(
                text = code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace
                ),
                color = onBackgroundColor
            )
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "CodeBlock â€“ Kotlin")
@Composable
private fun CodeBlockKotlinPreview() {
    AppTheme(dynamicColor = false) {
        CodeBlock(
            code = """
                fun fibonacci(n: Int): Long {
                    tailrec fun fib(n: Int, a: Long, b: Long): Long =
                        if (n == 0) a else fib(n - 1, b, a + b)
                    return fib(n, 0L, 1L)
                }
            """.trimIndent(),
            language = "kotlin"
        )
    }
}

@Preview(showBackground = true, name = "CodeBlock â€“ No language")
@Composable
private fun CodeBlockNoLanguagePreview() {
    AppTheme(dynamicColor = false) {
        CodeBlock(
            code = "SELECT id, name FROM users WHERE active = 1;"
        )
    }
}

@Preview(
    showBackground = true,
    name = "CodeBlock â€“ Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun CodeBlockDarkPreview() {
    AppTheme(dynamicColor = false) {
        CodeBlock(
            code = "print('Hello, world!')",
            language = "python"
        )
    }
}
