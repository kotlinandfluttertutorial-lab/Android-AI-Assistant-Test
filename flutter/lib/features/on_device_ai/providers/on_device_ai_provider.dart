/// Riverpod provider for on-device AI.
library;

import 'package:ai_assistant_flutter/features/on_device_ai/domain/on_device_ai_service.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The active [OnDeviceAiService] implementation.
///
/// Override this in tests or when a real implementation is ready:
///   onDeviceAiServiceProvider.overrideWithValue(RealOnDeviceAiService())
final onDeviceAiServiceProvider = Provider<OnDeviceAiService>(
  (_) => StubOnDeviceAiService(),
);

/// Async notifier that manages the on-device AI lifecycle.
class OnDeviceAiNotifier extends AsyncNotifier<OnDeviceAiState> {
  @override
  Future<OnDeviceAiState> build() async {
    final service = ref.watch(onDeviceAiServiceProvider);
    final supported = await service.checkSupport();
    return supported ? OnDeviceAiState.supported : OnDeviceAiState.unsupported;
  }

  Future<void> loadModel() async {
    state = const AsyncLoading();
    final service = ref.read(onDeviceAiServiceProvider);
    try {
      await service.loadModel();
      state = AsyncData(service.state);
    } catch (e, st) {
      state = AsyncError(e, st);
    }
  }
}

final onDeviceAiProvider =
    AsyncNotifierProvider<OnDeviceAiNotifier, OnDeviceAiState>(
  OnDeviceAiNotifier.new,
);
