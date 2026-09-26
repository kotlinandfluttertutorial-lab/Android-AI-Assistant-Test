/// Freezed model for a single chat message in the UI layer.
///
/// ## Code generation
///
/// After editing this file, regenerate with:
/// ```bash
/// flutter pub run build_runner build --delete-conflicting-outputs
/// ```
///
/// The generated files (`chat_message.freezed.dart`, `chat_message.g.dart`)
/// are committed to the repository so the project compiles without running
/// build_runner first.
///
/// ## Why Freezed for chat messages?
///
/// - Guaranteed immutability — no accidental mutation of streaming state.
/// - `copyWith` is auto-generated (no hand-written copy constructor to maintain).
/// - `==` and `hashCode` are correct-by-construction, important for Riverpod
///   selective rebuilds.
library;

import 'package:freezed_annotation/freezed_annotation.dart';

part 'chat_message.freezed.dart';
part 'chat_message.g.dart';

/// Role of the message author.
enum MessageRole {
  @JsonValue('user')
  user,

  @JsonValue('assistant')
  assistant,
}

/// A single message displayed in the chat UI.
///
/// During streaming, [isStreaming] is true and [content] grows with each
/// token. When the stream completes, [isStreaming] is set to false.
@freezed
class ChatMessage with _$ChatMessage {
  const ChatMessage._(); // Required by Freezed for custom getters.

  const factory ChatMessage({
    /// Client-side unique ID (UUID v4). Not the server message ID.
    required String localId,

    /// Who sent this message.
    required MessageRole role,

    /// Text content (may be partial during streaming).
    @Default('') String content,

    /// When this message was created on the client.
    required DateTime createdAt,

    /// True while the assistant is still streaming tokens.
    @Default(false) bool isStreaming,

    /// True if the AI returned an error instead of a valid response.
    @Default(false) bool hasError,

    /// Server-assigned message ID (null until the server confirms).
    String? serverId,

    /// The LLM provider that generated this response (assistant only).
    String? provider,

    /// The model name used (assistant only).
    String? model,
  }) = _ChatMessage;

  factory ChatMessage.fromJson(Map<String, dynamic> json) =>
      _$ChatMessageFromJson(json);

  /// Convenience — true for user messages.
  bool get isUser => role == MessageRole.user;
}
