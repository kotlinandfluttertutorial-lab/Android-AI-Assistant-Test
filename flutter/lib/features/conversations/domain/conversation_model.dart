/// Domain models for conversations and messages.
library;

import 'package:equatable/equatable.dart';

class Conversation extends Equatable {
  const Conversation({
    required this.id,
    required this.title,
    required this.createdAt,
    this.updatedAt,
    this.isPinned = false,
    this.provider,
    this.lastMessage,
  });

  factory Conversation.fromJson(Map<String, dynamic> json) => Conversation(
        id:          json['id'] as String,
        title:       (json['title'] as String?) ?? 'New conversation',
        createdAt:   DateTime.parse(json['created_at'] as String),
        updatedAt:   json['updated_at'] != null
            ? DateTime.tryParse(json['updated_at'] as String)
            : null,
        isPinned:    (json['is_pinned'] as bool?) ?? false,
        provider:    json['provider'] as String?,
        lastMessage: json['last_message'] as String?,
      );

  final String id;
  final String title;
  final DateTime createdAt;
  final DateTime? updatedAt;
  final bool isPinned;
  final String? provider;
  final String? lastMessage;

  @override
  List<Object?> get props =>
      [id, title, createdAt, isPinned, provider, lastMessage];
}

class ChatMessage extends Equatable {
  const ChatMessage({
    required this.id,
    required this.role,
    required this.content,
    required this.createdAt,
    this.provider,
    this.model,
  });

  factory ChatMessage.fromJson(Map<String, dynamic> json) => ChatMessage(
        id:        json['id'] as String,
        role:      json['role'] as String,
        content:   json['content'] as String,
        createdAt: DateTime.parse(json['created_at'] as String),
        provider:  json['provider'] as String?,
        model:     json['model'] as String?,
      );

  final String id;
  final String role; // 'user' | 'assistant'
  final String content;
  final DateTime createdAt;
  final String? provider;
  final String? model;

  bool get isUser => role == 'user';

  @override
  List<Object?> get props => [id, role, content, createdAt];
}

/// Paginated conversation list response from the backend.
class ConversationListResponse {
  const ConversationListResponse({
    required this.items,
    required this.total,
    required this.page,
    required this.pageSize,
  });

  factory ConversationListResponse.fromJson(Map<String, dynamic> json) {
    final rawItems = json['items'] as List<dynamic>? ?? [];
    return ConversationListResponse(
      items:    rawItems.map((e) => Conversation.fromJson(e as Map<String, dynamic>)).toList(),
      total:    json['total'] as int? ?? 0,
      page:     json['page'] as int? ?? 1,
      pageSize: json['page_size'] as int? ?? 20,
    );
  }

  final List<Conversation> items;
  final int total;
  final int page;
  final int pageSize;
}
