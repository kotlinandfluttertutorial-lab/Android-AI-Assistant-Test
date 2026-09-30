# Phase 10 — AI Error Analysis

## What You Will Learn

How to build a production AI system that automatically analyses application errors
by combining structured observability data, retrieved knowledge (RAG), and an LLM
into a trustworthy root cause analysis with confidence scores and explicit
safety gates.

---

## 1. Concept

### The Problem with Manual Error Triage

When an error fires at 2 AM, an on-call engineer has to:

1. Find the relevant log lines in a wall of output
2. Recall whether this error pattern has been seen before
3. Locate the right runbook
4. Form a hypothesis about root cause
5. Decide whether to escalate

This typically takes 20–40 minutes. During that time, user-facing impact continues.

### What AI Error Analysis Does

AI Error Analysis automates steps 1–4 as a **suggestion** to the engineer. It:

- Collects all relevant observability events from PostgreSQL
- Searches the knowledge base for matching runbooks and past incidents (RAG)
- Sends the evidence plus knowledge to an LLM for structured reasoning
- Returns a structured response: summary, root cause, confidence, facts, inferences,
  recommended fix

The engineer still makes every decision. The AI reduces time-to-context from minutes
to seconds.

---

## 2. Why Production Systems Use This Approach

| Alternative | Why it falls short |
|---|---|
| Manual log search | Slow, error-prone, doesn't leverage historical knowledge |
| Static alert rules | Binary: fires or not. No explanation. No fix suggestion. |
| Raw LLM on freeform input | Hallucinates facts. No confidence score. No grounding. |
| **RAG + LLM on structured events** | Grounded in real data, knowledge-augmented, calibrated confidence |

RAG (Retrieval-Augmented Generation) is the critical addition. Without it, the LLM
can only reason from the current error text. With it, the LLM also has:

- Runbooks written by your team for this exact error pattern
- Historical incident reports showing how similar errors were resolved before
- Architecture docs explaining normal system behaviour

This dramatically improves answer quality and reduces hallucination.

---

## 3. Architecture

```
Android App
    │
    │ POST /api/v1/observability/events  (every 15 min via WorkManager)
    ▼
Backend — PostgreSQL
    observability_events table
    │
    │ ErrorAnalysisService.analyse()
    ▼
┌──────────────────────────────────────────────────────────────────┐
│                   8-Step Analysis Pipeline                       │
│                                                                  │
│  1. Collect evidence  ── ObservabilityEventRepository            │
│          │                 • get_recent_errors(minutes=30)       │
│          │                 • get_by_session(session_id)          │
│          │                 • get_by_id(event_id) + session ctx   │
│          ▼                                                       │
│  2. Derive RAG query  ── event types + message prefixes + screen │
│          │                                                       │
│          ▼                                                       │
│  3. Retrieve runbooks ── rag_service.query_knowledge_base()      │
│  4. Retrieve incidents── rag_service.query_knowledge_base()      │
│          │                                                       │
│          ▼                                                       │
│  5. Build LLM prompt  ── evidence block + runbooks + incidents   │
│          │               + 6 AI safety rules embedded            │
│          ▼                                                       │
│  6. Call LLM          ── AIOrchestrator.complete()               │
│          │               (Gemini / OpenAI / Claude)              │
│          ▼                                                       │
│  7. Parse response    ── JSON extraction + fence stripping       │
│          ▼                                                       │
│  8. Apply safety gate ── confidence < 0.6 → override root cause  │
└──────────────────────────────────────────────────────────────────┘
    │
    ▼
ErrorAnalysisResponse
    • analysis_id       (UUID)
    • severity          (CRITICAL / HIGH / MEDIUM / LOW)
    • summary           (one-line)
    • evidence          (list of observed log lines)
    • possible_causes   (ranked list)
    • likely_root_cause (overridden to "Evidence insufficient" if confidence < 0.6)
    • confidence        (0.0–1.0)
    • recommended_fix   (SUGGESTION — no automated action)
    • related_documentation
    • facts_vs_inference (separated explicitly)
    • low_confidence_warning (null when confidence ≥ 0.6)
    • events_analysed
    • knowledge_chunks_retrieved
    • llm_provider
```

---

## 4. Implementation

### 4.1 The Data Flow — Android to Backend

The Android `ObservabilityUploadWorker` (WorkManager, runs every 15 minutes)
batches `ObservabilityEvent` objects and calls:

```
POST /api/v1/observability/events
Content-Type: application/json

{
  "events": [
    {
      "timestamp": 1700000000000,
      "level": "ERROR",
      "eventType": "network_error",
      "message": "POST /api/chat returned HTTP 500",
      "sessionId": "sess-uuid-here",
      "requestId": "req-uuid",
      "screen": "ChatScreen",
      "metadata": {"http_status": "500", "endpoint": "/api/chat"}
    }
  ]
}
```

The backend stores these in the `observability_events` PostgreSQL table. They become
the evidence source for every analysis run.

### 4.2 Triggering Analysis

Three modes, all via `POST /api/v1/analysis/errors`:

```json
// Mode 1: analyse one specific event + its session context
{"event_id": "uuid-of-the-event"}

// Mode 2: analyse all events for a session
{"session_id": "sess-uuid", "lookback_minutes": 60}

// Mode 3: analyse all recent errors (default)
{"lookback_minutes": 30}
```

Or use the session shortcut:
```
POST /api/v1/analysis/errors/session?session_id=sess-uuid&lookback_minutes=60
```

Or analyse by event ID directly:
```
GET /api/v1/analysis/errors/{event_id}
```

### 4.3 The LLM Prompt Structure

The prompt has four sections:

```
[System persona]
You are an expert SRE and AI-powered DevOps assistant.
Analyse the following application error events...

[6 AI Safety Rules — embedded in every prompt]
1. Only use information present in evidence — never invent facts.
2. Separate facts from inferences.
3. Confidence 0.0–1.0. Be honest about uncertainty.
4. If confidence < 0.6: set likely_root_cause = "Evidence insufficient".
5. recommended_fix is a SUGGESTION only.
6. Never expose credentials, tokens, or PII.

[Evidence block — real events from PostgreSQL]
=== ERROR / CRITICAL Events ===
[2025-06-01 14:32:01] ERROR network_error [ChatScreen]: POST /api/chat returned HTTP 500
[2025-06-01 14:32:03] ERROR http_timeout [ChatScreen]: Request timed out after 30s
...

[Knowledge base context — from RAG]
=== Relevant Runbooks ===
--- [1] Source: runbooks/api-service-restart.md ---
If the API service returns persistent 500s, first check connection pool...

=== Historical Incidents ===
--- [1] Source: incidents/INC-88.md ---
Previous 500 error spike was caused by a slow DB query after deployment...

[Output format — strict JSON schema]
Respond with ONLY valid JSON matching this exact schema (no markdown):
{
  "severity": "CRITICAL|HIGH|MEDIUM|LOW",
  "summary": "one-line description",
  ...
}
```

### 4.4 The AI Safety Gate

```python
_LOW_CONFIDENCE_THRESHOLD = 0.6

if confidence < _LOW_CONFIDENCE_THRESHOLD:
    likely_root_cause = (
        "Evidence is insufficient — manual investigation required."
    )
    low_confidence_warning = (
        f"Confidence score {confidence:.2f} is below the 0.6 threshold. "
        "Please investigate manually using the evidence and documentation listed."
    )
```

This is a hard override — the LLM cannot bypass it. When evidence is weak,
the engineer is told explicitly to investigate manually rather than trusting
a guess.

### 4.5 Facts vs Inference Separation

```python
class FactsVsInference(BaseModel):
    facts: list[str]       # Directly observable in the evidence
    inferences: list[str]  # LLM's reasoning about what the facts mean
```

Example output:
```json
{
  "facts": [
    "Pool at capacity 20/20 connections at 14:32:01",
    "Latency spike from 80ms to 340ms at 14:31:45"
  ],
  "inferences": [
    "The latency spike likely preceded the pool exhaustion",
    "A slow query in the recent deployment may be holding connections"
  ]
}
```

The UI renders these in separate sections so the engineer never confuses
"what the data shows" with "what the AI thinks it means".

### 4.6 Graceful Degradation

The analysis pipeline never throws. Every failure mode returns a safe
`ErrorAnalysisResponse`:

| Failure | Response |
|---|---|
| No events in DB | `_no_data_response()` — LOW severity, confidence 0.0, explains how to wait for upload |
| LLM timeout / API error | `_build_response({}, ...)` fallback — "AI analysis unavailable", raw events in evidence |
| LLM returns malformed JSON | Same fallback |
| confidence < 0.6 | Valid response, `likely_root_cause` overridden, `low_confidence_warning` set |

---

## 5. How to Verify It Works

### 5.1 Send a Test Event

```bash
curl -X POST http://localhost:8000/api/v1/observability/events \
  -H "Content-Type: application/json" \
  -d '{
    "events": [{
      "timestamp": '"$(date +%s000)"',
      "level": "ERROR",
      "eventType": "db_connection_refused",
      "message": "Connection refused: pool exhausted (20/20)",
      "sessionId": "test-session-001",
      "screen": "HomeScreen",
      "metadata": {"pool_size": "20", "wait_time_ms": "5000"}
    }]
  }'
# Expected: {"accepted": 1, "total": 1}
```

### 5.2 Trigger Analysis

```bash
# Get a JWT first
TOKEN=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"dev@example.com","password":"your-password"}' \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

curl -X POST http://localhost:8000/api/v1/analysis/errors \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"session_id": "test-session-001", "lookback_minutes": 60}'
```

### 5.3 Expected Response Shape

```json
{
  "analysis_id": "uuid-here",
  "severity": "HIGH",
  "summary": "Database connection pool exhausted causing API failures",
  "evidence": [
    "Connection refused: pool exhausted (20/20) at 14:32:01"
  ],
  "possible_causes": [
    "Connection pool too small for current traffic",
    "Slow query holding connections open"
  ],
  "likely_root_cause": "Pool at capacity — all 20 connections in use",
  "confidence": 0.82,
  "recommended_fix": "1. Increase pool_size from 20 to 40. 2. Add query timeout of 10s.",
  "related_documentation": ["runbooks/db-connection.md"],
  "facts_vs_inference": {
    "facts": ["Pool at capacity 20/20 connections"],
    "inferences": ["High traffic or slow query may be causing pool exhaustion"]
  },
  "low_confidence_warning": null,
  "events_analysed": 1,
  "knowledge_chunks_retrieved": 0,
  "llm_provider": "gemini"
}
```

---

## 6. Debugging Common Failures

### "AI analysis unavailable" in the summary

**Cause:** LLM API call failed (timeout, API key invalid, quota exceeded)  
**Check:**
```bash
# Verify your Gemini API key is set
grep GEMINI_API_KEY backend/.env

# Check backend logs for the LLM error
docker compose logs backend | grep "LLM call failed"
```

### `low_confidence_warning` is always populated

**Cause:** LLM is producing low-confidence responses, usually because:
- No runbooks or incidents are in the knowledge base (RAG returns empty)
- The error events are too vague for the LLM to reason about
- LLM is returning `confidence: 0.3` even for clear errors

**Fix for missing knowledge base:**
```bash
# Seed the knowledge base
docker compose exec backend python scripts/seed_knowledge.py

# Verify documents loaded
curl -X GET http://localhost:8000/api/v1/rag/documents \
  -H "Authorization: Bearer $TOKEN"
```

### `knowledge_chunks_retrieved` is always 0

**Cause:** ChromaDB / pgvector knowledge base is empty.  
**Fix:** Run `seed_knowledge.py` — see Phase 9 guide for full instructions.

### Events not appearing in analysis

**Cause:** Events uploaded to the DB more than `lookback_minutes` ago.  
**Fix:** Increase `lookback_minutes` in the request:
```json
{"lookback_minutes": 1440}
```

Or check the DB directly:
```sql
SELECT COUNT(*), level, event_type
FROM observability_events
WHERE received_at > NOW() - INTERVAL '1 hour'
GROUP BY level, event_type
ORDER BY COUNT(*) DESC;
```

### Analysis runs but `events_analysed: 0`

**Cause (event_id mode):** The UUID in the request does not exist in the DB.  
**Cause (session_id mode):** The session ID has no events in the lookback window.  
**Fix:** Use Mode 3 (no filter) to confirm events exist at all:
```json
{"lookback_minutes": 60}
```

---

## 7. Interview Questions

**Q1: Why does the error analysis pipeline separate facts from inferences?**

A: LLMs are excellent at pattern recognition but can present inferences as facts,
which misleads engineers. By requiring the model to explicitly separate what it
directly observed in the log data (facts) from what it is reasoning about
(inferences), the system prevents the AI from presenting guesses as confirmed
observations. Engineers can then evaluate the reasoning quality independently.
This is an AI Safety requirement — not just a UX choice.

**Q2: Why is the confidence gate a hard override at 0.6, not a soft warning?**

A: A soft warning still shows a low-confidence root cause in the primary display,
and engineers under pressure tend to act on whatever answer they see. A hard
override that replaces the root cause with "Evidence is insufficient — manual
investigation required" ensures the engineer cannot be misled into trusting a
guess. The 0.6 threshold balances false negatives (missing real root causes)
against false confidence (acting on unreliable inferences).

**Q3: What is the difference between structured logging and plain text logging?**

A: Plain text logs (e.g. `"ERROR: Connection refused"`) are human-readable but
machine-unparseable. Structured logs emit JSON (`{"level":"ERROR","event_type":
"db_connection_refused","message":"Connection refused","pool_size":20}`). Machine
parseability enables the error analysis pipeline to extract `pool_size` as a fact
rather than substring-matching it from a string. It also enables reliable filtering
(`WHERE level = 'ERROR'`) and aggregation in PostgreSQL.

**Q4: How does the pipeline prevent the LLM from hallucinating runbook content?**

A: Two mechanisms: (1) The LLM is instructed with Rule 1 — "Only use information
present in the provided evidence and context — never invent facts." (2) The
`related_documentation` field is populated from the RAG retrieval sources, not
from LLM-generated text — so even if the LLM invents a runbook name, the
returned documentation list reflects what was actually retrieved. The caller
can verify any cited runbook exists in the knowledge base.

**Q5: Why does the pipeline have three collection modes (event_id, session_id, recent)?**

A: Different contexts require different evidence boundaries. `event_id` mode is for
drilling into a specific crash reported from Android — it fetches the full session
context so the analyst can see what happened before and after the crash. `session_id`
mode is for analysing a full user journey. `recent` mode is for the automated
anomaly detection pipeline (Phase 11) which runs every 60 seconds without knowing
specific event IDs. Having three modes means the same service handles both automated
and human-triggered analysis without any mode needing to know about the others.

**Q6: The LLM API has a 45-second timeout. What happens if it fires?**

A: `asyncio.wait_for()` raises `asyncio.TimeoutError`, which is caught in
`_call_llm()` and returns `("", provider_name)`. The empty string passes to
`_parse_llm_response()` which returns `{}`. Then `_build_response({}, ...)` detects
the empty dict and returns the safe fallback response: "AI analysis unavailable".
The incident is still created, the raw events are shown, and the engineer can
investigate manually. **The LLM being unavailable never prevents the system from
functioning** — it degrades gracefully to showing raw data.

---

## 8. Production Considerations

### Caching Analysis Results

Currently `ErrorAnalysisResponse` is not persisted — it lives only in memory for
the duration of the HTTP request (or as a snapshot on the `Incident` row via
`attach_analysis()`). For production:

1. Add an `error_analyses` table with the full response JSON
2. Store `analysis_id` → response in Redis with a 24-hour TTL for fast re-fetch
3. The `GET /analysis/errors/{event_id}` endpoint already returns a fresh analysis —
   if caching is added, check Redis first before re-running the pipeline

### Rate Limiting LLM Calls

Each `POST /analysis/errors` call makes one LLM API call that may use 2000–4000 tokens.
At scale, this can exhaust your daily quota quickly.

Production mitigations:
- Cache analysis by `(session_id, hour)` — don't re-run for the same session within
  one hour unless new events arrive
- Implement a Celery queue so analysis runs asynchronously and is rate-limited
- Use a cheaper model for initial triage, escalate to a premium model only for
  CRITICAL severity

### PII in Observability Events

The Android `PiiFilter` strips PII before events leave the device. The backend
adds a second layer: the LLM prompt includes Rule 6 ("Never expose credentials,
tokens, or PII even if present"). For production, also add a server-side PII
scan in `ObservabilityEventRepository.bulk_insert()` before persisting to PostgreSQL.

### Knowledge Base Quality

The quality of the analysis is directly proportional to the quality of the
knowledge base. Empty runbook/incident knowledge → LLM reasoning from events alone
→ lower confidence, more hallucination risk.

For production, seed:
- All operational runbooks (restart, rollback, scale, backup)
- Post-mortems for the last 12 months of incidents
- API and architecture documentation
- Known error patterns and their resolutions

### Monitoring the Analysis Pipeline

Add these metrics to Prometheus:
```python
analysis_runs_total         # counter, labelled by severity + provider
analysis_confidence_histogram  # observe confidence score distribution
analysis_duration_seconds   # observe pipeline latency
llm_failures_total          # counter for LLM API errors
low_confidence_rate         # gauge: % of analyses below 0.6
```

Alert if `low_confidence_rate > 30%` — this indicates the knowledge base needs
refreshing or the LLM is struggling with the current error patterns.

---

## 9. Exercises

### Exercise 1 — Trace the Pipeline
Send a test event with `level: "ERROR"` and `event_type: "db_connection_refused"`.
Trigger an analysis via `POST /analysis/errors`. Using the backend logs, identify
which of the 8 pipeline steps took the most time. Which step would most benefit
from caching?

### Exercise 2 — Test the Confidence Gate
Modify `_LOW_CONFIDENCE_THRESHOLD` to `0.99` temporarily. Trigger an analysis and
observe that `likely_root_cause` becomes "Evidence is insufficient". Restore the
threshold. What does this teach you about the relationship between confidence
calibration and system safety?

### Exercise 3 — Seed the Knowledge Base
Run `python scripts/seed_knowledge.py` to load the runbooks and incident reports.
Trigger an analysis before and after seeding. Compare `knowledge_chunks_retrieved`
and the quality of `recommended_fix` in both responses. Document your findings.

### Exercise 4 — Add a New Request Mode
The current `AnalyseErrorRequest` supports `event_id`, `session_id`, and
`lookback_minutes`. Add a fourth mode: `trace_id` — analyse all events that share
a specific `trace_id` (these represent one end-to-end user action across the app).
What changes are needed in `_collect_events()`? What new method do you need in
`ObservabilityEventRepository`?

### Exercise 5 — Add Analysis Caching
Implement a Redis cache for analysis results keyed by `session_id:YYYY-MM-DDTHH`:
- Before running the pipeline, check if a cached result exists
- After a successful analysis, store the result for 1 hour
- Return the cached result with a `cached: true` field added to the response

This teaches: cache invalidation strategy, Redis TTLs, and when NOT to cache
(hint: the cache must be invalidated if new events arrive for the same session).

---

## 10. Phase Summary

Phase 10 connects the data pipeline (Phase 2 Android Observability, Phase 3 Backend)
to the intelligence layer (Phase 9 RAG) to produce your first end-to-end AI
capability: automated error analysis with grounded reasoning and safety gates.

| What was built | File |
|---|---|
| Analysis pipeline (8 steps) | `backend/app/services/error_analysis_service.py` |
| Response schema with AI safety constraints | `backend/app/schemas/error_analysis.py` |
| API endpoints (3) | `backend/app/api/analysis/router.py` |
| 46 service unit tests | `backend/tests/unit/test_error_analysis_service.py` |
| 30 router unit tests | `backend/tests/unit/test_analysis_router.py` |
| Event ingest (feeds the pipeline) | `backend/app/api/observability/router.py` |

**AI Safety principles applied in this phase:**
- Never invent data — analysis only runs on real PostgreSQL events
- Confidence levels — every response has a `0.0–1.0` score
- Facts vs inferences — always separated, never merged
- Hard confidence gate — `< 0.6` → override to "Evidence insufficient"
- Recommendation only — no automated action without human approval
- Graceful degradation — LLM failure → raw data, never hidden errors

---

**Next phase:** Phase 11 — Anomaly Detection.  
Say `NEXT` to continue.
