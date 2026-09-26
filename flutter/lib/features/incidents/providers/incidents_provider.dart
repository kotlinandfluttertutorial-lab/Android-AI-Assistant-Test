/// Riverpod providers for the Incidents feature.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/incidents/data/incidents_api.dart';
import 'package:ai_assistant_flutter/features/incidents/domain/incident_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

// ── API provider ──────────────────────────────────────────────────────────────

final incidentsApiProvider = Provider<IncidentsApi>((ref) {
  return IncidentsApi(ref.watch(dioProvider));
});

// ── Filter state ──────────────────────────────────────────────────────────────

class IncidentFilter {
  const IncidentFilter({this.status, this.severity});
  final String? status;
  final String? severity;

  IncidentFilter copyWith({String? status, String? severity}) =>
      IncidentFilter(
        status: status ?? this.status,
        severity: severity ?? this.severity,
      );
}

final incidentFilterProvider =
    StateProvider<IncidentFilter>((_) => const IncidentFilter());

// ── Incidents list notifier ───────────────────────────────────────────────────

class IncidentsNotifier extends AsyncNotifier<IncidentListResponse> {
  @override
  Future<IncidentListResponse> build() => _fetch();

  Future<IncidentListResponse> _fetch() async {
    final api    = ref.read(incidentsApiProvider);
    final filter = ref.watch(incidentFilterProvider);
    final result = await api.listIncidents(
      status:   filter.status,
      severity: filter.severity,
    );
    return result.when(
      onSuccess: (data) => data,
      onFailure: (e) => throw e,
    );
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_fetch);
  }

  void setFilter(IncidentFilter filter) {
    ref.read(incidentFilterProvider.notifier).state = filter;
    unawaited(refresh());
  }
}

final incidentsProvider =
    AsyncNotifierProvider<IncidentsNotifier, IncidentListResponse>(
  IncidentsNotifier.new,
);

// ── Single incident ───────────────────────────────────────────────────────────

final incidentDetailProvider =
    FutureProvider.family<Incident, String>((ref, id) async {
  final api    = ref.watch(incidentsApiProvider);
  final result = await api.getIncident(id);
  return result.when(
    onSuccess: (data) => data,
    onFailure: (e) => throw e,
  );
});

// ── RCA ───────────────────────────────────────────────────────────────────────

class RcaNotifier extends FamilyAsyncNotifier<RcaAnalysisResponse?, String> {
  @override
  Future<RcaAnalysisResponse?> build(String incidentId) async {
    // Try to fetch any cached RCA result; return null if none yet.
    final api    = ref.read(incidentsApiProvider);
    final result = await api.getRca(incidentId);
    return result.when(
      onSuccess: (rca) => rca,
      onFailure: (_) => null, // 404 is expected when no RCA run yet
    );
  }

  Future<void> runRca({bool forceRerun = false}) async {
    state = const AsyncLoading();
    final api    = ref.read(incidentsApiProvider);
    final result = await api.runRca(arg, forceRerun: forceRerun);
    state = result.when(
      onSuccess: AsyncData.new,
      onFailure: (e) => AsyncError(e, StackTrace.current),
    );
  }
}

final rcaProvider =
    AsyncNotifierProviderFamily<RcaNotifier, RcaAnalysisResponse?, String>(
  RcaNotifier.new,
);

// ── Remediation ───────────────────────────────────────────────────────────────

class RemediationNotifier
    extends FamilyAsyncNotifier<RemediationPlanResponse?, String> {
  @override
  Future<RemediationPlanResponse?> build(String incidentId) async => null;

  Future<void> recommend() async {
    state = const AsyncLoading();
    final api    = ref.read(incidentsApiProvider);
    final result = await api.recommendRemediation(arg);
    state = result.when(
      onSuccess: AsyncData.new,
      onFailure: (e) => AsyncError(e, StackTrace.current),
    );
  }

  Future<Result<RemediationAction>> approve(String actionId) async {
    final api = ref.read(incidentsApiProvider);
    final result = await api.approveAction(arg, actionId);
    if (result.isSuccess) await recommend(); // refresh after approval
    return result;
  }

  Future<Result<RemediationAction>> reject(
    String actionId, {
    String reason = '',
  }) async {
    final api = ref.read(incidentsApiProvider);
    final result = await api.rejectAction(arg, actionId, reason: reason);
    if (result.isSuccess) await recommend();
    return result;
  }
}

final remediationProvider =
    AsyncNotifierProviderFamily<RemediationNotifier, RemediationPlanResponse?,
        String>(
  RemediationNotifier.new,
);
