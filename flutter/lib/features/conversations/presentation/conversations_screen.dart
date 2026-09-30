/// Conversation list screen — shows all past chats.
///
/// On tablet (≥ 700dp): two-pane layout — list on the left, chat on the right.
/// On phone:            single-pane list; tapping navigates to ChatScreen.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/utils/date_formatter.dart';
import 'package:ai_assistant_flutter/features/chat/presentation/chat_screen.dart';
import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:ai_assistant_flutter/features/conversations/providers/conversations_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

/// Minimum width to enable the two-pane layout (dp).
const double _twoPaneBreakpoint = 700;

class ConversationsScreen extends ConsumerStatefulWidget {
  const ConversationsScreen({super.key});

  @override
  ConsumerState<ConversationsScreen> createState() =>
      _ConversationsScreenState();
}

class _ConversationsScreenState extends ConsumerState<ConversationsScreen> {
  /// The conversation open in the detail pane (tablet only).
  String? _selectedConversationId;

  void _openConversation(BuildContext context, String id) {
    final isTablet =
        MediaQuery.of(context).size.width >= _twoPaneBreakpoint;
    if (isTablet) {
      setState(() => _selectedConversationId = id);
    } else {
      context.push(Routes.chatPath(id));
    }
  }

  @override
  Widget build(BuildContext context) {
    final conversationsAsync = ref.watch(conversationsProvider);
    final isTablet =
        MediaQuery.of(context).size.width >= _twoPaneBreakpoint;

    final listPane = _ConversationListPane(
      conversationsAsync: conversationsAsync,
      selectedId: _selectedConversationId,
      onOpen: (id) => _openConversation(context, id),
      onRefresh: () => ref.read(conversationsProvider.notifier).refresh(),
    );

    if (!isTablet) {
      return listPane;
    }

    // ── Two-pane tablet layout ─────────────────────────────────────────────
    return Scaffold(
      body: Row(
        children: [
          SizedBox(
            width: 360,
            child: listPane,
          ),
          const VerticalDivider(width: 1, thickness: 1),
          Expanded(
            child: _selectedConversationId != null
                ? ChatScreen(
                    conversationId: _selectedConversationId!,
                    showBackButton: false,
                  )
                : Center(
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        Icon(
                          Icons.chat_bubble_outline,
                          size: 64,
                          color: context.mutedColor,
                          semanticLabel: 'Select a chat',
                        ),
                        const SizedBox(height: 16),
                        Text(
                          'Select a conversation',
                          style: context.texts.titleSmall
                              ?.copyWith(color: context.mutedColor),
                        ),
                      ],
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

// ── Conversation list pane ────────────────────────────────────────────────────

class _ConversationListPane extends ConsumerWidget {
  const _ConversationListPane({
    required this.conversationsAsync,
    required this.selectedId,
    required this.onOpen,
    required this.onRefresh,
  });

  final AsyncValue<List<Conversation>> conversationsAsync;
  final String? selectedId;
  final void Function(String id) onOpen;
  final Future<void> Function() onRefresh;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Chats'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: onRefresh,
          ),
        ],
      ),
      body: conversationsAsync.when(
        loading: () =>
            const LoadingIndicator(message: 'Loading conversations…'),
        error: (e, _) => ErrorView(
          message: e.toString(),
          onRetry: onRefresh,
        ),
        data: (conversations) {
          if (conversations.isEmpty) {
            return EmptyState(
              icon: Icons.chat_bubble_outline,
              title: 'No conversations yet',
              subtitle: 'Start a new chat to get going.',
              actionLabel: 'New chat',
              onAction: () => context.push(Routes.newChat),
            );
          }
          return RefreshIndicator(
            onRefresh: onRefresh,
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: conversations.length,
              separatorBuilder: (_, __) =>
                  const Divider(height: 1, indent: 72),
              itemBuilder: (ctx, i) => _ConversationTile(
                conversation: conversations[i],
                isSelected: conversations[i].id == selectedId,
                onTap: () => onOpen(conversations[i].id),
              ),
            ),
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => context.push(Routes.newChat),
        icon: const Icon(Icons.add),
        label: const Text('New chat'),
      ),
    );
  }
}

class _ConversationTile extends ConsumerWidget {
  const _ConversationTile({
    required this.conversation,
    this.isSelected = false,
    this.onTap,
  });
  final Conversation conversation;
  final bool isSelected;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Dismissible(
      key: ValueKey(conversation.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: context.critical,
        child: const Icon(Icons.delete,
            color: Colors.white, semanticLabel: 'Delete conversation'),
      ),
      confirmDismiss: (_) => showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Delete conversation?'),
          content: const Text('This cannot be undone.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.pop(ctx, false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.pop(ctx, true),
              child: Text('Delete',
                  style: TextStyle(color: context.critical)),
            ),
          ],
        ),
      ),
      onDismissed: (_) => unawaited(ref
          .read(conversationsProvider.notifier)
          .deleteConversation(conversation.id)),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor:
              Theme.of(context).colorScheme.primary.withAlpha(20),
          child: const Icon(Icons.chat_bubble_outline, size: 20),
        ),
        title: Text(
          conversation.title,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
          style: context.texts.titleSmall,
        ),
        subtitle: conversation.lastMessage != null
            ? Text(
                conversation.lastMessage!,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: context.texts.bodySmall,
              )
            : null,
        trailing: Text(
          DateFormatter.relative(
              conversation.updatedAt ?? conversation.createdAt),
          style:
              context.texts.labelSmall?.copyWith(color: context.mutedColor),
        ),
        onTap: () {
          if (onTap != null) {
            onTap!();
          } else {
            context.push(Routes.chatPath(conversation.id));
          }
        },
        onLongPress: () => _showExportSheet(context, ref),
        selected: isSelected,
        selectedTileColor:
            Theme.of(context).colorScheme.primary.withAlpha(12),
      ),
    );
  }

  void _showExportSheet(BuildContext context, WidgetRef ref) {
    showModalBottomSheet<void>(
      context: context,
      builder: (_) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            ListTile(
              leading: const Icon(Icons.download_outlined),
              title: const Text('Export as Markdown'),
              onTap: () {
                Navigator.pop(context);
                unawaited(_export(context, ref, ExportFormat.markdown));
              },
            ),
            ListTile(
              leading: const Icon(Icons.picture_as_pdf_outlined),
              title: const Text('Export as PDF'),
              onTap: () {
                Navigator.pop(context);
                unawaited(_export(context, ref, ExportFormat.pdf));
              },
            ),
          ],
        ),
      ),
    );
  }

  Future<void> _export(
    BuildContext context,
    WidgetRef ref,
    ExportFormat format,
  ) async {
    final api = ref.read(conversationsApiProvider);
    final result = await api.exportConversation(
      conversation.id,
      format: format,
    );

    if (!context.mounted) return;

    result.when(
      onSuccess: (export) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Exported "${export.filename}" '
                '(${export.bytes.length} bytes)'),
            action: SnackBarAction(
              label: 'OK',
              onPressed: () {},
            ),
          ),
        );
      },
      onFailure: (err) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Export failed: ${err.userMessage}'),
            backgroundColor: Theme.of(context).colorScheme.error,
          ),
        );
      },
    );
  }
}
