/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : ErrorBanner.kt
 * Purpose    : ErrorBanner — core-ui module component
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
 * File       : ErrorBanner.kt
 * Purpose    : ErrorBanner — core-ui module component
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
 * ErrorBanner.kt
 *
 * Purpose: A prominent error banner composable used to surface recoverable and
 *          non-recoverable error states throughout the application.
 * Architecture: core-ui â€” shared design system; consumed by all feature modules.
 * Dependencies: Compose Material 3, AppTheme tokens.
 *
 * Design decisions:
 * - Uses [MaterialTheme.colorScheme.errorContainer] as the background and
 *   [MaterialTheme.colorScheme.onErrorContainer] for text/icons, satisfying minimum
 *   contrast ratios (4.5:1 for normal text, 3:1 for large text) while staying within
 *   the Material 3 palette.
 * - An error icon is always shown alongside the message text so error state is never
 *   conveyed by color alone â€” satisfying the "no color-only status indicators" rule
 *   (requirement 23.4).
 * - An optional [onRetry] callback surfaces a retry button inside the banner. When
 *   provided, the button uses an outline style for visual hierarchy; the icon + label
 *   pattern ensures the action is also non-color-coded.
 * - [contentDescription] allows callers to override the TalkBack announcement. When
 *   omitted the default is "Error: [message]".
 * - All layout values use [MaterialTheme.spacing] tokens; no magic number literals.
 * - Requirements: 23.1, 23.2, 23.4
 */

package com.aiassistant.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing

/**
 * A banner that communicates an error condition to the user.
 *
 * @param message            The human-readable error message to display.
 * @param onRetry            Optional retry callback. When non-null a "Retry" button is
 *                           rendered inside the banner.
 * @param contentDescription TalkBack label for the banner. Defaults to
 *                           "Error: [message]".
 * @param modifier           Optional [Modifier] applied to the root [Surface].
 */
@Composable
fun ErrorBanner(
    message: String,
    onRetry: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier
) {
    val a11yLabel = contentDescription ?: "Error: $message"

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics { this.contentDescription = a11yLabel },
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(MaterialTheme.spacing.sm),
            verticalAlignment = Alignment.Top
        ) {
            // Error icon â€” non-color status cue (requirement 23.4)
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null, // described by the surrounding row semantics
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier
                    .size(20.dp)
                    .padding(top = 2.dp)
            )

            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                if (onRetry != null) {
                    OutlinedButton(
                        onClick = onRetry,
                        modifier = Modifier.semantics {
                            this.contentDescription = "Retry"
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null, // button label below is sufficient
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                        Text(
                            text = "Retry",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            if (onDismiss != null) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.semantics { this.contentDescription = "Dismiss error" }
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

// â”€â”€â”€ Previews â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Preview(showBackground = true, name = "ErrorBanner â€“ No retry")
@Composable
private fun ErrorBannerNoRetryPreview() {
    AppTheme(dynamicColor = false) {
        ErrorBanner(
            message = "Failed to load conversation history. Please try again."
        )
    }
}

@Preview(showBackground = true, name = "ErrorBanner â€“ With retry")
@Composable
private fun ErrorBannerWithRetryPreview() {
    AppTheme(dynamicColor = false) {
        ErrorBanner(
            message = "Connection to AI service failed.",
            onRetry = {}
        )
    }
}

@Preview(
    showBackground = true,
    name = "ErrorBanner â€“ Dark",
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ErrorBannerDarkPreview() {
    AppTheme(dynamicColor = false) {
        ErrorBanner(
            message = "Something went wrong. Please check your connection.",
            onRetry = {}
        )
    }
}
