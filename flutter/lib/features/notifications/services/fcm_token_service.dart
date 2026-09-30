/// FCM token service — obtains and registers the device push token.
///
/// ## Usage
///
/// Call [FcmTokenService.registerIfAvailable] after a successful login:
///
/// ```dart
/// final tokenService = ref.read(fcmTokenServiceProvider);
/// await tokenService.registerIfAvailable();
/// ```
///
/// ## Wiring the real FCM SDK
///
/// 1. Add to pubspec.yaml:
///    ```yaml
///    firebase_messaging: ^15.1.3
///    ```
///
/// 2. Replace [_getToken] stub with:
///    ```dart
///    Future<String?> _getToken() async {
///      // Request permission on iOS (no-op on Android).
///      await FirebaseMessaging.instance.requestPermission(
///        alert: true, badge: true, sound: true,
///      );
///      return FirebaseMessaging.instance.getToken();
///    }
///    ```
///
/// 3. Add token-refresh listener (e.g. in main.dart or app.dart):
///    ```dart
///    FirebaseMessaging.instance.onTokenRefresh.listen((newToken) {
///      ref.read(fcmTokenServiceProvider).registerToken(newToken);
///    });
///    ```
library;

import 'dart:io';

import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:ai_assistant_flutter/features/notifications/data/notifications_api.dart';

class FcmTokenService {
  FcmTokenService(this._api);
  final NotificationsApi _api;

  /// Register the device token with the backend.
  ///
  /// Does nothing when the stub returns null (FCM not yet configured).
  /// Logs a warning but does not throw — push notification failure must
  /// never degrade the core app experience.
  Future<void> registerIfAvailable() async {
    try {
      final token = await _getToken();
      if (token == null) {
        AppLogger.d('FcmTokenService: no token available (FCM not configured)');
        return;
      }
      await registerToken(token);
    } catch (e) {
      AppLogger.w('FcmTokenService: failed to register token', e);
    }
  }

  /// Register a specific token with the backend.
  ///
  /// Call this from the `onTokenRefresh` listener when FCM issues a new token.
  Future<void> registerToken(String token) async {
    AppLogger.i('FcmTokenService: registering device token');
    final platform = _platform();
    final result = await _api.registerDeviceToken(
      token: token,
      platform: platform,
    );
    result.when(
      onSuccess: (_) =>
          AppLogger.i('FcmTokenService: token registered (platform=$platform)'),
      onFailure: (err) => AppLogger.w(
        'FcmTokenService: backend rejected token — ${err.userMessage}',
      ),
    );
  }

  // ── Private ────────────────────────────────────────────────────────────────

  /// Returns the FCM token, or null if the SDK is not wired.
  ///
  /// Replace this method body with `FirebaseMessaging.instance.getToken()`
  /// once firebase_messaging is installed.
  Future<String?> _getToken() async {
    // Stub — returns null until firebase_messaging is added.
    return null;
  }

  String _platform() {
    if (Platform.isIOS || Platform.isMacOS) return 'ios';
    return 'android';
  }
}
