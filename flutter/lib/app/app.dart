/// Root widget of the Flutter application.
///
/// Provides:
///  - GoRouter (navigation)
///  - Material 3 light + dark themes
///  - Theme mode read from [AppPreferences]
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class AiAssistantApp extends ConsumerWidget {
  const AiAssistantApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(routerProvider);
    final prefs  = ref.watch(appPreferencesProvider);

    final themeMode = switch (prefs.themeMode) {
      'light'  => ThemeMode.light,
      'dark'   => ThemeMode.dark,
      _        => ThemeMode.system,
    };

    return MaterialApp.router(
      title: 'AI Assistant',
      debugShowCheckedModeBanner: false,
      theme:     AppTheme.light,
      darkTheme: AppTheme.dark,
      themeMode: themeMode,
      routerConfig: router,
    );
  }
}
