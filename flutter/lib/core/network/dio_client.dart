/// Dio HTTP client factory with auth interceptor, logging, and error handling.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/constants/app_constants.dart';
import 'package:ai_assistant_flutter/core/network/auth_interceptor.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:dio/dio.dart';

/// Creates and configures the shared Dio instance.
Dio createDio(AuthInterceptor authInterceptor) {
  final dio = Dio(
    BaseOptions(
      baseUrl: ApiConfig.baseUrl,
      connectTimeout: AppConstants.connectTimeout,
      receiveTimeout: AppConstants.receiveTimeout,
      sendTimeout: AppConstants.sendTimeout,
      headers: {
        'Accept': 'application/json',
        'Content-Type': 'application/json',
      },
    ),
  );

  dio.interceptors.addAll([
    authInterceptor,
    _LoggingInterceptor(),
  ]);

  return dio;
}

/// Logs request/response details at DEBUG level.
///
/// Sanitizes Authorization headers to prevent token leaks in logs.
class _LoggingInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    AppLogger.d(
      '→ ${options.method} ${options.path}',
      {
        'query': options.queryParameters,
        'headers': _sanitizeHeaders(options.headers),
      },
    );
    handler.next(options);
  }

  @override
  void onResponse(
    Response<dynamic> response,
    ResponseInterceptorHandler handler,
  ) {
    AppLogger.d(
      '← ${response.statusCode} ${response.requestOptions.path}',
    );
    handler.next(response);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    AppLogger.w(
      '✕ ${err.requestOptions.method} ${err.requestOptions.path} — ${err.message}',
      err,
    );
    handler.next(err);
  }

  Map<String, dynamic> _sanitizeHeaders(Map<String, dynamic> headers) {
    final copy = Map<String, dynamic>.from(headers);
    if (copy.containsKey('Authorization')) {
      copy['Authorization'] = 'Bearer [REDACTED]';
    }
    return copy;
  }
}
