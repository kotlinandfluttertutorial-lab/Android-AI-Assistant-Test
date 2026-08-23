/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-common
 * File       : ApiResult.kt
 * Purpose    : ApiResult — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */

/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-common
 * File       : ApiResult.kt
 * Purpose    : ApiResult — core-common module component
 *
 * Architecture Layer : Core-Common
 * Pattern Used       : Kotlin Class
 *
 * Key Concepts:
 *   - Clean Architecture with strict layer separation
 *   - Hilt dependency injection
 *
 * Dependencies:
 *   - See import statements below
 * ============================================================
 */
/**
 * ApiResult.kt
 *
 * Purpose: Generic sealed result type for all async operations across domain and data layers.
 * Architecture: core-common â€” shared infrastructure, no Android/framework dependencies.
 * Dependencies: None (pure Kotlin)
 *
 * Design decisions:
 * - Four variants cover every observable state: Loading, Success, Error, NetworkUnavailable.
 * - NetworkUnavailable is a top-level variant (not a subtype of Error) so the UI can render
 *   an offline banner without pattern-matching through the error hierarchy.
 * - map / flatMap / fold are modelled after Kotlin's Result / Arrow Either conventions so
 *   transformation chains stay readable without a dedicated FP library dependency.
 * - All operator functions are inline + reified-free to avoid unnecessary boxing on Android.
 */

package com.aiassistant.core.common

/**
 * Represents every possible state of an asynchronous operation that produces a value of
 * type [T] or fails with a [DomainError].
 *
 * Typical usage in a ViewModel:
 * ```kotlin
 * _uiState.value = ApiResult.Loading
 * _uiState.value = repository.fetchData()   // returns ApiResult<Data>
 *     .map { data -> data.toUiModel() }
 * ```
 *
 * And in a repository:
 * ```kotlin
 * return try {
 *     ApiResult.Success(api.fetchUser())
 * } catch (e: IOException) {
 *     ApiResult.Error(DomainError.NetworkError(cause = e))
 * }
 * ```
 */
sealed class ApiResult<out T> {

    // â”€â”€â”€ Variants â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * The operation is in progress. No data is available yet.
     *
     * This is an object (not a data class) because there is only ever one loading state.
     */
    data object Loading : ApiResult<Nothing>()

    /**
     * The operation completed successfully and produced [data].
     *
     * @param data The result value.
     */
    data class Success<out T>(val data: T) : ApiResult<T>()

    /**
     * The operation failed with a typed [DomainError].
     *
     * @param error The domain error describing the failure.
     */
    data class Error(val error: DomainError) : ApiResult<Nothing>()

    /**
     * The operation could not be attempted because the device has no network connectivity.
     *
     * Kept as a top-level variant separate from [Error] so the UI layer can render an
     * offline banner without extra pattern matching.
     */
    data object NetworkUnavailable : ApiResult<Nothing>()

    // â”€â”€â”€ Transformation operators â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /**
     * Transforms the [Success.data] value using [transform].
     *
     * [Loading], [Error], and [NetworkUnavailable] pass through unchanged.
     *
     * ```kotlin
     * val result: ApiResult<String> = ApiResult.Success(42).map { it.toString() }
     * ```
     *
     * @param transform Function applied to the success value.
     * @return [ApiResult] with the transformed value, or the original non-success variant.
     */
    inline fun <R> map(transform: (T) -> R): ApiResult<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is NetworkUnavailable -> this
        is Loading -> this
    }

    /**
     * Chains an operation that itself returns an [ApiResult].
     *
     * Useful when multiple sequential async operations must each succeed before proceeding.
     *
     * ```kotlin
     * val result: ApiResult<Profile> = fetchUser()
     *     .flatMap { user -> fetchProfile(user.id) }
     * ```
     *
     * [Loading], [Error], and [NetworkUnavailable] short-circuit and pass through unchanged.
     *
     * @param transform Function returning the next [ApiResult] when the current result is
     *                  a [Success].
     * @return The result of [transform] when successful, or the original non-success variant.
     */
    inline fun <R> flatMap(transform: (T) -> ApiResult<R>): ApiResult<R> = when (this) {
        is Success -> transform(data)
        is Error -> this
        is NetworkUnavailable -> this
        is Loading -> this
    }

    /**
     * Collapses all four variants into a single value of type [R].
     *
     * This is the primary way to "unwrap" an [ApiResult] in a ViewModel or UI layer without
     * a `when` expression:
     *
     * ```kotlin
     * val text: String = result.fold(
     *     onSuccess    = { data -> data.name },
     *     onError      = { error -> error.message },
     *     onLoading    = { "Loadingâ€¦" },
     *     onOffline    = { "No connection" },
     * )
     * ```
     *
     * @param onSuccess   Called when this is [Success]; receives [Success.data].
     * @param onError     Called when this is [Error]; receives [Error.error].
     * @param onLoading   Called when this is [Loading].
     * @param onOffline   Called when this is [NetworkUnavailable].
     * @return The value produced by whichever branch is invoked.
     */
    inline fun <R> fold(onSuccess: (T) -> R, onError: (DomainError) -> R, onLoading: () -> R, onOffline: () -> R): R =
        when (this) {
            is Success -> onSuccess(data)
            is Error -> onError(error)
            is Loading -> onLoading()
            is NetworkUnavailable -> onOffline()
        }

    // â”€â”€â”€ Convenience properties â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    /** Returns `true` if and only if this is a [Success]. */
    val isSuccess: Boolean get() = this is Success

    /** Returns `true` if and only if this is an [Error]. */
    val isError: Boolean get() = this is Error

    /** Returns `true` if and only if this is [Loading]. */
    val isLoading: Boolean get() = this is Loading

    /** Returns `true` if and only if this is [NetworkUnavailable]. */
    val isNetworkUnavailable: Boolean get() = this is NetworkUnavailable

    /**
     * Returns the success value or `null` for every other variant.
     *
     * Prefer [fold] for exhaustive handling; use this only where a nullable shortcut is
     * intentional.
     */
    fun getOrNull(): T? = (this as? Success)?.data

    /**
     * Returns the [DomainError] or `null` for every other variant.
     */
    fun errorOrNull(): DomainError? = (this as? Error)?.error
}

// â”€â”€â”€ Extension helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Wraps [value] in [ApiResult.Success].
 *
 * ```kotlin
 * return user.asSuccess()
 * ```
 */
fun <T> T.asSuccess(): ApiResult<T> = ApiResult.Success(this)

/**
 * Wraps this [DomainError] in [ApiResult.Error].
 *
 * ```kotlin
 * return DomainError.NetworkError(cause = e).asError()
 * ```
 */
fun DomainError.asError(): ApiResult<Nothing> = ApiResult.Error(this)

/**
 * Runs [block] and wraps the result in [ApiResult.Success], or catches any [Exception]
 * and converts it to [ApiResult.Error] via [errorMapper].
 *
 * ```kotlin
 * val result: ApiResult<User> = apiResultOf(
 *     errorMapper = { e -> DomainError.NetworkError(cause = e) }
 * ) {
 *     api.fetchUser(id)
 * }
 * ```
 *
 * @param errorMapper Maps any [Exception] thrown by [block] to a [DomainError].
 * @param block       Suspending or non-suspending block producing the success value.
 */
inline fun <T> apiResultOf(
    errorMapper: (Exception) -> DomainError = { e ->
        DomainError.NetworkError(message = e.message ?: "Unknown error", cause = e)
    },
    block: () -> T
): ApiResult<T> = try {
    ApiResult.Success(block())
} catch (e: Exception) {
    ApiResult.Error(errorMapper(e))
}
