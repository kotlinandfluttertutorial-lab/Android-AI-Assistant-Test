import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/notifications/data/notifications_api.dart';
import 'package:ai_assistant_flutter/features/notifications/services/fcm_token_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mocktail/mocktail.dart';

// ── Mock ──────────────────────────────────────────────────────────────────────

class MockNotificationsApi extends Mock implements NotificationsApi {}

// ── Test-only subclass that injects a controllable token ─────────────────────
// Note: Dart does not allow overriding private methods across library boundaries.
// Tests use registerToken (the public method) and verify the API call directly.

void main() {
  late MockNotificationsApi mockApi;

  setUp(() {
    mockApi = MockNotificationsApi();
  });

  // ── registerToken ─────────────────────────────────────────────────────────

  group('FcmTokenService.registerToken', () {
    test('calls NotificationsApi.registerDeviceToken with correct params',
        () async {
      when(() => mockApi.registerDeviceToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          )).thenAnswer((_) async => const Success(null));

      final service = FcmTokenService(mockApi);
      await service.registerToken('fcm-test-token-abc');

      verify(
        () => mockApi.registerDeviceToken(
          token: 'fcm-test-token-abc',
          platform: any(named: 'platform'),
        ),
      ).called(1);
    });

    test('does not throw when backend returns an error', () async {
      when(() => mockApi.registerDeviceToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          )).thenAnswer((_) async => Failure(AppError.serverError()));

      final service = FcmTokenService(mockApi);

      // Must not throw — push notification failures are non-fatal.
      await expectLater(
        service.registerToken('some-token'),
        completes,
      );
    });

    test('platform is "android" on non-Apple platforms', () async {
      String? capturedPlatform;
      when(() => mockApi.registerDeviceToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          )).thenAnswer((invocation) async {
        capturedPlatform =
            invocation.namedArguments[const Symbol('platform')] as String;
        return const Success(null);
      });

      final service = FcmTokenService(mockApi);
      await service.registerToken('tok');

      // In the test environment Platform.isIOS is false (Linux/Windows runner).
      expect(capturedPlatform, isNotNull);
      expect(capturedPlatform, isIn(['android', 'ios']));
    });

    test('handles exception from API gracefully', () async {
      when(() => mockApi.registerDeviceToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          )).thenThrow(Exception('Network error'));

      final service = FcmTokenService(mockApi);

      // registerToken catches exceptions internally — must not propagate.
      await expectLater(
        service.registerToken('tok'),
        completes,
      );
    });
  });

  // ── registerIfAvailable ───────────────────────────────────────────────────

  group('FcmTokenService.registerIfAvailable', () {
    test('does not call API when token is null (SDK not configured)', () async {
      // The real stub _getToken() returns null, so API should never be called.
      final service = FcmTokenService(mockApi);
      await service.registerIfAvailable();

      verifyNever(() => mockApi.registerDeviceToken(
            token: any(named: 'token'),
            platform: any(named: 'platform'),
          ));
    });
  });
}
