package com.aiassistant.domain.usecase.devops

import com.aiassistant.core.common.ApiResult
import com.aiassistant.core.common.DomainError
import com.aiassistant.domain.model.AiAnalysis
import com.aiassistant.domain.model.DevOpsChatResult
import com.aiassistant.domain.model.Incident
import com.aiassistant.domain.repository.DevOpsRepository
import com.aiassistant.domain.repository.IncidentRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DevOpsUseCaseTest {

    private val devOpsRepository: DevOpsRepository = mockk()
    private val incidentRepository: IncidentRepository = mockk()

    private val analyseErrorsUseCase = AnalyseErrorsUseCase(devOpsRepository)
    private val askDevOpsAssistantUseCase = AskDevOpsAssistantUseCase(devOpsRepository)
    private val getIncidentsUseCase = GetIncidentsUseCase(incidentRepository)

    @Test
    fun `AnalyseErrorsUseCase returns Success when repository succeeds`() = runTest {
        // Arrange
        val expectedResult = mockk<AiAnalysis>()
        coEvery { devOpsRepository.analyseErrors(any(), any()) } returns ApiResult.Success(expectedResult)

        // Act
        val result = analyseErrorsUseCase(lookbackMinutes = 30, sessionId = "session_123")

        // Assert
        assertEquals(ApiResult.Success(expectedResult), result)
        coVerify { devOpsRepository.analyseErrors(lookbackMinutes = 30, sessionId = "session_123") }
    }

    @Test
    fun `AnalyseErrorsUseCase returns Error when repository fails`() = runTest {
        // Arrange
        val expectedError = DomainError.NetworkError("Analysis failed")
        coEvery { devOpsRepository.analyseErrors(any(), any()) } returns ApiResult.Error(expectedError)

        // Act
        val result = analyseErrorsUseCase(lookbackMinutes = 30, sessionId = "session_123")

        // Assert
        assertEquals(ApiResult.Error(expectedError), result)
    }

    @Test
    fun `AskDevOpsAssistantUseCase returns Success when repository succeeds`() = runTest {
        // Arrange
        val expectedResult = mockk<DevOpsChatResult>()
        coEvery { devOpsRepository.chat(any(), any()) } returns ApiResult.Success(expectedResult)

        // Act
        val result = askDevOpsAssistantUseCase(question = "What's the status?", provider = "openai")

        // Assert
        assertEquals(ApiResult.Success(expectedResult), result)
        coVerify { devOpsRepository.chat(question = "What's the status?", provider = "openai") }
    }

    @Test
    fun `AskDevOpsAssistantUseCase returns Error when repository fails`() = runTest {
        // Arrange
        val expectedError = DomainError.NetworkError("Chat failed")
        coEvery { devOpsRepository.chat(any(), any()) } returns ApiResult.Error(expectedError)

        // Act
        val result = askDevOpsAssistantUseCase(question = "What's the status?")

        // Assert
        assertEquals(ApiResult.Error(expectedError), result)
    }

    @Test
    fun `GetIncidentsUseCase returns Success when repository succeeds`() = runTest {
        // Arrange
        val expectedResult = listOf(mockk<Incident>())
        coEvery { incidentRepository.getIncidents(any(), any(), any()) } returns ApiResult.Success(expectedResult)

        // Act
        val result = getIncidentsUseCase(status = "active", severity = "high", limit = 10)

        // Assert
        assertEquals(ApiResult.Success(expectedResult), result)
        coVerify { incidentRepository.getIncidents(status = "active", severity = "high", limit = 10) }
    }

    @Test
    fun `GetIncidentsUseCase returns Error when repository fails`() = runTest {
        // Arrange
        val expectedError = DomainError.NetworkError("Fetch incidents failed")
        coEvery { incidentRepository.getIncidents(any(), any(), any()) } returns ApiResult.Error(expectedError)

        // Act
        val result = getIncidentsUseCase()

        // Assert
        assertEquals(ApiResult.Error(expectedError), result)
    }
}
