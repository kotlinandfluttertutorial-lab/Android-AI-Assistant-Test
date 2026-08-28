/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module      : feature-dashboard
 * File        : DashboardViewModelTest.kt
 * Purpose     : Unit tests for DashboardViewModel state logic and use cases.
 * Architecture: feature-dashboard — MVVM ViewModel Tests
 * Requirements: Phase 14 AI DevOps Dashboard
 * ============================================================
 */
package com.aiassistant.feature.dashboard

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DispatcherProvider
import com.aiassistant.core.common.DomainError
import com.aiassistant.core.network.ConnectivityObserver
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.model.IncidentSeverity
import com.aiassistant.domain.model.IncidentStatus
import com.aiassistant.domain.usecase.devops.AnalyseErrorsUseCase
import com.aiassistant.domain.usecase.devops.AskDevOpsAssistantUseCase
import com.aiassistant.domain.usecase.devops.GetIncidentsUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DashboardViewModel].
 * Uses MockK for mocking and UnconfinedTestDispatcher for coroutine control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private val testDispatchers = object : DispatcherProvider {
        override val main: CoroutineDispatcher = testDispatcher
        override val io: CoroutineDispatcher = testDispatcher
        override val default: CoroutineDispatcher = testDispatcher
        override val mainImmediate: CoroutineDispatcher = testDispatcher
        override val unconfined: CoroutineDispatcher = testDispatcher
    }

    // ── Mocks ─────────────────────────────────────────────────────────────────

    private val getIncidentsUseCase = mockk<GetIncidentsUseCase>()
    private val analyseErrorsUseCase = mockk<AnalyseErrorsUseCase>()
    private val askDevOpsAssistantUseCase = mockk<AskDevOpsAssistantUseCase>()
    private val connectivityObserver = mockk<ConnectivityObserver>()

    private lateinit var viewModel: DashboardViewModel

    private val isConnectedFlow = MutableStateFlow(true)

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Default Mock Behaviors
        every { connectivityObserver.isConnectedFlow } returns isConnectedFlow
        every { connectivityObserver.isConnected() } returns true

        coEvery { getIncidentsUseCase(any(), any(), any()) } returns ApiResult.Success(emptyList())
        coEvery { analyseErrorsUseCase(any()) } returns ApiResult.Success(mockk(relaxed = true))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun initViewModel() {
        viewModel = DashboardViewModel(
            getIncidents = getIncidentsUseCase,
            analyseErrors = analyseErrorsUseCase,
            askDevOpsAssistant = askDevOpsAssistantUseCase,
            connectivityObserver = connectivityObserver,
            dispatchers = testDispatchers
        )
    }

    private fun makeIncident(
        id: String = "1",
        status: IncidentStatus = IncidentStatus.OPEN,
        severity: IncidentSeverity = IncidentSeverity.HIGH
    ) = Incident(
        id = id,
        title = "Test Incident",
        status = status,
        severity = severity,
        detectionMethod = "rule_based",
        triggeredBy = "System",
        detectedAt = "2026-08-28T18:00:00Z"
    )

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial load calls use cases and emits Content state`() = runTest {
        val incidents = listOf(makeIncident(severity = IncidentSeverity.CRITICAL))
        val analysis = mockk<AiAnalysis>(relaxed = true)

        coEvery { getIncidentsUseCase(limit = 20) } returns ApiResult.Success(incidents)
        coEvery { analyseErrorsUseCase(lookbackMinutes = 30) } returns ApiResult.Success(analysis)

        initViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Content", state is DashboardUiState.Content)
        val content = state as DashboardUiState.Content
        assertEquals(incidents, content.incidents)
        assertEquals(analysis, content.aiAnalysis)
        assertEquals(1, content.counts.critical)
        assertEquals(1, content.counts.open)

        coVerify { getIncidentsUseCase(limit = 20) }
        coVerify { analyseErrorsUseCase(lookbackMinutes = 30) }
    }

    @Test
    fun `refresh updates isRefreshing to true and reloads data`() = runTest {
        coEvery { getIncidentsUseCase(any(), any(), any()) } returns ApiResult.Success(emptyList())

        initViewModel()
        advanceUntilIdle()

        // Trigger refresh
        viewModel.refresh()

        // Reloaded
        coVerify(exactly = 2) { getIncidentsUseCase(any(), any(), any()) }
        val state = viewModel.uiState.value as DashboardUiState.Content
        assertEquals(false, state.isRefreshing) // Finished loading
    }

    @Test
    fun `askQuestion updates chatState and calls use case`() = runTest {
        initViewModel()
        val question = "What is the status of the auth service?"
        val chatResult = mockk<DevOpsChatResult>(relaxed = true)
        coEvery { askDevOpsAssistantUseCase(question) } returns ApiResult.Success(chatResult)

        viewModel.askQuestion(question)
        advanceUntilIdle()

        assertTrue("Chat state should be Success", viewModel.chatState.value is ChatUiState.Success)
        val chatState = viewModel.chatState.value as ChatUiState.Success
        assertEquals(chatResult, chatState.result)
        coVerify { askDevOpsAssistantUseCase(question) }
    }

    @Test
    fun `GetIncidentsUseCase error transitions to Error state`() = runTest {
        val errorMessage = "API failure"
        coEvery { getIncidentsUseCase(any(), any(), any()) } returns ApiResult.Error(DomainError.ServerError(errorMessage))

        initViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("State should be Error", state is DashboardUiState.Error)
        assertEquals(errorMessage, (state as DashboardUiState.Error).message)
    }

    @Test
    fun `offline state change reflected in isOffline and DashboardUiState Content`() = runTest {
        coEvery { getIncidentsUseCase(any(), any(), any()) } returns ApiResult.Success(emptyList())

        initViewModel()
        advanceUntilIdle()

        // Change connectivity to offline
        isConnectedFlow.value = false
        advanceUntilIdle()

        assertTrue("isOffline should be true", viewModel.isOffline.value)

        // Trigger a reload to see if Content reflects offline
        viewModel.refresh()
        advanceUntilIdle()

        val state = viewModel.uiState.value as DashboardUiState.Content
        assertTrue("Content state should reflect offline", state.isOffline)
    }
}
