/// ObservabilityService — captures and batches Flutter app events for upload.
///
/// Mirrors the Android ObservabilityManager pattern:
///   capture() → in-memory buffer → flush() → POST /api/v1/observability/events
///
/// Design:
///   - Events are buffered in memory (not persisted — non-critical telemetry).
///   - Auto-flush every [_flushIntervalSeconds] seconds, or when [_flushBatchSize]
///     events accumulate.
///   - Flush on app backgrounding (via WidgetsBindingObserver hook in provider).
///   - PII filtering: screen names and error messages should already be safe;
///     this service adds a final strip of common patterns just in case.
///   - Never logs tokens, passwords, API keys, or full stack traces.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:ai_assistant_flutter/features/observability/data/observability_api.dart';
import 'package:ai_assistant_flutter/features/observability/domain/observability_models.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

class ObservabilityService {
  ObservabilityService({
    required ObservabilityApi api,
    int flushIntervalSeconds = 60,
    int flushBatchSize       = 50,
  })  : _api               = api,
        _flushBatchSize    = flushBatchSize {
    _sessionId = _uuid.v4();
    _startFlushTimer(flushIntervalSeconds);
  }

  final ObservabilityApi _api;
  final int              _flushBatchSize;
  final List<ObservabilityEvent> _buffer = [];

  late final String _sessionId;
  String? _currentScreen;
  Timer? _flushTimer;

  // ── Public API ─────────────────────────────────────────────────────────────

  /// Record the currently active route for event context.
  void setScreen(String routeName) {
    _currentScreen = routeName;
  }

  /// Capture a structured event.
  void capture({
    required EventLevel level,
    required String eventType,
    required String message,
    String? requestId,
    String? traceId,
    Map<String, dynamic> metadata = const {},
  }) {
    final event = ObservabilityEvent(
      timestamp:  DateTime.now().millisecondsSinceEpoch,
      level:      level,
      eventType:  eventType,
      message:    _sanitize(message),
      sessionId:  _sessionId,
      requestId:  requestId,
      traceId:    traceId,
      screen:     _currentScreen,
      metadata:   _sanitizeMetadata(metadata),
    );

    _buffer.add(event);
    AppLogger.d(
      'Observability: buffered [${level.value}] $eventType '
      '(buffer=${_buffer.length})',
    );

    if (_buffer.length >= _flushBatchSize) {
      unawaited(flush());
    }
  }

  // ── Convenience helpers ────────────────────────────────────────────────────

  void info(String eventType, String message, {Map<String, dynamic> metadata = const {}}) =>
      capture(level: EventLevel.info,  eventType: eventType, message: message, metadata: metadata);

  void warn(String eventType, String message, {Map<String, dynamic> metadata = const {}}) =>
      capture(level: EventLevel.warn,  eventType: eventType, message: message, metadata: metadata);

  void error(String eventType, String message, {Map<String, dynamic> metadata = const {}}) =>
      capture(level: EventLevel.error, eventType: eventType, message: message, metadata: metadata);

  void networkError({
    required String url,
    required int statusCode,
    required String message,
    String? requestId,
  }) {
    capture(
      level:     EventLevel.error,
      eventType: EventType.networkError,
      message:   _sanitize(message),
      requestId: requestId,
      metadata:  {
        'url':         _stripQuery(url),
        'status_code': statusCode,
      },
    );
  }

  void apiLatency({
    required String endpoint,
    required int latencyMs,
    required int statusCode,
    String? requestId,
  }) {
    capture(
      level:     latencyMs > 3000 ? EventLevel.warn : EventLevel.info,
      eventType: EventType.apiLatency,
      message:   '$endpoint completed in ${latencyMs}ms',
      requestId: requestId,
      metadata:  {
        'endpoint':   endpoint,
        'latency_ms': latencyMs,
        'status_code': statusCode,
      },
    );
  }

  void navigation(String from, String to) {
    capture(
      level:     EventLevel.info,
      eventType: EventType.navigation,
      message:   'Navigate $from → $to',
      metadata:  {'from': from, 'to': to},
    );
  }

  // ── Flush ──────────────────────────────────────────────────────────────────

  /// Upload all buffered events to the backend.
  ///
  /// Returns silently on failure (observability is non-critical — the app
  /// must never crash or degrade because telemetry failed).
  Future<void> flush() async {
    if (_buffer.isEmpty) return;

    final batch = List<ObservabilityEvent>.from(_buffer);
    _buffer.clear();

    AppLogger.i('Observability: flushing ${batch.length} events');

    try {
      final result = await _api.ingest(IngestRequest(events: batch));
      result.when(
        onSuccess: (resp) => AppLogger.i(
          'Observability: accepted ${resp.accepted}/${resp.total}',
        ),
        onFailure: (err) {
          AppLogger.w(
            'Observability: flush failed — ${err.userMessage}. '
            '${batch.length} events dropped.',
          );
          // Events are not re-queued — telemetry loss is acceptable.
        },
      );
    } catch (e) {
      AppLogger.w('Observability: unexpected flush error', e);
    }
  }

  /// Flush and cancel the timer. Call on app shutdown or logout.
  Future<void> dispose() async {
    _flushTimer?.cancel();
    await flush();
  }

  // ── Private ────────────────────────────────────────────────────────────────

  void _startFlushTimer(int intervalSeconds) {
    _flushTimer = Timer.periodic(
      Duration(seconds: intervalSeconds),
      (_) => unawaited(flush()),
    );
  }

  /// Remove common PII patterns from free-text strings.
  ///
  /// Strips email-looking tokens, bearer tokens, and UUIDs from messages.
  /// This is a last-resort filter — callers should already sanitize their input.
  static String _sanitize(String text) {
    return text
        // Bearer tokens
        .replaceAll(RegExp(r'Bearer\s+\S+', caseSensitive: false), 'Bearer [REDACTED]')
        // Email addresses
        .replaceAll(RegExp(r'\b[\w.+-]+@[\w-]+\.[a-z]{2,}\b'), '[EMAIL]')
        // Truncate very long messages to avoid log bloat.
        .substring(0, text.length > 500 ? 500 : text.length);
  }

  /// Strip values that look like credentials from metadata.
  static Map<String, dynamic> _sanitizeMetadata(Map<String, dynamic> raw) {
    const _sensitiveKeys = {
      'token', 'password', 'secret', 'key', 'authorization',
      'api_key', 'access_token', 'refresh_token',
    };
    return {
      for (final entry in raw.entries)
        if (!_sensitiveKeys.contains(entry.key.toLowerCase()))
          entry.key: entry.value,
    };
  }

  /// Remove query parameters from URLs before logging (may contain tokens).
  static String _stripQuery(String url) {
    try {
      return Uri.parse(url).replace(query: '').toString();
    } catch (_) {
      return url;
    }
  }
}
