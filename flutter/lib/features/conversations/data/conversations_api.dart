/// API calls for the /conversations endpoints.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:dio/dio.dart';

class ConversationsApi {
  const ConversationsApi(this._dio);
  final Dio _dio;

  Future<Result<ConversationListResponse>> listConversations({
    int page = 1,
    int pageSize = 20,
  }) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.conversations,
        queryParameters: {'page': page, 'page_size': pageSize},
      );
      return Success(ConversationListResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<Conversation>> createConversation({String? title, String? provider}) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.conversations,
        data: {
          if (title != null) 'title': title,
          if (provider != null) 'provider': provider,
        },
      );
      return Success(Conversation.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<List<ChatMessage>>> getMessages(String conversationId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.conversationMessages(conversationId),
      );
      final raw = response.data!['items'] as List<dynamic>? ?? [];
      final messages = raw
          .map((e) => ChatMessage.fromJson(e as Map<String, dynamic>))
          .toList();
      return Success(messages);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  Future<Result<void>> deleteConversation(String id) async {
    try {
      await _dio.delete<void>('${ApiConfig.conversations}/$id');
      return const Success(null);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
