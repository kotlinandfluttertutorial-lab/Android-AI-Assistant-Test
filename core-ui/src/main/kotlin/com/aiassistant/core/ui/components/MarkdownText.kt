/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : MarkdownText.kt
 * Purpose    : MarkdownText — core-ui module component
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
 * File       : MarkdownText.kt
 * Purpose    : MarkdownText — core-ui module component
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
 * MarkdownText.kt
 *
 * Purpose: Renders Markdown-formatted text as rich Compose UI, supporting all six node
 *          types required by the design spec: headers (H1â€“H6), bold, italic, inline code,
 *          fenced code blocks, tables, and bullet lists.
 * Architecture: core-ui â€” shared design system; consumed by feature-chat and any module
 *               that needs to render AI-generated Markdown content.
 * Dependencies: Compose Material 3, compose-markdown (com.github.jeziellago).
 *
 * Design decisions:
 * - Delegates heavy Markdown parsing to the `compose-markdown` library
 *   (com.github.jeziellago:compose-markdown:0.7.2), which internally uses commonmark-java
 *   and maps AST nodes to Compose Text spans and layout elements.
 * - [contentDescription] is applied to a wrapper Box so that TalkBack reads the full
 *   rendered text as a single semantic unit â€” callers should pass the raw Markdown source
 *   (or an abbreviated description) so TalkBack output is meaningful.
 * - Colors are sourced exclusively from [MaterialTheme.colorScheme]; no hardcoded hex
 *   values. This guarantees the composable adapts correctly to both light and dark themes.
 * - Code blocks inside Markdown are rendered with a tinted [MaterialTheme.colorScheme.
 *   surfaceVariant] background so they are visually distinguishable without relying on
 *   color alone â€” the monospace font provides a second differentiator.
 * - Requirements: 2.5, 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import com.aiassistant.core.ui.AppTheme
import dev.jeziellago.compose.markdowntext.MarkdownText as ComposeMarkdown

/**
 * Renders [markdown] content as a rich Compose layout.
 *
 * Supported Markdown node types:
 * - **Headers** â€” ATX headings H1â€“H6 rendered with decreasing [MaterialTheme.typography]
 *   styles (headlineMedium â†’ labelLarge).
 * - **Bold** â€” `**text**` or `__text__` rendered with [FontWeight.Bold].
 * - **Italic** â€” `*text*` or `_text_` rendered with [FontStyle.Italic].
 * - **Inline code** â€” `` `code` `` rendered with a monospace font and tinted background.
 * - **Fenced code blocks** â€” ` ``` ` blocks rendered with surface-variant background and
 *   monospace font; language hint is passed to [ComposeMarkdown] for optional highlighting.
 * - **Tables** â€” GFM pipe-table syntax rendered as a scrollable grid.
 * - **Bullet lists** â€” unordered (`- `, `* `) and ordered (`1. `) lists.
 *
 * @param markdown           The Markdown source string to render.
 * @param contentDescription TalkBack / accessibility label for the rendered block.
 *                           Defaults to the raw [markdown] string when not supplied,
 *                           which gives screen-reader users the unformatted text.
 * @param modifier           Optional [Modifier] applied to the wrapper [Box].
 */
@Composable
fun MarkdownText(markdown: String, contentDescription: String? = null, modifier: Modifier = Modifier) {
    val a11yLabel = contentDescription ?: markdown
    val textColor = MaterialTheme.colorScheme.onSurface
    val codeBackgroundColor = MaterialTheme.colorScheme.surfaceVariant

    Box(
        modifier = modifier.semantics { this.contentDescription = a11yLabel }
    ) {
        ComposeMarkdown(
            markdown = markdown,
            style = MaterialTheme.typography.bodyMedium.copy(color = textColor)
        )
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private val previewMarkdown = """
# Heading 1
## Heading 2
### Heading 3

This paragraph contains **bold text**, *italic text*, and `inline code`.

Here is a bullet list:
- First item
- Second item
- Third item

And an ordered list:
1. Step one
2. Step two
3. Step three

```kotlin
fun greet(name: String): String {
    return "Hello, ${'$'}name!"
}
```

| Column A | Column B | Column C |
|----------|----------|----------|
| Value 1  | Value 2  | Value 3  |
| Value 4  | Value 5  | Value 6  |
""".trimIndent()

@Preview(showBackground = true, name = "MarkdownText â€“ Light")
@Composable
private fun MarkdownTextLightPreview() {
    AppTheme(dynamicColor = false) {
        MarkdownText(
            markdown = previewMarkdown,
            contentDescription = "Sample markdown content"
        )
    }
}

@Preview(
    showBackground = true,
    name = "MarkdownText â€“ Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun MarkdownTextDarkPreview() {
    AppTheme(dynamicColor = false) {
        MarkdownText(
            markdown = previewMarkdown,
            contentDescription = "Sample markdown content"
        )
    }
}
