/// Offline message queue backed by SharedPreferences.
///
/// When the user sends a message while the WebSocket is disconnected or the
/// device has no network, the message is persisted here and retried
/// automatically when connectivity is restored.
///
/// Storage key: 'pending_messages'
/// Format:      JSON-encoded list of [PendingMessage] objects.
///
/// Design decisions:
/// - SharedPreferences keeps the dependency count zero (no SQLite needed).
/// - Maximum queue size is capped at [maxQueueSize] to avoid unbounded growth.
/// - Messages are processed in FIFO order.
/// - Sensitive content is NOT stored here (no tokens, no credentials).
library;

import 'dart:convert';

import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A message waiting to be sent.
class PendingMessage {
  const PendingMessage({
    required this.id,
    required this.conversationId,
    required this.content,
    required this.enqueuedAt,
    this.provider,
    this.retryCount = 0,
  });

  factory PendingMessage.fromJson(Map<String, dynamic> json) => PendingMessage(
        id:             json['id'] as String,
        conversationId: json['conversation_id'] as String,
        content:        json['content'] as String,
        enqueuedAt:     DateTime.parse(json['enqueued_at'] as String),
        provider:       json['provider'] as String?,
        retryCount:     (json['retry_count'] as int?) ?? 0,
      );

  final String id;
  final String conversationId;
  final String content;
  final DateTime enqueuedAt;
  final String? provider;
  final int retryCount;

  Map<String, dynamic> toJson() => {
        'id':              id,
        'conversation_id': conversationId,
        'content':         content,
        'enqueued_at':     enqueuedAt.toIso8601String(),
        if (provider != null) 'provider': provider,
        'retry_count':     retryCount,
      };

  PendingMessage withRetry() => PendingMessage(
        id:             id,
        conversationId: conversationId,
        content:        content,
        enqueuedAt:     enqueuedAt,
        provider:       provider,
        retryCount:     retryCount + 1,
      );

  /// Discard messages that have been retried too many times.
  bool get isExpired => retryCount >= PendingMessageQueue.maxRetries;
}

/// FIFO queue of pending messages persisted across app restarts.
class PendingMessageQueue {
  PendingMessageQueue(this._prefs);

  final SharedPreferences _prefs;

  static const String _storageKey  = 'pending_messages';
  static const int    maxQueueSize = 50;
  static const int    maxRetries   = 5;

  // ── Read ──────────────────────────────────────────────────────────────────

  /// All pending messages in FIFO order, expired ones excluded.
  List<PendingMessage> get all {
    final raw = _prefs.getString(_storageKey);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .map((e) => PendingMessage.fromJson(e as Map<String, dynamic>))
          .where((m) => !m.isExpired)
          .toList();
    } catch (e) {
      AppLogger.w('PendingMessageQueue: failed to decode queue', e);
      return [];
    }
  }

  bool get isEmpty => all.isEmpty;
  int  get length  => all.length;

  // ── Write ─────────────────────────────────────────────────────────────────

  /// Add a message to the end of the queue.
  ///
  /// If [maxQueueSize] would be exceeded, the oldest message is dropped first
  /// and a warning is logged.
  Future<void> enqueue(PendingMessage message) async {
    var queue = all;
    if (queue.length >= maxQueueSize) {
      AppLogger.w(
        'PendingMessageQueue: queue full ($maxQueueSize), dropping oldest message',
      );
      queue = queue.sublist(1);
    }
    queue.add(message);
    await _persist(queue);
    AppLogger.d(
      'PendingMessageQueue: enqueued ${message.id} '
      '(conversation=${message.conversationId}, queue=${queue.length})',
    );
  }

  /// Remove the message with the given [id].
  Future<void> remove(String id) async {
    final queue = all.where((m) => m.id != id).toList();
    await _persist(queue);
    AppLogger.d('PendingMessageQueue: removed $id (remaining=${queue.length})');
  }

  /// Replace a message in-place (used to increment retry count).
  Future<void> update(PendingMessage updated) async {
    final queue =
        all.map((m) => m.id == updated.id ? updated : m).toList();
    await _persist(queue);
  }

  /// Remove all expired messages (retryCount >= [maxRetries]).
  Future<void> pruneExpired() async {
    final before = all.length;
    final queue  = all.where((m) => !m.isExpired).toList();
    if (queue.length != before) {
      await _persist(queue);
      AppLogger.i(
        'PendingMessageQueue: pruned ${before - queue.length} expired messages',
      );
    }
  }

  /// Wipe the entire queue (e.g. on logout).
  Future<void> clear() async {
    await _prefs.remove(_storageKey);
    AppLogger.i('PendingMessageQueue: cleared');
  }

  // ── Private ───────────────────────────────────────────────────────────────

  Future<void> _persist(List<PendingMessage> queue) async {
    final json = jsonEncode(queue.map((m) => m.toJson()).toList());
    await _prefs.setString(_storageKey, json);
  }
}
