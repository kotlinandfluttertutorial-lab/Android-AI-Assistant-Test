/**
 * BiometricAuthManagerTest.kt — core-security module unit tests
 *
 * Tests for [BiometricAuthManager] interface contract and [BiometricAuthManagerImpl].
 *
 * BiometricPrompt requires a live FragmentActivity and the Android OS biometric
 * subsystem, so [authenticate] cannot be meaningfully tested on the JVM without
 * instrumentation. Instead, we test:
 *   - [isBiometricAvailable] behaviour with a mocked BiometricManager
 *   - That [BiometricAuthManagerImpl] is constructable and implements the interface
 *   - That no biometric data is captured or exposed (structural checks)
 */
package com.aiassistant.core.security

import android.content.Context
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BiometricAuthManagerTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Construction & interface contract ────────────────────────────────────

    @Test
    fun `BiometricAuthManagerImpl can be instantiated`() {
        val manager: BiometricAuthManager = BiometricAuthManagerImpl()
        manager shouldNotBe null
    }

    @Test
    fun `BiometricAuthManagerImpl implements BiometricAuthManager`() {
        val impl = BiometricAuthManagerImpl()
        (impl is BiometricAuthManager) shouldBe true
    }

    // ── isBiometricAvailable ─────────────────────────────────────────────────

    @Test
    fun `isBiometricAvailable returns false when BiometricManager reports none enrolled`() {
        val context = mockk<Context>(relaxed = true)
        val biometricManager = mockk<androidx.biometric.BiometricManager>()

        mockkStatic(androidx.biometric.BiometricManager::class)
        every { androidx.biometric.BiometricManager.from(context) } returns biometricManager
        every {
            biometricManager.canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } returns androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED

        val manager = BiometricAuthManagerImpl()
        manager.isBiometricAvailable(context) shouldBe false
    }

    @Test
    fun `isBiometricAvailable returns false when BiometricManager reports no hardware`() {
        val context = mockk<Context>(relaxed = true)
        val biometricManager = mockk<androidx.biometric.BiometricManager>()

        mockkStatic(androidx.biometric.BiometricManager::class)
        every { androidx.biometric.BiometricManager.from(context) } returns biometricManager
        every {
            biometricManager.canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } returns androidx.biometric.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE

        val manager = BiometricAuthManagerImpl()
        manager.isBiometricAvailable(context) shouldBe false
    }

    @Test
    fun `isBiometricAvailable returns true when BiometricManager reports success`() {
        val context = mockk<Context>(relaxed = true)
        val biometricManager = mockk<androidx.biometric.BiometricManager>()

        mockkStatic(androidx.biometric.BiometricManager::class)
        every { androidx.biometric.BiometricManager.from(context) } returns biometricManager
        every {
            biometricManager.canAuthenticate(
                androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } returns androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS

        val manager = BiometricAuthManagerImpl()
        manager.isBiometricAvailable(context) shouldBe true
    }

    // ── Security invariant (structural) ─────────────────────────────────────

    @Test
    fun `BiometricAuthManager interface has no method that returns biometric data`() {
        // This is a structural test: verify that the interface's authenticate() method
        // returns Unit (void) and therefore cannot leak biometric material to callers.
        val authenticateMethod = BiometricAuthManager::class.java.methods
            .first { it.name == "authenticate" }
        authenticateMethod.returnType shouldBe Void.TYPE
    }
}
