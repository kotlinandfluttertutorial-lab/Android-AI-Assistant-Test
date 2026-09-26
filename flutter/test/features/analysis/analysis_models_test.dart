import 'package:ai_assistant_flutter/features/analysis/domain/analysis_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AnalysisSeverity.parse', () {
    test('parses all known values', () {
      expect(AnalysisSeverity.parse('CRITICAL'), AnalysisSeverity.critical);
      expect(AnalysisSeverity.parse('HIGH'),     AnalysisSeverity.high);
      expect(AnalysisSeverity.parse('MEDIUM'),   AnalysisSeverity.medium);
      expect(AnalysisSeverity.parse('LOW'),      AnalysisSeverity.low);
    });

    test('is case-insensitive', () {
      expect(AnalysisSeverity.parse('critical'), AnalysisSeverity.critical);
      expect(AnalysisSeverity.parse('High'),     AnalysisSeverity.high);
    });

    test('defaults to low for unknown value', () {
      expect(AnalysisSeverity.parse('UNKNOWN'), AnalysisSeverity.low);
    });
  });

  group('FactsVsInference.fromJson', () {
    test('parses facts and inferences', () {
      final json = {
        'facts':      ['DB pool at capacity', 'Latency +340%'],
        'inferences': ['Likely caused by slow query'],
      };
      final fvi = FactsVsInference.fromJson(json);
      expect(fvi.facts.length,      2);
      expect(fvi.inferences.length, 1);
    });

    test('handles empty lists', () {
      final fvi = FactsVsInference.fromJson({'facts': [], 'inferences': []});
      expect(fvi.facts,      isEmpty);
      expect(fvi.inferences, isEmpty);
    });

    test('handles missing keys gracefully', () {
      final fvi = FactsVsInference.fromJson({});
      expect(fvi.facts,      isEmpty);
      expect(fvi.inferences, isEmpty);
    });
  });

  group('ErrorAnalysisResponse.fromJson', () {
    final fullJson = {
      'analysis_id':    'ana-001',
      'severity':       'HIGH',
      'summary':        'DB connection pool exhausted',
      'likely_root_cause': 'Slow queries blocking connections',
      'confidence':     0.87,
      'recommended_fix': '1. Increase pool size\n2. Add query timeout',
      'evidence':       ['Pool at 20/20 at 14:32', 'Latency +340%'],
      'possible_causes': ['Slow query', 'Connection leak'],
      'related_documentation': ['runbook-db.md'],
      'facts_vs_inference': {
        'facts':      ['Pool at 20/20'],
        'inferences': ['Likely slow query'],
      },
      'low_confidence_warning': null,
      'events_analysed':        12,
      'knowledge_chunks_retrieved': 5,
      'llm_provider':           'gemini',
    };

    test('parses all fields correctly', () {
      final r = ErrorAnalysisResponse.fromJson(fullJson);

      expect(r.analysisId,               'ana-001');
      expect(r.severity,                 AnalysisSeverity.high);
      expect(r.summary,                  isNotEmpty);
      expect(r.confidence,               closeTo(0.87, 0.001));
      expect(r.evidence.length,          2);
      expect(r.possibleCauses.length,    2);
      expect(r.relatedDocumentation,     ['runbook-db.md']);
      expect(r.factsVsInference.facts.length, 1);
      expect(r.eventsAnalysed,           12);
      expect(r.knowledgeChunksRetrieved, 5);
      expect(r.llmProvider,              'gemini');
      expect(r.hasLowConfidence,         isFalse);
    });

    test('confidencePct converts to integer percentage', () {
      final r = ErrorAnalysisResponse.fromJson(fullJson);
      expect(r.confidencePct, 87);
    });

    test('hasLowConfidence is true when warning is set', () {
      final json = Map<String, dynamic>.from(fullJson)
        ..['low_confidence_warning'] =
            'Evidence insufficient — manual investigation required.'
        ..['confidence'] = 0.45;
      final r = ErrorAnalysisResponse.fromJson(json);
      expect(r.hasLowConfidence, isTrue);
    });

    test('defaults missing optional fields to safe values', () {
      final r = ErrorAnalysisResponse.fromJson({
        'analysis_id':       'x',
        'severity':          'LOW',
        'summary':           'test',
        'likely_root_cause': 'unknown',
        'confidence':        0.5,
        'recommended_fix':   'none',
      });
      expect(r.evidence,             isEmpty);
      expect(r.possibleCauses,       isEmpty);
      expect(r.eventsAnalysed,       0);
      expect(r.llmProvider,          '');
    });
  });

  group('AnalyseErrorRequest.toJson', () {
    test('serialises lookback_minutes', () {
      const req = AnalyseErrorRequest(lookbackMinutes: 60);
      final json = req.toJson();
      expect(json['lookback_minutes'], 60);
    });

    test('includes event_id when set', () {
      const req = AnalyseErrorRequest(eventId: 'evt-1');
      expect(req.toJson()['event_id'], 'evt-1');
    });

    test('includes session_id when set', () {
      const req = AnalyseErrorRequest(sessionId: 'sess-1');
      expect(req.toJson()['session_id'], 'sess-1');
    });

    test('omits optional fields when null', () {
      const req = AnalyseErrorRequest();
      final json = req.toJson();
      expect(json.containsKey('event_id'),   isFalse);
      expect(json.containsKey('session_id'), isFalse);
      expect(json.containsKey('provider'),   isFalse);
    });

    test('includes provider when set', () {
      const req = AnalyseErrorRequest(provider: 'openai');
      expect(req.toJson()['provider'], 'openai');
    });
  });
}
