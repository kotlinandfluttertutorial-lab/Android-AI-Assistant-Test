/**
 * FederationProviderTest.kt — data module
 *
 * Purpose: Unit tests for [UserRoleProviderImpl] and [UserRegionProviderImpl], covering:
 *
 *   UserRoleProviderImpl:
 *     - getRole() returns the user's role string when a user exists in Room
 *     - getRole() returns DEFAULT_ROLE ("user") when no user is stored
 *     - getRole() returns DEFAULT_ROLE for any role value (premium, admin, etc.)
 *
 *   UserRegionProviderImpl:
 *     - getRegion() always returns DEFAULT_REGION ("") per current schema note
 *     - getRegion() returns "" regardless of whether a user exists in Room
 *
 * Architecture: data module — pure JVM unit tests, no Robolectric or Android framework.
 *               Both impls use runBlocking internally; tests invoke getRole()/getRegion()
 *               directly on the calling thread (safe because UserDao is mocked).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock UserDao (suspend fun getFirstUser)
 *
 * Requirements covered: 35.1, 35.2, 35.4
 */
package com.aiassistant.data.federation

import com.aiassistant.core.database.dao.UserDao
import com.aiassistant.core.database.entity.UserEntity
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.mockk

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakeUserEntity(id: String = "user-1", role: String = "user") = UserEntity(
    id = id,
    email = "test@example.com",
    displayName = "Test User",
    avatarUrl = null,
    role = role,
    activeProvider = "openai",
    themeMode = "system",
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class FederationProviderTest :
    DescribeSpec({

        val userDao: UserDao = mockk()

        beforeEach {
            clearAllMocks()
        }

        // ── UserRoleProviderImpl ───────────────────────────────────────────────────

        describe("UserRoleProviderImpl.getRole()") {

            it("returns the user's role string when a user exists in Room") {
                coEvery { userDao.getFirstUser() } returns fakeUserEntity(role = "user")

                val provider = UserRoleProviderImpl(userDao)

                provider.getRole() shouldBe "user"
            }

            it("returns 'premium' when user has premium role") {
                coEvery { userDao.getFirstUser() } returns fakeUserEntity(role = "premium")

                val provider = UserRoleProviderImpl(userDao)

                provider.getRole() shouldBe "premium"
            }

            it("returns 'admin' when user has admin role") {
                coEvery { userDao.getFirstUser() } returns fakeUserEntity(role = "admin")

                val provider = UserRoleProviderImpl(userDao)

                provider.getRole() shouldBe "admin"
            }

            it("returns DEFAULT_ROLE ('user') when no user is stored in Room") {
                coEvery { userDao.getFirstUser() } returns null

                val provider = UserRoleProviderImpl(userDao)

                provider.getRole() shouldBe "user"
            }

            it("returns DEFAULT_ROLE for each call when Room stays empty") {
                coEvery { userDao.getFirstUser() } returns null

                val provider = UserRoleProviderImpl(userDao)

                // Calling getRole() multiple times should remain stable
                provider.getRole() shouldBe "user"
                provider.getRole() shouldBe "user"
            }
        }

        // ── UserRegionProviderImpl ─────────────────────────────────────────────────

        describe("UserRegionProviderImpl.getRegion()") {

            it("returns empty string (DEFAULT_REGION) when a user exists in Room") {
                // Schema note: regionTag is not yet stored on UserEntity;
                // implementation always returns DEFAULT_REGION regardless of the user.
                coEvery { userDao.getFirstUser() } returns fakeUserEntity()

                val provider = UserRegionProviderImpl(userDao)

                provider.getRegion() shouldBe ""
            }

            it("returns empty string (DEFAULT_REGION) when no user is stored in Room") {
                coEvery { userDao.getFirstUser() } returns null

                val provider = UserRegionProviderImpl(userDao)

                provider.getRegion() shouldBe ""
            }

            it("returns empty string regardless of user role") {
                coEvery { userDao.getFirstUser() } returns fakeUserEntity(role = "admin")

                val provider = UserRegionProviderImpl(userDao)

                provider.getRegion() shouldBe ""
            }

            it("returns consistent empty string on repeated calls") {
                coEvery { userDao.getFirstUser() } returns fakeUserEntity()

                val provider = UserRegionProviderImpl(userDao)

                provider.getRegion() shouldBe ""
                provider.getRegion() shouldBe ""
            }
        }
    })
