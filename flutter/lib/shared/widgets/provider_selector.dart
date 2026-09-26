/// AI provider selector chip row — used in Chat and Settings.
library;

import 'package:ai_assistant_flutter/features/ai_providers/domain/ai_provider_model.dart';
import 'package:flutter/material.dart';

class ProviderSelector extends StatelessWidget {
  const ProviderSelector({
    super.key,
    required this.providers,
    required this.selected,
    required this.onSelected,
  });

  final List<AiProvider> providers;
  final AiProvider selected;
  final void Function(AiProvider) onSelected;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 40,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 16),
        itemCount: providers.length,
        separatorBuilder: (_, __) => const SizedBox(width: 8),
        itemBuilder: (context, i) {
          final provider = providers[i];
          final isSelected = provider.id == selected.id;
          return Semantics(
            label:
                '${provider.displayName} provider${isSelected ? ', selected' : ''}',
            child: ChoiceChip(
              label: Text(provider.displayName),
              selected: isSelected,
              onSelected: (_) => onSelected(provider),
              avatar: provider.icon != null
                  ? Icon(provider.icon, size: 16)
                  : null,
            ),
          );
        },
      ),
    );
  }
}
