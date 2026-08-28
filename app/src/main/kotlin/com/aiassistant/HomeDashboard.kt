/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : HomeDashboard.kt
 * Purpose    : Hub screen that gives users access to all 15 feature areas of the app.
 * Architecture: app module — pure navigation composable, no ViewModel.
 * Dependencies: AndroidX Navigation Compose, Material 3.
 *
 * Design decisions:
 * - Primary destinations (Chat, History, Voice, Notes, Productivity) are surfaced in
 *   a Material 3 NavigationBar for one-tap access.
 * - Secondary feature areas (RAG, Camera, Code, Resume, Email, Meeting, Translator,
 *   Settings, Profile) are presented as a LazyVerticalGrid of FeatureCard tiles.
 * - No ViewModel needed — this screen is purely navigation dispatch.
 * - The navController is passed in from the parent NavHost so navigation stays in one place.
 *
 * Requirements: 19.1, 21.4
 * ============================================================
 */
package com.aiassistant

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.outlined.Camera
import androidx.compose.material.icons.outlined.Code
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
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
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
// â”€â”€â”€ Bottom navigation items â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private data class BottomNavItem(val label: String, val icon: ImageVector, val route: String)

private val bottomNavItems = listOf(
    BottomNavItem("Chat", Icons.Outlined.Forum, ChatRoute.LIST),
    BottomNavItem("History", Icons.Outlined.History, HistoryRoute.GRAPH),
    BottomNavItem("Voice", Icons.Outlined.Mic, VoiceRoute.GRAPH),
    BottomNavItem("Notes", Icons.Outlined.NoteAlt, NotesRoute.GRAPH),
    BottomNavItem("Tasks", Icons.Outlined.TaskAlt, ProductivityRoute.GRAPH)
)

// â”€â”€â”€ Feature card items (grid) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

private data class FeatureCardItem(val label: String, val icon: ImageVector, val route: String)

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

/**
 * Hub composable that wires all feature navigation entry points.
 *
 * Primary destinations are in the bottom navigation bar; secondary features are
 * displayed as tappable cards in a 2-column grid.
 *
 * @param navController Root [NavHostController] used to push destinations.
 */
@Composable
fun homeDashboard(navController: NavHostController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar {
                bottomNavItems.forEach { item ->
                    NavigationBarItem(
                        selected = currentRoute == item.route ||
                            currentRoute?.startsWith(item.route.substringBefore("/")) == true,
                        onClick = {
                            navController.navigate(item.route) {
                                // Avoid multiple copies on back stack
                                popUpTo(HOME_ROUTE) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = item.icon,
                                contentDescription = item.label
                            )
                        },
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item(key = "header") {
                // spans both columns via wrapContentWidth; kept simple with a Box
            }
            items(featureCards, key = { it.label }) { card ->
                featureCard(
                    label = card.label,
                    icon = card.icon,
                    onClick = { navController.navigate(card.route) }
                )
            }
        }
    }
}

/** Route string for the Home Dashboard screen. */
const val HOME_ROUTE = "home"

// â”€â”€â”€ Private sub-composables â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

@Composable
private fun featureCard(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(110.dp)
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
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
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}
