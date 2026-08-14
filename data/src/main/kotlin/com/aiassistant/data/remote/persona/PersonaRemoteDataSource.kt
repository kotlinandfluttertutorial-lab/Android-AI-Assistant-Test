package com.aiassistant.data.remote.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext
import retrofit2.HttpException

@Singleton
class PersonaRemoteDataSource @Inject constructor(
    private val apiService: PersonaApiService,
    private val dispatchers: DispatcherProvider,
) {
    suspend fun getPersonas(): ApiResult<List<PersonaResponse>> = withContext(dispatchers.io) {
        safeApiCall { apiService.getPersonas().items }
    }

    suspend fun createPersona(request: PersonaCreateRequest): ApiResult<PersonaResponse> = withContext(dispatchers.io) {
        safeApiCall { apiService.createPersona(request) }
    }

    suspend fun updatePersona(id: String, request: PersonaUpdateRequest): ApiResult<PersonaResponse> = withContext(dispatchers.io) {
        safeApiCall { apiService.updatePersona(id, request) }
    }

    suspend fun deletePersona(id: String): ApiResult<Unit> = withContext(dispatchers.io) {
        safeApiCall { apiService.deletePersona(id) }
    }

    private suspend fun <T> safeApiCall(block: suspend () -> T): ApiResult<T> = try {
        ApiResult.Success(block())
    } catch (e: HttpException) {
        ApiResult.Error(
            when (e.code()) {
                401 -> DomainError.Unauthorized(cause = e)
                403 -> DomainError.Forbidden(cause = e)
                422 -> DomainError.ValidationError(message = "Validation failed: ${e.message()}", cause = e)
                in 400..499 -> DomainError.ValidationError(message = "Invalid request (${e.code()})", cause = e)
                else -> DomainError.ServerError(httpStatusCode = e.code(), cause = e)
            },
        )
    } catch (e: IOException) {
        ApiResult.Error(DomainError.NetworkError(message = e.message ?: "Network error", cause = e))
    }
}
