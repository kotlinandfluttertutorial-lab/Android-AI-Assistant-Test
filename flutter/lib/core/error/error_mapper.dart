/// Maps raw Dio / socket exceptions to typed [AppError] values.
///
/// All repository and service code calls [ErrorMapper.map] in their catch
/// blocks so the upper layers only ever see [AppError].
library;

import 'dart:io';

import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:dio/dio.dart';

class ErrorMapper {
  ErrorMapper._();

  /// Convert any exception into a typed [AppError].
  static AppError map(Object error, [StackTrace? stackTrace]) {
    if (error is AppError) return error;

    if (error is DioException) return _mapDio(error);

    if (error is SocketException) return AppError.networkUnavailable();

    return AppError.unknown(error.toString());
  }

  static AppError _mapDio(DioException e) {
    switch (e.type) {
      case DioExceptionType.connectionTimeout:
      case DioExceptionType.sendTimeout:
      case DioExceptionType.receiveTimeout:
        return AppError.timeout();

      case DioExceptionType.connectionError:
        return AppError.networkUnavailable();

      case DioExceptionType.badResponse:
        return _mapHttpResponse(e.response);

      case DioExceptionType.cancel:
        return const AppError(
          type: AppErrorType.unknown,
          userMessage: 'Request was cancelled.',
        );

      case DioExceptionType.unknown:
      case DioExceptionType.badCertificate:
        return AppError.unknown(e.message);
    }
  }

  static AppError _mapHttpResponse(Response<dynamic>? response) {
    if (response == null) return AppError.serverError();

    final status = response.statusCode ?? 0;
    final detail = _extractDetail(response.data);
    final code = _extractCode(response.data);
    final retryAfter = _extractRetryAfter(response);

    return switch (status) {
      401 => AppError.unauthorized(),
      403 => AppError.forbidden(),
      404 => AppError.notFound('The requested resource'),
      422 => AppError(
          type: AppErrorType.validation,
          userMessage: detail ?? 'Please check your input and try again.',
          code: code,
        ),
      429 => AppError.rateLimited(retryAfter: retryAfter),
      503 => AppError.aiProvider(detail ?? 'Service unavailable'),
      >= 500 => AppError.serverError(detail),
      _ => AppError.unknown('HTTP $status: ${detail ?? 'Unknown error'}'),
    };
  }

  /// Pulls the human-readable message out of the backend error envelope.
  ///
  /// Handles both:
  ///   {"detail": "message"}
  ///   {"detail": {"error": {"code": "...", "message": "..."}}}
  static String? _extractDetail(dynamic data) {
    if (data == null) return null;
    if (data is Map) {
      final detail = data['detail'];
      if (detail is String) return detail;
      if (detail is Map) {
        final error = detail['error'];
        if (error is Map) {
          return error['message'] as String?;
        }
      }
    }
    return null;
  }

  static String? _extractCode(dynamic data) {
    if (data is Map) {
      final detail = data['detail'];
      if (detail is Map) {
        final error = detail['error'];
        if (error is Map) return error['code'] as String?;
      }
    }
    return null;
  }

  static int? _extractRetryAfter(Response<dynamic> response) {
    final header = response.headers.value('Retry-After');
    if (header == null) return null;
    return int.tryParse(header);
  }
}
