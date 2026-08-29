/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteOnDeviceDocumentUseCase.kt
 * Purpose    : Removes all chunks from the local vector index and the
 *              document record from Room within 10 seconds.
 *
 * Architecture Layer : Domain — pure Kotlin use case.
 *
 * Design Decision    : Chunks are explicitly deleted via LocalVectorIndex
 *                      before the document row is removed.  Room's CASCADE
 *                      FK would also delete chunks, but the explicit call
 *                      makes the 10-second deletion SLA measurable in tests
 *                      using advanceTimeBy() in the test coroutine scope.
 *
 * Requirements: 33.10, 35.5, 35.6, 35.7
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.ai.ondevicerag.LocalVectorIndex
import com.aiassistant.core.common.ApiResult
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import javax.inject.Inject

/**
 * Deletes an on-device document and all its associated embedding chunks.
 *
 * Steps (both must complete within 10 seconds total):
 * 1. Remove all chunks from [LocalVectorIndex] (explicit DAO delete).
 * 2. Delete the document row from [OnDeviceDocumentRepository] (also triggers
 *    CASCADE FK delete as a safety net).
 *
 * @param vectorIndex            Removes embedding chunks from the local index.
 * @param documentRepository     Removes the document metadata row.
 */
class DeleteOnDeviceDocumentUseCase @Inject constructor(
    private val vectorIndex: LocalVectorIndex,
    private val documentRepository: OnDeviceDocumentRepository,
) {

    /**
     * Deletes [documentId] and all its chunks for [userId].
     *
     * @param documentId ID of the document to delete.
     * @param userId     Owner — scoping guard prevents cross-user deletion.
     * @return [ApiResult.Success] with [Unit] when both operations complete.
     *         [ApiResult.Error] if either step fails.
     */
    suspend operator fun invoke(documentId: String, userId: String): ApiResult<Unit> {
        return try {
            // Step 1 — remove all embedding chunks from the local vector index
            vectorIndex.deleteByDocument(userId, documentId)

            // Step 2 — remove the document metadata row (CASCADE also deletes
            // any chunks that weren't removed in step 1, e.g. due to a partial failure)
            documentRepository.deleteDocument(documentId, userId)

            ApiResult.Success(Unit)
        } catch (e: Exception) {
            ApiResult.Error(
                com.aiassistant.core.common.DomainError.ServerError(
                    message = "Failed to delete on-device document $documentId: ${e.message}",
                    code = 500,
                )
            )
        }
    }
}
