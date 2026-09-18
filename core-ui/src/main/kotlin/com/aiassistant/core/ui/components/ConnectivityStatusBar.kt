/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/ConnectivityStatusBar.kt
 * Purpose    : Animated connectivity status bar that slides in/out below
 *              the TopAppBar when connectivity state changes.
 *
 *              Supports three states:
 *                Offline  — amber/tertiary color, WifiOff icon
 *                Syncing  — primary color, SyncAlt icon (spinning)
 *                Online   — green/success color, auto-dismisses after 3s
 *
 * Architecture Layer : Core-UI — shared design system.
 * Requirements       : 10.4, 23.4
 * ============================================================
 */
package com.aiassistant.core.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.ONLINE_BANNER_AUTO_DISMISS_MS
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.delay

/** The connectivity state the status bar should represent. */
enum class ConnectivityState {
    /** Device has no network connectivity. */
    OFFLINE,

    /** Device is back online and syncing pending data. */
    SYNCING,

    /** Device is fully online. Banner will auto-dismiss. */
    ONLINE
}

/**
 * An animated status bar shown below the TopAppBar when connectivity changes.
 *
 * Slides down with [expandVertically] on appearance, slides up with
 * [shrinkVertically] on dismissal. When [state] is [ConnectivityState.ONLINE]
 * the banner auto-dismisses after [ONLINE_BANNER_AUTO_DISMISS_MS] ms.
 *
 * @param state      Current connectivity state.
 * @param visible    Controls whether the bar is shown. Callers toggle this
 *                   based on [ConnectivityObserver] emissions.
 * @param onDismiss  Called when the online banner auto-dismisses, allowing
 *                   the caller to hide the banner.
 * @param modifier   Applied to the root Row.
 */
@Composable
fun ConnectivityStatusBar(
    state: ConnectivityState,
    visible: Boolean,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    // Auto-dismiss when back online
    if (visible && state == ConnectivityState.ONLINE) {
        LaunchedEffect(Unit) {
            delay(ONLINE_BANNER_AUTO_DISMISS_MS.toLong())
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val isDark = isSystemInDarkTheme()
        val config = connectivityConfig(state, isDark)

        Row(
            modifier = modifier
                .fillMaxWidth()
                .background(config.backgroundColor)
                .padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.xs
                )
                .semantics { contentDescription = config.accessibilityLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (state == ConnectivityState.SYNCING) {
                SpinningIcon(tint = config.contentColor)
            } else {
                Icon(
                    imageVector = config.icon,
                    contentDescription = null,
                    tint = config.contentColor,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
            Text(
                text = config.message,
                style = MaterialTheme.typography.labelSmall,
                color = config.contentColor
            )
        }
    }
}

@Composable
private fun SpinningIcon(tint: Color) {
    val transition = rememberInfiniteTransition(label = "syncSpin")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "syncRotation"
    )
    Icon(
        imageVector = AppIcons.Status.Syncing,
        contentDescription = null,
        tint = tint,
        modifier = Modifier
            .size(14.dp)
            .rotate(rotation)
    )
}

private data class ConnectivityConfig(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val message: String,
    val backgroundColor: Color,
    val contentColor: Color,
    val accessibilityLabel: String
)

private fun connectivityConfig(state: ConnectivityState, isDark: Boolean): ConnectivityConfig =
    when (state) {
        ConnectivityState.OFFLINE -> ConnectivityConfig(
            icon = AppIcons.Status.Offline,
            message = "You're offline",
            backgroundColor = if (isDark) Color(0xFF3A1D00) else Color(0xFFFFF3CD),
            contentColor = if (isDark) Color(0xFFFFB956) else Color(0xFF856404),
            accessibilityLabel = "You are offline. Some features are unavailable."
        )
        ConnectivityState.SYNCING -> ConnectivityConfig(
            icon = AppIcons.Status.Syncing,
            message = "Syncing…",
            backgroundColor = if (isDark) Color(0xFF1E3A5F) else Color(0xFFDBEAFE),
            contentColor = if (isDark) Color(0xFFBFDBFE) else Color(0xFF1E40AF),
            accessibilityLabel = "Syncing data."
        )
        ConnectivityState.ONLINE -> ConnectivityConfig(
            icon = AppIcons.Status.Success,
            message = "Back online",
            backgroundColor = if (isDark) Color(0xFF064E3B) else Color(0xFFD1FAE5),
            contentColor = if (isDark) Color(0xFFA7F3D0) else Color(0xFF065F46),
            accessibilityLabel = "Back online."
        )
    }

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun OfflineBarPreview() {
    AppTheme(dynamicColor = false) {
        ConnectivityStatusBar(state = ConnectivityState.OFFLINE, visible = true)
    }
}

@Preview(showBackground = true)
@Composable
private fun SyncingBarPreview() {
    AppTheme(dynamicColor = false) {
        ConnectivityStatusBar(state = ConnectivityState.SYNCING, visible = true)
    }
}
