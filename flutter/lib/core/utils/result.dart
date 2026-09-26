/// A simple Result<T> / Either type for explicit success/failure handling.
///
/// All repository methods return `Result<T>` so callers must handle both
/// cases — no silent swallowing of errors.
library;

import 'package:ai_assistant_flutter/core/error/app_error.dart';

sealed class Result<T> {
  const Result();

  bool get isSuccess => this is Success<T>;
  bool get isFailure => this is Failure<T>;

  T? get dataOrNull => switch (this) {
        Success<T>(data: final d) => d,
        Failure<T>() => null,
      };

  AppError? get errorOrNull => switch (this) {
        Success<T>() => null,
        Failure<T>(error: final e) => e,
      };

  /// Transform the success value; pass through failures unchanged.
  Result<R> map<R>(R Function(T data) transform) => switch (this) {
        Success<T>(data: final d) => Success(transform(d)),
        Failure<T>(error: final e) => Failure(e),
      };

  /// Run [onSuccess] or [onFailure] based on the state.
  R when<R>({
    required R Function(T data) onSuccess,
    required R Function(AppError error) onFailure,
  }) =>
      switch (this) {
        Success<T>(data: final d) => onSuccess(d),
        Failure<T>(error: final e) => onFailure(e),
      };
}

final class Success<T> extends Result<T> {
  const Success(this.data);
  final T data;
}

final class Failure<T> extends Result<T> {
  const Failure(this.error);
  final AppError error;
}
