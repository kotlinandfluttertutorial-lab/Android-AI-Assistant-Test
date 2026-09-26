/// Domain models for the Incidents feature.
///
/// Mirrors the backend schemas:
///   GET  /incidents                -> IncidentListResponse
///   GET  /incidents/{id}           -> Incident
///   POST /incidents/{id}/rca       -> RcaAnalysisResponse
///   POST /incidents/{id}/remediation/recommend -> RemediationPlanResponse
///
/// Phase 11 / 12 / 15 — Anomaly Detection, RCA, AIOps
library;

import 'package:equatable/equatable.dart';

// ── Enums ────────────────────────────────────────────────────────────────────

enum IncidentSeverity {
  critical, high, medium, low;

  static IncidentSeverity parse(String v) => switch (v.toUpperCase()) {
        'CRITICAL' => IncidentSeverity.critical,
        'HIGH'     => IncidentSeverity.high,
        'MEDIUM'   => IncidentSeverity.medium,
        _          => IncidentSeverity.low,
      };

  String get label => name.toUpperCase();
}

enum IncidentStatus {
  open, investigating, resolved, dismissed;

  static IncidentStatus parse(String v) => switch (v.toUpperCase()) {
        'INVESTIGATING' => IncidentStatus.investigating,
        'RESOLVED'      => IncidentStatus.resolved,
        'DISMISSED'     => IncidentStatus.dismissed,
        _               => IncidentStatus.open,
      };

  String get label => name.toUpperCase();
}

// ── Incident ─────────────────────────────────────────────────────────────────

class Incident extends Equatable {
  const Incident({
    required this.id,
    required this.title,
    required this.severity,
    required this.status,
    required this.detectedAt,
    required this.eventCount,
    this.detectionMethod = '',
    this.triggeredBy = '',
    this.metricValue,
    this.thresholdValue,
    this.analysisId,
    this.aiSummary,
    this.aiConfidence,
    this.aiRecommendedFix,
    this.windowMinutes = 0,
    this.resolvedAt,
  });

  factory Incident.fromJson(Map<String, dynamic> json) => Incident(
        id:               json['id'] as String,
        title:            json['title'] as String,
        severity:         IncidentSeverity.parse(json['severity'] as String),
        status:           IncidentStatus.parse(json['status'] as String),
        detectedAt:       json['detected_at'] as String,
        eventCount:       (json['event_count'] as int?) ?? 0,
        detectionMethod:  (json['detection_method'] as String?) ?? '',
        triggeredBy:      (json['triggered_by'] as String?) ?? '',
        metricValue:      (json['metric_value'] as num?)?.toDouble(),
        thresholdValue:   (json['threshold_value'] as num?)?.toDouble(),
        analysisId:       json['analysis_id'] as String?,
        aiSummary:        json['ai_summary'] as String?,
        aiConfidence:     (json['ai_confidence'] as num?)?.toDouble(),
        aiRecommendedFix: json['ai_recommended_fix'] as String?,
        windowMinutes:    (json['window_minutes'] as int?) ?? 0,
        resolvedAt:       json['resolved_at'] as String?,
      );

  final String id;
  final String title;
  final IncidentSeverity severity;
  final IncidentStatus status;
  final String detectedAt;
  final int eventCount;
  final String detectionMethod;
  final String triggeredBy;
  final double? metricValue;
  final double? thresholdValue;
  final String? analysisId;
  final String? aiSummary;
  final double? aiConfidence;
  final String? aiRecommendedFix;
  final int windowMinutes;
  final String? resolvedAt;

  bool get isOpen => status == IncidentStatus.open;
  bool get hasAiAnalysis => aiSummary != null && aiSummary!.isNotEmpty;

  @override
  List<Object?> get props =>
      [id, title, severity, status, detectedAt, eventCount];
}

// ── List response ─────────────────────────────────────────────────────────────

class IncidentListResponse {
  const IncidentListResponse({
    required this.incidents,
    required this.total,
    required this.openCount,
  });

  factory IncidentListResponse.fromJson(Map<String, dynamic> json) {
    final raw = json['incidents'] as List<dynamic>? ?? [];
    return IncidentListResponse(
      incidents: raw
          .map((e) => Incident.fromJson(e as Map<String, dynamic>))
          .toList(),
      total:     (json['total'] as int?) ?? 0,
      openCount: (json['open_count'] as int?) ?? 0,
    );
  }

  final List<Incident> incidents;
  final int total;
  final int openCount;
}

// ── RCA models ────────────────────────────────────────────────────────────────

class RootCauseCandidate extends Equatable {
  const RootCauseCandidate({
    required this.rank,
    required this.cause,
    required this.confidence,
    this.supportingEvidence = const [],
    this.reasoning = '',
  });

  factory RootCauseCandidate.fromJson(Map<String, dynamic> json) =>
      RootCauseCandidate(
        rank:               (json['rank'] as int?) ?? 1,
        cause:              (json['cause'] as String?) ?? '',
        confidence:         ((json['confidence'] as num?) ?? 0).toDouble(),
        supportingEvidence: List<String>.from(
            json['supporting_evidence'] as List<dynamic>? ?? []),
        reasoning:          (json['reasoning'] as String?) ?? '',
      );

  final int rank;
  final String cause;
  final double confidence;
  final List<String> supportingEvidence;
  final String reasoning;

  @override
  List<Object?> get props => [rank, cause, confidence];
}

class TimelineEvent extends Equatable {
  const TimelineEvent({
    required this.timestamp,
    required this.source,
    required this.level,
    required this.message,
    this.eventType = '',
    this.screen,
  });

  factory TimelineEvent.fromJson(Map<String, dynamic> json) => TimelineEvent(
        timestamp: (json['timestamp'] as String?) ?? '',
        source:    (json['source'] as String?) ?? '',
        level:     (json['level'] as String?) ?? 'INFO',
        message:   (json['message'] as String?) ?? '',
        eventType: (json['event_type'] as String?) ?? '',
        screen:    json['screen'] as String?,
      );

  final String timestamp;
  final String source;
  final String level;
  final String message;
  final String eventType;
  final String? screen;

  @override
  List<Object?> get props => [timestamp, source, message];
}

class RcaAnalysisResponse extends Equatable {
  const RcaAnalysisResponse({
    required this.rcaId,
    required this.incidentId,
    required this.summary,
    required this.overallConfidence,
    this.rootCauseCandidates = const [],
    this.timeline = const [],
    this.chainOfThought = '',
    this.investigationSteps = const [],
    this.relatedDocumentation = const [],
    this.lowConfidenceWarning,
    this.llmProvider = '',
  });

  factory RcaAnalysisResponse.fromJson(Map<String, dynamic> json) =>
      RcaAnalysisResponse(
        rcaId:             (json['rca_id'] as String?) ?? '',
        incidentId:        (json['incident_id'] as String?) ?? '',
        summary:           (json['summary'] as String?) ?? '',
        overallConfidence: ((json['overall_confidence'] as num?) ?? 0).toDouble(),
        rootCauseCandidates: (json['root_cause_candidates'] as List<dynamic>? ?? [])
            .map((e) => RootCauseCandidate.fromJson(e as Map<String, dynamic>))
            .toList(),
        timeline: (json['timeline'] as List<dynamic>? ?? [])
            .map((e) => TimelineEvent.fromJson(e as Map<String, dynamic>))
            .toList(),
        chainOfThought:     (json['chain_of_thought'] as String?) ?? '',
        investigationSteps: List<String>.from(
            json['investigation_steps'] as List<dynamic>? ?? []),
        relatedDocumentation: List<String>.from(
            json['related_documentation'] as List<dynamic>? ?? []),
        lowConfidenceWarning: json['low_confidence_warning'] as String?,
        llmProvider:          (json['llm_provider'] as String?) ?? '',
      );

  final String rcaId;
  final String incidentId;
  final String summary;
  final double overallConfidence;
  final List<RootCauseCandidate> rootCauseCandidates;
  final List<TimelineEvent> timeline;
  final String chainOfThought;
  final List<String> investigationSteps;
  final List<String> relatedDocumentation;
  final String? lowConfidenceWarning;
  final String llmProvider;

  bool get hasLowConfidence => lowConfidenceWarning != null;

  @override
  List<Object?> get props => [rcaId, incidentId, overallConfidence];
}

// ── Remediation models ────────────────────────────────────────────────────────

class RemediationAction extends Equatable {
  const RemediationAction({
    required this.id,
    required this.incidentId,
    required this.title,
    required this.actionType,
    required this.riskTier,
    required this.reasoning,
    required this.rank,
    required this.status,
    this.confidence,
    this.params = const {},
    this.reviewedBy,
    this.rejectionReason,
    this.createdAt = '',
    this.reviewedAt,
  });

  factory RemediationAction.fromJson(Map<String, dynamic> json) =>
      RemediationAction(
        id:               (json['id'] as String?) ?? '',
        incidentId:       (json['incident_id'] as String?) ?? '',
        title:            (json['title'] as String?) ?? '',
        actionType:       (json['action_type'] as String?) ?? '',
        riskTier:         (json['risk_tier'] as String?) ?? 'LOW',
        reasoning:        (json['reasoning'] as String?) ?? '',
        rank:             (json['rank'] as int?) ?? 1,
        status:           (json['status'] as String?) ?? 'RECOMMENDED',
        confidence:       (json['confidence'] as num?)?.toDouble(),
        params:           Map<String, dynamic>.from(
            json['params'] as Map<String, dynamic>? ?? {}),
        reviewedBy:       json['reviewed_by'] as String?,
        rejectionReason:  json['rejection_reason'] as String?,
        createdAt:        (json['created_at'] as String?) ?? '',
        reviewedAt:       json['reviewed_at'] as String?,
      );

  final String id;
  final String incidentId;
  final String title;
  final String actionType;
  final String riskTier;   // LOW | MEDIUM | HIGH
  final String reasoning;
  final int rank;
  final String status;     // RECOMMENDED | APPROVED | REJECTED
  final double? confidence;
  final Map<String, dynamic> params;
  final String? reviewedBy;
  final String? rejectionReason;
  final String createdAt;
  final String? reviewedAt;

  bool get isHighRisk => riskTier.toUpperCase() == 'HIGH';
  bool get isPending   => status == 'RECOMMENDED';
  bool get isApproved  => status == 'APPROVED';
  bool get isRejected  => status == 'REJECTED';

  @override
  List<Object?> get props => [id, status, riskTier];
}

class RemediationPlanResponse {
  const RemediationPlanResponse({
    required this.incidentId,
    required this.incidentTitle,
    required this.aiSummary,
    required this.actions,
    this.lowConfidenceWarning,
  });

  factory RemediationPlanResponse.fromJson(Map<String, dynamic> json) =>
      RemediationPlanResponse(
        incidentId:    (json['incident_id'] as String?) ?? '',
        incidentTitle: (json['incident_title'] as String?) ?? '',
        aiSummary:     (json['ai_summary'] as String?) ?? '',
        actions: (json['actions'] as List<dynamic>? ?? [])
            .map((e) => RemediationAction.fromJson(e as Map<String, dynamic>))
            .toList(),
        lowConfidenceWarning: json['low_confidence_warning'] as String?,
      );

  final String incidentId;
  final String incidentTitle;
  final String aiSummary;
  final List<RemediationAction> actions;
  final String? lowConfidenceWarning;
}
