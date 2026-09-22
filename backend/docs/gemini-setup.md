# Gemini Setup Guide

## SDK

The backend uses the **Google Gen AI SDK** (`google-genai`).

```
google-generativeai  ← EOL November 30 2025. DO NOT use.
google-genai         ← current SDK (v1.68.0+). Required.
```

Import pattern:

```python
from google import genai
from google.genai import types, errors

client = genai.Client(api_key=settings.GEMINI_API_KEY)
```

---

## Getting an API key

1. Go to [Google AI Studio](https://aistudio.google.com/app/apikey).
2. Click **Create API key**.
3. Copy the key — it will not be shown again.
4. Store it as described below. **Never hardcode it in source code.**

---

## Available stable models (September 2026)

| Model ID | Released | Notes |
|---|---|---|
| `gemini-3.8-flash` | Sep 2 2026 | Most intelligent Flash; complex coding/agents |
| `gemini-3.7-flash` | Aug 13 2026 | Coding/agents focus |
| `gemini-3.6-flash` | Jul 21 2026 | **Default** — improved token efficiency |
| `gemini-3.5-flash` | May 19 2026 | Best price/performance; `gemini-flash-latest` alias |
| `gemini-3.5-flash-lite` | Jul 21 2026 | Ultra-low-latency, high-volume |
| `gemini-3.1-flash-lite` | May 7 2026 | **Default fallback** — speed/cost/efficiency |

> **Warning:** `gemini-2.0-flash` was shut down June 1 2026.
> `gemini-1.5-flash` and `gemini-1.5-pro` were shut down September 29 2025.
> Using those model IDs will cause a `LLMConfigurationError` at runtime.

---

## Local development

Copy `.env.example` to `.env` and fill in your key:

```bash
cp backend/.env.example backend/.env
```

Set the required fields:

```env
GEMINI_API_KEY=AIza...your_real_key_here
GEMINI_MODEL=gemini-3.6-flash
GEMINI_FALLBACK_MODEL=gemini-3.1-flash-lite
LLM_ENABLE_FALLBACK=true
```

Start the stack:

```bash
docker compose up
```

The backend validates `GEMINI_API_KEY` at init time (`GeminiProvider.__init__`).
A blank key raises `LLMConfigurationError` before any API call is made.

---

## Cloud Run (production)

### 1. Create the secret

```bash
echo -n "AIza...your_real_key_here" | \
  gcloud secrets create GEMINI_API_KEY \
    --data-file=- \
    --project=your-gcp-project
```

### 2. Grant Cloud Run access

```bash
gcloud secrets add-iam-policy-binding GEMINI_API_KEY \
  --member="serviceAccount:YOUR_SERVICE_ACCOUNT@your-gcp-project.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor" \
  --project=your-gcp-project
```

### 3. Mount as environment variable in Cloud Run

In your Cloud Run service configuration (YAML or Console):

```yaml
env:
  - name: GEMINI_API_KEY
    valueFrom:
      secretKeyRef:
        name: GEMINI_API_KEY
        key: latest
  - name: GEMINI_MODEL
    value: "gemini-3.6-flash"
  - name: GEMINI_FALLBACK_MODEL
    value: "gemini-3.1-flash-lite"
  - name: LLM_ENABLE_FALLBACK
    value: "true"
```

Or via `gcloud`:

```bash
gcloud run services update your-service \
  --set-secrets="GEMINI_API_KEY=GEMINI_API_KEY:latest" \
  --set-env-vars="GEMINI_MODEL=gemini-3.6-flash,GEMINI_FALLBACK_MODEL=gemini-3.1-flash-lite" \
  --region=your-region \
  --project=your-gcp-project
```

### 4. Security checklist

- [ ] `GEMINI_API_KEY` stored only in Secret Manager — not in Dockerfile, not in code.
- [ ] API key NOT present in Android app, build artifacts, or logs.
- [ ] `LLM_LOG_PROMPTS=false` (default) — confirms prompts are not logged.
- [ ] `GEMINI_API_KEY` excluded from `backend/.env` when committing (`.gitignore`).

---

## Model switching

Switch the primary model without any code changes:

```bash
# Local
GEMINI_MODEL=gemini-3.8-flash  # add to backend/.env

# Cloud Run
gcloud run services update your-service \
  --set-env-vars="GEMINI_MODEL=gemini-3.8-flash" \
  --region=your-region
```

The setting is read by `GeminiProvider.__init__` on every cold start.
A rolling deployment is not required for a model switch — update the env var
and the next pod startup picks it up automatically.

---

## Verifying the connection

After setting `GEMINI_API_KEY`, run the integration test to confirm the key
works and the model is reachable:

```bash
export GEMINI_API_KEY=AIza...your_real_key
export RUN_LLM_INTEGRATION_TESTS=true

cd backend
pytest tests/integration/test_llm_integration.py -v
```

Expected output: all four tests pass with non-empty text responses.
