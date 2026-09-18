/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-ui
 * File       : components/ModelStatusCard.kt
 * Purpose    : On-device AI model status components for Phase 6.
 *
 *              Includes:
 *              - ModelStatus sealed class (all possible model states)
 *              - ModelStatusCard       — full card with name, status badge,
 *                                        storage size, action button
 *              - ModelDownloadProgress — determinate/indeterminate download bar
 *              - ModelLoadingIndicator — inline loading chip for chat header
 *              - OfflineAiIndicator    — persistent banner when on-device mode
 *                                        is active
 *
 * Architecture Layer : Core-UI — shared design system.
 *                      Consumed by feature-on-device-rag ManageModelsScreen
 *                      and feature-chat ChatDetailScreen header.
 *
 * Design Decision    : All technical details (bytes, URLs, model paths) are
 *                      hidden. Only user-facing labels are shown. No raw
 *                      exception strings are ever displayed.
 *
 * Requirements       : 8.1–8.5, 31.1–31.6
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.aiassistant.core.ui.AppShapes
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.core.ui.AppTypeExtended
import com.aiassistant.core.ui.DURATION_MEDIUM
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing

// ── Model state sealed class ──────────────────────────────────────────────────

/**
 * All possible states of an on-device AI model.
 * Maps to user-facing labels; never exposes internal technical details.
 */
sealed class ModelStatus {
    /** Model has not been downloaded yet. */
    data object NotDownloaded : ModelStatus()

    /** Model is being downloaded. [progressPercent] is 0–100, or null for indeterminate. */
    data class Downloading(
        val progressPercent: Int? = null,
        val speedMbps: Float? = null,
        val etaSeconds: Int? = null
    ) : ModelStatus()

    /** Model has been downloaded and is being verified. */
    data object Installing : ModelStatus()

    /** Model is being loaded into device memory. */
    data object Loading : ModelStatus()

    /** Model is ready for inference. */
    data object Ready : ModelStatus()

    /** Model download / verification failed. */
    data class Error(val userMessage: String) : ModelStatus()

    /** Device does not meet hardware requirements. */
    data object Unavailable : ModelStatus()

    /** A newer model version is available. */
    data class Outdated(val newVersion: String) : ModelStatus()
}

// ── ModelStatusCard ───────────────────────────────────────────────────────────

/**
 * Full-size card showing the on-device model state.
 *
 * @param modelDisplayName  User-facing model name, e.g. "Gemma 3 (1B)".
 * @param status            Current [ModelStatus].
 * @param storageSizeLabel  Formatted string, e.g. "1.8 GB". Null when unknown.
 * @param onManage          Called when the user taps the manage/download action.
 * @param onDelete          Called when the user taps delete (shown when Ready/Outdated).
 * @param modifier          Applied to the card.
 */
@Composable
fun ModelStatusCard(
    modelDisplayName: String,
    status: ModelStatus,
    storageSizeLabel: String? = null,
    onManage: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "AI model status: $modelDisplayName" },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.padding(MaterialTheme.spacing.md)
        ) {
            // Header row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Ai.Gemma,
                    contentDescription = null,
                    tint = if (isDark) AppColors.gemmaIndicatorDark else AppColors.gemmaIndicatorLight,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = modelDisplayName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "On-device AI",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                ModelStatusBadge(status = status, isDark = isDark)
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // Download progress (visible only when downloading)
            if (status is ModelStatus.Downloading) {
                ModelDownloadProgress(
                    status = status,
                    onCancel = onManage,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            // Info rows
            ModelInfoRow(label = "Status", value = status.toUserLabel())

            if (storageSizeLabel != null) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                ModelInfoRow(label = "Storage", value = storageSizeLabel)
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            ModelInfoRow(
                label = "Offline mode",
                value = if (status is ModelStatus.Ready || status is ModelStatus.Outdated) "Available" else "Unavailable"
            )

            // Action buttons
            val showManage = status !is ModelStatus.Unavailable
            val showDelete = status is ModelStatus.Ready || status is ModelStatus.Outdated

            if (showManage || showDelete) {
                Spacer(modifier = Modifier.height(MaterialTheme.spacing.md))
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)) {
                    if (showManage) {
                        Button(
                            onClick = { onManage?.invoke() },
                            modifier = Modifier.semantics {
                                contentDescription = status.toActionLabel()
                            }
                        ) {
                            Text(status.toActionLabel(), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    if (showDelete) {
                        OutlinedButton(
                            onClick = { onDelete?.invoke() },
                            modifier = Modifier.semantics { contentDescription = "Delete model" }
                        ) {
                            Icon(
                                imageVector = AppIcons.Chat.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Delete", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

// ── ModelDownloadProgress ─────────────────────────────────────────────────────

/**
 * Progress indicator for model download operations.
 *
 * @param status    The [ModelStatus.Downloading] state.
 * @param onCancel  Called when the user taps Cancel. Null = hide cancel.
 */
@Composable
fun ModelDownloadProgress(
    status: ModelStatus.Downloading,
    onCancel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        if (status.progressPercent != null) {
            LinearProgressIndicator(
                progress = { status.progressPercent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Downloading: ${status.progressPercent}%"
                    }
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Downloading model…" }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val progressText = when {
                status.progressPercent != null && status.etaSeconds != null ->
                    "${status.progressPercent}% · ${formatEta(status.etaSeconds)}"
                status.progressPercent != null -> "${status.progressPercent}%"
                status.speedMbps != null -> "%.1f MB/s".format(status.speedMbps)
                else -> "Downloading…"
            }
            Text(
                text = progressText,
                style = AppTypeExtended.cacheTimestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onCancel != null) {
                TextButton(
                    onClick = onCancel,
                    modifier = Modifier.semantics { contentDescription = "Cancel download" }
                ) {
                    Text("Cancel", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun formatEta(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s remaining"
    else         -> "${seconds / 60}m ${seconds % 60}s remaining"
}

// ── ModelLoadingIndicator ─────────────────────────────────────────────────────

/**
 * Compact inline chip shown in a chat TopAppBar when the model is loading.
 *
 * @param visible When false the chip is hidden (with slide animation).
 */
@Composable
fun ModelLoadingIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically()
    ) {
        val transition = rememberInfiniteTransition(label = "modelLoadSpin")
        val rotation by transition.animateFloat(
            initialValue = 0f, targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(DURATION_MEDIUM * 2, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "modelRotation"
        )

        Surface(
            modifier = modifier.semantics {
                contentDescription = "Loading AI model into memory"
            },
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = AppShapes.pill
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Status.Syncing,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(12.dp).rotate(rotation)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Loading model…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

// ── OfflineAiIndicator ────────────────────────────────────────────────────────

/**
 * Persistent chip/banner shown when on-device Gemma is active.
 *
 * @param modelName  Short display name, e.g. "Gemma 3".
 * @param onTap      Called when the user taps the indicator (opens model settings).
 */
@Composable
fun OfflineAiIndicator(
    modelName: String,
    onTap: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) AppColors.gemmaContainerDark else AppColors.gemmaContainerLight
    val onContainerColor = if (isDark) AppColors.gemmaOnContainerDark else AppColors.gemmaOnContainerLight
    val indicatorColor = if (isDark) AppColors.gemmaIndicatorDark else AppColors.gemmaIndicatorLight

    Surface(
        onClick = { onTap?.invoke() },
        enabled = onTap != null,
        modifier = modifier.semantics {
            contentDescription = "Running on device: $modelName. Tap for model settings."
        },
        color = containerColor,
        shape = AppShapes.pill
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = AppIcons.Ai.Gemma,
                contentDescription = null,
                tint = indicatorColor,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = modelName,
                style = MaterialTheme.typography.labelSmall,
                color = onContainerColor
            )
        }
    }
}

// ── Supporting composables ────────────────────────────────────────────────────

@Composable
private fun ModelStatusBadge(status: ModelStatus, isDark: Boolean) {
    val (bg, fg, text) = when (status) {
        is ModelStatus.Ready         -> Triple(
            if (isDark) AppColors.gemmaContainerDark else AppColors.gemmaContainerLight,
            if (isDark) AppColors.gemmaOnContainerDark else AppColors.gemmaOnContainerLight,
            "Ready"
        )
        is ModelStatus.Downloading   -> Triple(
            if (isDark) AppColors.cloudContainerDark else AppColors.cloudContainerLight,
            if (isDark) AppColors.cloudOnContainerDark else AppColors.cloudOnContainerLight,
            "Downloading"
        )
        is ModelStatus.Loading,
        is ModelStatus.Installing    -> Triple(
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.onSecondaryContainer,
            if (status is ModelStatus.Installing) "Installing" else "Loading"
        )
        is ModelStatus.Error         -> Triple(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            "Error"
        )
        is ModelStatus.Outdated      -> Triple(
            if (isDark) AppColors.ragContainerDark else AppColors.ragContainerLight,
            if (isDark) AppColors.ragOnContainerDark else AppColors.ragOnContainerLight,
            "Update available"
        )
        is ModelStatus.Unavailable   -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Unavailable"
        )
        is ModelStatus.NotDownloaded -> Triple(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            "Not installed"
        )
    }

    Surface(color = bg, shape = AppShapes.pill) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun ModelInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = AppTypeExtended.modelStatusBody,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = AppTypeExtended.modelStatusBody,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

private fun ModelStatus.toUserLabel(): String = when (this) {
    is ModelStatus.NotDownloaded -> "Not installed"
    is ModelStatus.Downloading   -> "Downloading"
    is ModelStatus.Installing    -> "Installing"
    is ModelStatus.Loading       -> "Loading"
    is ModelStatus.Ready         -> "Ready"
    is ModelStatus.Error         -> "Failed"
    is ModelStatus.Unavailable   -> "Not supported on this device"
    is ModelStatus.Outdated      -> "Update available (v${newVersion})"
}

private fun ModelStatus.toActionLabel(): String = when (this) {
    is ModelStatus.NotDownloaded -> "Download"
    is ModelStatus.Error         -> "Retry"
    is ModelStatus.Outdated      -> "Update"
    is ModelStatus.Downloading   -> "Cancel"
    else                         -> "Manage"
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(showBackground = true)
@Composable
private fun ModelStatusCardReadyPreview() {
    AppTheme(dynamicColor = false) {
        ModelStatusCard(
            modelDisplayName = "Gemma 3 (1B INT4)",
            status = ModelStatus.Ready,
            storageSizeLabel = "1.8 GB",
            onManage = {},
            onDelete = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun ModelStatusCardDownloadingPreview() {
    AppTheme(dynamicColor = false) {
        ModelStatusCard(
            modelDisplayName = "Gemma 3 (1B INT4)",
            status = ModelStatus.Downloading(progressPercent = 47, speedMbps = 3.2f, etaSeconds = 65),
            storageSizeLabel = "1.8 GB",
            onManage = {},
            modifier = Modifier.padding(16.dp)
        )
    }
}
