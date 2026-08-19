/**
 * AuthUseCaseTest.kt — domain module unit tests
 *
 * Tests for authentication use cases:
 *   - [LoginUseCase]       — delegates to AuthRepository; surfaces token output and errors
 *   - [RegisterUseCase]    — validates email format and password length (≥12 chars) before
 *                            delegating to AuthRepository; returns ValidationError on bad input
 *   - [RefreshTokenUseCase] — delegates to AuthRepository; surfaces fresh token output
 *
 * Requirements: 21.1 (Unit tests for domain use cases)
 * Related requirements: 1.1 (registration validation), 1.2 (token issuance), 1.3 (token refresh)
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for AuthRepository mocking
 * No Android framework dependencies — pure JVM tests.
 */

package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private const val VALID_EMAIL = "user@example.com"
private const val VALID_PASSWORD = "SuperSecret123" // 14 chars — ≥12 requirement satisfied
private const val VALID_REFRESH_TOKEN = "refresh-token-abc123"
private val SAMPLE_TOKENS = AuthTokens(
    jwt = "jwt.sample.token",
    refreshToken = "refresh.sample.token",
    jwtExpiresAt = System.currentTimeMillis() + 900_000L, // +15 min
    refreshExpiresAt = System.currentTimeMillis() + 2_592_000_000L // +30 days
)

// ─── LoginUseCase ──────────────────────────────────────────────────────────────

class LoginUseCaseTest :
    DescribeSpec({

        val authRepository = mockk<AuthRepository>()
        val loginUseCase = LoginUseCase(authRepository)

        beforeEach {
            clearMocks(authRepository)
        }

        describe("LoginUseCase") {

            describe("successful login") {

                it("returns Success with AuthTokens when repository returns Success") {
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                    (result as ApiResult.Success<AuthTokens>).data shouldBe SAMPLE_TOKENS
                }

                it("returned AuthTokens contain non-blank JWT") {
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD) as ApiResult.Success<AuthTokens>

                    result.data.jwt.isNotBlank() shouldBe true
                }

                it("returned AuthTokens contain non-blank refreshToken") {
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD) as ApiResult.Success<AuthTokens>

                    result.data.refreshToken.isNotBlank() shouldBe true
                }

                it("delegates to repository exactly once with the provided credentials") {
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    coVerify(exactly = 1) { authRepository.login(VALID_EMAIL, VALID_PASSWORD) }
                }
            }

            describe("failed login") {

                it("returns Error when repository returns Unauthorized") {
                    val error = DomainError.Unauthorized()
                    coEvery { authRepository.login(VALID_EMAIL, "wrongPassword123") } returns
                        ApiResult.Error(error)

                    val result = loginUseCase(VALID_EMAIL, "wrongPassword123")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }

                it("returns Error when repository returns ServerError") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Error(error)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ServerError>()
                }

                it("returns NetworkUnavailable when repository returns NetworkUnavailable") {
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.NetworkUnavailable

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates the exact error from repository without modification") {
                    val error = DomainError.ServerError(httpStatusCode = 503, message = "Service unavailable")
                    coEvery { authRepository.login(any(), any()) } returns ApiResult.Error(error)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    (result as ApiResult.Error).error shouldBe error
                }

                it("returns Error when repository returns Forbidden (HTTP 403)") {
                    val error = DomainError.Forbidden()
                    coEvery { authRepository.login(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Error(error)

                    val result = loginUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }

                it("passes empty strings to repository without local validation") {
                    // LoginUseCase has no client-side validation — blank inputs go to the repo
                    coEvery { authRepository.login("", "") } returns
                        ApiResult.Error(DomainError.Unauthorized())

                    val result = loginUseCase("", "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    coVerify(exactly = 1) { authRepository.login("", "") }
                }
            }
        }
    })

// ─── RegisterUseCase ──────────────────────────────────────────────────────────

class RegisterUseCaseTest :
    DescribeSpec({

        val authRepository = mockk<AuthRepository>()
        val registerUseCase = RegisterUseCase(authRepository)

        beforeEach {
            clearMocks(authRepository)
        }

        describe("RegisterUseCase") {

            describe("successful registration") {

                it("returns Success with AuthTokens for valid email and password") {
                    coEvery { authRepository.register(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = registerUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                    (result as ApiResult.Success<AuthTokens>).data shouldBe SAMPLE_TOKENS
                }

                it("delegates to repository exactly once when inputs are valid") {
                    coEvery { authRepository.register(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    registerUseCase(VALID_EMAIL, VALID_PASSWORD)

                    coVerify(exactly = 1) { authRepository.register(VALID_EMAIL, VALID_PASSWORD) }
                }

                it("accepts password of exactly 12 characters") {
                    val twelveCharPassword = "Exactly12chr"
                    coEvery { authRepository.register(VALID_EMAIL, twelveCharPassword) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = registerUseCase(VALID_EMAIL, twelveCharPassword)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                    coVerify(exactly = 1) { authRepository.register(VALID_EMAIL, twelveCharPassword) }
                }

                it("accepts email with subdomain") {
                    val subdomainEmail = "user@mail.example.co.uk"
                    coEvery { authRepository.register(subdomainEmail, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = registerUseCase(subdomainEmail, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                }

                it("accepts email with dots in local part") {
                    val dottedEmail = "first.last@example.com"
                    coEvery { authRepository.register(dottedEmail, VALID_PASSWORD) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    val result = registerUseCase(dottedEmail, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                }
            }

            describe("email validation") {

                it("returns ValidationError when email has no '@' character") {
                    val result = registerUseCase("notanemail.com", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error
                    error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when email has no domain after '@'") {
                    val result = registerUseCase("user@", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when email domain has no dot") {
                    val result = registerUseCase("user@domain", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when email has no local part before '@'") {
                    val result = registerUseCase("@example.com", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when email is blank") {
                    val result = registerUseCase("", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when email is whitespace-only (trim collapses to blank)") {
                    // EMAIL_REGEX is applied to email.trim() — "   ".trim() = "" which fails
                    val result = registerUseCase("   ", VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                    coVerify(exactly = 0) { authRepository.register(any(), any()) }
                }

                it("email ValidationError includes 'email' field in fields map") {
                    val result = registerUseCase("bad-email", VALID_PASSWORD)

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey RegisterUseCase.FIELD_EMAIL
                }

                it("does NOT call repository when email is invalid") {
                    registerUseCase("not-an-email", VALID_PASSWORD)

                    coVerify(exactly = 0) { authRepository.register(any(), any()) }
                }
            }

            describe("password validation") {

                it("returns ValidationError when password is shorter than 12 characters") {
                    val shortPassword = "Short1!" // 7 chars
                    val result = registerUseCase(VALID_EMAIL, shortPassword)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError for password of exactly 11 characters") {
                    val elevenCharPassword = "Eleven1char" // exactly 11 chars
                    val result = registerUseCase(VALID_EMAIL, elevenCharPassword)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("returns ValidationError when password is empty") {
                    val result = registerUseCase(VALID_EMAIL, "")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }

                it("password ValidationError includes 'password' field in fields map") {
                    val result = registerUseCase(VALID_EMAIL, "tooshort")

                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey RegisterUseCase.FIELD_PASSWORD
                }

                it("does NOT call repository when password is too short") {
                    registerUseCase(VALID_EMAIL, "tooshort")

                    coVerify(exactly = 0) { authRepository.register(any(), any()) }
                }

                it("email is validated before password — email error wins when both are invalid") {
                    // Invalid email + short password: should return email ValidationError
                    val result = registerUseCase("bad-email", "short")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    // Email error is returned first per the implementation ordering
                    error.fields shouldContainKey RegisterUseCase.FIELD_EMAIL
                }
            }

            describe("repository error propagation") {

                it("propagates NetworkUnavailable from repository on valid inputs") {
                    coEvery { authRepository.register(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.NetworkUnavailable

                    val result = registerUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("propagates ServerError from repository on valid inputs") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { authRepository.register(VALID_EMAIL, VALID_PASSWORD) } returns
                        ApiResult.Error(error)

                    val result = registerUseCase(VALID_EMAIL, VALID_PASSWORD)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── RefreshTokenUseCase ──────────────────────────────────────────────────────

class RefreshTokenUseCaseTest :
    DescribeSpec({

        val authRepository = mockk<AuthRepository>()
        val refreshTokenUseCase = RefreshTokenUseCase(authRepository)

        beforeEach {
            clearMocks(authRepository)
        }

        describe("RefreshTokenUseCase") {

            describe("successful refresh") {

                it("returns Success with fresh AuthTokens when repository succeeds") {
                    val freshTokens = SAMPLE_TOKENS.copy(
                        jwt = "new.jwt.token",
                        refreshToken = "new.refresh.token"
                    )
                    coEvery { authRepository.refreshToken(VALID_REFRESH_TOKEN) } returns
                        ApiResult.Success(freshTokens)

                    val result = refreshTokenUseCase(VALID_REFRESH_TOKEN)

                    result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                    (result as ApiResult.Success<AuthTokens>).data shouldBe freshTokens
                }

                it("returned fresh AuthTokens contain non-blank JWT") {
                    val freshTokens = SAMPLE_TOKENS.copy(jwt = "fresh.jwt.value")
                    coEvery { authRepository.refreshToken(VALID_REFRESH_TOKEN) } returns
                        ApiResult.Success(freshTokens)

                    val result = refreshTokenUseCase(VALID_REFRESH_TOKEN) as ApiResult.Success<AuthTokens>

                    result.data.jwt.isNotBlank() shouldBe true
                }

                it("delegates to repository exactly once with the provided refresh token") {
                    coEvery { authRepository.refreshToken(VALID_REFRESH_TOKEN) } returns
                        ApiResult.Success(SAMPLE_TOKENS)

                    refreshTokenUseCase(VALID_REFRESH_TOKEN)

                    coVerify(exactly = 1) { authRepository.refreshToken(VALID_REFRESH_TOKEN) }
                }
            }

            describe("failed refresh") {

                it("returns Error when refresh token is invalid or expired") {
                    val error = DomainError.Unauthorized(message = "Refresh token expired")
                    coEvery { authRepository.refreshToken("expired-token") } returns
                        ApiResult.Error(error)

                    val result = refreshTokenUseCase("expired-token")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Unauthorized>()
                }

                it("returns NetworkUnavailable when device has no connectivity") {
                    coEvery { authRepository.refreshToken(VALID_REFRESH_TOKEN) } returns
                        ApiResult.NetworkUnavailable

                    val result = refreshTokenUseCase(VALID_REFRESH_TOKEN)

                    result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }

                it("returns Error when repository returns ServerError") {
                    val error = DomainError.ServerError(httpStatusCode = 503)
                    coEvery { authRepository.refreshToken(VALID_REFRESH_TOKEN) } returns
                        ApiResult.Error(error)

                    val result = refreshTokenUseCase(VALID_REFRESH_TOKEN)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }

                it("propagates the exact error from repository without modification") {
                    val error = DomainError.Unauthorized(message = "Token revoked")
                    coEvery { authRepository.refreshToken(any()) } returns ApiResult.Error(error)

                    val result = refreshTokenUseCase(VALID_REFRESH_TOKEN)

                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
