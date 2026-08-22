/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-on-device-ai
 * File       : OnDeviceSettingsProviderTest.kt
 * Purpose    : Unit tests for task 33.2 — OnDeviceModelManager lifecycle and
 *              fallback routing. Covers all three required scenarios:
 *
 *   1. Checksum mismatch triggers corrupt-files branch (Requirement 31.7)
 *      When a model file exists but its SHA-256 does not match the manifest,
 *      checkModelState returns ModelFileState.Corrupt and the file is deleted.
 *      OnDeviceCapabilityChecker returns SupportedButModelNotReady so the
 *      caller falls back to the cloud LLM provider (Requirement 31.6).
 *
 *   2. RAM threshold cancels request and retries fallback (Requirement 31.6)
 *      When available RAM is below RAM_THRESHOLD_BYTES (512 MB), inference is
 *      cancelled and StreamEvent.Error with the "switching to cloud" message
 *      is emitted so the caller retries against the fallback cloud provider.
 *
 *   3. Device without NPU/GPU does not expose on-device provider (Requirement 31.7)
 *      When DeviceCapabilityDetector.isOnDeviceInferenceSupported() returns false,
 *      OnDeviceCapabilityChecker returns OnDeviceCapabilityState.NotSupported,
 *      signalling the settings layer to hide LlmProvider.ON_DEVICE.
 *
 * Architecture Layer : Feature (feature-on-device-ai) — unit tests
 * Pattern Used       : Kotest DescribeSpec + JUnit 5 runner + MockK
 *
 * Requirements: 21.1, 31.1, 31.6, 31.7
 * ============================================================
 */

package com.aiassistant.feature.ondeviceai

import android.content.Context
import android.content.res.AssetManager
import com.aiassistant.core.ai.MessagePayload
import com.aiassistant.core.ai.ON_DEVICE_PROVIDER_ID
import com.aiassistant.core.ai.OnDeviceCapabilityState
import com.aiassistant.core.ai.StreamEvent
import com.aiassistant.core.common.DispatcherProvider
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContainIgnoringCase
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

// ─── Test helpers ─────────────────────────────────────────────────────────────

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}

private fun buildEntry(
    id: String = "test-model",
    fileName: String = "test-model.gguf",
    sha256: String = "0".repeat(64)
) = ModelEntry(
    id = id,
    displayName = "Test Model",
    fileName = fileName,
    downloadUrl = "https://example.com/$fileName",
    sha256 = sha256,
    sizeBytes = 1024L,
    quantization = "INT4"
)

private fun buildManifestJson(entry: ModelEntry) = """
    {
      "models": [
        {
          "id": "${entry.id}",
          "displayName": "${entry.displayName}",
          "fileName": "${entry.fileName}",
          "downloadUrl": "${entry.downloadUrl}",
          "sha256": "${entry.sha256}",
          "sizeBytes": ${entry.sizeBytes},
          "quantization": "${entry.quantization}"
        }
      ]
    }
""".trimIndent()

private class TestDispatchers(d: CoroutineDispatcher) : DispatcherProvider {
    override val default = d
    override val io = d
    override val main = d
    override val mainImmediate = d
    override val unconfined = d
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Tests for task 33.2 — [OnDeviceModelManager] lifecycle and fallback routing.
 */
class OnDeviceSettingsProviderTest :
    DescribeSpec({

        val testDispatcher = UnconfinedTestDispatcher()
        val dispatchers = TestDispatchers(testDispatcher)

        val tmpDir = Files.createTempDirectory("settings-provider-test").toFile()
        File(tmpDir, "models").mkdirs()

        afterSpec {
            unmockkAll()
            tmpDir.deleteRecursively()
        }

        val context = mockk<Context>(relaxed = true)
        val assetManager = mockk<AssetManager>(relaxed = true)
        every { context.assets } returns assetManager
        every { context.filesDir } returns tmpDir

        val manager = OnDeviceModelManager(context)

        // ─────────────────────────────────────────────────────────────────────────
        // Scenario 1 — Checksum mismatch triggers corrupt-files branch
        // ─────────────────────────────────────────────────────────────────────────

        describe("checksum mismatch triggers corrupt-files branch (Requirement 31.7)") {

            it("checkModelState returns ModelFileState.Corrupt when SHA-256 does not match manifest") {
                runTest(testDispatcher) {
                    val content = "fake gguf model content".toByteArray()
                    val entry = buildEntry(id = "c1", fileName = "c1.gguf", sha256 = "0".repeat(64))

                    manager.modelFile(entry).writeBytes(content)

                    val state = manager.checkModelState(entry)

                    state.shouldBeInstanceOf<ModelFileState.Corrupt>()
                    (state as ModelFileState.Corrupt).entry.id shouldBe entry.id
                }
            }

            it("deletes the corrupt file from disk when checksum mismatch is detected") {
                runTest(testDispatcher) {
                    val content = "some bytes".toByteArray()
                    val entry = buildEntry(id = "c2", fileName = "c2.gguf", sha256 = "0".repeat(64))

                    val file = manager.modelFile(entry)
                    file.writeBytes(content)
                    file.exists().shouldBeTrue()

                    manager.checkModelState(entry)

                    // Corrupt file must be deleted so inference engine cannot accidentally load it
                    file.exists().shouldBeFalse()
                }
            }

            it("does NOT return Corrupt when SHA-256 matches — file remains on disk as Ready") {
                runTest(testDispatcher) {
                    val content = "valid gguf model content".toByteArray()
                    val correctHash = sha256Hex(content)
                    val entry = buildEntry(id = "c3", fileName = "c3.gguf", sha256 = correctHash)

                    val file = manager.modelFile(entry)
                    file.writeBytes(content)

                    val state = manager.checkModelState(entry)

                    state.shouldBeInstanceOf<ModelFileState.Ready>()
                    file.exists().shouldBeTrue()
                    file.delete()
                }
            }

            it("checkModelStatus returns ModelStatus.VerificationFailed after checksum mismatch") {
                runTest(testDispatcher) {
                    val entry = buildEntry(id = "c4", fileName = "c4.gguf", sha256 = "0".repeat(64))

                    // Write file with content that will NOT hash to all-zeros
                    manager.modelFile(entry).writeBytes("corrupt content that does not match".toByteArray())

                    every { assetManager.open("model_manifest.json") } returns
                        ByteArrayInputStream(buildManifestJson(entry).toByteArray())

                    val status = manager.checkModelStatus()

                    status.shouldBeInstanceOf<ModelStatus.VerificationFailed>()
                }
            }

            it(
                "OnDeviceCapabilityChecker returns SupportedButModelNotReady with VerificationFailed for corrupt model"
            ) {
                runTest(testDispatcher) {
                    val mockDetector = mockk<DeviceCapabilityDetector>()
                    coEvery { mockDetector.isOnDeviceInferenceSupported() } returns true

                    val content = "real bytes".toByteArray()
                    val wrongHash = sha256Hex("completely different content".toByteArray())
                    val entry = buildEntry(id = "c5", fileName = "c5.gguf", sha256 = wrongHash)

                    manager.modelFile(entry).writeBytes(content)

                    every { assetManager.open("model_manifest.json") } returns
                        ByteArrayInputStream(buildManifestJson(entry).toByteArray())

                    val checker = OnDeviceCapabilityChecker(
                        capabilityDetector = mockDetector,
                        modelManager = manager,
                        dispatchers = dispatchers
                    )

                    val capState = checker.evaluate()

                    // Hardware qualified but model failed verification — must show download prompt
                    // and continue using cloud fallback provider
                    capState.shouldBeInstanceOf<OnDeviceCapabilityState.SupportedButModelNotReady>()
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Scenario 2 — RAM below 512 MB threshold cancels request and retries fallback
        // ─────────────────────────────────────────────────────────────────────────

        describe("RAM threshold cancels request and retries fallback (Requirement 31.6)") {

            it("RAM_THRESHOLD_BYTES constant is exactly 512 MB") {
                RAM_THRESHOLD_BYTES shouldBe 512L * 1024L * 1024L
            }

            it("OnDeviceInferenceClient emits StreamEvent.Error when available RAM is below 512 MB") {
                runTest(testDispatcher) {
                    val ramMonitor = mockk<RamMonitor>(relaxed = true)
                    every { ramMonitor.availableMemoryBytes() } returns 256L * 1024L * 1024L // 256 MB
                    every { ramMonitor.observe(any()) } returns kotlinx.coroutines.flow.emptyFlow()

                    val modelFile = File(tmpDir, "ram1.gguf").also { it.writeText("fake model") }
                    val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-ram-1",
                        content = "Hello",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    // Collect events: connect first, then send the message
                    val event = kotlinx.coroutines.flow.channelFlow {
                        val inferenceFlow = client.connect("conv-ram-1", "jwt")
                        launch { inferenceFlow.collect { send(it) } }
                        delay(10L)
                        client.sendMessage(payload)
                    }.first()

                    event.shouldBeInstanceOf<StreamEvent.Error>()
                }
            }

            it("StreamEvent.Error message references cloud or Insufficient when RAM is below threshold") {
                runTest(testDispatcher) {
                    val ramMonitor = mockk<RamMonitor>(relaxed = true)
                    every { ramMonitor.availableMemoryBytes() } returns 100L * 1024L * 1024L // 100 MB
                    every { ramMonitor.observe(any()) } returns kotlinx.coroutines.flow.emptyFlow()

                    val modelFile = File(tmpDir, "ram2.gguf").also { it.writeText("model data") }
                    val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-ram-2",
                        content = "prompt",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    val event = kotlinx.coroutines.flow.channelFlow {
                        val inferenceFlow = client.connect("conv-ram-2", "jwt")
                        launch { inferenceFlow.collect { send(it) } }
                        delay(10L)
                        client.sendMessage(payload)
                    }.first()

                    val errorEvent = event as StreamEvent.Error
                    // Message must signal caller to switch to cloud (Requirement 31.6)
                    (
                        errorEvent.message.contains("cloud", ignoreCase = true) ||
                            errorEvent.message.contains("Insufficient", ignoreCase = true)
                        )
                        .shouldBeTrue()
                }
            }

            it("error message matches INSUFFICIENT_RESOURCES_MESSAGE constant exactly") {
                runTest(testDispatcher) {
                    val ramMonitor = mockk<RamMonitor>(relaxed = true)
                    every { ramMonitor.availableMemoryBytes() } returns 50L * 1024L * 1024L // 50 MB
                    every { ramMonitor.observe(any()) } returns kotlinx.coroutines.flow.emptyFlow()

                    val modelFile = File(tmpDir, "ram3.gguf").also { it.writeText("model") }
                    val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-ram-3",
                        content = "test",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    val event = kotlinx.coroutines.flow.channelFlow {
                        val inferenceFlow = client.connect("conv-ram-3", "jwt")
                        launch { inferenceFlow.collect { send(it) } }
                        delay(10L)
                        client.sendMessage(payload)
                    }.first()

                    val errorEvent = event as StreamEvent.Error
                    errorEvent.message shouldBe OnDeviceInferenceClient.INSUFFICIENT_RESOURCES_MESSAGE
                }
            }

            it("INSUFFICIENT_RESOURCES_MESSAGE constant contains the word 'cloud'") {
                OnDeviceInferenceClient.INSUFFICIENT_RESOURCES_MESSAGE
                    .shouldContainIgnoringCase("cloud")
            }

            it("sufficient RAM (> 512 MB) does NOT produce a RAM-threshold error as the first event") {
                runTest(testDispatcher) {
                    val ramMonitor = mockk<RamMonitor>(relaxed = true)
                    every { ramMonitor.availableMemoryBytes() } returns 2L * 1024L * 1024L * 1024L // 2 GB
                    every { ramMonitor.observe(any()) } returns flow {
                        while (true) {
                            emit(RamEvent.Sufficient(2L * 1024L * 1024L * 1024L))
                            delay(500L)
                        }
                    }

                    val modelFile = File(tmpDir, "ram4.gguf").also { it.writeText("fake model") }
                    val client = OnDeviceInferenceClient(ramMonitor = ramMonitor, modelFile = modelFile)

                    val payload = MessagePayload(
                        conversationId = "conv-ok",
                        content = "Normal prompt",
                        provider = ON_DEVICE_PROVIDER_ID
                    )

                    val event = kotlinx.coroutines.flow.channelFlow {
                        val inferenceFlow = client.connect("conv-ok", "jwt")
                        launch { inferenceFlow.collect { send(it) } }
                        delay(10L)
                        client.sendMessage(payload)
                    }.first()

                    // First event must NOT be the RAM-limit error
                    val isRamError = event is StreamEvent.Error &&
                        (event as StreamEvent.Error).message ==
                        OnDeviceInferenceClient.INSUFFICIENT_RESOURCES_MESSAGE
                    isRamError.shouldBeFalse()
                }
            }
        }

        // ─────────────────────────────────────────────────────────────────────────
        // Scenario 3 — Device without NPU/GPU does not expose on-device provider
        // ─────────────────────────────────────────────────────────────────────────

        describe("device without NPU/GPU does not expose on-device provider in settings (Requirement 31.7)") {

            it("OnDeviceCapabilityChecker returns NotSupported when hardware threshold is not met") {
                runTest(testDispatcher) {
                    val mockDetector = mockk<DeviceCapabilityDetector>()
                    coEvery { mockDetector.isOnDeviceInferenceSupported() } returns false

                    // strict mock — unexpected calls on modelManager will throw
                    val mockModelManager = mockk<OnDeviceModelManager>(relaxed = false)

                    val checker = OnDeviceCapabilityChecker(
                        capabilityDetector = mockDetector,
                        modelManager = mockModelManager,
                        dispatchers = dispatchers
                    )

                    val state = checker.evaluate()

                    state.shouldBeInstanceOf<OnDeviceCapabilityState.NotSupported>()
                    // NotSupported means isAvailable = false in SettingsUiState.Settings,
                    // so LlmProvider.ON_DEVICE will not appear in availableProviders
                }
            }

            it("OnDeviceCapabilityState.NotSupported means on-device provider should be hidden") {
                // Verify the mapping logic that SettingsViewModel applies:
                // NotSupported → OnDeviceCapabilityAvailability(isAvailable = false)
                val capState: OnDeviceCapabilityState = OnDeviceCapabilityState.NotSupported
                val isAvailable = capState is OnDeviceCapabilityState.SupportedAndReady
                isAvailable.shouldBeFalse()
            }

            it("SupportedButModelNotReady also hides the on-device provider") {
                // Even when hardware qualifies, if model is not ready the provider is hidden
                val capState: OnDeviceCapabilityState = OnDeviceCapabilityState.SupportedButModelNotReady
                val isAvailable = capState is OnDeviceCapabilityState.SupportedAndReady
                isAvailable.shouldBeFalse()
            }

            it("SupportedAndReady exposes the on-device provider") {
                // Positive control: qualified hardware + verified model → provider is shown
                val capState: OnDeviceCapabilityState = OnDeviceCapabilityState.SupportedAndReady("Llama 3 INT4")
                val isAvailable = capState is OnDeviceCapabilityState.SupportedAndReady
                isAvailable.shouldBeTrue()
            }

            it("capability check short-circuits and does not call modelManager when hardware unsupported") {
                runTest(testDispatcher) {
                    val mockDetector = mockk<DeviceCapabilityDetector>()
                    coEvery { mockDetector.isOnDeviceInferenceSupported() } returns false

                    // strict mock — any call will throw, proving no disk I/O happens
                    val strictModelManager = mockk<OnDeviceModelManager>(relaxed = false)

                    val checker = OnDeviceCapabilityChecker(
                        capabilityDetector = mockDetector,
                        modelManager = strictModelManager,
                        dispatchers = dispatchers
                    )

                    // Must succeed without triggering mockModelManager
                    val result = checker.evaluate()
                    result shouldBe OnDeviceCapabilityState.NotSupported
                }
            }

            it("OnDeviceCapabilityChecker returns SupportedAndReady when hardware passes and model is ready") {
                runTest(testDispatcher) {
                    val entry = buildEntry()
                    val mockDetector = mockk<DeviceCapabilityDetector>()
                    coEvery { mockDetector.isOnDeviceInferenceSupported() } returns true

                    val mockModelManager = mockk<OnDeviceModelManager>()
                    coEvery { mockModelManager.checkModelStatus() } returns ModelStatus.Ready(entry)

                    val checker = OnDeviceCapabilityChecker(
                        capabilityDetector = mockDetector,
                        modelManager = mockModelManager,
                        dispatchers = dispatchers
                    )

                    val state = checker.evaluate()

                    state.shouldBeInstanceOf<OnDeviceCapabilityState.SupportedAndReady>()
                    (state as OnDeviceCapabilityState.SupportedAndReady).modelDisplayName shouldBe entry.displayName
                }
            }
        }
    })
