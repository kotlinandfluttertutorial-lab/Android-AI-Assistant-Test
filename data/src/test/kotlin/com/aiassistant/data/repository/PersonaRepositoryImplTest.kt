/**
 * PersonaRepositoryImplTest.kt — data module
 *
 * Purpose: Unit tests for [PersonaRepositoryImpl], covering:
 *   - getPersonas()           — emits Loading then Success / Error / NetworkUnavailable
 *   - createPersona()         — Success, Error, NetworkUnavailable
 *   - updatePersona()         — Success, Error, NetworkUnavailable
 *   - deletePersona()         — delegates directly to remoteSource
 *   - getPersonaCount()       — Success (size of list), Error, NetworkUnavailable
 *   - getSelectedPersonaId()  — reads from PersonaPreferencesRepository
 *   - setSelectedPersonaId()  — writes to PersonaPreferencesRepository, always succeeds
 *
 * Architecture: data module — pure JVM unit tests, no Android framework dependencies.
 *
 * Test toolchain:
 * - Kotest DescribeSpec  — test structure
 * - MockK                — mock PersonaRemoteDataSource, PersonaPreferencesRepository
 * - Turbine              — Flow collection assertions
 * - kotlinx.coroutines.test — runTest
 *
 * Requirements covered: 32.1, 32.2, 32.3, 32.6
 */
package com.aiassistant.data.repository

import app.cash.turbine.test
import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.data.remote.persona.PersonaCreateRequest
import com.aiassistant.data.remote.persona.PersonaRemoteDataSource
import com.aiassistant.data.remote.persona.PersonaResponse
import com.aiassistant.data.remote.persona.PersonaUpdateRequest
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.model.PersonaTone
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest

// ─── Fixtures ─────────────────────────────────────────────────────────────────

private fun fakePersonaResponse(
    id: String = "persona-1",
    userId: String = "user-1",
    name: String = "Code Reviewer",
    systemPrompt: String = "Review code carefully.",
    tone: String = "professional",
    scopeDescription: String? = "Code review tasks",
    adminLocked: Boolean = false,
    allowedRoles: List<String> = emptyList(),
    createdAt: Long = 1_000_000L,
    updatedAt: Long = 2_000_000L
) = PersonaResponse(
    id = id,
    userId = userId,
    name = name,
    systemPrompt = systemPrompt,
    tone = tone,
    scopeDescription = scopeDescription,
    adminLocked = adminLocked,
    allowedRoles = allowedRoles,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun fakeDomainPersona(
    id: String = "persona-1",
    userId: String = "user-1",
    name: String = "Code Reviewer",
    systemPrompt: String = "Review code carefully.",
    tone: PersonaTone = PersonaTone.PROFESSIONAL,
    scopeDescription: String? = "Code review tasks"
) = Persona(
    id = id,
    userId = userId,
    name = name,
    systemPrompt = systemPrompt,
    tone = tone,
    scopeDescription = scopeDescription,
    createdAt = 1_000_000L,
    updatedAt = 2_000_000L
)

private fun fakeServerError() = DomainError.ServerError(message = "Internal server error", httpStatusCode = 500)

// ─── Spec ─────────────────────────────────────────────────────────────────────

class PersonaRepositoryImplTest :
    DescribeSpec({

        val remoteSource: PersonaRemoteDataSource = mockk()
        val prefsRepository: PersonaPreferencesRepository = mockk()
        val dispatchers = TestDispatcherProvider()

        lateinit var repository: PersonaRepositoryImpl

        beforeEach {
            clearAllMocks()
            repository = PersonaRepositoryImpl(remoteSource, prefsRepository, dispatchers)
        }

        // ── getPersonas() ──────────────────────────────────────────────────────────

        describe("getPersonas()") {

            it("emits Loading then Success with mapped domain list") {
                runTest {
                    val response = fakePersonaResponse()
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Success(listOf(response))

                    repository.getPersonas().test {
                        awaitItem() shouldBe ApiResult.Loading

                        val success = awaitItem()
                        success.shouldBeInstanceOf<ApiResult.Success<List<Persona>>>()
                        val personas = (success as ApiResult.Success).data
                        personas.size shouldBe 1
                        personas[0].id shouldBe "persona-1"
                        personas[0].name shouldBe "Code Reviewer"
                        personas[0].tone shouldBe PersonaTone.PROFESSIONAL

                        awaitComplete()
                    }
                }
            }

            it("emits Loading then propagates Error from remoteSource") {
                runTest {
                    val error = fakeServerError()
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Error(error)

                    repository.getPersonas().test {
                        awaitItem() shouldBe ApiResult.Loading

                        val result = awaitItem()
                        result.shouldBeInstanceOf<ApiResult.Error>()
                        (result as ApiResult.Error).error shouldBe error

                        awaitComplete()
                    }
                }
            }

            it("emits Loading then NetworkUnavailable when offline") {
                runTest {
                    coEvery { remoteSource.getPersonas() } returns ApiResult.NetworkUnavailable

                    repository.getPersonas().test {
                        awaitItem() shouldBe ApiResult.Loading
                        awaitItem() shouldBe ApiResult.NetworkUnavailable
                        awaitComplete()
                    }
                }
            }

            it("maps persona with unknown tone to PROFESSIONAL default") {
                runTest {
                    val response = fakePersonaResponse(tone = "unknown_tone")
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Success(listOf(response))

                    repository.getPersonas().test {
                        awaitItem() // Loading
                        val success = awaitItem() as ApiResult.Success
                        success.data[0].tone shouldBe PersonaTone.PROFESSIONAL
                        awaitComplete()
                    }
                }
            }

            it("returns empty list when remote returns empty") {
                runTest {
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Success(emptyList())

                    repository.getPersonas().test {
                        awaitItem() // Loading
                        val success = awaitItem() as ApiResult.Success
                        success.data shouldBe emptyList()
                        awaitComplete()
                    }
                }
            }
        }

        // ── createPersona() ───────────────────────────────────────────────────────

        describe("createPersona()") {

            it("returns Success with mapped domain Persona on success") {
                runTest {
                    val persona = fakeDomainPersona()
                    val response = fakePersonaResponse()
                    coEvery {
                        remoteSource.createPersona(
                            PersonaCreateRequest(
                                name = persona.name,
                                systemPrompt = persona.systemPrompt,
                                tone = persona.tone.value,
                                scopeDescription = persona.scopeDescription,
                                allowedRoles = persona.allowedRoles
                            )
                        )
                    } returns ApiResult.Success(response)

                    val result = repository.createPersona(persona)

                    result.shouldBeInstanceOf<ApiResult.Success<Persona>>()
                    (result as ApiResult.Success).data.id shouldBe "persona-1"
                }
            }

            it("propagates Error from remoteSource") {
                runTest {
                    val persona = fakeDomainPersona()
                    val error = DomainError.ValidationError(message = "Name too long")
                    coEvery { remoteSource.createPersona(any()) } returns ApiResult.Error(error)

                    val result = repository.createPersona(persona)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    val persona = fakeDomainPersona()
                    coEvery { remoteSource.createPersona(any()) } returns ApiResult.NetworkUnavailable

                    val result = repository.createPersona(persona)

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }

            it("passes correct tone value from PersonaTone to remote request") {
                runTest {
                    val persona = fakeDomainPersona(tone = PersonaTone.CREATIVE)
                    val response = fakePersonaResponse(tone = "creative")
                    coEvery {
                        remoteSource.createPersona(match { it.tone == "creative" })
                    } returns ApiResult.Success(response)

                    val result = repository.createPersona(persona)

                    result.shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
            }
        }

        // ── updatePersona() ───────────────────────────────────────────────────────

        describe("updatePersona()") {

            it("returns Success with updated mapped domain Persona") {
                runTest {
                    val persona = fakeDomainPersona(name = "Updated Name")
                    val response = fakePersonaResponse(name = "Updated Name")
                    coEvery {
                        remoteSource.updatePersona(
                            "persona-1",
                            PersonaUpdateRequest(
                                name = persona.name,
                                systemPrompt = persona.systemPrompt,
                                tone = persona.tone.value,
                                scopeDescription = persona.scopeDescription,
                                allowedRoles = persona.allowedRoles
                            )
                        )
                    } returns ApiResult.Success(response)

                    val result = repository.updatePersona(persona)

                    result.shouldBeInstanceOf<ApiResult.Success<Persona>>()
                    (result as ApiResult.Success).data.name shouldBe "Updated Name"
                }
            }

            it("propagates Error from remoteSource") {
                runTest {
                    val persona = fakeDomainPersona()
                    val error = DomainError.Forbidden()
                    coEvery { remoteSource.updatePersona(any(), any()) } returns ApiResult.Error(error)

                    val result = repository.updatePersona(persona)

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    val persona = fakeDomainPersona()
                    coEvery { remoteSource.updatePersona(any(), any()) } returns ApiResult.NetworkUnavailable

                    val result = repository.updatePersona(persona)

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }

        // ── deletePersona() ───────────────────────────────────────────────────────

        describe("deletePersona()") {

            it("delegates to remoteSource and returns Success") {
                runTest {
                    coEvery { remoteSource.deletePersona("persona-1") } returns ApiResult.Success(Unit)

                    val result = repository.deletePersona("persona-1")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { remoteSource.deletePersona("persona-1") }
                }
            }

            it("propagates Error from remoteSource") {
                runTest {
                    val error = DomainError.ServerError(message = "Not found", httpStatusCode = 404)
                    coEvery { remoteSource.deletePersona(any()) } returns ApiResult.Error(error)

                    val result = repository.deletePersona("missing-id")

                    result.shouldBeInstanceOf<ApiResult.Error>()
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    coEvery { remoteSource.deletePersona(any()) } returns ApiResult.NetworkUnavailable

                    val result = repository.deletePersona("persona-1")

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }

        // ── getPersonaCount() ─────────────────────────────────────────────────────

        describe("getPersonaCount()") {

            it("returns Success with count equal to list size") {
                runTest {
                    val responses =
                        listOf(fakePersonaResponse("p-1"), fakePersonaResponse("p-2"), fakePersonaResponse("p-3"))
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Success(responses)

                    val result = repository.getPersonaCount()

                    result shouldBe ApiResult.Success(3)
                }
            }

            it("returns Success(0) for empty list") {
                runTest {
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Success(emptyList())

                    val result = repository.getPersonaCount()

                    result shouldBe ApiResult.Success(0)
                }
            }

            it("propagates Error when getPersonas fails") {
                runTest {
                    val error = fakeServerError()
                    coEvery { remoteSource.getPersonas() } returns ApiResult.Error(error)

                    val result = repository.getPersonaCount()

                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            it("returns NetworkUnavailable when offline") {
                runTest {
                    coEvery { remoteSource.getPersonas() } returns ApiResult.NetworkUnavailable

                    val result = repository.getPersonaCount()

                    result shouldBe ApiResult.NetworkUnavailable
                }
            }
        }

        // ── getSelectedPersonaId() ────────────────────────────────────────────────

        describe("getSelectedPersonaId()") {

            it("returns Success wrapping the persona ID from prefsRepository") {
                runTest {
                    coEvery { prefsRepository.getSelectedPersonaId() } returns "persona-42"

                    val result = repository.getSelectedPersonaId()

                    result shouldBe ApiResult.Success("persona-42")
                }
            }

            it("returns Success(null) when no persona is selected") {
                runTest {
                    coEvery { prefsRepository.getSelectedPersonaId() } returns null

                    val result = repository.getSelectedPersonaId()

                    result shouldBe ApiResult.Success(null)
                }
            }
        }

        // ── setSelectedPersonaId() ────────────────────────────────────────────────

        describe("setSelectedPersonaId()") {

            it("saves the persona ID to prefsRepository and returns Success(Unit)") {
                runTest {
                    coEvery { prefsRepository.saveSelectedPersonaId("persona-7") } returns Unit

                    val result = repository.setSelectedPersonaId("persona-7")

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { prefsRepository.saveSelectedPersonaId("persona-7") }
                }
            }

            it("saves null to prefsRepository when clearing selection and returns Success(Unit)") {
                runTest {
                    coEvery { prefsRepository.saveSelectedPersonaId(null) } returns Unit

                    val result = repository.setSelectedPersonaId(null)

                    result shouldBe ApiResult.Success(Unit)
                    coVerify(exactly = 1) { prefsRepository.saveSelectedPersonaId(null) }
                }
            }
        }
    })
