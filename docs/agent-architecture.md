# Agent Architecture

> Phase 3–10 implementation — Android AI Assistant (Enterprise Edition)

## Overview

The agent system exposes a multi-layer architecture that routes user requests
through a domain-layer orchestrator to specialised agents, each of which
delegates to an infrastructure service (WebSocket, LLM, on-device model, MCP
tool broker, etc.) without any agent knowing about any other.

```
Feature Module (ViewModel)
        │  injects domain interface only
        ▼
AgentGatewayRepository  ← domain interface
        │  implemented by
        ▼
AgentGateway  (:data)   ← Hilt singleton
        │
        ▼
DefaultAgentOrchestrator  (:domain)
        │  auth guard → concurrency gate → route → plan → execute
        ├── DefaultAgentRouter
        ├── DefaultAgentPlanner
        └── DefaultAgentRegistry
                │
                ├── ChatAgent       → AIStreamClient (WebSocket)
                ├── CodeAgent       → CodeRepository
                ├── RagAgent        → DocumentRepository
                ├── PdfAgent        → DocumentRepository (upload/ingest)
                ├── ToolAgent       → ToolRegistry → Tool implementations
                ├── WebAgent        → WebSearchProvider
                ├── ImageAgent      → ImageAnalysisRemoteDataSource
                ├── VoiceAgent      → SpeechToTextProvider / TextToSpeechProvider
                └── OnDeviceAgent   → OnDeviceInferencePort → OnDeviceInferenceClient
```

## Module Dependency Rules

| Module | Can depend on | Cannot depend on |
|--------|--------------|-----------------|
| `:domain` | `:core-common` | `:data`, `:feature-*`, `:core-ai` |
| `:data` | `:domain`, `:core-*` | `:feature-*` |
| `:feature-*` | `:domain`, `:core-*` | `:data`, other `:feature-*` |
| `:feature-on-device-ai` | `:domain`, `:core-ai` | `:data` |

## Request Lifecycle

1. Feature ViewModel calls `agentGateway.executeChat(conversationId, content, provider, mode)`.
2. `AgentGateway` resolves `userId` from `AgentContext` — rejects `"anonymous"` / blank.
3. `DefaultAgentOrchestrator.execute()`:
   - **Auth guard** — rejects unauthenticated user IDs (Phase 10).
   - **Concurrency gate** — max 5 concurrent executions per user (Phase 10).
   - **Route** — `DefaultAgentRouter` selects the agent by metadata hint or capabilities.
   - **Plan** — `DefaultAgentPlanner` builds an `AgentPlan` with hard safety limits.
   - **Recursion check** — rejects plans with 3+ consecutive same-agent steps.
   - **Execute** — `executePlan()` loop: check limits → resolve agent → execute step → handoff.
4. Each agent emits `Flow<AgentEvent>` — the orchestrator channels all events to the caller.
5. The ViewModel collects `AgentEvent` and projects to `UiState`.

## AgentMode → Routing Hint

| AgentMode | Agent name hint | Capability added |
|-----------|----------------|-----------------|
| AUTO | none | none |
| CHAT | `conversational` | none |
| CODE | `code-analysis` | `CODE_ANALYSIS` |
| RESEARCH | `web-search` | `SEMANTIC_SEARCH` |
| DOCUMENT | `rag` | `DOCUMENT_RETRIEVAL` |
| IMAGE | `image-analysis` | `IMAGE_UNDERSTANDING` |
| VOICE | `voice` | `SPEECH_TO_TEXT` |
| LOCAL | `on_device` | `ON_DEVICE_INFERENCE` + `routing_mode=LOCAL_ONLY` |

## Safety Limits

All limits are enforced by `DefaultAgentPlanner.checkLimits()` before each
step and by `withTimeoutOrNull()` around the entire plan:

| Limit | Default | Hard cap |
|-------|---------|----------|
| `maxAgentSteps` | 10 | 50 |
| `maxAgentHandoffs` | 3 | 10 |
| `maxToolCalls` | 20 | 100 |
| `agentTimeoutMs` | 60,000 ms | — |
| `maxConcurrentPerUser` | 5 | — |

## Multi-Agent Handoff

The `metadata["plan_steps"]` key (value: `"pdf,rag,code"`) instructs
`DefaultAgentPlanner` to build a multi-step plan. Each step's output becomes
the next step's input. Phase 8 fixed the bug where content was always `null`
between steps — now tracked via `AgentEvent.Completed.result.content`.

Handoff events surfaced in the UI:
- `AgentEvent.HandoffStarted(fromAgent, toAgent, handoffIndex, context)`
- `AgentEvent.HandoffCompleted(fromAgent, toAgent, handoffIndex, outputSummary)`
