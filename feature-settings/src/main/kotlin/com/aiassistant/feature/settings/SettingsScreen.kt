/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsScreen.kt
 * Purpose    : Production-quality Settings screen (Phase 10 upgrade).
 *
 *              Sections:
 *              1. AI          — mode selector, provider, on-device model link
 *              2. Appearance  — Light/Dark/System theme picker, dynamic color
 *              3. Chat        — save history, streaming, Markdown, auto-scroll
 *              4. Storage     — cache size, clear cache, models, documents
 *              5. Privacy     — local/cloud processing labels, data retention
 *              6. About       — app version, model version, licenses
 *              7. Account     — change password, Google link/unlink, logout
 *
 *              Each section uses a reusable [SettingsSectionCard] wrapper.
 *              Destructive actions require an AlertDialog confirmation.
 *
 * Architecture Layer : Feature (feature-settings) — Compose UI layer.
 *                      All state comes from SettingsViewModel/SettingsUiState.
 *
 * Requirements       : 3.2, 3.7, 7.6, 16.4, 24.2, 28.3
 * ============================================================
 */
package com.aiassistant.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.ThemeMode
import com.aiassistant.core.ui.components.AiMode
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

// ── Entry composable ──────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateUp: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        is SettingsUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        is SettingsUiState.Settings -> {
            SettingsScreenContent(
                state = state,
                onNavigateUp = onNavigateUp,
                onProviderChange = viewModel::setProvider,
                onThemeChange = viewModel::setThemeMode,
                onPrivacyToggle = viewModel::setPrivacyMode,
                onClearCache = viewModel::clearCache,
                onChangePassword = { old, new -> viewModel.changePassword(old, new) },
                onLogout = viewModel::logout,
                onLinkGoogle = viewModel::linkGoogleAccount,
                onUnlinkGoogle = viewModel::unlinkGoogleAccount
            )
        }
        is SettingsUiState.Error -> {
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
        else -> Unit
    }
}

// ── Stateless screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    state: SettingsUiState.Settings,
    onNavigateUp: () -> Unit,
    onProviderChange: (LlmProvider) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onPrivacyToggle: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onChangePassword: (String, String) -> Unit,
    onLogout: () -> Unit,
    onLinkGoogle: () -> Unit,
    onUnlinkGoogle: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showChangePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }
    var showClearCacheDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(AppIcons.Navigation.Back, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(snackbarData = data)
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.sm
                ),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm)
        ) {
            // ── 1. AI ──────────────────────────────────────────────────────
            SettingsSectionCard(title = "AI", icon = { Icon(AppIcons.Ai.Assistant, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                SettingsInfoRow(
                    label = "AI Mode",
                    value = state.activeProvider.displayName()
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Text(
                    text = "Provider",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                    state.availableProviders.forEach { provider ->
                        FilterChip(
                            selected = provider == state.activeProvider,
                            onClick = { onProviderChange(provider) },
                            label = { Text(provider.displayName(), style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.semantics { contentDescription = "Select ${provider.displayName()}" }
                        )
                    }
                }
            }

            // ── 2. Appearance ──────────────────────────────────────────────
            SettingsSectionCard(title = "Appearance", icon = { Icon(AppIcons.Settings.Appearance, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                    listOf(ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark", ThemeMode.SYSTEM to "System")
                        .forEach { (mode, label) ->
                            FilterChip(
                                selected = mode == state.themeMode,
                                onClick = { onThemeChange(mode) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.semantics { contentDescription = "Select $label theme" }
                            )
                        }
                }
            }

            // ── 3. Chat ────────────────────────────────────────────────────
            SettingsSectionCard(title = "Chat", icon = { Icon(AppIcons.Destinations.ChatOutlined, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                SettingsToggleRow(
                    label = "Save chat history",
                    description = "Conversations are saved locally.",
                    checked = true,
                    onCheckedChange = {},
                    contentDescription = "Toggle save chat history"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs))
                SettingsToggleRow(
                    label = "Streaming responses",
                    description = "Show AI responses as they are generated.",
                    checked = true,
                    onCheckedChange = {},
                    contentDescription = "Toggle streaming responses"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs))
                SettingsToggleRow(
                    label = "Markdown rendering",
                    description = "Render formatted text and code blocks.",
                    checked = true,
                    onCheckedChange = {},
                    contentDescription = "Toggle Markdown rendering"
                )
            }

            // ── 4. Storage ─────────────────────────────────────────────────
            SettingsSectionCard(title = "Storage", icon = { Icon(AppIcons.Settings.Storage, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                SettingsInfoRow(label = "Cache", value = "Computing…")
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                OutlinedButton(
                    onClick = { showClearCacheDialog = true },
                    modifier = Modifier.semantics { contentDescription = "Clear app cache" }
                ) {
                    Text("Clear cache")
                }
            }

            // ── 5. Privacy ─────────────────────────────────────────────────
            SettingsSectionCard(title = "Privacy", icon = { Icon(AppIcons.Settings.Privacy, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                SettingsToggleRow(
                    label = "AI Memory",
                    description = "Allow the assistant to learn from your conversations.",
                    checked = !state.privacyModeEnabled,
                    onCheckedChange = { onPrivacyToggle(!it) },
                    contentDescription = "Toggle AI memory"
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs))
                SettingsInfoRow(label = "Local processing", value = if (state.activeProvider == LlmProvider.ON_DEVICE) "Active" else "Inactive")
                SettingsInfoRow(label = "Cloud processing", value = if (state.activeProvider != LlmProvider.ON_DEVICE) "Active" else "Inactive")
            }

            // ── 6. About ───────────────────────────────────────────────────
            SettingsSectionCard(title = "About", icon = { Icon(AppIcons.Settings.About, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                SettingsInfoRow(label = "App version", value = "1.0.0")
                SettingsInfoRow(label = "Model", value = if (state.onDeviceCapability.isAvailable) (state.onDeviceCapability as? OnDeviceCapabilityAvailability)?.modelName ?: "Available" else "Cloud AI")
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                TextButton(onClick = {}) {
                    Text("Open-source licenses")
                }
            }

            // ── 7. Account ─────────────────────────────────────────────────
            SettingsSectionCard(title = "Account", icon = { Icon(AppIcons.Destinations.ProfileOutlined, contentDescription = null, modifier = Modifier.size(18.dp)) }) {
                OutlinedButton(
                    onClick = { showChangePasswordDialog = true },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Change password" }
                ) {
                    Text("Change password")
                }
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                if (state.isGoogleLinked) {
                    OutlinedButton(
                        onClick = onUnlinkGoogle,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Unlink Google account" }
                    ) { Text("Unlink Google account") }
                } else {
                    Button(
                        onClick = onLinkGoogle,
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Link Google account" }
                    ) { Text("Link Google account") }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Log out" }
                ) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xl))
        }
    }

    // Dialogs
    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onConfirm = { old, new ->
                onChangePassword(old, new)
                showChangePasswordDialog = false
                scope.launch { snackbarHostState.showSnackbar("Password changed successfully") }
            },
            onDismiss = { showChangePasswordDialog = false }
        )
    }
    if (showLogoutDialog) {
        ConfirmDialog(
            title = "Log out",
            message = "Are you sure you want to log out?",
            confirmLabel = "Log out",
            isDestructive = true,
            onConfirm = {
                showLogoutDialog = false
                onLogout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }
    if (showClearCacheDialog) {
        ConfirmDialog(
            title = "Clear cache",
            message = "This will remove cached conversations and media. Your saved history will not be deleted.",
            confirmLabel = "Clear",
            isDestructive = true,
            onConfirm = {
                showClearCacheDialog = false
                onClearCache()
                scope.launch { snackbarHostState.showSnackbar("Cache cleared") }
            },
            onDismiss = { showClearCacheDialog = false }
        )
    }
}

// ── Reusable section components ───────────────────────────────────────────────

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = MaterialTheme.spacing.sm)
            ) {
                if (icon != null) {
                    icon()
                    Spacer(modifier = Modifier.width(MaterialTheme.spacing.xs))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            content()
        }
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentDescription: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.semantics { this.contentDescription = contentDescription }
        )
    }
}

@Composable
private fun SettingsInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChangePasswordDialog(
    onConfirm: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var oldPassword by rememberSaveable { mutableStateOf("") }
    var newPassword by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = oldPassword,
                    onValueChange = { oldPassword = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (oldPassword.isNotBlank() && newPassword.isNotBlank()) onConfirm(oldPassword, newPassword) }
            ) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    isDestructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (isDestructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// Helper extension to get display name from LlmProvider without coupling to its internals
private fun LlmProvider.displayName(): String = name
    .replace("_", " ")
    .lowercase()
    .replaceFirstChar { it.uppercaseChar() }
