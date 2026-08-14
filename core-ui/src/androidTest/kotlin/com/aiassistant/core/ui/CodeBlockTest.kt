/**
 * CodeBlockTest.kt
 *
 * Purpose: Compose UI tests verifying that [CodeBlock] correctly displays the language
 *          identifier badge, copy button, and code content.
 * Architecture: core-ui androidTest — instrumented Compose UI tests.
 * Requirements: 24.2, 21.3
 *
 * Design decisions:
 * - The language label is rendered as a [Text] node inside the header row, so we can
 *   locate it with [onNodeWithText].
 * - The copy button's content description changes from "Copy code" → "Copied to clipboard"
 *   after a tap; we verify both states.
 * - [semantics { contentDescription = ... }] on the outer Column is tested via
 *   [onNodeWithContentDescription] to verify the a11y label.
 */

package com.aiassistant.core.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aiassistant.core.ui.components.CodeBlock
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodeBlockTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ── Language identifier display ────────────────────────────────────────────

    @Test
    fun codeBlock_displaysKotlinLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "val x = 42", language = "kotlin")
            }
        }
        composeTestRule.onNodeWithText("kotlin").assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysPythonLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "print('hello')", language = "python")
            }
        }
        composeTestRule.onNodeWithText("python").assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysJavaScriptLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "console.log('hi')", language = "javascript")
            }
        }
        composeTestRule.onNodeWithText("javascript").assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysSqlLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "SELECT * FROM users;", language = "sql")
            }
        }
        composeTestRule.onNodeWithText("sql").assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysJavaLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(
                    code = "System.out.println(\"Hello\");",
                    language = "java"
                )
            }
        }
        composeTestRule.onNodeWithText("java").assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysCppLanguageLabel() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "#include <iostream>", language = "c++")
            }
        }
        composeTestRule.onNodeWithText("c++").assertIsDisplayed()
    }

    // ── No-language fallback ─────────────────────────────────────────────────

    @Test
    fun codeBlock_showsCodeFallbackLabel_whenNoLanguageProvided() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "some code here")
            }
        }
        // When language is null, the badge shows "code"
        composeTestRule.onNodeWithText("code").assertIsDisplayed()
    }

    @Test
    fun codeBlock_showsCodeFallbackLabel_whenBlankLanguageProvided() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "some code here", language = "   ")
            }
        }
        composeTestRule.onNodeWithText("code").assertIsDisplayed()
    }

    // ── Code content display ──────────────────────────────────────────────────

    @Test
    fun codeBlock_displaysCodeContent() {
        val codeSnippet = "fun add(a: Int, b: Int) = a + b"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = codeSnippet, language = "kotlin")
            }
        }
        composeTestRule.onNodeWithText(codeSnippet).assertIsDisplayed()
    }

    @Test
    fun codeBlock_displaysMultilineCode() {
        val multiline = "line one\nline two\nline three"

        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = multiline, language = "text")
            }
        }
        composeTestRule.onNodeWithText(multiline).assertIsDisplayed()
    }

    // ── Copy button ───────────────────────────────────────────────────────────

    @Test
    fun codeBlock_hasCopyCodeButton_beforeTap() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "val x = 1", language = "kotlin")
            }
        }
        composeTestRule.onNodeWithContentDescription("Copy code").assertIsDisplayed()
    }

    @Test
    fun codeBlock_showsCopiedFeedback_afterCopyButtonTap() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "val x = 1", language = "kotlin")
            }
        }

        // Tap the copy button
        composeTestRule.onNodeWithContentDescription("Copy code").performClick()

        // Icon should change to "Copied to clipboard" feedback
        composeTestRule.onNodeWithContentDescription("Copied to clipboard").assertIsDisplayed()
    }

    // ── Accessibility: contentDescription ────────────────────────────────────

    @Test
    fun codeBlock_hasDefaultA11yLabel_withLanguage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "val x = 1", language = "kotlin")
            }
        }
        // Default a11y label: "[language] code block"
        composeTestRule.onNodeWithContentDescription("kotlin code block").assertIsDisplayed()
    }

    @Test
    fun codeBlock_hasDefaultA11yLabel_withoutLanguage() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(code = "some code")
            }
        }
        // Default a11y label when no language: "code block"
        composeTestRule.onNodeWithContentDescription("code block").assertIsDisplayed()
    }

    @Test
    fun codeBlock_usesExplicitA11yLabel_whenProvided() {
        composeTestRule.setContent {
            AppTheme(dynamicColor = false) {
                CodeBlock(
                    code = "val x = 1",
                    language = "kotlin",
                    contentDescription = "Fibonacci implementation"
                )
            }
        }
        composeTestRule.onNodeWithContentDescription("Fibonacci implementation").assertIsDisplayed()
    }

    // ── Dark theme ────────────────────────────────────────────────────────────

    @Test
    fun codeBlock_rendersInDarkTheme() {
        composeTestRule.setContent {
            AppTheme(themeMode = ThemeMode.DARK, dynamicColor = false) {
                CodeBlock(code = "print('dark mode')", language = "python")
            }
        }
        composeTestRule.onNodeWithText("python").assertIsDisplayed()
    }
}
