/// Centralised, typed error model used throughout the application.
///
/// All errors are converted to [AppError] before reaching the UI layer.
/// The UI reads [AppError.userMessage] — never raw exception messages.
library;

import 'package:equatable/equatable.dart';

/// Category of error. Used to drive UI icon / copy decisions.
enum AppErrorType {
  /// Device has no internet connectivity.
  networkUnavailable,

  /// Request timed out.
  timeout,

  /// HTTP 401 — token missing, invalid, or expired.
  unauthorized,

  /// HTTP 403 — authenticated but insufficient role.
  forbidden,

  /// HTTP 404 — resource not found.
  notFound,

  /// HTTP 422 — request validation error.
  validation,

  /// HTTP 429 — rate limit or account locked.
  rateLimited,

  /// HTTP 5xx — server-side failure.
  serverError,

  /// WebSocket disconnected unexpectedly.
  websocketDisconnect,

  /// WebSocket message could not be parsed.
  websocketInvalidMessage,

  /// AI provider returned an error or is unavailable.
  aiProviderError,

  /// Unknown / unclassified error.
  unknown,
}

/// Immutable error value object.
class AppError extends Equatable implements Exception {
  const AppError({
    required this.type,
    required this.userMessage,
    this.technicalMessage,
    this.code,
    this.retryAfterSeconds,
  });

  final AppErrorType type;

  /// Safe, user-facing message. Never contains stack traces or internal details.
  final String userMessage;

  /// Internal detail — log this, never show it to the user.
  final String? technicalMessage;

  /// Machine-readable error code from the backend (e.g. 'RATE_LIMIT_EXCEEDED').
  final String? code;

  /// Populated for 429 responses that include Retry-After.
  final int? retryAfterSeconds;

  // ── Factory constructors ────────────────────────────────────────────────

  factory AppError.networkUnavailable() => const AppError(
        type: AppErrorType.networkUnavailable,
        userMessage: 'No internet connection. Please check your network.',
      );

  factory AppError.timeout() => const AppError(
        type: AppErrorType.timeout,
        userMessage: 'The request timed out. Please try again.',
      );

  factory AppError.unauthorized() => const AppError(
        type: AppErrorType.unauthorized,
        userMessage: 'Your session has expired. Please sign in again.',
      );

  factory AppError.forbidden() => const AppError(
        type: AppErrorType.forbidden,
        userMessage: 'You do not have permission to perform this action.',
      );

  factory AppError.notFound(String resource) => AppError(
        type: AppErrorType.notFound,
        userMessage: '$resource was not found.',
      );

  factory AppError.serverError([String? detail]) => AppError(
        type: AppErrorType.serverError,
        userMessage: 'Something went wrong on our end. Please try again.',
        technicalMessage: detail,
      );

  factory AppError.rateLimited({int? retryAfter}) => AppError(
        type: AppErrorType.rateLimited,
        userMessage: retryAfter != null
            ? 'Too many requests. Please wait $retryAfter seconds before retrying.'
            : 'Too many requests. Please wait a moment before retrying.',
        retryAfterSeconds: retryAfter,
      );

  factory AppError.aiProvider(String detail) => AppError(
        type: AppErrorType.aiProviderError,
        userMessage: 'The AI service is currently unavailable. Please try again.',
        technicalMessage: detail,
      );

  factory AppError.websocketDisconnect() => const AppError(
        type: AppErrorType.websocketDisconnect,
        userMessage: 'Connection lost. Attempting to reconnect…',
      );

  factory AppError.unknown([String? detail]) => AppError(
        type: AppErrorType.unknown,
        userMessage: 'An unexpected error occurred. Please try again.',
        technicalMessage: detail,
      );

  @override
  List<Object?> get props => [type, userMessage, code, retryAfterSeconds];

  @override
  String toString() =>
      'AppError(type: $type, message: $userMessage, code: $code)';
}
