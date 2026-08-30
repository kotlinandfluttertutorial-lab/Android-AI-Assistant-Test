/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-rag
 * File       : ManageModelsScreen.kt
 * Purpose    : Lists downloaded on-device models with name, version, disk size,
 *              last-used date, delete button, and download progress for
 *              missing/corrupt models.  Shows Battery Saver notice when active.
 *              Notifies in-app (not push) when a new model version is available.
 *
 * Architecture Layer : Feature (feature-on-device-rag) — Compose UI layer.
 *
 * Requirements: 32.3, 32.4, 32.5, 37.3, 37.4, 37.5, 37.8, 37.10
 * ============================================================
 */
package com.aiassistant.feature.ondevicerag

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aiassistant.domain.model.OnDeviceModelInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Stateful entry point ─────────────────────────────────────────────────────

@Composable
fun ManageModelsScreen(onNavigateUp: () -> Unit, viewModel: ManageModelsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    ManageModelsContent(
        uiState = uiState,
        onDownloadModel = viewModel::downloadModel,
        onDeleteModel = viewModel::deleteModel,
        onNavigateUp = onNavigateUp,
        modifier = Modifier.fillMaxSize()
    )
}

// ── Stateless content composable ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageModelsContent(
    uiState: ManageModelsUiState,
    onDownloadModel: (OnDeviceModelInfo) -> Unit,
    onDeleteModel: (OnDeviceModelInfo) -> Unit,
    onNavigateUp: () -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Manage On-Device Models") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate up" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            // Battery Saver notice
            if (uiState.batterySaverActive) {
                BatterySaverNotice()
            }

            // Update available in-app notification
            if (uiState.updateAvailableModelName != null) {
                UpdateAvailableNotice(modelName = uiState.updateAvailableModelName)
            }

            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.semantics { contentDescription = "Loading models" }
                        )
                    }
                }

                uiState.models.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No models downloaded yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.semantics {
                                contentDescription = "No models available"
                            }
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        items(uiState.models, key = { it.name }) { model ->
                            val downloadState = uiState.downloadProgress[model.name]
                            ModelListItem(
                                model = model,
                                downloadProgress = downloadState,
                                onDownload = { onDownloadModel(model) },
                                onDelete = { onDeleteModel(model) }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun ModelListItem(
    model: OnDeviceModelInfo,
    downloadProgress: DownloadState?,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Model: ${model.name}" }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            ModelInfoRow(model, onDownload, onDelete)
            if (downloadProgress != null) {
                ModelDownloadProgress(downloadProgress)
            }
        }
    }
}

@Composable
private fun ModelInfoRow(model: OnDeviceModelInfo, onDownload: () -> Unit, onDelete: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.name,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "v${model.version} · ${formatBytes(model.sizeBytes)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            model.lastUsed?.let { lastUsed ->
                Text(
                    text = "Last used: ${formatDate(lastUsed)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row {
            IconButton(
                onClick = onDownload,
                modifier = Modifier.semantics {
                    contentDescription = "Download ${model.name}"
                }
            ) {
                Icon(Icons.Default.Download, contentDescription = "Download model")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "Delete ${model.name}"
                }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete model",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@Composable
private fun ModelDownloadProgress(state: DownloadState) {
    Spacer(Modifier.height(8.dp))
    val label = when (state) {
        is DownloadState.Downloading ->
            "${state.percent}% · ${formatBytes(state.bytesDownloaded)} / ${formatBytes(state.totalBytes)}"
        is DownloadState.Verifying -> "Verifying checksum…"
        is DownloadState.Error -> "Download failed: ${state.message}"
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = if (state is DownloadState.Error) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.primary
        },
        modifier = Modifier.semantics { contentDescription = label }
    )
    if (state is DownloadState.Downloading) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { state.percent / 100f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun BatterySaverNotice(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Battery saver active" }
    ) {
        Text(
            text = "Battery saver active — on-device AI uses CPU only.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun UpdateAvailableNotice(modelName: String, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Update available for $modelName" }
    ) {
        Text(
            text = "Update available for $modelName. Download to use the latest version.",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = 1048576L
private const val BYTES_PER_GB = 1073741824L

private fun formatBytes(bytes: Long): String = when {
    bytes >= BYTES_PER_GB -> "%.1f GB".format(bytes / BYTES_PER_GB.toDouble())
    bytes >= BYTES_PER_MB -> "%.1f MB".format(bytes / BYTES_PER_MB.toDouble())
    bytes >= BYTES_PER_KB -> "%.1f KB".format(bytes / BYTES_PER_KB.toDouble())
    else -> "$bytes B"
}

private fun formatDate(epochMs: Long): String =
    SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
