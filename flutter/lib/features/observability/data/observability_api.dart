/// API client for POST /api/v1/observability/events.
///
/// This endpoint requires NO authentication — events can be uploaded even
/// when the user is logged out.  The backend rate-limits by IP instead.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/observability/domain/observability_models.dart';
import 'package:dio/dio.dart';

class ObservabilityApi {
  const ObservabilityApi(this._dio);
  final Dio _dio;

  /// Upload a batch of observability events (max 500 per call).
  Future<Result<IngestResponse>> ingest(IngestRequest request) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.observabilityEvents,
        // Skip the auth interceptor: this endpoint has no auth.
        options: Options(headers: {'Authorization': null}),
        data: request.toJson(),
      );
      return Success(IngestResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
