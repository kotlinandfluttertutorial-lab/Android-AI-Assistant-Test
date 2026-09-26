/// API client for /devops endpoints.
///
///   POST /devops/chat  — ReAct tool-calling loop, returns grounded answer
///   GET  /devops/tools — lists all 7 DevOps MCP tool schemas
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/devops/domain/devops_models.dart';
import 'package:dio/dio.dart';

class DevOpsApi {
  const DevOpsApi(this._dio);
  final Dio _dio;

  /// Ask the AI DevOps assistant a natural-language question.
  ///
  /// The backend runs a ReAct tool-calling loop and returns a grounded answer
  /// with citations, tool calls used, and the LLM provider that served it.
  Future<Result<DevOpsChatResponse>> chat(DevOpsChatRequest request) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.devopsChat,
        data: request.toJson(),
      );
      return Success(DevOpsChatResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  /// List available DevOps tool schemas (for display/debug purposes).
  Future<Result<List<Map<String, dynamic>>>> listTools() async {
    try {
      final response = await _dio.get<List<dynamic>>(ApiConfig.devopsTools);
      final tools = (response.data ?? [])
          .map((e) => Map<String, dynamic>.from(e as Map))
          .toList();
      return Success(tools);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
