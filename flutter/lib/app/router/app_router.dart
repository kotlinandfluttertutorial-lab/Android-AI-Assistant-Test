/// GoRouter configuration — all named routes in one place.
///
/// Route guard: unauthenticated users are redirected to /login.
/// The router reacts to [authStateProvider] changes automatically.
///
/// Navigation structure:
///   ShellRoute (AdaptiveShell — 5-tab bottom nav on phone,
///                               NavigationRail on tablet ≥ 600dp)
///     /home          → HomeScreen
///     /conversations → ConversationsScreen
///     /incidents     → IncidentsScreen
///     /devops        → DevOpsChatScreen
///     /settings      → SettingsScreen
///
///   Full-screen (no nav chrome)
///     /chat/new, /chat/:id      → ChatScreen
///     /incidents/:id            → IncidentDetailScreen
///     /analysis/errors          → ErrorAnalysisScreen
///     /documents                → DocumentsScreen
///     /documents/query          → DocumentQueryScreen
library;

import 'package:ai_assistant_flutter/features/analysis/presentation/error_analysis_screen.dart';
import 'package:ai_assistant_flutter/features/auth/presentation/login_screen.dart';
import 'package:ai_assistant_flutter/features/auth/presentation/register_screen.dart';
import 'package:ai_assistant_flutter/features/auth/providers/auth_provider.dart';
import 'package:ai_assistant_flutter/features/chat/presentation/chat_screen.dart';
import 'package:ai_assistant_flutter/features/conversations/presentation/conversations_screen.dart';
import 'package:ai_assistant_flutter/features/devops/presentation/devops_chat_screen.dart';
import 'package:ai_assistant_flutter/features/home/presentation/home_screen.dart';
import 'package:ai_assistant_flutter/features/incidents/presentation/incident_detail_screen.dart';
import 'package:ai_assistant_flutter/features/incidents/presentation/incidents_screen.dart';
import 'package:ai_assistant_flutter/features/rag/presentation/document_query_screen.dart';
import 'package:ai_assistant_flutter/features/rag/presentation/documents_screen.dart';
import 'package:ai_assistant_flutter/features/settings/presentation/settings_screen.dart';
import 'package:ai_assistant_flutter/shared/widgets/adaptive_shell.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

// ── Route constants ───────────────────────────────────────────────────────────

abstract class Routes {
  // Auth
  static const splash   = '/';
  static const login    = '/login';
  static const register = '/register';

  // Shell tabs
  static const home          = '/home';
  static const conversations = '/conversations';
  static const incidents     = '/incidents';
  static const devops        = '/devops';
  static const settings      = '/settings';

  // Full-screen routes (no nav chrome)
  static const newChat = '/chat/new'; // MUST come before /chat/:id
  static const chat    = '/chat/:conversationId';

  static const incidentDetailRoute = '/incidents/:incidentId';
  static const errorAnalysis       = '/analysis/errors';
  static const documents           = '/documents';
  static const ragQuery            = '/documents/query';

  // ── Path helpers ──────────────────────────────────────────────────────────
  static String chatPath(String conversationId) => '/chat/$conversationId';
  static String incidentDetail(String id)        => '/incidents/$id';
}

// ── Router provider ───────────────────────────────────────────────────────────

final routerProvider = Provider<GoRouter>((ref) {
  final authState = ref.watch(authStateProvider);

  return GoRouter(
    initialLocation: Routes.splash,
    debugLogDiagnostics: false,
    redirect: (context, state) {
      final isLoggedIn = authState.valueOrNull?.isAuthenticated ?? false;
      final isLoading  = authState.isLoading;
      final path       = state.matchedLocation;

      if (isLoading) return null;

      final isAuthPath = path == Routes.login || path == Routes.register;

      if (!isLoggedIn && !isAuthPath) return Routes.login;
      if (isLoggedIn && isAuthPath)   return Routes.home;
      return null;
    },
    routes: [
      // ── Splash ────────────────────────────────────────────────────────
      GoRoute(
        path:    Routes.splash,
        builder: (_, __) => const _SplashPage(),
      ),

      // ── Auth ──────────────────────────────────────────────────────────
      GoRoute(
        path:    Routes.login,
        builder: (_, __) => const LoginScreen(),
      ),
      GoRoute(
        path:    Routes.register,
        builder: (_, __) => const RegisterScreen(),
      ),

      // ── Adaptive shell (phone: BottomNav, tablet: NavigationRail) ──────
      ShellRoute(
        builder: (context, state, child) => AdaptiveShell(child: child),
        routes: [
          GoRoute(
            path:    Routes.home,
            builder: (_, __) => const HomeScreen(),
          ),
          GoRoute(
            path:    Routes.conversations,
            builder: (_, __) => const ConversationsScreen(),
          ),
          GoRoute(
            path:    Routes.incidents,
            builder: (_, __) => const IncidentsScreen(),
          ),
          GoRoute(
            path:    Routes.devops,
            builder: (_, __) => const DevOpsChatScreen(),
          ),
          GoRoute(
            path:    Routes.settings,
            builder: (_, __) => const SettingsScreen(),
          ),
        ],
      ),

      // ── Chat ──────────────────────────────────────────────────────────
      GoRoute(
        path:    Routes.newChat,
        builder: (_, __) => const ChatScreen(conversationId: 'new'),
      ),
      GoRoute(
        path: Routes.chat,
        builder: (_, state) {
          final id = state.pathParameters['conversationId'] ?? 'new';
          return ChatScreen(conversationId: id);
        },
      ),

      // ── Incident detail ────────────────────────────────────────────────
      GoRoute(
        path: Routes.incidentDetailRoute,
        builder: (_, state) {
          final id = state.pathParameters['incidentId'] ?? '';
          return IncidentDetailScreen(incidentId: id);
        },
      ),

      // ── Error analysis ─────────────────────────────────────────────────
      GoRoute(
        path:    Routes.errorAnalysis,
        builder: (_, __) => const ErrorAnalysisScreen(),
      ),

      // ── RAG / Documents ────────────────────────────────────────────────
      GoRoute(
        path:    Routes.documents,
        builder: (_, __) => const DocumentsScreen(),
      ),
      GoRoute(
        path: Routes.ragQuery,
        builder: (_, state) {
          final preselected = state.extra as String?;
          return DocumentQueryScreen(preselectedDocumentId: preselected);
        },
      ),
    ],
  );
});

// ── Splash page ───────────────────────────────────────────────────────────────

class _SplashPage extends ConsumerWidget {
  const _SplashPage();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return const Scaffold(
      body: Center(child: CircularProgressIndicator.adaptive()),
    );
  }
}
