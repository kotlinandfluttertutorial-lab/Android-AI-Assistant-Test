# LLM Architecture

## Overview

The Android AI Assistant uses a **provider-based LLM architecture** that decouples
the application from any single model or vendor. Gemini is the default cloud
provider; on-device Gemma is available for privacy-sensitive or offline requests.

```
Android App
     │
     ▼
FastAPI  ─── POST /api/v1/chat
             POST /chat/message
     │
     ▼
InjectionDetector      ← blocks prompt injection before any LLM call
     │
     ▼
PromptBuilder          ← assembles system + RAG context + history + user question
     │
     ▼
LLMService             ← routes request, handles provider-level fallback
     │
  ┌──┴──┐
  ▼     ▼
GeminiProvider   LocalGemmaProvider
  │
  └─ primary model  ──┐
                       ▼
                  Gemini API (google-genai SDK)
                       │
                (quota error + LLM_ENABLE_FALLBACK=true)
                       ▼
                  fallback model
```

---

## Layer responsibilities

### `app/llm/base.py`

Defines the contracts every provider must honour:

| Type | Purpose |
|---|---|
| `LLMProvider` | Abstract base class — `generate()` and `stream()` |
| `LLMRequest` | Carries prompt, system_prompt, user_id, RAG context, complexity hint |
| `LLMResponse` | Carries text, provider, model, usage, fallback_used, latency_ms |
| `LLMUsage` | Token counts — input_tokens, output_tokens, total_tokens |

### `app/llm/exceptions.py`

Typed exception hierarchy used for precise retry/fallback decisions:

```
LLMError
├── LLMConfigurationError   — permanent (bad key, unknown model) — NEVER retry
├── LLMRateLimitError       — application-level cap exceeded — back off
├── LLMQuotaError           — Google API 429 — switch to fallback model
├── LLMTimeoutError         — request timed out — retry with backoff
├── LLMProviderError        — other errors; .is_transient controls retry
└── LLMPromptTooLargeError  — prompt too large — raised before any API call
```

### `app/llm/providers/gemini_provider.py`

`GeminiProvider` — the primary cloud provider.

- Uses `google-genai` SDK (`from google import genai`).
- Reads `GEMINI_API_KEY` once at init; key never stored in a named attribute.
- Enforces the **application-level** Redis rate limit (`LLM_RATE_LIMIT_GEMINI`)
  before calling the API.
- Retries transient errors with exponential backoff up to `LLM_MAX_RETRY_ATTEMPTS`.
- Falls back to `GEMINI_FALLBACK_MODEL` on HTTP 429 quota errors when
  `LLM_ENABLE_FALLBACK=true`.
- Extracts token usage from `response.usage_metadata`.

### `app/llm/providers/local_gemma_provider.py`

`LocalGemmaProvider` — on-device Gemma via a local Ollama server.

- Zero API cost. No external network calls — all traffic goes to `OLLAMA_BASE_URL`.
- Activated when `request.complexity="local"` or `DEFAULT_LLM_PROVIDER=gemma`.
- Delegates to the existing `OllamaClient` from `app.services.llm_clients`.

### `app/llm/service.py`

`LLMService` — the single entry point for all LLM calls in the application.

**Request routing (deterministic, no extra LLM call):**

| `LLMRequest.complexity` | `DEFAULT_LLM_PROVIDER` | Routes to |
|---|---|---|
| `"local"` | any | `LocalGemmaProvider` |
| any | `"gemma"` | `LocalGemmaProvider` |
| `"simple"` / `"complex"` | `"gemini"` (default) | `GeminiProvider` |

**Provider-level fallback:**

If the primary provider raises `LLMError` (except `LLMConfigurationError`),
and `LLM_FALLBACK_PROVIDER` is set, `LLMService` retries with the fallback
provider. This is separate from the model-level fallback inside `GeminiProvider`.

### `app/llm/prompt_builder.py`

`PromptBuilder` — assembles a safe, size-limited prompt from structured inputs.

**Section order (always):**

```
## SYSTEM INSTRUCTIONS        ← static; never influenced by user input
## RETRIEVED CONTEXT          ← RAG docs wrapped in delimiters (untrusted)
## CONVERSATION HISTORY       ← previous turns (oldest → newest)
## USER QUESTION              ← current user message
```

**Size control:**

When the total prompt exceeds `LLM_PROMPT_MAX_CHARS`, truncation applies in
this order:

1. Oldest history entries dropped first.
2. Individual RAG chunks shortened (up to 50% of `_MAX_CHARS_PER_CHUNK`).
3. Hard truncation as last resort (very rare).

**Complexity classification** (deterministic regex, no extra LLM call):

Matches patterns like `\barchitect\b`, `\bsecurity\b.*\baudit\b`,
`\brefactor\b`, `\bmigrat\b` etc. Returns `"complex"` or `"simple"`.

### `app/api/chat/router.py`

Two endpoints sharing the same handler:

| Endpoint | Description |
|---|---|
| `POST /chat/message` | Legacy REST endpoint — backward-compatible |
| `POST /api/v1/chat` | Versioned endpoint (spec-compliant) |

**Request pipeline per call:**

```
JWT auth
  → InjectionDetector.check_input()     [400 on detection]
  → PromptBuilder.build()               [413 if prompt too large]
  → LLMService.generate()               [429/503 on LLM errors]
  → ChatMessageResponse                 [answer, provider, model, usage]
```

---

## Relationship to existing `AIOrchestrator`

`LLMService` / `GeminiProvider` are the new REST path introduced in this
feature. The existing `AIOrchestrator` continues to power:

- WebSocket streaming (`/ws/chat/{conversation_id}`)
- RAG document queries (`/documents/query`)
- Conversation summarisation
- Memory injection
- Persona injection

`GeminiClient` in `app/services/llm_clients.py` is the concrete adapter used
by `AIOrchestrator`. It has been migrated from the EOL `google-generativeai`
SDK to the current `google-genai` SDK in this feature.

---

## Data flow — POST /api/v1/chat

```
Client
  │  POST /api/v1/chat
  │  {"message": "What is Clean Architecture?"}
  ▼
FastAPI JWT auth
  ▼
InjectionDetector
  │  check_input(text, user_id, db)
  │  → PromptInjectionError → HTTP 400
  ▼
PromptBuilder.build()
  │  system_prompt + [] history + user_message
  │  → LLMRequest(prompt=..., complexity="simple")
  ▼
LLMService.generate(request)
  │  _resolve_provider("gemini")
  │  → GeminiProvider
  ▼
GeminiProvider
  │  _rate_limiter.check(user_id)        [LLMRateLimitError → HTTP 429]
  │  _generate_with_retry(primary_model)
  │    → genai.Client.aio.models.generate_content(...)
  │    → on 429: fallback to GEMINI_FALLBACK_MODEL
  │  → LLMResponse(text, provider, model, usage, fallback_used)
  ▼
ChatMessageResponse
  │  {answer, provider, model, usage:{input,output,total_tokens}}
  ▼
Client
```
