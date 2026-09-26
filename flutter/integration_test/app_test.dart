/// End-to-end integration test skeleton.
///
/// Flow tested:
///   Launch → Splash/Auth redirect → Login form present
///
/// The full happy-path (Login → Home → Open Chat → Send → Receive) requires
/// a running backend. Use a mock server or the local Docker stack:
///
///   docker compose up -d
///   flutter test integration_test/ --dart-define=ENV=local
///
/// ⚠️  These tests do NOT call the production API. All assertions below
/// are against the unauthenticated state (no stored tokens).
library;

import 'package:ai_assistant_flutter/app/app.dart';
import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();

  group('App launch — unauthenticated', () {
    testWidgets('app starts and redirects to Login screen', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
          ],
          child: const AiAssistantApp(),
        ),
      );

      // Allow auth state to resolve (session restore completes).
      await tester.pumpAndSettle(const Duration(seconds: 3));

      // Should redirect to login screen.
      expect(find.text('Sign in'), findsOneWidget);
      expect(find.text('Welcome back'), findsOneWidget);
    });

    testWidgets('login screen has email and password fields', (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
          ],
          child: const AiAssistantApp(),
        ),
      );
      await tester.pumpAndSettle(const Duration(seconds: 3));

      expect(find.widgetWithText(TextFormField, 'Email'), findsOneWidget);
      expect(find.widgetWithText(TextFormField, 'Password'), findsOneWidget);
    });

    testWidgets('tapping "Create account" navigates to Register screen',
        (tester) async {
      SharedPreferences.setMockInitialValues({});
      final prefs = await SharedPreferences.getInstance();

      await tester.pumpWidget(
        ProviderScope(
          overrides: [
            sharedPreferencesProvider.overrideWithValue(prefs),
          ],
          child: const AiAssistantApp(),
        ),
      );
      await tester.pumpAndSettle(const Duration(seconds: 3));

      await tester.tap(find.widgetWithText(TextButton, 'Create account'));
      await tester.pumpAndSettle();

      expect(find.text('Create account'), findsWidgets);
    });
  });
}
