# AI Architecture
## Android AI Assistant — Enterprise Edition

---

## Overview

The AI layer is built around a **provider-agnostic AIOrchestrator** that sits between the FastAPI
HTTP/WebSocket layer and the concrete LLM provider clients. All features — chat, RAG, code
analysis, meeting summarisation, notes, resume generation, translation — route LLM calls through
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
# services/ai_orchestrator.py — provider-agnostic interface
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
| `ClaudeClient` | Anthropic Claude 3.5 Sonnet | Streams via Anthropic Messages API |
| `OllamaClient` | Ollama (self-hosted) | Routes to local endpoint; **no external network calls** |
| `LlamaClient` | Llama 3.x | Local or API-hosted |
| `MistralClient` | Mistral | Streams via Mistral API |

---

## Prompt Pipeline

```
1. System Prompt (from versioned template in prompts/)
     │
2. Injected Memories (top-3 from MemoryService → ChromaDB)
     │
3. Conversation History (up to token limit)
     │   → If > 80% used: summarise oldest turns, retain summary in place
     │
4. RAG Context (document query only — top-5 chunks + citations)
     │
5. User Message
     │
6. Tool Results (if MCP tool was invoked in this turn)
     ▼
   PromptContext → Provider Adapter → LLM
```

---

## Memory Service

The `MemoryService` stores user facts, preferences, and writing style observations as vector
embeddings in a user-scoped ChromaDB collection (`memories_{user_id}`).

**Injection flow:**
1. User message is embedded using `SentenceTransformer(all-MiniLM-L6-v2)`
2. ChromaDB cosine-similarity search retrieves top-3 memories from `memories_{user_id}`
3. Memories are prepended to the system prompt as structured facts
4. If retrieval fails or returns zero results, the orchestrator proceeds without injection

**Memory extraction:** After each completed message, new facts, preferences, and writing-style
signals are extracted from the message content and stored as new memory entries.

**Privacy mode:** When enabled, the session captures no new memories. Existing memories are
still injected (no deletion).

**User isolation:** Memories are stored under `memories_{user_id}` in ChromaDB. A query for
one user can never retrieve embeddings from another user's collection.

---

## Safety Filters

### Prompt Injection Detection

`SafetyService.detect_prompt_injection(text: str) -> bool` scans inputs for patterns including:
- Instruction override phrases: "ignore previous instructions", "disregard the above"
- Role-switching attempts: "you are now", "act as", "pretend to be"
- System prompt extraction: "repeat your instructions", "what are your rules"
- Jailbreak delimiters: `[INST]`, `<|system|>`, DAN, STAN, JAILBREAK payloads

**On detection:**
1. HTTP 400 returned with `error.code = "PROMPT_INJECTION_DETECTED"`
2. Input is **not forwarded** to any LLM provider
3. Audit log entry created with user ID, timestamp, and SHA-256 hash of sanitised input

### Input Sanitisation

`InputSanitizer` strips SQL injection patterns, XSS vectors, and Unicode homoglyphs from all
user-supplied strings before they reach the database layer.

---

## Fallback Logic

```
Request with provider = "openai"
  │
  ├─► Try OpenAIClient
  │       │
  │       ├─► Success → stream response
  │       └─► Error (timeout 10 s / rate limit / 5xx)
  │               │
  │               └─► Try fallback_provider (from config)
  │                       │
  │                       ├─► Success → stream + notify user of substitution in-app
  │                       └─► Error → return structured error (no substitution notification)
  └─► (no fallback configured) → return structured error
```

---

## Context Window Management

When conversation history reaches 80% of `max_context_tokens`:
1. Oldest message turns (excluding system prompt) are extracted
2. A `complete()` call to the same provider with a summarisation prompt produces a summary
3. The summary replaces the raw turns in PromptContext
4. Raw messages remain in PostgreSQL for audit and export

---

## WebSocket Event Schema

| Event | JSON Schema | Meaning |
|-------|-------------|---------|
| `token` | `{"type":"token","data":"<text>"}` | Next token from LLM |
| `done` | `{"type":"done","usage":{"input_tokens":N,"output_tokens":M}}` | Stream complete |
| `error` | `{"type":"error","message":"<description>"}` | Error during streaming |
| `tool_call` | `{"type":"tool_call","toolName":"<name>","toolInput":{...}}` | MCP tool invoked |

**WebSocket close codes:**
- `4001` — Authentication failure (JWT absent, expired, malformed, or revoked)
- `1001` — Heartbeat timeout (no pong within 10 s of ping)

**Heartbeat:** Backend sends a ping every 30 s. No pong within 10 s → close code 1001.

**Buffer on disconnect:** Backend buffers up to 1,000 tokens for 60 s. Reconnecting client
receives buffered tokens.

---

## Token Cost Tracking

Every completed message records in `token_usage` table:
- `input_tokens` — tokens in the prompt
- `output_tokens` — tokens in the response
- `provider` — which LLM adapter was used
- `cost_usd` — `input_tokens × cost_per_input + output_tokens × cost_per_output`

Per-token pricing is configurable per provider. Aggregated cost is surfaced in the Admin
Dashboard broken down by provider and calendar day, refreshed within 60 seconds.

---

## Embedding Model

All chunk embeddings and memory embeddings are generated by `SentenceTransformer(all-MiniLM-L6-v2)`:
- Dimension: 384
- Max sequence length: 256 tokens
- Storage: ChromaDB collections `documents_{user_id}` and `memories_{user_id}`
- Zero cross-user data leakage at the storage layer (user-scoped collections)
