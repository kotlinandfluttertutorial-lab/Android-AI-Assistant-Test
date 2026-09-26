/// Riverpod state management for the DevOps AI assistant.
///
/// [DevOpsChatNotifier] holds the conversation history (list of [DevOpsTurn])
/// and fires REST calls to POST /devops/chat.
///
/// Unlike the general chat feature (which uses WebSocket streaming), the
/// DevOps assistant endpoint is REST-only — the backend runs the full ReAct
/// loop server-side and returns a complete answer in one response.
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/devops/data/devops_api.dart';
import 'package:ai_assistant_flutter/features/devops/domain/devops_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

// ── API provider ──────────────────────────────────────────────────────────────

final devOpsApiProvider = Provider<DevOpsApi>((ref) {
  return DevOpsApi(ref.watch(dioProvider));
});

// ── State ─────────────────────────────────────────────────────────────────────

class DevOpsChatState {
  const DevOpsChatState({
    this.turns = const [],
    this.isAsking = false,
    this.selectedProvider,
  });

  final List<DevOpsTurn> turns;
  final bool isAsking;
  final String? selectedProvider; // null = backend default

  bool get isEmpty => turns.isEmpty;

  DevOpsChatState copyWith({
    List<DevOpsTurn>? turns,
    bool? isAsking,
    String? selectedProvider,
  }) =>
      DevOpsChatState(
        turns:            turns ?? this.turns,
        isAsking:         isAsking ?? this.isAsking,
        selectedProvider: selectedProvider ?? this.selectedProvider,
      );
}

// ── Notifier ──────────────────────────────────────────────────────────────────

class DevOpsChatNotifier extends Notifier<DevOpsChatState> {
  @override
  DevOpsChatState build() => const DevOpsChatState();

  /// Ask the DevOps assistant a question.
  ///
  /// Adds a loading turn immediately so the UI reflects activity, then
  /// updates it with the response or error when the call completes.
  Future<void> ask(String question) async {
    if (question.trim().isEmpty) return;

    final turnId = _uuid.v4();
    final loadingTurn = DevOpsTurn(
      id:        turnId,
      question:  question.trim(),
      isLoading: true,
    );

    state = state.copyWith(
      turns:    [...state.turns, loadingTurn],
      isAsking: true,
    );

    final api = ref.read(devOpsApiProvider);
    final result = await api.chat(
      DevOpsChatRequest(
        question: question.trim(),
        provider: state.selectedProvider,
      ),
    );

    final updatedTurns = List<DevOpsTurn>.from(state.turns);
    final idx = updatedTurns.indexWhere((t) => t.id == turnId);

    if (idx != -1) {
      updatedTurns[idx] = result.when(
        onSuccess: (response) => loadingTurn.copyWith(
          response:  response,
          isLoading: false,
        ),
        onFailure: (err) => loadingTurn.copyWith(
          isLoading:    false,
          errorMessage: err.userMessage,
        ),
      );
    }

    state = state.copyWith(turns: updatedTurns, isAsking: false);
  }

  /// Set the LLM provider override (null = backend default).
  void setProvider(String? provider) {
    state = state.copyWith(selectedProvider: provider);
  }

  /// Remove all conversation history.
  void clearHistory() {
    state = const DevOpsChatState();
  }
}

final devOpsChatProvider =
    NotifierProvider<DevOpsChatNotifier, DevOpsChatState>(
  DevOpsChatNotifier.new,
);
