/// Riverpod providers for AI provider selection and status.
library;

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/ai_providers/domain/ai_provider_model.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The currently selected AI provider (persisted in SharedPreferences).
final selectedProviderProvider =
    NotifierProvider<SelectedProviderNotifier, AiProvider>(
  SelectedProviderNotifier.new,
);

class SelectedProviderNotifier extends Notifier<AiProvider> {
  @override
  AiProvider build() {
    final prefs = ref.watch(appPreferencesProvider);
    final savedId = prefs.selectedProvider;
    return KnownProviders.allProviders.firstWhere(
      (p) => p.id == savedId,
      orElse: () => KnownProviders.gemini,
    );
  }

  Future<void> select(AiProvider provider) async {
    state = provider;
    final prefs = ref.read(appPreferencesProvider);
    await prefs.setSelectedProvider(provider.id);
  }
}

/// The list of all providers (cloud + on-device).
final providersListProvider = Provider<List<AiProvider>>(
  (_) => KnownProviders.allProviders,
);
