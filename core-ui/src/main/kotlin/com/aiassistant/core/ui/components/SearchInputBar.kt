/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/SearchInputBar.kt
 * Purpose    : Standalone pill-shaped search input bar used on HistoryScreen,
 *              ToolsScreen, and any other screen that needs a dedicated text-
 *              search affordance (distinct from [MessageInputBar] which is the
 *              AI chat compositor).
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-history, feature-dashboard (Tools),
 *                      and any future search entry points.
 *
 * Design reference   : HistoryScreen.tsx — search bar (r24 pill, card bg)
 *                      ToolsScreen.tsx — search bar (r24 pill, placeholder only)
 *                      HomeScreen.tsx  — "Ask me anything…" tap-to-navigate bar
 *
 * Design decisions   :
 * - Two interaction modes are supported:
 *     [SearchInputBarMode.EDITABLE] — live-typed search (HistoryScreen).
 *     [SearchInputBarMode.TAPPABLE] — non-editable button that fires [onTap]
 *       on click (HomeScreen's search → navigates to ChatScreen).
 * - Uses Material 3 [BasicTextField] wrapped in a custom pill container rather
 *   than [SearchBar] / [OutlinedTextField] to match the Stitch pill shape
 *   exactly (no floating label, no underline).
 * - Leading search icon is always shown; trailing clear button appears only when
 *   the field is focused AND the value is non-empty.
 * - [contentDescription] on the root covers TalkBack for the tappable variant
 *   where there is no keyboard interaction.
 * - Colors from [MaterialTheme.colorScheme] — no hardcoded hex values.
 *
 * Requirements       : 23.1, 24.1
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

// ── Enum ──────────────────────────────────────────────────────────────────────

/** Controls whether [SearchInputBar] accepts keyboard input or acts as a tap target. */
enum class SearchInputBarMode {
    /** The field accepts typed input — full BasicTextField behaviour. */
    EDITABLE,
    /**
     * The field is a non-editable button that fires [SearchInputBar]'s [onTap]
     * callback on click.  Used on HomeScreen where tapping the bar navigates to
     * the chat screen rather than opening a keyboard.
     */
    TAPPABLE,
}

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Pill-shaped search input bar.
 *
 * @param value         Current text value (ignored in [SearchInputBarMode.TAPPABLE]).
 * @param onValueChange Callback receiving updated text on each keystroke.
 * @param placeholder   Placeholder string shown when [value] is empty.
 * @param mode          [SearchInputBarMode.EDITABLE] or [SearchInputBarMode.TAPPABLE].
 * @param onTap         Called when the bar is tapped in [SearchInputBarMode.TAPPABLE]
 *                      mode, or when the keyboard Search / Done action fires.
 * @param onClear       Called when the trailing clear-icon is tapped.
 *                      If null the clear icon is never shown.
 * @param modifier      Optional layout modifier.
 */
@Composable
fun SearchInputBar(
    value: String = "",
    onValueChange: (String) -> Unit = {},
    placeholder: String = "Search…",
    mode: SearchInputBarMode = SearchInputBarMode.EDITABLE,
    onTap: (() -> Unit)? = null,
    onClear: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }

    val containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    val contentColor = MaterialTheme.colorScheme.onSurface
    val placeholderColor = MaterialTheme.colorScheme.onSurfaceVariant
    val showClear = onClear != null && value.isNotEmpty() && isFocused

    val rootModifier = if (mode == SearchInputBarMode.TAPPABLE) {
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(AppShapes.pill)
            .background(containerColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null, // Material 3 default ripple provided by Surface/clickable
                onClick = { onTap?.invoke() },
            )
            .semantics {
                contentDescription = placeholder
                role = Role.Button
            }
    } else {
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(AppShapes.pill)
            .background(containerColor)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = rootModifier.padding(horizontal = MaterialTheme.spacing.md),
    ) {
        Icon(
            imageVector = AppIcons.Chat.Search,
            contentDescription = null, // described at container level
            tint = placeholderColor,
            modifier = Modifier.size(20.dp),
        )

        Spacer(Modifier.width(MaterialTheme.spacing.sm))

        if (mode == SearchInputBarMode.TAPPABLE) {
            // Non-interactive placeholder text
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyLarge,
                color = placeholderColor,
                modifier = Modifier.weight(1f),
            )
        } else {
            // Live editable text field
            Box(modifier = Modifier.weight(1f)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = contentColor),
                    singleLine = true,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            onTap?.invoke()
                        },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    decorationBox = { innerTextField ->
                        if (value.isEmpty()) {
                            Text(
                                text = placeholder,
                                style = MaterialTheme.typography.bodyLarge,
                                color = placeholderColor,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        // Trailing clear button — animated in/out
        AnimatedVisibility(
            visible = showClear,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            IconButton(
                onClick = {
                    onClear?.invoke()
                    focusManager.clearFocus()
                },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = AppIcons.Navigation.Close,
                    contentDescription = "Clear search",
                    tint = placeholderColor,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true, name = "SearchInputBar — Editable empty")
@Composable
private fun EditableEmptyPreview() {
    AppTheme(dynamicColor = false) {
        SearchInputBar(
            value = "",
            placeholder = "Search conversations…",
            mode = SearchInputBarMode.EDITABLE,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "SearchInputBar — Editable with text")
@Composable
private fun EditableFilledPreview() {
    AppTheme(dynamicColor = false) {
        var text by remember { mutableStateOf("React performance") }
        SearchInputBar(
            value = text,
            onValueChange = { text = it },
            placeholder = "Search conversations…",
            mode = SearchInputBarMode.EDITABLE,
            onClear = { text = "" },
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(showBackground = true, name = "SearchInputBar — Tappable (Home)")
@Composable
private fun TappablePreview() {
    AppTheme(dynamicColor = false) {
        SearchInputBar(
            placeholder = "Ask me anything…",
            mode = SearchInputBarMode.TAPPABLE,
            onTap = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
