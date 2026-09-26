/// API client for /analysis endpoints.
///
///   POST /analysis/errors  — analyse recent errors
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/analysis/domain/analysis_models.dart';
import 'package:dio/dio.dart';

class AnalysisApi {
  const AnalysisApi(this._dio);
  final Dio _dio;

  Future<Result<ErrorAnalysisResponse>> analyseErrors(
    AnalyseErrorRequest request,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.analysisErrors,
        data: request.toJson(),
      );
      return Success(ErrorAnalysisResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
