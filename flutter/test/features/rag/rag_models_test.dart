import 'package:ai_assistant_flutter/features/rag/domain/rag_models.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  // ── IngestJobStatus ─────────────────────────────────────────────────────────

  group('IngestJobStatus.parse', () {
    test('parses all known values case-insensitively', () {
      expect(IngestJobStatus.parse('queued'), IngestJobStatus.queued);
      expect(IngestJobStatus.parse('RUNNING'), IngestJobStatus.running);
      expect(IngestJobStatus.parse('Completed'), IngestJobStatus.completed);
      expect(IngestJobStatus.parse('FAILED'), IngestJobStatus.failed);
    });

    test('defaults to queued for unknown value', () {
      expect(IngestJobStatus.parse('unknown'), IngestJobStatus.queued);
      expect(IngestJobStatus.parse(''), IngestJobStatus.queued);
    });

    test('isTerminal is true only for completed and failed', () {
      expect(IngestJobStatus.queued.isTerminal, isFalse);
      expect(IngestJobStatus.running.isTerminal, isFalse);
      expect(IngestJobStatus.completed.isTerminal, isTrue);
      expect(IngestJobStatus.failed.isTerminal, isTrue);
    });

    test('isCompleted and isFailed are mutually exclusive', () {
      expect(IngestJobStatus.completed.isCompleted, isTrue);
      expect(IngestJobStatus.completed.isFailed, isFalse);
      expect(IngestJobStatus.failed.isFailed, isTrue);
      expect(IngestJobStatus.failed.isCompleted, isFalse);
    });
  });

  // ── DocumentUploadResponse ──────────────────────────────────────────────────

  group('DocumentUploadResponse.fromJson', () {
    test('parses all fields', () {
      final json = {
        'document_id': 'doc-abc',
        'job_id': 'job-xyz',
        'status': 'queued',
      };
      final r = DocumentUploadResponse.fromJson(json);
      expect(r.documentId, 'doc-abc');
      expect(r.jobId, 'job-xyz');
      expect(r.status, IngestJobStatus.queued);
    });

    test('defaults missing fields safely', () {
      final r = DocumentUploadResponse.fromJson({});
      expect(r.documentId, '');
      expect(r.jobId, '');
      expect(r.status, IngestJobStatus.queued);
    });
  });

  // ── IngestJob ───────────────────────────────────────────────────────────────

  group('IngestJob.fromJson', () {
    test('parses completed job', () {
      final json = {
        'job_id': 'job-001',
        'status': 'completed',
        'document_id': 'doc-001',
      };
      final job = IngestJob.fromJson(json);
      expect(job.jobId, 'job-001');
      expect(job.status, IngestJobStatus.completed);
      expect(job.documentId, 'doc-001');
      expect(job.status.isTerminal, isTrue);
    });

    test('parses failed job with error message', () {
      final json = {
        'job_id': 'job-002',
        'status': 'failed',
        'error_message': 'Unsupported file type',
      };
      final job = IngestJob.fromJson(json);
      expect(job.status.isFailed, isTrue);
      expect(job.errorMessage, 'Unsupported file type');
    });

    test('parses running job with no document_id yet', () {
      final json = {'job_id': 'job-003', 'status': 'running'};
      final job = IngestJob.fromJson(json);
      expect(job.documentId, isNull);
      expect(job.status.isTerminal, isFalse);
    });
  });

  // ── RagDocument ─────────────────────────────────────────────────────────────

  group('RagDocument.fromJson', () {
    test('parses all optional fields', () {
      final json = {
        'id': 'doc-1',
        'filename': 'architecture.pdf',
        'created_at': '2025-01-15T10:00:00',
        'size_bytes': 1_048_576, // 1 MB
        'chunk_count': 42,
        'status': 'completed',
      };
      final doc = RagDocument.fromJson(json);
      expect(doc.id, 'doc-1');
      expect(doc.filename, 'architecture.pdf');
      expect(doc.chunkCount, 42);
      expect(doc.sizeBytes, 1_048_576);
      expect(doc.status, IngestJobStatus.completed);
    });

    test('falls back to "filename" field, then "name"', () {
      final withFilename = RagDocument.fromJson(
          {'id': 'x', 'filename': 'a.pdf', 'created_at': ''});
      expect(withFilename.filename, 'a.pdf');

      final withName =
          RagDocument.fromJson({'id': 'y', 'name': 'b.pdf', 'created_at': ''});
      expect(withName.filename, 'b.pdf');

      final neither = RagDocument.fromJson({'id': 'z', 'created_at': ''});
      expect(neither.filename, 'Unknown');
    });

    test('displaySize formats KB correctly', () {
      final doc = RagDocument.fromJson({
        'id': 'd',
        'filename': 'f',
        'created_at': '',
        'size_bytes': 2048,
      });
      expect(doc.displaySize, '2.0 KB');
    });

    test('displaySize formats MB correctly', () {
      final doc = RagDocument.fromJson({
        'id': 'd',
        'filename': 'f',
        'created_at': '',
        'size_bytes': 2_097_152,
      });
      expect(doc.displaySize, '2.0 MB');
    });

    test('displaySize is empty when sizeBytes is null', () {
      final doc =
          RagDocument.fromJson({'id': 'd', 'filename': 'f', 'created_at': ''});
      expect(doc.displaySize, '');
    });
  });

  // ── RagCitation ─────────────────────────────────────────────────────────────

  group('RagCitation.fromJson', () {
    test('parses all fields', () {
      final json = {
        'document_name': 'runbook-db.md',
        'page_number': 3,
        'chunk_index': 7,
        'citation_type': 'exact',
        'char_offset_start': 100,
        'char_offset_end': 250,
      };
      final c = RagCitation.fromJson(json);
      expect(c.documentName, 'runbook-db.md');
      expect(c.pageNumber, 3);
      expect(c.chunkIndex, 7);
      expect(c.citationType, 'exact');
    });

    test('shortLabel includes page number when present', () {
      final c = RagCitation.fromJson({
        'document_name': 'guide.pdf',
        'page_number': 5,
      });
      expect(c.shortLabel, 'guide.pdf · p.5');
    });

    test('shortLabel omits page number when absent', () {
      final c = RagCitation.fromJson({'document_name': 'notes.md'});
      expect(c.shortLabel, 'notes.md');
    });
  });

  // ── DocumentQueryResponse ───────────────────────────────────────────────────

  group('DocumentQueryResponse.fromJson', () {
    test('parses answer and citations', () {
      final json = {
        'answer': 'The connection pool exhausted at 14:32.',
        'citations': <Map<String, dynamic>>[
          {'document_name': 'runbook-db.md', 'page_number': 2},
          {'document_name': 'architecture.md'},
        ],
        'context_used': 'chunked excerpt…',
      };
      final r = DocumentQueryResponse.fromJson(json);
      expect(r.answer, isNotEmpty);
      expect(r.citations.length, 2);
      expect(r.citations.first.documentName, 'runbook-db.md');
      expect(r.contextUsed, 'chunked excerpt…');
    });

    test('handles empty citations list', () {
      final r = DocumentQueryResponse.fromJson({
        'answer': 'No results found.',
        'citations': <dynamic>[],
      });
      expect(r.citations, isEmpty);
    });

    test('defaults missing fields safely', () {
      final r = DocumentQueryResponse.fromJson({});
      expect(r.answer, '');
      expect(r.citations, isEmpty);
    });
  });

  // ── DocumentQueryRequest ────────────────────────────────────────────────────

  group('DocumentQueryRequest.toJson', () {
    test('serialises query and topK', () {
      final req = DocumentQueryRequest(query: 'How to restart?', topK: 3);
      final json = req.toJson();
      expect(json['query'], 'How to restart?');
      expect(json['top_k'], 3);
      expect(json.containsKey('document_ids'), isFalse);
    });

    test('includes document_ids when provided', () {
      final req = DocumentQueryRequest(
        query: 'DB timeout',
        documentIds: <String>['doc-1', 'doc-2'],
      );
      final json = req.toJson();
      expect(json['document_ids'], ['doc-1', 'doc-2']);
    });

    test('default topK is 5', () {
      final req = DocumentQueryRequest(query: 'test');
      expect(req.topK, 5);
    });
  });
}
