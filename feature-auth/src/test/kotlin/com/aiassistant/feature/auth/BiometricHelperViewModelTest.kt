/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-auth
 * File       : BiometricHelperViewModelTest.kt
 * Purpose    : Unit tests for BiometricHelperViewModel
 *
 * Architecture Layer : Test (feature-auth)
 *
 * Dependencies:
 *   - JUnit 4
 *   - MockK
 * ============================================================
 */
package com.aiassistant.feature.auth

import com.aiassistant.core.security.BiometricAuthManager
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [BiometricHelperViewModel].
 *
 * Purpose: Verifies that the ViewModel correctly holds and surfaces the [BiometricAuthManager]
 *          provided via constructor injection.
 */
class BiometricHelperViewModelTest {

    @Test
    fun `viewModel should hold the provided BiometricAuthManager instance`() {
        // Arrange
        val mockBiometricAuthManager = mockk<BiometricAuthManager>()

        // Act
        val viewModel = BiometricHelperViewModel(mockBiometricAuthManager)

        // Assert
        assertEquals(
            "ViewModel should expose the same BiometricAuthManager instance passed to its constructor",
            mockBiometricAuthManager,
            viewModel.biometricAuthManager
        )
    }
}
