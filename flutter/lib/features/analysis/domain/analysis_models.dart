/// Domain models for AI Error Analysis (Phase 10).
///
/// Backend contract: POST /analysis/errors
///
/// Request:
///   {event_id?, session_id?, lookback_minutes? (1-1440), provider?}
///
/// Response: ErrorAnalysisResponse — mirrors backend schema exactly.
///
/// AI Safety constraints encoded here:
///   - confidence is bounded [0.0, 1.0]
///   - facts_vs_inference separates observed data from LLM reasoning
///   - low_confidence_warning is populated when confidence < 0.6
///   - recommended_fix is a suggestion only; no automated action is taken
library;

import 'package:equatable/equatable.dart';

// ── Severity ──────────────────────────────────────────────────────────────────

enum AnalysisSeverity {
  critical, high, medium, low;

  static AnalysisSeverity parse(String v) => switch (v.toUpperCase()) {
        'CRITICAL' => AnalysisSeverity.critical,
        'HIGH'     => AnalysisSeverity.high,
        'MEDIUM'   => AnalysisSeverity.medium,
        _          => AnalysisSeverity.low,
      };

  String get label => name.toUpperCase();
}

// ── Facts vs inference ────────────────────────────────────────────────────────

/// Separates confirmed observations from LLM reasoning.
///
/// AI Safety Principle: the AI must never present inferences as facts.
class FactsVsInference extends Equatable {
  const FactsVsInference({
    this.facts      = const [],
    this.inferences = const [],
  });

  factory FactsVsInference.fromJson(Map<String, dynamic> json) =>
      FactsVsInference(
        facts:      List<String>.from(json['facts']      as List? ?? []),
        inferences: List<String>.from(json['inferences'] as List? ?? []),
      );

  /// Directly observable facts from logs and metrics.
  final List<String> facts;

  /// LLM reasoning about probable causes — clearly labelled as inference.
  final List<String> inferences;

  @override
  List<Object?> get props => [facts, inferences];
}

// ── Primary response schema ───────────────────────────────────────────────────

class ErrorAnalysisResponse extends Equatable {
  const ErrorAnalysisResponse({
    required this.analysisId,
    required this.severity,
    required this.summary,
    required this.likelyRootCause,
    required this.confidence,
    required this.recommendedFix,
    this.evidence             = const [],
    this.possibleCauses       = const [],
    this.relatedDocumentation = const [],
    this.factsVsInference     = const FactsVsInference(),
    this.lowConfidenceWarning,
    this.eventsAnalysed       = 0,
    this.knowledgeChunksRetrieved = 0,
    this.llmProvider          = '',
  });

  factory ErrorAnalysisResponse.fromJson(Map<String, dynamic> json) =>
      ErrorAnalysisResponse(
        analysisId:      (json['analysis_id'] as String?) ?? '',
        severity:        AnalysisSeverity.parse(
            (json['severity'] as String?) ?? 'LOW'),
        summary:         (json['summary'] as String?) ?? '',
        likelyRootCause: (json['likely_root_cause'] as String?) ?? '',
        confidence:      ((json['confidence'] as num?) ?? 0).toDouble(),
        recommendedFix:  (json['recommended_fix'] as String?) ?? '',
        evidence: List<String>.from(
            json['evidence'] as List? ?? []),
        possibleCauses: List<String>.from(
            json['possible_causes'] as List? ?? []),
        relatedDocumentation: List<String>.from(
            json['related_documentation'] as List? ?? []),
        factsVsInference: json['facts_vs_inference'] != null
            ? FactsVsInference.fromJson(
                json['facts_vs_inference'] as Map<String, dynamic>)
            : const FactsVsInference(),
        lowConfidenceWarning:
            json['low_confidence_warning'] as String?,
        eventsAnalysed:
            (json['events_analysed'] as int?) ?? 0,
        knowledgeChunksRetrieved:
            (json['knowledge_chunks_retrieved'] as int?) ?? 0,
        llmProvider: (json['llm_provider'] as String?) ?? '',
      );

  final String analysisId;
  final AnalysisSeverity severity;
  final String summary;

  /// Most probable root cause with reasoning. When confidence < 0.6 this
  /// says "Evidence is insufficient — manual investigation required."
  final String likelyRootCause;

  /// LLM self-assessed confidence in the root cause (0.0 – 1.0).
  final double confidence;

  /// Step-by-step suggestion only. No automated action is taken.
  final String recommendedFix;

  final List<String> evidence;
  final List<String> possibleCauses;
  final List<String> relatedDocumentation;
  final FactsVsInference factsVsInference;

  /// Populated when confidence < 0.6 — instructs manual investigation.
  final String? lowConfidenceWarning;

  final int eventsAnalysed;
  final int knowledgeChunksRetrieved;
  final String llmProvider;

  bool get hasLowConfidence => lowConfidenceWarning != null;
  int  get confidencePct    => (confidence * 100).round();

  @override
  List<Object?> get props => [analysisId, severity, confidence];
}

// ── Request ───────────────────────────────────────────────────────────────────

class AnalyseErrorRequest {
  const AnalyseErrorRequest({
    this.eventId,
    this.sessionId,
    this.lookbackMinutes = 30,
    this.provider,
  });

  final String? eventId;
  final String? sessionId;
  final int     lookbackMinutes;
  final String? provider;

  Map<String, dynamic> toJson() => {
        if (eventId != null)   'event_id':         eventId,
        if (sessionId != null) 'session_id':        sessionId,
        'lookback_minutes':                         lookbackMinutes,
        if (provider != null)  'provider':          provider,
      };
}
