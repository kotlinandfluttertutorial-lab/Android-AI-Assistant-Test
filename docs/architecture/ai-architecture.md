# AI Architecture
## Android AI Assistant — Enterprise Edition

---

## Overview

The AI layer is built around a **provider-agnostic AIOrchestrator** that sits between the FastAPI
HTTP/WebSocket layer and the concrete LLM provider clients. All features — chat, RAG, code
analysis, meeting summarisation, notes, resume generation, translator — route LLM calls through
this single component.

---

## AI Orchestrator Design

```
Request (user message + conv_id + provider + user_id)
  │
  ▼
┌──────────────────────────────────────┐
│           AI Orchestrator            │
│  1. Resolve provider                 │
│  2. Retrieve top-3 memories          │
│  3. Fetch conversation history       │
│  4. Apply context summarisation       │
│     (if > 80% of token window used)  │
│  5. Build PromptContext               │
│  6. Detect prompt injection           │
│  7. Invoke provider adapter          │
│  8. Stream tokens via WebSocket      │
│  9. Persist message + token usage    │
│ 10. Trigger MCP tool calls if needed │
└──────────────────────────────────────┘
  │
  ▼
Response (streamed tokens or completion)
```

---

## Provider Adapter Pattern

Every LLM provider implements the `BaseLLMClient` abstract class:

```python
class BaseLLMClient(ABC):
    @abstractmethod
    async def stream(self, context: PromptContext) -> AsyncIterator[str]: ...

    @abstractmethod
    async def complete(self, context: PromptContext) -> str: ...

    @property
    @abstractmethod
    def max_context_tokens(self) -> int: ...

    @property
    @abstractmethod
    def cost_per_input_token(self) -> Decimal: ...

    @property
    @abstractmethod
    def cost_per_output_token(self) -> Decimal: ...
```

### Concrete Implementations

| Class | Provider | Notes |
|-------|---------|-------|
| `OpenAIClient` | OpenAI GPT-4o | Streams via OpenAI streaming API |
| `GeminiClient` | Google Gemini 1.5 Pro | Streams via Vertex/Gemini API |
| `ClaudeClient` | Anthropic Claude 3.5 Sonnet | Streams via Anthropic API |
| `OllamaClient` | Ollama (self-hosted) | Routes to local endpoint; **no external network calls** |
| `LlamaClient` | Llama 3.x | Local or API-hosted |
| `MistralClient` | Mistral | Streams via Mistral API |

---

## Prompt Pipeline

```
1. System Prompt (from versioned template)
     │
2. Injected Memories (top-3 from ChromaDB)
     │
3. Conversation History (up to token limit)
     │   → If > 80% used: summarise oldest turns
     │
4. RAG Context (if document query — top-5 chunks + citations)
     │
5. User Message
     │
6. Tool Results (if MCP tool was invoked)
     ▼
   PromptContext → Provider Adapter → LLM
```

---

## Memory Injection

The `MemoryService` stores user facts, preferences, and writing style observations as vector
embeddings in a user-scoped ChromaDB collection (`memories_{user_id}`).

**Injection flow:**
1. User message text is embedded using the configured embedding model
2. ChromaDB cosine similarity search retrieves the top-3 memories
3. Memories are prepended to the system prompt as structured facts
4. If retrieval fails or returns zero results, the orchestrator proceeds without memory injection

**Privacy mode:** When enabled, the session captures no new memories but existing ones are still
injected (they are not deleted).

---

## Safety Filters

### Prompt Injection Detection

`SafetyService.detect_prompt_injection(text: str) -> bool` scans inputs for patterns including:
- Instruction override phrases ("ignore previous instructions", "disregard the above")
- Role-switching attempts ("you are now", "act as", "pretend to be")
- System prompt extraction attempts ("repeat your instructions", "what are your rules")
- Jailbreak delimiters (e.g., DAN, STAN prompts)

**On detection:** HTTP 400 is returned, the input is **not forwarded** to any LLM, and the blocked
attempt is logged to the audit log with the user ID, timestamp, and detected pattern.

### Input Sanitisation

`InputSanitizer` strips SQL injection patterns and XSS vectors from all user-supplied strings
before they reach the database layer.

---

## Fallback Logic

```
Request with provider = "openai"
  │
  ├─► Try OpenAIClient
  │       │
  │       ├─► Success → stream response
  │       └─► Error (timeout / rate limit / 5xx)
  │               │
  │               └─► Try fallback_provider (from config)
  │                       │
  │                       ├─► Success → stream response + notify user of substitution
  │                       └─► Error → return structured error to user
  └─► (no fallback configured) → return structured error to user
```

The substitution notification is sent as a system message in the conversation.
No notification is sent if no fallback attempt was made.

---

## Context Window Management

When conversation history reaches 80% of the active provider's `max_context_tokens`:
1. The oldest message turns (excluding the system prompt) are extracted
2. They are summarised using a `complete()` call to the same provider with a summarisation prompt
3. The summary replaces the raw history turns in the PromptContext
4. The original messages remain in PostgreSQL for audit / export purposes

---

## WebSocket Event Schema

All streaming events follow a typed JSON envelope:

| Event | Payload | Meaning |
|-------|---------|---------|
| `token` | `{"type":"token","data":"<text>"}` | Next token from LLM |
| `done` | `{"type":"done","usage":{"input_tokens":N,"output_tokens":M}}` | Stream complete + usage |
| `error` | `{"type":"error","message":"<description>"}` | Recoverable or terminal error |
| `tool_call` | `{"type":"tool_call","toolName":"<name>","toolInput":{...}}` | MCP tool being invoked |

---

## Token Cost Tracking

Every completed message records:
- `input_tokens` — tokens in the prompt
- `output_tokens` — tokens in the response
- `provider` — which LLM was used
- `cost_usd` — calculated from configurable per-token pricing

Aggregates are surfaced in the Admin Dashboard and per-user usage stats.
