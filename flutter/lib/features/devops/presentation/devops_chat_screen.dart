/// DevOps AI Assistant chat screen.
///
/// Unlike the general chat (WebSocket streaming), this screen talks to the
/// REST endpoint POST /devops/chat.  The backend runs its full ReAct
/// tool-calling loop and returns a single complete answer with citations and
/// a list of tools it used.
///
/// UI layout:
///   AppBar (title + clear button)
///   ↓
///   Turn list (question bubble → answer bubble with tool-call badge + citations)
///   ↓
///   Suggestion chip row (pre-built queries)
///   ↓
///   Input bar
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/devops/domain/devops_models.dart';
import 'package:ai_assistant_flutter/features/devops/providers/devops_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/typing_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class DevOpsChatScreen extends ConsumerStatefulWidget {
  const DevOpsChatScreen({super.key});

  @override
  ConsumerState<DevOpsChatScreen> createState() => _DevOpsChatScreenState();
}

class _DevOpsChatScreenState extends ConsumerState<DevOpsChatScreen> {
  final _inputCtrl  = TextEditingController();
  final _scrollCtrl = ScrollController();
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
    super.dispose();
  }

  void _scrollToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scrollCtrl.hasClients) {
        _scrollCtrl.animateTo(
          _scrollCtrl.position.maxScrollExtent,
          duration: const Duration(milliseconds: 300),
          curve: Curves.easeOut,
        );
      }
    });
  }

  Future<void> _send(String text) async {
    if (text.trim().isEmpty) return;
    _inputCtrl.clear();
    setState(() => _canSend = false);
    await ref.read(devOpsChatProvider.notifier).ask(text);
    _scrollToBottom();
  }

  @override
  Widget build(BuildContext context) {
    final chatState = ref.watch(devOpsChatProvider);

    ref.listen(devOpsChatProvider, (_, __) => _scrollToBottom());

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.travel_explore,
                size: 20, color: context.aiAccent,
                semanticLabel: 'DevOps assistant'),
            const SizedBox(width: 8),
            const Text('DevOps Assistant'),
          ],
        ),
        actions: [
          if (!chatState.isEmpty)
            IconButton(
              icon: const Icon(Icons.delete_outline),
              tooltip: 'Clear history',
              onPressed: () => ref
                  .read(devOpsChatProvider.notifier)
                  .clearHistory(),
            ),
        ],
      ),
      body: Column(
        children: [
          // ── Turn list ──────────────────────────────────────────────────
          Expanded(
            child: chatState.isEmpty
                ? EmptyState(
                    icon: Icons.travel_explore,
                    title: 'Ask your DevOps assistant',
                    subtitle:
                        'Ask about incidents, logs, metrics, or deployments.',
                  )
                : ListView.builder(
                    controller: _scrollCtrl,
                    padding: const EdgeInsets.only(top: 8, bottom: 16),
                    itemCount: chatState.turns.length,
                    itemBuilder: (_, i) =>
                        _TurnWidget(turn: chatState.turns[i]),
                  ),
          ),

          // ── Suggestion chips ───────────────────────────────────────────
          if (chatState.isEmpty || !chatState.isAsking)
            _SuggestionRow(onTap: _send),

          // ── Input bar ──────────────────────────────────────────────────
          _DevOpsInputBar(
            controller: _inputCtrl,
            canSend:    _canSend,
            isAsking:   chatState.isAsking,
            onSend:     () => _send(_inputCtrl.text),
          ),
        ],
      ),
    );
  }
}

// ── Turn widget ───────────────────────────────────────────────────────────────

class _TurnWidget extends StatelessWidget {
  const _TurnWidget({required this.turn});
  final DevOpsTurn turn;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          // Question bubble (right-aligned)
          Align(
            alignment: Alignment.centerRight,
            child: Container(
              constraints: BoxConstraints(
                maxWidth: MediaQuery.of(context).size.width * 0.78,
              ),
              padding: const EdgeInsets.symmetric(
                  horizontal: 16, vertical: 12),
              decoration: BoxDecoration(
                color: context.userBubble,
                borderRadius: const BorderRadius.only(
                  topLeft:     Radius.circular(18),
                  topRight:    Radius.circular(4),
                  bottomLeft:  Radius.circular(18),
                  bottomRight: Radius.circular(18),
                ),
              ),
              child: Semantics(
                label: 'You: ${turn.question}',
                child: Text(
                  turn.question,
                  style: const TextStyle(
                      color: Colors.white, fontSize: 15, height: 1.4),
                ),
              ),
            ),
          ),

          const SizedBox(height: 8),

          // Answer bubble (left-aligned)
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Avatar
              Container(
                width: 32,
                height: 32,
                decoration: BoxDecoration(
                  gradient: context.heroGradient,
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.travel_explore,
                    size: 18, color: Colors.white,
                    semanticLabel: 'DevOps AI'),
              ),
              const SizedBox(width: 8),

              // Content
              Expanded(
                child: turn.isLoading
                    ? _LoadingBubble()
                    : turn.hasError
                        ? _ErrorBubble(message: turn.errorMessage!)
                        : _AnswerBubble(turn: turn),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _LoadingBubble extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
      decoration: BoxDecoration(
        color: context.aiBubble,
        borderRadius: const BorderRadius.only(
          topLeft:     Radius.circular(4),
          topRight:    Radius.circular(18),
          bottomLeft:  Radius.circular(18),
          bottomRight: Radius.circular(18),
        ),
        border: Border(left: BorderSide(color: context.aiAccent, width: 3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Running tools…',
            style: TextStyle(
              fontSize: 12,
              color: context.mutedColor,
              fontStyle: FontStyle.italic,
            ),
          ),
          const SizedBox(height: 8),
          const TypingIndicator(),
        ],
      ),
    );
  }
}

class _ErrorBubble extends StatelessWidget {
  const _ErrorBubble({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: context.critical.withAlpha(15),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: context.critical.withAlpha(60)),
      ),
      child: Row(
        children: [
          Icon(Icons.error_outline,
              size: 16, color: context.critical,
              semanticLabel: 'Error'),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style:
                  TextStyle(color: context.critical, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

class _AnswerBubble extends StatelessWidget {
  const _AnswerBubble({required this.turn});
  final DevOpsTurn turn;

  @override
  Widget build(BuildContext context) {
    final resp = turn.response!;

    return Container(
      decoration: BoxDecoration(
        color: context.aiBubble,
        borderRadius: const BorderRadius.only(
          topLeft:     Radius.circular(4),
          topRight:    Radius.circular(18),
          bottomLeft:  Radius.circular(18),
          bottomRight: Radius.circular(18),
        ),
        border: Border(left: BorderSide(color: context.aiAccent, width: 3)),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(10),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            // Answer text (Markdown)
            Semantics(
              label: 'AI: ${resp.answer}',
              child: MarkdownBody(
                data:       resp.answer,
                selectable: true,
                styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context))
                    .copyWith(
                  p: Theme.of(context).textTheme.bodyMedium?.copyWith(height: 1.5),
                  code: TextStyle(
                    fontFamily:      'monospace',
                    fontSize:        13,
                    backgroundColor: context.cardTonal,
                  ),
                  codeblockDecoration: BoxDecoration(
                    color:        context.cardTonal,
                    borderRadius: BorderRadius.circular(8),
                  ),
                ),
              ),
            ),

            // Tool calls used
            if (resp.toolCalls.isNotEmpty) ...[
              const SizedBox(height: 12),
              _ToolCallBadges(toolCalls: resp.toolCalls),
            ],

            // Citations
            if (resp.citations.isNotEmpty) ...[
              const SizedBox(height: 12),
              _Citations(citations: resp.citations),
            ],

            // Footer: provider + rounds
            const SizedBox(height: 10),
            _Footer(response: resp),
          ],
        ),
      ),
    );
  }
}

class _ToolCallBadges extends StatelessWidget {
  const _ToolCallBadges({required this.toolCalls});
  final List<ToolCallSummary> toolCalls;

  @override
  Widget build(BuildContext context) {
    return Wrap(
      spacing: 6,
      runSpacing: 4,
      children: toolCalls
          .map(
            (tc) => Tooltip(
              message: 'Tool: ${tc.toolName}',
              child: Container(
                padding: const EdgeInsets.symmetric(
                    horizontal: 8, vertical: 3),
                decoration: BoxDecoration(
                  color: context.aiAccent.withAlpha(20),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Icon(Icons.build_outlined,
                        size: 12, color: context.aiAccent,
                        semanticLabel: 'Tool call: ${tc.toolName}'),
                    const SizedBox(width: 4),
                    Text(
                      tc.toolName,
                      style: TextStyle(
                        fontSize: 11,
                        color: context.aiAccent,
                        fontWeight: FontWeight.w500,
                      ),
                    ),
                  ],
                ),
              ),
            ),
          )
          .toList(),
    );
  }
}

class _Citations extends StatelessWidget {
  const _Citations({required this.citations});
  final List<String> citations;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(
          '${citations.length} source${citations.length == 1 ? '' : 's'}',
          style: context.texts.labelSmall?.copyWith(color: context.mutedColor),
        ),
        const SizedBox(height: 4),
        ...citations.take(3).map(
              (c) => Padding(
                padding: const EdgeInsets.only(bottom: 2),
                child: GestureDetector(
                  onLongPress: () => Clipboard.setData(
                    ClipboardData(text: c),
                  ),
                  child: Text(
                    '• $c',
                    style: TextStyle(
                      fontSize: 12,
                      color: context.colors.primary,
                      decoration: TextDecoration.underline,
                    ),
                    maxLines: 2,
                    overflow: TextOverflow.ellipsis,
                  ),
                ),
              ),
            ),
      ],
    );
  }
}

class _Footer extends StatelessWidget {
  const _Footer({required this.response});
  final DevOpsChatResponse response;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        Icon(Icons.memory_outlined,
            size: 12, color: context.mutedColor,
            semanticLabel: 'LLM provider'),
        const SizedBox(width: 4),
        Text(
          response.llmProvider,
          style: context.texts.labelSmall
              ?.copyWith(color: context.mutedColor),
        ),
        const SizedBox(width: 12),
        Icon(Icons.loop, size: 12, color: context.mutedColor,
            semanticLabel: 'Tool rounds'),
        const SizedBox(width: 4),
        Text(
          '${response.roundsUsed} round${response.roundsUsed == 1 ? '' : 's'}',
          style: context.texts.labelSmall
              ?.copyWith(color: context.mutedColor),
        ),
      ],
    );
  }
}

// ── Suggestion chips ──────────────────────────────────────────────────────────

class _SuggestionRow extends StatelessWidget {
  const _SuggestionRow({required this.onTap});
  final void Function(String) onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: context.cardTonal,
      padding: const EdgeInsets.symmetric(vertical: 8),
      child: SizedBox(
        height: 36,
        child: ListView.separated(
          scrollDirection:  Axis.horizontal,
          padding: const EdgeInsets.symmetric(horizontal: 12),
          itemCount: DevOpsSuggestions.defaults.length,
          separatorBuilder: (_, __) => const SizedBox(width: 8),
          itemBuilder: (ctx, i) {
            final text = DevOpsSuggestions.defaults[i];
            return ActionChip(
              label: Text(
                text,
                style: const TextStyle(fontSize: 12),
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
              ),
              onPressed: () => onTap(text),
              avatar: Icon(
                Icons.send_outlined,
                size: 14,
                color: ctx.colors.primary,
                semanticLabel: 'Ask: $text',
              ),
            );
          },
        ),
      ),
    );
  }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

class _DevOpsInputBar extends StatelessWidget {
  const _DevOpsInputBar({
    required this.controller,
    required this.canSend,
    required this.isAsking,
    required this.onSend,
  });

  final TextEditingController controller;
  final bool canSend;
  final bool isAsking;
  final VoidCallback onSend;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        decoration: BoxDecoration(
          color: context.cardColor,
          boxShadow: [
            BoxShadow(
              color:  Colors.black.withAlpha(10),
              blurRadius: 8,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: [
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color:        context.cardTonal,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: TextField(
                  controller: controller,
                  maxLines:   4,
                  minLines:   1,
                  enabled:    !isAsking,
                  textInputAction: TextInputAction.newline,
                  decoration: const InputDecoration(
                    hintText:    'Ask about your system…',
                    border:      InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    contentPadding: EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12),
                  ),
                  onSubmitted: canSend && !isAsking ? (_) => onSend() : null,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Semantics(
              button: true,
              label: isAsking ? 'Waiting for response' : 'Send question',
              child: GestureDetector(
                onTap: (canSend && !isAsking) ? onSend : null,
                child: Container(
                  width:  44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: isAsking
                        ? context.mutedColor.withAlpha(30)
                        : canSend
                            ? context.colors.primary.withAlpha(20)
                            : context.mutedColor.withAlpha(20),
                    shape: BoxShape.circle,
                  ),
                  child: isAsking
                      ? Padding(
                          padding: const EdgeInsets.all(12),
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: context.aiAccent,
                          ),
                        )
                      : Icon(
                          Icons.send,
                          size: 22,
                          color: canSend
                              ? context.colors.primary
                              : context.mutedColor,
                        ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
