/// Horizontally scrolling row of suggestion chips shown above the chat input.
///
/// Tapping a chip fills the input bar with the suggested text.
/// The row collapses to zero height once the user starts a conversation
/// so it doesn't distract from the message flow.
library;

import 'package:flutter/material.dart';

class SuggestionChipRow extends StatelessWidget {
  const SuggestionChipRow({
    super.key,
    required this.suggestions,
    required this.onTap,
    this.visible = true,
  });

  final List<String> suggestions;
  final void Function(String suggestion) onTap;

  /// When false the widget renders as zero height (AnimatedSize handles the
  /// transition so there is no jump).
  final bool visible;

  @override
  Widget build(BuildContext context) {
    return AnimatedSize(
      duration: const Duration(milliseconds: 250),
      curve: Curves.easeInOut,
      child: visible && suggestions.isNotEmpty
          ? Container(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              padding: const EdgeInsets.symmetric(vertical: 6),
              child: SizedBox(
                height: 36,
                child: ListView.separated(
                  scrollDirection: Axis.horizontal,
                  padding:
                      const EdgeInsets.symmetric(horizontal: 12),
                  itemCount: suggestions.length,
                  separatorBuilder: (_, __) =>
                      const SizedBox(width: 8),
                  itemBuilder: (ctx, i) {
                    final text = suggestions[i];
                    return Semantics(
                      button: true,
                      label: 'Suggestion: $text',
                      child: ActionChip(
                        label: Text(
                          text,
                          style: const TextStyle(fontSize: 12),
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                        onPressed: () => onTap(text),
                        visualDensity: VisualDensity.compact,
                        padding: EdgeInsets.zero,
                      ),
                    );
                  },
                ),
              ),
            )
          : const SizedBox.shrink(),
    );
  }
}

/// Default suggestions shown in the general AI assistant chat.
class ChatSuggestions {
  ChatSuggestions._();

  static const List<String> defaults = [
    'Summarise recent errors',
    'What caused the last incident?',
    'Show me today\'s anomalies',
    'Explain the current error',
    'What should I investigate first?',
  ];
}
