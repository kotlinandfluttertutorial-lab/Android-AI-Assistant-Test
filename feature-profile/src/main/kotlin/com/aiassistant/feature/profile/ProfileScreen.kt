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
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PersonOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.AlertDialog
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
import com.aiassistant.core.ui.StitchColors
import com.aiassistant.core.ui.StitchType
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.pressScale
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Memory
import com.aiassistant.domain.model.User

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    uiState: ProfileUiState,
    onNavigateUp: () -> Unit,
    onNavigateToMemoryList: () -> Unit = {},
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    onRequestDataExport: () -> Unit,
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onLogout: () -> Unit = {}
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
                        modifier = Modifier.semantics { contentDescription = "Navigate back" }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    modifier = Modifier.semantics {
                        contentDescription = "Notification: ${data.visuals.message}"
                    }
                )
            }
        }
    ) { innerPadding ->
        when (uiState) {
            is ProfileUiState.Loading -> LoadingContent(Modifier.padding(innerPadding))
            is ProfileUiState.Error -> ErrorContent(
                message = uiState.message,
                onRetry = onRetry,
                modifier = Modifier.padding(innerPadding)
            )
            is ProfileUiState.Content -> ProfileBody(
                state = uiState,
                onNavigateToMemoryList = onNavigateToMemoryList,
                onUpdateEditContent = onUpdateEditContent,
                onCancelEdit = onCancelEdit,
                onSaveEdit = onSaveEdit,
                onDeleteMemory = onDeleteMemory,
                onStartEditName = onStartEditName,
                onUpdateEditingName = onUpdateEditingName,
                onCancelEditName = onCancelEditName,
                onSaveDisplayName = onSaveDisplayName,
                onRequestDataExport = onRequestDataExport,
                onInitiateAccountDeletion = onInitiateAccountDeletion,
                onUpdateDeletionInput = onUpdateDeletionInput,
                onCancelAccountDeletion = onCancelAccountDeletion,
                onConfirmAccountDeletion = onConfirmAccountDeletion,
                onDismissDeletionError = onDismissDeletionError,
                onLogout = onLogout,
                modifier = Modifier.padding(innerPadding)
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
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Unable to load profile", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(MaterialTheme.spacing.sm))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(MaterialTheme.spacing.md))
        TextButton(
            onClick = onRetry,
            modifier = Modifier.semantics { contentDescription = "Retry loading profile" }
        ) { Text("Retry") }
    }
}

// ── Main scrollable body ──────────────────────────────────────────────────────

@Composable
private fun ProfileBody(
    state: ProfileUiState.Content,
    onNavigateToMemoryList: () -> Unit,
    onUpdateEditContent: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSaveEdit: () -> Unit,
    onDeleteMemory: (String) -> Unit,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    onRequestDataExport: () -> Unit,
    onInitiateAccountDeletion: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        // ── Stitch: gradient header band ──────────────────────────────────────
        ProfileGradientHeader(user = state.user)

        // ── Stitch: floating stats card (overlaps header by -16 dp) ───────────
        ProfileStatsCard(
            memories = state.memories,
            modifier = Modifier
                .padding(horizontal = MaterialTheme.spacing.screenEdge)
                .offset(y = (-16).dp),
        )

        // ── Stitch: Upgrade to Pro banner ─────────────────────────────────────
        val isPremium = state.user?.role == com.aiassistant.domain.model.UserRole.PREMIUM
        if (!isPremium) {
            UpgradeBannerCard(
                modifier = Modifier.padding(horizontal = MaterialTheme.spacing.screenEdge)
            )
        }

        // ── Existing: AvatarCard (name edit, tier chip, avatar) ───────────────
        Column(
            modifier = Modifier.padding(horizontal = MaterialTheme.spacing.screenEdge),
            verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
        ) {
            AvatarCard(
                user = state.user,
                isEditingName = state.isEditingName,
                editingName = state.editingName,
                isSavingName = state.isSavingName,
                onStartEditName = onStartEditName,
                onUpdateEditingName = onUpdateEditingName,
                onCancelEditName = onCancelEditName,
                onSaveDisplayName = onSaveDisplayName
            )

            MemorySummaryCard(
                memories = state.memories,
                deletingIds = state.deletingMemoryIds,
                onDeleteMemory = onDeleteMemory,
                onViewAll = onNavigateToMemoryList
            )

            ProfileSettingsGroups(
                onRequestDataExport = onRequestDataExport,
                onInitiateAccountDeletion = onInitiateAccountDeletion
            )

            SignOutCard(onLogout = onLogout)

            Spacer(Modifier.height(MaterialTheme.spacing.xl))
        }
    }

    ProfileDialogs(
        state = state,
        onUpdateEditContent = onUpdateEditContent,
        onSaveEdit = onSaveEdit,
        onCancelEdit = onCancelEdit,
        onUpdateDeletionInput = onUpdateDeletionInput,
        onConfirmAccountDeletion = onConfirmAccountDeletion,
        onCancelAccountDeletion = onCancelAccountDeletion,
        onDismissDeletionError = onDismissDeletionError
    )
}

@Composable
private fun ProfileSettingsGroups(
    onRequestDataExport: () -> Unit,
    onInitiateAccountDeletion: () -> Unit
) {
    SettingsGroup(title = "Data & Privacy") {
        SettingsRow(
            icon = Icons.Filled.Download,
            label = "Export My Data",
            sublabel = "Request a full archive (up to 24 hours)",
            onClick = onRequestDataExport,
            contentDesc = "Request data export"
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = MaterialTheme.spacing.xxl),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        SettingsRow(
            icon = Icons.Filled.Lock,
            label = "Privacy Policy",
            onClick = { },
            contentDesc = "View privacy policy"
        )
    }

    SettingsGroup(title = "Account") {
        SettingsRow(
            icon = Icons.Filled.Shield,
            label = "Change Password",
            onClick = { },
            contentDesc = "Change password"
        )
        HorizontalDivider(
            modifier = Modifier.padding(start = MaterialTheme.spacing.xxl),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        SettingsRow(
            icon = Icons.Filled.PersonOff,
            label = "Delete Account",
            labelColor = MaterialTheme.colorScheme.error,
            onClick = onInitiateAccountDeletion,
            contentDesc = "Delete account"
        )
    }
}

@Composable
private fun ProfileDialogs(
    state: ProfileUiState.Content,
    onUpdateEditContent: (String) -> Unit,
    onSaveEdit: () -> Unit,
    onCancelEdit: () -> Unit,
    onUpdateDeletionInput: (String) -> Unit,
    onConfirmAccountDeletion: () -> Unit,
    onCancelAccountDeletion: () -> Unit,
    onDismissDeletionError: () -> Unit
) {
    if (state.editingMemory != null) {
        EditMemoryDialog(
            memory = state.editingMemory,
            editContent = state.editContent,
            isSaving = state.isSavingEdit,
            onContentChange = onUpdateEditContent,
            onConfirm = onSaveEdit,
            onDismiss = onCancelEdit
        )
    }
    if (state.accountDeletionState is AccountDeletionState.Confirming ||
        state.accountDeletionState is AccountDeletionState.Deleting
    ) {
        AccountDeletionDialog(
            state = state.accountDeletionState,
            onUpdateInput = onUpdateDeletionInput,
            onConfirm = onConfirmAccountDeletion,
            onDismiss = onCancelAccountDeletion
        )
    }
    if (state.accountDeletionState is AccountDeletionState.Failed) {
        AccountDeletionErrorDialog(
            message = (state.accountDeletionState as AccountDeletionState.Failed).message,
            onDismiss = onDismissDeletionError
        )
    }
}

// ── Stitch: gradient profile header band ─────────────────────────────────────

/**
 * Full-width purple gradient band matching Stitch ProfileScreen header.
 * Shows avatar, display name, email, and plan badge on a gradient background.
 * No edit controls here — those remain in the existing AvatarCard below.
 */
@Composable
private fun ProfileGradientHeader(user: User?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(
                    listOf(
                        StitchColors.splashGradientStop2, // #6750A4
                        StitchColors.splashGradientStop3, // #9C89C4
                    )
                )
            )
            .padding(
                start = MaterialTheme.spacing.md,
                end = MaterialTheme.spacing.md,
                top = MaterialTheme.spacing.xl,
                bottom = MaterialTheme.spacing.xl + 16.dp, // extra room for floating stats card
            ),
    ) {
        // Decorative overflow circle
        Box(
            modifier = Modifier
                .size(140.dp)
                .offset(x = (-30).dp, y = (-30).dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.08f))
                .align(Alignment.TopEnd),
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md),
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Avatar circle
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            listOf(
                                // D0BCFF ≈ primary tonal; 9C89C4 = splashGradientStop3
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                                StitchColors.splashGradientStop3,
                            )
                        )
                    )
                    .semantics { contentDescription = "${user?.displayName ?: "User"}'s avatar" },
            ) {
                val initials = user?.displayName
                    ?.split(" ")
                    ?.take(2)
                    ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    ?.joinToString("") ?: "?"
                Text(
                    text = initials,
                    style = MaterialTheme.typography.headlineSmall,
                    color = StitchColors.splashGradientStop1, // #381E72 dark purple on light bg
                    fontWeight = FontWeight.Bold,
                )
            }

            Column {
                Text(
                    text = user?.displayName ?: "—",
                    style = StitchType.profileName,
                    color = Color.White,
                )
                Text(
                    text = user?.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.80f),
                )
                Spacer(Modifier.height(4.dp))
                // Plan badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = 0.20f),
                    modifier = Modifier.semantics {
                        val isPremium = user?.role == com.aiassistant.domain.model.UserRole.PREMIUM
                        contentDescription = if (isPremium) "Premium plan" else "Free plan"
                    },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(text = "⚡", style = MaterialTheme.typography.labelSmall)
                        val isPremium = user?.role == com.aiassistant.domain.model.UserRole.PREMIUM
                        Text(
                            text = if (isPremium) "Premium" else "Free Plan",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ── Stitch: floating stats card ───────────────────────────────────────────────

/**
 * 4-column stats card that floats over the gradient header (-16 dp offset).
 * Stats are derived from available data; counts without ViewModel backing use
 * the memories list as a proxy until a dedicated stats endpoint is added.
 */
@Composable
private fun ProfileStatsCard(
    memories: List<Memory>,
    modifier: Modifier = Modifier,
) {
    val isDark = isSystemInDarkTheme()

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.mid),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isDark) AppColors.surfaceTonal1Dark else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf(
                Triple("💬", "—", "Total Chats"),
                Triple("✍️", "—", "Words"),
                Triple("📄", "${memories.size}", "Memories"),
                Triple("🔥", "—", "Days Active"),
            ).forEachIndexed { i, (emoji, value, label) ->
                if (i > 0) {
                    Box(
                        modifier = Modifier
                            .height(40.dp)
                            .width(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                            .align(Alignment.CenterVertically),
                    )
                }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.xs),
                ) {
                    Text(text = emoji, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = value,
                        style = StitchType.statValue,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = label,
                        style = StitchType.statLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// ── Stitch: Upgrade to Pro gradient banner ────────────────────────────────────

/**
 * Purple gradient promotional card shown for Free plan users.
 * Matches Stitch ProfileScreen.tsx Upgrade to Pro section.
 */
@Composable
private fun UpgradeBannerCard(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        StitchColors.splashGradientStop2,
                        StitchColors.splashGradientStop1,
                    )
                )
            )
            .semantics { contentDescription = "Upgrade to Pro" },
    ) {
        // Background decorative icon
        Icon(
            imageVector = Icons.Filled.AutoAwesome,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.20f),
            modifier = Modifier
                .size(120.dp)
                .align(Alignment.CenterEnd)
                .offset(x = 20.dp),
        )
        Column(modifier = Modifier.padding(MaterialTheme.spacing.md)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Stars,
                    contentDescription = null,
                    tint = Color(0xFFFFDF93), // amber/gold
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "PREMIUM ACCESS",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.90f),
                    letterSpacing = androidx.compose.ui.unit.TextUnit(
                        1f, androidx.compose.ui.unit.TextUnitType.Sp
                    ),
                )
            }
            Spacer(Modifier.height(MaterialTheme.spacing.xs))
            Text(
                text = "Upgrade to Pro",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Unlock unlimited conversations, priority responses, and exclusive features.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(MaterialTheme.spacing.sm))
            Surface(
                onClick = { /* TODO: navigate to subscription screen */ },
                shape = RoundedCornerShape(percent = 50),
                color = Color(0xFFFFDF93), // amber/gold
                modifier = Modifier.semantics { contentDescription = "Upgrade now" },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Upgrade Now",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xFF21005D),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color(0xFF21005D),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
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
    onSaveDisplayName: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight
    val isPremium = user?.role == com.aiassistant.domain.model.UserRole.PREMIUM

    AvatarCardContent(
        user = user,
        isPremium = isPremium,
        isDark = isDark,
        isEditingName = isEditingName,
        editingName = editingName,
        isSavingName = isSavingName,
        onStartEditName = onStartEditName,
        onUpdateEditingName = onUpdateEditingName,
        onCancelEditName = onCancelEditName,
        onSaveDisplayName = onSaveDisplayName,
        gradientStart = gradientStart,
        gradientEnd = gradientEnd
    )
}

@Composable
private fun AvatarCardContent(
    user: User?,
    isPremium: Boolean,
    isDark: Boolean,
    isEditingName: Boolean,
    editingName: String,
    isSavingName: Boolean,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit,
    gradientStart: androidx.compose.ui.graphics.Color,
    gradientEnd: androidx.compose.ui.graphics.Color
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "User profile: ${user?.displayName ?: "Unknown"}" },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.mid),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            AvatarProfileHeader(
                user = user,
                isPremium = isPremium,
                isDark = isDark,
                gradientStart = gradientStart,
                gradientEnd = gradientEnd
            )

            Spacer(Modifier.height(MaterialTheme.spacing.xs))

            DisplayNameSection(
                user = user,
                isEditingName = isEditingName,
                editingName = editingName,
                isSavingName = isSavingName,
                onStartEditName = onStartEditName,
                onUpdateEditingName = onUpdateEditingName,
                onCancelEditName = onCancelEditName,
                onSaveDisplayName = onSaveDisplayName
            )

            if (!user?.email.isNullOrBlank()) {
                Text(
                    text = user?.email ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AvatarProfileHeader(
    user: User?,
    isPremium: Boolean,
    isDark: Boolean,
    gradientStart: androidx.compose.ui.graphics.Color,
    gradientEnd: androidx.compose.ui.graphics.Color
) {
    AvatarImageBox(user = user, gradientStart = gradientStart, gradientEnd = gradientEnd)
    Spacer(Modifier.height(MaterialTheme.spacing.sm))
    TierChip(isPremium = isPremium, isDark = isDark)
}

@Composable
private fun TierChip(isPremium: Boolean, isDark: Boolean) {
    SuggestionChip(
        onClick = { },
        label = {
            Text(
                text = if (isPremium) "Premium" else "Free",
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (isPremium) {
                if (isDark) {
                    AppColors.gradientStartDark.copy(alpha = 0.22f)
                } else {
                    AppColors.gradientStartLight.copy(alpha = 0.15f)
                }
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            labelColor = if (isPremium) {
                if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        ),
        modifier = Modifier.semantics {
            contentDescription = if (isPremium) "Premium account" else "Free account"
        }
    )
}

@Composable
private fun AvatarImageBox(
    user: User?,
    gradientStart: androidx.compose.ui.graphics.Color,
    gradientEnd: androidx.compose.ui.graphics.Color
) {
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(CircleShape)
                .background(Brush.linearGradient(listOf(gradientStart, gradientEnd))),
            contentAlignment = Alignment.Center
        ) {
            if (!user?.avatarUrl.isNullOrBlank()) {
                AsyncImage(
                    model = user?.avatarUrl,
                    contentDescription = "Avatar for ${user?.displayName}",
                    modifier = Modifier.fillMaxSize().clip(CircleShape)
                )
            } else {
                AvatarInitials(user = user)
            }
        }

        Surface(
            modifier = Modifier
                .size(28.dp)
                .offset(x = 4.dp, y = 4.dp)
                .semantics { contentDescription = "Edit avatar" },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.CameraAlt,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
private fun AvatarInitials(user: User?) {
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
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun DisplayNameSection(
    user: User?,
    isEditingName: Boolean,
    editingName: String,
    isSavingName: Boolean,
    onStartEditName: () -> Unit,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit
) {
    if (isEditingName) {
        DisplayNameEditor(
            editingName = editingName,
            isSavingName = isSavingName,
            onUpdateEditingName = onUpdateEditingName,
            onCancelEditName = onCancelEditName,
            onSaveDisplayName = onSaveDisplayName
        )
    } else {
        DisplayNameDisplay(user = user, onStartEditName = onStartEditName)
    }
}

@Composable
private fun DisplayNameEditor(
    editingName: String,
    isSavingName: Boolean,
    onUpdateEditingName: (String) -> Unit,
    onCancelEditName: () -> Unit,
    onSaveDisplayName: () -> Unit
) {
    OutlinedTextField(
        value = editingName,
        onValueChange = onUpdateEditingName,
        label = { Text("Display name") },
        singleLine = true,
        enabled = !isSavingName,
        modifier = Modifier.fillMaxWidth()
            .semantics { contentDescription = "Display name input" }
    )
    Spacer(Modifier.height(MaterialTheme.spacing.xs))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        TextButton(
            onClick = onCancelEditName,
            enabled = !isSavingName,
            modifier = Modifier.semantics { contentDescription = "Cancel name edit" }
        ) { Text("Cancel") }
        Spacer(Modifier.width(MaterialTheme.spacing.xs))
        Button(
            onClick = onSaveDisplayName,
            enabled = !isSavingName && editingName.isNotBlank(),
            modifier = Modifier.semantics { contentDescription = "Save display name" }
        ) {
            if (isSavingName) {
                CircularProgressIndicator(Modifier.size(16.dp))
            } else {
                Text("Save")
            }
        }
    }
}

@Composable
private fun DisplayNameDisplay(user: User?, onStartEditName: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = user?.displayName ?: "—",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(MaterialTheme.spacing.xs))
        IconButton(
            onClick = onStartEditName,
            modifier = Modifier.size(24.dp)
                .semantics { contentDescription = "Edit display name" }
        ) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
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
    onViewAll: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    MemorySummaryCardContent(
        memories = memories,
        deletingIds = deletingIds,
        onDeleteMemory = onDeleteMemory,
        onViewAll = onViewAll,
        cardColor = cardColor
    )
}

// ── 4. SettingsGroup composable ───────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MemorySummaryCardContent(
    memories: List<Memory>,
    deletingIds: Set<String>,
    onDeleteMemory: (String) -> Unit,
    onViewAll: () -> Unit,
    cardColor: androidx.compose.ui.graphics.Color
) {
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
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md)
        ) {
            MemorySummaryHeader(memories = memories)

            if (memories.isNotEmpty()) {
                MemoryChipsSection(memories = memories, deletingIds = deletingIds, onDeleteMemory = onDeleteMemory)
            } else {
                Spacer(Modifier.height(MaterialTheme.spacing.sm))
                Text(
                    text = "No memories stored yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MemoryChipsSection(memories: List<Memory>, deletingIds: Set<String>, onDeleteMemory: (String) -> Unit) {
    Spacer(Modifier.height(MaterialTheme.spacing.sm))
    MemoryChips(memories = memories, deletingIds = deletingIds, onDeleteMemory = onDeleteMemory)
    if (memories.size > 6) {
        Spacer(Modifier.height(MaterialTheme.spacing.xs))
        Text(
            text = "+ ${memories.size - 6} more",
            style = AppType.sectionLabel,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun MemorySummaryHeader(memories: List<Memory>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
        ) {
            Icon(
                Icons.Filled.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = "Memories",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${memories.size}",
                style = AppType.sectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MemoryChips(memories: List<Memory>, deletingIds: Set<String>, onDeleteMemory: (String) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs),
        verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        memories.take(6).forEach { memory ->
            MemoryChip(memory = memory, isDeleting = memory.id in deletingIds, onDelete = { onDeleteMemory(memory.id) })
        }
    }
}

@Composable
private fun MemoryChip(memory: Memory, isDeleting: Boolean, onDelete: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = { },
        label = {
            Text(
                text = memory.content.take(30) +
                    if (memory.content.length > 30) "…" else "",
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        trailingIcon = {
            if (isDeleting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(18.dp)
                        .semantics {
                            contentDescription = "Delete memory: ${memory.content.take(30)}"
                        }
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        modifier = Modifier.semantics {
            contentDescription = "Memory: ${memory.content.take(30)}"
        }
    )
}

/**
 * Groups related settings rows under a labelled [ElevatedCard].
 * Used for "Data & Privacy" and "Account" sections.
 */
@Composable
private fun SettingsGroup(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val isDark = isSystemInDarkTheme()
    val cardColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.low),
        colors = CardDefaults.elevatedCardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = MaterialTheme.spacing.xs)
        ) {
            Text(
                text = title,
                style = AppType.sectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.md,
                    vertical = MaterialTheme.spacing.xs
                )
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
    contentDesc: String = label
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressScale()
            .semantics(mergeDescendants = true) { contentDescription = contentDesc }
            .background(Color.Transparent)
            .clickable { onClick() }
            .padding(
                horizontal = MaterialTheme.spacing.md,
                vertical = MaterialTheme.spacing.sm
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, color = labelColor)
            if (sublabel != null) {
                Text(
                    text = sublabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
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
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.spacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.md)
        ) {
            Icon(
                imageVector = Icons.Filled.Logout,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Sign Out",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "You will be returned to the login screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.75f)
                )
            }
            Button(
                onClick = onLogout,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                ),
                modifier = Modifier.semantics { contentDescription = "Sign out" }
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
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text("Edit Memory") },
        text = {
            Column {
                Text(
                    text = "Type: ${memoryTypeLabel(memory.memoryType)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editContent,
                    onValueChange = onContentChange,
                    label = { Text("Memory content") },
                    enabled = !isSaving,
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Edit memory content" }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSaving && editContent.isNotBlank(),
                modifier = Modifier.semantics { contentDescription = "Save memory edit" }
            ) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                } else {
                    Text("Save")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isSaving,
                modifier = Modifier.semantics { contentDescription = "Cancel memory edit" }
            ) { Text("Cancel") }
        }
    )
}

@Composable
private fun AccountDeletionDialog(
    state: AccountDeletionState,
    onUpdateInput: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
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
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = inputValue,
                    onValueChange = onUpdateInput,
                    label = { Text("Type DELETE to confirm") },
                    enabled = !isDeleting,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .semantics { contentDescription = "Type DELETE to confirm account deletion" }
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting && inputValue.trim().uppercase() == "DELETE",
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.semantics { contentDescription = "Confirm account deletion" }
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White)
                } else {
                    Text("Delete Account")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting,
                modifier = Modifier.semantics { contentDescription = "Cancel account deletion" }
            ) { Text("Cancel") }
        }
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
                modifier = Modifier.semantics { contentDescription = "Dismiss deletion error" }
            ) { Text("OK") }
        }
    )
}

// ── Helper ────────────────────────────────────────────────────────────────────

internal fun memoryTypeLabel(type: String?): String = when (type?.lowercase()) {
    "fact" -> "Fact"
    "preference" -> "Preference"
    "style" -> "Writing Style"
    else -> type?.replaceFirstChar { it.uppercase() } ?: "Memory"
}
