/**
 * AuthRepositoryImplTest.kt â€” data module
 *
 * Purpose: Comprehensive unit tests for [AuthRepositoryImpl], verifying that every
 *          method correctly orchestrates the remote [AuthApiService], the local
 *          [SecureStorage], and the [ConnectivityObserver], and that all error paths
 *          are mapped to the expected [DomainError] subtypes.
 *
 * Architecture: data module â€” unit tests (pure JVM, no Android framework).
 *               All Android dependencies are replaced with MockK fakes or simple
 *               test doubles.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  â€” test structure and assertions
 * - MockK                â€” mocking AuthApiService, SecureStorage, ConnectivityObserver
 * - kotlinx.coroutines.test â€” runTest + UnconfinedTestDispatcher for coroutine control
 *
 * Requirements covered: 1.1 (registration), 1.2 (JWT + refresh issuance),
 *                       1.3 (silent refresh), 1.10 (logout invalidates tokens).
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.auth.AuthApiService
import com.aiassistant.data.remote.auth.AuthResponse
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException

// â”€â”€â”€ Test doubles â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

// â”€â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

/**
 * Builds a real [HttpException] for a given HTTP [code].
 *
 * [retrofit2.Response.error] wraps the code in a proper OkHttp [okhttp3.Response],
 * which is what Retrofit's [HttpException] expects.
 */
private fun httpException(code: Int): HttpException {
    val body = "{}".toResponseBody("application/json".toMediaType())
    val response = retrofit2.Response.error<Any>(code, body)
    return HttpException(response)
}

/**
 * Factory for a fully-populated [AuthResponse] with sensible defaults.
 *
 * Individual fields can be overridden per test to keep assertions focused.
 */
private fun fakeAuthResponse(
    accessToken: String = "jwt-token",
    refreshToken: String = "refresh-token",
    accessTokenExpiresAt: Long = 1_000_000L,
    refreshTokenExpiresAt: Long = 30_000_000L
) = AuthResponse(
    accessToken = accessToken,
    refreshToken = refreshToken,
    accessTokenExpiresAt = accessTokenExpiresAt,
    refreshTokenExpiresAt = refreshTokenExpiresAt
)

// â”€â”€â”€ Spec â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

class AuthRepositoryImplTest :
    DescribeSpec({

        // â”€â”€ Shared mocks â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        val authApiService: AuthApiService = mockk()
        val secureStorage: SecureStorage = mockk(relaxed = true) // save*/clearAll never throw
        val connectivityObserver: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()

        // SUT â€” recreated with fresh mocks before each describe block by clearAllMocks
        lateinit var repository: AuthRepositoryImpl

        // Convenience: wire up a fake flow on the connectivity observer so MockK is happy
        // even when individual tests only stub isConnected().
        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            repository = AuthRepositoryImpl(
                authApiService = authApiService,
                secureStorage = secureStorage,
                connectivityObserver = connectivityObserver,
                dispatchers = dispatchers
            )
        }

        // â”€â”€â”€ login() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("login()") {

            describe("success path") {
                it("returns ApiResult.Success with correctly mapped AuthTokens") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "access-123",
                            refreshToken = "refresh-456",
                            accessTokenExpiresAt = 1_111_111L,
                            refreshTokenExpiresAt = 9_999_999L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } returns response

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val tokens = (result as ApiResult.Success).data
                        tokens.jwt shouldBe "access-123"
                        tokens.refreshToken shouldBe "refresh-456"
                        tokens.jwtExpiresAt shouldBe 1_111_111L
                        tokens.refreshExpiresAt shouldBe 9_999_999L
                    }
                }

                it("persists jwt and refreshToken via SecureStorage") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "access-abc",
                            refreshToken = "refresh-xyz"
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } returns response

                        repository.login("user@example.com", "password123")

                        verify(exactly = 1) { secureStorage.saveJwt("access-abc") }
                        verify(exactly = 1) { secureStorage.saveRefreshToken("refresh-xyz") }
                    }
                }
            }

            describe("offline") {
                it("returns ApiResult.NetworkUnavailable without calling the API") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.login("user@example.com", "password123")

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { authApiService.login(any()) }
                    }
                }

                it("does not touch SecureStorage when offline") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.login("user@example.com", "password123")

                        verify(exactly = 0) { secureStorage.saveJwt(any()) }
                        verify(exactly = 0) { secureStorage.saveRefreshToken(any()) }
                    }
                }
            }

            describe("HTTP 401") {
                it("returns ApiResult.Error with DomainError.Unauthorized") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws httpException(401)

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                    }
                }

                it("does not save tokens on HTTP 401") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws httpException(401)

                        repository.login("user@example.com", "password123")

                        verify(exactly = 0) { secureStorage.saveJwt(any()) }
                        verify(exactly = 0) { secureStorage.saveRefreshToken(any()) }
                    }
                }
            }

            describe("HTTP 403") {
                it("returns ApiResult.Error with DomainError.Forbidden") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws httpException(403)

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                    }
                }
            }

            describe("HTTP 422 (4xx validation error)") {
                it("returns ApiResult.Error with DomainError.ValidationError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws httpException(422)

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                    }
                }
            }

            describe("HTTP 500 (5xx server error)") {
                it("returns ApiResult.Error with DomainError.ServerError carrying the status code") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws httpException(500)

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        val error = (result as ApiResult.Error).error
                        error.shouldBeInstanceOf<DomainError.ServerError>()
                        (error as DomainError.ServerError).httpStatusCode shouldBe 500
                    }
                }
            }

            describe("IOException") {
                it("returns ApiResult.Error with DomainError.NetworkError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.login(any()) } throws java.io.IOException("connection reset")

                        val result = repository.login("user@example.com", "password123")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                    }
                }
            }
        }

        // â”€â”€â”€ register() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("register()") {

            describe("success path") {
                it("returns ApiResult.Success with correctly mapped AuthTokens") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "reg-access",
                            refreshToken = "reg-refresh",
                            accessTokenExpiresAt = 2_000_000L,
                            refreshTokenExpiresAt = 60_000_000L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.register(any()) } returns response

                        val result = repository.register("new@example.com", "newpassword123")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val tokens = (result as ApiResult.Success).data
                        tokens.jwt shouldBe "reg-access"
                        tokens.refreshToken shouldBe "reg-refresh"
                        tokens.jwtExpiresAt shouldBe 2_000_000L
                        tokens.refreshExpiresAt shouldBe 60_000_000L
                    }
                }

                it("persists jwt and refreshToken via SecureStorage") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "reg-jwt",
                            refreshToken = "reg-rt"
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.register(any()) } returns response

                        repository.register("new@example.com", "newpassword123")

                        verify(exactly = 1) { secureStorage.saveJwt("reg-jwt") }
                        verify(exactly = 1) { secureStorage.saveRefreshToken("reg-rt") }
                    }
                }
            }

            describe("offline") {
                it("returns ApiResult.NetworkUnavailable without calling the API") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.register("new@example.com", "newpassword123")

                        result shouldBe ApiResult.NetworkUnavailable
                        coVerify(exactly = 0) { authApiService.register(any()) }
                    }
                }
            }

            describe("HTTP 400 (validation error)") {
                it("returns ApiResult.Error with DomainError.ValidationError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.register(any()) } throws httpException(400)

                        val result = repository.register("bad-email", "short")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                    }
                }
            }
        }

        // â”€â”€â”€ refreshToken() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("refreshToken()") {

            describe("success path") {
                it("returns ApiResult.Success with fresh AuthTokens") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "new-jwt",
                            refreshToken = "new-refresh",
                            accessTokenExpiresAt = 3_000_000L,
                            refreshTokenExpiresAt = 90_000_000L
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } returns response

                        val result = repository.refreshToken("old-refresh-token")

                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val tokens = (result as ApiResult.Success).data
                        tokens.jwt shouldBe "new-jwt"
                        tokens.refreshToken shouldBe "new-refresh"
                        tokens.jwtExpiresAt shouldBe 3_000_000L
                        tokens.refreshExpiresAt shouldBe 90_000_000L
                    }
                }

                it("persists the new tokens via SecureStorage") {
                    runTest {
                        val response = fakeAuthResponse(
                            accessToken = "refreshed-jwt",
                            refreshToken = "refreshed-rt"
                        )
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } returns response

                        repository.refreshToken("old-refresh-token")

                        verify(exactly = 1) { secureStorage.saveJwt("refreshed-jwt") }
                        verify(exactly = 1) { secureStorage.saveRefreshToken("refreshed-rt") }
                    }
                }
            }

            describe("offline") {
                it("returns ApiResult.NetworkUnavailable and does not touch storage") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.refreshToken("some-refresh-token")

                        result shouldBe ApiResult.NetworkUnavailable
                        verify(exactly = 0) { secureStorage.saveJwt(any()) }
                        verify(exactly = 0) { secureStorage.saveRefreshToken(any()) }
                        verify(exactly = 0) { secureStorage.clearAll() }
                    }
                }
            }

            describe("HTTP 401") {
                it("returns ApiResult.Error with DomainError.Unauthorized") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(401)

                        val result = repository.refreshToken("expired-token")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                    }
                }

                it("calls SecureStorage.clearAll() exactly once on HTTP 401") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(401)

                        repository.refreshToken("expired-token")

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("HTTP 403") {
                it("returns ApiResult.Error with DomainError.Forbidden") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(403)

                        val result = repository.refreshToken("forbidden-token")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                    }
                }

                it("calls SecureStorage.clearAll() exactly once on HTTP 403") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(403)

                        repository.refreshToken("forbidden-token")

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("HTTP 500 (server error)") {
                it("returns ApiResult.Error with DomainError.ServerError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(500)

                        val result = repository.refreshToken("my-refresh")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                    }
                }

                it("does NOT call SecureStorage.clearAll() on HTTP 500") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws httpException(500)

                        repository.refreshToken("my-refresh")

                        verify(exactly = 0) { secureStorage.clearAll() }
                    }
                }
            }

            describe("IOException") {
                it("returns ApiResult.Error with DomainError.NetworkError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws java.io.IOException("socket timeout")

                        val result = repository.refreshToken("my-refresh")

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                    }
                }

                it("does NOT call SecureStorage.clearAll() on IOException") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.refresh(any()) } throws java.io.IOException("socket timeout")

                        repository.refreshToken("my-refresh")

                        verify(exactly = 0) { secureStorage.clearAll() }
                    }
                }
            }
        }

        // â”€â”€â”€ logout() â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("logout()") {

            describe("success (online)") {
                it("returns ApiResult.Success(Unit) when remote logout succeeds") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } returns Unit

                        val result = repository.logout()

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("calls SecureStorage.clearAll() exactly once on successful remote logout") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } returns Unit

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("offline") {
                it("returns ApiResult.Success(Unit) without calling the API") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        val result = repository.logout()

                        result shouldBe ApiResult.Success(Unit)
                        coVerify(exactly = 0) { authApiService.logout() }
                    }
                }

                it("still calls SecureStorage.clearAll() when offline (best-effort local clear)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns false

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("remote HTTP 401 (session already expired)") {
                it("treats 401 as success and returns ApiResult.Success(Unit)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(401)

                        val result = repository.logout()

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("calls SecureStorage.clearAll() on remote HTTP 401") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(401)

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("remote HTTP 403") {
                it("treats 403 as success and returns ApiResult.Success(Unit)") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(403)

                        val result = repository.logout()

                        result shouldBe ApiResult.Success(Unit)
                    }
                }

                it("calls SecureStorage.clearAll() on remote HTTP 403") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(403)

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("remote HTTP 500 (server error)") {
                it("returns ApiResult.Error with DomainError.ServerError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(500)

                        val result = repository.logout()

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                    }
                }

                it("still calls SecureStorage.clearAll() even on HTTP 500") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws httpException(500)

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }

            describe("remote IOException") {
                it("returns ApiResult.Error with DomainError.NetworkError") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws java.io.IOException("read timeout")

                        val result = repository.logout()

                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.NetworkError>()
                    }
                }

                it("still calls SecureStorage.clearAll() on IOException") {
                    runTest {
                        every { connectivityObserver.isConnected() } returns true
                        coEvery { authApiService.logout() } throws java.io.IOException("read timeout")

                        repository.logout()

                        verify(exactly = 1) { secureStorage.clearAll() }
                    }
                }
            }
        }

        // â”€â”€â”€ Token storage verification â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
        describe("token storage") {

            it("login stores access_token as JWT and refresh_token as refreshToken") {
                runTest {
                    val response = fakeAuthResponse(
                        accessToken = "stored-jwt",
                        refreshToken = "stored-rt"
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { authApiService.login(any()) } returns response

                    repository.login("user@example.com", "pass")

                    verify { secureStorage.saveJwt("stored-jwt") }
                    verify { secureStorage.saveRefreshToken("stored-rt") }
                }
            }

            it("register stores access_token as JWT and refresh_token as refreshToken") {
                runTest {
                    val response = fakeAuthResponse(
                        accessToken = "reg-stored-jwt",
                        refreshToken = "reg-stored-rt"
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { authApiService.register(any()) } returns response

                    repository.register("new@example.com", "newpass")

                    verify { secureStorage.saveJwt("reg-stored-jwt") }
                    verify { secureStorage.saveRefreshToken("reg-stored-rt") }
                }
            }

            it("refreshToken stores the new access_token as JWT and new refresh_token as refreshToken") {
                runTest {
                    val response = fakeAuthResponse(
                        accessToken = "new-stored-jwt",
                        refreshToken = "new-stored-rt"
                    )
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { authApiService.refresh(any()) } returns response

                    repository.refreshToken("old-rt")

                    verify { secureStorage.saveJwt("new-stored-jwt") }
                    verify { secureStorage.saveRefreshToken("new-stored-rt") }
                }
            }
        }
    })
