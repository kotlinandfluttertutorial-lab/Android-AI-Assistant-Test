/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/SurfaceFillTextField.kt
 * Purpose    : Design-system text field with a filled surface background
 *              instead of the standard M3 outlined border.  Used on screens
 *              that sit on top of a coloured or gradient background (Login,
 *              Register) where the outlined border disappears visually.
 *
 * Architecture Layer : Core-UI — shared composable.
 *                      Used by feature-auth LoginScreen, RegisterScreen, and
 *                      any other screen that needs a filled-surface input on
 *                      a non-neutral background.
 *
 * Dependencies       : Compose Material 3, AppColors (surfaceTonal3),
 *                      MaterialTheme.spacing.
 *
 * Design Decision    : TextField (filled variant) is used instead of
 *                      OutlinedTextField because the surfaceTonal3 container
 *                      provides contrast against the gradient background without
 *                      needing a visible border.  The indicator line is kept
 *                      (M3 default) to signal focus state to keyboard users.
 *                      The container colour switches between light/dark
 *                      surfaceTonal3 variants automatically via isSystemInDarkTheme.
 *                      All accessibility requirements (contentDescription,
 *                      error text, label) mirror OutlinedTextField behaviour.
 *
 * Requirements       : 24.1, 24.3, 23.1 (contentDescription on interactive elements)
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import com.aiassistant.core.ui.AppColors

/**
 * Filled-surface text field for use on gradient or coloured backgrounds.
 *
 * Wraps Material 3 [TextField] with:
 * - Container colour: [AppColors.surfaceTonal3Light] / [AppColors.surfaceTonal3Dark]
 * - Transparent indicator (unfocused) → primary colour (focused)
 * - Error state via [isError] / [supportingText]
 * - Full accessibility: [label], [placeholder], [contentDescriptionText]
 *
 * @param value                 Current field value.
 * @param onValueChange         Called on every keystroke.
 * @param label                 Floating label text (e.g. "Email address").
 * @param modifier              Modifier applied to the [TextField].
 * @param placeholder           Hint shown when the field is empty and unfocused.
 * @param trailingIcon          Optional trailing icon composable (e.g. show/hide password).
 * @param isError               Whether to display the error state.
 * @param supportingText        Error / helper text shown below the field; null hides it.
 * @param visualTransformation  Used to mask passwords.
 * @param keyboardOptions       IME options (type, action).
 * @param keyboardActions       IME action callbacks (next, done).
 * @param singleLine            If true the field does not wrap (default true).
 * @param enabled               Whether the field accepts input.
 * @param contentDescriptionText Accessibility description for TalkBack.
 */
@Composable
fun SurfaceFillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isError: Boolean = false,
    supportingText: String? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    contentDescriptionText: String = label,
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) AppColors.surfaceTonal3Dark else AppColors.surfaceTonal3Light

    TextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.semantics { contentDescription = contentDescriptionText },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        trailingIcon = trailingIcon,
        isError = isError,
        supportingText = supportingText?.let { msg -> { Text(msg) } },
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        enabled = enabled,
        colors = TextFieldDefaults.colors(
            // Container
            focusedContainerColor   = containerColor,
            unfocusedContainerColor = containerColor,
            disabledContainerColor  = containerColor.copy(alpha = 0.38f),
            errorContainerColor     = containerColor,
            // Indicator line (bottom border)
            focusedIndicatorColor   = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = Color.Transparent,
            errorIndicatorColor     = MaterialTheme.colorScheme.error,
            // Label
            focusedLabelColor   = MaterialTheme.colorScheme.primary,
            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            // Text
            focusedTextColor   = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}
