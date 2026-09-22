# LLM Cost Optimisation

## How Gemini pricing works

Gemini charges per token (input + output separately).
Flash models cost significantly less than Pro models.

Approximate pricing for Gemini 3.x Flash models (check
[ai.google.dev/gemini-api/docs/pricing](https://ai.google.dev/gemini-api/docs/pricing)
for current rates — prices change):

| Model | Input (per 1M tokens) | Output (per 1M tokens) |
|---|---|---|
| gemini-3.8-flash | ~$0.15 | ~$0.60 |
| gemini-3.6-flash | ~$0.15 | ~$0.60 |
| gemini-3.5-flash | ~$0.15 | ~$0.60 |
| gemini-3.1-flash-lite | ~$0.08 | ~$0.30 |

> These are illustrative estimates. Always verify current pricing before budgeting.

---

## Implemented cost controls

### 1. Prompt length control — `LLM_PROMPT_MAX_CHARS`

Every prompt is capped at `LLM_PROMPT_MAX_CHARS` characters (default: 32 000,
≈8 000 tokens at 4 chars/token) before the API is called.

`PromptBuilder` truncates in this priority order:
1. Drop oldest conversation history entries.
2. Shorten individual RAG document chunks.
3. Hard-truncate as a last resort.

The user question and system instructions are **always** preserved.

**Configuration:**

```env
LLM_PROMPT_MAX_CHARS=32000   # reduce to save cost; increase for complex RAG
```

### 2. Output token cap — `LLM_MAX_OUTPUT_TOKENS_GEMINI`

Limits how many tokens the model can generate per response:

```env
LLM_MAX_OUTPUT_TOKENS_GEMINI=8192   # per-response output cap for Gemini
LLM_MAX_OUTPUT_TOKENS=2048          # global default (lower priority)
```

Smaller output caps reduce cost on chatty models. Set based on your use case —
a customer-service bot rarely needs 8K output tokens.

### 3. Application-level rate limit — `LLM_RATE_LIMIT_GEMINI`

Prevents runaway usage by capping requests per user per minute **before** the
API is called:

```env
LLM_RATE_LIMIT_GEMINI=60   # max Gemini requests/min per authenticated user
```

> **Important:** This is the **application limit**, not Google's API quota.
> It protects against accidental loops and abuse of your API key. It does not
> replace quota management in Google AI Studio.

### 4. Retry control — avoid unnecessary retries

Permanent errors (invalid key, 4xx) are never retried:

```env
LLM_MAX_RETRY_ATTEMPTS=3        # max retries on transient errors
LLM_RETRY_BASE_DELAY_SECONDS=1.0  # base backoff delay
```

Each retry doubles the delay: 1s → 2s → 4s. With 3 max attempts, worst-case
wait before giving up is 1 + 2 + 4 = 7 seconds. Avoid setting
`LLM_MAX_RETRY_ATTEMPTS` higher than 5 unless you have a specific reason.

### 5. Model-level fallback — `GEMINI_FALLBACK_MODEL`

When the primary model returns a quota error (HTTP 429), the fallback model is
tried. The fallback model is cheaper by default:

```env
GEMINI_MODEL=gemini-3.6-flash         # primary
GEMINI_FALLBACK_MODEL=gemini-3.1-flash-lite  # fallback — ~50% cheaper
```

This avoids failing the request when quota is temporarily exhausted, and the
fallback model costs less per token.

### 6. Routing cheaper requests to a lighter model

For high-volume, low-complexity use cases (e.g. autocomplete, short answers),
set the primary model to a cheaper Flash Lite variant:

```env
GEMINI_MODEL=gemini-3.5-flash-lite    # ultra-low cost
GEMINI_FALLBACK_MODEL=gemini-3.1-flash-lite
```

For workflows that need higher accuracy only occasionally, use complexity-based
routing in `PromptBuilder`:

- `"simple"` → routes to the default (cheaper) model.
- `"complex"` → same model currently; future hook to route to
  `gemini-3.8-flash` for architecture/audit/migration tasks.

The complexity classification uses deterministic regex patterns — no extra
LLM call is made.

### 7. On-device Gemma for private/offline requests

For privacy-sensitive requests or when minimising cloud cost matters:

```env
DEFAULT_LLM_PROVIDER=gemma  # routes everything to local Ollama
```

Or send specific requests with `complexity="local"` in the API body.
Cost: $0.00 per token (self-hosted).

**Requirements:** Ollama running locally with `gemma3:latest` installed:

```bash
ollama pull gemma3:latest
```

---

## Cost monitoring

Token usage is logged on every request:

```json
{
  "request_id": "abc123",
  "provider": "gemini",
  "model": "gemini-3.6-flash",
  "input_tokens": 120,
  "output_tokens": 85,
  "total_tokens": 205,
  "latency_ms": 1240.5
}
```

The existing `TokenUsage` table in PostgreSQL records input/output tokens and
computed cost per request, queryable via `GET /usage`.

To track cost over time, use the Prometheus counter
`llm_token_cost_total{provider="gemini"}` exported by `app/workers/metrics.py`.

---

## Recommended settings per environment

| Env | `GEMINI_MODEL` | `GEMINI_FALLBACK_MODEL` | `LLM_MAX_OUTPUT_TOKENS_GEMINI` | `LLM_RATE_LIMIT_GEMINI` |
|---|---|---|---|---|
| Local dev | `gemini-3.6-flash` | `gemini-3.1-flash-lite` | 2048 | 10 |
| Staging | `gemini-3.6-flash` | `gemini-3.1-flash-lite` | 4096 | 30 |
| Production | `gemini-3.6-flash` | `gemini-3.1-flash-lite` | 8192 | 60 |
| High-accuracy | `gemini-3.8-flash` | `gemini-3.6-flash` | 8192 | 20 |

Use a lower `LLM_RATE_LIMIT_GEMINI` in development to catch unexpected loops early.
