import 'package:ai_assistant_flutter/features/observability/domain/observability_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('EventLevel', () {
    test('value returns uppercase name', () {
      expect(EventLevel.debug.value,    'DEBUG');
      expect(EventLevel.info.value,     'INFO');
      expect(EventLevel.warn.value,     'WARN');
      expect(EventLevel.error.value,    'ERROR');
      expect(EventLevel.critical.value, 'CRITICAL');
    });
  });

  group('ObservabilityEvent.toJson', () {
    test('serialises required fields in camelCase', () {
      const event = ObservabilityEvent(
        timestamp: 1_700_000_000_000,
        level:     EventLevel.error,
        eventType: 'network_error',
        message:   'Connection refused',
        sessionId: 'sess-abc',
      );
      final json = event.toJson();

      expect(json['timestamp'], 1_700_000_000_000);
      expect(json['level'],     'ERROR');
      expect(json['eventType'], 'network_error');
      expect(json['message'],   'Connection refused');
      expect(json['sessionId'], 'sess-abc');
    });

    test('omits null optional fields', () {
      const event = ObservabilityEvent(
        timestamp: 1_000,
        level:     EventLevel.info,
        eventType: 'app_lifecycle',
        message:   'resumed',
        sessionId: 'sess-1',
      );
      final json = event.toJson();

      expect(json.containsKey('requestId'), isFalse);
      expect(json.containsKey('traceId'),   isFalse);
      expect(json.containsKey('screen'),    isFalse);
    });

    test('includes optional fields when set', () {
      const event = ObservabilityEvent(
        timestamp: 2_000,
        level:     EventLevel.warn,
        eventType: 'api_latency',
        message:   'Slow response',
        sessionId: 'sess-2',
        requestId: 'req-001',
        traceId:   'trace-abc',
        screen:    '/chat/new',
        metadata:  {'latency_ms': 4200},
      );
      final json = event.toJson();

      expect(json['requestId'],            'req-001');
      expect(json['traceId'],              'trace-abc');
      expect(json['screen'],               '/chat/new');
      expect(json['metadata']['latency_ms'], 4200);
    });
  });

  group('IngestRequest.toJson', () {
    test('wraps events under "events" key', () {
      final req = IngestRequest(
        events: [
          const ObservabilityEvent(
            timestamp: 1_000,
            level:     EventLevel.info,
            eventType: 'test',
            message:   'hello',
            sessionId: 's1',
          ),
        ],
      );
      final json = req.toJson();

      expect(json['events'], isList);
      expect((json['events'] as List).length, 1);
    });

    test('empty events list is valid', () {
      final req = IngestRequest(events: []);
      expect(req.toJson()['events'], isEmpty);
    });
  });

  group('IngestResponse.fromJson', () {
    test('parses accepted and total', () {
      final resp = IngestResponse.fromJson({'accepted': 47, 'total': 50});
      expect(resp.accepted, 47);
      expect(resp.total,    50);
    });

    test('defaults to 0 for missing fields', () {
      final resp = IngestResponse.fromJson({});
      expect(resp.accepted, 0);
      expect(resp.total,    0);
    });
  });
}
