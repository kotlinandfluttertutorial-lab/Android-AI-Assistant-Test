/// Riverpod provider for [ConversationCache].
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/storage/conversation_cache.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final conversationCacheProvider = Provider<ConversationCache>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  return ConversationCache(prefs);
});
