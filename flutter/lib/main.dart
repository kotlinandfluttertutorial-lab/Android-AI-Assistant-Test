import 'package:ai_assistant_flutter/app/app.dart';
import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/observability/providers/observability_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();

  final prefs = await SharedPreferences.getInstance();

  // Create a ProviderContainer so we can read the observability service
  // before runApp() and register the lifecycle observer.
  final container = ProviderContainer(
    overrides: [
      sharedPreferencesProvider.overrideWithValue(prefs),
    ],
  );

  // Register the app lifecycle observer so the observability service
  // flushes buffered events when the app is backgrounded.
  final observabilityService = container.read(observabilityServiceProvider);
  final lifecycleObserver    = AppLifecycleObserver(observabilityService);
  WidgetsBinding.instance.addObserver(lifecycleObserver);

  runApp(
    UncontrolledProviderScope(
      container: container,
      child: const AiAssistantApp(),
    ),
  );
}
