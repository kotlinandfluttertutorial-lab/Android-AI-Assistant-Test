import 'package:ai_assistant_flutter/core/storage/conversation_cache.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

Future<ConversationCache> makeCache() async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  return ConversationCache(prefs);
}

CachedConversation makeConversation({
  String id    = 'conv-1',
  String title = 'Test conversation',
}) =>
    CachedConversation(
      id:        id,
      title:     title,
      createdAt: '2025-01-15T10:00:00',
    );

void main() {
  group('ConversationCache', () {
    test('is empty initially', () async {
      final cache = await makeCache();
      expect(cache.isEmpty, isTrue);
      expect(cache.all, isEmpty);
    });

    test('writeAll persists conversations', () async {
      final cache = await makeCache();
      await cache.writeAll([
        makeConversation(id: 'a', title: 'Alpha'),
        makeConversation(id: 'b', title: 'Beta'),
      ]);

      expect(cache.all.length,      2);
      expect(cache.all.first.title, 'Alpha');
      expect(cache.isEmpty,         isFalse);
    });

    test('writeAll caps at maxCachedConversations', () async {
      final cache = await makeCache();
      final convs = List.generate(
        ConversationCache.maxCachedConversations + 20,
        (i) => makeConversation(id: 'c-$i', title: 'Conv $i'),
      );

      await cache.writeAll(convs);

      expect(cache.all.length,
          ConversationCache.maxCachedConversations);
    });

    test('upsert adds a new conversation at the front', () async {
      final cache = await makeCache();
      await cache.upsert(makeConversation(id: 'first'));
      await cache.upsert(makeConversation(id: 'second'));

      // second was upserted last but should appear first (newest-first order
      // is only guaranteed if the caller inserts newest first — upsert adds
      // at index 0 for new entries)
      expect(cache.all.first.id, 'second');
      expect(cache.all.last.id,  'first');
    });

    test('upsert updates an existing entry in-place', () async {
      final cache = await makeCache();
      await cache.upsert(makeConversation(id: 'c1', title: 'Old title'));
      await cache.upsert(makeConversation(id: 'c1', title: 'New title'));

      // Should not grow.
      expect(cache.all.length,      1);
      expect(cache.all.first.title, 'New title');
    });

    test('remove deletes the correct entry', () async {
      final cache = await makeCache();
      await cache.upsert(makeConversation(id: 'keep'));
      await cache.upsert(makeConversation(id: 'remove-me'));

      await cache.remove('remove-me');

      expect(cache.all.length,      1);
      expect(cache.all.first.id,    'keep');
    });

    test('remove on unknown id is a no-op', () async {
      final cache = await makeCache();
      await cache.upsert(makeConversation());
      await cache.remove('does-not-exist');
      expect(cache.all.length, 1);
    });

    test('clear empties the cache', () async {
      final cache = await makeCache();
      await cache.writeAll([
        makeConversation(id: 'x'),
        makeConversation(id: 'y'),
      ]);
      await cache.clear();
      expect(cache.isEmpty, isTrue);
    });

    // ── JSON round-trip ────────────────────────────────────────────────────

    test('CachedConversation survives JSON round-trip', () async {
      final cache = await makeCache();
      final conv = CachedConversation(
        id:          'rt-conv',
        title:       'Round-trip title',
        createdAt:   '2025-06-15T08:00:00',
        updatedAt:   '2025-06-15T09:00:00',
        provider:    'gemini',
        lastMessage: 'Hello there',
        isPinned:    true,
      );
      await cache.upsert(conv);

      final restored = cache.all.first;
      expect(restored.id,          conv.id);
      expect(restored.title,       conv.title);
      expect(restored.provider,    conv.provider);
      expect(restored.lastMessage, conv.lastMessage);
      expect(restored.isPinned,    isTrue);
      expect(restored.updatedAt,   conv.updatedAt);
    });
  });
}
