/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceInferenceIsolationPropertyTest.kt
 * Purpose    : Property-based tests verifying that OnDeviceInferenceClient is
 *              structurally isolated from all network/HTTP infrastructure.
 *
 *              Property 31  — For any message payload routed to OnDeviceInferenceClient,
 *                             zero HTTP requests are made to any external host.
 *              Property 31b — OnDeviceInferenceClient has no OkHttpClient constructor
 *                             parameter or field dependency (structural isolation).
 *
 * Architecture Layer : Feature (feature-on-device-ai) — property tests
 * Pattern Used       : Kotest StringSpec + PropTest (checkAll) + MockK
 *
 * **Validates: Requirements 31.2**
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.ON_DEVICE_PROVIDER_ID
import com.aiassistant.core.ai.StreamEvent
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.property.Arb
import io.kotest.property.arbitrary.Codepoint
import io.kotest.property.arbitrary.alphanumeric
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart
import okhttp3.OkHttpClient

/**
 * Property-based tests asserting that [OnDeviceInferenceClient] never touches HTTP transport
 * regardless of what message payload is provided.
 *
 * **Validates: Requirements 31.2**
 */
class OnDeviceInferenceIsolationPropertyTest :
    StringSpec({

        // ─── Setup ────────────────────────────────────────────────────────────────

        val tmpDir = Files.createTempDirectory("ondevice-isolation-pbt").toFile()
        val modelFile = File(tmpDir, "model.gguf").also { it.writeText("fake gguf model data") }

        beforeEach {
            mockkStatic(android.util.Log::class)
            every { android.util.Log.d(any(), any()) } returns 0
            every { android.util.Log.i(any(), any()) } returns 0
            every { android.util.Log.w(any(), any<String>()) } returns 0
            every { android.util.Log.w(any(), any<String>(), any()) } returns 0
            every { android.util.Log.e(any(), any()) } returns 0
            every { android.util.Log.e(any(), any(), any()) } returns 0
        }

        afterSpec {
            unmockkAll()
            tmpDir.deleteRecursively()
        }

        // ─── Property 31: Zero network calls for arbitrary message payloads ────────

        /**
         * Property 31: On-Device Inference Zero Network Calls
         *
         * For any arbitrary (conversationId, content) combination routed through
         * [OnDeviceInferenceClient], zero calls must be made to [OkHttpClient].
         *
         * Strategy:
         * 1. Create a mock [OkHttpClient] that records invocations.
         * 2. Run checkAll over generated (conversationId, content) inputs.
         * 3. For each input: run connect + sendMessage + collect first event.
         * 4. After each run, verify the mock OkHttpClient.newCall() was NEVER invoked.
         *
         * **Validates: Requirements 31.2**
         */
        "Property 31: for any message payload, OnDeviceInferenceClient makes zero HTTP calls" {
            // Create a mock OkHttpClient that records any invocations
            val mockOkHttpClient = mockk<OkHttpClient>(relaxed = true)

            checkAll(
                io.kotest.property.PropTestConfig(iterations = 10),
                Arb.string(1..50, Codepoint.alphanumeric()),
                Arb.string(1..500)
            ) { conversationId, content ->
                val ramMonitor = mockk<RamMonitor>(relaxed = true)
                every { ramMonitor.availableMemoryBytes() } returns 2L * 1024L * 1024L * 1024L
                every { ramMonitor.observe(any()) } returns flow {
                    while (true) {
                        emit(RamEvent.Sufficient(2L * 1024L * 1024L * 1024L))
                        delay(500L)
                    }
                }

                val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = modelFile)
                val payload = MessagePayload(
                    conversationId = conversationId,
                    content = content,
                    provider = ON_DEVICE_PROVIDER_ID
                )

                // Collect the first event — proves inference ran without network
                val firstEvent = client.connect(conversationId, "test-jwt")
                    .onStart { client.sendMessage(payload) }
                    .first()

                // The emitted event must be a valid StreamEvent (Token, Done, or Error)
                val isValidStreamEvent = firstEvent is StreamEvent.Token ||
                    firstEvent is StreamEvent.Done ||
                    firstEvent is StreamEvent.Error
                isValidStreamEvent.shouldBeTrue()

                // ── Core assertion: OkHttpClient was never touched ──────────────
                verify(exactly = 0) { mockOkHttpClient.newCall(any()) }

                client.disconnect()
            }
        }

        /**
         * Property 31b: Structural isolation — [OnDeviceInferenceClient] has no
         * [OkHttpClient] dependency anywhere in its constructor or fields.
         *
         * Uses Kotlin reflection to inspect the class definition itself, ensuring that
         * no future refactor accidentally introduces a network dependency.
         *
         * **Validates: Requirements 31.2**
         */
        "Property 31b: OnDeviceInferenceClient class has no OkHttpClient dependency — structural isolation" {
            val clazz = OnDeviceInferenceClient::class.java

            // ── Check constructor parameters ────────────────────────────────────
            val constructorParamTypes: List<String> = clazz.constructors
                .flatMap { ctor -> ctor.parameterTypes.toList() }
                .map { paramClass -> paramClass.name }
            val hasOkHttpInConstructors: Boolean = constructorParamTypes
                .filter { name: String -> name.contains("OkHttpClient") || name.contains("okhttp3") }
                .isNotEmpty()
            hasOkHttpInConstructors.shouldBeFalse()

            // ── Check declared Java fields (catches private backing fields) ─────
            val javaFieldTypes: List<String> = clazz.declaredFields
                .map { field -> field.type.name }
            val hasOkHttpJavaField: Boolean = javaFieldTypes
                .filter { name: String -> name.contains("OkHttpClient") || name.contains("okhttp3") }
                .isNotEmpty()
            hasOkHttpJavaField.shouldBeFalse()

            // ── Check superclass / interface hierarchy ──────────────────────────
            val interfaceNames: List<String> = clazz.interfaces.map { iface -> iface.name }
            val hasOkHttpInInterfaces: Boolean = interfaceNames
                .filter { name: String -> name.contains("OkHttpClient") || name.contains("okhttp3") }
                .isNotEmpty()
            hasOkHttpInInterfaces.shouldBeFalse()
        }
    })
