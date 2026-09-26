import 'package:ai_assistant_flutter/features/devops/domain/devops_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // ── DevOpsChatRequest ──────────────────────────────────────────────────────

  group('DevOpsChatRequest.toJson', () {
    test('includes question and omits provider when null', () {
      const req = DevOpsChatRequest(question: 'Why did the API fail?');
      final json = req.toJson();

      expect(json['question'], 'Why did the API fail?');
      expect(json.containsKey('provider'), isFalse);
    });

    test('includes provider when set', () {
      const req = DevOpsChatRequest(
        question: 'Show recent incidents',
        provider: 'gemini',
      );
      final json = req.toJson();

      expect(json['provider'], 'gemini');
    });

    test('empty question is preserved as-is', () {
      const req = DevOpsChatRequest(question: '');
      expect(req.toJson()['question'], '');
    });
  });

  // ── ToolCallSummary.fromJson ───────────────────────────────────────────────

  group('ToolCallSummary.fromJson', () {
    test('parses tool name and params', () {
      final json = {
        'tool_name': 'search_logs',
        'params':    {'query': 'connection refused', 'limit': 10},
        'result':    {'count': 5, 'logs': []},
      };
      final tc = ToolCallSummary.fromJson(json);

      expect(tc.toolName,          'search_logs');
      expect(tc.params['query'],   'connection refused');
      expect(tc.result['count'],   5);
    });

    test('defaults to empty maps when params/result missing', () {
      final json = {'tool_name': 'get_metrics'};
      final tc = ToolCallSummary.fromJson(json);

      expect(tc.params, isEmpty);
      expect(tc.result, isEmpty);
    });
  });

  // ── DevOpsChatResponse.fromJson ────────────────────────────────────────────

  group('DevOpsChatResponse.fromJson', () {
    test('parses a full response', () {
      final json = {
        'session_id':   'sess-abc',
        'question':     'Why did the API fail at 14:32?',
        'answer':       'The connection pool was exhausted due to slow queries.',
        'citations':    ['runbook-db.md', 'incident-1234'],
        'tool_calls':   [
          {
            'tool_name': 'search_logs',
            'params':    {'query': 'error', 'service': 'api'},
            'result':    {'count': 12},
          },
        ],
        'rounds_used':  2,
        'llm_provider': 'gemini',
      };
      final resp = DevOpsChatResponse.fromJson(json);

      expect(resp.sessionId,           'sess-abc');
      expect(resp.answer,              isNotEmpty);
      expect(resp.citations.length,    2);
      expect(resp.toolCalls.length,    1);
      expect(resp.toolCalls.first.toolName, 'search_logs');
      expect(resp.roundsUsed,          2);
      expect(resp.llmProvider,         'gemini');
    });

    test('handles response with empty citations and tool calls', () {
      final json = {
        'session_id':   'sess-xyz',
        'question':     'Are there any active incidents?',
        'answer':       'No active incidents at this time.',
        'citations':    <dynamic>[],
        'tool_calls':   <dynamic>[],
        'rounds_used':  1,
        'llm_provider': 'openai',
      };
      final resp = DevOpsChatResponse.fromJson(json);

      expect(resp.citations, isEmpty);
      expect(resp.toolCalls, isEmpty);
    });
  });

  // ── DevOpsTurn ────────────────────────────────────────────────────────────

  group('DevOpsTurn', () {
    test('initial loading turn has no response', () {
      const turn = DevOpsTurn(
        id:        'turn-1',
        question:  'What is the error rate?',
        isLoading: true,
      );

      expect(turn.isLoading,  isTrue);
      expect(turn.hasError,   isFalse);
      expect(turn.hasAnswer,  isFalse);
    });

    test('copyWith sets response and clears loading', () {
      const turn = DevOpsTurn(
        id:        'turn-2',
        question:  'Show incidents',
        isLoading: true,
      );

      final response = DevOpsChatResponse.fromJson({
        'session_id':   's1',
        'question':     'Show incidents',
        'answer':       'Found 3 open incidents.',
        'citations':    <dynamic>[],
        'tool_calls':   <dynamic>[],
        'rounds_used':  1,
        'llm_provider': 'gemini',
      });

      final updated = turn.copyWith(response: response, isLoading: false);

      expect(updated.isLoading, isFalse);
      expect(updated.hasAnswer, isTrue);
      expect(updated.response?.answer, 'Found 3 open incidents.');
    });

    test('copyWith sets error state', () {
      const turn = DevOpsTurn(
        id:        'turn-3',
        question:  'Failing question',
        isLoading: true,
      );

      final errored = turn.copyWith(
        isLoading:    false,
        errorMessage: 'Service unavailable',
      );

      expect(errored.hasError,   isTrue);
      expect(errored.errorMessage, 'Service unavailable');
      expect(errored.hasAnswer,  isFalse);
    });

    test('copyWith clearError removes errorMessage', () {
      const turn = DevOpsTurn(
        id:           'turn-4',
        question:     'Retry',
        errorMessage: 'Previous error',
      );
      final cleared = turn.copyWith(clearError: true);
      expect(cleared.errorMessage, isNull);
      expect(cleared.hasError,     isFalse);
    });
  });

  // ── DevOpsSuggestions ─────────────────────────────────────────────────────

  group('DevOpsSuggestions.defaults', () {
    test('is non-empty', () {
      expect(DevOpsSuggestions.defaults, isNotEmpty);
    });

    test('all suggestions are non-empty strings', () {
      for (final s in DevOpsSuggestions.defaults) {
        expect(s.trim(), isNotEmpty,
            reason: 'Suggestion "$s" should not be blank');
      }
    });
  });
}
