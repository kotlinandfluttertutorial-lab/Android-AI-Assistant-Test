# LLM Security

## API key security

### What the code guarantees

| Rule | How it is enforced |
|---|---|
| `GEMINI_API_KEY` never logged | `GeminiProvider` reads the key once in `__init__`, passes it to `genai.Client`. The variable is not stored as a named instance attribute. Logs use `"provider"` and `"model"` fields only. |
| Key never returned in API responses | `ChatMessageResponse` schema contains only `answer`, `provider`, `model`, `usage`. No internal fields are exposed. |
| Key never in Android app | The key exists only on the backend (`backend/.env` or Cloud Run Secret Manager). |
| Key never in Docker images | `backend/.env` is listed in `.gitignore` and `.gcloudignore`. `Dockerfile` does not `COPY .env`. |
| Key stripped of whitespace | `api_key = settings.GEMINI_API_KEY.strip()` prevents Cloud Run secrets with trailing newlines causing auth failures. |

### Local development

Store the key only in `backend/.env`:

```env
GEMINI_API_KEY=AIza...your_real_key
```

`backend/.env` is excluded by `.gitignore`. Run `git status` before every commit
to confirm it is untracked.

### Production (Cloud Run)

Store in **Secret Manager** and mount as an env var — see `docs/gemini-setup.md`.

---

## Prompt injection protection

Prompt injection is the most common LLM security risk. Multiple layers protect
against it:

### Layer 1 — InjectionDetector (before any LLM call)

`InjectionDetector.check_input()` runs static regex checks on every user
message before `PromptBuilder` or `LLMService` is invoked.

Patterns detected:

- `ignore (all) previous instructions`
- `disregard previous`
- `forget your instructions/training/rules`
- `system:` prefix in user message
- `you are now ...`
- `new system prompt`
- `override the (system|prompt|instructions)`
- `your (new) true/real/actual identity/self/persona/role`
- `pretend to be`
- `act as` (when not referring to a helpful AI)
- `[SYSTEM]` tag in user message
- `<system>` XML tag
- LLaMA instruction tokens (`<s>`, `</s>`, `[INST]`, etc.)

On detection: HTTP 400 is returned with `PROMPT_INJECTION_DETECTED`. The LLM
receives nothing.

### Layer 2 — Structural prompt design in PromptBuilder

`PromptBuilder` enforces a fixed section order that makes instruction override
structurally impossible:

```
## SYSTEM INSTRUCTIONS    ← always first; static; never influenced by user
## RETRIEVED CONTEXT      ← RAG documents wrapped in clear delimiters
## CONVERSATION HISTORY   ← prior conversation turns
## USER QUESTION          ← current user message; always last
```

RAG documents are explicitly marked as external data (`[Document N]`), not
instructions. The system instructions that appear before the document block
cannot be overridden by content inside it.

### Layer 3 — Safety filtering on output

`AIOrchestrator._apply_safety_filters()` strips known harmful patterns from
LLM output before it is delivered to the client. This catches cases where a
bypassed injection attempt causes harmful output.

---

## Input size limits

| Control | Default | Purpose |
|---|---|---|
| `LLM_PROMPT_MAX_CHARS` | 32 000 chars | Prevents token-exhaustion attacks via huge prompts |
| `MAX_REQUEST_BODY_SIZE` | 1 048 576 bytes (1 MiB) | Stops oversized HTTP bodies before schema validation |
| `LLM_MAX_OUTPUT_TOKENS_GEMINI` | 8 192 | Caps output length — limits cost from runaway generation |

---

## Rate limiting

Two layers, independent of each other:

### Application rate limit (`LLM_RATE_LIMIT_GEMINI`)

Redis sliding-window counter keyed `llm_rate:gemini:{user_id}:{window}`.
Fires **before** any API call is made. Default: 60 req/min per authenticated user.

```
Application limit ≠ Google API quota
```

The application limit is a protective cap that fires before the Google API is
called. It does not represent or enforce Google's official rate limits.

### HTTP rate limit (`RATE_LIMIT_REQUESTS_PER_MINUTE`)

General FastAPI request rate limit applied to all endpoints (authenticated
users: 60/min; unauthenticated IPs: 20/min). This is separate from the
LLM-specific limit.

---

## What is never logged

By default (`LLM_LOG_PROMPTS=false`):

- Full user prompt text
- Assembled system + RAG + history prompt
- RAG document contents
- Personal user data from conversation history
- `GEMINI_API_KEY` value

What **is** logged on every request:

```json
{
  "request_id": "abc123def456",
  "provider": "gemini",
  "model": "gemini-3.6-flash",
  "latency_ms": 1240.5,
  "status": "success",
  "input_tokens": 120,
  "output_tokens": 85,
  "total_tokens": 205,
  "fallback_used": false
}
```

### Enabling prompt logging for debugging

Only do this in a controlled, non-production environment:

```env
LLM_LOG_PROMPTS=true
```

This writes prompt text to stdout logs. In a containerised deployment, those
logs may be captured by Cloud Logging or a log aggregator. Before enabling:

- Confirm no PII is present in prompts.
- Confirm log storage meets your data-handling policy.
- Set a short log retention period.
- Disable it again before any user-facing deployment.

---

## Untrusted input sources

Every external data source is treated as untrusted:

| Source | Trust level | Safeguard |
|---|---|---|
| User message | Untrusted | `InjectionDetector` + structural position (last in prompt) |
| RAG documents | Untrusted | Clear `[Document N]` delimiters + positioned after system instructions |
| Conversation history | Untrusted | Injection-detected on original write; treated as data not instructions |
| LLM output | Untrusted | Safety filter before delivery |
| Web content / tool output | Untrusted | Not passed as instructions |

System instructions are **always** placed before untrusted content and are
never interpolated from user-supplied values.

---

## SonarQube targets

| Metric | Target |
|---|---|
| Bugs | 0 |
| Vulnerabilities | 0 |
| Security Hotspots | Reviewed |
| Code Smells (critical) | 0 |
| Duplications | < 3% |
| Coverage | ≥ 80% |

The implementation avoids:

- Hardcoded secrets (API keys read from env; never literal strings).
- Swallowed exceptions (all caught exceptions are re-raised as typed `LLMError` subclasses).
- Magic numbers (all thresholds are settings fields or named constants).
- Deep nesting (retry logic extracted into `_generate_with_retry` helper).
- Duplicated provider logic (one `GeminiProvider`; `GeminiClient` delegates to it via the new SDK).
