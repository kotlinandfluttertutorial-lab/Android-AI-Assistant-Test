import 'package:ai_assistant_flutter/core/error/app_error_freezed.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AppErrorF sealed union', () {
    // ── userMessage getter ─────────────────────────────────────────────────

    test('networkUnavailable.userMessage mentions network', () {
      final msg = const AppErrorF.networkUnavailable().userMessage;
      expect(msg.toLowerCase(), contains('internet'));
    });

    test('timeout.userMessage mentions timeout', () {
      final msg = const AppErrorF.timeout().userMessage;
      expect(msg.toLowerCase(), contains('timed out'));
    });

    test('unauthorized.userMessage mentions sign in', () {
      final msg = const AppErrorF.unauthorized().userMessage;
      expect(msg.toLowerCase(), contains('sign in'));
    });

    test('forbidden.userMessage mentions permission', () {
      final msg = const AppErrorF.forbidden().userMessage;
      expect(msg.toLowerCase(), contains('permission'));
    });

    test('notFound.userMessage includes resource name', () {
      final msg = const AppErrorF.notFound(resource: 'Document').userMessage;
      expect(msg, contains('Document'));
    });

    test('rateLimited with retryAfter includes seconds', () {
      final msg = const AppErrorF.rateLimited(retryAfterSeconds: 30).userMessage;
      expect(msg, contains('30'));
    });

    test('rateLimited without retryAfter still returns message', () {
      final msg = const AppErrorF.rateLimited().userMessage;
      expect(msg, isNotEmpty);
    });

    test('serverError.userMessage is safe for end users', () {
      final msg = const AppErrorF.serverError(detail: 'DB connection failed').userMessage;
      // Must NOT expose internal detail to users
      expect(msg.contains('DB'), isFalse);
      expect(msg, isNotEmpty);
    });

    test('aiProvider.userMessage mentions service', () {
      final msg = const AppErrorF.aiProvider(detail: 'Quota exceeded').userMessage;
      expect(msg.toLowerCase(), contains('service'));
    });

    test('websocketDisconnect.userMessage mentions reconnect', () {
      final msg = const AppErrorF.websocketDisconnect().userMessage;
      expect(msg.toLowerCase(), contains('reconnect'));
    });

    test('unknown.userMessage is safe fallback', () {
      final msg = const AppErrorF.unknown().userMessage;
      expect(msg, isNotEmpty);
    });

    // ── when() exhaustive matching ─────────────────────────────────────────

    test('when() routes to correct branch for networkUnavailable', () {
      const error = AppErrorF.networkUnavailable();
      final result = error.when(
        networkUnavailable: () => 'network',
        timeout:            () => 'timeout',
        unauthorized:       () => 'unauth',
        forbidden:          () => 'forbidden',
        notFound:           (_) => 'notfound',
        validation:         (_, __) => 'validation',
        rateLimited:        (_) => 'ratelimited',
        serverError:        (_) => 'server',
        aiProvider:         (_) => 'ai',
        websocketDisconnect: () => 'ws',
        unknown:            (_) => 'unknown',
      );
      expect(result, 'network');
    });

    test('when() routes to correct branch for rateLimited', () {
      const error = AppErrorF.rateLimited(retryAfterSeconds: 60);
      final result = error.when(
        networkUnavailable: () => '',
        timeout:            () => '',
        unauthorized:       () => '',
        forbidden:          () => '',
        notFound:           (_) => '',
        validation:         (_, __) => '',
        rateLimited:        (s) => 'limited:$s',
        serverError:        (_) => '',
        aiProvider:         (_) => '',
        websocketDisconnect: () => '',
        unknown:            (_) => '',
      );
      expect(result, 'limited:60');
    });

    test('when() routes to correct branch for notFound with resource', () {
      const error = AppErrorF.notFound(resource: 'Incident');
      final result = error.when(
        networkUnavailable: () => '',
        timeout:            () => '',
        unauthorized:       () => '',
        forbidden:          () => '',
        notFound:           (r) => 'notfound:$r',
        validation:         (_, __) => '',
        rateLimited:        (_) => '',
        serverError:        (_) => '',
        aiProvider:         (_) => '',
        websocketDisconnect: () => '',
        unknown:            (_) => '',
      );
      expect(result, 'notfound:Incident');
    });

    // ── maybeWhen() ───────────────────────────────────────────────────────

    test('maybeWhen() calls orElse when variant is not matched', () {
      const error = AppErrorF.forbidden();
      final result = error.maybeWhen(
        unauthorized: () => 'unauth',
        orElse:       () => 'other',
      );
      expect(result, 'other');
    });

    test('maybeWhen() calls matched variant', () {
      const error = AppErrorF.unauthorized();
      final result = error.maybeWhen(
        unauthorized: () => 'unauth',
        orElse:       () => 'other',
      );
      expect(result, 'unauth');
    });

    // ── whenOrNull() ──────────────────────────────────────────────────────

    test('whenOrNull() returns null when variant is not matched', () {
      const error = AppErrorF.timeout();
      final result = error.whenOrNull(
        unauthorized: () => 'unauth',
      );
      expect(result, isNull);
    });

    test('whenOrNull() returns value when variant matches', () {
      const error = AppErrorF.timeout();
      final result = error.whenOrNull(
        timeout: () => 'timed_out',
      );
      expect(result, 'timed_out');
    });
  });
}
