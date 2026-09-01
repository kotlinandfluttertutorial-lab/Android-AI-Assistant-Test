# Skill: AI Streaming Integration

## Purpose
Wire a new or existing feature screen to the project's AI streaming infrastructure:
`AIStreamClient` (WebSocket via OkHttp), `StreamEvent` sealed class, `LlmProvider`
enum, and the `ChatDetailViewModel` streaming lifecycle pattern.

## When to Use
- Adding AI streaming to a new feature (e.g. `feature-meeting`, `feature-code`)
- Implementing a new provider option in `LlmProvider`
- Debugging streaming reconnect / token collection issues
- Adding `StreamEvent.ToolCall` handling for MCP tool invocations

---

## Core Types (all in `:core-ai`)

### `AIStreamClient` interface
```kotlin
interface AIStreamClient {
    fun connect(conversationId: String, jwt: String): Flow<StreamEvent>
    fun sendMessage(payload: MessagePayload)
    fun disconnect()
}
```
`connect()` returns a **cold** `callbackFlow`. The WebSocket opens when the Flow is
collected and closes (via `awaitClose`) when the collector is cancelled.

### `StreamEvent` sealed class
```kotlin
sealed class StreamEvent {
    data class Token(val text: String) : StreamEvent()
    data class Done(val usage: TokenUsage) : StreamEvent()
    data class Error(val message: String) : StreamEvent()
    data class ToolCall(val toolName: String, val toolInput: Map<String, Any>) : StreamEvent()
}
```

### `LlmProvider` enum (in `:core-ai`)
```kotlin
enum class LlmProvider(val id: String, val display: String) {
    OPENAI_GPT4O("openai_gpt4o", "OpenAI GPT-4o"),
    GEMINI_1_5_PRO("gemini_1_5_pro", "Gemini 1.5 Pro"),
    CLAUDE_3_5_SONNET("claude_3_5_sonnet", "Claude 3.5 Sonnet"),
    OLLAMA("ollama", "Ollama (self-hosted)"),
    LLAMA_3X("llama_3x", "Llama 3.x"),
    MISTRAL("mistral", "Mistral"),
    ON_DEVICE("on_device", "On-Device (Private)");
}
const val ON_DEVICE_PROVIDER_ID = "on_device"
```

### `MessagePayload` (in `:core-ai`)
```kotlin
@Serializable
data class MessagePayload(
    val conversationId: String,
    val content: String,
    val provider: String,
)
```

---

## Reconnect Behaviour (implemented in `AIStreamClientImpl`)

| Attempt | Backoff |
|---------|---------|
| 1 | 1 s |
| 2 | 2 s |
| 3 | 4 s |
| 4 | 8 s |
| 5 (max) | 16 s → emits `StreamEvent.Error` and closes |

Capped at 30 s per attempt. Do **not** implement your own reconnect loop — use the
client as-is and handle `StreamEvent.Error` in the ViewModel.

---

## Standard ViewModel Streaming Pattern

Copy this pattern into any ViewModel that needs streaming. It mirrors `ChatDetailViewModel`.

```kotlin
@HiltViewModel
class <Feature>ViewModel @Inject constructor(
    private val streamClient: AIStreamClient,
    private val dispatchers: DispatcherProvider,
    // + domain use cases as needed
) : ViewModel() {

    private val _uiState = MutableStateFlow(<Feature>UiState())
    val uiState: StateFlow<<Feature>UiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private var pendingPayload: MessagePayload? = null

    // ── Public actions ────────────────────────────────────────────────────────

    fun sendMessage(content: String, provider: String) {
        if (content.isBlank()) return

        val payload = MessagePayload(
            conversationId = /* your conversationId */,
            content = content.trim(),
            provider = provider,
        )
        pendingPayload = payload

        _uiState.update { it.copy(
            isTypingIndicatorVisible = true,
            error = null,
            showRetryOption = false,
        ) }

        viewModelScope.launch(dispatchers.io) {
            startStreaming(payload)
        }
    }

    fun retryStreaming() {
        val payload = pendingPayload ?: return
        _uiState.update { it.copy(error = null, showRetryOption = false) }
        viewModelScope.launch(dispatchers.io) { startStreaming(payload) }
    }

    // ── Private streaming logic ───────────────────────────────────────────────

    private fun startStreaming(payload: MessagePayload) {
        streamingJob?.cancel()

        val jwt = /* read from SecureStorage via use case */
        val isOnDevice = payload.provider == ON_DEVICE_PROVIDER_ID

        streamingJob = viewModelScope.launch(dispatchers.io) {
            _uiState.update { it.copy(isStreaming = true, streamingText = "", isRunningOnDevice = isOnDevice) }

            try {
                val flow = streamClient.connect(payload.conversationId, jwt)
                streamClient.sendMessage(payload)

                flow.collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            _uiState.update { state ->
                                state.copy(
                                    isTypingIndicatorVisible = false,   // hide on first token
                                    streamingText = state.streamingText + event.text,
                                )
                            }
                        }
                        is StreamEvent.Done -> {
                            commitStreamedResponse(event.usage)
                            pendingPayload = null
                        }
                        is StreamEvent.Error -> {
                            _uiState.update { it.copy(
                                isStreaming = false,
                                isTypingIndicatorVisible = false,
                                error = DomainError.StreamingInterrupted(event.message),
                                showRetryOption = true,
                            ) }
                        }
                        is StreamEvent.ToolCall -> {
                            // Tool calls resolve server-side; tokens resume automatically.
                            // Optionally show a "using tool: X" indicator:
                            _uiState.update { it.copy(activeToolName = event.toolName) }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    isStreaming = false,
                    isTypingIndicatorVisible = false,
                    error = DomainError.StreamingInterrupted(e.message ?: "Connection lost"),
                    showRetryOption = true,
                ) }
            }
        }
    }

    private fun commitStreamedResponse(usage: TokenUsage) {
        val text = _uiState.value.streamingText
        if (text.isEmpty()) {
            _uiState.update { it.copy(isStreaming = false, isTypingIndicatorVisible = false, isRunningOnDevice = false) }
            return
        }
        // Persist the completed assistant message via a use case, then update state.
        // Example: saveMessageUseCase(conversationId, text, usage)
        _uiState.update { it.copy(
            isStreaming = false,
            streamingText = "",
            isTypingIndicatorVisible = false,
            isRunningOnDevice = false,
            // append the committed message to your message list here
        ) }
    }
}
```

---

## UiState Fields Required for Streaming

Add these fields to your `<Feature>UiState`:

```kotlin
data class <Feature>UiState(
    // ... your domain fields ...
    val isStreaming: Boolean = false,
    val streamingText: String = "",           // accumulates tokens in real-time
    val isTypingIndicatorVisible: Boolean = false,
    val isRunningOnDevice: Boolean = false,   // true when provider == ON_DEVICE_PROVIDER_ID
    val showRetryOption: Boolean = false,
    val activeToolName: String? = null,       // non-null while a ToolCall is in-flight
    val error: DomainError? = null,
)
```

---

## Composable: Rendering Streaming Text

Use `streamingText` to show a live cursor while streaming, then replace with the
committed message on `StreamEvent.Done`:

```kotlin
@Composable
fun StreamingMessageBubble(text: String, isStreaming: Boolean) {
    val displayText = if (isStreaming && text.isEmpty()) "" else text
    val suffix = if (isStreaming) "▌" else ""   // blinking cursor indicator

    Text(
        text = displayText + suffix,
        style = MaterialTheme.typography.bodyMedium,
    )
}
```

For Markdown rendering use the existing project dependency:
`com.github.jeziellago:compose-markdown:0.7.2` via `libs.compose.markdown`.

---

## Adding a New LLM Provider

1. Add an entry to `LlmProvider` enum in
   `core-ai/src/main/kotlin/com/aiassistant/core/ai/LlmProvider.kt`.
2. Add the `id`→display string to `SettingsViewModel.availableProviders`.
3. Add a new `*Client` class in `backend/app/services/llm_clients/` that extends
   `BaseLLMClient` and implements `stream()` and `complete()`.
4. Register the client in `AIOrchestrator._get_client()` switch in
   `backend/app/services/ai_orchestrator.py`.
5. Add the provider's API key to `.env.example` and `REQUIRED_ENV_VARS` in
   `backend/app/main.py` if the key is mandatory at startup.

---

## WebSocket URL Convention

The dev URL is hard-coded in `AIStreamClientImpl`:
```kotlin
private const val WS_BASE_URL = "ws://192.168.0.158:8000"
```
Production should read this from `BuildConfig.BASE_URL` with `http(s)` replaced by
`ws(s)`. When moving to a BuildConfig-driven URL:

```kotlin
// In NetworkModule.kt
@Provides
fun provideWsBaseUrl(): String =
    BuildConfig.BASE_URL
        .replace("https://", "wss://")
        .replace("http://",  "ws://")
        .trimEnd('/')
```

---

## Checklist

- [ ] `AIStreamClient` injected via Hilt (never `AIStreamClientImpl` directly)
- [ ] `streamingJob?.cancel()` called before starting a new stream
- [ ] Typing indicator hidden on first `StreamEvent.Token`
- [ ] `StreamEvent.Done` commits message and clears `streamingText`
- [ ] `StreamEvent.Error` sets `showRetryOption = true`, does NOT auto-reconnect
- [ ] `retryStreaming()` reuses `pendingPayload` — no new user input required
- [ ] `StreamEvent.ToolCall` handled (at minimum: not crashing)
- [ ] `isRunningOnDevice` set from `provider == ON_DEVICE_PROVIDER_ID`
- [ ] JWT read from `SecureStorage`, never hard-coded in production paths
- [ ] `collectAsStateWithLifecycle()` used in the Screen composable

---

## Pre-Push CI Gate

Every change to streaming code must pass all checks in `pre-push-check.ps1` before
pushing. Run from the repo root:

```powershell
.\pre-push-check.ps1
```

Exit `0` = safe to push. Exit `1` = fix failures first.

### Checks that streaming changes commonly affect

| Check | What to watch for in streaming code |
|-------|-------------------------------------|
| **ktlint** | Auto-fix with `./gradlew ktlintFormat` before committing |
| **Detekt** | `LongFunction` in `startStreaming()` — keep it under 60 lines; extract `handleEvent()` helper |
| **Detekt** | `MagicNumber` — extract reconnect constants (`MAX_RETRY_ATTEMPTS = 5`, `BASE_BACKOFF_MS = 1000L`) |
| **Android Unit Tests** | New `ViewModel` changes require updated `StateFlow` emission tests via Turbine |
| **Android Lint** | `ContentDescription` on every streaming-status icon/indicator composable |

### Run only Android checks (skip backend) when editing Kotlin only

```powershell
.\pre-push-check.ps1 -SkipBackend -SkipSecurity
```

### JaCoCo coverage gate (≥ 70% combined `domain` + `data`)

Streaming `UseCase` and `Repository` changes in `:domain` / `:data` affect coverage.
Check before pushing:

```powershell
./gradlew :domain:jacocoTestReport :data:jacocoTestReport
# Open domain/build/reports/jacoco/jacocoTestReport/html/index.html
```

See `.kiro/skills/pre-push-cicd-checks.md` for full fix guides for every check.
