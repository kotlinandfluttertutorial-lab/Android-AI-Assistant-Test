package com.aiassistant.data.remote.persona

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

@Serializable
data class PersonaResponse(
    val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    @SerialName("system_prompt") val systemPrompt: String,
    val tone: String,
    @SerialName("scope_description") val scopeDescription: String? = null,
    @SerialName("admin_locked") val adminLocked: Boolean = false,
    @SerialName("allowed_roles") val allowedRoles: List<String> = emptyList(),
    @SerialName("created_at") val createdAt: Long = 0L,
    @SerialName("updated_at") val updatedAt: Long = 0L
)

@Serializable
data class PersonaListResponse(val items: List<PersonaResponse>, val total: Int)

@Serializable
data class PersonaCreateRequest(
    val name: String,
    @SerialName("system_prompt") val systemPrompt: String,
    val tone: String,
    @SerialName("scope_description") val scopeDescription: String? = null,
    @SerialName("admin_locked") val adminLocked: Boolean = false,
    @SerialName("allowed_roles") val allowedRoles: List<String> = emptyList()
)

@Serializable
data class PersonaUpdateRequest(
    val name: String? = null,
    @SerialName("system_prompt") val systemPrompt: String? = null,
    val tone: String? = null,
    @SerialName("scope_description") val scopeDescription: String? = null,
    @SerialName("allowed_roles") val allowedRoles: List<String>? = null
)

interface PersonaApiService {
    @GET("api/v1/personas")
    suspend fun getPersonas(): PersonaListResponse

    @POST("api/v1/personas")
    suspend fun createPersona(@Body request: PersonaCreateRequest): PersonaResponse

    @PUT("api/v1/personas/{id}")
    suspend fun updatePersona(@Path("id") id: String, @Body request: PersonaUpdateRequest): PersonaResponse

    @DELETE("api/v1/personas/{id}")
    suspend fun deletePersona(@Path("id") id: String)
}
