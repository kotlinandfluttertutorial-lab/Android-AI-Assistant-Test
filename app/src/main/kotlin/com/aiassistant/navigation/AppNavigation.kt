/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : navigation/AppNavigation.kt
 * Purpose    : Production navigation shell — adaptive NavigationBar (compact),
 *              NavigationRail (medium), and NavigationDrawer (expanded/tablet).
 *              Replaces the inline NavigationBar in HomeDashboard.kt.
 *
 * Architecture Layer : App — navigation shell only; no business logic.
 *
 * Design Decision    : Navigation chrome is window-size-aware using
 *                      WindowSizeClass from the Material3 adaptive library.
 *                      Compact   → NavigationBar (bottom)
 *                      Medium    → NavigationRail (start side)
 *                      Expanded  → Permanent NavigationDrawer (start side)
 *
 *                      The drawer is split into two composables:
 *                        - AppNavigationDrawerContent — the drawer's content
 *                        - AppNavigationShell — the outer adaptive wrapper
 *
 * Requirements       : 19.1, 23.3, 24.4
 * ============================================================
 */
package com.aiassistant.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.aiassistant.HOME_ROUTE
import com.aiassistant.core.ui.AppColors
import com.aiassistant.core.ui.AppIcons
import com.aiassistant.core.ui.DURATION_QUICK
import com.aiassistant.core.ui.spacing
import com.aiassistant.feature.camera.CAMERA_ROUTE
import com.aiassistant.feature.chat.ChatRoute
import com.aiassistant.feature.history.HistoryRoute
import com.aiassistant.feature.notes.NotesRoute
import com.aiassistant.feature.productivity.ProductivityRoute
import com.aiassistant.feature.profile.ProfileRoute
import com.aiassistant.feature.rag.RAGRoute
import com.aiassistant.feature.settings.SettingsRoute
import com.aiassistant.feature.voice.VoiceRoute
import kotlinx.coroutines.launch

// ── Navigation item data model ────────────────────────────────────────────────

data class NavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: String,
    val contentDescription: String = label
)

data class NavSection(
    val title: String?,           // null = no header
    val items: List<NavItem>
)

// ── Bottom navigation items (compact/phone) ───────────────────────────────────

val bottomNavItems = listOf(
    NavItem(
        label = "Chat",
        selectedIcon = AppIcons.Destinations.ChatFilled,
        unselectedIcon = AppIcons.Destinations.ChatOutlined,
        route = ChatRoute.LIST
    ),
    NavItem(
        label = "History",
        selectedIcon = AppIcons.Destinations.HistoryFilled,
        unselectedIcon = AppIcons.Destinations.HistoryOutlined,
        route = HistoryRoute.GRAPH
    ),
    NavItem(
        label = "Docs",
        selectedIcon = AppIcons.Destinations.DocumentsFilled,
        unselectedIcon = AppIcons.Destinations.DocumentsOutlined,
        route = RAGRoute.DOCUMENT_LIST
    ),
    NavItem(
        label = "Voice",
        selectedIcon = AppIcons.Chat.Mic,
        unselectedIcon = AppIcons.Chat.Mic,
        route = VoiceRoute.GRAPH
    ),
    NavItem(
        label = "Settings",
        selectedIcon = AppIcons.Destinations.SettingsFilled,
        unselectedIcon = AppIcons.Destinations.SettingsOutlined,
        route = SettingsRoute.SCREEN
    )
)

// ── Drawer navigation sections ────────────────────────────────────────────────

val drawerSections = listOf(
    NavSection(
        title = null,
        items = listOf(
            NavItem("New Chat", AppIcons.Destinations.NewChat, AppIcons.Destinations.NewChat, ChatRoute.LIST),
            NavItem("Conversations", AppIcons.Destinations.ChatFilled, AppIcons.Destinations.ChatOutlined, ChatRoute.LIST),
            NavItem("History", AppIcons.Destinations.HistoryFilled, AppIcons.Destinations.HistoryOutlined, HistoryRoute.GRAPH),
            NavItem("Documents", AppIcons.Destinations.DocumentsFilled, AppIcons.Destinations.DocumentsOutlined, RAGRoute.DOCUMENT_LIST),
            NavItem("Favorites", AppIcons.Destinations.Favorites, AppIcons.Destinations.FavoritesOutlined, HistoryRoute.GRAPH)
        )
    ),
    NavSection(
        title = "AI",
        items = listOf(
            NavItem("On-device Gemma", AppIcons.Ai.Gemma, AppIcons.Ai.Gemma, VoiceRoute.GRAPH),
            NavItem("Cloud AI", AppIcons.Ai.Cloud, AppIcons.Ai.Cloud, ChatRoute.LIST),
            NavItem("RAG Documents", AppIcons.Ai.Rag, AppIcons.Ai.Rag, RAGRoute.DOCUMENT_LIST)
        )
    ),
    NavSection(
        title = "Settings",
        items = listOf(
            NavItem("Settings", AppIcons.Destinations.SettingsFilled, AppIcons.Destinations.SettingsOutlined, SettingsRoute.SCREEN),
            NavItem("Profile", AppIcons.Destinations.Profile, AppIcons.Destinations.ProfileOutlined, ProfileRoute.SCREEN),
            NavItem("Notes", AppIcons.Chat.Mic, AppIcons.Chat.Mic, NotesRoute.GRAPH)
        )
    )
)

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun isRouteSelected(currentRoute: String?, itemRoute: String): Boolean {
    if (currentRoute == null) return false
    return currentRoute == itemRoute ||
        currentRoute.startsWith(itemRoute.substringBefore("/").ifBlank { itemRoute })
}

// ── Drawer content ────────────────────────────────────────────────────────────

/**
 * Content composable for the navigation drawer.
 * Used by both [ModalNavigationDrawer] (compact) and [PermanentNavigationDrawer]
 * (expanded).
 */
@Composable
fun AppNavigationDrawerContent(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val drawerSurface = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    Surface(
        modifier = modifier
            .fillMaxHeight()
            .width(280.dp),
        color = drawerSurface,
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = MaterialTheme.spacing.md)
        ) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MaterialTheme.spacing.md, vertical = MaterialTheme.spacing.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = AppIcons.Ai.Assistant,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(MaterialTheme.spacing.sm))
                Text(
                    text = "AI Assistant",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.sm))

            drawerSections.forEachIndexed { sectionIndex, section ->
                // Section divider (except before first section)
                if (sectionIndex > 0) {
                    HorizontalDivider(
                        modifier = Modifier.padding(
                            horizontal = MaterialTheme.spacing.md,
                            vertical = MaterialTheme.spacing.xs
                        ),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }

                // Section header
                if (section.title != null) {
                    Text(
                        text = section.title.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(
                            start = MaterialTheme.spacing.md + 12.dp,
                            top = MaterialTheme.spacing.sm,
                            bottom = MaterialTheme.spacing.xs
                        )
                    )
                }

                // Section items
                section.items.forEach { item ->
                    val selected = isRouteSelected(currentRoute, item.route)
                    val iconColor by animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        animationSpec = tween(durationMillis = DURATION_QUICK),
                        label = "drawerItemIconColor_${item.label}"
                    )

                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = item.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        selected = selected,
                        onClick = { onNavigate(item.route) },
                        modifier = Modifier
                            .padding(horizontal = MaterialTheme.spacing.sm)
                            .semantics {
                                contentDescription = item.contentDescription
                                role = Role.Tab
                            },
                        colors = NavigationDrawerItemDefaults.colors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurface,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(MaterialTheme.spacing.xl))
        }
    }
}

// ── Navigation Rail (medium windows) ─────────────────────────────────────────

/**
 * Navigation Rail for medium-width windows (small tablets, large phones in landscape).
 */
@Composable
fun AppNavigationRail(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        header = {
            // AI assistant logo mark in rail header
            Icon(
                imageVector = AppIcons.Ai.Assistant,
                contentDescription = "AI Assistant",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(vertical = MaterialTheme.spacing.md)
                    .size(24.dp)
            )
        }
    ) {
        bottomNavItems.forEach { item ->
            val selected = isRouteSelected(currentRoute, item.route)

            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.1f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMedium
                ),
                label = "railIconScale_${item.label}"
            )

            NavigationRailItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size((20 * iconScale).dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                alwaysShowLabel = false
            )
        }
    }
}

// ── Navigation Bar (compact/phone) ────────────────────────────────────────────

/**
 * Bottom navigation bar for compact-width windows (phones in portrait).
 */
@Composable
fun AppNavigationBar(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val surfaceColor = if (isDark) AppColors.surfaceTonal1Dark else AppColors.surfaceTonal1Light

    NavigationBar(
        modifier = modifier,
        containerColor = surfaceColor,
        tonalElevation = 0.dp
    ) {
        bottomNavItems.forEach { item ->
            val selected = isRouteSelected(currentRoute, item.route)

            val iconScale by animateFloatAsState(
                targetValue = if (selected) 1.15f else 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow
                ),
                label = "navBarIconScale_${item.label}"
            )

            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(item.route) },
                icon = {
                    Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = item.contentDescription,
                        modifier = Modifier.size((22 * iconScale).dp)
                    )
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
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

// ── Adaptive navigation shell ─────────────────────────────────────────────────

/**
 * Adaptive navigation shell composable.
 *
 * Wraps [content] in the appropriate navigation chrome based on window size:
 * - Compact → Bottom NavigationBar
 * - Medium  → NavigationRail (start)
 * - Expanded → Permanent NavigationDrawer (start)
 *
 * @param navController    Root nav controller for current route detection.
 * @param widthSizeClass   Current window width size class.
 * @param content          Screen content slot.
 */
@Composable
fun AppNavigationShell(
    navController: NavHostController,
    widthSizeClass: WindowWidthSizeClass,
    content: @Composable () -> Unit
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    fun navigate(route: String) {
        navController.navigate(route) {
            popUpTo(HOME_ROUTE) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    when (widthSizeClass) {
        WindowWidthSizeClass.Expanded -> {
            // Permanent drawer for large tablets
            PermanentNavigationDrawer(
                drawerContent = {
                    AppNavigationDrawerContent(
                        currentRoute = currentRoute,
                        onNavigate = ::navigate
                    )
                }
            ) {
                content()
            }
        }

        WindowWidthSizeClass.Medium -> {
            // Rail for medium windows (tablet portrait / large phone landscape)
            Row(modifier = Modifier.fillMaxSize()) {
                AppNavigationRail(
                    currentRoute = currentRoute,
                    onNavigate = ::navigate
                )
                Box(modifier = Modifier.weight(1f)) {
                    content()
                }
            }
        }

        else -> {
            // Bottom bar for compact (phones)
            val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
            val scope = rememberCoroutineScope()

            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    AppNavigationDrawerContent(
                        currentRoute = currentRoute,
                        onNavigate = { route ->
                            scope.launch { drawerState.close() }
                            navigate(route)
                        }
                    )
                }
            ) {
                content()
            }
        }
    }
}
