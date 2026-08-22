/**
 * PersonaViewModelTest.kt — feature-persona module unit tests
 *
 * Tests for [PersonaViewModel] covering:
 *   1. Role-filtered persona list — the ViewModel surfaces only the personas returned
 *      by [PersonaRepository.getPersonas], which is already RBAC-filtered by the data layer.
 *      Tests verify that the ViewModel correctly propagates the filtered list into
 *      [PersonaUiState.PersonaList.personas] and that personas not in the user's
 *      role are absent.
 *
 *   2. Persona switch inserts timeline system message — verifies that [PersonaViewModel.selectPersona]
 *      emits a [PersonaSwitchEvent] containing the persona name and a timestamp when a
 *      persona is successfully selected. feature-chat observes this SharedFlow and inserts
 *      a system message in the conversation timeline (Requirement 32.7).
 *
 * Requirements: 21.1
 * Related requirements: 32.1, 32.2, 32.3, 32.5, 32.6, 32.7
 *
 * Test framework: Kotest (DescribeSpec, JUnit 5 runner) + MockK + kotlinx-coroutines-test
 * Pattern: matches [SettingsViewModelTest] / [ProductivityViewModelTest] style used in this project.
 */

package com.aiassistant.feature.persona

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.Persona
import com.aiassistant.domain.model.PersonaTone
import com.aiassistant.domain.repository.PersonaPreferencesRepository
import com.aiassistant.domain.repository.PersonaRepository
import com.aiassistant.domain.usecase.persona.CreatePersonaUseCase
import com.aiassistant.domain.usecase.persona.DeletePersonaUseCase
import com.aiassistant.domain.usecase.persona.SelectPersonaUseCase
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

// ─── Test helpers ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
private fun testDispatcherProvider(d: CoroutineDispatcher): DispatcherProvider = object : DispatcherProvider {
    override val default = d
    override val io = d
    override val main = d
    override val mainImmediate = d
    override val unconfined = d
}

// ─── Shared test fixtures ──────────────────────────────────────────────────────

private val NOW = System.currentTimeMillis()

/** A regular persona whose allowedRoles contains "user" and "admin". */
private val USER_ROLE_PERSONA = Persona(
    id = "persona-user-1",
    userId = "user-owner",
    name = "Customer Support Assistant",
    systemPrompt = "You help customers resolve their issues professionally.",
    tone = PersonaTone.PROFESSIONAL,
    scopeDescription = "Customer-facing support",
    adminLocked = false,
    allowedRoles = listOf("user", "admin"),
    createdAt = NOW,
    updatedAt = NOW
)

/** A premium-only admin-locked persona whose allowedRoles excludes the "user" role. */
private val PREMIUM_ONLY_PERSONA = Persona(
    id = "persona-premium-1",
    userId = "admin-owner",
    name = "Deep Analysis Expert",
    systemPrompt = "You perform detailed multi-step analysis.",
    tone = PersonaTone.DETAILED,
    scopeDescription = "Premium analytics",
    adminLocked = true,
    allowedRoles = listOf("premium", "admin"),
    createdAt = NOW,
    updatedAt = NOW
)

/** A persona accessible to all roles (empty allowedRoles = no restriction). */
private val OPEN_PERSONA = Persona(
    id = "persona-open-1",
    userId = "admin-owner",
    name = "General Assistant",
    systemPrompt = "You are a helpful general-purpose AI assistant.",
    tone = PersonaTone.CASUAL,
    scopeDescription = null,
    adminLocked = false,
    allowedRoles = emptyList(),
    createdAt = NOW,
    updatedAt = NOW
)

// ─── PersonaViewModelTest ──────────────────────────────────────────────────────

@OptIn(ExperimentalCoroutinesApi::class)
class PersonaViewModelTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val dispatchers = testDispatcherProvider(testDispatcher)

        val personaRepository = mockk<PersonaRepository>()
        val personaPreferencesRepository = mockk<PersonaPreferencesRepository>()

        val createPersonaUseCase = CreatePersonaUseCase(personaRepository)
        val deletePersonaUseCase = DeletePersonaUseCase(personaRepository)
        val selectPersonaUseCase = SelectPersonaUseCase(personaRepository, personaPreferencesRepository)

        beforeEach {
            Dispatchers.setMain(testDispatcher)
        }

        afterEach {
            Dispatchers.resetMain()
            clearMocks(personaRepository, personaPreferencesRepository)
            unmockkAll()
        }

        /** Builds a [PersonaViewModel] with the given persona list pre-loaded. */
        fun buildViewModel(
            personaList: List<Persona> = emptyList(),
            selectedPersonaId: String? = null
        ): PersonaViewModel {
            every { personaRepository.getPersonas() } returns flowOf(ApiResult.Success(personaList))
            coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(selectedPersonaId)
            return PersonaViewModel(
                createPersonaUseCase = createPersonaUseCase,
                deletePersonaUseCase = deletePersonaUseCase,
                selectPersonaUseCase = selectPersonaUseCase,
                personaRepository = personaRepository,
                dispatchers = dispatchers
            )
        }

        // ─── 1. Role-filtered persona list ────────────────────────────────────────

        describe("Role-filtered persona list (Requirement 32.6)") {

            /**
             * The data layer filters personas by the current user's RBAC role before returning
             * them. The ViewModel must propagate the filtered list exactly as-is.
             */
            it("PersonaList contains only personas permitted for the current user role") {
                // Data layer already filtered: only USER_ROLE_PERSONA and OPEN_PERSONA for "user" role
                val roleFilteredList = listOf(USER_ROLE_PERSONA, OPEN_PERSONA)
                val vm = buildViewModel(personaList = roleFilteredList)

                val state = vm.uiState.value
                state.shouldBeInstanceOf<PersonaUiState.PersonaList>()
                val list = (state as PersonaUiState.PersonaList).personas
                list shouldHaveSize 2
                list.any { it.id == USER_ROLE_PERSONA.id }.shouldBeTrue()
                list.any { it.id == OPEN_PERSONA.id }.shouldBeTrue()
            }

            it("premium-only persona is absent when repository excludes it for user role") {
                // Data layer excludes PREMIUM_ONLY_PERSONA for "user" role
                val roleFilteredList = listOf(USER_ROLE_PERSONA, OPEN_PERSONA)
                val vm = buildViewModel(personaList = roleFilteredList)

                val state = vm.uiState.value as PersonaUiState.PersonaList
                state.personas.any { it.id == PREMIUM_ONLY_PERSONA.id }.shouldBeFalse()
            }

            it("admin sees all three personas including premium-only and admin-locked") {
                // Admin role: repository returns all personas unfiltered
                val allPersonas = listOf(USER_ROLE_PERSONA, PREMIUM_ONLY_PERSONA, OPEN_PERSONA)
                val vm = buildViewModel(personaList = allPersonas)

                val state = vm.uiState.value as PersonaUiState.PersonaList
                state.personas shouldHaveSize 3
                state.personas.any { it.id == PREMIUM_ONLY_PERSONA.id }.shouldBeTrue()
            }

            it("emits empty PersonaList when no personas are permitted for user role") {
                val vm = buildViewModel(personaList = emptyList())

                val state = vm.uiState.value as PersonaUiState.PersonaList
                state.personas.shouldBeEmpty()
            }

            it("selectedPersonaId is null when no persona has been selected") {
                val vm = buildViewModel(personaList = listOf(USER_ROLE_PERSONA), selectedPersonaId = null)

                val state = vm.uiState.value as PersonaUiState.PersonaList
                state.selectedPersonaId shouldBe null
            }

            it("selectedPersonaId reflects the persisted selection returned by repository") {
                val vm = buildViewModel(
                    personaList = listOf(USER_ROLE_PERSONA),
                    selectedPersonaId = USER_ROLE_PERSONA.id
                )

                val state = vm.uiState.value as PersonaUiState.PersonaList
                state.selectedPersonaId shouldBe USER_ROLE_PERSONA.id
            }

            it("transitions to Error state when repository returns an error") {
                every { personaRepository.getPersonas() } returns flowOf(
                    ApiResult.Error(DomainError.NetworkError("Network unreachable"))
                )
                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                vm.uiState.value.shouldBeInstanceOf<PersonaUiState.Error>()
            }

            it("emits empty PersonaList on NetworkUnavailable without showing an error") {
                every { personaRepository.getPersonas() } returns flowOf(ApiResult.NetworkUnavailable)
                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                val state = vm.uiState.value
                state.shouldBeInstanceOf<PersonaUiState.PersonaList>()
                (state as PersonaUiState.PersonaList).personas.shouldBeEmpty()
            }
        }

        // ─── 2. Persona switch inserts timeline system message ────────────────────

        describe("Persona switch inserts timeline system message (Requirement 32.7)") {

            /**
             * When a persona is selected, [PersonaViewModel.selectPersona] must emit a
             * [PersonaSwitchEvent] on [PersonaViewModel.personaSwitchEvents]. feature-chat
             * observes this SharedFlow and inserts a system message in the conversation
             * timeline with the persona name and timestamp.
             */
            it("selectPersona emits PersonaSwitchEvent with the persona name on selection") {
                every { personaRepository.getPersonas() } returns flowOf(
                    ApiResult.Success(listOf(USER_ROLE_PERSONA))
                )
                coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(null)
                coEvery { personaPreferencesRepository.saveSelectedPersonaId(USER_ROLE_PERSONA.id) } returns Unit

                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                var emittedEvent: PersonaSwitchEvent? = null
                val collectionJob = launch(testDispatcher) {
                    emittedEvent = vm.personaSwitchEvents.first()
                }

                val beforeSwitch = System.currentTimeMillis()
                vm.selectPersona(USER_ROLE_PERSONA.id)
                collectionJob.join()

                emittedEvent.shouldNotBeNull()
                emittedEvent!!.personaName shouldBe USER_ROLE_PERSONA.name
                (emittedEvent!!.timestamp >= beforeSwitch).shouldBeTrue()
            }

            it("PersonaSwitchEvent persona name matches the selected persona") {
                every { personaRepository.getPersonas() } returns flowOf(
                    ApiResult.Success(listOf(OPEN_PERSONA))
                )
                coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(null)
                coEvery { personaPreferencesRepository.saveSelectedPersonaId(OPEN_PERSONA.id) } returns Unit

                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                var receivedEvent: PersonaSwitchEvent? = null
                val collectionJob = launch(testDispatcher) {
                    receivedEvent = vm.personaSwitchEvents.first()
                }

                vm.selectPersona(OPEN_PERSONA.id)
                collectionJob.join()

                receivedEvent?.personaName shouldBe OPEN_PERSONA.name
            }

            it("selectPersona persists selected persona id to preferences repository") {
                every { personaRepository.getPersonas() } returns flowOf(
                    ApiResult.Success(listOf(USER_ROLE_PERSONA))
                )
                coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(null)
                coEvery { personaPreferencesRepository.saveSelectedPersonaId(USER_ROLE_PERSONA.id) } returns Unit

                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                vm.selectPersona(USER_ROLE_PERSONA.id)

                coVerify(exactly = 1) { personaPreferencesRepository.saveSelectedPersonaId(USER_ROLE_PERSONA.id) }
            }

            it("selectPersona with null (deselect) does NOT emit PersonaSwitchEvent") {
                coEvery { personaPreferencesRepository.saveSelectedPersonaId(null) } returns Unit
                coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(null)
                every { personaRepository.getPersonas() } returns flowOf(ApiResult.Success(emptyList()))

                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                var eventEmitted = false
                val collectionJob = launch(testDispatcher) {
                    vm.personaSwitchEvents.collect { eventEmitted = true }
                }

                vm.selectPersona(null)
                delay(50L) // window for any erroneous emission to propagate
                collectionJob.cancel()

                eventEmitted.shouldBeFalse()
            }

            it("PersonaSwitchEvent timestamp is a positive epoch-millisecond value") {
                every { personaRepository.getPersonas() } returns flowOf(
                    ApiResult.Success(listOf(USER_ROLE_PERSONA))
                )
                coEvery { personaRepository.getSelectedPersonaId() } returns ApiResult.Success(null)
                coEvery { personaPreferencesRepository.saveSelectedPersonaId(USER_ROLE_PERSONA.id) } returns Unit

                val vm = PersonaViewModel(
                    createPersonaUseCase = createPersonaUseCase,
                    deletePersonaUseCase = deletePersonaUseCase,
                    selectPersonaUseCase = selectPersonaUseCase,
                    personaRepository = personaRepository,
                    dispatchers = dispatchers
                )

                var receivedEvent: PersonaSwitchEvent? = null
                val collectionJob = launch(testDispatcher) {
                    receivedEvent = vm.personaSwitchEvents.first()
                }

                vm.selectPersona(USER_ROLE_PERSONA.id)
                collectionJob.join()

                (receivedEvent!!.timestamp > 0L).shouldBeTrue()
            }
        }
    })
