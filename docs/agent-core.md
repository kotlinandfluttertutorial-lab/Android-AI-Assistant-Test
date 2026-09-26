# Agent Core Architecture — Phase 1

> **Status:** Implemented, tested, and linted.  
> **Date:** 2026-09-26  
> **Scope:** Foundational types and interfaces only. No existing Chat/Code/PDF flows were modified.

---

## Table of Contents

1. [Overview](#1-overview)
2. [Design Principles](#2-design-principles)
3. [Architecture Placement](#3-architecture-placement)
4. [Component Reference](#4-component-reference)
   - 4.1 [AgentStatus](#41-agentstatus)
   - 4.2 [AgentCapability](#42-agentcapability)
   - 4.3 [AgentRequest](#43-agentrequest)
   - 4.4 [AgentContext](#44-agentcontext)
   - 4.5 [AgentResult](#45-agentresult)
   - 4.6 [AgentDecision](#46-agentdecision)
   - 4.7 [AgentEvent](#47-agentevent)
   - 4.8 [AgentExecution](#48-agentexecution)
   - 4.9 [Agent Interface](#49-agent-interface)
5. [Lifecycle State Machine](#5-lifecycle-state-machine)
6. [AI Request Flow (Agent Path)](#6-ai-request-flow-agent-path)
7. [Example: Implementing an Agent](#7-example-implementing-an-agent)
8. [File Inventory](#8-file-inventory)
9. [Test Coverage](#9-test-coverage)
10. [Recommended Integration Points](#10-recommended-integration-points)

---

## 1. Overview

Phase 1 introduces the **Agent Core** — a provider-independent, strongly-typed foundation for multi-step AI reasoning on both Android (Kotlin) and the backend (Python).

The agent core defines:
- A complete lifecycle state machine (`AgentStatus`)
- A typed capability model (`AgentCapability`)
- Validated input/output contracts (`AgentRequest`, `AgentResult`)
- Runtime context assembly (`AgentContext`)
- Step-level reasoning records (`AgentDecision`, `AgentExecution`)
- An incremental event stream (`AgentEvent`)
- A provider-independent interface (`Agent`)

**Nothing in the existing system was modified.** All new types live in isolated packages:
- Android: `com.aiassistant.domain.agent`
- Backend: `app.agents`

---

## 2. Design Principles

| Principle | How it is applied |
|---|---|
| **Immutability** | All data types are immutable (`data class` / `frozen=True`). State changes return new snapshots via `withStatus()`, `withStep()`, etc. |
| **Validation at the boundary** | `AgentRequest` and `AgentResult` validate in their `init` / `field_validator` blocks. Invalid objects cannot be constructed. |
| **Typed state machine** | `AgentStatus.canTransitionTo()` enforces the directed graph of valid lifecycle transitions. `AgentExecution.withStatus()` throws on invalid moves. |
| **Provider independence** | The `Agent` interface accepts `AgentRequest` and returns `Flow<AgentEvent>` / `AsyncIterator[AgentEvent]`. No LLM SDK types appear in the interface layer. |
| **Cold streams** | `Agent.execute()` returns a cold `Flow` (Kotlin) / async generator (Python). Execution only begins when the caller subscribes; cancellation is automatic. |
| **Existing conventions** | Android types follow existing domain patterns (`data class`, `sealed class`, `@Serializable`, `javax.inject`). Backend types use Pydantic v2 with `ConfigDict(frozen=True)`, matching `app/schemas/`. |

---

## 3. Architecture Placement

```
Android Clean Architecture layers
─────────────────────────────────────────────────────────────
:domain
  └── com.aiassistant.domain.agent/        ← NEW (Phase 1)
        AgentStatus, AgentCapability
        AgentRequest, AgentContext
        AgentResult (+ AgentToolCall, AgentCitation,
                       AgentAttachment, AgentUsage,
                       AgentError, AgentNextAction)
        AgentDecision (sealed: Respond, CallTool,
                                Retrieve, Wait, Finish)
        AgentEvent   (sealed: Started, StatusChanged,
                               Token, Thinking,
                               ToolStarted/Completed/Failed/
                               ToolConfirmationRequired,
                               RetrievalCompleted,
                               Completed, Failed, Cancelled)
        AgentExecution + AgentStep
        Agent (interface)

:data  (future Phase 2)
  └── Agent implementations (ConversationalAgent, RagAgent, …)
      AgentRegistry, AgentOrchestrator

:feature-* (future Phase 2+)
  └── AgentViewModel observes Flow<AgentEvent>
─────────────────────────────────────────────────────────────

Backend layers
─────────────────────────────────────────────────────────────
app/agents/                               ← NEW (Phase 1)
  __init__.py   (re-exports public surface)
  models.py     (Pydantic v2 frozen models, all types above)
  base.py       (Agent ABC)

app/api/agents/        (future Phase 2)
app/workers/agent_worker.py  (future Phase 2)
─────────────────────────────────────────────────────────────
```

---

## 4. Component Reference

### 4.1 AgentStatus

**Package:** `com.aiassistant.domain.agent` / `app.agents.models`

Enum of 8 lifecycle states with two computed properties and a transition guard.

| Status | Meaning |
|---|---|
| `REQUESTED` | Submitted, not yet picked up |
| `STARTED` | Accepted; context assembly underway |
| `RUNNING` | Actively executing steps |
| `WAITING` | Paused for external signal (tool confirmation, async result) |
| `COMPLETED` | Finished successfully — **terminal** |
| `PARTIAL` | Finished with incomplete results — **terminal** |
| `FAILED` | Unrecoverable error — **terminal** |
| `CANCELLED` | Stopped before completion — **terminal** |

**Key methods:**

```kotlin
// Kotlin
status.isTerminal                     // COMPLETED|PARTIAL|FAILED|CANCELLED
status.isSuccess                      // COMPLETED|PARTIAL
status.canTransitionTo(AgentStatus.RUNNING)  // enforced by AgentExecution
```

```python
# Python
status.is_terminal
status.is_success
status.can_transition_to(AgentStatus.RUNNING)
```

---

### 4.2 AgentCapability

15-value enum describing what an agent can do. Agents declare a `Set<AgentCapability>`; the router matches against `AgentRequest.capabilities`.

```
TEXT_GENERATION      STREAMING            DOCUMENT_RETRIEVAL
CODE_ANALYSIS        SPEECH_TO_TEXT       TEXT_TO_SPEECH
IMAGE_UNDERSTANDING  TOOL_USE             MEMORY_ACCESS
ON_DEVICE_INFERENCE  SEMANTIC_SEARCH      MULTI_STEP_REASONING
TRANSLATION          DOCUMENT_GENERATION  PRODUCTIVITY_MANAGEMENT
```

---

### 4.3 AgentRequest

**Validation rules:**

| Field | Rule |
|---|---|
| `input` | Must not be blank |
| `userId` | Must not be blank |
| `maxSteps` | 1–50 (default 10) |
| `timeoutMs` | > 0 (default 60,000 ms) |

Optional fields: `conversationId`, `provider`, `capabilities`, `context`, `streamingEnabled`, `metadata`.

**Builder pattern (Kotlin):**

```kotlin
val request = AgentRequest.Builder(userId = "user-abc", input = "Summarise document X")
    .conversationId("conv-123")
    .capabilities(setOf(AgentCapability.DOCUMENT_RETRIEVAL))
    .maxSteps(5)
    .build()
```

---

### 4.4 AgentContext

Assembled by the orchestration layer before calling `Agent.execute()`. Agents never query MemoryService, PersonaRepository, etc. directly — they receive a fully assembled context.

```kotlin
AgentContext(
    userId = "user-abc",
    conversationHistory = listOf(ContextMessage("user", "Hello")),
    memories = listOf(ContextMemory("prefers dark mode", 0.92f)),
    personaSystemPrompt = "You are a concise assistant.",
    availableTools = listOf("github", "gmail"),
    isPrivacyMode = false,
    isOffline = false,
)
```

**Mutation helpers (returns new instance):**
- `withMemories(additional)` — appends memories (used when retrieval completes asynchronously)
- `withTools(tools)` — adds tools (deduplicated)

---

### 4.5 AgentResult

**Only created with a terminal `AgentStatus`** — the constructor / validator enforces this.

| Field | Type | Notes |
|---|---|---|
| `executionId` | String | Links back to the `AgentExecution` |
| `requestId` | String | Links back to the `AgentRequest` |
| `agentName` | String | Which agent ran |
| `status` | AgentStatus | Must be terminal |
| `content` | String? | Primary text output |
| `toolCalls` | List<AgentToolCall> | All MCP invocations |
| `citations` | List<AgentCitation> | RAG source citations |
| `attachments` | List<AgentAttachment> | Generated files |
| `usage` | AgentUsage? | Token counts + cost |
| `error` | AgentError? | Set when status = FAILED |
| `nextAction` | AgentNextAction? | Suggested follow-up |
| `metadata` | Map<String,String> | Diagnostics |

---

### 4.6 AgentDecision

Sealed class / discriminated union describing what the agent wants to do on the current step.

| Variant | Fields | Description |
|---|---|---|
| `Respond` | `content`, `isFinal`, `streaming` | Produce text output |
| `CallTool` | `toolName`, `parameters`, `requiresConfirmation`, `rationale` | Invoke MCP tool |
| `Retrieve` | `query`, `documentIds`, `topK`, `minScore` | RAG retrieval |
| `Wait` | `reason`, `waitForType`, `payload` | Pause for external signal |
| `Finish` | `reason` | Declare execution complete |

**Python factory methods:**

```python
AgentDecision.respond("Here is your answer.", is_final=True)
AgentDecision.call_tool("github", '{"title":"Bug"}', requires_confirmation=True)
AgentDecision.retrieve("architecture patterns", top_k=3)
AgentDecision.wait("Awaiting user confirmation")
AgentDecision.finish()
```

---

### 4.7 AgentEvent

Sealed class / discriminated union emitted from `Agent.execute()` as a stream.

| Category | Events |
|---|---|
| Lifecycle | `Started`, `StatusChanged` |
| Content | `Token` |
| Reasoning | `Thinking` |
| Tools | `ToolStarted`, `ToolCompleted`, `ToolFailed`, `ToolConfirmationRequired` |
| Retrieval | `RetrievalCompleted` |
| Terminal | `Completed`, `Failed`, `Cancelled` |

The stream **always ends** with one of `Completed`, `Failed`, or `Cancelled`.

---

### 4.8 AgentExecution

The runtime envelope wrapping a request and accumulating state.

```kotlin
// Immutable — every helper returns a new snapshot
val exec = AgentExecution(request = request, agentName = "rag-agent")

val running = exec
    .withStatus(AgentStatus.STARTED)
    .withStatus(AgentStatus.RUNNING)
    .withStep(AgentStep(0, AgentDecision.Retrieve("What is RAG?")))

val done = running.withResult(result)  // status transitions automatically
```

Invalid transitions (`REQUESTED → RUNNING`) throw `IllegalStateException` / `ValueError`.

`cancel()` is idempotent — calling it on an already-terminal execution returns the original unchanged.

---

### 4.9 Agent Interface

```kotlin
interface Agent {
    val name: String
    val description: String
    val capabilities: Set<AgentCapability>

    // Default: returns true when request.capabilities ⊆ this.capabilities
    fun canHandle(request: AgentRequest): Boolean

    // Cold Flow — starts on collection, cancels automatically
    fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent>
}
```

```python
class Agent(ABC):
    @property
    @abstractmethod
    def name(self) -> str: ...

    @property
    @abstractmethod
    def description(self) -> str: ...

    @property
    @abstractmethod
    def capabilities(self) -> frozenset[AgentCapability]: ...

    # Default implementation — override for richer logic
    def can_handle(self, request: AgentRequest) -> bool: ...

    # Async generator — yields AgentEvent, always ends with terminal event
    @abstractmethod
    def execute(self, request: AgentRequest, execution: AgentExecution) -> AsyncIterator[AgentEvent]: ...
```

---

## 5. Lifecycle State Machine

```
                        ┌──────────────┐
                        │  REQUESTED   │
                        └──────┬───────┘
                 accepted │         │ cancel
                        ┌──▼───────▼┐
                        │  STARTED  │
                        └──────┬────┘
               first step │
                        ┌──▼────────┐        ┌───────────┐
                        │  RUNNING  │◄───────►│  WAITING  │
                        └──┬────────┘ pause/  └───────────┘
              ┌────────────┼────────────────┐    resume
         done │        partial │       error │
        ┌─────▼────┐  ┌───────▼──┐  ┌──────▼────┐
        │COMPLETED │  │  PARTIAL │  │  FAILED   │
        └──────────┘  └──────────┘  └───────────┘

  CANCELLED reachable from REQUESTED, STARTED, RUNNING, WAITING
```

**All terminal states are final** — `canTransitionTo()` returns false for every next state.

---

## 6. AI Request Flow (Agent Path)

This flow does not exist in production yet — it shows where the agent core fits relative to the existing WebSocket infrastructure.

```
User input (future AgentScreen / ChatDetailViewModel)
  │
  ▼
AgentRequest.Builder(userId, input)
    .capabilities(setOf(DOCUMENT_RETRIEVAL, TEXT_GENERATION))
    .build()
  │
  ▼
AgentOrchestrator (future Phase 2)
  ├── Assemble AgentContext (memories, history, tools, persona)
  ├── AgentRegistry.select(request)  ← calls Agent.canHandle()
  ├── Create AgentExecution(request, agentName, status=STARTED)
  │
  ▼
Agent.execute(request, execution)  →  Flow<AgentEvent>
  │
  │  AgentEvent.Started
  │  AgentEvent.StatusChanged(RUNNING)
  │  AgentEvent.Thinking(0, "I need to retrieve context first")
  │  AgentEvent.ToolStarted("rag_retrieve", "{query: '...'}")
  │  AgentEvent.ToolCompleted("rag_retrieve", "{chunks: [...]}", 340ms)
  │  AgentEvent.RetrievalCompleted("query", chunkCount=3)
  │  AgentEvent.Token("The") AgentEvent.Token(" answer") ...
  │  AgentEvent.Completed(AgentResult(status=COMPLETED, content="..."))
  │
  ▼
ViewModel / WebSocket router
  ├── Accumulates Token events → streamingText
  ├── Shows ToolStarted → progress indicator
  ├── On Completed → commit message, clear streaming state
  └── On Failed → show error + retry option
```

---

## 7. Example: Implementing an Agent

### Android (Kotlin)

```kotlin
@Singleton
class ConversationalAgent @Inject constructor(
    private val streamClient: AIStreamClient,
    private val dispatchers: DispatcherProvider,
) : Agent {

    override val name = "conversational"
    override val description = "General-purpose conversational AI agent."
    override val capabilities = setOf(
        AgentCapability.TEXT_GENERATION,
        AgentCapability.STREAMING,
        AgentCapability.MEMORY_ACCESS,
    )

    // canHandle() uses the default: true when request capabilities ⊆ this.capabilities

    override fun execute(
        request: AgentRequest,
        execution: AgentExecution,
    ): Flow<AgentEvent> = callbackFlow {
        send(AgentEvent.Started(execution.executionId, name))
        send(AgentEvent.StatusChanged(execution.executionId, AgentStatus.RUNNING))

        var accumulated = ""
        try {
            streamClient.connect(request.conversationId ?: "", jwt = "...").collect { event ->
                when (event) {
                    is StreamEvent.Token -> {
                        accumulated += event.text
                        send(AgentEvent.Token(event.text))
                    }
                    is StreamEvent.Done -> {
                        val result = AgentResult(
                            executionId = execution.executionId,
                            requestId = request.requestId,
                            agentName = name,
                            status = AgentStatus.COMPLETED,
                            content = accumulated,
                            usage = AgentUsage(event.usage.inputTokens, event.usage.outputTokens),
                        )
                        send(AgentEvent.Completed(result))
                    }
                    is StreamEvent.Error -> {
                        val result = AgentResult(
                            executionId = execution.executionId,
                            requestId = request.requestId,
                            agentName = name,
                            status = AgentStatus.FAILED,
                            error = AgentError("STREAM_ERROR", event.message),
                        )
                        send(AgentEvent.Failed(result))
                    }
                    else -> Unit
                }
            }
        } finally {
            streamClient.disconnect()
        }
        awaitClose { streamClient.disconnect() }
    }
}
```

### Backend (Python)

```python
from collections.abc import AsyncIterator
from app.agents.base import Agent
from app.agents.models import (
    AgentCapability, AgentEvent, AgentExecution, AgentRequest,
    AgentResult, AgentStatus, AgentStartedEvent, AgentStatusChangedEvent,
    AgentTokenEvent, AgentCompletedEvent, AgentUsage, AgentDecision,
)

class ConversationalAgent(Agent):
    @property
    def name(self) -> str:
        return "conversational"

    @property
    def description(self) -> str:
        return "General-purpose conversational AI agent."

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({
            AgentCapability.TEXT_GENERATION,
            AgentCapability.STREAMING,
            AgentCapability.MEMORY_ACCESS,
        })

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        # ... call AIOrchestrator, stream tokens ...
        accumulated = ""
        async for token in orchestrator.stream(request):
            accumulated += token
            yield AgentTokenEvent(token=token)

        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=accumulated,
        )
        yield AgentCompletedEvent(result=result)
```

---

## 8. File Inventory

### Android (`:domain` module)

| File | Purpose |
|---|---|
| `domain/…/agent/AgentStatus.kt` | Lifecycle enum + `isTerminal`, `isSuccess`, `canTransitionTo()` |
| `domain/…/agent/AgentCapability.kt` | 15-value capability enum |
| `domain/…/agent/AgentRequest.kt` | Validated input contract + Builder |
| `domain/…/agent/AgentContext.kt` | Runtime context + `ContextMessage`, `ContextMemory` |
| `domain/…/agent/AgentResult.kt` | Output contract + `AgentToolCall`, `AgentCitation`, `AgentAttachment`, `AgentUsage`, `AgentError`, `AgentNextAction` |
| `domain/…/agent/AgentDecision.kt` | Sealed step-action type |
| `domain/…/agent/AgentEvent.kt` | Sealed event stream type (12 variants) |
| `domain/…/agent/AgentExecution.kt` | Runtime envelope + `AgentStep` |
| `domain/…/agent/Agent.kt` | Provider-independent interface |

### Android (`:domain` tests)

| File | Tests |
|---|---|
| `AgentStatusTest.kt` | 36 — terminal/success flags + all transitions |
| `AgentRequestTest.kt` | 17 — construction, validation, Builder |
| `AgentContextTest.kt` | 12 — defaults, `withMemories`, `withTools`, validation |
| `AgentResultTest.kt` | 18 — construction, validation, computed properties |
| `AgentExecutionTest.kt` | 22 — state transitions, immutability, helpers |
| `AgentDecisionTest.kt` | 12 — all variants + serialisation |
| `AgentEventTest.kt` | 14 — all 12 event variants |
| `AgentCapabilityTest.kt` | 8 — `canHandle()` default logic |

### Backend (`app/agents/`)

| File | Purpose |
|---|---|
| `app/agents/__init__.py` | Public re-export surface |
| `app/agents/models.py` | All Pydantic v2 frozen models |
| `app/agents/base.py` | `Agent` ABC |

### Backend (`tests/unit/agents/`)

| File | Tests |
|---|---|
| `test_agent_status.py` | 36 — status flags + all transitions |
| `test_agent_request.py` | 16 — construction, validation, serialisation |
| `test_agent_context.py` | 13 — defaults, helpers, validation, serialisation |
| `test_agent_result.py` | 18 — construction, validation, computed, serialisation |
| `test_agent_execution.py` | 24 — state transitions, immutability |
| `test_agent_decision.py` | 10 — all factory methods + round-trip |
| `test_agent_base.py` | 7 — `can_handle()` + ABC enforcement |

---

## 9. Test Coverage

### Backend

```
tests/unit/agents/ — 122 passed, 0 failed
ruff check app/agents/ tests/unit/agents/ — All checks passed
mypy app/agents/ — Success: no issues found
```

### Android

```
:domain:testReleaseUnitTest — BUILD SUCCESSFUL
All existing domain tests (59 test classes) — 0 failures
New agent test classes (8) — 0 failures
```

---

## 10. Recommended Integration Points

These are the next natural steps. No existing code needs to change — these are purely additive.

### Phase 2 — Agent Implementations (data layer)

| What to add | Where |
|---|---|
| `ConversationalAgent` — wraps existing `AIStreamClient` | `:data` module |
| `RagAgent` — wraps `QueryDocumentUseCase` + `AIStreamClient` | `:data` module |
| `CodeAnalysisAgent` — wraps `AnalyzeCodeUseCase` | `:data` module |
| `OnDeviceAgent` — wraps `OnDeviceInferenceClient` | `:data` or `:core-ai` |
| `AgentRegistry` — maps capability sets to agent instances | `:domain` (interface) / `:data` (impl) |
| `AgentOrchestrator` — assembles `AgentContext`, selects agent, drives event loop | `:domain` (interface) / `:data` (impl) |
| Backend: `app/agents/implementations/` | `ConversationalAgent`, `RagAgent`, etc. |
| Backend: `app/workers/agent_worker.py` | Celery task for long-running agents |

### Phase 2 — Android UI wiring

| What to add | Where |
|---|---|
| Fix JWT placeholder in `ChatDetailViewModel.startStreaming()` | `feature-chat` |
| `AgentViewModel` that collects `Flow<AgentEvent>` | New `feature-agent` or extend `feature-chat` |
| Tool-call progress composable in chat | `core-ui` |
| `AiModeIndicator` shows "Agent" badge during multi-step execution | Reuse existing `core-ui` component |

### Phase 2 — Backend routing

| What to add | Where |
|---|---|
| `POST /agents/execute` — new non-streaming endpoint | `app/api/agents/router.py` |
| Extend `/ws/chat/{conversation_id}` to emit `agent_step` events | `app/api/websocket/router.py` (backward-compatible addition) |
| Unify `BaseLLMClient` + `LLMProvider` into single abstraction | `app/agents/implementations/` |

### Phase 2 — WebSocket protocol extension

Add these event types to the existing WebSocket contract (backward-compatible — old clients ignore unknown type fields):

```jsonc
{"type": "agent_thinking",  "stepIndex": 0, "thought": "..."}
{"type": "agent_tool",      "toolName": "github", "phase": "started|completed|failed"}
{"type": "agent_retrieval", "chunkCount": 5}
```

Android `AIStreamClientImpl.parseEvent()` already handles unknown types as `StreamEvent.Error` — the new types just need adding to the parser.

---

*End of Agent Core Architecture document.*
