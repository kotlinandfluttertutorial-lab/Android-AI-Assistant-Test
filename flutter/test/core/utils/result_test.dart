import 'package:ai_assistant_flutter/core/error/app_error.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Result<T>', () {
    test('Success.isSuccess is true, isFailure is false', () {
      const result = Success(42);
      expect(result.isSuccess, isTrue);
      expect(result.isFailure, isFalse);
    });

    test('Failure.isFailure is true, isSuccess is false', () {
      final result = Failure<int>(AppError.unauthorized());
      expect(result.isFailure, isTrue);
      expect(result.isSuccess, isFalse);
    });

    test('Success.dataOrNull returns data', () {
      const result = Success('hello');
      expect(result.dataOrNull, 'hello');
    });

    test('Failure.dataOrNull returns null', () {
      final result = Failure<String>(AppError.timeout());
      expect(result.dataOrNull, isNull);
    });

    test('Failure.errorOrNull returns the error', () {
      final error  = AppError.networkUnavailable();
      final result = Failure<void>(error);
      expect(result.errorOrNull, error);
    });

    test('Success.map transforms value', () {
      const result = Success(2);
      final mapped = result.map((v) => v * 10);
      expect(mapped.dataOrNull, 20);
    });

    test('Failure.map passes error through', () {
      final error  = AppError.serverError();
      final result = Failure<int>(error);
      final mapped = result.map((v) => v * 10);
      expect(mapped.errorOrNull, error);
    });

    test('Success.when calls onSuccess', () {
      const result = Success(7);
      final out = result.when(
        onSuccess: (v) => 'ok:$v',
        onFailure: (_) => 'fail',
      );
      expect(out, 'ok:7');
    });

    test('Failure.when calls onFailure', () {
      final result = Failure<int>(AppError.forbidden());
      final out = result.when(
        onSuccess: (_) => 'ok',
        onFailure: (e) => 'fail:${e.type.name}',
      );
      expect(out, 'fail:forbidden');
    });
  });
}
