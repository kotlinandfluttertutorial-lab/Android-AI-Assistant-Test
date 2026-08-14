package com.aiassistant.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Generic wrapper for paginated list responses from the backend.
 *
 * Used when the backend returns a JSON object with metadata instead of a raw array.
 */
@Serializable
data class PaginatedResponse<T>(
    @SerialName("items") val items: List<T>,
    @SerialName("total") val total: Int,
    @SerialName("page") val page: Int? = null,
    @SerialName("page_size") val pageSize: Int? = null
)
