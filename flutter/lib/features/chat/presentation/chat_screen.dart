/// Production-quality AI chat screen with WebSocket streaming.
///
/// Features: streaming, stop, retry, copy, regenerate, markdown,
/// code blocks, typing indicator, empty state, error state,
/// auto-scroll to latest, keyboard handling.
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/ai_providers/domain/ai_provider_model.dart';
import 'package:ai_assistant_flutter/features/ai_providers/providers/ai_provider_provider.dart';
import 'package:ai_assistant_flutter/features/chat/domain/chat_state.dart';
import 'package:ai_assistant_flutter/features/chat/providers/chat_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/message_bubble.dart';
import 'package:ai_assistant_flutter/shared/widgets/provider_selector.dart';
import 'package:ai_assistant_flutter/shared/widgets/suggestion_chip_row.dart';
import 'package:ai_assistant_flutter/shared/widgets/typing_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class ChatScreen extends ConsumerStatefulWidget {
  const ChatScreen({super.key, required this.conversationId});
  final String conversationId;

  @override
  ConsumerState<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends ConsumerState<ChatScreen> {
  final _inputCtrl   = TextEditingController();
  final _scrollCtrl  = ScrollController();
  final _inputFocus  = FocusNode();
  bool _canSend = false;

  @override
  void initState() {
    super.initState();
    _inputCtrl.addListener(() {
      final canSend = _inputCtrl.text.trim().isNotEmpty;
      if (canSend != _canSend) setState(() => _canSend = canSend);
    });
  }

  @override
  void dispose() {
    _inputCtrl.dispose();
    _scrollCtrl.dispose();
    _inputFocus.dispose();
    super.dispose();
  }

  void _scrollToBottom({bool animate = true}) {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scrollCtrl.hasClients) return;
      final target = _scrollCtrl.position.maxScrollExtent;
      if (animate) {
        _scrollCtrl.animateTo(
          target,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      } else {
        _scrollCtrl.jumpTo(target);
      }
    });
  }

  Future<void> _sendSuggestion(String text) async {
    _inputCtrl.text = text;
    await _send();
  }

  Future<void> _send() async {
    final text = _inputCtrl.text.trim();
    if (text.isEmpty) return;
    _inputCtrl.clear();
    setState(() => _canSend = false);
    await ref.read(chatProvider(widget.conversationId).notifier).sendMessage(text);
    _scrollToBottom();
  }

  @override
  Widget build(BuildContext context) {
    final chatState    = ref.watch(chatProvider(widget.conversationId));
    final providers    = ref.watch(providersListProvider);
    final selected     = ref.watch(selectedProviderProvider);
    final isStreaming  = chatState.isStreaming;
    final isConnecting = chatState.isConnecting;

    // Auto-scroll when new messages arrive.
    ref.listen(chatProvider(widget.conversationId), (_, next) {
      if (next.messages.isNotEmpty) _scrollToBottom();
    });

    return Scaffold(
      appBar: AppBar(
        title: Text(
          chatState.conversationId != null ? 'Chat' : 'New chat',
          style: context.texts.titleMedium,
        ),
        actions: [
          if (isConnecting)
            const Padding(
              padding: EdgeInsets.only(right: 16),
              child: SizedBox(
                width: 20,
                height: 20,
                child: CircularProgressIndicator(strokeWidth: 2),
              ),
            ),
        ],
      ),
      body: Column(
        children: [
          // ── Provider selector ──────────────────────────────────────────
          _ProviderBar(
            providers: providers,
            selected: selected,
            onSelected: (p) =>
                ref.read(selectedProviderProvider.notifier).select(p),
          ),

          // ── Error banner ───────────────────────────────────────────────
          if (chatState.connectionError != null)
            _ErrorBanner(
              message: chatState.connectionError!,
              onRetry: chatState.connectionError!.contains('offline')
                  ? () => ref
                      .read(chatProvider(widget.conversationId).notifier)
                      .retryPendingMessages()
                  : null,
            ),

          // ── Suggestion chips (hidden once conversation starts) ──────────
          SuggestionChipRow(
            suggestions: ChatSuggestions.defaults,
            visible:     chatState.messages.isEmpty && !isStreaming,
            onTap:       _sendSuggestion,
          ),

          // ── Message list ───────────────────────────────────────────────
          Expanded(
            child: chatState.messages.isEmpty
                ? EmptyState(
                    icon: Icons.auto_awesome,
                    title: 'Ask me anything',
                    subtitle: 'Your AI assistant is ready.',
                  )
                : ListView.builder(
                    controller: _scrollCtrl,
                    padding: const EdgeInsets.only(top: 8, bottom: 16),
                    itemCount: chatState.messages.length,
                    itemBuilder: (context, i) {
                      final msg = chatState.messages[i];
                      return MessageBubble(
                        role: msg.isUser
                            ? BubbleRole.user
                            : BubbleRole.assistant,
                        content: msg.content,
                        isStreaming: msg.isStreaming,
                        timestamp: msg.createdAt,
                        onRegenerate: !msg.isUser
                            ? () => ref
                                .read(chatProvider(widget.conversationId)
                                    .notifier)
                                .stopGeneration()
                            : null,
                      );
                    },
                  ),
          ),

          // ── Typing indicator ───────────────────────────────────────────
          if (isStreaming)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 4),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Semantics(
                  label: 'AI is generating a response',
                  child: const TypingIndicator(),
                ),
              ),
            ),

          // ── Input bar ──────────────────────────────────────────────────
          _InputBar(
            controller: _inputCtrl,
            focusNode: _inputFocus,
            canSend: _canSend,
            isStreaming: isStreaming,
            onSend: _send,
            onStop: () => ref
                .read(chatProvider(widget.conversationId).notifier)
                .stopGeneration(),
          ),
        ],
      ),
    );
  }
}

// ── Provider bar ──────────────────────────────────────────────────────────────

class _ProviderBar extends StatelessWidget {
  const _ProviderBar({
    required this.providers,
    required this.selected,
    required this.onSelected,
  });

  final List<AiProvider> providers;
  final AiProvider selected;
  final void Function(AiProvider) onSelected;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: context.cardTonal,
      child: ProviderSelector(
        providers: providers,
        selected: selected,
        onSelected: onSelected,
      ),
    );
  }
}

// ── Error banner ──────────────────────────────────────────────────────────────

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message, this.onRetry});
  final String message;
  final VoidCallback? onRetry;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: context.critical.withAlpha(20),
      child: Row(
        children: [
          Icon(Icons.error_outline, size: 18, color: context.critical,
              semanticLabel: 'Error'),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: context.critical, fontSize: 13),
            ),
          ),
          if (onRetry != null)
            TextButton(
              onPressed: onRetry,
              style: TextButton.styleFrom(
                foregroundColor: context.critical,
                padding: EdgeInsets.zero,
                minimumSize: const Size(60, 32),
              ),
              child: const Text('Retry', style: TextStyle(fontSize: 12)),
            ),
        ],
      ),
    );
  }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

class _InputBar extends StatelessWidget {
  const _InputBar({
    required this.controller,
    required this.focusNode,
    required this.canSend,
    required this.isStreaming,
    required this.onSend,
    required this.onStop,
  });

  final TextEditingController controller;
  final FocusNode focusNode;
  final bool canSend;
  final bool isStreaming;
  final VoidCallback onSend;
  final Future<void> Function() onStop;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        decoration: BoxDecoration(
          color: context.cardColor,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withAlpha(10),
              blurRadius: 8,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            // ── Text input ────────────────────────────────────────────────
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color: context.cardTonal,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: TextField(
                  controller: controller,
                  focusNode: focusNode,
                  maxLines: 5,
                  minLines: 1,
                  maxLength: 4000,
                  buildCounter: (_, {required currentLength,
                      required isFocused, required maxLength}) => null,
                  textInputAction: TextInputAction.newline,
                  keyboardType: TextInputType.multiline,
                  decoration: InputDecoration(
                    hintText: 'Ask anything…',
                    border: InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    contentPadding: const EdgeInsets.symmetric(
                      horizontal: 16,
                      vertical: 12,
                    ),
                  ),
                  onSubmitted: canSend && !isStreaming ? (_) => onSend() : null,
                ),
              ),
            ),
            const SizedBox(width: 8),

            // ── Send / Stop button ─────────────────────────────────────────
            Semantics(
              button: true,
              label: isStreaming ? 'Stop generation' : 'Send message',
              child: AnimatedSwitcher(
                duration: const Duration(milliseconds: 200),
                child: isStreaming
                    ? _IconCircle(
                        key: const ValueKey('stop'),
                        icon: Icons.stop,
                        color: context.critical,
                        onTap: onStop,
                      )
                    : _IconCircle(
                        key: const ValueKey('send'),
                        icon: Icons.send,
                        color: canSend
                            ? Theme.of(context).colorScheme.primary
                            : context.mutedColor,
                        onTap: canSend ? onSend : null,
                      ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _IconCircle extends StatelessWidget {
  const _IconCircle({
    super.key,
    required this.icon,
    required this.color,
    this.onTap,
  });
  final IconData icon;
  final Color color;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        width: 44,
        height: 44,
        decoration: BoxDecoration(
          color: color.withAlpha(20),
          shape: BoxShape.circle,
        ),
        child: Icon(icon, color: color, size: 22),
      ),
    );
  }
}
