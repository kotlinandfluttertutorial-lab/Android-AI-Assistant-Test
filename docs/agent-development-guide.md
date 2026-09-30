# Agent Development Guide

> How to create a new agent without modifying the core orchestrator.

## Example: Adding a WeatherAgent

This guide walks through adding a `WeatherAgent` that fetches current weather
data. At no point do you need to modify `DefaultAgentOrchestrator`,
`DefaultAgentRouter`, `DefaultAgentPlanner`, or `DefaultAgentRegistry`.

---

### Step 1 — Declare the capability (if new)

If your agent introduces a new cross-cutting capability, add it to
`domain/src/main/kotlin/com/aiassistant/domain/agent/AgentCapability.kt`:

```kotlin
// AgentCapability.kt
enum class AgentCapability {
    // … existing values …
    WEATHER_ACCESS,   // ← add only if genuinely new
}
```

`WeatherAgent` can reuse `SEMANTIC_SEARCH` or `NETWORK_ACCESS` if applicable —
you do NOT need a new capability unless it's architecturally distinct.

---

### Step 2 — Add AgentMode routing hint (optional)

If you want a dedicated UI mode (e.g. a "Weather" tab), add the value to
`AgentMode` in `:domain` and wire `toCapabilities()` and `toAgentNameHint()`:

```kotlin
enum class AgentMode {
    // …
    WEATHER,   // new
    ;
    companion object {
        fun toCapabilities(mode: AgentMode): Set<AgentCapability> = when (mode) {
            WEATHER -> setOf(AgentCapability.SEMANTIC_SEARCH)
            // …
        }
        fun toAgentNameHint(mode: AgentMode): String? = when (mode) {
            WEATHER -> "weather"
            // …
        }
    }
}
```

---

### Step 3 — Define a domain port (if wrapping external infra)

Create a domain interface so `:data` can depend on it without importing
the feature module:

```kotlin
// domain/agent/WeatherProvider.kt
interface WeatherProvider {
    val isConfigured: Boolean
    suspend fun getCurrentWeather(location: String): Result<WeatherData>
}

data class WeatherData(
    val location: String,
    val temperatureCelsius: Float,
    val description: String,
)
```

---

### Step 4 — Implement the Agent in `:data`

```kotlin
// data/agent/WeatherAgent.kt
@Singleton
class WeatherAgent @Inject constructor(
    private val weatherProvider: WeatherProvider,
) : Agent {

    override val name: String = NAME
    override val description = "Fetches current weather for a location."
    override val capabilities = setOf(AgentCapability.SEMANTIC_SEARCH)

    override fun canHandle(request: AgentRequest): Boolean {
        val explicit = request.metadata[METADATA_AGENT_NAME]
        if (!explicit.isNullOrBlank()) return explicit == name
        return AgentCapability.SEMANTIC_SEARCH in request.capabilities
    }

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = flow {
        emit(AgentEvent.Started(execution.executionId, name))
        emit(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        if (!weatherProvider.isConfigured) {
            emit(AgentEvent.Failed(failedResult(execution, request,
                "WEATHER_NOT_CONFIGURED", "Weather API key not set.")))
            return@flow
        }

        val location = request.metadata["location"] ?: request.input.trim()
        val result = weatherProvider.getCurrentWeather(location)

        if (result.isFailure) {
            emit(AgentEvent.Failed(failedResult(execution, request,
                "WEATHER_ERROR", result.exceptionOrNull()?.message ?: "Unknown error")))
            return@flow
        }

        val weather = result.getOrThrow()
        val content = "${weather.location}: ${weather.temperatureCelsius}°C, ${weather.description}"
        emit(AgentEvent.Token(content))
        emit(AgentEvent.Completed(AgentResult(
            executionId = execution.executionId,
            requestId = request.requestId,
            agentName = name,
            status = AgentStatus.COMPLETED,
            content = content,
        )))
    }

    private fun failedResult(e: AgentExecution, r: AgentRequest, code: String, msg: String) =
        AgentResult(executionId = e.executionId, requestId = r.requestId,
            agentName = name, status = AgentStatus.FAILED,
            error = AgentError(code, msg))

    companion object {
        const val NAME = "weather"
        const val METADATA_AGENT_NAME = "agent_name"
    }
}
```

---

### Step 5 — Register in AgentGateway

In `data/agent/AgentGateway.kt`, add `weatherAgent: WeatherAgent` to the
constructor and register it:

```kotlin
@Singleton
class AgentGateway @Inject constructor(
    // … existing agents …
    private val weatherAgent: WeatherAgent,
    // …
) {
    private val registry = DefaultAgentRegistry().also { reg ->
        // … existing registrations …
        reg.register(weatherAgent)
    }
    // …
}
```

---

### Step 6 — Wire Hilt bindings in AgentDataModule

```kotlin
// In AgentDataModule.kt companion object:
@Binds @Singleton
abstract fun bindWeatherProvider(impl: OpenWeatherMapProvider): WeatherProvider
```

---

### Step 7 — Write tests

```kotlin
class WeatherAgentTest {
    private val provider = mockk<WeatherProvider>()
    private val agent = WeatherAgent(provider)

    @Test
    fun `success emits Token and Completed`() = runTest {
        every { provider.isConfigured } returns true
        coEvery { provider.getCurrentWeather(any()) } returns
            Result.success(WeatherData("London", 18f, "Cloudy"))

        val req = AgentRequest(userId = "u1", input = "London weather",
            metadata = mapOf("agent_name" to "weather"))
        val events = agent.execute(req, AgentExecution(request=req, agentName="weather")).toList()

        events.filterIsInstance<AgentEvent.Completed>().first()
            .result.status shouldBe AgentStatus.COMPLETED
    }
}
```

---

### What you did NOT need to change

- `DefaultAgentOrchestrator` — routing is capability/name-based; no hardcoded logic
- `DefaultAgentRouter` — picks `WeatherAgent` via `metadata["agent_name"]="weather"` or capability
- `DefaultAgentPlanner` — plan limits apply automatically
- `DefaultAgentRegistry` — registered at gateway construction time, not hardcoded
