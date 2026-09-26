/// Riverpod providers for shared infrastructure objects.
///
/// These are constructed once at app startup and shared across all features
/// via the Riverpod provider graph.
library;

import 'package:ai_assistant_flutter/core/network/auth_interceptor.dart';
import 'package:ai_assistant_flutter/core/network/dio_client.dart';
import 'package:ai_assistant_flutter/core/storage/app_preferences.dart';
import 'package:ai_assistant_flutter/core/storage/secure_storage.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:dio/dio.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:shared_preferences/shared_preferences.dart';

// ── SecureStorage ─────────────────────────────────────────────────────────────

final secureStorageProvider = Provider<SecureStorage>((ref) {
  return SecureStorage();
});

// ── SharedPreferences ─────────────────────────────────────────────────────────
// Loaded once during ProviderScope.overrides at startup.

final sharedPreferencesProvider = Provider<SharedPreferences>((ref) {
  throw StateError(
    'sharedPreferencesProvider must be overridden in ProviderScope. '
    'Call SharedPreferences.getInstance() in main() and pass it as an override.',
  );
});

final appPreferencesProvider = Provider<AppPreferences>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  return AppPreferences(prefs);
});

// ── Dio HTTP client ──────────────────────────────────────────────────────────

/// Notifier that other providers can call to signal authentication loss.
/// The router watches [authStateProvider] which will react to this.
final authExpiredNotifierProvider =
    StateProvider<int>((ref) => 0);

final authInterceptorProvider = Provider<AuthInterceptor>((ref) {
  final storage = ref.watch(secureStorageProvider);
  return AuthInterceptor(
    storage: storage,
    onAuthExpired: () {
      AppLogger.w('AuthInterceptor: session expired — incrementing counter');
      ref.read(authExpiredNotifierProvider.notifier).state++;
    },
  );
});

final dioProvider = Provider<Dio>((ref) {
  final interceptor = ref.watch(authInterceptorProvider);
  return createDio(interceptor);
});
