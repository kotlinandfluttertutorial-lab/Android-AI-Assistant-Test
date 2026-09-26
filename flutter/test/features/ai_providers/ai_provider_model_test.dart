import 'package:ai_assistant_flutter/features/ai_providers/domain/ai_provider_model.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('AiProvider', () {
    test('copyWith updates status only', () {
      const provider = KnownProviders.gemini;
      final updated = provider.copyWith(status: AiProviderStatus.unavailable);
      expect(updated.status, AiProviderStatus.unavailable);
      expect(updated.id, provider.id);
      expect(updated.displayName, provider.displayName);
    });

    test('isOnDevice is false for cloud providers', () {
      expect(KnownProviders.gemini.isOnDevice, isFalse);
      expect(KnownProviders.openai.isOnDevice, isFalse);
      expect(KnownProviders.claude.isOnDevice, isFalse);
    });

    test('isOnDevice is true for on-device provider', () {
      expect(KnownProviders.onDevice.isOnDevice, isTrue);
    });
  });

  group('KnownProviders', () {
    test('cloudProviders does not include onDevice', () {
      final ids = KnownProviders.cloudProviders.map((p) => p.id).toList();
      expect(ids, isNot(contains('on_device')));
    });

    test('allProviders includes onDevice', () {
      final ids = KnownProviders.allProviders.map((p) => p.id).toList();
      expect(ids, contains('on_device'));
    });

    test('all cloud providers have non-empty id and displayName', () {
      for (final p in KnownProviders.cloudProviders) {
        expect(p.id, isNotEmpty);
        expect(p.displayName, isNotEmpty);
      }
    });
  });
}
