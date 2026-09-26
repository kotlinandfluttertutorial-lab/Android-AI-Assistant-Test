/// Chat state notifier — drives the Chat screen.
///
/// Offline queue integration:
///   - If the WebSocket cannot connect, the message is persisted to
///     [PendingMessageQueue] and shown to the user with a "queued" indicator.
///   - On the next successful WebSocket connection, all pending messages for
///     this conversation are flushed in order before the new message is sent.
///
/// Flow (online):
///   1. sendMessage → optimistic user bubble → create conversation if needed
///   2. connect WebSocket → flush pending queue → send message
///   3. accumulate token stream → mark done
///
/// Flow (offline):
///   1. sendMessage → optimistic user bubble
///   2. WebSocket connect fails → enqueue to PendingMessageQueue
///   3. Show "queued" error state — will retry on next reconnect
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/network/websocket_service.dart';
import 'package:ai_assistant_flutter/core/storage/pending_message_queue.dart';
import 'package:ai_assistant_flutter/core/storage/pending_message_queue_provider.dart';
import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:ai_assistant_flutter/features/ai_providers/providers/ai_provider_provider.dart';
import 'package:ai_assistant_flutter/features/chat/domain/chat_state.dart';
import 'package:ai_assistant_flutter/features/conversations/data/conversations_api.dart';
import 'package:ai_assistant_flutter/features/conversations/providers/conversations_provider.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:uuid/uuid.dart';

const _uuid = Uuid();

class ChatNotifier extends FamilyNotifier<ChatState, String> {
  late WebSocketService _ws;
  StreamSubscription<WsMessage>? _sub;

  @override
  ChatState build(String conversationId) {
    _ws = WebSocketService();
    ref.onDispose(() {
      _sub?.cancel();
      unawaited(_ws.disconnect());
    });

    if (conversationId != 'new') {
      unawaited(_loadHistory(conversationId));
    }

    return ChatState(
      conversationId: conversationId == 'new' ? null : conversationId,
    );
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  Future<void> sendMessage(String text) async {
    if (text.trim().isEmpty) return;

    // Optimistic user bubble.
    final messageId = _uuid.v4();
    final userMsg = UiMessage(
      localId:   messageId,
      role:      'user',
      content:   text.trim(),
      createdAt: DateTime.now(),
    );
    state = state.copyWith(
      messages:   [...state.messages, userMsg],
      clearError: true,
    );

    // Ensure we have a conversation ID before touching the socket.
    String? convId = state.conversationId;
    if (convId == null) {
      convId = await _createConversation();
      if (convId == null) return; // creation failed; error already in state
    }

    // Try to connect the WebSocket.
    if (_ws.state != WsConnectionState.connected) {
      final connected = await _connectWebSocket(convId);
      if (!connected) {
        // Offline — persist to queue and inform the user.
        await _enqueueOffline(
          id:             messageId,
          conversationId: convId,
          content:        text.trim(),
        );
        return;
      }
    }

    // Flush any messages that were queued while offline.
    await _flushPendingQueue(convId);

    // Add the streaming assistant placeholder.
    final assistantMsg = UiMessage(
      localId:     _uuid.v4(),
      role:        'assistant',
      content:     '',
      createdAt:   DateTime.now(),
      isStreaming: true,
    );
    state = state.copyWith(
      messages:    [...state.messages, assistantMsg],
      isStreaming: true,
    );

    final provider = ref.read(selectedProviderProvider);
    _ws.sendMessage(
      text.trim(),
      provider: provider.isOnDevice ? null : provider.id,
    );
  }

  Future<void> stopGeneration() async {
    _sub?.cancel();
    await _ws.disconnect();
    _markLastAssistantDone();
  }

  /// Manually retry all queued messages for this conversation.
  Future<void> retryPendingMessages() async {
    final convId = state.conversationId;
    if (convId == null) return;

    if (_ws.state != WsConnectionState.connected) {
      final connected = await _connectWebSocket(convId);
      if (!connected) {
        state = state.copyWith(
          connectionError: 'Still offline — messages remain queued.',
        );
        return;
      }
    }
    await _flushPendingQueue(convId);
  }

  // ── Offline queue helpers ──────────────────────────────────────────────────

  Future<void> _enqueueOffline({
    required String id,
    required String conversationId,
    required String content,
  }) async {
    final provider = ref.read(selectedProviderProvider);
    final queue    = ref.read(pendingMessageQueueProvider);
    await queue.enqueue(PendingMessage(
      id:             id,
      conversationId: conversationId,
      content:        content,
      enqueuedAt:     DateTime.now(),
      provider:       provider.isOnDevice ? null : provider.id,
    ));

    AppLogger.i(
      'ChatNotifier: message queued offline '
      '(conv=$conversationId, queue=${queue.length})',
    );

    state = state.copyWith(
      connectionError:
          'You are offline. This message will be sent automatically when connectivity returns.',
      isStreaming: false,
    );
  }

  /// Send all messages in the queue that belong to [conversationId].
  Future<void> _flushPendingQueue(String conversationId) async {
    final queue = ref.read(pendingMessageQueueProvider);
    final pending = queue.all
        .where((m) => m.conversationId == conversationId)
        .toList();

    if (pending.isEmpty) return;

    AppLogger.i(
      'ChatNotifier: flushing ${pending.length} pending message(s) '
      'for conversation $conversationId',
    );

    final provider = ref.read(selectedProviderProvider);

    for (final msg in pending) {
      // Add a streaming placeholder for the queued message's response.
      final assistantMsg = UiMessage(
        localId:     _uuid.v4(),
        role:        'assistant',
        content:     '',
        createdAt:   DateTime.now(),
        isStreaming: true,
      );
      state = state.copyWith(
        messages:    [...state.messages, assistantMsg],
        isStreaming: true,
        clearError:  true,
      );

      _ws.sendMessage(
        msg.content,
        provider: msg.provider ?? (provider.isOnDevice ? null : provider.id),
      );

      // Remove from queue after sending.
      await queue.remove(msg.id);

      // Small delay between queued messages to avoid overwhelming the backend.
      await Future<void>.delayed(const Duration(milliseconds: 200));
    }
  }

  // ── Private helpers ────────────────────────────────────────────────────────

  Future<String?> _createConversation() async {
    final api      = ref.read(conversationsApiProvider);
    final provider = ref.read(selectedProviderProvider);
    final result   = await api.createConversation(provider: provider.id);
    return result.when(
      onSuccess: (conv) {
        state = state.copyWith(conversationId: conv.id);
        unawaited(ref.read(conversationsProvider.notifier).refresh());
        return conv.id;
      },
      onFailure: (err) {
        state = state.copyWith(
          connectionError: err.userMessage,
          isStreaming:     false,
        );
        return null;
      },
    );
  }

  /// Returns true if the WebSocket connected successfully.
  Future<bool> _connectWebSocket(String conversationId) async {
    state = state.copyWith(isConnecting: true);

    final storage = ref.read(secureStorageProvider);
    final token   = await storage.getAccessToken();
    if (token == null) {
      state = state.copyWith(
        isConnecting:    false,
        connectionError: 'Session expired. Please sign in again.',
      );
      return false;
    }

    try {
      await _ws.connect(conversationId: conversationId, token: token);
      _sub?.cancel();
      _sub = _ws.messages?.listen(_onWsMessage);
      state = state.copyWith(isConnecting: false);
      return _ws.state == WsConnectionState.connected;
    } catch (e) {
      AppLogger.w('ChatNotifier: WebSocket connect failed', e);
      state = state.copyWith(isConnecting: false);
      return false;
    }
  }

  void _onWsMessage(WsMessage msg) {
    switch (msg.type) {
      case WsMessageType.token:
        _appendToken(msg.data ?? '');
      case WsMessageType.done:
        _markLastAssistantDone();
      case WsMessageType.error:
        AppLogger.w('Chat WS error: ${msg.message}');
        _markLastAssistantError(msg.message ?? 'AI returned an error.');
      case WsMessageType.toolCall:
        AppLogger.d('Chat: tool call — ${msg.toolName}');
      case WsMessageType.ping:
      case WsMessageType.unknown:
        break;
    }
  }

  void _appendToken(String token) {
    final messages  = List<UiMessage>.from(state.messages);
    final lastIndex = messages.lastIndexWhere((m) => !m.isUser);
    if (lastIndex == -1) return;
    final last = messages[lastIndex];
    messages[lastIndex] = last.copyWith(
      content:     last.content + token,
      isStreaming: true,
    );
    state = state.copyWith(messages: messages, isStreaming: true);
  }

  void _markLastAssistantDone() {
    final messages  = List<UiMessage>.from(state.messages);
    final lastIndex = messages.lastIndexWhere((m) => !m.isUser);
    if (lastIndex != -1) {
      messages[lastIndex] =
          messages[lastIndex].copyWith(isStreaming: false);
    }
    state = state.copyWith(messages: messages, isStreaming: false);
  }

  void _markLastAssistantError(String errorText) {
    final messages  = List<UiMessage>.from(state.messages);
    final lastIndex = messages.lastIndexWhere((m) => !m.isUser);
    if (lastIndex != -1) {
      messages[lastIndex] = messages[lastIndex].copyWith(
        content:     errorText,
        isStreaming: false,
        hasError:    true,
      );
    }
    state = state.copyWith(messages: messages, isStreaming: false);
  }

  Future<void> _loadHistory(String conversationId) async {
    final api    = ref.read(conversationsApiProvider);
    final result = await api.getMessages(conversationId);
    result.when(
      onSuccess: (msgs) {
        final uiMessages = msgs
            .map((m) => UiMessage(
                  localId:   m.id,
                  role:      m.role,
                  content:   m.content,
                  createdAt: m.createdAt,
                ))
            .toList();
        state = state.copyWith(messages: uiMessages);
      },
      onFailure: (e) =>
          AppLogger.w('ChatNotifier: failed to load history: ${e.userMessage}'),
    );
  }
}

final chatProvider =
    NotifierProviderFamily<ChatNotifier, ChatState, String>(
  ChatNotifier.new,
);
