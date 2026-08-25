# Skill: On-Device AI Integration

## Purpose
Work with the on-device AI subsystem in the Android AI Assistant: the `OnDeviceEngine`
interface, `StubOnDeviceEngine`, hardware capability detection, model management, and
the integration point with `AIStreamClient` and `ChatDetailViewModel`.

## When to Use
- Replacing `StubOnDeviceEngine` with a real JNI binding (llama.cpp / GGUF)
- Adding a new capability check for a device threshold
- Implementing model download, verification, or deletion
- Debugging why the On-Device option is not appearing in the Settings screen provider list
- Adding on-device inference to a new feature (e.g. voice transcription)

---

## Module: `:core-on-device-ai`

Package root: `com.aiassistant.core.ondeviceai`

Key files:
| File | Responsibility |
|---|---|
| `OnDeviceEngine.kt` | Interface for on-device LLM inference |
| `StubOnDeviceEngine.kt` | Default no-op binding (current Hilt binding) |
| `OnDeviceInferenceClient.kt` | Bridges `AIStreamClient` interface → `OnDeviceEngine` |
| `OnDeviceModelManager.kt` | Model download, file verification, deletion |
| `DeviceCapabilityDetector.kt` | Public API used by Settings to gate the provider option |
| `HardwareCapabilityDetector.kt` | NPU / GPU detection internals |
| `RamMonitor.kt` | Real-time RAM availability check |

Feature module:
- `:feature-on-device-ai` — Settings sub-screen for model download and inference config

---

## Provider ID

The on-device provider is identified by:
```kotlin
// core-ai: LlmProvider.kt
const val ON_DEVICE_PROVIDER_ID = "on_device"
enum class LlmProvider { ..., ON_DEVICE("on_device", "On-Device (Private)") }
```

This constant is used in:
- `ChatDetailViewModel.startStreaming()` — sets `isRunningOnDevice = true`
- `SettingsViewModel` — filters `availableProviders` to include `ON_DEVICE` only if
  `DeviceCapabilityDetector.isAvailable()` returns `true`

---

## `OnDeviceEngine` Interface

```kotlin
// core-on-device-ai
interface OnDeviceEngine {
    /**
     * Streams inference tokens for [prompt] as a cold Flow.
     * Each emission is a raw token string.
     * Completes when the model emits an EOS token.
     *
     * @param modelPath Absolute path to the GGUF model file on-device.
     * @param prompt    The fully assembled prompt string.
     */
    fun infer(modelPath: String, prompt: String): Flow<String>

    /**
     * Warm up the model so the first real inference doesn't pay the cold-start
     * cost. Optional — implementations may be no-ops.
     */
    suspend fun warmUp(modelPath: String)

    /**
     * Release all native resources (JNI handles, memory maps).
     * Must be called when the owning component is destroyed.
     */
    fun release()
}
```

---

## Replacing the Stub with a Real JNI Binding

`StubOnDeviceEngine` emits a single placeholder token and completes:
```kotlin
class StubOnDeviceEngine @Inject constructor() : OnDeviceEngine {
    override fun infer(modelPath: String, prompt: String): Flow<String> =
        flowOf("[On-device inference not yet implemented]")
    override suspend fun warmUp(modelPath: String) = Unit
    override fun release() = Unit
}
```

To replace it with a real llama.cpp binding:

### 1. Add the JNI wrapper

```kotlin
// core-on-device-ai/.../LlamaCppEngine.kt
class LlamaCppEngine @Inject constructor() : OnDeviceEngine {

    init {
        System.loadLibrary("llama_jni")  // loads libs/arm64-v8a/libllama_jni.so
    }

    override fun infer(modelPath: String, prompt: String): Flow<String> = callbackFlow {
        val ctx = nativeCreateContext(modelPath) // JNI call
        try {
            nativeStreamInference(ctx, prompt) { token: String ->
                trySend(token)
            }
        } finally {
            nativeFreeContext(ctx)
        }
        awaitClose()
    }

    override suspend fun warmUp(modelPath: String) {
        withContext(Dispatchers.Default) { nativeWarmUp(modelPath) }
    }

    override fun release() { /* no-op — context freed per inference */ }

    // JNI declarations
    private external fun nativeCreateContext(modelPath: String): Long
    private external fun nativeStreamInference(ctx: Long, prompt: String, callback: (String) -> Unit)
    private external fun nativeFreeContext(ctx: Long)
    private external fun nativeWarmUp(modelPath: String)
}
```

### 2. Update the Hilt binding

In `core-on-device-ai/di/OnDeviceModule.kt`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class OnDeviceModule {
    @Binds
    @Singleton
    abstract fun bindOnDeviceEngine(
        // Change from StubOnDeviceEngine to LlamaCppEngine for production
        impl: LlamaCppEngine
    ): OnDeviceEngine
}
```

### 3. Place `.so` files

Put compiled libraries under:
```
core-on-device-ai/src/main/jniLibs/arm64-v8a/libllama_jni.so
core-on-device-ai/src/main/jniLibs/x86_64/libllama_jni.so   # emulator
```

---

## Hardware Capability Detection

```kotlin
// DeviceCapabilityDetector.kt
class DeviceCapabilityDetector @Inject constructor(
    private val hardwareDetector: HardwareCapabilityDetector,
    private val ramMonitor: RamMonitor,
) {
    /**
     * Returns true when the device is capable of running on-device inference.
     *
     * Conditions (Requirement 31.1):
     * - Has NPU or GPU with adequate compute capability, AND
     * - Available RAM >= RAM_THRESHOLD_MB at evaluation time
     */
    fun isAvailable(): Boolean =
        hardwareDetector.hasNpuOrGpu() && ramMonitor.availableRamMb >= RAM_THRESHOLD_MB

    companion object {
        const val RAM_THRESHOLD_MB = 3_000  // 3 GB — tune per tested device matrix
    }
}
```

`SettingsViewModel` calls `DeviceCapabilityDetector.isAvailable()` and filters
`availableProviders` before emitting the UI state:

```kotlin
val providers = LlmProvider.entries.filter { provider ->
    if (provider == LlmProvider.ON_DEVICE) capabilityDetector.isAvailable() else true
}
```

---

## Model Manager

```kotlin
// OnDeviceModelManager.kt
interface OnDeviceModelManager {
    /** Downloads and verifies the GGUF model. Emits progress 0.0..1.0. */
    fun downloadModel(modelUrl: String, targetPath: String): Flow<Float>

    /** SHA-256 integrity check on the stored file. */
    suspend fun verifyModel(modelPath: String, expectedSha256: String): Boolean

    /** Deletes the model file to reclaim storage. */
    suspend fun deleteModel(modelPath: String)

    /** Returns the size of the stored model in bytes, or null if not present. */
    suspend fun modelSize(modelPath: String): Long?
}
```

Model download is a long-running operation — use `WorkManager` for the actual HTTP
download (scheduled via `:feature-on-device-ai`'s `DownloadModelWorker`). The Flow
from `downloadModel()` is a progress proxy backed by WorkManager `LiveData`/`Flow`.

---

## `OnDeviceInferenceClient`

This class adapts `OnDeviceEngine` to the `AIStreamClient` interface so
`ChatDetailViewModel` doesn't need to know whether it's using cloud or on-device:

```kotlin
@Singleton
class OnDeviceInferenceClient @Inject constructor(
    private val engine: OnDeviceEngine,
    private val modelManager: OnDeviceModelManager,
) : AIStreamClient {

    override fun connect(conversationId: String, jwt: String): Flow<StreamEvent> = callbackFlow {
        // jwt is ignored for on-device inference — no network call
        awaitClose()
    }

    override fun sendMessage(payload: MessagePayload) {
        // Launches inference; tokens flow via connect()
    }

    override fun disconnect() = engine.release()
}
```

The Hilt binding for `AIStreamClient` is typically `AIStreamClientImpl` (WebSocket).
For on-device, override the binding in `feature-on-device-ai/di/` using a
`@Named("onDevice")` qualifier or a conditional Hilt binding based on the selected
provider.

---

## Privacy Guarantee

On-Device inference means:
- No tokens, conversation content, or user data leave the device.
- The `jwt` parameter in `connect()` is intentionally ignored.
- `SecureStorage` credentials are not accessed during on-device inference.
- The `isRunningOnDevice = true` flag in `ChatDetailUiState` drives a privacy
  indicator in the UI (e.g. a padlock icon) — do not remove this flag.

---

## Checklist

- [ ] `DeviceCapabilityDetector.isAvailable()` checked before showing ON_DEVICE in Settings
- [ ] `ON_DEVICE_PROVIDER_ID` constant used (not the raw string `"on_device"`)
- [ ] `isRunningOnDevice = true` set in UiState when using on-device provider
- [ ] JNI `.so` libraries placed under `jniLibs/arm64-v8a/`
- [ ] `OnDeviceEngine.release()` called when ViewModel is cleared
- [ ] Model download uses WorkManager (not a raw coroutine in the ViewModel)
- [ ] `verifyModel()` called after download before the model is used
- [ ] No user data sent over the network when `provider == ON_DEVICE_PROVIDER_ID`
- [ ] Privacy indicator visible in the chat UI when on-device is active
- [ ] Stub remains the Hilt default until the JNI binding is production-ready
