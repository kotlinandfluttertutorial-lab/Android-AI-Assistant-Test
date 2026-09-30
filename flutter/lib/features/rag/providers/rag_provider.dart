/// Riverpod providers for the RAG feature.
library;

import 'dart:async';

import 'package:ai_assistant_flutter/app/providers/core_providers.dart';
import 'package:ai_assistant_flutter/features/rag/data/rag_api.dart';
import 'package:ai_assistant_flutter/features/rag/domain/rag_models.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

// ── API provider ──────────────────────────────────────────────────────────────

final ragApiProvider =
    Provider<RagApi>((ref) => RagApi(ref.watch(dioProvider)));

// ── Document list ─────────────────────────────────────────────────────────────

class DocumentsNotifier extends AsyncNotifier<List<RagDocument>> {
  @override
  Future<List<RagDocument>> build() => _fetch();

  Future<List<RagDocument>> _fetch() async {
    final result = await ref.read(ragApiProvider).listDocuments();
    return result.when(onSuccess: (d) => d, onFailure: (e) => throw e);
  }

  Future<void> refresh() async {
    state = const AsyncLoading();
    state = await AsyncValue.guard(_fetch);
  }

  Future<void> delete(String id) async {
    await ref.read(ragApiProvider).deleteDocument(id);
    state.whenData((docs) {
      state = AsyncData(docs.where((d) => d.id != id).toList());
    });
  }
}

final documentsProvider =
    AsyncNotifierProvider<DocumentsNotifier, List<RagDocument>>(
  DocumentsNotifier.new,
);

// ── Upload + job-polling notifier ─────────────────────────────────────────────

/// State for a single upload + its in-progress ingest job.
class UploadState {
  const UploadState({
    this.isUploading = false,
    this.jobId,
    this.jobStatus,
    this.error,
    this.completedDocumentId,
  });

  final bool isUploading;
  final String? jobId;
  final IngestJobStatus? jobStatus;
  final String? error;
  final String? completedDocumentId;

  bool get isPolling => jobId != null && !(jobStatus?.isTerminal ?? false);
  bool get isDone => jobStatus == IngestJobStatus.completed;
  bool get hasFailed => jobStatus == IngestJobStatus.failed || error != null;

  UploadState copyWith({
    bool? isUploading,
    String? jobId,
    IngestJobStatus? jobStatus,
    String? error,
    String? completedDocumentId,
    bool clearError = false,
  }) =>
      UploadState(
        isUploading: isUploading ?? this.isUploading,
        jobId: jobId ?? this.jobId,
        jobStatus: jobStatus ?? this.jobStatus,
        error: clearError ? null : (error ?? this.error),
        completedDocumentId: completedDocumentId ?? this.completedDocumentId,
      );
}

class UploadNotifier extends Notifier<UploadState> {
  Timer? _pollTimer;

  @override
  UploadState build() {
    ref.onDispose(() => _pollTimer?.cancel());
    return const UploadState();
  }

  Future<void> upload({
    required String filePath,
    required String filename,
    required String mimeType,
  }) async {
    state = const UploadState(isUploading: true);
    final api = ref.read(ragApiProvider);
    final result = await api.uploadDocument(
      filePath: filePath,
      filename: filename,
      mimeType: mimeType,
    );
    result.when(
      onSuccess: (resp) {
        state = UploadState(
          isUploading: false,
          jobId: resp.jobId,
          jobStatus: resp.status,
        );
        _startPolling(resp.jobId);
      },
      onFailure: (err) {
        state = UploadState(error: err.userMessage);
      },
    );
  }

  void _startPolling(String jobId) {
    _pollTimer?.cancel();
    _pollTimer = Timer.periodic(const Duration(seconds: 3), (_) async {
      final api = ref.read(ragApiProvider);
      final result = await api.getJob(jobId);
      result.when(
        onSuccess: (job) {
          state = state.copyWith(
            jobStatus: job.status,
            completedDocumentId: job.documentId,
            error: job.status.isFailed
                ? (job.errorMessage ?? 'Ingestion failed')
                : null,
          );
          if (job.status.isTerminal) {
            _pollTimer?.cancel();
            if (job.status.isCompleted) {
              // Refresh document list so the new doc appears.
              unawaited(
                ref.read(documentsProvider.notifier).refresh(),
              );
            }
          }
        },
        onFailure: (_) {}, // transient poll error — keep polling
      );
    });
  }

  void reset() {
    _pollTimer?.cancel();
    state = const UploadState();
  }
}

final uploadProvider =
    NotifierProvider<UploadNotifier, UploadState>(UploadNotifier.new);

// ── Query state ───────────────────────────────────────────────────────────────

class QueryState {
  const QueryState({
    this.result,
    this.isLoading = false,
    this.error,
    this.query = '',
    this.selectedIds = const [],
    this.topK = 5,
  });

  final DocumentQueryResponse? result;
  final bool isLoading;
  final String? error;
  final String query;
  final List<String> selectedIds;
  final int topK;

  bool get hasResult => result != null;

  QueryState copyWith({
    DocumentQueryResponse? result,
    bool? isLoading,
    String? error,
    String? query,
    List<String>? selectedIds,
    int? topK,
    bool clearError = false,
    bool clearResult = false,
  }) =>
      QueryState(
        result: clearResult ? null : (result ?? this.result),
        isLoading: isLoading ?? this.isLoading,
        error: clearError ? null : (error ?? this.error),
        query: query ?? this.query,
        selectedIds: selectedIds ?? this.selectedIds,
        topK: topK ?? this.topK,
      );
}

class QueryNotifier extends Notifier<QueryState> {
  @override
  QueryState build() => const QueryState();

  Future<void> search(String query) async {
    if (query.trim().isEmpty) return;
    state = state.copyWith(
      query: query,
      isLoading: true,
      clearError: true,
      clearResult: true,
    );
    final api = ref.read(ragApiProvider);
    final result = await api.queryDocuments(
      DocumentQueryRequest(
        query: query.trim(),
        documentIds: state.selectedIds.isEmpty ? null : state.selectedIds,
        topK: state.topK,
      ),
    );
    state = result.when(
      onSuccess: (data) => state.copyWith(result: data, isLoading: false),
      onFailure: (err) =>
          state.copyWith(isLoading: false, error: err.userMessage),
    );
  }

  void toggleDocument(String id) {
    final ids = List<String>.from(state.selectedIds);
    if (ids.contains(id)) {
      ids.remove(id);
    } else {
      ids.add(id);
    }
    state = state.copyWith(selectedIds: ids, clearResult: true);
  }

  void clearSelection() =>
      state = state.copyWith(selectedIds: const [], clearResult: true);

  void clear() => state = const QueryState();
}

final queryProvider =
    NotifierProvider<QueryNotifier, QueryState>(QueryNotifier.new);
