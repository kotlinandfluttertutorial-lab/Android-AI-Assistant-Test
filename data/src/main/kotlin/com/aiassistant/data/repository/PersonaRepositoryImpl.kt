package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.data.remote.persona.PersonaCreateRequest
import com.aiassistant.data.remote.persona.PersonaRemoteDataSource
import com.aiassistant.data.remote.persona.PersonaResponse
import com.aiassistant.data.remote.persona.PersonaUpdateRequest
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.model.PersonaTone
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import com.aiassistant.domain.repository.PersonaRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

@Singleton
class PersonaRepositoryImpl @Inject constructor(
    private val remoteSource: PersonaRemoteDataSource,
    private val preferencesRepository: PersonaPreferencesRepository,
    private val dispatchers: DispatcherProvider
) : PersonaRepository {

    override fun getPersonas(): Flow<ApiResult<List<Persona>>> = flow {
        emit(ApiResult.Loading)
        val result = remoteSource.getPersonas()
        if (result is ApiResult.Success) {
            emit(ApiResult.Success(result.data.map { it.toDomain() }))
        } else {
            @Suppress("UNCHECKED_CAST")
            emit(result as ApiResult<List<Persona>>)
        }
    }.flowOn(dispatchers.io)

    override suspend fun createPersona(persona: Persona): ApiResult<Persona> {
        val request = PersonaCreateRequest(
            name = persona.name,
            systemPrompt = persona.systemPrompt,
            tone = persona.tone.value,
            scopeDescription = persona.scopeDescription,
            allowedRoles = persona.allowedRoles
        )
        return when (val result = remoteSource.createPersona(request)) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }

    override suspend fun updatePersona(persona: Persona): ApiResult<Persona> {
        val request = PersonaUpdateRequest(
            name = persona.name,
            systemPrompt = persona.systemPrompt,
            tone = persona.tone.value,
            scopeDescription = persona.scopeDescription,
            allowedRoles = persona.allowedRoles
        )
        return when (val result = remoteSource.updatePersona(persona.id, request)) {
            is ApiResult.Success -> ApiResult.Success(result.data.toDomain())
            is ApiResult.Error -> result
            is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
            is ApiResult.Loading -> ApiResult.Loading
        }
    }

    override suspend fun deletePersona(personaId: String): ApiResult<Unit> = remoteSource.deletePersona(personaId)

    override suspend fun getPersonaCount(): ApiResult<Int> = when (val result = remoteSource.getPersonas()) {
        is ApiResult.Success -> ApiResult.Success(result.data.size)
        is ApiResult.Error -> result
        is ApiResult.NetworkUnavailable -> ApiResult.NetworkUnavailable
        is ApiResult.Loading -> ApiResult.Loading
    }

    override suspend fun getSelectedPersonaId(): ApiResult<String?> =
        ApiResult.Success(preferencesRepository.getSelectedPersonaId())

    override suspend fun setSelectedPersonaId(personaId: String?): ApiResult<Unit> {
        preferencesRepository.saveSelectedPersonaId(personaId)
        return ApiResult.Success(Unit)
    }

    private fun PersonaResponse.toDomain(): Persona = Persona(
        id = id,
        userId = userId,
        name = name,
        systemPrompt = systemPrompt,
        tone = PersonaTone.fromValue(tone),
        scopeDescription = scopeDescription,
        adminLocked = adminLocked,
        allowedRoles = allowedRoles,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
