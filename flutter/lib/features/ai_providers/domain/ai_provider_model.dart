/// AI provider domain model — decouples UI from the raw backend string values.
///
/// The backend accepts: openai | gemini | claude | ollama | llama | mistral
library;

import 'package:flutter/material.dart';

enum AiProviderStatus {
  /// Provider availability is unknown (initial state).
  loading,
  /// Provider is available and ready.
  available,
  /// Provider is not configured (no API key).
  unavailable,
  /// An error occurred checking provider status.
  error,
}

/// Immutable descriptor for one AI provider.
class AiProvider {
  const AiProvider({
    required this.id,
    required this.displayName,
    this.description = '',
    this.icon,
    this.isOnDevice = false,
    this.status = AiProviderStatus.available,
  });

  /// Backend provider string (e.g. 'gemini', 'openai').
  final String id;
  final String displayName;
  final String description;
  final IconData? icon;
  final bool isOnDevice;
  final AiProviderStatus status;

  AiProvider copyWith({AiProviderStatus? status}) => AiProvider(
        id: id,
        displayName: displayName,
        description: description,
        icon: icon,
        isOnDevice: isOnDevice,
        status: status ?? this.status,
      );

  @override
  String toString() => 'AiProvider($id, $status)';
}

/// The canonical list of supported providers.
class KnownProviders {
  KnownProviders._();

  static const gemini = AiProvider(
    id: 'gemini',
    displayName: 'Gemini',
    description: 'Google Gemini — cloud inference',
    icon: Icons.auto_awesome,
  );

  static const openai = AiProvider(
    id: 'openai',
    displayName: 'GPT-4o',
    description: 'OpenAI GPT-4o — cloud inference',
    icon: Icons.psychology,
  );

  static const claude = AiProvider(
    id: 'claude',
    displayName: 'Claude',
    description: 'Anthropic Claude — cloud inference',
    icon: Icons.smart_toy_outlined,
  );

  static const ollama = AiProvider(
    id: 'ollama',
    displayName: 'Ollama',
    description: 'Ollama — local inference server',
    icon: Icons.computer,
  );

  static const onDevice = AiProvider(
    id: 'on_device',
    displayName: 'On-Device',
    description: 'Local inference — no network required',
    icon: Icons.phone_android,
    isOnDevice: true,
  );

  static const List<AiProvider> cloudProviders = [gemini, openai, claude, ollama];
  static const List<AiProvider> allProviders   = [gemini, openai, claude, ollama, onDevice];
}
