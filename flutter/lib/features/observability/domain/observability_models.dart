/// Domain models for the Observability feature.
///
/// Mirrors the backend [ObservabilityEventPayload] schema exactly.
/// Fields use camelCase to match the Kotlin serialization format that the
/// backend expects (it was designed to receive Android events).
///
/// POST /api/v1/observability/events  — no auth required.
///
/// Phase 2 — Android Observability (Flutter equivalent)
library;

import 'package:equatable/equatable.dart';

// ── Event level ───────────────────────────────────────────────────────────────

enum EventLevel {
  debug,
  info,
  warn,
  error,
  critical;

  String get value => name.toUpperCase();
}

// ── Event type constants ───────────────────────────────────────────────────────

/// Machine-readable event categories understood by the backend AI pipeline.
class EventType {
  EventType._();

  static const String networkError   = 'network_error';
  static const String apiLatency     = 'api_latency';
  static const String httpError      = 'http_error';
  static const String appLifecycle   = 'app_lifecycle';
  static const String userError      = 'user_error';
  static const String handledException = 'handled_exception';
  static const String crash          = 'crash';
  static const String navigation     = 'navigation';
  static const String performance    = 'performance';
}

// ── Event model ───────────────────────────────────────────────────────────────

/// A single observability event captured on the Flutter client.
class ObservabilityEvent extends Equatable {
  const ObservabilityEvent({
    required this.timestamp,
    required this.level,
    required this.eventType,
    required this.message,
    required this.sessionId,
    this.requestId,
    this.traceId,
    this.screen,
    this.metadata = const {},
  });

  /// Epoch milliseconds (UTC).
  final int timestamp;
  final EventLevel level;

  /// Machine-readable event category (see [EventType]).
  final String eventType;

  /// PII-filtered human-readable description.
  final String message;
  final String sessionId;
  final String? requestId;
  final String? traceId;

  /// Active route/screen at time of event.
  final String? screen;

  /// Arbitrary key-value context (no PII).
  final Map<String, dynamic> metadata;

  /// Serialise to the camelCase JSON format the backend expects.
  Map<String, dynamic> toJson() => {
        'timestamp':  timestamp,
        'level':      level.value,
        'eventType':  eventType,
        'message':    message,
        'sessionId':  sessionId,
        if (requestId != null) 'requestId': requestId,
        if (traceId != null)   'traceId':   traceId,
        if (screen != null)    'screen':    screen,
        'metadata':   metadata,
      };

  @override
  List<Object?> get props =>
      [timestamp, level, eventType, message, sessionId];
}

// ── Batch request / response ─────────────────────────────────────────────────

class IngestRequest {
  const IngestRequest({required this.events});
  final List<ObservabilityEvent> events;

  Map<String, dynamic> toJson() => {
        'events': events.map((e) => e.toJson()).toList(),
      };
}

class IngestResponse {
  const IngestResponse({
    required this.accepted,
    required this.total,
  });

  factory IngestResponse.fromJson(Map<String, dynamic> json) =>
      IngestResponse(
        accepted: (json['accepted'] as int?) ?? 0,
        total:    (json['total'] as int?) ?? 0,
      );

  final int accepted;
  final int total;
}
