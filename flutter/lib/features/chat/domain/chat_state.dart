/// Chat state model used by [ChatNotifier].
library;

import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:equatable/equatable.dart';

/// A single message shown in the chat UI.
///
/// Separate from [ChatMessage] (API model) because during streaming the
/// assistant message is built incrementally before it has a server-assigned id.
class UiMessage extends Equatable {
  const UiMessage({
    required this.localId,
    required this.role,
    required this.content,
    required this.createdAt,
    this.isStreaming = false,
    this.hasError = false,
  });

  final String localId;
  final String role;     // 'user' | 'assistant'
  final String content;
  final DateTime createdAt;
  final bool isStreaming;
  final bool hasError;

  bool get isUser => role == 'user';

  UiMessage copyWith({
    String? content,
    bool? isStreaming,
    bool? hasError,
  }) =>
      UiMessage(
        localId:     localId,
        role:        role,
        content:     content ?? this.content,
        createdAt:   createdAt,
        isStreaming: isStreaming ?? this.isStreaming,
        hasError:    hasError ?? this.hasError,
      );

  @override
  List<Object?> get props =>
      [localId, role, content, isStreaming, hasError];
}

/// Top-level state for the [ChatNotifier].
class ChatState extends Equatable {
  const ChatState({
    this.conversationId,
    this.messages = const [],
    this.isConnecting = false,
    this.isStreaming = false,
    this.connectionError,
  });

  final String? conversationId;
  final List<UiMessage> messages;
  final bool isConnecting;
  final bool isStreaming;
  final String? connectionError;

  bool get hasConversation => conversationId != null;

  ChatState copyWith({
    String? conversationId,
    List<UiMessage>? messages,
    bool? isConnecting,
    bool? isStreaming,
    String? connectionError,
    bool clearError = false,
  }) =>
      ChatState(
        conversationId:  conversationId ?? this.conversationId,
        messages:        messages ?? this.messages,
        isConnecting:    isConnecting ?? this.isConnecting,
        isStreaming:     isStreaming ?? this.isStreaming,
        connectionError: clearError ? null : (connectionError ?? this.connectionError),
      );

  @override
  List<Object?> get props =>
      [conversationId, messages, isConnecting, isStreaming, connectionError];
}
