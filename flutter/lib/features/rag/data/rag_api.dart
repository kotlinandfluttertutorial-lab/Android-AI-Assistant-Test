/// API client for /documents and /jobs endpoints.
library;

import 'package:ai_assistant_flutter/core/constants/api_config.dart';
import 'package:ai_assistant_flutter/core/error/error_mapper.dart';
import 'package:ai_assistant_flutter/core/utils/result.dart';
import 'package:ai_assistant_flutter/features/rag/domain/rag_models.dart';
import 'package:dio/dio.dart';

class RagApi {
  const RagApi(this._dio);
  final Dio _dio;

  // ── Upload ────────────────────────────────────────────────────────────────

  /// Upload a document file (PDF / DOCX / TXT / MD, max 50 MB).
  ///
  /// [filePath] is the on-device file path (from a file picker).
  /// Returns a [DocumentUploadResponse] with a job ID to poll.
  Future<Result<DocumentUploadResponse>> uploadDocument({
    required String filePath,
    required String filename,
    required String mimeType,
  }) async {
    try {
      final formData = FormData.fromMap({
        'file': await MultipartFile.fromFile(
          filePath,
          filename: filename,
          contentType: DioMediaType.parse(mimeType),
        ),
      });
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.documents,
        data: formData,
        options: Options(contentType: 'multipart/form-data'),
      );
      return Success(DocumentUploadResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── List documents ────────────────────────────────────────────────────────

  Future<Result<List<RagDocument>>> listDocuments() async {
    try {
      final response = await _dio.get<dynamic>(ApiConfig.documents);
      final raw = response.data as List<dynamic>? ?? [];
      return Success(
        raw.map((e) => RagDocument.fromJson(e as Map<String, dynamic>)).toList(),
      );
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Delete ────────────────────────────────────────────────────────────────

  Future<Result<void>> deleteDocument(String id) async {
    try {
      await _dio.delete<void>(ApiConfig.documentById(id));
      return const Success(null);
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Query ─────────────────────────────────────────────────────────────────

  /// Query across all (or selected) documents.
  Future<Result<DocumentQueryResponse>> queryDocuments(
    DocumentQueryRequest request,
  ) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.documentsQuery,
        data: request.toJson(),
      );
      return Success(DocumentQueryResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  /// Query a single document.
  Future<Result<DocumentQueryResponse>> querySingleDocument(
    String documentId,
    String query, {
    int topK = 5,
  }) async {
    try {
      final response = await _dio.post<Map<String, dynamic>>(
        ApiConfig.documentQuery(documentId),
        data: {'query': query, 'top_k': topK},
      );
      return Success(DocumentQueryResponse.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }

  // ── Job polling ───────────────────────────────────────────────────────────

  Future<Result<IngestJob>> getJob(String jobId) async {
    try {
      final response = await _dio.get<Map<String, dynamic>>(
        ApiConfig.ragJobById(jobId),
      );
      return Success(IngestJob.fromJson(response.data!));
    } catch (e, st) {
      return Failure(ErrorMapper.map(e, st));
    }
  }
}
