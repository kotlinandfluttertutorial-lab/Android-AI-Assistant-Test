/// API client for /incidents endpoints.
///
/// Endpoints consumed:
///   GET  /incidents?status=&severity=&limit=
///   GET  /incidents/{id}
///   POST /incidents/{id}/rca
///   GET  /incidents/{id}/rca
///   POST /incidents/{id}/remediation/recommend
///   POST /incidents/{id}/remediation/{actionId}/approve
///   POST /incidents/{id}/remediation/{actionId}/reject
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/incidents/domain/incident_models.dart';
import 'package:dio/dio.dart';

class IncidentsApi {
  const IncidentsApi(this._dio);
  final Dio _dio;

  // ── List ──────────────────────────────────────────────────────────────────

  Future<Result<IncidentListResponse>> listIncidents({
    int limit = 50,
    String? status,
    String? severity,
  }) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.incidents,
        queryParameters: {
          'limit': limit,
          if (status != null) 'status': status,
          if (severity != null) 'severity': severity,
        },
      );
      return Success(IncidentListResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Single incident ────────────────────────────────────────────────────────

  Future<Result<Incident>> getIncident(String id) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.incidentById(id),
      );
      return Success(Incident.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Status update ─────────────────────────────────────────────────────────

  Future<Result<Incident>> updateStatus(String id, String status) async {
    try {
      final response = await _dio.patch<Map<String, dynamic>>(
        '${ApiConfig.incidentById(id)}/status',
        data: {'status': status},
      );
      return Success(Incident.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── RCA ───────────────────────────────────────────────────────────────────

  Future<Result<RcaAnalysisResponse>> runRca(
    String id, {
    int evidenceWindowMinutes = 30,
    bool forceRerun = false,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.incidentRca(id),
        data: {
          'evidence_window_minutes': evidenceWindowMinutes,
          'force_rerun': forceRerun,
        },
      );
      return Success(RcaAnalysisResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<RcaAnalysisResponse>> getRca(String id) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.incidentRca(id),
      );
      return Success(RcaAnalysisResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Remediation ───────────────────────────────────────────────────────────

  Future<Result<RemediationPlanResponse>> recommendRemediation(String id) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.incidentRemediationRecommend(id),
      );
      return Success(RemediationPlanResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<RemediationAction>> approveAction(
    String incidentId,
    String actionId,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.incidentRemediationApprove(incidentId, actionId),
      );
      return Success(RemediationAction.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<RemediationAction>> rejectAction(
    String incidentId,
    String actionId, {
    String reason = '',
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.incidentRemediationReject(incidentId, actionId),
        data: {'reason': reason},
      );
      return Success(RemediationAction.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
