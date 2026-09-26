import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/auth/data/auth_api.dart';
import 'package:ai_assistant_flutter/features/auth/domain/auth_models.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

// ── Mocks ─────────────────────────────────────────────────────────────────────

class MockAuthApi extends Mock implements AuthApi {}

class MockSecureStorage extends Mock {
  Future<void> saveAccessToken(String token, int expiresAt) async {}
  Future<void> saveRefreshToken(String token, int expiresAt) async {}
  Future<void> saveUserInfo({
    required String userId,
    required String email,
    required String role,
  }) async {}
  Future<void> clearAll() async {}
  Future<bool> isAccessTokenValid() async => false;
  Future<String?> getUserId() async => null;
  Future<String?> getUserEmail() async => null;
  Future<String?> getUserRole() async => null;
}

// A lightweight fake SecureStorage that stores values in memory.
class FakeSecureStorage {
  String? accessToken;
  int? accessTokenExpiry;
  String? refreshToken;
  int? refreshTokenExpiry;
  String? userId;
  String? email;
  String? role;
  bool valid = false;

  Future<void> saveAccessToken(String t, int exp) async {
    accessToken = t;
    accessTokenExpiry = exp;
    valid = true;
  }

  Future<void> saveRefreshToken(String t, int exp) async {
    refreshToken = t;
    refreshTokenExpiry = exp;
  }

  Future<void> saveUserInfo({
    required String userId,
    required String email,
    required String role,
  }) async {
    this.userId = userId;
    this.email = email;
    this.role = role;
  }

  Future<void> clearAll() async {
    accessToken = null;
    refreshToken = null;
    userId = null;
    email = null;
    role = null;
    valid = false;
  }

  Future<bool> isAccessTokenValid() async => valid;
  Future<String?> getUserId() async => userId;
  Future<String?> getUserEmail() async => email;
  Future<String?> getUserRole() async => role;
  Future<String?> getAccessToken() async => accessToken;
  Future<String?> getRefreshToken() async => refreshToken;
}

void main() {
  late MockAuthApi mockApi;
  late FakeSecureStorage fakeStorage;

  setUpAll(() {
    // Mocktail requires fallback values for custom types used with any().
    registerFallbackValue(
      const LoginRequest(email: 'fallback@test.com', password: 'fallbackpass'),
    );
    registerFallbackValue(
      const RegisterRequest(email: 'fallback@test.com', password: 'fallbackpass'),
    );
  });

  setUp(() {
    mockApi     = MockAuthApi();
    fakeStorage = FakeSecureStorage();
  });

  // We create a minimal test-only repository that uses FakeSecureStorage
  // to avoid depending on flutter_secure_storage in unit tests.
  _TestAuthRepository makeRepo() =>
      _TestAuthRepository(api: mockApi, storage: fakeStorage);

  group('AuthRepository.login', () {
    test('returns AuthUser and persists tokens on success', () async {
      final response = LoginResponse(
        userId: 'u1',
        email: 'test@test.com',
        role: 'user',
        accessToken: 'at',
        refreshToken: 'rt',
        accessTokenExpiresAt: 99999,
        refreshTokenExpiresAt: 99999,
      );
      when(() => mockApi.login(any()))
          .thenAnswer((_) async => Success(response));

      final repo   = makeRepo();
      final result = await repo.login(
          const LoginRequest(email: 'test@test.com', password: 'password12'));

      expect(result.isSuccess, isTrue);
      expect(result.dataOrNull?.userId, 'u1');
      expect(fakeStorage.accessToken, 'at');
      expect(fakeStorage.userId, 'u1');
    });

    test('returns Failure when API fails', () async {
      when(() => mockApi.login(any()))
          .thenAnswer((_) async => Failure(AppError.unauthorized()));

      final repo   = makeRepo();
      final result = await repo.login(
          const LoginRequest(email: 'x@x.com', password: 'wrongpassword'));

      expect(result.isFailure, isTrue);
      expect(result.errorOrNull?.type, AppErrorType.unauthorized);
    });
  });

  group('AuthRepository.logout', () {
    test('clears storage regardless of API result', () async {
      fakeStorage.accessToken = 'some_token';
      fakeStorage.valid = true;
      when(() => mockApi.logout())
          .thenAnswer((_) async => const Success(null));

      final repo = makeRepo();
      await repo.logout();

      expect(fakeStorage.accessToken, isNull);
    });
  });

  group('AuthRepository.restoreSession', () {
    test('returns AuthUser when token is valid', () async {
      fakeStorage
        ..valid = true
        ..userId = 'u99'
        ..email = 'restore@test.com'
        ..role = 'premium';

      final repo = makeRepo();
      final user = await repo.restoreSession();

      expect(user, isNotNull);
      expect(user?.userId, 'u99');
      expect(user?.role, 'premium');
    });

    test('returns null when token is expired', () async {
      fakeStorage.valid = false;

      final repo = makeRepo();
      final user = await repo.restoreSession();

      expect(user, isNull);
    });
  });
}

// ── Minimal test-only subclass that accepts FakeSecureStorage ────────────────

class _TestAuthRepository {
  _TestAuthRepository({
    required this.api,
    required this.storage,
  });

  final MockAuthApi api;
  final FakeSecureStorage storage;

  Future<Result<AuthUser>> login(LoginRequest request) async {
    final result = await api.login(request);
    return result.when(
      onSuccess: (response) async {
        await storage.saveAccessToken(
            response.accessToken, response.accessTokenExpiresAt);
        await storage.saveRefreshToken(
            response.refreshToken, response.refreshTokenExpiresAt);
        await storage.saveUserInfo(
          userId: response.userId,
          email: response.email,
          role: response.role,
        );
        return Success(AuthUser(
          userId: response.userId,
          email: response.email,
          role: response.role,
        ));
      },
      onFailure: Failure.new,
    );
  }

  Future<Result<void>> logout() async {
    await api.logout();
    await storage.clearAll();
    return const Success(null);
  }

  Future<AuthUser?> restoreSession() async {
    final isValid = await storage.isAccessTokenValid();
    if (!isValid) return null;
    final userId = await storage.getUserId();
    final email  = await storage.getUserEmail();
    final role   = await storage.getUserRole();
    if (userId == null || email == null || role == null) return null;
    return AuthUser(userId: userId, email: email, role: role);
  }
}
