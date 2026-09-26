/// Local cache for recent conversations backed by SharedPreferences.
///
/// Purpose:
///   - Show conversations instantly on app launch before the API responds.
///   - Display the conversation list when the device is offline.
///
/// Strategy:
///   - Cache the most recent [maxCachedConversations] conversations.
///   - Cache is written on every successful API response.
///   - Cache is invalidated on logout ([clear]).
///
/// Storage key: 'cached_conversations'
/// Format:      JSON-encoded list of serialised [CachedConversation] objects.
///
/// Only non-sensitive metadata is stored (id, title, provider, timestamps).
/// Message content is NOT cached here — fetch it fresh from the API.
library;

import 'dart:convert';

import 'package:ai_assistant_flutter/core/utils/app_logger.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Lightweight conversation summary stored in the local cache.
class CachedConversation {
  const CachedConversation({
    required this.id,
    required this.title,
    required this.createdAt,
    this.updatedAt,
    this.provider,
    this.lastMessage,
    this.isPinned = false,
  });

  factory CachedConversation.fromJson(Map<String, dynamic> json) =>
      CachedConversation(
        id:          json['id'] as String,
        title:       (json['title'] as String?) ?? 'Conversation',
        createdAt:   json['created_at'] as String,
        updatedAt:   json['updated_at'] as String?,
        provider:    json['provider'] as String?,
        lastMessage: json['last_message'] as String?,
        isPinned:    (json['is_pinned'] as bool?) ?? false,
      );

  final String id;
  final String title;
  final String createdAt;
  final String? updatedAt;
  final String? provider;
  final String? lastMessage;
  final bool isPinned;

  Map<String, dynamic> toJson() => {
        'id':           id,
        'title':        title,
        'created_at':   createdAt,
        if (updatedAt != null)    'updated_at':    updatedAt,
        if (provider != null)     'provider':      provider,
        if (lastMessage != null)  'last_message':  lastMessage,
        'is_pinned':    isPinned,
      };
}

class ConversationCache {
  ConversationCache(this._prefs);

  final SharedPreferences _prefs;

  static const String _storageKey          = 'cached_conversations';
  static const int    maxCachedConversations = 100;

  // ── Read ──────────────────────────────────────────────────────────────────

  /// Cached conversations in most-recently-updated order.
  List<CachedConversation> get all {
    final raw = _prefs.getString(_storageKey);
    if (raw == null) return [];
    try {
      final list = jsonDecode(raw) as List<dynamic>;
      return list
          .map((e) =>
              CachedConversation.fromJson(e as Map<String, dynamic>))
          .toList();
    } catch (e) {
      AppLogger.w('ConversationCache: failed to decode cache', e);
      return [];
    }
  }

  bool get isEmpty => all.isEmpty;

  // ── Write ─────────────────────────────────────────────────────────────────

  /// Replace the entire cache with [conversations].
  ///
  /// Caps at [maxCachedConversations] keeping the most recent entries.
  Future<void> writeAll(List<CachedConversation> conversations) async {
    final capped = conversations.take(maxCachedConversations).toList();
    final json   = jsonEncode(capped.map((c) => c.toJson()).toList());
    await _prefs.setString(_storageKey, json);
    AppLogger.d('ConversationCache: cached ${capped.length} conversations');
  }

  /// Upsert a single conversation (add if new, update if already cached).
  Future<void> upsert(CachedConversation conversation) async {
    final list = all;
    final idx  = list.indexWhere((c) => c.id == conversation.id);
    if (idx != -1) {
      list[idx] = conversation;
    } else {
      list.insert(0, conversation); // newest first
    }
    await writeAll(list);
  }

  /// Remove a conversation from the cache (e.g. after deletion).
  Future<void> remove(String id) async {
    final list = all.where((c) => c.id != id).toList();
    await writeAll(list);
  }

  /// Wipe the entire cache (call on logout).
  Future<void> clear() async {
    await _prefs.remove(_storageKey);
    AppLogger.i('ConversationCache: cleared');
  }
}
