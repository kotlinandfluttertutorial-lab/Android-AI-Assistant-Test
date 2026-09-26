/// Chat message bubble — user (right) and AI (left) variants.
///
/// AI messages render Markdown. User messages are plain text.
/// Long-press shows a copy action.
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';

enum BubbleRole { user, assistant }

class MessageBubble extends StatelessWidget {
  const MessageBubble({
    super.key,
    required this.role,
    required this.content,
    this.timestamp,
    this.isStreaming = false,
    this.onCopy,
    this.onRegenerate,
  });

  final BubbleRole role;
  final String content;
  final DateTime? timestamp;

  /// True while the assistant is still streaming this message.
  final bool isStreaming;
  final VoidCallback? onCopy;
  final VoidCallback? onRegenerate;

  bool get _isUser => role == BubbleRole.user;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
      child: Row(
        mainAxisAlignment:
            _isUser ? MainAxisAlignment.end : MainAxisAlignment.start,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (!_isUser) _AiAvatar(),
          const SizedBox(width: 8),
          Flexible(
            child: GestureDetector(
              onLongPress: () => _showActions(context),
              child: _isUser ? _UserBubble(content: content) : _AiBubble(
                content: content,
                isStreaming: isStreaming,
              ),
            ),
          ),
          if (_isUser) const SizedBox(width: 8),
        ],
      ),
    );
  }

  void _showActions(BuildContext context) {
    showModalBottomSheet<void>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.copy),
              title: const Text('Copy'),
              onTap: () {
                Navigator.pop(context);
                Clipboard.setData(ClipboardData(text: content));
                ScaffoldMessenger.of(context).showSnackBar(
                  const SnackBar(content: Text('Copied to clipboard')),
                );
              },
            ),
            if (!_isUser && onRegenerate != null)
              ListTile(
                leading: const Icon(Icons.refresh),
                title: const Text('Regenerate'),
                onTap: () {
                  Navigator.pop(context);
                  onRegenerate?.call();
                },
              ),
          ],
        ),
      ),
    );
  }
}

class _AiAvatar extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      width: 32,
      height: 32,
      decoration: BoxDecoration(
        gradient: context.heroGradient,
        shape: BoxShape.circle,
      ),
      child: const Icon(
        Icons.auto_awesome,
        size: 18,
        color: Colors.white,
        semanticLabel: 'AI assistant',
      ),
    );
  }
}

class _UserBubble extends StatelessWidget {
  const _UserBubble({required this.content});
  final String content;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      decoration: BoxDecoration(
        color: context.userBubble,
        borderRadius: const BorderRadius.only(
          topLeft: Radius.circular(18),
          topRight: Radius.circular(4),
          bottomLeft: Radius.circular(18),
          bottomRight: Radius.circular(18),
        ),
      ),
      child: Semantics(
        label: 'You: $content',
        child: Text(
          content,
          style: const TextStyle(color: Colors.white, fontSize: 15, height: 1.4),
        ),
      ),
    );
  }
}

class _AiBubble extends StatelessWidget {
  const _AiBubble({required this.content, required this.isStreaming});
  final String content;
  final bool isStreaming;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: context.aiBubble,
        borderRadius: const BorderRadius.only(
          topLeft: Radius.circular(4),
          topRight: Radius.circular(18),
          bottomLeft: Radius.circular(18),
          bottomRight: Radius.circular(18),
        ),
        border: Border(
          left: BorderSide(color: context.aiAccent, width: 3),
        ),
        boxShadow: [
          BoxShadow(
            color: Colors.black.withAlpha(10),
            blurRadius: 4,
            offset: const Offset(0, 2),
          ),
        ],
      ),
      child: Semantics(
        label: 'AI: $content',
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
          child: isStreaming && content.isEmpty
              ? const SizedBox(
                  width: 40,
                  height: 20,
                  child: _ThreeDots(),
                )
              : MarkdownBody(
                  data: content,
                  styleSheet: MarkdownStyleSheet.fromTheme(Theme.of(context))
                      .copyWith(
                    p: Theme.of(context)
                        .textTheme
                        .bodyMedium
                        ?.copyWith(height: 1.5),
                    code: TextStyle(
                      fontFamily: 'monospace',
                      fontSize: 13,
                      backgroundColor: context.cardTonal,
                    ),
                    codeblockDecoration: BoxDecoration(
                      color: context.cardTonal,
                      borderRadius: BorderRadius.circular(8),
                    ),
                  ),
                  selectable: true,
                ),
        ),
      ),
    );
  }
}

class _ThreeDots extends StatefulWidget {
  const _ThreeDots();
  @override
  State<_ThreeDots> createState() => _ThreeDotsState();
}

class _ThreeDotsState extends State<_ThreeDots>
    with SingleTickerProviderStateMixin {
  late AnimationController _ctrl;

  @override
  void initState() {
    super.initState();
    _ctrl = AnimationController(
      vsync: this,
      duration: const Duration(milliseconds: 1000),
    )..repeat();
  }

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _ctrl,
      builder: (_, __) {
        const dots = '...';
        final count = (_ctrl.value * 3).floor() + 1;
        return Text(
          dots.substring(0, count),
          style: TextStyle(
            color: context.aiAccent,
            fontSize: 20,
            fontWeight: FontWeight.bold,
          ),
        );
      },
    );
  }
}
