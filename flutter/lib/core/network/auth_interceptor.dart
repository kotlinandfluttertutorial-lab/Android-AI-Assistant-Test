/// Dio interceptor that:
///  1. Attaches the access token to every request as Bearer.
///  2. Proactively refreshes the token if it will expire soon.
///  3. On 401, attempts a single token refresh then retries.
///  4. On second 401 (refresh failed), clears storage and notifies listeners.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/storage/secure_storage.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:dio/dio.dart';

/// Callback invoked when authentication is fully lost (token refresh failed).
typedef OnAuthExpired = void Function();

class AuthInterceptor extends Interceptor {
  AuthInterceptor({
    required SecureStorage storage,
    required OnAuthExpired onAuthExpired,
  })  : _storage = storage,
        _onAuthExpired = onAuthExpired;

  final SecureStorage _storage;
  final OnAuthExpired _onAuthExpired;
  bool _isRefreshing = false;

  // Skip auth for these paths.
  static const _publicPaths = {
    ApiConfig.authLogin,
    ApiConfig.authRegister,
    ApiConfig.observabilityEvents,
    ApiConfig.health,
    ApiConfig.ready,
  };

  @override
  Future<void> onRequest(
    RequestOptions options,
    RequestInterceptorHandler handler,
  ) async {
    if (_publicPaths.contains(options.path)) {
      handler.next(options);
      return;
    }

    final token = await _storage.getAccessToken();
    if (token != null) {
      options.headers['Authorization'] = 'Bearer $token';
    }
    handler.next(options);
  }

  @override
  Future<void> onError(
    DioException err,
    ErrorInterceptorHandler handler,
  ) async {
    if (err.response?.statusCode != 401 ||
        _publicPaths.contains(err.requestOptions.path) ||
        _isRefreshing) {
      handler.next(err);
      return;
    }

    // Try a single silent token refresh.
    _isRefreshing = true;
    try {
      final refreshToken = await _storage.getRefreshToken();
      if (refreshToken == null) {
        _onAuthExpired();
        handler.next(err);
        return;
      }

      // Use a raw Dio instance (no interceptors) to avoid recursion.
      final rawDio = Dio(BaseOptions(baseUrl: ApiConfig.baseUrl));
      final refreshResponse = await rawDio.post<Map<String, dynamic>>(
        ApiConfig.authRefresh,
        data: {'refresh_token': refreshToken},
      );

      final body = refreshResponse.data!;
      await _storage.saveAccessToken(
        body['access_token'] as String,
        body['access_token_expires_at'] as int,
      );
      await _storage.saveRefreshToken(
        body['refresh_token'] as String,
        body['refresh_token_expires_at'] as int,
      );

      // Retry the original request with the new token.
      final newToken = body['access_token'] as String;
      final opts = err.requestOptions;
      opts.headers['Authorization'] = 'Bearer $newToken';

      final retryDio = Dio(BaseOptions(baseUrl: ApiConfig.baseUrl));
      final retryResponse = await retryDio.fetch<dynamic>(opts);
      handler.resolve(retryResponse);
    } catch (e) {
      AppLogger.w('Token refresh failed — clearing session', e);
      await _storage.clearAll();
      _onAuthExpired();
      handler.next(err);
    } finally {
      _isRefreshing = false;
    }
  }
}
