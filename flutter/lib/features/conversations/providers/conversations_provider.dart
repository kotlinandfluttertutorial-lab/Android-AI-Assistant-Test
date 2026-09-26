/// Riverpod providers for the conversation list.
///
/// Cache strategy:
///   build()    → serve from [ConversationCache] immediately, then fetch
///                API in the background and update state + cache.
///   refresh()  → fetch API, update state + cache.
///   create/delete → update state + cache in-place (no extra round-trip).
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/storage/conversation_cache.dart';
import 'package:ai_assistant_flutter/core/storage/conversation_cache_provider.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/conversations/data/conversations_api.dart';
import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final conversationsApiProvider = Provider<ConversationsApi>((ref) {
  return ConversationsApi(ref.watch(dioProvider));
});

// ── Helper: Conversation → CachedConversation ────────────────────────────────

CachedConversation _toCached(Conversation c) => CachedConversation(
      id:          c.id,
      title:       c.title,
      createdAt:   c.createdAt.toIso8601String(),
      updatedAt:   c.updatedAt?.toIso8601String(),
      provider:    c.provider,
      lastMessage: c.lastMessage,
      isPinned:    c.isPinned,
    );

Conversation _fromCached(CachedConversation c) => Conversation(
      id:          c.id,
      title:       c.title,
      createdAt:   DateTime.parse(c.createdAt),
      updatedAt:   c.updatedAt != null ? DateTime.tryParse(c.updatedAt!) : null,
      provider:    c.provider,
      lastMessage: c.lastMessage,
      isPinned:    c.isPinned,
    );

// ── Notifier ──────────────────────────────────────────────────────────────────

class ConversationsNotifier extends AsyncNotifier<List<Conversation>> {
  @override
  Future<List<Conversation>> build() async {
    // 1. Serve cache instantly so the UI shows content on first frame.
    final cache   = ref.read(conversationCacheProvider);
    final cached  = cache.all;
    if (cached.isNotEmpty) {
      // Set cached data synchronously, then refresh from API in background.
      state = AsyncData(cached.map(_fromCached).toList());
      unawaited(_fetchAndCache()); // background refresh
      return cached.map(_fromCached).toList();
    }
    // No cache — block on the network call.
    return _fetchAndCache();
  }

  Future<List<Conversation>> _fetchAndCache() async {
    final api    = ref.read(conversationsApiProvider);
    final cache  = ref.read(conversationCacheProvider);
    final result = await api.listConversations();
    return result.when(
      onSuccess: (data) async {
        // Update cache.
        await cache.writeAll(data.items.map(_toCached).toList());
        // Update state if we had cached data before (background refresh).
        state = AsyncData(data.items);
        return data.items;
      },
      onFailure: (e) => throw e,
    );
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_fetchAndCache);
  }

  Future<Result<Conversation>> createConversation({
    String? title,
    String? provider,
  }) async {
    final api   = ref.read(conversationsApiProvider);
    final cache = ref.read(conversationCacheProvider);

    final result = await api.createConversation(
      title:    title,
      provider: provider,
    );

    if (result.isSuccess) {
      final conv = result.dataOrNull!;
      // Prepend to state.
      state.whenData((list) {
        state = AsyncData([conv, ...list]);
      });
      // Persist to cache.
      await cache.upsert(_toCached(conv));
    }
    return result;
  }

  Future<void> deleteConversation(String id) async {
    final api   = ref.read(conversationsApiProvider);
    final cache = ref.read(conversationCacheProvider);

    await api.deleteConversation(id);

    // Update state.
    state.whenData((list) {
      state = AsyncData(list.where((c) => c.id != id).toList());
    });
    // Remove from cache.
    await cache.remove(id);
  }
}

final conversationsProvider =
    AsyncNotifierProvider<ConversationsNotifier, List<Conversation>>(
  ConversationsNotifier.new,
);
