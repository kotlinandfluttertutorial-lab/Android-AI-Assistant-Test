/// Auth repository — orchestrates API calls + secure token persistence.
library;

import 'package:ai_assistant_flutter/core/storage/secure_storage.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/auth/data/auth_api.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';

class AuthRepository {
  const AuthRepository({
    required AuthApi api,
    required SecureStorage storage,
  })  : _api = api,
        _storage = storage;

  final AuthApi _api;
  final SecureStorage _storage;

  // ── Login ─────────────────────────────────────────────────────────────────

  Future<Result<AuthUser>> login(LoginRequest request) async {
    final result = await _api.login(request);
    return result.when(
      onSuccess: (response) async {
        await _persistTokens(
          accessToken:            response.accessToken,
          refreshToken:           response.refreshToken,
          accessTokenExpiresAt:   response.accessTokenExpiresAt,
          refreshTokenExpiresAt:  response.refreshTokenExpiresAt,
          userId:                 response.userId,
          email:                  response.email,
          role:                   response.role,
        );
        return Success(
          AuthUser(
            userId: response.userId,
            email:  response.email,
            role:   response.role,
          ),
        );
      },
      onFailure: Failure.new,
    );
  }

  // ── Register ──────────────────────────────────────────────────────────────

  Future<Result<AuthUser>> register(RegisterRequest request) async {
    final result = await _api.register(request);
    return result.when(
      onSuccess: (response) async {
        await _persistTokens(
          accessToken:            response.accessToken,
          refreshToken:           response.refreshToken,
          accessTokenExpiresAt:   response.accessTokenExpiresAt,
          refreshTokenExpiresAt:  response.refreshTokenExpiresAt,
          userId:                 response.userId,
          email:                  response.email,
          role:                   'user',
        );
        return Success(
          AuthUser(
            userId: response.userId,
            email:  response.email,
            role:   'user',
          ),
        );
      },
      onFailure: Failure.new,
    );
  }

  // ── Logout ────────────────────────────────────────────────────────────────

  Future<Result<void>> logout() async {
    // Best-effort: fire logout API, then always clear local storage.
    await _api.logout();
    await _storage.clearAll();
    return const Success(null);
  }

  // ── Session restore ───────────────────────────────────────────────────────

  /// Returns the stored [AuthUser] if a valid token exists, null otherwise.
  Future<AuthUser?> restoreSession() async {
    final isValid = await _storage.isAccessTokenValid();
    if (!isValid) return null;
    final userId = await _storage.getUserId();
    final email  = await _storage.getUserEmail();
    final role   = await _storage.getUserRole();
    if (userId == null || email == null || role == null) return null;
    return AuthUser(userId: userId, email: email, role: role);
  }

  // ── Private helpers ───────────────────────────────────────────────────────

  Future<void> _persistTokens({
    required String accessToken,
    required String refreshToken,
    required int accessTokenExpiresAt,
    required int refreshTokenExpiresAt,
    required String userId,
    required String email,
    required String role,
  }) async {
    await Future.wait([
      _storage.saveAccessToken(accessToken, accessTokenExpiresAt),
      _storage.saveRefreshToken(refreshToken, refreshTokenExpiresAt),
      _storage.saveUserInfo(userId: userId, email: email, role: role),
    ]);
  }
}
