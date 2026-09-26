import 'package:ai_assistant_flutter/app/app.dart';
import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // Load SharedPreferences once at startup so the provider override is
  // synchronously available to every widget in the tree.
  final prefs = await SharedPreferences.getInstance();

  runApp(
    ProviderScope(
      overrides: [
        // Inject the real SharedPreferences instance.
        sharedPreferencesProvider.overrideWithValue(prefs),
      ],
      child: const AiAssistantApp(),
    ),
  );
}
