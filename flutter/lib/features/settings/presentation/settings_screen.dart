/// Settings screen — provider, theme, account, on-device AI.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/constants/app_constants.dart';
import 'package:ai_assistant_flutter/features/ai_providers/domain/ai_provider_model.dart';
import 'package:ai_assistant_flutter/features/ai_providers/providers/ai_provider_provider.dart';
import 'package:ai_assistant_flutter/features/auth/providers/auth_provider.dart';
import 'package:ai_assistant_flutter/features/on_device_ai/domain/on_device_ai_service.dart';
import 'package:ai_assistant_flutter/features/on_device_ai/providers/on_device_ai_provider.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final authState       = ref.watch(authStateProvider).valueOrNull;
    final selectedProvider = ref.watch(selectedProviderProvider);
    final allProviders    = ref.watch(providersListProvider);
    final prefs           = ref.watch(appPreferencesProvider);
    final onDeviceAsync   = ref.watch(onDeviceAiProvider);

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        children: [
          // ── Account ────────────────────────────────────────────────────
          _SectionHeader(label: 'Account'),
          ListTile(
            leading: const Icon(Icons.person_outline),
            title: Text(authState?.user?.email ?? '—'),
            subtitle: Text(
              'Role: ${authState?.user?.role ?? '—'}',
              style: TextStyle(color: context.mutedColor, fontSize: 12),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.logout),
            title: const Text('Sign out'),
            onTap: () async {
              await ref.read(authStateProvider.notifier).logout();
              if (context.mounted) context.go(Routes.login);
            },
          ),
          const Divider(),

          // ── AI Provider ────────────────────────────────────────────────
          _SectionHeader(label: 'AI Provider'),
          ...allProviders.map((provider) {
            final isSelected = provider.id == selectedProvider.id;
            return RadioListTile<AiProvider>(
              value: provider,
              groupValue: selectedProvider,
              title: Text(provider.displayName),
              subtitle: Text(
                provider.description,
                style: TextStyle(color: context.mutedColor, fontSize: 12),
              ),
              secondary: provider.icon != null
                  ? Icon(provider.icon,
                      color: isSelected
                          ? Theme.of(context).colorScheme.primary
                          : context.mutedColor)
                  : null,
              onChanged: (_) => ref
                  .read(selectedProviderProvider.notifier)
                  .select(provider),
            );
          }),
          const Divider(),

          // ── On-Device AI ───────────────────────────────────────────────
          _SectionHeader(label: 'On-Device AI'),
          onDeviceAsync.when(
            loading: () => const ListTile(
              title: Text('Checking device support…'),
              leading: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
            error: (e, _) => ListTile(
              leading: const Icon(Icons.error_outline),
              title: const Text('Could not check device support'),
              subtitle: Text(e.toString()),
            ),
            data: (status) => ListTile(
              leading: Icon(
                Icons.phone_android,
                color: status == OnDeviceAiState.ready
                    ? context.healthy
                    : context.mutedColor,
                semanticLabel: 'On-device AI status',
              ),
              title: Text(_statusLabel(status)),
              subtitle: status == OnDeviceAiState.unsupported
                  ? const Text('This device does not meet requirements')
                  : null,
              trailing: status == OnDeviceAiState.supported
                  ? TextButton(
                      onPressed: () => unawaited(
                          ref.read(onDeviceAiProvider.notifier).loadModel(),
                        ),
                      child: const Text('Load model'),
                    )
                  : null,
            ),
          ),
          const Divider(),

          // ── Appearance ─────────────────────────────────────────────────
          _SectionHeader(label: 'Appearance'),
          ListTile(
            leading: const Icon(Icons.palette_outlined),
            title: const Text('Theme'),
            trailing: DropdownButton<String>(
              value: prefs.themeMode,
              underline: const SizedBox.shrink(),
              items: const [
                DropdownMenuItem(value: 'system', child: Text('System')),
                DropdownMenuItem(value: 'light',  child: Text('Light')),
                DropdownMenuItem(value: 'dark',   child: Text('Dark')),
              ],
              onChanged: (v) async {
                if (v != null) await prefs.setThemeMode(v);
              },
            ),
          ),
          const Divider(),

          // ── About ──────────────────────────────────────────────────────
          _SectionHeader(label: 'About'),
          ListTile(
            leading: const Icon(Icons.info_outline),
            title: const Text('Version'),
            trailing: Text(AppConstants.appVersion,
                style: TextStyle(color: context.mutedColor)),
          ),
          ListTile(
            leading: const Icon(Icons.cloud_outlined),
            title: const Text('API environment'),
            trailing: Text(
              ApiConfig.environment.name.toUpperCase(),
              style: TextStyle(color: context.mutedColor, fontSize: 12),
            ),
          ),
          ListTile(
            leading: const Icon(Icons.link),
            title: const Text('Backend URL'),
            subtitle: Text(
              ApiConfig.baseUrl,
              style: TextStyle(color: context.mutedColor, fontSize: 12),
            ),
          ),
          const SizedBox(height: 32),
        ],
      ),
    );
  }

  String _statusLabel(OnDeviceAiState status) => switch (status) {
        OnDeviceAiState.checking     => 'Checking…',
        OnDeviceAiState.unsupported  => 'Not supported',
        OnDeviceAiState.supported    => 'Model not loaded',
        OnDeviceAiState.loadingModel => 'Loading model…',
        OnDeviceAiState.ready        => 'Ready',
        OnDeviceAiState.running      => 'Running',
        OnDeviceAiState.error        => 'Error',
      };
}

class _SectionHeader extends StatelessWidget {
  const _SectionHeader({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        label.toUpperCase(),
        style: context.texts.labelSmall?.copyWith(
          color: context.mutedColor,
          letterSpacing: 1.2,
        ),
      ),
    );
  }
}
