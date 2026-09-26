/// Conversation list screen — shows all past chats.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/core/utils/date_formatter.dart';
import 'package:ai_assistant_flutter/features/conversations/data/conversations_api.dart';
import 'package:ai_assistant_flutter/features/conversations/domain/conversation_model.dart';
import 'package:ai_assistant_flutter/features/conversations/providers/conversations_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class ConversationsScreen extends ConsumerWidget {
  const ConversationsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final conversationsAsync = ref.watch(conversationsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Chats'),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () =>
                ref.read(conversationsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: conversationsAsync.when(
        loading: () => const LoadingIndicator(message: 'Loading conversations…'),
        error: (e, _) => ErrorView(
          message: e.toString(),
          onRetry: () => ref.read(conversationsProvider.notifier).refresh(),
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
            onRefresh: () =>
                ref.read(conversationsProvider.notifier).refresh(),
            child: ListView.separated(
              padding: const EdgeInsets.symmetric(vertical: 8),
              itemCount: conversations.length,
              separatorBuilder: (_, __) => const Divider(height: 1, indent: 72),
              itemBuilder: (context, i) =>
                  _ConversationTile(conversation: conversations[i]),
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
  const _ConversationTile({required this.conversation});
  final Conversation conversation;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Dismissible(
      key: ValueKey(conversation.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: context.critical,
        child: const Icon(Icons.delete, color: Colors.white,
            semanticLabel: 'Delete conversation'),
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
          DateFormatter.relative(conversation.updatedAt ?? conversation.createdAt),
          style: context.texts.labelSmall?.copyWith(color: context.mutedColor),
        ),
        onTap: () => context.push(Routes.chatPath(conversation.id)),
        onLongPress: () => _showExportSheet(context, ref),
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
    final api    = ref.read(conversationsApiProvider);
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
