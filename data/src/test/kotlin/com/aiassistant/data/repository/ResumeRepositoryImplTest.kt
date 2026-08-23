/**
 * ResumeRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [ResumeRepositoryImpl], covering all four delegated operations:
 *   - generateResume() — delegates to remoteSource
 *   - generateCoverLetter() — delegates to remoteSource
 *   - generateEmail() — delegates to remoteSource
 *   - correctGrammar() — delegates to remoteSource
 *
 * Architecture: data module — unit tests (pure JVM, no Android framework).
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mocking ResumeRemoteDataSource
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 14.1, 14.2, 14.4, 14.5
 */
package com.aiassistant.data.repository

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.remote.resume.ResumeRemoteDataSource
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

class ResumeRepositoryImplTest :
    DescribeSpec({

        val remoteSource: ResumeRemoteDataSource = mockk()

        lateinit var repository: ResumeRepositoryImpl

        beforeEach {
            clearAllMocks()
            repository = ResumeRepositoryImpl(remoteSource = remoteSource)
        }

        // ─── generateResume() ─────────────────────────────────────────────────────

        describe("generateResume()") {
            it("returns Success with Markdown resume when remote succeeds") {
                runTest {
                    coEvery {
                        remoteSource.generateResume("5 years Android dev", "Senior Engineer at Acme")
                    } returns ApiResult.Success("# John Doe\n\n## Experience\n...")

                    val result = repository.generateResume("5 years Android dev", "Senior Engineer at Acme")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    (result as ApiResult.Success).data shouldBe "# John Doe\n\n## Experience\n..."
                }
            }

            it("delegates arguments correctly to remote source") {
                runTest {
                    coEvery {
                        remoteSource.generateResume(any(), any())
                    } returns ApiResult.Success("resume")

                    repository.generateResume("history", "job desc")

                    coVerify(exactly = 1) { remoteSource.generateResume("history", "job desc") }
                }
            }

            it("propagates remote error") {
                runTest {
                    coEvery {
                        remoteSource.generateResume(any(), any())
                    } returns ApiResult.Error(DomainError.ServerError("Timeout", 504))

                    val result = repository.generateResume("history", "job")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                }
            }
        }

        // ─── generateCoverLetter() ────────────────────────────────────────────────

        describe("generateCoverLetter()") {
            it("returns Success with cover letter text") {
                runTest {
                    coEvery {
                        remoteSource.generateCoverLetter(any(), any())
                    } returns ApiResult.Success("Dear Hiring Manager, ...")

                    val result = repository.generateCoverLetter("my history", "job desc")

                    result shouldBe ApiResult.Success("Dear Hiring Manager, ...")
                }
            }

            it("delegates arguments correctly") {
                runTest {
                    coEvery { remoteSource.generateCoverLetter(any(), any()) } returns
                        ApiResult.Success("letter")

                    repository.generateCoverLetter("prof history", "target job")

                    coVerify(exactly = 1) { remoteSource.generateCoverLetter("prof history", "target job") }
                }
            }

            it("propagates NetworkUnavailable when remote returns it") {
                runTest {
                    coEvery { remoteSource.generateCoverLetter(any(), any()) } returns
                        ApiResult.NetworkUnavailable

                    val result = repository.generateCoverLetter("hist", "job")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }

        // ─── generateEmail() ──────────────────────────────────────────────────────

        describe("generateEmail()") {
            it("returns Success with full email text") {
                runTest {
                    coEvery {
                        remoteSource.generateEmail("project update", "inform stakeholders")
                    } returns ApiResult.Success("Subject: Update\n\nDear Team,\n\n...")

                    val result = repository.generateEmail("project update", "inform stakeholders")

                    result.shouldBeInstanceOf<ApiResult.Success<*>>()
                    ((result as ApiResult.Success).data).shouldBe("Subject: Update\n\nDear Team,\n\n...")
                }
            }

            it("delegates context and intent to remote source") {
                runTest {
                    coEvery { remoteSource.generateEmail(any(), any()) } returns
                        ApiResult.Success("email text")

                    repository.generateEmail("ctx", "intent")

                    coVerify(exactly = 1) { remoteSource.generateEmail("ctx", "intent") }
                }
            }
        }

        // ─── correctGrammar() ─────────────────────────────────────────────────────

        describe("correctGrammar()") {
            it("returns Success with corrected email text") {
                runTest {
                    coEvery {
                        remoteSource.correctGrammar("I is happy to inform you.")
                    } returns ApiResult.Success("I am happy to inform you.")

                    val result = repository.correctGrammar("I is happy to inform you.")

                    result shouldBe ApiResult.Success("I am happy to inform you.")
                }
            }

            it("delegates draft email to remote source") {
                runTest {
                    coEvery { remoteSource.correctGrammar(any()) } returns ApiResult.Success("corrected")

                    repository.correctGrammar("raw draft")

                    coVerify(exactly = 1) { remoteSource.correctGrammar("raw draft") }
                }
            }

            it("propagates remote error") {
                runTest {
                    coEvery { remoteSource.correctGrammar(any()) } returns
                        ApiResult.Error(DomainError.ValidationError("Empty input"))

                    val result = repository.correctGrammar("")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
            }
        }
    })
