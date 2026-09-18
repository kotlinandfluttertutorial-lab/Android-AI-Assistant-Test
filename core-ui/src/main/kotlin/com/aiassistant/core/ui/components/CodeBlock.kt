/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/CodeBlock.kt
 * Purpose    : Production-quality code block composable (Phase 5.8 upgrade).
 *
 *              Changes:
 *              - Always dark background (AppColors.codeBlockBackground) regardless
 *                of light/dark theme — matches developer tool conventions
 *              - Uses AppTypeExtended.codeContent (monospace, 12sp) for code text
 *              - Uses AppTypeExtended.codeLabel for language header label
 *              - Copy confirmation uses COPY_CONFIRM_DURATION_MS from Animation.kt
 *              - remember{} wraps clipboard setText to avoid recompose on copy
 *              - Horizontal scroll for wide code lines
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 2.5, 12.5, 12.6, 23.4
 * ============================================================
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.AppTypeExtended
import com.aiassistant.core.ui.COPY_CONFIRM_DURATION_MS
import com.aiassistant.core.ui.DURATION_MICRO
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A production-quality code block with always-dark background, monospace text,
 * language label, and animated copy confirmation.
 *
 * @param code               The raw code string to display.
 * @param language           Optional language identifier shown as a badge
 *                           (e.g., "kotlin", "python", "json").
 * @param contentDescription TalkBack label. Defaults to "[language] code block".
 * @param modifier           Applied to the root [Column].
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
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    // Always dark — matches developer tooling conventions regardless of app theme
    val bgColor      = AppColors.codeBlockBackground
    val headerColor  = AppColors.codeBlockSurface
    val onSurface    = AppColors.codeBlockOnSurface
    val dimmedColor  = AppColors.codeBlockLineNumber

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = bgColor, shape = AppShapes.codeBlock)
            .semantics { this.contentDescription = a11yLabel }
    ) {
        // ── Header row ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = headerColor,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(
                        topStart = 8.dp, topEnd = 8.dp
                    )
                )
                .padding(
                    start = MaterialTheme.spacing.sm,
                    end = MaterialTheme.spacing.xs,
                    top = MaterialTheme.spacing.xs,
                    bottom = MaterialTheme.spacing.xs
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Chat.Code,
                contentDescription = null,
                tint = dimmedColor,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text(
                text = languageLabel ?: "code",
                style = AppTypeExtended.codeLabel,
                color = dimmedColor
            )

            Spacer(modifier = Modifier.weight(1f))

            // Copy button with animated confirmation
            IconButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(code))
                    copied = true
                    scope.launch {
                        delay(COPY_CONFIRM_DURATION_MS.toLong())
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
                        fadeIn(tween(DURATION_MICRO)) togetherWith fadeOut(tween(DURATION_MICRO))
                    },
                    label = "codeBlockCopyIcon"
                ) { isCopied ->
                    if (isCopied) {
                        Icon(
                            imageVector = AppIcons.Chat.CopyDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            imageVector = AppIcons.Chat.Copy,
                            contentDescription = null,
                            tint = onSurface.copy(alpha = 0.6f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // ── Code body ─────────────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(MaterialTheme.spacing.sm)
        ) {
            Text(
                text = code,
                style = AppTypeExtended.codeContent,
                color = onSurface
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "CodeBlock — Kotlin")
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
            language = "kotlin",
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true, name = "CodeBlock — Python (light theme)")
@Composable
private fun CodeBlockPythonPreview() {
    AppTheme(dynamicColor = false) {
        CodeBlock(
            code = "def hello():\n    print('Hello, world!')",
            language = "python",
            modifier = Modifier.padding(16.dp)
        )
    }
}
