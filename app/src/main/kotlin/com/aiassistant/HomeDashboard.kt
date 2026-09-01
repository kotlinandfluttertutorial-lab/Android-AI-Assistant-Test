/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : HomeDashboard.kt
 * Purpose    : Redesigned Home Dashboard (Task 50.3) — hub screen with a
 *              gradient hero "Ask AI" card, pressScale FeatureCards,
 *              QuickActionChip row, ConversationPreviewCard list (swipe-to-
 *              dismiss), and a redesigned NavigationBar with animated
 *              selected indicator.
 *
 * Architecture Layer : App — navigation shell + Compose UI.
 *                      Reads state from HomeDashboardViewModel; all navigation
 *                      is delegated via navController callbacks.
 *
 * Dependencies       : core-ui (AppColors, AppType, pressScale, spacing,
 *                      elevation), domain models, Hilt navigation-compose.
 *
 * Design Decision    : The hero card uses a Brush.linearGradient overlay on
 *                      an ElevatedCard so the gradient is rendered on the GPU
 *                      without a custom Canvas draw — compatible with M3
 *                      card semantics (click, accessibility, shape).
 *                      SwipeToDismiss wraps each ConversationPreviewCard so
 *                      the dismiss gesture is fully accessible via the
 *                      DismissState API (M3 SwipeToDismiss / experimental).
 *                      pressScale is applied to FeatureCards via the
 *                      core-ui motion modifier.
 *
 * Requirements       : 19.1, 24.1, 24.2, 24.3
 * ============================================================
 */
package com.aiassistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ConfirmationNumber
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GTranslate
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.MeetingRoom
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NoteAlt
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppType
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

// ── Navigation bar items ──────────────────────────────────────────────────────

private data class BottomNavItem(
    val label: String,
    val icon: ImageVector,
    val route: String,
    val contentDesc: String = label
)

private val bottomNavItems = listOf(
    BottomNavItem("Chat", Icons.Outlined.Forum, ChatRoute.LIST),
    BottomNavItem("History", Icons.Outlined.History, HistoryRoute.GRAPH),
    BottomNavItem("Voice", Icons.Outlined.Mic, VoiceRoute.GRAPH),
    BottomNavItem("Notes", Icons.Outlined.NoteAlt, NotesRoute.GRAPH),
    // Renamed from "Tasks" → "Tickets" per Task 50.3 spec
    BottomNavItem(
        "Tickets",
        Icons.Outlined.ConfirmationNumber,
        ProductivityRoute.GRAPH,
        contentDesc = "Tickets and productivity"
    )
)

// ── Feature grid items ────────────────────────────────────────────────────────

private data class FeatureCardItem(
    val label: String,
    val icon: ImageVector,
    val route: String,
    // optional left-border or icon tint override
    val accentColor: Color? = null
)

private val featureCards = listOf(
    FeatureCardItem("Documents\n& RAG", Icons.AutoMirrored.Outlined.LibraryBooks, RAGRoute.DOCUMENT_LIST),
    FeatureCardItem("Camera\n& Vision", Icons.Outlined.Camera, CAMERA_ROUTE),
    FeatureCardItem("Code\nAssistant", Icons.Outlined.Code, CodeRoute.GRAPH),
    FeatureCardItem("Resume\nBuilder", Icons.Outlined.Description, ResumeRoute.GRAPH),
    FeatureCardItem("Email\nComposer", Icons.Outlined.Email, EmailRoute.GRAPH),
    FeatureCardItem("Meeting\nRecorder", Icons.Outlined.MeetingRoom, meetingRoute()),
    FeatureCardItem("Translator", Icons.Outlined.GTranslate, TRANSLATOR_ROUTE),
    FeatureCardItem("Settings", Icons.Outlined.Settings, SettingsRoute.SCREEN),
    FeatureCardItem("Profile", Icons.Outlined.Person, ProfileRoute.SCREEN),
    FeatureCardItem("DevOps\nDashboard", Icons.Outlined.MonitorHeart, DashboardRoute.SCREEN)
)

// ── Quick-action definitions ──────────────────────────────────────────────────

private data class QuickAction(val label: String, val icon: ImageVector, val route: String)

private val quickActions = listOf(
    QuickAction("New Chat", Icons.Outlined.Chat, ChatRoute.LIST),
    QuickAction("Voice", Icons.Outlined.Mic, VoiceRoute.GRAPH),
    QuickAction("Translate", Icons.Outlined.GTranslate, TRANSLATOR_ROUTE),
    QuickAction("Camera", Icons.Outlined.Camera, CAMERA_ROUTE),
    QuickAction("Notes", Icons.Outlined.NoteAlt, NotesRoute.GRAPH)
)

// ── Route constant ────────────────────────────────────────────────────────────

const val HOME_ROUTE = "home"

// ── Entry composable ──────────────────────────────────────────────────────────

/**
 * Redesigned Home Dashboard hub composable.
 *
 * @param navController Root [NavHostController] for navigation dispatch.
 * @param viewModel     Hilt-injected [HomeDashboardViewModel].
 */
@Composable
fun homeDashboard(navController: NavHostController, viewModel: HomeDashboardViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    val isDark = isSystemInDarkTheme()

    Scaffold(
        bottomBar = {
            AppNavigationBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    navController.navigate(route) {
                        popUpTo(HOME_ROUTE) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
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
                        vertical = MaterialTheme.spacing.md
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

            // ── Recent conversations (max 3) ──────────────────────────────
            val conversations = ready?.recentConversations ?: emptyList()
            AnimatedVisibility(
                visible = conversations.isNotEmpty(),
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
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

            // LazyVerticalGrid inside a scroll-able Column requires a fixed height.
            // We use a 2-column grid and compute height: ceil(items/2) * rowHeight.
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
                userScrollEnabled = false // parent Column is the scroll container
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
    val gradientEnd = if (isDark) AppColors.gradientEndDark else AppColors.gradientEndLight

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .pressScale()
            .semantics { contentDescription = "Ask AI hero card — tap to start a new chat" },
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = MaterialTheme.elevation.high
        ),
        shape = RoundedCornerShape(20.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Gradient accent stripe (left edge)
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
                        imageVector = Icons.Outlined.AutoAwesome,
                        contentDescription = null,
                        tint = gradientStart,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Ask AI anything →",
                        style = MaterialTheme.typography.bodyMedium,
                        color = gradientStart
                    )
                }
            }
        }
    }
}

// ── Quick-action chip row ─────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
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
            FilterChip(
                selected = false,
                onClick = { onActionClick(action.route) },
                label = { Text(action.label, style = MaterialTheme.typography.labelMedium) },
                leadingIcon = {
                    Icon(action.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.semantics { contentDescription = action.label },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    iconColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            )
        }
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
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            // Red dismiss background revealed on swipe
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.errorContainer),
                contentAlignment = Alignment.CenterEnd
            ) {
                Text(
                    text = "Dismiss",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
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
                .semantics {
                    contentDescription = "Conversation: ${conversation.title}"
                },
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
                    imageVector = Icons.Outlined.Chat,
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

    ElevatedCard(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .pressScale()
            .semantics { contentDescription = label.replace("\n", " ") },
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

// ── Redesigned NavigationBar with animated indicator ─────────────────────────

@Composable
private fun AppNavigationBar(currentRoute: String?, onNavigate: (String) -> Unit) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    NavigationBar(
        containerColor = surfaceColor,
        tonalElevation = 0.dp // flat surface — tonal elevation handled by surfaceTonal1
    ) {
        bottomNavItems.forEach { item ->
            val selected = currentRoute == item.route ||
                currentRoute?.startsWith(item.route.substringBefore("/")) == true

            // Animate icon scale for the selected indicator
            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.15f else 1f,
                animationSpec = tween(durationMillis = 200),
                label = "navIconScale_${item.label}"
            )

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.contentDesc,
                        modifier = Modifier.size((24 * iconScale).dp)
                    )
                },
                label = { Text(item.label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}
