/// Riverpod provider for [PendingMessageQueue].
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/core/storage/pending_message_queue.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

final pendingMessageQueueProvider = Provider<PendingMessageQueue>((ref) {
  final prefs = ref.watch(sharedPreferencesProvider);
  return PendingMessageQueue(prefs);
});
