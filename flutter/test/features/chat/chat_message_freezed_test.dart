import 'package:ai_assistant_flutter/features/chat/domain/chat_message.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('ChatMessage (Freezed)', () {
    final baseMessage = ChatMessage(
      localId:   'msg-1',
      role:      MessageRole.user,
      content:   'Hello',
      createdAt: DateTime(2025, 6, 15, 10, 30),
    );

    // ── Construction ──────────────────────────────────────────────────────

    test('defaults: content="" isStreaming=false hasError=false', () {
      final msg = ChatMessage(
        localId:   'x',
        role:      MessageRole.assistant,
        createdAt: DateTime(2025),
      );
      expect(msg.content,     '');
      expect(msg.isStreaming, isFalse);
      expect(msg.hasError,    isFalse);
      expect(msg.serverId,    isNull);
    });

    // ── isUser getter ─────────────────────────────────────────────────────

    test('isUser returns true for MessageRole.user', () {
      expect(baseMessage.isUser, isTrue);
    });

    test('isUser returns false for MessageRole.assistant', () {
      final assistantMsg = baseMessage.copyWith(role: MessageRole.assistant);
      expect(assistantMsg.isUser, isFalse);
    });

    // ── copyWith ──────────────────────────────────────────────────────────

    test('copyWith updates content while preserving other fields', () {
      final updated = baseMessage.copyWith(content: 'Hello world');
      expect(updated.content,     'Hello world');
      expect(updated.localId,     baseMessage.localId);
      expect(updated.role,        baseMessage.role);
      expect(updated.createdAt,   baseMessage.createdAt);
      expect(updated.isStreaming, isFalse);
    });

    test('copyWith sets isStreaming=true', () {
      final streaming = baseMessage.copyWith(isStreaming: true);
      expect(streaming.isStreaming, isTrue);
    });

    test('copyWith clears isStreaming back to false', () {
      final streaming = baseMessage.copyWith(isStreaming: true);
      final done      = streaming.copyWith(isStreaming: false);
      expect(done.isStreaming, isFalse);
    });

    test('copyWith can mark hasError=true', () {
      final errored = baseMessage.copyWith(hasError: true);
      expect(errored.hasError, isTrue);
    });

    // ── Equality ──────────────────────────────────────────────────────────

    test('two messages with identical fields are equal', () {
      final a = ChatMessage(
        localId:   'same',
        role:      MessageRole.user,
        content:   'Hi',
        createdAt: DateTime(2025),
      );
      final b = ChatMessage(
        localId:   'same',
        role:      MessageRole.user,
        content:   'Hi',
        createdAt: DateTime(2025),
      );
      expect(a, equals(b));
      expect(a.hashCode, b.hashCode);
    });

    test('messages with different localIds are not equal', () {
      final a = baseMessage;
      final b = baseMessage.copyWith(localId: 'different-id');
      // copyWith preserves localId through the Freezed generated code;
      // use the constructor to get a different id.
      final c = ChatMessage(
        localId:   'msg-2',
        role:      MessageRole.user,
        content:   'Hello',
        createdAt: DateTime(2025, 6, 15, 10, 30),
      );
      expect(a == c, isFalse);
    });

    // ── MessageRole enum ──────────────────────────────────────────────────

    test('MessageRole has user and assistant variants', () {
      expect(MessageRole.values.length, 2);
      expect(MessageRole.values, containsAll([
        MessageRole.user,
        MessageRole.assistant,
      ]));
    });
  });
}
