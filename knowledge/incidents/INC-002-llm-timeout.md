# INC-002 — LLM Provider Timeout Cascade

**Date:** 2026-07-28
**Duration:** 22 minutes (09:14 – 09:36 UTC)
**Severity:** MEDIUM
**Status:** RESOLVED
**Services affected:** ai-assistant-backend — chat and RAG query endpoints
**Reported by:** User reports + Prometheus alert `HighP95Latency`

---

## Timeline

| Time (UTC) | Event |
|------------|-------|
| 09:14:00 | P95 latency crosses 2s; `HighP95Latency` alert fires |
| 09:14:30 | Users report "AI responses taking forever" |
| 09:17:00 | Engineer checks logs; sees OpenAI 429 rate limit errors |
| 09:19:00 | LLM fallback to Gemini confirmed not working (misconfigured fallback) |
| 09:23:00 | Fallback config fixed; Gemini starts handling requests |
| 09:29:00 | OpenAI rate limit lifted; primary provider restored |
| 09:36:00 | P95 returns to baseline; incident closed |

---

## Symptoms

- P95 latency: 18.4s (baseline: 145ms) on `POST /api/v1/chat`
- HTTP 200 responses but with empty or truncated answers
- Log pattern:
  ```
  openai.RateLimitError: You exceeded your current quota
  LLM fallback to gemini failed: provider not configured
  AI Orchestrator: all providers exhausted, returning empty response
  ```
- `llm_token_cost_usd_total{provider="openai"}` flat (no new tokens billed)
- `llm_token_cost_usd_total{provider="gemini"}` also flat (fallback not working)

---

## Root Cause

OpenAI rate limit was hit due to a batch document re-indexing job running
concurrently with production traffic. The re-indexing job made ~500 LLM calls
in 10 minutes, exhausting the per-minute token quota.

The `LLM_FALLBACK_PROVIDER=gemini` env var was set correctly in Cloud Run, but
`GEMINI_API_KEY` secret version had expired (Secret Manager showed `DISABLED`
for version 5 and no `:latest` version pointing to an active version).

As a result: OpenAI hit rate limit → fallback to Gemini → Gemini key invalid →
`AIOrchestrator` returned empty string → users saw blank responses.

---

## Evidence

1. `LLMCostSpike` alert did NOT fire (cost was zero — requests were failing, not succeeding)
2. OpenAI dashboard: 429 errors starting at 09:12 UTC
3. Secret Manager: `GEMINI_API_KEY` version 5 was disabled; version 6 existed but
   Cloud Run was pinned to `GEMINI_API_KEY:5`
4. `llm_output_tokens_total` counter flat during the incident window

---

## Fix Applied

1. **Immediate:** Updated Cloud Run to use `GEMINI_API_KEY:latest` instead of pinning `:5`
2. **Immediate:** Re-enabled version 6 of GEMINI_API_KEY in Secret Manager
3. **Follow-up:** Batch re-indexing job now rate-limited to 10 LLM calls/minute
4. **Follow-up:** All secret references in `deploy-cloud-run.ps1` changed from
   pinned version numbers to `:latest` for MINIO, LLM, and GEMINI keys
5. **Follow-up:** Added `LLMCostSpike` alert threshold at $0.05/min (not just $0.10)
   to catch unusual LLM activity earlier

---

## Lessons Learned

- Pinning secret versions by number is risky — when keys rotate, services break silently
- Batch jobs that call LLMs must be rate-limited and run during off-peak hours
- The fallback chain must be validated periodically; a silent fallback failure is
  worse than a loud primary failure
- `llm_token_cost_usd_total` going flat is itself an alert condition (stopped billing = stopped working)

---

## Prevention

- Secret references now use `:latest` everywhere
- CI pipeline now validates that all LLM providers are reachable before deploying
- Added alert: `LLMProviderSilent` — fires when token counters are flat for > 5 min
  during business hours (indicates all providers are failing)
- Batch jobs scheduled via Cloud Scheduler with `--max-concurrency=1`

---

## Related

- Runbook: `secrets-management.md` (rotate or update a secret version)
- Runbook: `service-restart.md` (force Cloud Run to pick up new secret version)
