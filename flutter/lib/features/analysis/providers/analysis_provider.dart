/// Riverpod providers for AI Error Analysis.
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/analysis/data/analysis_api.dart';
import 'package:ai_assistant_flutter/features/analysis/domain/analysis_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final analysisApiProvider = Provider<AnalysisApi>((ref) {
  return AnalysisApi(ref.watch(dioProvider));
});

// ── State ─────────────────────────────────────────────────────────────────────

class AnalysisState {
  const AnalysisState({
    this.result,
    this.isLoading = false,
    this.error,
    this.lookbackMinutes = 30,
    this.selectedProvider,
  });

  final ErrorAnalysisResponse? result;
  final bool isLoading;
  final String? error;
  final int lookbackMinutes;
  final String? selectedProvider;

  bool get hasResult => result != null;
  bool get hasError  => error != null;

  AnalysisState copyWith({
    ErrorAnalysisResponse? result,
    bool? isLoading,
    String? error,
    int? lookbackMinutes,
    String? selectedProvider,
    bool clearError = false,
    bool clearResult = false,
  }) =>
      AnalysisState(
        result:           clearResult  ? null  : (result ?? this.result),
        isLoading:        isLoading    ?? this.isLoading,
        error:            clearError   ? null  : (error  ?? this.error),
        lookbackMinutes:  lookbackMinutes    ?? this.lookbackMinutes,
        selectedProvider: selectedProvider   ?? this.selectedProvider,
      );
}

// ── Notifier ──────────────────────────────────────────────────────────────────

class AnalysisNotifier extends Notifier<AnalysisState> {
  @override
  AnalysisState build() => const AnalysisState();

  Future<void> analyseRecent() async {
    state = state.copyWith(isLoading: true, clearError: true);
    final api    = ref.read(analysisApiProvider);
    final result = await api.analyseErrors(
      AnalyseErrorRequest(
        lookbackMinutes: state.lookbackMinutes,
        provider:        state.selectedProvider,
      ),
    );
    state = result.when(
      onSuccess: (data) =>
          state.copyWith(result: data, isLoading: false),
      onFailure: (err) =>
          state.copyWith(isLoading: false, error: err.userMessage),
    );
  }

  Future<void> analyseSession(String sessionId) async {
    state = state.copyWith(isLoading: true, clearError: true);
    final api    = ref.read(analysisApiProvider);
    final result = await api.analyseErrors(
      AnalyseErrorRequest(
        sessionId: sessionId,
        provider:  state.selectedProvider,
      ),
    );
    state = result.when(
      onSuccess: (data) =>
          state.copyWith(result: data, isLoading: false),
      onFailure: (err) =>
          state.copyWith(isLoading: false, error: err.userMessage),
    );
  }

  void setLookback(int minutes)         => state = state.copyWith(lookbackMinutes: minutes);
  void setProvider(String? provider)    => state = state.copyWith(selectedProvider: provider);
  void clear()                          => state = const AnalysisState();
}

final analysisProvider =
    NotifierProvider<AnalysisNotifier, AnalysisState>(AnalysisNotifier.new);
