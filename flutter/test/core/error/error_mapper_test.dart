import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:dio/dio.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ErrorMapper', () {
    test('maps DioExceptionType.connectionTimeout to AppErrorType.timeout', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.connectionTimeout,
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.timeout);
    });

    test('maps DioExceptionType.connectionError to networkUnavailable', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.connectionError,
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.networkUnavailable);
    });

    test('maps 401 response to unauthorized', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/test'),
          statusCode: 401,
          data: {'detail': 'Unauthorized'},
        ),
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.unauthorized);
    });

    test('maps 403 response to forbidden', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/test'),
          statusCode: 403,
          data: {'detail': 'Forbidden'},
        ),
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.forbidden);
    });

    test('maps 429 response to rateLimited', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/test'),
          statusCode: 429,
          headers: Headers.fromMap({'Retry-After': ['30']}),
          data: {'detail': 'Too many requests'},
        ),
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.rateLimited);
      expect(result.retryAfterSeconds, 30);
    });

    test('maps 500 response to serverError', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/test'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/test'),
          statusCode: 500,
          data: {'detail': 'Internal server error'},
        ),
      );
      final result = ErrorMapper.map(err);
      expect(result.type, AppErrorType.serverError);
    });

    test('extracts structured error code from backend envelope', () {
      final err = DioException(
        requestOptions: RequestOptions(path: '/chat'),
        type: DioExceptionType.badResponse,
        response: Response(
          requestOptions: RequestOptions(path: '/chat'),
          statusCode: 400,
          data: {
            'detail': {
              'error': {
                'code': 'PROMPT_INJECTION_DETECTED',
                'message': 'Injection detected',
              },
            },
          },
        ),
      );
      final result = ErrorMapper.map(err);
      expect(result.code, 'PROMPT_INJECTION_DETECTED');
    });

    test('passes through existing AppError unchanged', () {
      final appError = AppError.unauthorized();
      final result = ErrorMapper.map(appError);
      expect(result, same(appError));
    });

    test('maps unknown exception to AppErrorType.unknown', () {
      final result = ErrorMapper.map(Exception('weird error'));
      expect(result.type, AppErrorType.unknown);
    });
  });
}
