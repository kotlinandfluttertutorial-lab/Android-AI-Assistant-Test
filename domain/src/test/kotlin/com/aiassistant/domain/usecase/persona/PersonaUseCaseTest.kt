/**
 * PersonaUseCaseTest.kt — domain module unit tests
 *
 * Tests for persona use cases:
 *   - CreatePersonaUseCase: validation (name, prompt, scopeDescription, 20-persona limit)
 *   - DeletePersonaUseCase: admin-locked enforcement
 *   - SelectPersonaUseCase: persistence of selected persona ID
 *
 * Requirements: 21.1
 * Related requirements: 32.1, 32.2, 32.3, 32.5, 32.6
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK for PersonaRepository
 */

package com.aiassistant.domain.usecase.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.model.PersonaTone
import com.aiassistant.domain.repository.PersonaRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val NOW = System.currentTimeMillis()

private val SAMPLE_PERSONA = Persona(
    id = "persona-001",
    userId = "user-1",
    name = "Professional Assistant",
    systemPrompt = "You are a professional AI assistant.",
    tone = PersonaTone.PROFESSIONAL,
    scopeDescription = "For business communications",
    adminLocked = false,
    allowedRoles = emptyList(),
    createdAt = NOW,
    updatedAt = NOW
)

private val ADMIN_LOCKED_PERSONA = SAMPLE_PERSONA.copy(
    id = "persona-admin",
    name = "Admin Template",
    adminLocked = true
)

// ─── CreatePersonaUseCase ─────────────────────────────────────────────────────

class CreatePersonaUseCaseTest :
    DescribeSpec({
        val repository = mockk<PersonaRepository>()
        val useCase = CreatePersonaUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("CreatePersonaUseCase") {
            describe("successful creation") {
                it("returns Success with Persona on valid input") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(SAMPLE_PERSONA) } returns ApiResult.Success(SAMPLE_PERSONA)
                    val result = useCase(SAMPLE_PERSONA)
                    result.shouldBeInstanceOf<ApiResult.Success<Persona>>()
                    (result as ApiResult.Success<Persona>).data shouldBe SAMPLE_PERSONA
                }
                it("delegates to repository exactly once after count check") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(SAMPLE_PERSONA) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(SAMPLE_PERSONA)
                    coVerify(exactly = 1) { repository.getPersonaCount() }
                    coVerify(exactly = 1) { repository.createPersona(SAMPLE_PERSONA) }
                }
            }

            describe("name validation") {
                it("returns ValidationError when name is blank") {
                    val result = useCase(SAMPLE_PERSONA.copy(name = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.ValidationError>()
                }
                it("ValidationError contains 'name' in fields map when blank") {
                    val result = useCase(SAMPLE_PERSONA.copy(name = ""))
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_NAME
                }
                it("returns ValidationError when name exceeds 80 characters") {
                    val longName = "a".repeat(81)
                    val result = useCase(SAMPLE_PERSONA.copy(name = longName))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_NAME
                }
                it("does NOT call repository when name is invalid") {
                    useCase(SAMPLE_PERSONA.copy(name = ""))
                    coVerify(exactly = 0) { repository.getPersonaCount() }
                    coVerify(exactly = 0) { repository.createPersona(any()) }
                }
            }

            describe("system prompt validation") {
                it("returns ValidationError when systemPrompt is blank") {
                    val result = useCase(SAMPLE_PERSONA.copy(systemPrompt = ""))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_SYSTEM_PROMPT
                }
                it("returns ValidationError when systemPrompt exceeds 4000 characters") {
                    val longPrompt = "a".repeat(4001)
                    val result = useCase(SAMPLE_PERSONA.copy(systemPrompt = longPrompt))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_SYSTEM_PROMPT
                }
                it("accepts systemPrompt at exactly 4000 characters") {
                    val maxPrompt = "a".repeat(4000)
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(any()) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(
                        SAMPLE_PERSONA.copy(systemPrompt = maxPrompt)
                    ).shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
                it("does NOT call repository when systemPrompt is invalid") {
                    useCase(SAMPLE_PERSONA.copy(systemPrompt = ""))
                    coVerify(exactly = 0) { repository.getPersonaCount() }
                    coVerify(exactly = 0) { repository.createPersona(any()) }
                }
            }

            describe("scope description validation") {
                it("accepts null scopeDescription") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(any()) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(
                        SAMPLE_PERSONA.copy(scopeDescription = null)
                    ).shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
                it("accepts empty scopeDescription") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(any()) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(SAMPLE_PERSONA.copy(scopeDescription = "")).shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
                it("returns ValidationError when scopeDescription exceeds 500 characters") {
                    val longScope = "a".repeat(501)
                    val result = useCase(SAMPLE_PERSONA.copy(scopeDescription = longScope))
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_SCOPE_DESCRIPTION
                }
                it("accepts scopeDescription at exactly 500 characters") {
                    val maxScope = "a".repeat(500)
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(any()) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(
                        SAMPLE_PERSONA.copy(scopeDescription = maxScope)
                    ).shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
            }

            describe("20-persona limit enforcement") {
                it("returns ValidationError when user already has 20 personas") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(20)
                    val result = useCase(SAMPLE_PERSONA)
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey CreatePersonaUseCase.FIELD_GENERAL
                }
                it("does NOT call createPersona when limit is reached") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(20)
                    useCase(SAMPLE_PERSONA)
                    coVerify(exactly = 0) { repository.createPersona(any()) }
                }
                it("accepts creation when user has 19 personas") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(19)
                    coEvery { repository.createPersona(SAMPLE_PERSONA) } returns ApiResult.Success(SAMPLE_PERSONA)
                    useCase(SAMPLE_PERSONA).shouldBeInstanceOf<ApiResult.Success<Persona>>()
                }
                it("propagates NetworkUnavailable from getPersonaCount") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_PERSONA).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from getPersonaCount") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.getPersonaCount() } returns ApiResult.Error(error)
                    val result = useCase(SAMPLE_PERSONA)
                    (result as ApiResult.Error).error shouldBe error
                }
            }

            describe("error propagation") {
                it("propagates NetworkUnavailable from createPersona") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    coEvery { repository.createPersona(any()) } returns ApiResult.NetworkUnavailable
                    useCase(SAMPLE_PERSONA).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from createPersona") {
                    coEvery { repository.getPersonaCount() } returns ApiResult.Success(5)
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.createPersona(any()) } returns ApiResult.Error(error)
                    val result = useCase(SAMPLE_PERSONA)
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── DeletePersonaUseCase ─────────────────────────────────────────────────────

class DeletePersonaUseCaseTest :
    DescribeSpec({
        val repository = mockk<PersonaRepository>()
        val useCase = DeletePersonaUseCase(repository)
        beforeEach { clearMocks(repository) }

        describe("DeletePersonaUseCase") {
            describe("successful deletion") {
                it("returns Success with Unit when persona is not admin-locked") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    coEvery { repository.deletePersona("persona-001") } returns ApiResult.Success(Unit)
                    val result = useCase("persona-001", isAdmin = false)
                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to repository exactly once with the given personaId") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    coEvery { repository.deletePersona("persona-001") } returns ApiResult.Success(Unit)
                    useCase("persona-001", isAdmin = false)
                    coVerify(exactly = 1) { repository.deletePersona("persona-001") }
                }
                it("allows admin to delete admin-locked persona") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(ADMIN_LOCKED_PERSONA)))
                    coEvery { repository.deletePersona("persona-admin") } returns ApiResult.Success(Unit)
                    val result = useCase("persona-admin", isAdmin = true)
                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
            }

            describe("admin-locked enforcement") {
                it("returns Forbidden when non-admin attempts to delete admin-locked persona") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(ADMIN_LOCKED_PERSONA)))
                    val result = useCase("persona-admin", isAdmin = false)
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    (result as ApiResult.Error).error.shouldBeInstanceOf<DomainError.Forbidden>()
                }
                it("does NOT call deletePersona when admin-locked and non-admin") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(ADMIN_LOCKED_PERSONA)))
                    useCase("persona-admin", isAdmin = false)
                    coVerify(exactly = 0) { repository.deletePersona(any()) }
                }
            }

            describe("persona not found") {
                it("returns ValidationError when persona does not exist") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(emptyList()))
                    val result = useCase("non-existent", isAdmin = false)
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey DeletePersonaUseCase.FIELD_PERSONA_ID
                }
                it("does NOT call deletePersona when persona not found") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(emptyList()))
                    useCase("non-existent", isAdmin = false)
                    coVerify(exactly = 0) { repository.deletePersona(any()) }
                }
            }

            describe("error propagation") {
                it("propagates NetworkUnavailable from getPersonas") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.NetworkUnavailable)
                    useCase("persona-001", isAdmin = false).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from getPersonas") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    every { repository.getPersonas() } returns flowOf(ApiResult.Error(error))
                    val result = useCase("persona-001", isAdmin = false)
                    (result as ApiResult.Error).error shouldBe error
                }
                it("propagates NetworkUnavailable from deletePersona") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    coEvery { repository.deletePersona(any()) } returns ApiResult.NetworkUnavailable
                    useCase("persona-001", isAdmin = false).shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from deletePersona") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    coEvery { repository.deletePersona(any()) } returns ApiResult.Error(error)
                    val result = useCase("persona-001", isAdmin = false)
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })

// ─── SelectPersonaUseCase ─────────────────────────────────────────────────────

class SelectPersonaUseCaseTest :
    DescribeSpec({
        val repository = mockk<PersonaRepository>()
        val preferencesRepository = mockk<com.aiassistant.domain.repository.PersonaPreferencesRepository>()
        val useCase = SelectPersonaUseCase(repository, preferencesRepository)
        beforeEach { clearMocks(repository, preferencesRepository) }

        describe("SelectPersonaUseCase") {
            describe("successful selection") {
                it("returns Success with Unit when selecting a valid persona") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    coEvery { preferencesRepository.saveSelectedPersonaId("persona-001") } returns Unit
                    val result = useCase("persona-001")
                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("persists selection via preferencesRepository exactly once") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(listOf(SAMPLE_PERSONA)))
                    coEvery { preferencesRepository.saveSelectedPersonaId("persona-001") } returns Unit
                    useCase("persona-001")
                    coVerify(exactly = 1) { preferencesRepository.saveSelectedPersonaId("persona-001") }
                }
            }

            describe("deselection (null personaId)") {
                it("returns Success with Unit when deselecting (personaId = null)") {
                    coEvery { preferencesRepository.saveSelectedPersonaId(null) } returns Unit
                    val result = useCase(null)
                    result.shouldBeInstanceOf<ApiResult.Success<Unit>>()
                }
                it("delegates to preferencesRepository with null to clear selection") {
                    coEvery { preferencesRepository.saveSelectedPersonaId(null) } returns Unit
                    useCase(null)
                    coVerify(exactly = 1) { preferencesRepository.saveSelectedPersonaId(null) }
                }
                it("does NOT call getPersonas when deselecting") {
                    coEvery { preferencesRepository.saveSelectedPersonaId(null) } returns Unit
                    useCase(null)
                    coVerify(exactly = 0) { repository.getPersonas() }
                }
            }

            describe("persona not found") {
                it("returns ValidationError when persona does not exist") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(emptyList()))
                    val result = useCase("non-existent")
                    result.shouldBeInstanceOf<ApiResult.Error>()
                    val error = (result as ApiResult.Error).error as DomainError.ValidationError
                    error.fields shouldContainKey SelectPersonaUseCase.FIELD_PERSONA_ID
                }
                it("does NOT persist selection when persona not found") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.Success(emptyList()))
                    useCase("non-existent")
                    coVerify(exactly = 0) { preferencesRepository.saveSelectedPersonaId(any()) }
                }
            }

            describe("error propagation") {
                it("propagates NetworkUnavailable from getPersonas") {
                    every { repository.getPersonas() } returns flowOf(ApiResult.NetworkUnavailable)
                    useCase("persona-001").shouldBeInstanceOf<ApiResult.NetworkUnavailable>()
                }
                it("propagates ServerError from getPersonas") {
                    val error = DomainError.ServerError(httpStatusCode = 500)
                    every { repository.getPersonas() } returns flowOf(ApiResult.Error(error))
                    val result = useCase("persona-001")
                    (result as ApiResult.Error).error shouldBe error
                }
            }
        }
    })
