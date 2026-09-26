/// Domain models for the RAG (Retrieval-Augmented Generation) feature.
///
/// Backend contract:
///   POST   /documents          — multipart upload → {document_id, job_id, status}
///   GET    /documents          — list user's documents
///   POST   /documents/query    — query across multiple documents
///   POST   /documents/{id}/query — query a single document
///   DELETE /documents/{id}     — delete document
///   GET    /jobs/{id}          — poll ingestion job status
///
/// Phase 9 — RAG (Retrieval-Augmented Generation)
library;

import 'package:equatable/equatable.dart';

// ── Job status ────────────────────────────────────────────────────────────────

enum IngestJobStatus {
  queued, running, completed, failed;

  static IngestJobStatus parse(String v) => switch (v.toLowerCase()) {
        'running'   => IngestJobStatus.running,
        'completed' => IngestJobStatus.completed,
        'failed'    => IngestJobStatus.failed,
        _           => IngestJobStatus.queued,
      };

  bool get isTerminal  => this == completed || this == failed;
  bool get isCompleted => this == completed;
  bool get isFailed    => this == failed;
}

// ── Upload response ───────────────────────────────────────────────────────────

class DocumentUploadResponse {
  const DocumentUploadResponse({
    required this.documentId,
    required this.jobId,
    required this.status,
  });

  factory DocumentUploadResponse.fromJson(Map<String, dynamic> json) =>
      DocumentUploadResponse(
        documentId: (json['document_id'] as String?) ?? '',
        jobId:      (json['job_id']      as String?) ?? '',
        status:     IngestJobStatus.parse(
            (json['status'] as String?) ?? 'queued'),
      );

  final String          documentId;
  final String          jobId;
  final IngestJobStatus status;
}

// ── Ingestion job ─────────────────────────────────────────────────────────────

class IngestJob extends Equatable {
  const IngestJob({
    required this.jobId,
    required this.status,
    this.documentId,
    this.errorMessage,
  });

  factory IngestJob.fromJson(Map<String, dynamic> json) => IngestJob(
        jobId:        (json['job_id']      as String?) ?? '',
        status:       IngestJobStatus.parse(
            (json['status'] as String?) ?? 'queued'),
        documentId:   json['document_id']   as String?,
        errorMessage: json['error_message'] as String?,
      );

  final String          jobId;
  final IngestJobStatus status;
  final String?         documentId;
  final String?         errorMessage;

  @override
  List<Object?> get props => [jobId, status];
}

// ── Document ──────────────────────────────────────────────────────────────────

class RagDocument extends Equatable {
  const RagDocument({
    required this.id,
    required this.filename,
    required this.createdAt,
    this.sizeBytes,
    this.chunkCount,
    this.status,
  });

  factory RagDocument.fromJson(Map<String, dynamic> json) => RagDocument(
        id:         (json['id']        as String?) ?? '',
        filename:   (json['filename']  as String?) ??
                    (json['name']      as String?) ?? 'Unknown',
        createdAt:  (json['created_at'] as String?) ?? '',
        sizeBytes:  (json['size_bytes'] as int?),
        chunkCount: (json['chunk_count'] as int?),
        status:     json['status'] != null
            ? IngestJobStatus.parse(json['status'] as String)
            : null,
      );

  final String          id;
  final String          filename;
  final String          createdAt;
  final int?            sizeBytes;
  final int?            chunkCount;
  final IngestJobStatus? status;

  String get displaySize {
    if (sizeBytes == null) return '';
    final kb = sizeBytes! / 1024;
    if (kb < 1024) return '${kb.toStringAsFixed(1)} KB';
    return '${(kb / 1024).toStringAsFixed(1)} MB';
  }

  @override
  List<Object?> get props => [id, filename, createdAt];
}

// ── Citation ──────────────────────────────────────────────────────────────────

class RagCitation extends Equatable {
  const RagCitation({
    required this.documentName,
    this.pageNumber,
    this.chunkIndex,
    this.citationType    = '',
    this.charOffsetStart,
    this.charOffsetEnd,
  });

  factory RagCitation.fromJson(Map<String, dynamic> json) => RagCitation(
        documentName:   (json['document_name']    as String?) ?? '',
        pageNumber:     json['page_number']        as int?,
        chunkIndex:     json['chunk_index']        as int?,
        citationType:   (json['citation_type']     as String?) ?? '',
        charOffsetStart: json['char_offset_start'] as int?,
        charOffsetEnd:  json['char_offset_end']    as int?,
      );

  final String documentName;
  final int?   pageNumber;
  final int?   chunkIndex;
  final String citationType;
  final int?   charOffsetStart;
  final int?   charOffsetEnd;

  String get shortLabel {
    final parts = <String>[documentName];
    if (pageNumber != null) parts.add('p.$pageNumber');
    return parts.join(' · ');
  }

  @override
  List<Object?> get props => [documentName, pageNumber, chunkIndex];
}

// ── Query response ────────────────────────────────────────────────────────────

class DocumentQueryResponse extends Equatable {
  const DocumentQueryResponse({
    required this.answer,
    required this.citations,
    this.contextUsed = '',
  });

  factory DocumentQueryResponse.fromJson(Map<String, dynamic> json) =>
      DocumentQueryResponse(
        answer:      (json['answer']    as String?) ?? '',
        citations: (json['citations'] as List<dynamic>? ?? [])
            .map((e) =>
                RagCitation.fromJson(e as Map<String, dynamic>))
            .toList(),
        contextUsed: (json['context_used'] as String?) ?? '',
      );

  final String          answer;
  final List<RagCitation> citations;
  final String          contextUsed;

  @override
  List<Object?> get props => [answer, citations];
}

// ── Query request ─────────────────────────────────────────────────────────────

class DocumentQueryRequest {
  const DocumentQueryRequest({
    required this.query,
    this.documentIds,
    this.topK = 5,
  });

  final String        query;
  final List<String>? documentIds;
  final int           topK;

  Map<String, dynamic> toJson() => {
        'query':                         query,
        if (documentIds != null) 'document_ids': documentIds,
        'top_k':                         topK,
      };
}
