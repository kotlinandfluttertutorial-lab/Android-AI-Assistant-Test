import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('Conversation.fromJson', () {
    test('parses required fields', () {
      final json = {
        'id': 'conv-1',
        'title': 'Test convo',
        'created_at': '2025-01-15T10:00:00',
        'is_pinned': false,
      };
      final convo = Conversation.fromJson(json);
      expect(convo.id, 'conv-1');
      expect(convo.title, 'Test convo');
      expect(convo.isPinned, isFalse);
    });

    test('uses default title when title is null', () {
      final json = {
        'id': 'conv-2',
        'title': null,
        'created_at': '2025-01-15T10:00:00',
      };
      final convo = Conversation.fromJson(json);
      expect(convo.title, 'New conversation');
    });

    test('parses updatedAt correctly', () {
      final json = {
        'id': 'conv-3',
        'title': 'Another',
        'created_at': '2025-01-15T10:00:00',
        'updated_at': '2025-01-16T12:00:00',
      };
      final convo = Conversation.fromJson(json);
      expect(convo.updatedAt, isNotNull);
    });
  });

  group('ChatMessage.fromJson', () {
    test('parses user message', () {
      final json = {
        'id': 'msg-1',
        'role': 'user',
        'content': 'Hello!',
        'created_at': '2025-01-15T10:00:00',
      };
      final msg = ChatMessage.fromJson(json);
      expect(msg.isUser, isTrue);
      expect(msg.content, 'Hello!');
    });

    test('parses assistant message', () {
      final json = {
        'id': 'msg-2',
        'role': 'assistant',
        'content': 'Hi there!',
        'created_at': '2025-01-15T10:01:00',
        'provider': 'gemini',
        'model': 'gemini-3.6-flash',
      };
      final msg = ChatMessage.fromJson(json);
      expect(msg.isUser, isFalse);
      expect(msg.provider, 'gemini');
    });
  });

  group('ConversationListResponse.fromJson', () {
    test('parses list of conversations', () {
      final json = {
        'items': [
          {
            'id': 'c1',
            'title': 'First',
            'created_at': '2025-01-01T00:00:00',
          },
          {
            'id': 'c2',
            'title': 'Second',
            'created_at': '2025-01-02T00:00:00',
          },
        ],
        'total': 2,
        'page': 1,
        'page_size': 20,
      };
      final response = ConversationListResponse.fromJson(json);
      expect(response.items.length, 2);
      expect(response.total, 2);
      expect(response.items.first.id, 'c1');
    });

    test('handles empty list gracefully', () {
      final json = {'items': [], 'total': 0, 'page': 1, 'page_size': 20};
      final response = ConversationListResponse.fromJson(json);
      expect(response.items, isEmpty);
    });
  });
}
