/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-profile
 * File       : ProfileScreen.kt
 * Purpose    : Redesigned Profile and Memory Management screen (Task 50.6):
 *              - Gradient-fill avatar circle with edit badge
 *              - Account tier SuggestionChip (Premium / Free)
 *              - MemorySummaryCard with FlowRow chip layout
 *              - SettingsGroup composable for grouped action rows
 *              - Sign-out card with errorContainer styling separated
 *                from general account management
 *
 * Architecture Layer : Feature (feature-profile) — Compose UI layer.
 *                      State driven by ProfileViewModel.
 *
 * Dependencies       : core-ui (AppColors, AppType, spacing, elevation, pressScale),
 *                      domain (User, Memory), Coil.
 *
 * Requirements       : 7.3, 7.4, 28.1, 28.2
 * ============================================================
 */
package com.aiassistant.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.pressScale
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.User
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val memoryDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault())

// ── Root screen ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onNavigateUp: () -> Unit,
    onNavigateToMemoryList: () -> Unit = {},
    onStartEditMemory: (Memory) -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    onRequestDataExport: () -> Unit,
    onDismissExportStatus: () -> Unit,
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    // Logout callback wired to AuthViewModel
    onLogout: () -> Unit = {},
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = (uiState as? ProfileUiState.Content)?.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            onDismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateUp,
                        modifier = Modifier.semantics { contentDescription = "Navigate back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.semantics {
                        contentDescription = "Notification: ${data.visuals.message}"
                    },
                )
            }
        },
    ) { innerPadding ->
        when (uiState) {
            is ProfileUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            is ProfileUiState.Error -> ErrorContent(
                message = uiState.message, onRetry = onRetry,
                modifier = Modifier.padding(innerPadding),
            )
            is ProfileUiState.Content -> ProfileBody(
                state = uiState,
                onNavigateToMemoryList = onNavigateToMemoryList,
                onStartEditMemory = onStartEditMemory,
                onUpdateEditContent = onUpdateEditContent,
                onCancelEdit = onCancelEdit,
                onSaveEdit = onSaveEdit,
                onDeleteMemory = onDeleteMemory,
                onStartEditName = onStartEditName,
                onUpdateEditingName = onUpdateEditingName,
                onCancelEditName = onCancelEditName,
                onSaveDisplayName = onSaveDisplayName,
                onRequestDataExport = onRequestDataExport,
                onDismissExportStatus = onDismissExportStatus,
                onInitiateAccountDeletion = onInitiateAccountDeletion,
                onUpdateDeletionInput = onUpdateDeletionInput,
                onCancelAccountDeletion = onCancelAccountDeletion,
                onConfirmAccountDeletion = onConfirmAccountDeletion,
                onDismissDeletionError = onDismissDeletionError,
                onLogout = onLogout,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.semantics { contentDescription = "Loading profile" }
        )
    }
}

@Composable
private fun ErrorContent(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(MaterialTheme.spacing.md),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Unable to load profile", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(message, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry loading profile" },
        ) { Text("Retry") }
    }
}

// ── Main scrollable body ──────────────────────────────────────────────────────

@Composable
private fun ProfileBody(
    state: ProfileUiState.Content,
    onNavigateToMemoryList: () -> Unit,
    onStartEditMemory: (Memory) -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    onRequestDataExport: () -> Unit,
    onDismissExportStatus: () -> Unit,
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.spacing.screenEdge),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Spacer(Modifier.height(MaterialTheme.spacing.xs))

        // ── 1. Gradient avatar card ────────────────────────────────────────
        AvatarCard(
            user = state.user,
            isEditingName = state.isEditingName,
            editingName = state.editingName,
            isSavingName = state.isSavingName,
            onStartEditName = onStartEditName,
            onUpdateEditingName = onUpdateEditingName,
            onCancelEditName = onCancelEditName,
            onSaveDisplayName = onSaveDisplayName,
        )

        // ── 3. Memory summary card with FlowRow chips ──────────────────────
        MemorySummaryCard(
            memories = state.memories,
            deletingIds = state.deletingMemoryIds,
            onDeleteMemory = onDeleteMemory,
            onViewAll = onNavigateToMemoryList,
        )

        // ── 4. Settings groups ─────────────────────────────────────────────
        SettingsGroup(title = "Data & Privacy") {
            SettingsRow(
                icon = Icons.Filled.Download,
                label = "Export My Data",
                sublabel = "Request a full archive (up to 24 hours)",
                onClick = onRequestDataExport,
                contentDesc = "Request data export",
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = MaterialTheme.spacing.xxl),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SettingsRow(
                icon = Icons.Filled.Lock,
                label = "Privacy Policy",
                onClick = { /* navigate to privacy policy */ },
                contentDesc = "View privacy policy",
            )
        }

        SettingsGroup(title = "Account") {
            SettingsRow(
                icon = Icons.Filled.Shield,
                label = "Change Password",
                onClick = { /* navigate to change password */ },
                contentDesc = "Change password",
            )
            HorizontalDivider(
                modifier = Modifier.padding(start = MaterialTheme.spacing.xxl),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            SettingsRow(
                icon = Icons.Filled.PersonOff,
                label = "Delete Account",
                labelColor = MaterialTheme.colorScheme.error,
                onClick = onInitiateAccountDeletion,
                contentDesc = "Delete account",
            )
        }

        // ── 5. Sign-out card with errorContainer styling ───────────────────
        SignOutCard(onLogout = onLogout)

        Spacer(Modifier.height(MaterialTheme.spacing.xl))
    }

    // Dialogs
    if (state.editingMemory != null) {
        EditMemoryDialog(
            memory = state.editingMemory,
            editContent = state.editContent,
            isSaving = state.isSavingEdit,
            onContentChange = onUpdateEditContent,
            onConfirm = onSaveEdit,
            onDismiss = onCancelEdit,
        )
    }
    if (state.accountDeletionState is AccountDeletionState.Confirming ||
        state.accountDeletionState is AccountDeletionState.Deleting
    ) {
        AccountDeletionDialog(
            state = state.accountDeletionState,
            onUpdateInput = onUpdateDeletionInput,
            onConfirm = onConfirmAccountDeletion,
            onDismiss = onCancelAccountDeletion,
        )
    }
    if (state.accountDeletionState is AccountDeletionState.Failed) {
        AccountDeletionErrorDialog(
            message = (state.accountDeletionState as AccountDeletionState.Failed).message,
            onDismiss = onDismissDeletionError,
        )
    }
}

// ── 1. Gradient avatar card ───────────────────────────────────────────────────

@Composable
private fun AvatarCard(
    user: User?,
    isEditingName: Boolean,
    editingName: String,
    isSavingName: Boolean,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd   = if (isDark) AppColors.gradientEndDark   else AppColors.gradientEndLight

    // ── 2. Account tier chip ─────────────────────────────────────────────
    val isPremium = user?.role == "premium"

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "User profile: ${user?.displayName ?: "Unknown"}" },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.mid),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Gradient avatar with edit badge ──────────────────────────
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(gradientStart, gradientEnd))),
                    contentAlignment = Alignment.Center,
                ) {
                    if (!user?.avatarUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = user?.avatarUrl,
                            contentDescription = "Avatar for ${user?.displayName}",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                        )
                    } else {
                        val initials = user?.displayName
                            ?.split(" ")
                            ?.take(2)
                            ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                            ?.joinToString("")
                            ?: "?"
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.headlineMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // Edit badge (CameraAlt overlay)
                Surface(
                    modifier = Modifier
                        .size(28.dp)
                        .offset(x = 4.dp, y = 4.dp)
                        .semantics { contentDescription = "Edit avatar" },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }

            Spacer(Modifier.height(MaterialTheme.spacing.sm))

            // ── Account tier chip ─────────────────────────────────────────
            SuggestionChip(
                onClick = { /* navigate to upgrade */ },
                label = {
                    Text(
                        text = if (isPremium) "Premium" else "Free",
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = if (isPremium)
                        if (isDark) AppColors.gradientStartDark.copy(alpha = 0.22f)
                        else AppColors.gradientStartLight.copy(alpha = 0.15f)
                    else MaterialTheme.colorScheme.surfaceVariant,
                    labelColor = if (isPremium)
                        if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                modifier = Modifier.semantics {
                    contentDescription = if (isPremium) "Premium account" else "Free account"
                },
            )

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            // ── Display name ──────────────────────────────────────────────
            if (isEditingName) {
                OutlinedTextField(
                    value = editingName,
                    onValueChange = onUpdateEditingName,
                    label = { Text("Display name") },
                    singleLine = true,
                    enabled = !isSavingName,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Display name input" },
                )
                Spacer(Modifier.height(MaterialTheme.spacing.xs))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = onCancelEditName, enabled = !isSavingName,
                        modifier = Modifier.semantics { contentDescription = "Cancel name edit" },
                    ) { Text("Cancel") }
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    Button(
                        onClick = onSaveDisplayName,
                        enabled = !isSavingName && editingName.isNotBlank(),
                        modifier = Modifier.semantics { contentDescription = "Save display name" },
                    ) {
                        if (isSavingName) CircularProgressIndicator(Modifier.size(16.dp))
                        else Text("Save")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = user?.displayName ?: "—",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(MaterialTheme.spacing.xs))
                    IconButton(
                        onClick = onStartEditName,
                        modifier = Modifier.size(24.dp)
                            .semantics { contentDescription = "Edit display name" },
                    ) {
                        Icon(Icons.Filled.Edit, contentDescription = null,
                            modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (!user?.email.isNullOrBlank()) {
                Text(
                    text = user?.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 3. Memory summary card with FlowRow chip layout ───────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MemorySummaryCard(
    memories: List<Memory>,
    deletingIds: Set<String>,
    onDeleteMemory: (String) -> Unit,
    onViewAll: () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    ElevatedCard(
        onClick = onViewAll,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .semantics {
                contentDescription = "Memory summary: ${memories.size} stored memories. Tap to view all."
            },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    Icon(
                        Icons.Filled.Memory, contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Memories",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${memories.size}",
                        style = AppType.sectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (memories.isNotEmpty()) {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                // FlowRow chip layout — first 6 memories as dismissible chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
                ) {
                    memories.take(6).forEach { memory ->
                        FilterChip(
                            selected = false,
                            onClick = { /* tap = view detail */ },
                            label = {
                                Text(
                                    text = memory.content.take(30) +
                                        if (memory.content.length > 30) "…" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingIcon = {
                                if (memory.id in deletingIds) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    IconButton(
                                        onClick = { onDeleteMemory(memory.id) },
                                        modifier = Modifier
                                            .size(18.dp)
                                            .semantics {
                                                contentDescription = "Delete memory: ${memory.content.take(30)}"
                                            },
                                    ) {
                                        Icon(
                                            Icons.Filled.Delete, contentDescription = null,
                                            modifier = Modifier.size(12.dp),
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "Memory: ${memory.content.take(30)}"
                            },
                        )
                    }
                }
                if (memories.size > 6) {
                    Spacer(Modifier.height(MaterialTheme.spacing.xs))
                    Text(
                        text = "+ ${memories.size - 6} more",
                        style = AppType.sectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    text = "No memories stored yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ── 4. SettingsGroup composable ───────────────────────────────────────────────

/**
 * Groups related settings rows under a labelled [ElevatedCard].
 * Used for "Data & Privacy" and "Account" sections.
 */
@Composable
private fun SettingsGroup(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.xs),
        ) {
            Text(
                text = title,
                style = AppType.sectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.xs,
                ),
            )
            content()
        }
    }
}

/**
 * A single tappable row inside a [SettingsGroup].
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sublabel: String? = null,
    labelColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    contentDesc: String = label,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .semantics(mergeDescendants = true) { contentDescription = contentDesc }
            // The Row is the tap target
            .let { m ->
                m.then(
                    Modifier.padding(
                        horizontal = MaterialTheme.spacing.md,
                        vertical = MaterialTheme.spacing.sm,
                    )
                )
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ── 5. Sign-out card with errorContainer styling ──────────────────────────────

@Composable
private fun SignOutCard(onLogout: () -> Unit) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "Sign out section" },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            Icon(
                imageVector = Icons.Filled.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sign Out",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "You will be returned to the login screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f),
                )
            }
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.semantics { contentDescription = "Sign out" },
            ) {
                Text("Sign Out")
            }
        }
    }
}

// ── Dialogs (logic unchanged from original) ───────────────────────────────────

@Composable
private fun EditMemoryDialog(
    memory: Memory,
    editContent: String,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Memory") },
        text = {
            Column {
                Text(
                    text = "Type: ${memoryTypeLabel(memory.memoryType)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editContent,
                    onValueChange = onContentChange,
                    label = { Text("Memory content") },
                    enabled = !isSaving,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Edit memory content" },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSaving && editContent.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Save memory edit" },
            ) {
                if (isSaving) CircularProgressIndicator(Modifier.size(16.dp))
                else Text("Save")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss, enabled = !isSaving,
                modifier = Modifier.semantics { contentDescription = "Cancel memory edit" },
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun AccountDeletionDialog(
    state: AccountDeletionState,
    onUpdateInput: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val isDeleting = state is AccountDeletionState.Deleting
    val inputValue = when (state) {
        is AccountDeletionState.Confirming -> state.confirmationInput
        is AccountDeletionState.Deleting -> state.confirmationInput
        else -> ""
    }
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text("Delete Account") },
        text = {
            Column {
                Text(
                    "This action is permanent and cannot be undone. " +
                        "All your data will be deleted within 72 hours.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onUpdateInput,
                    label = { Text("Type DELETE to confirm") },
                    enabled = !isDeleting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Type DELETE to confirm account deletion" },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting && inputValue.trim().uppercase() == "DELETE",
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.semantics { contentDescription = "Confirm account deletion" },
            ) {
                if (isDeleting) CircularProgressIndicator(Modifier.size(16.dp), color = Color.White)
                else Text("Delete Account")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss, enabled = !isDeleting,
                modifier = Modifier.semantics { contentDescription = "Cancel account deletion" },
            ) { Text("Cancel") }
        },
    )
}

@Composable
private fun AccountDeletionErrorDialog(message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Deletion Failed") },
        text = { Text(message, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss deletion error" },
            ) { Text("OK") }
        },
    )
}

// ── Helper ────────────────────────────────────────────────────────────────────

internal fun memoryTypeLabel(type: String?): String = when (type?.lowercase()) {
    "fact" -> "Fact"
    "preference" -> "Preference"
    "style" -> "Writing Style"
    else -> type?.replaceFirstChar { it.uppercase() } ?: "Memory"
}
