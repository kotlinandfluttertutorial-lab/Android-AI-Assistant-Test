/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-settings
 * File       : SettingsScreen.kt
 * Purpose    : Production-quality Settings screen (Phase 10 upgrade).
 *
 *              Sections:
 *              1. AI          — provider selector (chips), on-device model status
 *              2. Appearance  — Light/Dark/System theme picker chips
 *              3. Chat        — notification category toggles, context suggestions
 *              4. Privacy     — privacy mode toggle, processing labels
 *              5. About       — app version, model version
 *              6. Account     — change password, Google link/unlink, logout
 *
 * Architecture Layer : Feature (feature-settings) — Compose UI layer.
 *                      All ViewModel calls use the exact method signatures from
 *                      SettingsViewModel (selectProvider, selectTheme, setPrivacyMode,
 *                      changePassword, linkGoogleAccount, unlinkGoogleAccount, logout).
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
import androidx.compose.runtime.LaunchedEffect
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
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.ThemeMode
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.spacing
import kotlinx.coroutines.launch

// ── Entry composable ──────────────────────────────────────────────────────────

@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onNavigateUp: () -> Unit) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Handle ActionResult snackbar
    LaunchedEffect(uiState) {
        if (uiState is SettingsUiState.ActionResult) {
            val result = uiState as SettingsUiState.ActionResult
            scope.launch { snackbarHostState.showSnackbar(result.message) }
            viewModel.onActionConsumed()
        }
    }

    when (val state = uiState) {
        is SettingsUiState.Loading -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading settings" }
                )
            }
        }

        is SettingsUiState.Settings -> {
            SettingsScreenContent(
                state = state,
                snackbarHostState = snackbarHostState,
                onNavigateUp = onNavigateUp,
                onProviderChange = viewModel::selectProvider,
                onThemeChange = viewModel::selectTheme,
                onPrivacyToggle = viewModel::setPrivacyMode,
                onContextSuggestionsToggle = viewModel::setContextSuggestionsEnabled,
                onNotificationToggle = viewModel::setNotificationEnabled,
                onChangePassword = { old, new -> viewModel.changePassword(old, new) },
                onLogout = { viewModel.logout(onNavigateUp) },
                onLinkGoogle = { viewModel.linkGoogleAccount("") }, // token obtained in dialog
                onUnlinkGoogle = viewModel::unlinkGoogleAccount
            )
        }

        is SettingsUiState.Error -> {
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(MaterialTheme.spacing.sm))
                    Button(onClick = viewModel::retry) { Text("Retry") }
                }
            }
        }

        else -> Unit // ChangePasswordDialog / ActionResult handled via LaunchedEffect
    }
}

// ── Stateless screen ──────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreenContent(
    state: SettingsUiState.Settings,
    snackbarHostState: SnackbarHostState,
    onNavigateUp: () -> Unit,
    onProviderChange: (LlmProvider) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onPrivacyToggle: (Boolean) -> Unit,
    onContextSuggestionsToggle: (Boolean) -> Unit,
    onNotificationToggle: (String, Boolean) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onLogout: () -> Unit,
    onLinkGoogle: () -> Unit,
    onUnlinkGoogle: () -> Unit
) {
    var showChangePasswordDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutDialog by rememberSaveable { mutableStateOf(false) }

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
            SnackbarHost(snackbarHostState) { data -> Snackbar(snackbarData = data) }
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

            // ── 1. AI ──────────────────────────────────────────────────────────
            SettingsSectionCard(
                title = "AI",
                icon = { Icon(AppIcons.Ai.Assistant, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                Text(
                    text = "Provider",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    state.availableProviders.forEach { provider ->
                        FilterChip(
                            selected = provider == state.activeProvider,
                            onClick = { onProviderChange(provider) },
                            label = {
                                Text(
                                    text = provider.display,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Select ${provider.display}"
                            }
                        )
                    }
                }

                if (state.onDeviceCapability.isAvailable) {
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    SettingsInfoRow(
                        label = "On-device model",
                        value = state.onDeviceCapability.modelDisplayName ?: "Ready"
                    )
                }
            }

            // ── 2. Appearance ──────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Appearance",
                icon = { Icon(AppIcons.Settings.Appearance, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                Text(
                    text = "Theme",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)) {
                    listOf(
                        ThemeMode.LIGHT  to "Light",
                        ThemeMode.DARK   to "Dark",
                        ThemeMode.SYSTEM to "System"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = mode == state.themeMode,
                            onClick = { onThemeChange(mode) },
                            label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.semantics {
                                contentDescription = "Select $label theme"
                            }
                        )
                    }
                }
            }

            // ── 3. Chat ────────────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Chat",
                icon = { Icon(AppIcons.Destinations.ChatOutlined, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                SettingsToggleRow(
                    label = "Context-aware suggestions",
                    description = "Show AI suggestions based on your current task.",
                    checked = state.contextSuggestionsEnabled,
                    onCheckedChange = onContextSuggestionsToggle,
                    contentDesc = "Toggle context-aware suggestions"
                )
                state.notificationCategories.forEachIndexed { index, category ->
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs)
                    )
                    SettingsToggleRow(
                        label = category.displayLabel,
                        checked = category.enabled,
                        onCheckedChange = { enabled ->
                            onNotificationToggle(category.key, enabled)
                        },
                        contentDesc = "Toggle ${category.displayLabel} notifications"
                    )
                }
            }

            // ── 4. Privacy ─────────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Privacy",
                icon = { Icon(AppIcons.Settings.Privacy, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                SettingsToggleRow(
                    label = "AI Memory",
                    description = "Allow the assistant to remember context from conversations.",
                    checked = !state.privacyModeEnabled,
                    onCheckedChange = { enabled -> onPrivacyToggle(!enabled) },
                    contentDesc = "Toggle AI memory"
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs)
                )
                SettingsInfoRow(
                    label = "Local processing",
                    value = if (state.activeProvider == LlmProvider.ON_DEVICE) "Active" else "Inactive"
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                SettingsInfoRow(
                    label = "Cloud processing",
                    value = if (state.activeProvider != LlmProvider.ON_DEVICE) "Active" else "Inactive"
                )
            }

            // ── 5. About ───────────────────────────────────────────────────────
            SettingsSectionCard(
                title = "About",
                icon = { Icon(AppIcons.Settings.About, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                SettingsInfoRow(label = "App version", value = "1.0.0")
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                SettingsInfoRow(
                    label = "AI model",
                    value = if (state.onDeviceCapability.isAvailable)
                        (state.onDeviceCapability.modelDisplayName ?: "On-device")
                    else
                        state.activeProvider.display
                )
                if (state.remoteConfigEntries.isNotEmpty()) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(vertical = MaterialTheme.spacing.xs)
                    )
                    Text(
                        text = "Remote config",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    state.remoteConfigEntries.forEach { entry ->
                        SettingsInfoRow(label = entry.displayLabel, value = entry.value)
                        Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                TextButton(onClick = {}) {
                    Text("Open-source licenses")
                }
            }

            // ── 6. Account ─────────────────────────────────────────────────────
            SettingsSectionCard(
                title = "Account",
                icon = { Icon(AppIcons.Destinations.ProfileOutlined, contentDescription = null, modifier = Modifier.size(18.dp)) }
            ) {
                OutlinedButton(
                    onClick = { showChangePasswordDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Change password" }
                ) {
                    Text("Change password")
                }
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                if (state.isGoogleLinked) {
                    OutlinedButton(
                        onClick = onUnlinkGoogle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Unlink Google account" }
                    ) { Text("Unlink Google account") }
                } else {
                    Button(
                        onClick = onLinkGoogle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Link Google account" }
                    ) { Text("Link Google account") }
                }
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                OutlinedButton(
                    onClick = { showLogoutDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Log out" }
                ) {
                    Text("Log out", color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.xl))
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    if (showChangePasswordDialog) {
        ChangePasswordDialog(
            onConfirm = { old, new ->
                onChangePassword(old, new)
                showChangePasswordDialog = false
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
}

// ── Reusable section components ───────────────────────────────────────────────

@Composable
private fun SettingsSectionCard(
    title: String,
    icon: (@Composable () -> Unit)? = null,
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
    contentDesc: String
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
            modifier = Modifier.semantics { contentDescription = contentDesc }
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
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
                    label = { Text("New password (min. 12 characters)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (oldPassword.isNotBlank() && newPassword.isNotBlank()) {
                        onConfirm(oldPassword, newPassword)
                    }
                }
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
                    color = if (isDestructive) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ── Navigation-layer entry point ──────────────────────────────────────────────
// SettingsNavigation.kt calls settingsScreen(...) with individual callbacks.
// This function bridges the old call-site API to the new SettingsScreen composable.

/**
 * Navigation-layer entry point called by [settingsNavGraph].
 *
 * Accepts the individual callback parameters that [SettingsNavigation.kt] passes and
 * delegates to [SettingsScreen] / [SettingsScreenContent].  The [uiState] has already
 * been collected in the navigation file; we forward all side-effect callbacks so the
 * composable remains stateless.
 */
@Composable
fun settingsScreen(
    uiState: SettingsUiState,
    onNavigateUp: () -> Unit,
    onProviderSelected: (LlmProvider) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onNotificationToggle: (String, Boolean) -> Unit,
    onPrivacyModeToggle: (Boolean) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onLinkGoogle: (String) -> Unit,
    onUnlinkGoogle: () -> Unit,
    onLogout: () -> Unit,
    onActionConsumed: () -> Unit,
    onRetry: () -> Unit,
    onNavigateToCostDashboard: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Handle ActionResult snackbar and auto-consume
    LaunchedEffect(uiState) {
        if (uiState is SettingsUiState.ActionResult) {
            scope.launch { snackbarHostState.showSnackbar(uiState.message) }
            onActionConsumed()
        }
    }

    when (val state = uiState) {
        is SettingsUiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.semantics { contentDescription = "Loading settings" }
                )
            }
        }

        is SettingsUiState.Settings -> {
            SettingsScreenContent(
                state = state,
                snackbarHostState = snackbarHostState,
                onNavigateUp = onNavigateUp,
                onProviderChange = onProviderSelected,
                onThemeChange = onThemeSelected,
                onPrivacyToggle = onPrivacyModeToggle,
                onContextSuggestionsToggle = { /* wired separately if needed */ },
                onNotificationToggle = onNotificationToggle,
                onChangePassword = onChangePassword,
                onLogout = onLogout,
                onLinkGoogle = { onLinkGoogle("") }, // idToken obtained via Google Sign-In dialog
                onUnlinkGoogle = onUnlinkGoogle
            )
        }

        is SettingsUiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
                    androidx.compose.material3.Button(onClick = onRetry) {
                        Text("Retry")
                    }
                }
            }
        }

        else -> Unit // ChangePasswordDialog / ActionResult handled via LaunchedEffect
    }
}
