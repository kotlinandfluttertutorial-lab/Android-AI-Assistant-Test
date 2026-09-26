import 'package:ai_assistant_flutter/features/incidents/domain/incident_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // ── IncidentSeverity ───────────────────────────────────────────────────────

  group('IncidentSeverity.parse', () {
    test('parses CRITICAL case-insensitively', () {
      expect(IncidentSeverity.parse('CRITICAL'), IncidentSeverity.critical);
      expect(IncidentSeverity.parse('critical'), IncidentSeverity.critical);
    });

    test('parses HIGH', () {
      expect(IncidentSeverity.parse('HIGH'), IncidentSeverity.high);
    });

    test('parses MEDIUM', () {
      expect(IncidentSeverity.parse('MEDIUM'), IncidentSeverity.medium);
    });

    test('falls back to low for unknown value', () {
      expect(IncidentSeverity.parse('UNKNOWN'), IncidentSeverity.low);
      expect(IncidentSeverity.parse(''), IncidentSeverity.low);
    });

    test('label returns uppercase name', () {
      expect(IncidentSeverity.critical.label, 'CRITICAL');
      expect(IncidentSeverity.medium.label,   'MEDIUM');
    });
  });

  // ── IncidentStatus ─────────────────────────────────────────────────────────

  group('IncidentStatus.parse', () {
    test('parses OPEN as default', () {
      expect(IncidentStatus.parse('OPEN'),     IncidentStatus.open);
      expect(IncidentStatus.parse('anything'), IncidentStatus.open);
    });

    test('parses INVESTIGATING', () {
      expect(IncidentStatus.parse('INVESTIGATING'),
          IncidentStatus.investigating);
    });

    test('parses RESOLVED', () {
      expect(IncidentStatus.parse('RESOLVED'), IncidentStatus.resolved);
    });

    test('parses DISMISSED', () {
      expect(IncidentStatus.parse('DISMISSED'), IncidentStatus.dismissed);
    });
  });

  // ── Incident.fromJson ──────────────────────────────────────────────────────

  group('Incident.fromJson', () {
    test('parses a minimal incident', () {
      final json = {
        'id':          'inc-001',
        'title':       'DB Connection Failure',
        'severity':    'CRITICAL',
        'status':      'OPEN',
        'detected_at': '2025-01-15T14:32:00',
        'event_count': 47,
      };
      final inc = Incident.fromJson(json);

      expect(inc.id,         'inc-001');
      expect(inc.title,      'DB Connection Failure');
      expect(inc.severity,   IncidentSeverity.critical);
      expect(inc.status,     IncidentStatus.open);
      expect(inc.eventCount, 47);
      expect(inc.isOpen,     isTrue);
    });

    test('parses optional AI fields', () {
      final json = {
        'id':                  'inc-002',
        'title':               'Memory pressure',
        'severity':            'MEDIUM',
        'status':              'INVESTIGATING',
        'detected_at':         '2025-01-15T14:00:00',
        'event_count':         12,
        'ai_summary':          'GC pauses detected',
        'ai_confidence':       0.72,
        'ai_recommended_fix':  'Increase heap size',
      };
      final inc = Incident.fromJson(json);

      expect(inc.hasAiAnalysis,    isTrue);
      expect(inc.aiConfidence,     closeTo(0.72, 0.001));
      expect(inc.aiRecommendedFix, 'Increase heap size');
    });

    test('defaults missing numeric fields to 0', () {
      final json = {
        'id':          'inc-003',
        'title':       'Test',
        'severity':    'LOW',
        'status':      'OPEN',
        'detected_at': '2025-01-01T00:00:00',
      };
      final inc = Incident.fromJson(json);
      expect(inc.eventCount,    0);
      expect(inc.windowMinutes, 0);
    });

    test('isOpen is false for RESOLVED status', () {
      final json = {
        'id':          'inc-004',
        'title':       'Resolved',
        'severity':    'LOW',
        'status':      'RESOLVED',
        'detected_at': '2025-01-01T00:00:00',
        'event_count': 3,
      };
      final inc = Incident.fromJson(json);
      expect(inc.isOpen, isFalse);
    });
  });

  // ── IncidentListResponse.fromJson ──────────────────────────────────────────

  group('IncidentListResponse.fromJson', () {
    test('parses list of incidents correctly', () {
      final json = {
        'incidents': [
          {
            'id':          'a',
            'title':       'First',
            'severity':    'HIGH',
            'status':      'OPEN',
            'detected_at': '2025-01-01T00:00:00',
            'event_count': 5,
          },
          {
            'id':          'b',
            'title':       'Second',
            'severity':    'LOW',
            'status':      'RESOLVED',
            'detected_at': '2025-01-01T01:00:00',
            'event_count': 1,
          },
        ],
        'total':      2,
        'open_count': 1,
      };
      final response = IncidentListResponse.fromJson(json);

      expect(response.incidents.length, 2);
      expect(response.total,            2);
      expect(response.openCount,        1);
      expect(response.incidents.first.id, 'a');
    });

    test('handles empty incidents list gracefully', () {
      final json = {'incidents': [], 'total': 0, 'open_count': 0};
      final response = IncidentListResponse.fromJson(json);
      expect(response.incidents, isEmpty);
    });
  });

  // ── RootCauseCandidate.fromJson ────────────────────────────────────────────

  group('RootCauseCandidate.fromJson', () {
    test('parses all fields', () {
      final json = {
        'rank':                 1,
        'cause':                'Connection pool exhausted',
        'confidence':           0.87,
        'supporting_evidence':  ['Pool at 20/20', 'Latency +340%'],
        'reasoning':            'High connection wait time correlates with pool saturation.',
      };
      final candidate = RootCauseCandidate.fromJson(json);

      expect(candidate.rank,                     1);
      expect(candidate.cause,                    'Connection pool exhausted');
      expect(candidate.confidence,               closeTo(0.87, 0.001));
      expect(candidate.supportingEvidence.length, 2);
      expect(candidate.reasoning,                isNotEmpty);
    });

    test('defaults to empty evidence list when missing', () {
      final json = {
        'rank':       2,
        'cause':      'Slow query',
        'confidence': 0.45,
      };
      final candidate = RootCauseCandidate.fromJson(json);
      expect(candidate.supportingEvidence, isEmpty);
    });
  });

  // ── RcaAnalysisResponse.fromJson ───────────────────────────────────────────

  group('RcaAnalysisResponse.fromJson', () {
    test('parses full RCA response', () {
      final json = {
        'rca_id':            'rca-001',
        'incident_id':       'inc-001',
        'summary':           'Connection pool exhausted due to slow queries.',
        'overall_confidence': 0.87,
        'root_cause_candidates': [
          {
            'rank': 1, 'cause': 'Pool exhaustion',
            'confidence': 0.87, 'supporting_evidence': [], 'reasoning': '',
          },
        ],
        'timeline':             [],
        'investigation_steps':  ['Check pool size', 'Review slow query log'],
        'related_documentation': ['runbook-db-connections.md'],
        'low_confidence_warning': null,
        'llm_provider':          'gemini',
      };
      final rca = RcaAnalysisResponse.fromJson(json);

      expect(rca.rcaId,                      'rca-001');
      expect(rca.overallConfidence,          closeTo(0.87, 0.001));
      expect(rca.rootCauseCandidates.length, 1);
      expect(rca.investigationSteps.length,  2);
      expect(rca.hasLowConfidence,           isFalse);
    });

    test('hasLowConfidence is true when warning is set', () {
      final json = {
        'rca_id':             'rca-002',
        'incident_id':        'inc-002',
        'summary':            'Insufficient evidence.',
        'overall_confidence':  0.45,
        'low_confidence_warning':
            'Evidence is insufficient — manual investigation required.',
      };
      final rca = RcaAnalysisResponse.fromJson(json);
      expect(rca.hasLowConfidence, isTrue);
      expect(rca.lowConfidenceWarning, isNotNull);
    });
  });

  // ── RemediationAction.fromJson ─────────────────────────────────────────────

  group('RemediationAction.fromJson', () {
    test('parses a recommended action', () {
      final json = {
        'id':           'act-001',
        'incident_id':  'inc-001',
        'title':        'Increase connection pool size',
        'action_type':  'config_change',
        'risk_tier':    'MEDIUM',
        'reasoning':    'Pool saturation is the most likely cause.',
        'rank':         1,
        'status':       'RECOMMENDED',
        'confidence':   0.82,
        'params':       {'pool_size': 20},
        'created_at':   '2025-01-15T15:00:00',
      };
      final action = RemediationAction.fromJson(json);

      expect(action.id,         'act-001');
      expect(action.isPending,  isTrue);
      expect(action.isApproved, isFalse);
      expect(action.isRejected, isFalse);
      expect(action.isHighRisk, isFalse);
      expect(action.params['pool_size'], 20);
    });

    test('isHighRisk is true for HIGH risk tier', () {
      final json = {
        'id':          'act-002',
        'incident_id': 'inc-001',
        'title':       'Rollback deployment',
        'action_type': 'rollback',
        'risk_tier':   'HIGH',
        'reasoning':   'Deployment correlates with incident start.',
        'rank':        1,
        'status':      'RECOMMENDED',
        'created_at':  '2025-01-15T15:00:00',
      };
      final action = RemediationAction.fromJson(json);
      expect(action.isHighRisk, isTrue);
    });

    test('isApproved and isRejected reflect status correctly', () {
      final base = {
        'id':          'act-003',
        'incident_id': 'inc-001',
        'title':       'Restart service',
        'action_type': 'restart',
        'risk_tier':   'LOW',
        'reasoning':   '',
        'rank':        1,
        'created_at':  '2025-01-15T15:00:00',
      };

      final approved = RemediationAction.fromJson({...base, 'status': 'APPROVED'});
      final rejected = RemediationAction.fromJson({...base, 'status': 'REJECTED'});

      expect(approved.isApproved, isTrue);
      expect(approved.isPending,  isFalse);
      expect(rejected.isRejected, isTrue);
      expect(rejected.isPending,  isFalse);
    });
  });
}
