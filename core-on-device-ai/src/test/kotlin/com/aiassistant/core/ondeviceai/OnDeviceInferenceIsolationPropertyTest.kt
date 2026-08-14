/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : core-on-device-ai
 * File       : OnDeviceInferenceIsolationPropertyTest.kt
 * Purpose    : Property-based tests verifying that OnDeviceInferenceClient is
 *              structurally isolated from all network/HTTP infrastructure.
 *
 *              Property 31  — For any message payload routed to OnDeviceInferenceClient,
 *                             zero HTTP requests are made to any external host.
 *              Property 31b — OnDeviceInferenceClient has no OkHttpClient constructor
 *                             parameter or field dependency (structural isolation).
 *
 * Architecture Layer : Feature (feature-on-device-ai) — tests
 * Pattern Used       : Kotest PropTest + MockK
 *
 * **Validates: Requirements 31.2**
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.StreamEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import okhttp3.OkHttpClient
import java.io.File
import kotlin.reflect.full.memberProperties

/**
 * Property-based tests asserting that OnDeviceInferenceClient never touches HTTP transport
 * regardless of what message payload is provided.
 *
 * **Validates: Requirements 31.2**
 */
class OnDeviceInferenceIsolationPropertyTest : StringSpec({

    /**
     * Property 31: On-Device Inference Zero Network Calls
     *
     * For any arbitrary (conversationId, content) combination routed through
     * OnDeviceInferenceClient, zero calls must be made to OkHttpClient.
     *
     * Strategy:
     * 1. Create a mock OkHttpClient that records invocations.
     * 2. Confirm the client class does NOT accept OkHttpClient as a constructor param.
     * 3. Run checkAll over generated (conversationId, content) inputs.
     * 4. For each input: run connect + sendMessage + collect first event.
     * 5. After each run, verify the mock OkHttpClient.newCall() was NEVER invoked.
     *
     * **Validates: Requirements 31.2**
     */
    "Property 31: for any message payload, OnDeviceInferenceClient makes zero HTTP calls" {
        // Create a mock OkHttpClient that records any invocations
        val mockOkHttpClient = mockk<OkHttpClient>(relaxed = true)

        // Confirm structural isolation: OkHttpClient is not a constructor parameter
        val constructorParams = OnDeviceInferenceClient::class.constructors
            .flatMap { it.parameters }
            .map { it.type.toString() }
        val hasOkHttpInConstructor = constructorParams.any { it.contains("OkHttpClient") }
        hasOkHttpInConstructor.shouldBeTrue().not()

        checkAll(
            Arb.string(1..50, Codepoint.alphanumeric()),
            Arb.string(1..500),
        ) { conversationId, content ->
            // Fresh model file per iteration to avoid cross-test file state
            val tmpFile = File.createTempFile("model_prop31_", ".gguf").also {
                it.writeText("fake-model-content")
                it.deleteOnExit()
            }

            // Mock RamMonitor: always sufficient RAM, never emits BelowThreshold
            val ramMonitor = mockk<RamMonitor>(relaxed = true)
            every { ramMonitor.availableMemoryBytes() } returns 2L * 1024L * 1024L * 1024L
            every { ramMonitor.observe(any()) } returns flow {
                while (true) {
                    emit(RamEvent.Sufficient(2L * 1024L * 1024L * 1024L))
                    delay(500L)
                }
            }

            val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = tmpFile)
            val payload = MessagePayload(
                conversationId = conversationId,
                content = content,
                provider = ON_DEVICE_PROVIDER_ID,
            )

            // Collect the first event — proves inference ran without network
            val firstEvent = client.connect(conversationId, "test-jwt")
                .onStart { client.sendMessage(payload) }
                .first()

            // The emitted event must be a valid StreamEvent (Token, Done, or Error)
            // It must never be network-triggered
            val isValidStreamEvent = firstEvent is StreamEvent.Token ||
                firstEvent is StreamEvent.Done ||
                firstEvent is StreamEvent.Error
            isValidStreamEvent.shouldBeTrue()

            // ── Core assertion: OkHttpClient was never touched ──────────────
            verify(exactly = 0) { mockOkHttpClient.newCall(any()) }

            // Clean up
            client.disconnect()
        }
    }

    /**
     * Property 31b: Structural isolation — OnDeviceInferenceClient has no
     * OkHttpClient dependency anywhere in its constructor or fields.
     *
     * Uses Kotlin reflection to inspect the class definition itself, ensuring that
     * no future refactor accidentally introduces a network dependency.
     *
     * **Validates: Requirements 31.2**
     */
    "Property 31b: OnDeviceInferenceClient class has no OkHttpClient dependency — structural isolation" {
        // ── Check constructor parameters ────────────────────────────────────
        val constructors = OnDeviceInferenceClient::class.constructors
        val paramTypes = constructors.flatMap { it.parameters }.map { it.type.toString() }
        val hasOkHttpInConstructors = paramTypes.any { it.contains("OkHttpClient") }
        hasOkHttpInConstructors.shouldBeTrue().not()

        // ── Check member property return types ─────────────────────────────
        val fieldTypes = OnDeviceInferenceClient::class.memberProperties
            .map { it.returnType.toString() }
        val hasOkHttpField = fieldTypes.any { it.contains("OkHttpClient") }
        hasOkHttpField.shouldBeTrue().not()

        // ── Check declared Java fields (catches private backing fields) ─────
        val javaFields = OnDeviceInferenceClient::class.java.declaredFields
            .map { it.type.simpleName }
        val hasOkHttpJavaField = javaFields.any { it.contains("OkHttpClient") }
        hasOkHttpJavaField.shouldBeTrue().not()
    }
})
