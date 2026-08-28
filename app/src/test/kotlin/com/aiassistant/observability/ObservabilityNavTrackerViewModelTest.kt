package com.aiassistant.observability

import com.aiassistant.core.common.observability.ObservabilityEventBus
import com.aiassistant.core.common.observability.SessionManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit test for [ObservabilityNavTrackerViewModel].
 *
 * Verifies that the ViewModel correctly holds the injected [ObservabilityEventBus]
 * and [SessionManager] instances.
 */
class ObservabilityNavTrackerViewModelTest {

    private val mockBus = mockk<ObservabilityEventBus>()
    private val mockSessionManager = mockk<SessionManager>()

    @Test
    fun `ViewModel holds mocked instances correctly`() {
        // Act
        val viewModel = ObservabilityNavTrackerViewModel(
            bus = mockBus,
            sessionManager = mockSessionManager
        )

        // Assert
        assertEquals("ViewModel should hold the injected ObservabilityEventBus", mockBus, viewModel.bus)
        assertEquals("ViewModel should hold the injected SessionManager", mockSessionManager, viewModel.sessionManager)
    }
}
