import 'package:ai_assistant_flutter/features/chat/domain/chat_state.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('UiMessage', () {
    test('isUser returns true for role=user', () {
      final msg = UiMessage(
        localId: '1',
        role: 'user',
        content: 'hello',
        createdAt: DateTime.now(),
      );
      expect(msg.isUser, isTrue);
    });

    test('copyWith updates content and preserves others', () {
      final msg = UiMessage(
        localId: 'x',
        role: 'assistant',
        content: 'partial',
        createdAt: DateTime(2025),
        isStreaming: true,
      );
      final updated = msg.copyWith(content: 'partial text more');
      expect(updated.content, 'partial text more');
      expect(updated.isStreaming, isTrue);
      expect(updated.localId, 'x');
    });

    test('copyWith can clear isStreaming', () {
      final msg = UiMessage(
        localId: '1',
        role: 'assistant',
        content: 'done',
        createdAt: DateTime.now(),
        isStreaming: true,
      );
      final done = msg.copyWith(isStreaming: false);
      expect(done.isStreaming, isFalse);
    });

    test('equality is value-based via Equatable', () {
      final dt = DateTime(2025);
      final a = UiMessage(localId: '1', role: 'user', content: 'hi', createdAt: dt);
      final b = UiMessage(localId: '1', role: 'user', content: 'hi', createdAt: dt);
      expect(a, equals(b));
    });
  });

  group('ChatState', () {
    test('initial state has no messages and is not streaming', () {
      const state = ChatState();
      expect(state.messages, isEmpty);
      expect(state.isStreaming, isFalse);
      expect(state.hasConversation, isFalse);
    });

    test('copyWith with conversationId sets hasConversation to true', () {
      const state = ChatState();
      final updated = state.copyWith(conversationId: 'conv-1');
      expect(updated.hasConversation, isTrue);
      expect(updated.conversationId, 'conv-1');
    });

    test('copyWith clearError removes connectionError', () {
      const state = ChatState(connectionError: 'something broke');
      final cleared = state.copyWith(clearError: true);
      expect(cleared.connectionError, isNull);
    });

    test('copyWith preserves existing messages when not provided', () {
      final msg = UiMessage(
        localId: '1',
        role: 'user',
        content: 'test',
        createdAt: DateTime.now(),
      );
      final state = ChatState(messages: [msg]);
      final updated = state.copyWith(isStreaming: true);
      expect(updated.messages.length, 1);
    });
  });
}
