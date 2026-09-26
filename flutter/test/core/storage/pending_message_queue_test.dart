import 'package:ai_assistant_flutter/core/storage/pending_message_queue.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

// Helper — creates a fresh in-memory PendingMessageQueue.
Future<PendingMessageQueue> makeQueue() async {
  SharedPreferences.setMockInitialValues({});
  final prefs = await SharedPreferences.getInstance();
  return PendingMessageQueue(prefs);
}

PendingMessage makeMessage({
  String id          = 'msg-1',
  String conversationId = 'conv-1',
  String content     = 'Hello',
  int    retryCount  = 0,
}) =>
    PendingMessage(
      id:             id,
      conversationId: conversationId,
      content:        content,
      enqueuedAt:     DateTime(2025, 1, 15, 14, 32),
      retryCount:     retryCount,
    );

void main() {
  group('PendingMessageQueue', () {
    // ── isEmpty / length ───────────────────────────────────────────────────

    test('is empty on first use', () async {
      final q = await makeQueue();
      expect(q.isEmpty, isTrue);
      expect(q.length, 0);
    });

    // ── enqueue ────────────────────────────────────────────────────────────

    test('enqueue adds a message and makes queue non-empty', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage());

      expect(q.isEmpty, isFalse);
      expect(q.length, 1);
      expect(q.all.first.id, 'msg-1');
    });

    test('enqueue preserves FIFO order', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage(id: 'first',  content: 'A'));
      await q.enqueue(makeMessage(id: 'second', content: 'B'));
      await q.enqueue(makeMessage(id: 'third',  content: 'C'));

      final ids = q.all.map((m) => m.id).toList();
      expect(ids, ['first', 'second', 'third']);
    });

    test('enqueue drops oldest when queue exceeds maxQueueSize', () async {
      final q = await makeQueue();

      // Fill to capacity.
      for (var i = 0; i < PendingMessageQueue.maxQueueSize; i++) {
        await q.enqueue(makeMessage(id: 'msg-$i', content: 'content $i'));
      }
      expect(q.length, PendingMessageQueue.maxQueueSize);

      // Adding one more should drop the oldest.
      await q.enqueue(makeMessage(id: 'overflow', content: 'new'));

      expect(q.length, PendingMessageQueue.maxQueueSize);
      expect(q.all.first.id, 'msg-1');  // 'msg-0' was dropped
      expect(q.all.last.id,  'overflow');
    });

    // ── remove ─────────────────────────────────────────────────────────────

    test('remove deletes the correct message', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage(id: 'keep-1'));
      await q.enqueue(makeMessage(id: 'delete-me'));
      await q.enqueue(makeMessage(id: 'keep-2'));

      await q.remove('delete-me');

      expect(q.length, 2);
      expect(q.all.map((m) => m.id).toList(), ['keep-1', 'keep-2']);
    });

    test('remove on non-existent id is a no-op', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage());
      await q.remove('does-not-exist');
      expect(q.length, 1);
    });

    // ── update ─────────────────────────────────────────────────────────────

    test('update replaces a message in-place', () async {
      final q   = await makeQueue();
      final msg = makeMessage(id: 'upd-1', retryCount: 0);
      await q.enqueue(msg);

      await q.update(msg.withRetry());

      expect(q.all.first.retryCount, 1);
    });

    // ── pruneExpired ───────────────────────────────────────────────────────

    test('pruneExpired removes messages that exceeded maxRetries', () async {
      final q = await makeQueue();

      // One expired, one valid.
      await q.enqueue(makeMessage(
        id:         'expired',
        retryCount: PendingMessageQueue.maxRetries,
      ));
      await q.enqueue(makeMessage(id: 'valid', retryCount: 0));

      await q.pruneExpired();

      expect(q.length, 1);
      expect(q.all.first.id, 'valid');
    });

    // ── clear ──────────────────────────────────────────────────────────────

    test('clear wipes all messages', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage(id: 'a'));
      await q.enqueue(makeMessage(id: 'b'));

      await q.clear();

      expect(q.isEmpty, isTrue);
    });

    // ── PendingMessage.withRetry ───────────────────────────────────────────

    test('withRetry increments retryCount', () {
      final msg     = makeMessage(retryCount: 2);
      final retried = msg.withRetry();
      expect(retried.retryCount, 3);
    });

    test('isExpired is false below maxRetries', () {
      final msg =
          makeMessage(retryCount: PendingMessageQueue.maxRetries - 1);
      expect(msg.isExpired, isFalse);
    });

    test('isExpired is true at maxRetries', () {
      final msg = makeMessage(retryCount: PendingMessageQueue.maxRetries);
      expect(msg.isExpired, isTrue);
    });

    // ── JSON round-trip ────────────────────────────────────────────────────

    test('message survives JSON round-trip', () async {
      final q   = await makeQueue();
      final msg = PendingMessage(
        id:             'rt-1',
        conversationId: 'conv-rt',
        content:        'Roundtrip content',
        enqueuedAt:     DateTime(2025, 6, 15, 10, 30),
        provider:       'gemini',
        retryCount:     1,
      );
      await q.enqueue(msg);

      // Re-read from prefs to simulate app restart.
      final restored = q.all.first;
      expect(restored.id,             msg.id);
      expect(restored.conversationId, msg.conversationId);
      expect(restored.content,        msg.content);
      expect(restored.provider,       msg.provider);
      expect(restored.retryCount,     msg.retryCount);
      expect(
        restored.enqueuedAt.toIso8601String(),
        msg.enqueuedAt.toIso8601String(),
      );
    });

    // ── Expired messages are filtered in all ──────────────────────────────

    test('all getter silently excludes expired messages', () async {
      final q = await makeQueue();
      await q.enqueue(makeMessage(
        id:         'old',
        retryCount: PendingMessageQueue.maxRetries,
      ));
      await q.enqueue(makeMessage(id: 'fresh', retryCount: 0));

      // No pruneExpired called — all getter should still hide expired ones.
      expect(q.all.map((m) => m.id).toList(), ['fresh']);
    });
  });
}
