/// Domain models for the DevOps AI assistant feature.
///
/// Backend contract: POST /devops/chat
///   Request:  {question: str, provider?: str}
///   Response: {session_id, question, answer, citations: [str],
///              tool_calls: [{tool_name, params, result}],
///              rounds_used: int, llm_provider: str}
///
/// GET /devops/tools
///   Response: [{name, description, parameters}]
library;

import 'package:equatable/equatable.dart';

// ── Request ───────────────────────────────────────────────────────────────────

class DevOpsChatRequest {
  const DevOpsChatRequest({
    required this.question,
    this.provider,
  });

  final String question;
  final String? provider;

  Map<String, dynamic> toJson() => {
        'question': question,
        if (provider != null) 'provider': provider,
      };
}

// ── Tool call summary ─────────────────────────────────────────────────────────

class ToolCallSummary extends Equatable {
  const ToolCallSummary({
    required this.toolName,
    required this.params,
    required this.result,
  });

  factory ToolCallSummary.fromJson(Map<String, dynamic> json) =>
      ToolCallSummary(
        toolName: (json['tool_name'] as String?) ?? '',
        params:   Map<String, dynamic>.from(
            json['params'] as Map<String, dynamic>? ?? {}),
        result:   Map<String, dynamic>.from(
            json['result'] as Map<String, dynamic>? ?? {}),
      );

  final String toolName;
  final Map<String, dynamic> params;
  final Map<String, dynamic> result;

  @override
  List<Object?> get props => [toolName];
}

// ── Response ──────────────────────────────────────────────────────────────────

class DevOpsChatResponse extends Equatable {
  const DevOpsChatResponse({
    required this.sessionId,
    required this.question,
    required this.answer,
    required this.citations,
    required this.toolCalls,
    required this.roundsUsed,
    required this.llmProvider,
  });

  factory DevOpsChatResponse.fromJson(Map<String, dynamic> json) =>
      DevOpsChatResponse(
        sessionId:   (json['session_id'] as String?) ?? '',
        question:    (json['question'] as String?) ?? '',
        answer:      (json['answer'] as String?) ?? '',
        citations:   List<String>.from(
            json['citations'] as List<dynamic>? ?? []),
        toolCalls: (json['tool_calls'] as List<dynamic>? ?? [])
            .map((e) =>
                ToolCallSummary.fromJson(e as Map<String, dynamic>))
            .toList(),
        roundsUsed:  (json['rounds_used'] as int?) ?? 0,
        llmProvider: (json['llm_provider'] as String?) ?? '',
      );

  final String sessionId;
  final String question;
  final String answer;
  final List<String> citations;
  final List<ToolCallSummary> toolCalls;
  final int roundsUsed;
  final String llmProvider;

  @override
  List<Object?> get props => [sessionId, question, answer];
}

// ── Conversation turn (UI model) ──────────────────────────────────────────────

/// A single Q&A exchange in the DevOps assistant chat history.
class DevOpsTurn extends Equatable {
  const DevOpsTurn({
    required this.id,
    required this.question,
    this.response,
    this.isLoading = false,
    this.errorMessage,
  });

  final String id;
  final String question;
  final DevOpsChatResponse? response;
  final bool isLoading;
  final String? errorMessage;

  bool get hasError   => errorMessage != null;
  bool get hasAnswer  => response != null;

  DevOpsTurn copyWith({
    DevOpsChatResponse? response,
    bool? isLoading,
    String? errorMessage,
    bool clearError = false,
  }) =>
      DevOpsTurn(
        id:           id,
        question:     question,
        response:     response ?? this.response,
        isLoading:    isLoading ?? this.isLoading,
        errorMessage: clearError ? null : (errorMessage ?? this.errorMessage),
      );

  @override
  List<Object?> get props => [id, question, isLoading, errorMessage];
}

// ── Suggested questions ───────────────────────────────────────────────────────

/// Pre-built query suggestions shown in the DevOps chat input area.
class DevOpsSuggestions {
  DevOpsSuggestions._();

  static const List<String> defaults = [
    'Why did the API fail at the last incident?',
    'Show me recent critical incidents',
    'What is the current error rate?',
    'Have we seen this error before?',
    'What changed before the latest incident?',
    'Generate an incident report for the latest open incident',
    'What are the most likely root causes?',
  ];
}
