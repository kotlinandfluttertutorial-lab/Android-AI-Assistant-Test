/// Wrapper around flutter_secure_storage.
///
/// Tokens and sensitive user data are stored here.
/// Non-sensitive preferences live in [AppPreferences].
library;

import 'package:ai_assistant_flutter/core/constants/app_constants.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

class SecureStorage {
  SecureStorage() : _storage = const FlutterSecureStorage(
    aOptions: AndroidOptions(encryptedSharedPreferences: true),
    iOptions: IOSOptions(accessibility: KeychainAccessibility.first_unlock),
  );

  final FlutterSecureStorage _storage;

  // ── Access token ─────────────────────────────────────────────────────────

  Future<void> saveAccessToken(
    String token,
    int expiresAtMs,
  ) async {
    await Future.wait([
      _storage.write(key: AppConstants.secureAccessToken, value: token),
      _storage.write(
        key: AppConstants.secureAccessTokenExpiry,
        value: expiresAtMs.toString(),
      ),
    ]);
  }

  Future<String?> getAccessToken() =>
      _storage.read(key: AppConstants.secureAccessToken);

  Future<int?> getAccessTokenExpiry() async {
    final raw = await _storage.read(key: AppConstants.secureAccessTokenExpiry);
    return raw == null ? null : int.tryParse(raw);
  }

  // ── Refresh token ─────────────────────────────────────────────────────────

  Future<void> saveRefreshToken(
    String token,
    int expiresAtMs,
  ) async {
    await Future.wait([
      _storage.write(key: AppConstants.secureRefreshToken, value: token),
      _storage.write(
        key: AppConstants.secureRefreshTokenExpiry,
        value: expiresAtMs.toString(),
      ),
    ]);
  }

  Future<String?> getRefreshToken() =>
      _storage.read(key: AppConstants.secureRefreshToken);

  // ── User identity ─────────────────────────────────────────────────────────

  Future<void> saveUserInfo({
    required String userId,
    required String email,
    required String role,
  }) async {
    await Future.wait([
      _storage.write(key: AppConstants.secureUserId, value: userId),
      _storage.write(key: AppConstants.secureUserEmail, value: email),
      _storage.write(key: AppConstants.secureUserRole, value: role),
    ]);
  }

  Future<String?> getUserId() =>
      _storage.read(key: AppConstants.secureUserId);

  Future<String?> getUserEmail() =>
      _storage.read(key: AppConstants.secureUserEmail);

  Future<String?> getUserRole() =>
      _storage.read(key: AppConstants.secureUserRole);

  // ── Token helpers ─────────────────────────────────────────────────────────

  /// Returns true when an access token exists and is not expired (with buffer).
  Future<bool> isAccessTokenValid() async {
    final token = await getAccessToken();
    if (token == null) return false;
    final expiry = await getAccessTokenExpiry();
    if (expiry == null) return false;
    final bufferMs = AppConstants.tokenRefreshBufferSeconds * 1000;
    return DateTime.now().millisecondsSinceEpoch < (expiry - bufferMs);
  }

  // ── Clear ─────────────────────────────────────────────────────────────────

  Future<void> clearAll() async {
    AppLogger.i('SecureStorage: clearing all credentials');
    await _storage.deleteAll();
  }
}
