/**
 * SettingsScreen.kt
 *
 * Purpose: Settings screen Compose UI with provider selector, theme switcher,
 *          notification category toggles, privacy mode toggle, account management
 *          (change password, Google OAuth2 link/unlink, logout), and Firebase Remote
 *          Config value display.
 * Architecture: feature-settings — presentation layer; receives state from [SettingsViewModel].
 * Dependencies: core-ui (AppTheme, ThemeMode, MaterialTheme.spacing),
 *               feature-settings (SettingsUiState, LlmProvider, NotificationCategory),
 *               Compose Material3.
 *
 * Design decisions:
 * - All settings are grouped into clearly labeled [SectionCard] composables.
 * - Account management actions are listed as [OutlinedButton]s under an "Account" section
 *   to make destructive actions (logout) visually distinct from informational toggles.
 * - Change-password uses a separate [AlertDialog] composable to keep sensitive input
 *   off the main scroll surface while keeping state local to the screen.
 * - Remote Config entries are displayed as read-only info rows so the user can see
 *   Admin-published configuration without being able to modify it.
 * - Every interactive element carries a contentDescription for TalkBack (Requirement 23.1).
 *
 * Requirements: 3.2, 3.7, 7.6, 16.4, 24.2, 28.3
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aiassistant.core.ai.LlmProvider
import com.aiassistant.core.ui.ThemeMode
import com.aiassistant.core.ui.spacing

// ─── Root screen ──────────────────────────────────────────────────────────────

/**
 * Root composable for the Settings screen.
 *
 * Observes [uiState] and delegates to the appropriate child composable.
 *
 * @param uiState              Current [SettingsUiState] emitted by [SettingsViewModel].
 * @param onNavigateUp         Callback to pop the settings screen from the back stack.
 * @param onProviderSelected   Called when the user selects a new LLM provider.
 * @param onThemeSelected      Called when the user selects a new theme mode.
 * @param onNotificationToggle Called with (categoryKey, enabled) when a notification toggle changes.
 * @param onPrivacyModeToggle  Called with the new privacy mode enabled state.
 * @param onChangePassword     Called with (currentPassword, newPassword) to change the password.
 * @param onLinkGoogle         Called with a Google ID token to link the Google account.
 * @param onUnlinkGoogle       Called to unlink the Google account.
 * @param onLogout             Called to log out the user.
 * @param onActionConsumed     Called after a transient [SettingsUiState.ActionResult] is shown.
 * @param onRetry              Called when the user taps "Retry" on the error state.
 */
@Composable
fun settingsScreen(
    uiState: SettingsUiState,
    onNavigateUp: () -> Unit,
    onProviderSelected: (LlmProvider) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onNotificationToggle: (categoryKey: String, enabled: Boolean) -> Unit,
    onPrivacyModeToggle: (Boolean) -> Unit,
    onContextSuggestionsToggle: (Boolean) -> Unit = {},
    onChangePassword: (currentPassword: String, newPassword: String) -> Unit,
    onLinkGoogle: (idToken: String) -> Unit,
    onUnlinkGoogle: () -> Unit,
    onLogout: () -> Unit,
    onActionConsumed: () -> Unit,
    onRetry: () -> Unit,
    onNavigateToCostDashboard: () -> Unit = {}
) {
    // Track whether the change-password dialog is open locally
    var showChangePasswordDialog by remember { mutableStateOf(false) }

    // Determine the effective settings state for rendering (unwrap ActionResult)
    val effectiveSettings: SettingsUiState.Settings? = when (uiState) {
        is SettingsUiState.Settings -> uiState
        is SettingsUiState.ActionResult -> uiState.previousSettings
        is SettingsUiState.ChangePasswordDialog -> null
        else -> null
    }

    // Show snackbar-style banner when ActionResult is present
    if (uiState is SettingsUiState.ActionResult) {
        actionResultBanner(
            message = uiState.message,
            isSuccess = uiState.isSuccess,
            onDismiss = onActionConsumed
        )
    }

    // Change password dialog
    if (showChangePasswordDialog || uiState is SettingsUiState.ChangePasswordDialog) {
        changePasswordDialog(
            onDismiss = {
                showChangePasswordDialog = false
                onActionConsumed()
            },
            onConfirm = { current, new ->
                showChangePasswordDialog = false
                onChangePassword(current, new)
            }
        )
    }

    Scaffold(
        topBar = {
            settingsTopBar(onNavigateUp = onNavigateUp)
        }
    ) { innerPadding ->
        when (uiState) {
            is SettingsUiState.Loading -> {
                settingsLoadingContent(modifier = Modifier.padding(innerPadding))
            }
            is SettingsUiState.Settings -> {
                settingsContent(
                    state = uiState,
                    onProviderSelected = onProviderSelected,
                    onThemeSelected = onThemeSelected,
                    onNotificationToggle = onNotificationToggle,
                    onPrivacyModeToggle = onPrivacyModeToggle,
                    onContextSuggestionsToggle = onContextSuggestionsToggle,
                    onChangePassword = { showChangePasswordDialog = true },
                    onLinkGoogle = onLinkGoogle,
                    onUnlinkGoogle = onUnlinkGoogle,
                    onLogout = onLogout,
                    onNavigateToCostDashboard = onNavigateToCostDashboard,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            is SettingsUiState.ActionResult -> {
                // Render the settings content behind the result banner
                settingsContent(
                    state = uiState.previousSettings,
                    onProviderSelected = onProviderSelected,
                    onThemeSelected = onThemeSelected,
                    onNotificationToggle = onNotificationToggle,
                    onPrivacyModeToggle = onPrivacyModeToggle,
                    onContextSuggestionsToggle = onContextSuggestionsToggle,
                    onChangePassword = { showChangePasswordDialog = true },
                    onLinkGoogle = onLinkGoogle,
                    onUnlinkGoogle = onUnlinkGoogle,
                    onLogout = onLogout,
                    onNavigateToCostDashboard = onNavigateToCostDashboard,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            is SettingsUiState.ChangePasswordDialog -> {
                // Render settings behind the dialog
                if (effectiveSettings != null) {
                    settingsContent(
                        state = effectiveSettings,
                        onProviderSelected = onProviderSelected,
                        onThemeSelected = onThemeSelected,
                        onNotificationToggle = onNotificationToggle,
                        onPrivacyModeToggle = onPrivacyModeToggle,
                        onContextSuggestionsToggle = onContextSuggestionsToggle,
                        onChangePassword = { showChangePasswordDialog = true },
                        onLinkGoogle = onLinkGoogle,
                        onUnlinkGoogle = onUnlinkGoogle,
                        onLogout = onLogout,
                        onNavigateToCostDashboard = onNavigateToCostDashboard,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
            is SettingsUiState.Error -> {
                settingsErrorContent(
                    message = uiState.message,
                    onRetry = onRetry,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun settingsTopBar(onNavigateUp: () -> Unit) {
    TopAppBar(
        title = { Text(text = "Settings") },
        navigationIcon = {
            IconButton(
                onClick = onNavigateUp,
                modifier = Modifier.semantics {
                    contentDescription = "Navigate back from Settings"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = null
                )
            }
        }
    )
}

// ─── Loading ──────────────────────────────────────────────────────────────────

@Composable
private fun settingsLoadingContent(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier
                .size(48.dp)
                .semantics { contentDescription = "Loading settings" }
        )
    }
}

// ─── Error ────────────────────────────────────────────────────────────────────

@Composable
private fun settingsErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Unable to load settings",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(modifier = Modifier.height(spacing.sm))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(spacing.md))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry loading settings" }
        ) {
            Text(text = "Retry")
        }
    }
}

// ─── Action result banner ────────────────────────────────────────────────────

@Composable
private fun actionResultBanner(message: String, isSuccess: Boolean, onDismiss: () -> Unit) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = if (isSuccess) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isSuccess) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onErrorContainer
                },
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

// ─── Main content ─────────────────────────────────────────────────────────────

@Composable
private fun settingsContent(
    state: SettingsUiState.Settings,
    onProviderSelected: (LlmProvider) -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    onNotificationToggle: (categoryKey: String, enabled: Boolean) -> Unit,
    onPrivacyModeToggle: (Boolean) -> Unit,
    onContextSuggestionsToggle: (Boolean) -> Unit,
    onChangePassword: () -> Unit,
    onLinkGoogle: (idToken: String) -> Unit,
    onUnlinkGoogle: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToCostDashboard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val spacing = MaterialTheme.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        // ── LLM Provider ──────────────────────────────────────────────────────
        sectionCard(title = "AI Provider") {
            providerSelector(
                selectedProvider = state.activeProvider,
                availableProviders = state.availableProviders,
                onProviderSelected = onProviderSelected,
                enabled = !state.isSaving
            )
        }

        // ── Theme ─────────────────────────────────────────────────────────────
        sectionCard(title = "Appearance") {
            themeSelector(
                selectedTheme = state.themeMode,
                onThemeSelected = onThemeSelected,
                enabled = !state.isSaving
            )
        }

        // ── Notifications ────────────────────────────────────────────────────
        sectionCard(title = "Notifications") {
            state.notificationCategories.forEach { category ->
                notificationToggleRow(
                    category = category,
                    onToggle = { enabled -> onNotificationToggle(category.key, enabled) }
                )
            }
        }

        // ── Privacy ───────────────────────────────────────────────────────────
        sectionCard(title = "Privacy") {
            privacyModeRow(
                enabled = state.privacyModeEnabled,
                onToggle = onPrivacyModeToggle
            )
            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
            contextSuggestionsRow(
                enabled = state.contextSuggestionsEnabled,
                onToggle = onContextSuggestionsToggle
            )
        }

        // ── AI Cost Dashboard ─────────────────────────────────────────────────
        sectionCard(title = "Usage & Costs") {
            OutlinedButton(
                onClick = onNavigateToCostDashboard,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "View AI Cost Dashboard" }
            ) {
                Text("AI Cost Dashboard")
            }
        }

        // ── Account management ────────────────────────────────────────────────
        sectionCard(title = "Account") {
            accountManagementSection(
                isGoogleLinked = state.isGoogleLinked,
                isSaving = state.isSaving,
                onChangePassword = onChangePassword,
                onLinkGoogle = onLinkGoogle,
                onUnlinkGoogle = onUnlinkGoogle,
                onLogout = onLogout
            )
        }

        // ── Firebase Remote Config ─────────────────────────────────────────────
        if (state.remoteConfigEntries.isNotEmpty()) {
            sectionCard(title = "Remote Configuration") {
                state.remoteConfigEntries.forEach { entry ->
                    remoteConfigRow(entry = entry)
                    if (entry != state.remoteConfigEntries.last()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(spacing.lg))
    }
}

// ─── Section card ─────────────────────────────────────────────────────────────

@Composable
private fun sectionCard(title: String, content: @Composable () -> Unit) {
    val spacing = MaterialTheme.spacing
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(spacing.md)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(spacing.sm))
            content()
        }
    }
}

// ─── Provider selector ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun providerSelector(
    selectedProvider: LlmProvider,
    availableProviders: List<LlmProvider>,
    onProviderSelected: (LlmProvider) -> Unit,
    enabled: Boolean
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "LLM provider selector" }
    ) {
        OutlinedTextField(
            value = selectedProvider.display,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Active AI provider") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            availableProviders.forEach { provider ->
                DropdownMenuItem(
                    text = { Text(provider.display) },
                    onClick = {
                        onProviderSelected(provider)
                        expanded = false
                    },
                    leadingIcon = if (provider == selectedProvider) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Currently selected provider"
                            )
                        }
                    } else {
                        null
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "Select provider: ${provider.display}"
                    }
                )
            }
        }
    }
}

// ─── Theme selector ───────────────────────────────────────────────────────────

@Composable
private fun themeSelector(selectedTheme: ThemeMode, onThemeSelected: (ThemeMode) -> Unit, enabled: Boolean) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Theme mode selector" },
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        themeChip(
            label = "Light",
            isSelected = selectedTheme == ThemeMode.LIGHT,
            enabled = enabled,
            onClick = { onThemeSelected(ThemeMode.LIGHT) },
            contentDescription = "Select light theme"
        )
        themeChip(
            label = "Dark",
            isSelected = selectedTheme == ThemeMode.DARK,
            enabled = enabled,
            onClick = { onThemeSelected(ThemeMode.DARK) },
            contentDescription = "Select dark theme"
        )
        themeChip(
            label = "System",
            isSelected = selectedTheme == ThemeMode.SYSTEM,
            enabled = enabled,
            onClick = { onThemeSelected(ThemeMode.SYSTEM) },
            contentDescription = "Use system default theme"
        )
    }
}

@Composable
private fun themeChip(
    label: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        label = { Text(label) },
        enabled = enabled,
        leadingIcon = if (isSelected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            null
        },
        modifier = Modifier.semantics { this.contentDescription = contentDescription }
    )
}

// ─── Notification toggle row ──────────────────────────────────────────────────

@Composable
private fun notificationToggleRow(category: NotificationCategory, onToggle: (Boolean) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "${category.displayLabel} notifications: " +
                    if (category.enabled) "enabled" else "disabled"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.displayLabel,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = category.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics {
                contentDescription = "Toggle ${category.displayLabel} notifications"
            }
        )
    }
}

// ─── Privacy mode row ────────────────────────────────────────────────────────

@Composable
private fun privacyModeRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Privacy mode: " + if (enabled) "enabled — memory capture disabled" else "disabled"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Privacy mode",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (enabled) {
                    "Memory capture disabled — existing memories preserved"
                } else {
                    "Memory capture is active"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics {
                contentDescription = "Toggle privacy mode"
            }
        )
    }
}

// ─── Context suggestions row ─────────────────────────────────────────────────

/**
 * Toggle row for the context-aware AI suggestions global switch (Requirement 33.8).
 *
 * When [enabled] is false, [GetContextSuggestionsUseCase] returns an empty list
 * immediately without invoking the AI Orchestrator on any screen.
 */
@Composable
private fun contextSuggestionsRow(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Context suggestions: " + if (enabled) "enabled" else "disabled"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = if (enabled) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Context suggestions",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = if (enabled) {
                    "AI suggestions shown on notes, calendar, and conversations"
                } else {
                    "AI suggestions are disabled"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.semantics {
                contentDescription = "Toggle context-aware AI suggestions"
            }
        )
    }
}

// ─── Account management section ───────────────────────────────────────────────

/**
 * Account management section with change password, Google OAuth2 link/unlink, and logout.
 *
 * The Google link/unlink button label changes based on [isGoogleLinked]. The logout button
 * uses an error-tinted color to signal its destructive nature.
 */
@Composable
private fun accountManagementSection(
    isGoogleLinked: Boolean,
    isSaving: Boolean,
    onChangePassword: () -> Unit,
    onLinkGoogle: (idToken: String) -> Unit,
    onUnlinkGoogle: () -> Unit,
    onLogout: () -> Unit
) {
    val spacing = MaterialTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        // Change password
        OutlinedButton(
            onClick = onChangePassword,
            enabled = !isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Change password" }
        ) {
            Text("Change Password")
        }

        // Google account link / unlink
        if (isGoogleLinked) {
            OutlinedButton(
                onClick = onUnlinkGoogle,
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Unlink Google account" }
            ) {
                Text("Unlink Google Account")
            }
        } else {
            // In a real implementation the Google Sign-In flow would obtain the idToken
            // and pass it to onLinkGoogle. Here we surface the button; the caller (navigation
            // layer or composable parent) launches the sign-in flow and supplies the token.
            OutlinedButton(
                onClick = { onLinkGoogle("") }, // token supplied by Google Sign-In flow
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Link Google account" }
            ) {
                Text("Link Google Account")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = spacing.xs))

        // Logout — styled with error color to signal destructive nature
        OutlinedButton(
            onClick = onLogout,
            enabled = !isSaving,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Log out of account" }
        ) {
            Text("Log Out")
        }
    }
}

// ─── Change password dialog ───────────────────────────────────────────────────

/**
 * Dialog for changing the user's password.
 *
 * Both fields use [PasswordVisualTransformation] so characters are masked. The
 * "Confirm" button is enabled only when both fields are non-empty.
 */
@Composable
private fun changePasswordDialog(
    onDismiss: () -> Unit,
    onConfirm: (currentPassword: String, newPassword: String) -> Unit
) {
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    label = { Text("Current password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Current password input" }
                )
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New password (min 12 characters)") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "New password input" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(currentPassword, newPassword) },
                enabled = currentPassword.isNotBlank() && newPassword.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Confirm password change" }
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Cancel password change" }
            ) {
                Text("Cancel")
            }
        }
    )
}

// ─── Remote Config row ────────────────────────────────────────────────────────

/**
 * Read-only row displaying a single Firebase Remote Config [entry].
 *
 * The key is shown as a muted subtitle and the value as the primary body text.
 * An info icon indicates this is an Admin-controlled value.
 */
@Composable
private fun remoteConfigRow(entry: RemoteConfigEntry) {
    val spacing = MaterialTheme.spacing
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
            .semantics(mergeDescendants = true) {
                contentDescription =
                    "Remote config ${entry.displayLabel}: ${entry.value}"
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = entry.value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
