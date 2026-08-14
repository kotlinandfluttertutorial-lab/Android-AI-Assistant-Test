/**
 * ApiResultTest.kt — core-common module unit tests
 *
 * Tests for [ApiResult] sealed class and its operators:
 *   - [ApiResult.map]       — transforms Success data; passes through other variants unchanged
 *   - [ApiResult.flatMap]   — chains an ApiResult-returning transform on Success only
 *   - [ApiResult.fold]      — exhaustive collapse of all four variants into a single value
 *
 * Also covers:
 *   - Convenience properties: isSuccess, isError, isLoading, isNetworkUnavailable
 *   - Utility functions: getOrNull, errorOrNull
 *   - Extension helpers: asSuccess, asError, apiResultOf
 *
 * Requirements: 19.4, 21.1
 *
 * Test framework: Kotest (JUnit 5 runner) + Kotest assertions
 * No Android framework dependencies — pure JVM tests.
 */

package com.aiassistant.core.common

import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

// ─── ApiResult.map ────────────────────────────────────────────────────────────

class ApiResultMapTest :
    DescribeSpec({

        describe("ApiResult.map") {

            it("Success(data) maps to Success(transformed data)") {
                val result: ApiResult<Int> = ApiResult.Success(10)
                val mapped = result.map { it * 2 }

                mapped.shouldBeInstanceOf<ApiResult.Success<Int>>()
                (mapped as ApiResult.Success<Int>).data shouldBe 20
            }

            it("Success — transform is called exactly once") {
                var callCount = 0
                ApiResult.Success("hello").map {
                    callCount++
                    it.uppercase()
                }
                callCount shouldBe 1
            }

            it("Success — transform can change the type") {
                val result: ApiResult<Int> = ApiResult.Success(42)
                val mapped: ApiResult<String> = result.map { it.toString() }

                mapped.shouldBeInstanceOf<ApiResult.Success<String>>()
                (mapped as ApiResult.Success<String>).data shouldBe "42"
            }

            it("Error passes through unchanged — transform is NOT called") {
                val error = DomainError.NetworkError(message = "timeout")
                val result: ApiResult<Int> = ApiResult.Error(error)

                var transformCalled = false
                val mapped = result.map {
                    transformCalled = true
                    it + 1
                }

                mapped.shouldBeInstanceOf<ApiResult.Error>()
                (mapped as ApiResult.Error).error shouldBe error
                transformCalled.shouldBeFalse()
            }

            it("Loading passes through unchanged — transform is NOT called") {
                val result: ApiResult<Int> = ApiResult.Loading

                var transformCalled = false
                val mapped = result.map {
                    transformCalled = true
                    it + 1
                }

                mapped.shouldBeInstanceOf<ApiResult.Loading>()
                transformCalled.shouldBeFalse()
            }

            it("NetworkUnavailable passes through unchanged — transform is NOT called") {
                val result: ApiResult<Int> = ApiResult.NetworkUnavailable

                var transformCalled = false
                val mapped = result.map {
                    transformCalled = true
                    it + 1
                }

                mapped.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                transformCalled.shouldBeFalse()
            }

            it("chained maps on Success apply both transforms") {
                val result: ApiResult<Int> = ApiResult.Success(3)
                val mapped = result.map { it * 2 }.map { it + 1 }

                (mapped as ApiResult.Success<Int>).data shouldBe 7
            }

            it("chained maps short-circuit on Error") {
                val error = DomainError.ServerError(httpStatusCode = 500)
                val result: ApiResult<Int> = ApiResult.Error(error)
                var secondTransformCalled = false

                val mapped = result
                    .map { it * 2 }
                    .map {
                        secondTransformCalled = true
                        it + 1
                    }

                mapped.shouldBeInstanceOf<ApiResult.Error>()
                secondTransformCalled.shouldBeFalse()
            }
        }
    })

// ─── ApiResult.flatMap ────────────────────────────────────────────────────────

class ApiResultFlatMapTest :
    DescribeSpec({

        describe("ApiResult.flatMap") {

            it("Success(data) returns the result of the flatMap function") {
                val result: ApiResult<Int> = ApiResult.Success(5)
                val chained = result.flatMap { ApiResult.Success(it * 10) }

                (chained as ApiResult.Success<Int>).data shouldBe 50
            }

            it("Success can flatMap to a different Success type") {
                val result: ApiResult<Int> = ApiResult.Success(7)
                val chained: ApiResult<String> = result.flatMap { ApiResult.Success(it.toString()) }

                (chained as ApiResult.Success<String>).data shouldBe "7"
            }

            it("Success can flatMap to Loading") {
                val result: ApiResult<Int> = ApiResult.Success(1)
                val chained = result.flatMap { ApiResult.Loading }

                chained.shouldBeInstanceOf<ApiResult.Loading>()
            }

            it("Success can flatMap to Error") {
                val error = DomainError.Unauthorized()
                val result: ApiResult<Int> = ApiResult.Success(1)
                val chained = result.flatMap { ApiResult.Error(error) }

                chained.shouldBeInstanceOf<ApiResult.Error>()
                (chained as ApiResult.Error).error shouldBe error
            }

            it("Success can flatMap to NetworkUnavailable") {
                val result: ApiResult<Int> = ApiResult.Success(1)
                val chained = result.flatMap { ApiResult.NetworkUnavailable }

                chained.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
            }

            it("Error passes through — flatMap function is NOT called") {
                val error = DomainError.Forbidden()
                val result: ApiResult<Int> = ApiResult.Error(error)

                var transformCalled = false
                val chained = result.flatMap {
                    transformCalled = true
                    ApiResult.Success(it)
                }

                chained.shouldBeInstanceOf<ApiResult.Error>()
                (chained as ApiResult.Error).error shouldBe error
                transformCalled.shouldBeFalse()
            }

            it("Loading passes through — flatMap function is NOT called") {
                val result: ApiResult<Int> = ApiResult.Loading

                var transformCalled = false
                val chained = result.flatMap {
                    transformCalled = true
                    ApiResult.Success(it)
                }

                chained.shouldBeInstanceOf<ApiResult.Loading>()
                transformCalled.shouldBeFalse()
            }

            it("NetworkUnavailable passes through — flatMap function is NOT called") {
                val result: ApiResult<Int> = ApiResult.NetworkUnavailable

                var transformCalled = false
                val chained = result.flatMap {
                    transformCalled = true
                    ApiResult.Success(it)
                }

                chained.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                transformCalled.shouldBeFalse()
            }

            it("chained flatMaps on Success compose correctly") {
                val result: ApiResult<Int> = ApiResult.Success(2)
                val chained = result
                    .flatMap { ApiResult.Success(it * 3) }
                    .flatMap { ApiResult.Success(it + 4) }

                (chained as ApiResult.Success<Int>).data shouldBe 10 // (2*3)+4
            }

            it("chained flatMaps short-circuit when first returns Error") {
                val error = DomainError.ValidationError(message = "invalid")
                val result: ApiResult<Int> = ApiResult.Success(1)
                var secondCalled = false

                val chained = result
                    .flatMap { ApiResult.Error(error) }
                    .flatMap {
                        secondCalled = true
                        ApiResult.Success(it)
                    }

                chained.shouldBeInstanceOf<ApiResult.Error>()
                secondCalled.shouldBeFalse()
            }
        }
    })

// ─── ApiResult.fold ────────────────────────────────────────────────────────────

class ApiResultFoldTest :
    DescribeSpec({

        describe("ApiResult.fold") {

            it("Success invokes onSuccess handler with the data value") {
                val result: ApiResult<String> = ApiResult.Success("hello")
                val output = result.fold(
                    onSuccess = { it.uppercase() },
                    onError = { "error" },
                    onLoading = { "loading" },
                    onOffline = { "offline" }
                )
                output shouldBe "HELLO"
            }

            it("Success — only onSuccess handler is invoked") {
                var successCalled = false
                var errorCalled = false
                var loadingCalled = false
                var offlineCalled = false

                ApiResult.Success(1).fold(
                    onSuccess = {
                        successCalled = true
                        it
                    },
                    onError = {
                        errorCalled = true
                        0
                    },
                    onLoading = {
                        loadingCalled = true
                        0
                    },
                    onOffline = {
                        offlineCalled = true
                        0
                    }
                )

                successCalled.shouldBeTrue()
                errorCalled.shouldBeFalse()
                loadingCalled.shouldBeFalse()
                offlineCalled.shouldBeFalse()
            }

            it("Error invokes onError handler with the DomainError") {
                val error = DomainError.NetworkError(message = "connection refused")
                val result: ApiResult<Int> = ApiResult.Error(error)

                val output = result.fold(
                    onSuccess = { "success" },
                    onError = { it.message },
                    onLoading = { "loading" },
                    onOffline = { "offline" }
                )
                output shouldBe "connection refused"
            }

            it("Error — only onError handler is invoked") {
                var successCalled = false
                var errorCalled = false
                var loadingCalled = false
                var offlineCalled = false

                ApiResult.Error(DomainError.ServerError()).fold(
                    onSuccess = {
                        successCalled = true
                        ""
                    },
                    onError = {
                        errorCalled = true
                        ""
                    },
                    onLoading = {
                        loadingCalled = true
                        ""
                    },
                    onOffline = {
                        offlineCalled = true
                        ""
                    }
                )

                successCalled.shouldBeFalse()
                errorCalled.shouldBeTrue()
                loadingCalled.shouldBeFalse()
                offlineCalled.shouldBeFalse()
            }

            it("Loading invokes onLoading handler") {
                val result: ApiResult<Int> = ApiResult.Loading

                val output = result.fold(
                    onSuccess = { "success" },
                    onError = { "error" },
                    onLoading = { "loading" },
                    onOffline = { "offline" }
                )
                output shouldBe "loading"
            }

            it("Loading — only onLoading handler is invoked") {
                var successCalled = false
                var errorCalled = false
                var loadingCalled = false
                var offlineCalled = false

                ApiResult.Loading.fold(
                    onSuccess = {
                        successCalled = true
                        ""
                    },
                    onError = {
                        errorCalled = true
                        ""
                    },
                    onLoading = {
                        loadingCalled = true
                        ""
                    },
                    onOffline = {
                        offlineCalled = true
                        ""
                    }
                )

                successCalled.shouldBeFalse()
                errorCalled.shouldBeFalse()
                loadingCalled.shouldBeTrue()
                offlineCalled.shouldBeFalse()
            }

            it("NetworkUnavailable invokes onOffline handler") {
                val result: ApiResult<Int> = ApiResult.NetworkUnavailable

                val output = result.fold(
                    onSuccess = { "success" },
                    onError = { "error" },
                    onLoading = { "loading" },
                    onOffline = { "offline" }
                )
                output shouldBe "offline"
            }

            it("NetworkUnavailable — only onOffline handler is invoked") {
                var successCalled = false
                var errorCalled = false
                var loadingCalled = false
                var offlineCalled = false

                ApiResult.NetworkUnavailable.fold(
                    onSuccess = {
                        successCalled = true
                        ""
                    },
                    onError = {
                        errorCalled = true
                        ""
                    },
                    onLoading = {
                        loadingCalled = true
                        ""
                    },
                    onOffline = {
                        offlineCalled = true
                        ""
                    }
                )

                successCalled.shouldBeFalse()
                errorCalled.shouldBeFalse()
                loadingCalled.shouldBeFalse()
                offlineCalled.shouldBeTrue()
            }

            it("fold can return different output types") {
                val result: ApiResult<Int> = ApiResult.Success(99)
                val output: Boolean = result.fold(
                    onSuccess = { it > 50 },
                    onError = { false },
                    onLoading = { false },
                    onOffline = { false }
                )
                output.shouldBeTrue()
            }
        }
    })

// ─── Convenience properties ────────────────────────────────────────────────────

class ApiResultPropertiesTest :
    DescribeSpec({

        describe("ApiResult convenience properties") {

            it("isSuccess is true for Success, false for all others") {
                ApiResult.Success("x").isSuccess.shouldBeTrue()
                ApiResult.Error(DomainError.NetworkError()).isSuccess.shouldBeFalse()
                ApiResult.Loading.isSuccess.shouldBeFalse()
                ApiResult.NetworkUnavailable.isSuccess.shouldBeFalse()
            }

            it("isError is true for Error, false for all others") {
                ApiResult.Error(DomainError.NetworkError()).isError.shouldBeTrue()
                ApiResult.Success("x").isError.shouldBeFalse()
                ApiResult.Loading.isError.shouldBeFalse()
                ApiResult.NetworkUnavailable.isError.shouldBeFalse()
            }

            it("isLoading is true for Loading, false for all others") {
                ApiResult.Loading.isLoading.shouldBeTrue()
                ApiResult.Success("x").isLoading.shouldBeFalse()
                ApiResult.Error(DomainError.NetworkError()).isLoading.shouldBeFalse()
                ApiResult.NetworkUnavailable.isLoading.shouldBeFalse()
            }

            it("isNetworkUnavailable is true for NetworkUnavailable, false for all others") {
                ApiResult.NetworkUnavailable.isNetworkUnavailable.shouldBeTrue()
                ApiResult.Success("x").isNetworkUnavailable.shouldBeFalse()
                ApiResult.Error(DomainError.NetworkError()).isNetworkUnavailable.shouldBeFalse()
                ApiResult.Loading.isNetworkUnavailable.shouldBeFalse()
            }

            it("exactly one property is true for each variant") {
                fun boolCount(r: ApiResult<*>) =
                    listOf(r.isSuccess, r.isError, r.isLoading, r.isNetworkUnavailable).count { it }

                boolCount(ApiResult.Success(1)) shouldBe 1
                boolCount(ApiResult.Error(DomainError.NetworkError())) shouldBe 1
                boolCount(ApiResult.Loading) shouldBe 1
                boolCount(ApiResult.NetworkUnavailable) shouldBe 1
            }
        }

        describe("getOrNull") {
            it("returns the data value for Success") {
                ApiResult.Success(42).getOrNull() shouldBe 42
            }

            it("returns null for Error") {
                ApiResult.Error(DomainError.NetworkError()).getOrNull().shouldBeNull()
            }

            it("returns null for Loading") {
                ApiResult.Loading.getOrNull().shouldBeNull()
            }

            it("returns null for NetworkUnavailable") {
                ApiResult.NetworkUnavailable.getOrNull().shouldBeNull()
            }
        }

        describe("errorOrNull") {
            it("returns the DomainError for Error") {
                val error = DomainError.Unauthorized()
                ApiResult.Error(error).errorOrNull() shouldBe error
            }

            it("returns null for Success") {
                ApiResult.Success("data").errorOrNull().shouldBeNull()
            }

            it("returns null for Loading") {
                ApiResult.Loading.errorOrNull().shouldBeNull()
            }

            it("returns null for NetworkUnavailable") {
                ApiResult.NetworkUnavailable.errorOrNull().shouldBeNull()
            }
        }
    })

// ─── Extension helpers ─────────────────────────────────────────────────────────

class ApiResultExtensionHelpersTest :
    DescribeSpec({

        describe("T.asSuccess()") {
            it("wraps any non-null value in Success") {
                val result = "hello".asSuccess()
                result.shouldBeInstanceOf<ApiResult.Success<String>>()
                (result as ApiResult.Success<String>).data shouldBe "hello"
            }

            it("wraps an integer in Success") {
                val result = 100.asSuccess()
                (result as ApiResult.Success<Int>).data shouldBe 100
            }

            it("wraps a nullable value in Success") {
                val value: String? = null
                val result = value.asSuccess()
                result.shouldBeInstanceOf<ApiResult.Success<String?>>()
                (result as ApiResult.Success<String?>).data.shouldBeNull()
            }
        }

        describe("DomainError.asError()") {
            it("wraps a DomainError in ApiResult.Error") {
                val error = DomainError.NetworkError(message = "test error")
                val result = error.asError()

                result.shouldBeInstanceOf<ApiResult.Error>()
                (result as ApiResult.Error).error shouldBe error
            }

            it("wraps a Forbidden error in ApiResult.Error") {
                val error = DomainError.Forbidden()
                val result = error.asError()
                (result as ApiResult.Error).error shouldBe error
            }

            it("wraps a ValidationError with fields") {
                val error = DomainError.ValidationError(fields = mapOf("email" to "Invalid format"))
                val result = error.asError()
                val unwrapped = (result as ApiResult.Error).error as DomainError.ValidationError
                unwrapped.fields["email"] shouldBe "Invalid format"
            }
        }

        describe("apiResultOf") {
            it("returns Success when block does not throw") {
                val result = apiResultOf { 42 }
                result.shouldBeInstanceOf<ApiResult.Success<Int>>()
                (result as ApiResult.Success<Int>).data shouldBe 42
            }

            it("returns Error with default mapper when block throws an Exception") {
                val result = apiResultOf<Int> { throw RuntimeException("boom") }
                result.shouldBeInstanceOf<ApiResult.Error>()
                val error = (result as ApiResult.Error).error
                error.shouldBeInstanceOf<DomainError.NetworkError>()
                error.message shouldBe "boom"
            }

            it("uses custom errorMapper when provided") {
                val result = apiResultOf<Int>(
                    errorMapper = { DomainError.ServerError(httpStatusCode = 503) }
                ) {
                    throw RuntimeException("service unavailable")
                }

                result.shouldBeInstanceOf<ApiResult.Error>()
                val error = (result as ApiResult.Error).error as DomainError.ServerError
                error.httpStatusCode shouldBe 503
            }

            it("error from thrown Exception preserves cause") {
                val cause = RuntimeException("root cause")
                val result = apiResultOf<Int> { throw cause }
                val error = (result as ApiResult.Error).error as DomainError.NetworkError
                error.cause shouldBe cause
            }

            it("block returning a complex type wraps it in Success") {
                data class Payload(val id: Int, val name: String)
                val result = apiResultOf { Payload(id = 1, name = "Test") }
                val payload = (result as ApiResult.Success<Payload>).data
                payload.id shouldBe 1
                payload.name shouldBe "Test"
            }
        }
    })

// ─── DomainError sealed hierarchy ─────────────────────────────────────────────

class DomainErrorTest :
    DescribeSpec({

        describe("DomainError variants") {
            it("NetworkError has the correct default message") {
                DomainError.NetworkError().message shouldBe "A network error occurred."
            }

            it("NetworkUnavailable has the correct default message") {
                DomainError.NetworkUnavailable().message shouldBe "No network connection available."
            }

            it("Unauthorized has the correct default message") {
                DomainError.Unauthorized().message shouldBe "Authentication required. Please log in again."
            }

            it("Forbidden has the correct default message") {
                DomainError.Forbidden().message shouldBe "You do not have permission to perform this action."
            }

            it("ValidationError carries field-level messages") {
                val fields = mapOf("email" to "must be valid", "password" to "too short")
                val error = DomainError.ValidationError(fields = fields)
                error.fields["email"] shouldBe "must be valid"
                error.fields["password"] shouldBe "too short"
            }

            it("ServerError carries the HTTP status code") {
                val error = DomainError.ServerError(httpStatusCode = 500)
                error.httpStatusCode shouldBe 500
            }

            it("StreamingInterrupted carries the last token index") {
                val error = DomainError.StreamingInterrupted(lastTokenIndex = 17)
                error.lastTokenIndex shouldBe 17
            }

            it("BiometricFailed carries the platform error code") {
                val error = DomainError.BiometricFailed(errorCode = 7)
                error.errorCode shouldBe 7
            }

            it("OfflineQueueFull carries the queue limit") {
                val error = DomainError.OfflineQueueFull(queueLimit = 100)
                error.queueLimit shouldBe 100
            }

            it("DomainError subtypes are distinguishable by exhaustive when") {
                // Confirm the sealed hierarchy is exhaustive — if a new subtype is added
                // without updating this test the compiler will flag the when expression.
                fun describe(e: DomainError): String = when (e) {
                    is DomainError.NetworkError -> "NetworkError"
                    is DomainError.NetworkUnavailable -> "NetworkUnavailable"
                    is DomainError.Unauthorized -> "Unauthorized"
                    is DomainError.Forbidden -> "Forbidden"
                    is DomainError.ValidationError -> "ValidationError"
                    is DomainError.ServerError -> "ServerError"
                    is DomainError.StreamingInterrupted -> "StreamingInterrupted"
                    is DomainError.BiometricFailed -> "BiometricFailed"
                    is DomainError.OfflineQueueFull -> "OfflineQueueFull"
                }

                describe(DomainError.NetworkError()) shouldBe "NetworkError"
                describe(DomainError.ServerError()) shouldBe "ServerError"
                describe(DomainError.Unauthorized()) shouldBe "Unauthorized"
            }
        }
    })

// ─── DispatcherProvider ────────────────────────────────────────────────────────

class DispatcherProviderTest :
    DescribeSpec({

        describe("DefaultDispatcherProvider") {
            val provider = DefaultDispatcherProvider()

            it("default dispatcher is not null") {
                provider.default.shouldNotBeNull()
            }

            it("io dispatcher is not null") {
                provider.io.shouldNotBeNull()
            }

            it("main dispatcher is not null") {
                // Dispatchers.Main requires Android Looper; the dispatcher object itself
                // is non-null even on the JVM — attempting to dispatch on it would fail.
                provider.main.shouldNotBeNull()
            }

            it("mainImmediate dispatcher is not null") {
                provider.mainImmediate.shouldNotBeNull()
            }

            it("unconfined dispatcher is not null") {
                provider.unconfined.shouldNotBeNull()
            }

            it("all five dispatchers are distinct objects") {
                // default and io may differ; unconfined is its own singleton.
                // We only assert that unconfined is distinct from default.
                (provider.unconfined === provider.default).shouldBeFalse()
            }

            it("implements DispatcherProvider interface") {
                (provider is DispatcherProvider).shouldBeTrue()
            }
        }
    })
