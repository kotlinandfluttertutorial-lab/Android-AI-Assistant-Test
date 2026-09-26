/// Freezed-annotated version of AppError.
///
/// This sits alongside the hand-written [AppError] class.
/// The developer can migrate to this version gradually:
///
/// 1. Run: flutter pub run build_runner build --delete-conflicting-outputs
/// 2. Replace usages of [AppError] with [AppErrorF] where desired.
///
/// Demonstrates the sealed union pattern — each error type is a separate
/// factory constructor, enabling exhaustive switch handling:
///
/// ```dart
/// final error = AppErrorF.unauthorized();
/// final message = error.when(
///   networkUnavailable: () => 'No internet',
///   timeout:            () => 'Request timed out',
///   unauthorized:       () => 'Please sign in again',
///   // … etc.
/// );
/// ```
library;

import 'package:freezed_annotation/freezed_annotation.dart';

part 'app_error_freezed.freezed.dart';

/// Freezed sealed union of all application error types.
///
/// Each variant corresponds to a [AppErrorType] value in the hand-written
/// [AppError] class. The freezed pattern makes exhaustive handling a
/// compile-time guarantee.
@freezed
sealed class AppErrorF with _$AppErrorF {
  const AppErrorF._();

  const factory AppErrorF.networkUnavailable() = NetworkUnavailableError;
  const factory AppErrorF.timeout()            = TimeoutError;

  const factory AppErrorF.unauthorized() = UnauthorizedError;

  const factory AppErrorF.forbidden() = ForbiddenError;

  const factory AppErrorF.notFound({required String resource}) = NotFoundError;

  const factory AppErrorF.validation({
    required String message,
    String? code,
  }) = ValidationError;

  const factory AppErrorF.rateLimited({int? retryAfterSeconds}) =
      RateLimitedError;

  const factory AppErrorF.serverError({String? detail}) = ServerError;

  const factory AppErrorF.aiProvider({required String detail}) = AiProviderError;

  const factory AppErrorF.websocketDisconnect() = WebSocketDisconnectError;

  const factory AppErrorF.unknown({String? detail}) = UnknownError;

  // ── Convenience getter ───────────────────────────────────────────────────

  /// Safe, user-facing message. Never contains stack traces or internals.
  String get userMessage => when(
        networkUnavailable: () =>
            'No internet connection. Please check your network.',
        timeout:     () => 'The request timed out. Please try again.',
        unauthorized: () =>
            'Your session has expired. Please sign in again.',
        forbidden:   () =>
            'You do not have permission to perform this action.',
        notFound:    (r) => '$r was not found.',
        validation:  (msg, _) =>
            msg.isNotEmpty ? msg : 'Please check your input and try again.',
        rateLimited: (retryAfter) => retryAfter != null
            ? 'Too many requests. Please wait $retryAfter seconds.'
            : 'Too many requests. Please wait a moment.',
        serverError: (_) =>
            'Something went wrong on our end. Please try again.',
        aiProvider:  (_) =>
            'The AI service is currently unavailable. Please try again.',
        websocketDisconnect: () =>
            'Connection lost. Attempting to reconnect…',
        unknown:     (_) =>
            'An unexpected error occurred. Please try again.',
      );
}
