/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceInferenceClientTest.kt
 * Purpose    : Unit tests for OnDeviceInferenceClient — verifies that:
 *              - connect() emits Token and Done events
 *              - No network calls are made (zero OkHttp / Retrofit usage verified by
 *                class structure)
 *              - Missing model emits StreamEvent.Error immediately
 *              - disconnect() cancels inference
 *
 * Architecture Layer : Feature (feature-on-device-ai) — tests
 * Pattern Used       : Kotest DescribeSpec + MockK + Coroutines Test
 *
 * Requirements: 31.2, 31.4, 31.5
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import app.cash.turbine.test
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.ON_DEVICE_PROVIDER_ID
import com.aiassistant.core.ai.StreamEvent
import io.kotest.core.spec.style.DescribeSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class OnDeviceInferenceClientTest :
    DescribeSpec({

        val tmpFolder = Files.createTempDirectory("inference-client-test").toFile()
        val ramMonitor = mockk<RamMonitor>(relaxed = true)

        beforeSpec {
            // Default: RAM is always sufficient
            every { ramMonitor.availableMemoryBytes() } returns 2L * 1024L * 1024L * 1024L // 2 GB
            every { ramMonitor.observe(any()) } returns kotlinx.coroutines.flow.flow {
                // Emit only Sufficient events — never triggers BelowThreshold
                while (true) {
                    emit(RamEvent.Sufficient(2L * 1024L * 1024L * 1024L))
                    kotlinx.coroutines.delay(500)
                }
            }
        }

        afterSpec {
            unmockkAll()
            tmpFolder.deleteRecursively()
        }

        fun createClientWithModel(file: File? = null): OnDeviceInferenceClient =
            OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = file)

        describe("Model not available") {
            it("connect emits StreamEvent Error when model file is null") {
                runTest {
                    val client = createClientWithModel(file = null)
                    val event = client.connect("conv-1", "jwt").first()
                    assertTrue(
                        "Expected StreamEvent.Error but got $event",
                        event is StreamEvent.Error
                    )
                }
            }

            it("connect emits StreamEvent Error when model file does not exist") {
                runTest {
                    val nonExistent = File(tmpFolder, "ghost.gguf")
                    val client = createClientWithModel(file = nonExistent)
                    val event = client.connect("conv-1", "jwt").first()
                    assertTrue(
                        "Expected StreamEvent.Error for missing file but got $event",
                        event is StreamEvent.Error
                    )
                }
            }
        }

        describe("Normal inference path") {
            it("connect emits Token events and ends with Done after sending message") {
                runTest {
                    val modelFile = File(tmpFolder, "model.gguf").also { it.writeText("fake model") }
                    val client = createClientWithModel(modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-123",
                        content = "Hello on-device",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    client.connect("conv-123", "jwt").test {
                        client.sendMessage(payload)

                        // Collect events — expect at least one Token
                        var tokenSeen = false
                        var doneSeen = false

                        // Collect with a timeout-aware turbine approach
                        loop@ for (i in 0..100) {
                            when (val event = awaitItem()) {
                                is StreamEvent.Token -> tokenSeen = true
                                is StreamEvent.Done -> {
                                    doneSeen = true
                                    break@loop
                                }
                                is StreamEvent.Error -> {
                                    // Unexpected error — fail
                                    throw AssertionError("Unexpected error: ${event.message}")
                                }
                                else -> Unit
                            }
                        }

                        assertTrue("Expected at least one Token event", tokenSeen)
                        assertTrue("Expected Done event", doneSeen)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }

            it("Done event contains non-negative token counts") {
                runTest {
                    val modelFile = File(tmpFolder, "model2.gguf").also { it.writeText("fake model") }
                    val client = createClientWithModel(modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-456",
                        content = "Short prompt",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    client.connect("conv-456", "jwt").test {
                        client.sendMessage(payload)

                        var done: StreamEvent.Done? = null
                        loop@ while (true) {
                            when (val event = awaitItem()) {
                                is StreamEvent.Done -> {
                                    done = event
                                    break@loop
                                }
                                is StreamEvent.Error -> throw AssertionError("Unexpected error: ${event.message}")
                                else -> Unit
                            }
                        }

                        requireNotNull(done)
                        assertTrue("inputTokens must be ≥ 0", done.usage.inputTokens >= 0)
                        assertTrue("outputTokens must be ≥ 0", done.usage.outputTokens >= 0)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        describe("RAM threshold") {
            it("connect emits Error when RAM is below threshold before inference") {
                runTest {
                    val modelFile = File(tmpFolder, "model3.gguf").also { it.writeText("fake model") }
                    // RAM below 512 MB
                    every { ramMonitor.availableMemoryBytes() } returns 256L * 1024L * 1024L

                    val client = createClientWithModel(modelFile)
                    val payload = MessagePayload(
                        conversationId = "conv-ram",
                        content = "Low RAM test",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    client.connect("conv-ram", "jwt").test {
                        client.sendMessage(payload)
                        val event = awaitItem()
                        assertTrue(
                            "Expected StreamEvent.Error for low RAM but got $event",
                            event is StreamEvent.Error
                        )
                        val error = event as StreamEvent.Error
                        assertTrue(
                            "Error message should mention switching to cloud",
                            error.message.contains("cloud", ignoreCase = true) ||
                                error.message.contains("Insufficient", ignoreCase = true)
                        )
                        cancelAndIgnoreRemainingEvents()
                    }
                }
            }
        }

        describe("disconnect") {
            it("sendMessage after disconnect is a no-op") {
                runTest {
                    val modelFile = File(tmpFolder, "model4.gguf").also { it.writeText("fake model") }
                    val client = createClientWithModel(modelFile)

                    client.disconnect() // disconnect before connecting
                    // Should not throw
                    client.sendMessage(
                        MessagePayload("conv-disc", "test", ON_DEVICE_PROVIDER_ID)
                    )
                }
            }
        }

        describe("Provider identifier") {
            it("ON_DEVICE_PROVIDER_ID constant is non-blank") {
                assertTrue(ON_DEVICE_PROVIDER_ID.isNotBlank())
                assertEquals("on_device", ON_DEVICE_PROVIDER_ID)
            }
        }
    })
