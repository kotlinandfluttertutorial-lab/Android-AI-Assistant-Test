/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : app
 * File       : MainActivity.kt
 * Purpose    : Single-activity entry point; hosts the root [NavHost] that wires together
 *              all 15 feature navigation graphs and the Home Dashboard.
 * Architecture: app module — navigation shell only; no business logic.
 * Dependencies: All feature-* navigation extensions, core-ui (AppTheme), Firebase Analytics.
 *
 * Requirements: 19.1, 21.4
 * ============================================================
 */
package com.aiassistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import com.aiassistant.core.ui.AppTheme
import com.aiassistant.feature.auth.AuthRoute
import com.aiassistant.feature.auth.authNavGraph
import com.aiassistant.feature.camera.cameraNavGraph
import com.aiassistant.feature.chat.ChatRoute
import com.aiassistant.feature.chat.chatNavGraph
import com.aiassistant.feature.code.codeNavGraph
import com.aiassistant.feature.email.emailNavGraph
import com.aiassistant.feature.history.historyNavGraph
import com.aiassistant.feature.meeting.meetingNavGraph
import com.aiassistant.feature.meeting.meetingRoute
import com.aiassistant.feature.notes.notesNavGraph
import com.aiassistant.feature.productivity.ProductivityRoute
import com.aiassistant.feature.productivity.productivityNavGraph
import com.aiassistant.feature.profile.profileNavGraph
import com.aiassistant.feature.rag.RAGRoute
import com.aiassistant.feature.rag.ragNavGraph
import com.aiassistant.feature.resume.resumeNavGraph
import com.aiassistant.feature.settings.SettingsRoute
import com.aiassistant.feature.settings.settingsNavGraph
import com.aiassistant.feature.translator.TRANSLATOR_ROUTE
import com.aiassistant.feature.translator.translatorNavGraph
import com.aiassistant.feature.voice.VoiceRoute
import com.aiassistant.feature.voice.voiceNavGraph
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.logEvent
import dagger.hilt.android.AndroidEntryPoint

/** Deep link URI scheme and host used across all feature deep-link patterns. */
private const val DEEP_LINK_BASE = "aiassistant://open"

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val navController = rememberNavController()

                // Track screen_view events automatically on every destination change.
                screenViewTracker(navController = navController)

                rootNavHost(navController = navController)
            }
        }
    }
}

/**
 * Attaches a [NavController.OnDestinationChangedListener] to log `screen_view` events
 * via [FirebaseAnalytics] every time the active destination changes.
 *
 * Using a listener at the NavController level means every navigation destination is
 * tracked automatically â€” no per-screen wiring required.
 */
@Composable
private fun screenViewTracker(navController: NavHostController) {
    val context = LocalContext.current
    DisposableEffect(navController) {
        val analytics = FirebaseAnalytics.getInstance(context)
        val listener = NavController.OnDestinationChangedListener { _, destination, _ ->
            val route = destination.route ?: return@OnDestinationChangedListener
            val screenName = route
                .substringAfterLast("/")
                .substringBefore("?")
                .substringBefore("{")
                .trim('/')
                .replaceFirstChar { it.uppercaseChar() }
                .ifBlank { route }

            analytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW) {
                param(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
                param(FirebaseAnalytics.Param.SCREEN_CLASS, route)
            }
        }
        navController.addOnDestinationChangedListener(listener)
        onDispose {
            navController.removeOnDestinationChangedListener(listener)
        }
    }
}

/**
 * Root [NavHost] wiring all 15 feature navigation graphs into a single back-stack.
 *
 * Navigation flow:
 *   Splash (auth graph) â†’ Onboarding â†’ Login â†’ HomeDashboard
 *
 * Feature graphs are registered flat in the root NavHost so any screen can navigate
 * across graph boundaries using [NavHostController.navigate] with a route string.
 *
 * Deep links are declared on the chat, rag, voice, meeting, translator, and
 * productivity graphs per Requirement 19.1:
 *   aiassistant://open/chat          â†’ chat/list
 *   aiassistant://open/rag           â†’ rag/documents
 *   aiassistant://open/voice         â†’ voice graph
 *   aiassistant://open/meeting       â†’ meeting graph
 *   aiassistant://open/translator    â†’ translator
 *   aiassistant://open/productivity  â†’ productivity graph
 */
@Composable
private fun rootNavHost(navController: NavHostController) {
    NavHost(
        navController = navController,
        startDestination = AuthRoute.GRAPH
    ) {
        // ── Auth sub-graph (Splash → Onboarding → Login → Register) ──────────
        authNavGraph(
            navController = navController,
            onAuthSuccess = {
                navController.navigate(HOME_ROUTE) {
                    popUpTo(AuthRoute.GRAPH) { inclusive = true }
                }
            }
        )

        // â”€â”€ Home Dashboard â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        composable(route = HOME_ROUTE) {
            homeDashboard(navController = navController)
        }

        // â”€â”€ Chat (deep link: aiassistant://open/chat) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        chatNavGraph(
            navController = navController,
            onNavigateToDetail = { conversationId ->
                navController.navigate(ChatRoute.detail(conversationId))
            }
        )

        // Attach deep link to the chat list entry; done by adding a secondary composable
        // that catches the deep link and re-routes to the canonical route.
        composable(
            route = "deeplink/chat",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/chat" }
            )
        ) {
            // Re-navigate into the real chat destination so the back stack is correct.
            navController.navigate(ChatRoute.List) {
                popUpTo("deeplink/chat") { inclusive = true }
            }
        }

        // â”€â”€ RAG / Documents (deep link: aiassistant://open/rag) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        ragNavGraph(navController = navController)

        composable(
            route = "deeplink/rag",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/rag" }
            )
        ) {
            navController.navigate(RAGRoute.DocumentList) {
                popUpTo("deeplink/rag") { inclusive = true }
            }
        }

        // â”€â”€ Voice (deep link: aiassistant://open/voice) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        voiceNavGraph(
            navController = navController,
            onNavigateBack = { navController.popBackStack() }
        )

        composable(
            route = "deeplink/voice",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/voice" }
            )
        ) {
            navController.navigate(VoiceRoute.Graph) {
                popUpTo("deeplink/voice") { inclusive = true }
            }
        }

        // â”€â”€ Meeting (deep link: aiassistant://open/meeting) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        meetingNavGraph(
            navController = navController,
            onNavigateBack = { navController.popBackStack() }
        )

        composable(
            route = "deeplink/meeting",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/meeting" }
            )
        ) {
            navController.navigate(meetingRoute()) {
                popUpTo("deeplink/meeting") { inclusive = true }
            }
        }

        // â”€â”€ Translator (deep link: aiassistant://open/translator) â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        translatorNavGraph(
            onNavigateBack = { navController.popBackStack() }
        )

        composable(
            route = "deeplink/translator",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/translator" }
            )
        ) {
            navController.navigate(TRANSLATOR_ROUTE) {
                popUpTo("deeplink/translator") { inclusive = true }
            }
        }

        // â”€â”€ Productivity (deep link: aiassistant://open/productivity) â”€â”€â”€â”€â”€â”€â”€â”€â”€
        productivityNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        composable(
            route = "deeplink/productivity",
            deepLinks = listOf(
                navDeepLink { uriPattern = "$DEEP_LINK_BASE/productivity" }
            )
        ) {
            navController.navigate(ProductivityRoute.Graph) {
                popUpTo("deeplink/productivity") { inclusive = true }
            }
        }

        // â”€â”€ Camera â†’ Settings cross-graph link â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        cameraNavGraph(
            navController = navController,
            onNavigateToSettings = {
                navController.navigate(SettingsRoute.SCREEN)
            }
        )

        // â”€â”€ Code â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        codeNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        // â”€â”€ Settings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        settingsNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        // â”€â”€ Profile â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        profileNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        // â”€â”€ History â†’ Chat cross-graph link â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        historyNavGraph(
            navController = navController,
            onConversationClick = { conversationId ->
                navController.navigate(ChatRoute.detail(conversationId))
            }
        )

        // â”€â”€ Notes â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        notesNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        // â”€â”€ Resume â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        resumeNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )

        // â”€â”€ Email â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        emailNavGraph(
            navController = navController,
            onNavigateUp = { navController.popBackStack() }
        )
    }
}
