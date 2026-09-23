/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : HomeDashboard.kt
 * Purpose    : Production-quality Home Dashboard hub screen (Phase 3.4).
 *
 *              Changes from Phase 3.4 upgrade:
 *              - Removed inline NavigationBar (now in AppNavigationShell)
 *              - Added AI mode status row (Gemma / Cloud AI indicator)
 *              - Replaced all emoji/text icons with AppIcons references
 *              - Improved hero card with gradient accent + assistant icon
 *              - Added meaningful empty state when no recent conversations
 *              - Upgraded QuickAction chips with proper icons
 *              - Added menu/drawer open button in top bar
 *              - Consistent spacing using MaterialTheme.spacing tokens
 *
 * Architecture Layer : App — navigation shell + Compose UI.
 *                      Reads state from HomeDashboardViewModel; navigation
 *                      is delegated via navController callbacks.
 *
 * Requirements       : 19.1, 24.1, 24.2, 24.3
 * ============================================================
 */
package com.aiassistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.AppType
import com.aiassistant.core.ui.components.EnvironmentIndicator
import com.aiassistant.core.ui.elevation
import com.aiassistant.core.ui.motion.pressScale
import com.aiassistant.core.ui.spacing
import com.aiassistant.domain.model.Conversation
import com.aiassistant.feature.camera.CAMERA_ROUTE
import com.aiassistant.feature.chat.ChatRoute
import com.aiassistant.feature.code.CodeRoute
import com.aiassistant.feature.dashboard.DashboardRoute
import com.aiassistant.feature.email.EmailRoute
import com.aiassistant.feature.history.HistoryRoute
import com.aiassistant.feature.meeting.meetingRoute
import com.aiassistant.feature.notes.NotesRoute
import com.aiassistant.feature.productivity.ProductivityRoute
import com.aiassistant.feature.profile.ProfileRoute
import com.aiassistant.feature.rag.RAGRoute
import com.aiassistant.feature.resume.ResumeRoute
import com.aiassistant.feature.settings.SettingsRoute
import com.aiassistant.feature.translator.TRANSLATOR_ROUTE
import com.aiassistant.feature.voice.VoiceRoute

// ── Feature grid items ────────────────────────────────────────────────────────

private data class FeatureCardItem(
    val label: String,
    val icon: ImageVector,
    val route: String
)

private val featureCards = listOf(
    FeatureCardItem("Documents & RAG",    AppIcons.Destinations.DocumentsFilled, RAGRoute.DOCUMENT_LIST),
    FeatureCardItem("Camera & Vision",    AppIcons.Chat.Camera,                   CAMERA_ROUTE),
    FeatureCardItem("Code Assistant",     AppIcons.Chat.Code,                     CodeRoute.GRAPH),
    FeatureCardItem("Resume Builder",     AppIcons.Documents.Document,            ResumeRoute.GRAPH),
    FeatureCardItem("Email Composer",     AppIcons.Destinations.DocumentsFilled,  EmailRoute.GRAPH),
    FeatureCardItem("Meeting Recorder",   AppIcons.Chat.Mic,                      meetingRoute()),
    FeatureCardItem("Translator",         AppIcons.Settings.About,                TRANSLATOR_ROUTE),
    FeatureCardItem("Settings",           AppIcons.Destinations.SettingsFilled,   SettingsRoute.SCREEN),
    FeatureCardItem("Profile",            AppIcons.Destinations.Profile,          ProfileRoute.SCREEN),
    FeatureCardItem("DevOps Dashboard",   AppIcons.Status.Syncing,               DashboardRoute.SCREEN)
)

// ── Quick-action definitions ──────────────────────────────────────────────────

private data class QuickAction(val label: String, val icon: ImageVector, val route: String)

private val quickActions = listOf(
    QuickAction("New Chat",  AppIcons.Destinations.NewChat,        ChatRoute.LIST),
    QuickAction("Voice",     AppIcons.Chat.Mic,                    VoiceRoute.GRAPH),
    QuickAction("Documents", AppIcons.Documents.Document,          RAGRoute.DOCUMENT_LIST),
    QuickAction("History",   AppIcons.Destinations.HistoryFilled,  HistoryRoute.GRAPH),
    QuickAction("Notes",     AppIcons.Documents.Document,          NotesRoute.GRAPH)
)

// ── Route constant ────────────────────────────────────────────────────────────

const val HOME_ROUTE = "home"

// ── Entry composable ──────────────────────────────────────────────────────────

/**
 * Home Dashboard hub composable.
 *
 * The [AppNavigationShell] in [MainActivity] provides the bottom/rail/drawer nav chrome.
 * This composable owns only its own top bar and content.
 *
 * @param navController Root [NavHostController] for navigation dispatch.
 * @param viewModel     Hilt-injected [HomeDashboardViewModel].
 * @param onOpenDrawer  Called when the menu icon is tapped (compact mode only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun homeDashboard(
    navController: NavHostController,
    viewModel: HomeDashboardViewModel = hiltViewModel(),
    onOpenDrawer: (() -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsState()
    val isDark = isSystemInDarkTheme()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
                    ) {
                        Text(
                            text = "AI Assistant",
                            style = MaterialTheme.typography.titleMedium
                        )
                        // Visible only in stage builds — zero cost in production.
                        EnvironmentIndicator(isStage = !viewModel.isProduction)
                    }
                },
                navigationIcon = {
                    if (onOpenDrawer != null) {
                        IconButton(
                            onClick = onOpenDrawer,
                            modifier = Modifier.semantics {
                                contentDescription = "Open navigation menu"
                            }
                        ) {
                            Icon(
                                imageVector = AppIcons.Navigation.Menu,
                                contentDescription = null
                            )
                        }
                    }
                },
                actions = {
                    // Settings shortcut
                    IconButton(
                        onClick = { navController.navigate(SettingsRoute.SCREEN) },
                        modifier = Modifier.semantics {
                            contentDescription = "Open settings"
                        }
                    ) {
                        Icon(
                            imageVector = AppIcons.Destinations.SettingsOutlined,
                            contentDescription = null
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            val ready = uiState as? HomeDashboardUiState.Ready

            // ── AI mode status row ─────────────────────────────────────────
            AiModeStatusRow(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.screenEdge,
                    vertical = MaterialTheme.spacing.xs
                )
            )

            // ── Hero "Ask AI" card ─────────────────────────────────────────
            HeroAskAiCard(
                userName = ready?.userName ?: "there",
                todayDate = ready?.todayDate ?: "",
                isDark = isDark,
                onClick = { navController.navigate(ChatRoute.LIST) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.sm
                    )
            )

            // ── Quick-action chips ─────────────────────────────────────────
            QuickActionChipRow(
                actions = quickActions,
                onActionClick = { navController.navigate(it) },
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.screenEdge,
                    bottom = MaterialTheme.spacing.sm
                )
            )

            // ── Recent conversations ───────────────────────────────────────
            val conversations = ready?.recentConversations ?: emptyList()

            if (conversations.isNotEmpty()) {
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { it / 4 })
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = MaterialTheme.spacing.screenEdge)
                    ) {
                        Text(
                            text = "RECENT",
                            style = AppType.sectionLabel,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = MaterialTheme.spacing.xs)
                        )
                        conversations.take(3).forEach { conversation ->
                            ConversationPreviewCard(
                                conversation = conversation,
                                onTap = { navController.navigate(ChatRoute.detail(conversation.id)) },
                                onDismiss = { viewModel.dismissConversation(conversation.id) }
                            )
                            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                        }
                        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
                    }
                }
            } else if (ready != null) {
                // Empty state for recent conversations
                EmptyRecentConversations(
                    onStartChat = { navController.navigate(ChatRoute.LIST) },
                    modifier = Modifier.padding(
                        horizontal = MaterialTheme.spacing.screenEdge,
                        vertical = MaterialTheme.spacing.sm
                    )
                )
            }

            // ── Feature cards grid ─────────────────────────────────────────
            Text(
                text = "FEATURES",
                style = AppType.sectionLabel,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    start = MaterialTheme.spacing.screenEdge,
                    bottom = MaterialTheme.spacing.xs
                )
            )

            val gridRowHeight = 110.dp
            val gridRows = (featureCards.size + 1) / 2
            val gridHeight = gridRowHeight * gridRows + MaterialTheme.spacing.sm * (gridRows - 1)

            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(gridHeight + MaterialTheme.spacing.lg)
                    .padding(horizontal = MaterialTheme.spacing.screenEdge),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.sm),
                contentPadding = PaddingValues(bottom = MaterialTheme.spacing.sm),
                userScrollEnabled = false
            ) {
                items(featureCards, key = { it.label }) { card ->
                    FeatureCard(
                        label = card.label,
                        icon = card.icon,
                        isDark = isDark,
                        onClick = { navController.navigate(card.route) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

// ── AI mode status row ────────────────────────────────────────────────────────

/**
 * Compact row showing the current AI mode with a subtle chip indicator.
 * Tapping opens the AI settings or mode selector.
 */
@Composable
private fun AiModeStatusRow(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()
    val containerColor = if (isDark) AppColors.gemmaContainerDark else AppColors.gemmaContainerLight
    val onContainerColor = if (isDark) AppColors.gemmaOnContainerDark else AppColors.gemmaOnContainerLight
    val indicatorColor = if (isDark) AppColors.gemmaIndicatorDark else AppColors.gemmaIndicatorLight

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        Surface(
            color = containerColor,
            shape = MaterialTheme.shapes.extraSmall,
            modifier = Modifier.semantics {
                contentDescription = "AI mode: On-device Gemma — tap to change"
            }
        ) {
            Row(
                modifier = Modifier.padding(
                    horizontal = MaterialTheme.spacing.sm,
                    vertical = MaterialTheme.spacing.xs
                ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Ai.Gemma,
                    contentDescription = null,
                    tint = indicatorColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "On-device Gemma",
                    style = MaterialTheme.typography.labelSmall,
                    color = onContainerColor
                )
            }
        }
    }
}

// ── Hero "Ask AI" card ────────────────────────────────────────────────────────

@Composable
private fun HeroAskAiCard(
    userName: String,
    todayDate: String,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientStart = if (isDark) AppColors.gradientStartDark else AppColors.gradientStartLight
    val gradientEnd   = if (isDark) AppColors.gradientEndDark   else AppColors.gradientEndLight

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.pressScale().semantics {
            contentDescription = "Start new AI conversation. Tap to open chat."
        },
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = MaterialTheme.elevation.high),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Left-edge gradient accent stripe
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .height(120.dp)
                    .align(Alignment.CenterStart)
                    .background(Brush.verticalGradient(listOf(gradientStart, gradientEnd)))
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 20.dp, end = 20.dp, bottom = 20.dp)
            ) {
                if (todayDate.isNotBlank()) {
                    Text(
                        text = todayDate,
                        style = AppType.sectionLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
                }

                Text(
                    text = "Good day, $userName",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = AppIcons.Ai.Assistant,
                        contentDescription = null,
                        tint = gradientStart,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ask AI anything",
                        style = MaterialTheme.typography.bodyMedium,
                        color = gradientStart
                    )
                }
            }
        }
    }
}

// ── Quick-action chip row ─────────────────────────────────────────────────────

@Composable
private fun QuickActionChipRow(
    actions: List<QuickAction>,
    onActionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(end = MaterialTheme.spacing.screenEdge),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.spacing.xs)
    ) {
        items(actions, key = { it.label }) { action ->
            AssistChip(
                onClick = { onActionClick(action.route) },
                label = { Text(action.label, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.semantics { contentDescription = action.label },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
    }
}

// ── Empty state for recent conversations ──────────────────────────────────────

@Composable
private fun EmptyRecentConversations(
    onStartChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No conversations yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.xs))
        Text(
            text = "Start your first conversation with the AI assistant.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))
        FilterChip(
            selected = false,
            onClick = onStartChat,
            label = { Text("Start chatting", style = MaterialTheme.typography.labelMedium) },
            leadingIcon = {
                Icon(
                    imageVector = AppIcons.Destinations.NewChat,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            },
            colors = FilterChipDefaults.filterChipColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                iconColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            modifier = Modifier.semantics { contentDescription = "Start chatting button" }
        )
    }
}

// ── Conversation preview card with swipe-to-dismiss ──────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationPreviewCard(
    conversation: Conversation,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) {
                onDismiss()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = AppIcons.Chat.Delete,
                    contentDescription = "Dismiss conversation",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(end = MaterialTheme.spacing.md)
                )
            }
        },
        modifier = modifier,
        enableDismissFromStartToEnd = false
    ) {
        ElevatedCard(
            onClick = onTap,
            modifier = Modifier
                .fillMaxWidth()
                .pressScale()
                .semantics { contentDescription = "Conversation: ${conversation.title}" },
            elevation = CardDefaults.elevatedCardElevation(
                defaultElevation = MaterialTheme.elevation.low
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Destinations.ChatOutlined,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = conversation.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = conversation.updatedAt?.toString() ?: "",
                        style = AppType.chatTimestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = AppIcons.Navigation.Forward,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Feature card ──────────────────────────────────────────────────────────────

@Composable
private fun FeatureCard(
    label: String,
    icon: ImageVector,
    isDark: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    val scaleAnim by animateFloatAsState(
        targetValue = 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "featureCardScale_$label"
    )

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .pressScale()
            .semantics { contentDescription = label },
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = MaterialTheme.elevation.low
        ),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = containerColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(MaterialTheme.spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = MaterialTheme.spacing.xs),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
