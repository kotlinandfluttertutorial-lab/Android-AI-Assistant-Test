/// Documents screen — list uploaded documents, upload new ones, delete.
///
/// Each document shows:
///   filename · size · chunk count · ingestion status
///
/// Upload flow:
///   Tap FAB → FilePicker (PDF/DOCX/TXT/MD)
///   → POST /documents (multipart)
///   → Poll /jobs/{id} every 3s
///   → Show progress → document appears in list when completed
///
/// Note: file_picker is NOT added as a dependency to keep things minimal.
/// The upload button is wired to a stub that shows the user where to hook
/// a file picker. Instructions are documented inline.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/router/app_router.dart';
import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/rag/domain/rag_models.dart';
import 'package:ai_assistant_flutter/features/rag/providers/rag_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

class DocumentsScreen extends ConsumerWidget {
  const DocumentsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final docsAsync = ref.watch(documentsProvider);
    final uploadState = ref.watch(uploadProvider);

    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            Icon(Icons.folder_open_outlined,
                size: 20, color: context.colors.primary,
                semanticLabel: 'Documents'),
            const SizedBox(width: 8),
            const Text('Documents'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.search),
            tooltip: 'Query documents',
            onPressed: () => context.push(Routes.ragQuery),
          ),
          IconButton(
            icon: const Icon(Icons.refresh),
            tooltip: 'Refresh',
            onPressed: () =>
                ref.read(documentsProvider.notifier).refresh(),
          ),
        ],
      ),
      body: Column(
        children: [
          // ── Upload progress banner ─────────────────────────────────────
          if (uploadState.isUploading || uploadState.isPolling)
            _UploadBanner(state: uploadState),

          if (uploadState.hasFailed)
            _ErrorBanner(message: uploadState.error ?? 'Upload failed'),

          // ── Documents list ─────────────────────────────────────────────
          Expanded(
            child: docsAsync.when(
              loading: () => const LoadingIndicator(
                  message: 'Loading documents…'),
              error: (e, _) => ErrorView(
                message: e.toString(),
                onRetry: () =>
                    ref.read(documentsProvider.notifier).refresh(),
              ),
              data: (docs) {
                if (docs.isEmpty) {
                  return EmptyState(
                    icon: Icons.upload_file,
                    title: 'No documents yet',
                    subtitle:
                        'Upload a PDF, DOCX, TXT, or Markdown file to get started.',
                    actionLabel: 'Upload',
                    onAction: () => _showUploadDialog(context, ref),
                  );
                }
                return RefreshIndicator(
                  onRefresh: () =>
                      ref.read(documentsProvider.notifier).refresh(),
                  child: ListView.separated(
                    padding: const EdgeInsets.symmetric(vertical: 8),
                    itemCount: docs.length,
                    separatorBuilder: (_, __) =>
                        const Divider(height: 1, indent: 72),
                    itemBuilder: (ctx, i) =>
                        _DocumentTile(document: docs[i]),
                  ),
                );
              },
            ),
          ),
        ],
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _showUploadDialog(context, ref),
        icon: const Icon(Icons.upload_file),
        label: const Text('Upload'),
      ),
    );
  }

  /// Shows a dialog explaining how to connect a real file picker.
  ///
  /// To add real file-picking, install `file_picker: ^8.x.x` from pub.dev,
  /// then replace this dialog body with:
  ///
  /// ```dart
  /// final result = await FilePicker.platform.pickFiles(
  ///   type: FileType.custom,
  ///   allowedExtensions: ['pdf', 'docx', 'txt', 'md'],
  /// );
  /// if (result != null) {
  ///   final f = result.files.single;
  ///   await ref.read(uploadProvider.notifier).upload(
  ///     filePath: f.path!,
  ///     filename: f.name,
  ///     mimeType: _mimeType(f.extension),
  ///   );
  /// }
  /// ```
  void _showUploadDialog(BuildContext context, WidgetRef ref) {
    showDialog<void>(
      context: context,
      builder: (_) => AlertDialog(
        title: const Text('Upload Document'),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Add file_picker to pubspec.yaml to enable document upload:',
            ),
            SizedBox(height: 12),
            SelectableText(
              '  file_picker: ^8.1.2',
              style: TextStyle(fontFamily: 'monospace', fontSize: 13),
            ),
            SizedBox(height: 12),
            Text(
              'Then call uploadProvider.notifier.upload() with the selected file path.',
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('OK'),
          ),
        ],
      ),
    );
  }
}

// ── Upload progress banner ─────────────────────────────────────────────────────

class _UploadBanner extends StatelessWidget {
  const _UploadBanner({required this.state});
  final UploadState state;

  String get _label {
    if (state.isUploading) return 'Uploading…';
    return switch (state.jobStatus) {
      IngestJobStatus.queued => 'Queued for processing…',
      IngestJobStatus.running => 'Indexing document…',
      IngestJobStatus.completed => 'Ingestion complete',
      IngestJobStatus.failed => 'Ingestion failed',
      null => 'Processing…',
    };
  }

  @override
  Widget build(BuildContext context) {
    final isDone = state.jobStatus == IngestJobStatus.completed;
    final color = isDone ? context.healthy : context.colors.primary;
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: color.withAlpha(15),
      child: Row(
        children: [
          if (!isDone)
            SizedBox(
              width: 16,
              height: 16,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                color: color,
              ),
            )
          else
            Icon(Icons.check_circle, size: 16, color: color,
                semanticLabel: 'Complete'),
          const SizedBox(width: 10),
          Text(_label, style: TextStyle(color: color, fontSize: 13)),
        ],
      ),
    );
  }
}

class _ErrorBanner extends StatelessWidget {
  const _ErrorBanner({required this.message});
  final String message;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
      color: context.critical.withAlpha(18),
      child: Row(
        children: [
          Icon(Icons.error_outline, size: 16, color: context.critical,
              semanticLabel: 'Upload error'),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              message,
              style: TextStyle(color: context.critical, fontSize: 13),
            ),
          ),
        ],
      ),
    );
  }
}

// ── Document tile ─────────────────────────────────────────────────────────────

class _DocumentTile extends ConsumerWidget {
  const _DocumentTile({required this.document});
  final RagDocument document;

  IconData _icon() {
    final ext = document.filename.split('.').last.toLowerCase();
    return switch (ext) {
      'pdf' => Icons.picture_as_pdf_outlined,
      'docx' => Icons.description_outlined,
      'md' => Icons.article_outlined,
      _ => Icons.text_snippet_outlined,
    };
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return Dismissible(
      key: ValueKey(document.id),
      direction: DismissDirection.endToStart,
      background: Container(
        alignment: Alignment.centerRight,
        padding: const EdgeInsets.only(right: 24),
        color: context.critical,
        child: const Icon(Icons.delete, color: Colors.white,
            semanticLabel: 'Delete document'),
      ),
      confirmDismiss: (_) => showDialog<bool>(
        context: context,
        builder: (ctx) => AlertDialog(
          title: const Text('Delete document?'),
          content:
              const Text('This removes the document and all its indexed chunks.'),
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
      onDismissed: (_) => unawaited(
        ref.read(documentsProvider.notifier).delete(document.id),
      ),
      child: ListTile(
        leading: CircleAvatar(
          backgroundColor:
              context.colors.primary.withAlpha(15),
          child: Icon(_icon(), size: 20, color: context.colors.primary,
              semanticLabel: document.filename),
        ),
        title: Text(
          document.filename,
          maxLines: 1,
          overflow: TextOverflow.ellipsis,
        ),
        subtitle: Text(
          [
            if (document.displaySize.isNotEmpty) document.displaySize,
            if (document.chunkCount != null)
              '${document.chunkCount} chunks',
          ].join(' · '),
          style: context.texts.bodySmall
              ?.copyWith(color: context.mutedColor),
        ),
        trailing: IconButton(
          icon: const Icon(Icons.send_outlined, size: 18),
          tooltip: 'Query this document',
          onPressed: () => context.push(
            Routes.ragQuery,
            extra: document.id,
          ),
        ),
        onTap: () =>
            context.push(Routes.ragQuery, extra: document.id),
      ),
    );
  }
}
