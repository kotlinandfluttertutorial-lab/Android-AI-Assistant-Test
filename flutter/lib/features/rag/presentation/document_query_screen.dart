/// Document query screen — ask questions about uploaded documents (RAG).
///
/// Layout:
///   ┌────────────────────────────────┐
///   │  Documents filter chips        │ ← select which docs to search
///   ├────────────────────────────────┤
///   │                                │
///   │  Answer (Markdown)             │
///   │  Citations                     │
///   │                                │
///   ├────────────────────────────────┤
///   │  [ Ask about your documents… ]│
///   └────────────────────────────────┘
library;

import 'package:ai_assistant_flutter/app/theme/app_theme.dart';
import 'package:ai_assistant_flutter/features/rag/domain/rag_models.dart';
import 'package:ai_assistant_flutter/features/rag/providers/rag_provider.dart';
import 'package:ai_assistant_flutter/shared/widgets/empty_state.dart';
import 'package:ai_assistant_flutter/shared/widgets/error_view.dart';
import 'package:ai_assistant_flutter/shared/widgets/loading_indicator.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_markdown/flutter_markdown.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

class DocumentQueryScreen extends ConsumerStatefulWidget {
  const DocumentQueryScreen({
    super.key,
    this.preselectedDocumentId,
  });

  /// When navigated from a document tile, this pre-selects that document.
  final String? preselectedDocumentId;

  @override
  ConsumerState<DocumentQueryScreen> createState() =>
      _DocumentQueryScreenState();
}

class _DocumentQueryScreenState extends ConsumerState<DocumentQueryScreen> {
  final _inputCtrl = TextEditingController();
  bool _canSearch = false;

  @override
  void initState() {
    super.initState();
    _inputCtrl.addListener(() {
      final can = _inputCtrl.text.trim().isNotEmpty;
      if (can != _canSearch) setState(() => _canSearch = can);
    });

    // Pre-select the document if provided.
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (widget.preselectedDocumentId != null) {
        ref
            .read(queryProvider.notifier)
            .toggleDocument(widget.preselectedDocumentId!);
      }
    });
  }

  @override
  void dispose() {
    _inputCtrl.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final text = _inputCtrl.text.trim();
    if (text.isEmpty) return;
    await ref.read(queryProvider.notifier).search(text);
  }

  @override
  Widget build(BuildContext context) {
    final queryState = ref.watch(queryProvider);
    final docsAsync = ref.watch(documentsProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Query Documents'),
        actions: [
          if (queryState.hasResult || queryState.selectedIds.isNotEmpty)
            TextButton(
              onPressed: () {
                ref.read(queryProvider.notifier).clear();
                _inputCtrl.clear();
              },
              child: const Text('Clear'),
            ),
        ],
      ),
      body: Column(
        children: [
          // ── Document filter chips ──────────────────────────────────────
          docsAsync.when(
            loading: () => const SizedBox.shrink(),
            error: (_, __) => const SizedBox.shrink(),
            data: (docs) => _DocumentFilterRow(
              docs: docs,
              selectedIds: queryState.selectedIds,
              onToggle: (id) =>
                  ref.read(queryProvider.notifier).toggleDocument(id),
              onClearAll: () =>
                  ref.read(queryProvider.notifier).clearSelection(),
            ),
          ),

          const Divider(height: 1),

          // ── Answer area ────────────────────────────────────────────────
          Expanded(
            child: Builder(builder: (ctx) {
              if (queryState.isLoading) {
                return const LoadingIndicator(
                  message: 'Searching documents…',
                );
              }
              if (queryState.error != null) {
                return ErrorView(
                  message: queryState.error!,
                  onRetry: _search,
                );
              }
              if (!queryState.hasResult) {
                return EmptyState(
                  icon: Icons.question_answer_outlined,
                  title: 'Ask about your documents',
                  subtitle: queryState.selectedIds.isEmpty
                      ? 'Search across all documents, or select specific ones above.'
                      : '${queryState.selectedIds.length} document(s) selected.',
                );
              }
              return _AnswerView(response: queryState.result!);
            }),
          ),

          // ── Input bar ──────────────────────────────────────────────────
          _QueryInputBar(
            controller: _inputCtrl,
            canSearch: _canSearch,
            isLoading: queryState.isLoading,
            onSearch: _search,
          ),
        ],
      ),
    );
  }
}

// ── Document filter chips ─────────────────────────────────────────────────────

class _DocumentFilterRow extends StatelessWidget {
  const _DocumentFilterRow({
    required this.docs,
    required this.selectedIds,
    required this.onToggle,
    required this.onClearAll,
  });

  final List<RagDocument> docs;
  final List<String> selectedIds;
  final void Function(String) onToggle;
  final VoidCallback onClearAll;

  @override
  Widget build(BuildContext context) {
    if (docs.isEmpty) return const SizedBox.shrink();
    return SizedBox(
      height: 48,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding:
            const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        children: [
          if (selectedIds.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(right: 8),
              child: FilterChip(
                label: const Text('Clear'),
                selected: false,
                onSelected: (_) => onClearAll(),
                avatar: const Icon(Icons.clear, size: 14),
              ),
            ),
          ...docs.map((doc) {
            final isSelected = selectedIds.contains(doc.id);
            return Padding(
              padding: const EdgeInsets.only(right: 8),
              child: Semantics(
                label:
                    '${doc.filename}${isSelected ? ', selected' : ''}',
                child: FilterChip(
                  label: Text(
                    doc.filename,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                  ),
                  selected: isSelected,
                  onSelected: (_) => onToggle(doc.id),
                ),
              ),
            );
          }),
        ],
      ),
    );
  }
}

// ── Answer view ───────────────────────────────────────────────────────────────

class _AnswerView extends StatelessWidget {
  const _AnswerView({required this.response});
  final DocumentQueryResponse response;

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        // Answer header
        Row(
          children: [
            Icon(Icons.auto_awesome,
                size: 18, color: context.aiAccent,
                semanticLabel: 'AI answer'),
            const SizedBox(width: 8),
            Text('Answer', style: context.texts.labelMedium),
          ],
        ),
        const SizedBox(height: 10),

        // Answer markdown
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: MarkdownBody(
              data: response.answer,
              selectable: true,
              styleSheet:
                  MarkdownStyleSheet.fromTheme(Theme.of(context)).copyWith(
                p: Theme.of(context)
                    .textTheme
                    .bodyMedium
                    ?.copyWith(height: 1.5),
                code: TextStyle(
                  fontFamily: 'monospace',
                  fontSize: 13,
                  backgroundColor: context.cardTonal,
                ),
              ),
            ),
          ),
        ),

        // Citations
        if (response.citations.isNotEmpty) ...[
          const SizedBox(height: 16),
          Row(
            children: [
              Icon(Icons.format_quote,
                  size: 18, color: context.colors.primary,
                  semanticLabel: 'Sources'),
              const SizedBox(width: 8),
              Text(
                '${response.citations.length} source${response.citations.length == 1 ? '' : 's'}',
                style: context.texts.labelMedium,
              ),
            ],
          ),
          const SizedBox(height: 8),
          ...response.citations.map((c) => _CitationCard(citation: c)),
        ],
      ],
    );
  }
}

class _CitationCard extends StatelessWidget {
  const _CitationCard({required this.citation});
  final RagCitation citation;

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      child: ListTile(
        dense: true,
        leading: Icon(
          Icons.bookmark_outline,
          size: 18,
          color: context.colors.primary,
          semanticLabel: 'Citation',
        ),
        title: Text(
          citation.documentName,
          style: context.texts.bodySmall,
        ),
        subtitle: citation.pageNumber != null
            ? Text(
                'Page ${citation.pageNumber}',
                style: context.texts.labelSmall
                    ?.copyWith(color: context.mutedColor),
              )
            : null,
        trailing: IconButton(
          icon: const Icon(Icons.copy, size: 16),
          tooltip: 'Copy citation',
          onPressed: () {
            Clipboard.setData(
              ClipboardData(text: citation.shortLabel),
            );
            ScaffoldMessenger.of(context).showSnackBar(
              const SnackBar(
                content: Text('Citation copied'),
                duration: Duration(seconds: 1),
              ),
            );
          },
        ),
      ),
    );
  }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

class _QueryInputBar extends StatelessWidget {
  const _QueryInputBar({
    required this.controller,
    required this.canSearch,
    required this.isLoading,
    required this.onSearch,
  });

  final TextEditingController controller;
  final bool                  canSearch;
  final bool                  isLoading;
  final VoidCallback          onSearch;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Container(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 8),
        decoration: BoxDecoration(
          color: context.cardColor,
          boxShadow: [
            BoxShadow(
              color:     Colors.black.withAlpha(10),
              blurRadius: 8,
              offset:    const Offset(0, -2),
            ),
          ],
        ),
        child: Row(
          children: [
            Expanded(
              child: Container(
                decoration: BoxDecoration(
                  color:        context.cardTonal,
                  borderRadius: BorderRadius.circular(24),
                ),
                child: TextField(
                  controller: controller,
                  maxLines:   3,
                  minLines:   1,
                  enabled:    !isLoading,
                  textInputAction: TextInputAction.search,
                  decoration: const InputDecoration(
                    hintText:     'Ask about your documents…',
                    border:       InputBorder.none,
                    enabledBorder: InputBorder.none,
                    focusedBorder: InputBorder.none,
                    contentPadding: EdgeInsets.symmetric(
                        horizontal: 16, vertical: 12),
                  ),
                  onSubmitted: canSearch && !isLoading
                      ? (_) => onSearch()
                      : null,
                ),
              ),
            ),
            const SizedBox(width: 8),
            Semantics(
              button: true,
              label: 'Search documents',
              child: GestureDetector(
                onTap: (canSearch && !isLoading) ? onSearch : null,
                child: Container(
                  width:  44,
                  height: 44,
                  decoration: BoxDecoration(
                    color: isLoading
                        ? context.mutedColor.withAlpha(30)
                        : canSearch
                            ? context.colors.primary.withAlpha(20)
                            : context.mutedColor.withAlpha(20),
                    shape: BoxShape.circle,
                  ),
                  child: isLoading
                      ? Padding(
                          padding: const EdgeInsets.all(12),
                          child: CircularProgressIndicator(
                            strokeWidth: 2,
                            color: context.colors.primary,
                          ),
                        )
                      : Icon(
                          Icons.search,
                          size: 22,
                          color: canSearch
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
