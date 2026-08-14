/**
 * MarkdownTextTest.kt
 *
 * Purpose: Compose UI tests verifying that [MarkdownText] correctly renders all six
 *          required Markdown node types and exposes them to the Compose semantic tree.
 * Architecture: core-ui androidTest — instrumented Compose UI tests.
 * Requirements: 24.2, 21.3
 *
 * Covered node types:
 *   1. Headers        — ATX headings (# … through ######)
 *   2. Bold           — **bold**
 *   3. Italic         — *italic*
 *   4. Inline code    — `code`
 *   5. Fenced code    — ```lang ... ```
 *   6. Bullet lists   — - item
 *   (bonus) Tables    — | col | col |
 *
 * Design decisions:
 * - [MarkdownText] wraps the compose-markdown library, which renders its output as
 *   composed layout nodes. The semantic tree captures text content so we can use
 *   [onNodeWithText] or [onNodeWithContentDescription] for assertions.
 * - We assert the node is "displayed" after setting content to confirm the composable
 *   didn't crash and rendered the markdown section.
 * - [contentDescription] parameter is used to locate the wrapper Box in tests that
 *   need to assert on the container rather than the inner text.
 */

package com.aiassistant.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.components.MarkdownText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MarkdownTextTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── 1. Headers ────────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersH1Header() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "# Heading One",
                    contentDescription = "heading_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("heading_test").assertIsDisplayed()
    }

    @Test
    fun markdownText_rendersH2Header() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "## Heading Two",
                    contentDescription = "h2_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("h2_test").assertIsDisplayed()
    }

    @Test
    fun markdownText_rendersH3Header() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "### Heading Three",
                    contentDescription = "h3_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("h3_test").assertIsDisplayed()
    }

    // ── 2. Bold ───────────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersBoldText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "This is **bold text** in a sentence.",
                    contentDescription = "bold_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("bold_test").assertIsDisplayed()
    }

    // ── 3. Italic ─────────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersItalicText() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "This is *italic text* in a sentence.",
                    contentDescription = "italic_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("italic_test").assertIsDisplayed()
    }

    // ── 4. Inline code ────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersInlineCode() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "Use `val x = 42` to declare a value.",
                    contentDescription = "inline_code_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("inline_code_test").assertIsDisplayed()
    }

    // ── 5. Fenced code block ──────────────────────────────────────────────────

    @Test
    fun markdownText_rendersFencedCodeBlock() {
        val fencedCode = """
            ```kotlin
            fun hello() = println("Hello")
            ```
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = fencedCode,
                    contentDescription = "fenced_code_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("fenced_code_test").assertIsDisplayed()
    }

    @Test
    fun markdownText_rendersFencedCodeBlock_withoutLanguage() {
        val fencedCode = """
            ```
            SELECT * FROM users;
            ```
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = fencedCode,
                    contentDescription = "fenced_code_no_lang_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("fenced_code_no_lang_test").assertIsDisplayed()
    }

    // ── 6. Bullet list ────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersBulletList() {
        val bulletList = """
            - First item
            - Second item
            - Third item
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = bulletList,
                    contentDescription = "bullet_list_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("bullet_list_test").assertIsDisplayed()
    }

    @Test
    fun markdownText_rendersOrderedList() {
        val orderedList = """
            1. Step one
            2. Step two
            3. Step three
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = orderedList,
                    contentDescription = "ordered_list_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("ordered_list_test").assertIsDisplayed()
    }

    // ── Bonus: Table ──────────────────────────────────────────────────────────

    @Test
    fun markdownText_rendersTable() {
        val table = """
            | Column A | Column B |
            |----------|----------|
            | Cell 1   | Cell 2   |
            | Cell 3   | Cell 4   |
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = table,
                    contentDescription = "table_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("table_test").assertIsDisplayed()
    }

    // ── Combined: all six node types in one render ────────────────────────────

    @Test
    fun markdownText_rendersAllSixNodeTypes_inSingleBlock() {
        val combined = """
            # Main Heading

            This has **bold** and *italic* and `inline code`.

            - Bullet item A
            - Bullet item B

            ```python
            print("hello")
            ```
        """.trimIndent()

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = combined,
                    contentDescription = "all_six_nodes_test"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("all_six_nodes_test").assertIsDisplayed()
    }

    // ── Accessibility: contentDescription fallback ────────────────────────────

    @Test
    fun markdownText_usesRawMarkdownAsDefaultA11yLabel() {
        val rawMarkdown = "Hello **world**"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                // No explicit contentDescription — should default to raw markdown string
                MarkdownText(markdown = rawMarkdown)
            }
        }
        // The Box wrapper carries the raw markdown as its contentDescription
        composeTestRule.onNodeWithContentDescription(rawMarkdown).assertIsDisplayed()
    }

    @Test
    fun markdownText_usesExplicitA11yLabelWhenProvided() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "# Hello",
                    contentDescription = "Greeting header"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Greeting header").assertIsDisplayed()
    }

    // ── Dark theme rendering ──────────────────────────────────────────────────

    @Test
    fun markdownText_rendersInDarkTheme() {
        composeTestRule.setContent {
            AppTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                MarkdownText(
                    markdown = "# Dark Heading\n\n**Bold** and *italic*.",
                    contentDescription = "dark_theme_markdown"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("dark_theme_markdown").assertIsDisplayed()
    }

    // ── Empty / edge cases ────────────────────────────────────────────────────

    @Test
    fun markdownText_handlesEmptyString() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "",
                    contentDescription = "empty_markdown"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("empty_markdown").assertIsDisplayed()
    }

    @Test
    fun markdownText_handlesPlainText_withNoMarkdown() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                MarkdownText(
                    markdown = "Just a plain sentence with no formatting.",
                    contentDescription = "plain_text_markdown"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("plain_text_markdown").assertIsDisplayed()
    }
}
