/// Centralised logging abstraction.
///
/// Usage:
///   AppLogger.d('message');   // DEBUG
///   AppLogger.i('message');   // INFO
///   AppLogger.w('message');   // WARNING
///   AppLogger.e('message', error, stackTrace);  // ERROR
///
/// In production, only WARN and ERROR levels are emitted.
/// Tokens, passwords, and API keys must NEVER be passed to this logger.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:logger/logger.dart';

class AppLogger {
  AppLogger._();

  static final Logger _logger = Logger(
    level: currentEnvironment == AppEnvironment.production
        ? Level.warning
        : Level.debug,
    printer: PrettyPrinter(
      methodCount: 0,
      errorMethodCount: 8,
      lineLength: 100,
      colors: true,
      printEmojis: true,
    ),
  );

  static void d(String message, [dynamic data]) =>
      _logger.d(data != null ? '$message\n$data' : message);

  static void i(String message, [dynamic data]) =>
      _logger.i(data != null ? '$message\n$data' : message);

  static void w(String message, [Object? error]) =>
      _logger.w(message, error: error);

  static void e(
    String message, [
    Object? error,
    StackTrace? stackTrace,
  ]) =>
      _logger.e(message, error: error, stackTrace: stackTrace);
}
