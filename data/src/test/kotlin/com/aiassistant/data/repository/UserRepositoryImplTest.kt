/**
 * UserRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [UserRepositoryImpl], covering:
 *   - getCurrentUser() — emits domain User from Room
 *   - updateActiveProvider() — optimistic local update + remote sync; offline guard
 *   - updateThemeMode() — optimistic local update + remote sync; offline guard
 *   - updateDisplayName() — optimistic local update + remote sync; no-user guard
 *   - updateFcmToken() — delegates to remote service
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking UserDao, UserApiService, ConnectivityObserver, SecureStorage
 * - kotlinx.coroutines.test — runTest
 * - Turbine              — Flow collection
 *
 * Requirements covered: 3.2, 24.2
 */
package com.aiassistant.data.repository

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.database.entity.UserEntity
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.core.security.SecureStorage
import com.aiassistant.data.remote.user.UserApiService
import com.aiassistant.data.remote.user.UserResponse
import com.aiassistant.domain.model.ThemeMode
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

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeUserEntity(
    id: String = "user-1",
    email: String = "user@example.com",
    displayName: String = "Test User",
    avatarUrl: String? = null,
    role: String = "user",
    activeProvider: String = "openai_gpt4o",
    themeMode: String = "system",
    createdAt: Long = 1_000_000L,
    updatedAt: Long = 2_000_000L
) = UserEntity(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = avatarUrl,
    role = role,
    activeProvider = activeProvider,
    themeMode = themeMode,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun fakeUserResponse(
    id: String = "user-1",
    email: String = "user@example.com",
    displayName: String = "Test User",
    activeProvider: String = "openai_gpt4o",
    themeMode: String = "system"
) = UserResponse(
    id = id,
    email = email,
    displayName = displayName,
    avatarUrl = null,
    role = "user",
    activeProvider = activeProvider,
    themeMode = themeMode,
    createdAt = 1_000_000L,
    updatedAt = 3_000_000L
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class UserRepositoryImplTest :
    DescribeSpec({

        val userDao: UserDao = mockk(relaxed = true)
        val userApiService: UserApiService = mockk()
        val connectivityObserver: ConnectivityObserver = mockk()
        val dispatchers = TestDispatcherProvider()
        val secureStorage: SecureStorage = mockk(relaxed = true)

        lateinit var repository: UserRepositoryImpl

        beforeEach {
            clearAllMocks()
            every { connectivityObserver.isConnectedFlow } returns flowOf(true)
            every { secureStorage.isFcmTokenPendingSync() } returns false
            repository = UserRepositoryImpl(
                userDao = userDao,
                userApiService = userApiService,
                connectivityObserver = connectivityObserver,
                dispatchers = dispatchers,
                secureStorage = secureStorage
            )
        }

        // ─── getCurrentUser() ─────────────────────────────────────────────────────

        describe("getCurrentUser()") {
            it("emits ApiResult.Success with mapped User when entity exists") {
                runTest {
                    val entity = fakeUserEntity(id = "user-1", displayName = "Alice")
                    every { userDao.getAllUsers() } returns flowOf(entity)

                    repository.getCurrentUser().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        val user = (result as ApiResult.Success).data
                        user?.id shouldBe "user-1"
                        user?.displayName shouldBe "Alice"
                        awaitComplete()
                    }
                }
            }

            it("emits ApiResult.Success with null when no user is stored") {
                runTest {
                    every { userDao.getAllUsers() } returns flowOf(null)

                    repository.getCurrentUser().test {
                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Success<*>>()
                        (result as ApiResult.Success).data shouldBe null
                        awaitComplete()
                    }
                }
            }
        }

        // ─── updateActiveProvider() ───────────────────────────────────────────────

        describe("updateActiveProvider()") {
            it("applies optimistic local update immediately") {
                runTest {
                    val entity = fakeUserEntity(activeProvider = "openai_gpt4o")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { userApiService.updateActiveProvider(any()) } returns
                        fakeUserResponse(activeProvider = "gemini_15_pro")

                    repository.updateActiveProvider("gemini_15_pro")

                    coVerify(atLeast = 1) {
                        userDao.updateUser(match { it.activeProvider == "gemini_15_pro" })
                    }
                }
            }

            it("returns Success with updated User on remote success") {
                runTest {
                    val entity = fakeUserEntity()
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { userApiService.updateActiveProvider(any()) } returns
                        fakeUserResponse(activeProvider = "claude_35_sonnet")

                    val result = repository.updateActiveProvider("claude_35_sonnet")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.activeProvider shouldBe "claude_35_sonnet"
                }
            }

            it("returns local Success offline without calling remote") {
                runTest {
                    val entity = fakeUserEntity(activeProvider = "openai_gpt4o")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.updateActiveProvider("ollama")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.activeProvider shouldBe "ollama"
                    coVerify(exactly = 0) { userApiService.updateActiveProvider(any()) }
                }
            }

            it("returns Error with ValidationError when no active user found") {
                runTest {
                    coEvery { userDao.getFirstUser() } returns null
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.updateActiveProvider("openai_gpt4o")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        // ─── updateThemeMode() ────────────────────────────────────────────────────

        describe("updateThemeMode()") {
            it("applies optimistic local update with new themeMode") {
                runTest {
                    val entity = fakeUserEntity(themeMode = "system")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { userApiService.updateThemeMode(any()) } returns
                        fakeUserResponse(themeMode = "dark")

                    repository.updateThemeMode(ThemeMode.DARK)

                    coVerify(atLeast = 1) {
                        userDao.updateUser(match { it.themeMode == "dark" })
                    }
                }
            }

            it("returns local Success when offline") {
                runTest {
                    val entity = fakeUserEntity(themeMode = "system")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.updateThemeMode(ThemeMode.LIGHT)

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.themeMode shouldBe ThemeMode.LIGHT
                    coVerify(exactly = 0) { userApiService.updateThemeMode(any()) }
                }
            }

            it("returns Error when no active user found") {
                runTest {
                    coEvery { userDao.getFirstUser() } returns null
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.updateThemeMode(ThemeMode.DARK)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                }
            }
        }

        // ─── updateDisplayName() ──────────────────────────────────────────────────

        describe("updateDisplayName()") {
            it("applies local update and returns Success on remote success") {
                runTest {
                    val entity = fakeUserEntity(displayName = "Old Name")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns true
                    coEvery { userApiService.updateDisplayName(any()) } returns
                        fakeUserResponse(displayName = "New Name")

                    val result = repository.updateDisplayName("New Name")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.displayName shouldBe "New Name"
                    coVerify(atLeast = 1) {
                        userDao.updateUser(match { it.displayName == "New Name" })
                    }
                }
            }

            it("returns local Success with new displayName when offline") {
                runTest {
                    val entity = fakeUserEntity(displayName = "Old Name")
                    coEvery { userDao.getFirstUser() } returns entity
                    every { connectivityObserver.isConnected() } returns false

                    val result = repository.updateDisplayName("New Name")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data.displayName shouldBe "New Name"
                    coVerify(exactly = 0) { userApiService.updateDisplayName(any()) }
                }
            }

            it("returns ValidationError when no user is found in Room") {
                runTest {
                    coEvery { userDao.getFirstUser() } returns null
                    every { connectivityObserver.isConnected() } returns true

                    val result = repository.updateDisplayName("New Name")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }

        // ─── updateFcmToken() ─────────────────────────────────────────────────────

        describe("updateFcmToken()") {
            it("calls remote API and returns Success on success") {
                runTest {
                    coEvery { userApiService.updateFcmToken(any()) } returns Unit

                    val result = repository.updateFcmToken("fcm-token-xyz")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { userApiService.updateFcmToken(any()) }
                }
            }

            it("marks FCM token as synced in SecureStorage on success") {
                runTest {
                    coEvery { userApiService.updateFcmToken(any()) } returns Unit

                    repository.updateFcmToken("fcm-token-xyz")

                    verify(exactly = 1) { secureStorage.saveFcmTokenSynced() }
                }
            }
        }
    })
