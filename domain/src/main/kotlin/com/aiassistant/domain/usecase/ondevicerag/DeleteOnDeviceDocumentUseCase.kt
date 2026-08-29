/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : DeleteOnDeviceDocumentUseCase.kt
 * Purpose    : Removes all chunks from the local vector index and the
 *              document record from Room.
 * ============================================================
 */
package com.aiassistant.domain.usecase.ondevicerag

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.common.LocalVectorIndex
import com.aiassistant.domain.repository.OnDeviceDocumentRepository
import javax.inject.Inject

class DeleteOnDeviceDocumentUseCase @Inject constructor(
    private val vectorIndex: LocalVectorIndex,
    private val documentRepository: OnDeviceDocumentRepository,
) {

    suspend operator fun invoke(documentId: String, userId: String): ApiResult<Unit> {
        return try {
            vectorIndex.deleteByDocument(userId, documentId)
            documentRepository.deleteDocument(documentId, userId)
            ApiResult.Success(Unit)
        } catch (e: Exception) {
            ApiResult.Error(
                DomainError.ServerError(
                    message = "Failed to delete on-device document $documentId: ${e.message}",
                    httpStatusCode = 500,
                )
            )
        }
    }
}
