package com.aiassistant.domain.usecase.auth

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.AuthTokens
import com.aiassistant.domain.repository.AuthRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk

class LoginWithGoogleUseCaseTest :
    DescribeSpec({

        val authRepository = mockk<AuthRepository>()
        val useCase = LoginWithGoogleUseCase(authRepository)

        val sampleTokens = AuthTokens(
            jwt = "google-jwt",
            refreshToken = "google-refresh",
            jwtExpiresAt = 123456789L,
            refreshExpiresAt = 987654321L
        )

        beforeEach {
            clearMocks(authRepository)
        }

        describe("LoginWithGoogleUseCase") {

            it("returns Success with AuthTokens when repository succeeds") {
                val idToken = "google-id-token"
                coEvery { authRepository.loginWithGoogle(idToken) } returns ApiResult.Success(sampleTokens)

                val result = useCase(idToken)

                result.shouldBeInstanceOf<ApiResult.Success<AuthTokens>>()
                (result as ApiResult.Success).data shouldBe sampleTokens
                coVerify(exactly = 1) { authRepository.loginWithGoogle(idToken) }
            }

            it("returns Error when repository fails") {
                val error = DomainError.Unauthorized("Invalid Google token")
                coEvery { authRepository.loginWithGoogle(any()) } returns ApiResult.Error(error)

                val result = useCase("invalid-token")

                result.shouldBeInstanceOf<ApiResult.Error>()
                (result as ApiResult.Error).error shouldBe error
            }

            it("returns NetworkUnavailable when device is offline") {
                coEvery { authRepository.loginWithGoogle(any()) } returns ApiResult.NetworkUnavailable

                val result = useCase("token")

                result.shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
            }
        }
    })
