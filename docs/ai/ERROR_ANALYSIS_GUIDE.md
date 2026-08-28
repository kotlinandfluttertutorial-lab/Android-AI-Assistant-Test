# Phase 10 — AI Error Analysis Guide

> **Learning goal:** Understand how to build an AI system that analyses production
> errors using real evidence, retrieved knowledge, and LLM reasoning — safely.
>
> **Career connection:** This is the pattern behind every AIOps tool (Datadog
> Watchdog, AWS DevOps Guru, Google Cloud Error Reporting AI). Understanding
> how to build it from scratch sets you apart in GenAI + DevOps roles.

---

## 1. Concept — What Is AI Error Analysis?

Traditional error monitoring tells you **what** happened:
```
ERROR: asyncpg.TooManyConnectionsError at 14:32:01
```

AI Error Analysis tells you **why it happened** and **what to do**:
```json
{
  "summary": "Database connection pool exhausted by long-running LLM calls",
  "likely_root_cause": "LLM calls hold DB connections open for 15-45s, exhausting the pool",
  "confidence": 0.87,
  "recommended_fix": "Add asyncio.wait_for(timeout=30) around all LLM calls..."
}
```

The AI doesn't just find patterns — it reasons about them using:
1. **Real evidence** — the actual error events captured on-device
2. **Historical context** — past incidents with similar patterns (RAG)
3. **Operational knowledge** — runbooks that describe known fixes (RAG)
4. **LLM reasoning** — connects evidence to knowledge and produces actionable output

---

## 2. Why Build It This Way?

Three alternative approaches and why this one is better:

| Approach | Pros | Cons |
|----------|------|------|
| Rule-based (if error_type == X → do Y) | Fast, predictable | Can't handle novel errors; brittle |
| Pure LLM (send error to GPT, ask "what's wrong?") | Flexible | Hallucinates; no grounding in real data |
| **RAG + LLM (this project)** | Grounded in real evidence + known solutions | Requires embedding infrastructure |

The RAG + LLM pattern solves the hallucination problem: the LLM is constrained to
reason about specific evidence and retrieved knowledge. It can't invent incidents
that didn't happen.

---

## 3. Architecture — The Complete Pipeline

```
Android App
  │  ObservabilityEvent (every HTTP call, crash, screen view)
  │  Captured by: NetworkObservabilityInterceptor, AppLifecycleObserver
  │  Filtered by: PiiFilter (never sends credentials or PII)
  ▼
ObservabilityUploadWorker (WorkManager, every 15 min)
  │  POST /api/v1/observability/events
  ▼
Backend: observability_events table (PostgreSQL)

                                ↓ When analysis is triggered
                    POST /api/v1/analysis/errors

PIPELINE STEP 1 — Collect Evidence
  ObservabilityEventRepository.get_recent_errors()
  → last 30 min of ERROR/CRITICAL events from PostgreSQL
  → ordered oldest-first (natural narrative order for LLM)

PIPELINE STEP 2 — Derive Search Query
  ErrorAnalysisService._derive_search_query()
  → extracts: unique event_types + error messages + screen names
  → e.g. "Error types: network_error, http_error; Messages: Connection refused..."

PIPELINE STEP 3 — Retrieve Runbooks (RAG)
  RAGService.query_knowledge_base(query, categories=["runbooks"])
  → embedds query with all-MiniLM-L6-v2
  → cosine similarity search on devops_knowledge ChromaDB collection
  → returns top-5 runbook chunks

PIPELINE STEP 4 — Retrieve Incidents (RAG)
  RAGService.query_knowledge_base(query, categories=["incidents"])
  → same embedding, same collection, different category filter
  → returns top-5 historical incident chunks

PIPELINE STEP 5 — Build LLM Prompt
  ErrorAnalysisService._build_prompt()
  → combines: formatted events + runbook context + incident context
  → includes explicit AI safety constraints
  → requests structured JSON output

PIPELINE STEP 6 — LLM Reasoning
  AIOrchestrator.complete(prompt, provider, max_tokens=1024)
  → Gemini / OpenAI / Claude with 45s timeout
  → returns raw JSON string

PIPELINE STEP 7 — Parse + Validate
  ErrorAnalysisService._parse_llm_response()
  → extracts JSON object (handles markdown fences)
  → maps to ErrorAnalysisResponse schema

PIPELINE STEP 8 — AI Safety Gate
  confidence < 0.6 → override likely_root_cause with "Evidence insufficient"
  → populates low_confidence_warning

PIPELINE RESULT → ErrorAnalysisResponse
  {
    "severity": "HIGH",
    "summary": "...",
    "evidence": [...],
    "possible_causes": [...],
    "likely_root_cause": "...",
    "confidence": 0.87,
    "recommended_fix": "...",
    "related_documentation": [...],
    "facts_vs_inference": {"facts": [...], "inferences": [...]},
    "events_analysed": 23,
    "knowledge_chunks_retrieved": 10
  }
```

---

## 4. Implementation — Key Files

| File | Purpose |
|------|---------|
| `backend/app/models/observability_event.py` | ORM model — stores Android events in PostgreSQL |
| `backend/alembic/versions/0010_add_observability_events.py` | Migration creating the table |
| `backend/app/repositories/observability_event_repository.py` | Data access — bulk insert + time-window queries |
| `backend/app/api/observability/router.py` | `POST /observability/events` — ingest endpoint |
| `backend/app/schemas/error_analysis.py` | Request/response schemas with AI safety fields |
| `backend/app/services/rag_service.py` | `query_knowledge_base()` — devops_knowledge search |
| `backend/app/services/error_analysis_service.py` | Full pipeline orchestration |
| `backend/app/api/analysis/router.py` | `POST /analysis/errors` — trigger endpoint |
| `core-common/.../ObservabilityEvent.kt` | Android event model |
| `core-network/.../ObservabilityUploadWorker.kt` | Android upload worker |
| `core-common/.../PiiFilter.kt` | Client-side PII scrubbing |

---

## 5. Prompt Engineering — Why the Prompt Is Designed This Way

The analysis prompt (`_build_prompt` in `error_analysis_service.py`) follows
specific principles. Each one matters:

### Principle 1 — Explicit role and expertise

```
"You are an expert Site Reliability Engineer and AI-powered DevOps assistant."
```

Setting the role reduces generic responses. The LLM is more likely to use
technical terminology correctly and reason at the right abstraction level.

### Principle 2 — Grounding constraint

```
"Only use information present in the provided evidence and context — never invent facts."
```

Without this, the LLM will fill knowledge gaps with plausible-sounding fabrications.
This is the primary hallucination prevention rule.

### Principle 3 — Facts vs inference separation

```
"Separate facts (directly observable in the evidence) from inferences (your reasoning)."
```

This forces the LLM to be explicit about what it knows vs what it's guessing.
The `facts_vs_inference` field in the response makes this visible to the caller.

### Principle 4 — Confidence calibration

```
"Provide a confidence score between 0.0 and 1.0. Be honest about uncertainty."
```

LLMs tend to be overconfident. Asking them to score confidence reduces this,
and the 0.6 threshold provides a programmatic safety gate.

### Principle 5 — The 0.6 confidence gate

```
"If confidence is below 0.6, set likely_root_cause to exactly:
 'Evidence is insufficient — manual investigation required.'"
```

This is enforced twice:
1. **In the prompt** — the LLM knows the rule
2. **In `_build_response()`** — the code overrides the LLM output regardless

The code-level enforcement matters because LLMs don't always follow instructions
perfectly. The safety property must not depend on the LLM's compliance alone.

### Principle 6 — Human approval constraint

```
"Recommended fix is a SUGGESTION only — never imply automated action will be taken."
```

No automated remediation executes without explicit human approval. The analysis
output is advisory, not a command.

### Principle 7 — Structured JSON output

Asking the LLM to return raw JSON (not prose) makes parsing reliable and
keeps the response machine-readable for the Android dashboard.

---

## 6. AI Safety Principles Applied

The master plan defines 10 AI safety principles. Here is how Phase 10 implements each:

| Principle | Implementation |
|-----------|--------------|
| Never invent data | Prompt grounding constraint + `facts_vs_inference.facts` must cite evidence |
| Never claim unsupported root causes | Every `likely_root_cause` cites specific evidence |
| Provide confidence levels | `confidence` field (0.0–1.0) required in every response |
| Separate facts from inferences | `facts_vs_inference` schema field, enforced in prompt |
| Never expose secrets | PiiFilter on Android; prompt reminds LLM not to repeat secrets |
| Require human approval | `recommended_fix` is labelled as suggestion; no action endpoint exists |
| Validate tool outputs | `_parse_llm_response()` validates JSON structure; defaults on failure |
| Protect against prompt injection | `AIOrchestrator._detect_prompt_injection()` runs on all inputs |
| Audit trail | Every call logs user ID, event count, provider, analysis ID |
| Graceful degradation | LLM failure → safe fallback response; no 500 errors |

---

## 7. How to Test the Pipeline End-to-End

### Step 1 — Generate some error events

The fastest way is to force a network error in the Android app. Alternatively,
insert test events directly:

```bash
# Insert fake ERROR events via the ingest endpoint
curl -X POST http://localhost:8000/api/v1/observability/events \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {
        "timestamp": '"$(date +%s)000"',
        "level": "ERROR",
        "eventType": "http_error",
        "message": "POST /chat returned HTTP 500",
        "sessionId": "test-session-001",
        "requestId": "req-abc",
        "screen": "ChatScreen",
        "metadata": {"http_status": "500", "endpoint": "/chat", "latency_ms": "8230"}
      },
      {
        "timestamp": '"$(date +%s)000"',
        "level": "CRITICAL",
        "eventType": "network_error",
        "message": "Connection refused: asyncpg pool exhausted",
        "sessionId": "test-session-001",
        "metadata": {"error_type": "TooManyConnectionsError"}
      }
    ]
  }'
# Expected: {"accepted":2,"total":2}
```

### Step 2 — Trigger analysis

```bash
# Get a JWT first
JWT=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"testpass"}' | jq -r .access_token)

# Run analysis on the last 5 minutes
curl -X POST http://localhost:8000/api/v1/analysis/errors \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"lookback_minutes": 5}' | jq .
```

### Step 3 — Verify the response

Expected response structure:
```json
{
  "analysis_id": "uuid",
  "severity": "HIGH",
  "summary": "Database connection pool exhausted during API call",
  "evidence": [
    "POST /chat returned HTTP 500 (8230ms)",
    "Connection refused: asyncpg pool exhausted"
  ],
  "possible_causes": [
    "Connection pool exhausted by long-running LLM calls",
    "Max pool_size reached under concurrent traffic"
  ],
  "likely_root_cause": "LLM calls holding DB connections open for extended periods",
  "confidence": 0.84,
  "recommended_fix": "1. Increase pool_size from 5 to 15\n2. Add asyncio.wait_for...",
  "related_documentation": [
    "INC-001-db-connection-pool.md",
    "database-recovery.md"
  ],
  "facts_vs_inference": {
    "facts": ["HTTP 500 at 14:32", "asyncpg TooManyConnectionsError"],
    "inferences": ["Pool exhausted by concurrent LLM calls holding connections open"]
  },
  "low_confidence_warning": null,
  "events_analysed": 2,
  "knowledge_chunks_retrieved": 10
}
```

### Step 4 — Verify low confidence safety gate

Insert a single ambiguous event and check the safety gate fires:

```bash
# Single event with no clear pattern
curl -X POST http://localhost:8000/api/v1/observability/events \
  -H "Content-Type: application/json" \
  -d '{"events": [{"timestamp": '"$(date +%s)000"', "level": "ERROR",
       "eventType": "user_error", "message": "Something went wrong",
       "sessionId": "test-ambiguous", "metadata": {}}]}'

curl -X POST http://localhost:8000/api/v1/analysis/errors \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"session_id": "test-ambiguous"}' | jq '.confidence, .low_confidence_warning'
# Verify: confidence < 0.6 and low_confidence_warning is populated
```

---

## 8. Debug — Common Issues

### Analysis returns `"No error events found"`

The `observability_events` table is empty. Check:

1. Has the Alembic migration run? `alembic current` should show `0010_add_observability_events`
2. Is the Android app uploading? WorkManager runs every 15 minutes when CONNECTED
3. Force a test insert via the curl command in Step 1 above
4. Check `lookback_minutes` — default is 30, but events may be older

### Analysis confidence is always 0.0

The LLM call is failing silently. Check:
1. `GEMINI_API_KEY` / `OPENAI_API_KEY` is set in Secret Manager
2. Check startup logs: `STARTUP: knowledge base seeded` (ChromaDB must be running)
3. Set `LOG_LEVEL=DEBUG` and re-run to see the LLM prompt and response

### `related_documentation` is empty

ChromaDB is empty — the knowledge base was not seeded. Fix:
```bash
python backend/scripts/seed_knowledge.py
# Or call the admin endpoint
curl -X POST http://localhost:8000/api/v1/admin/rag/reindex \
  -H "Authorization: Bearer ADMIN_JWT"
```

### Slow analysis (> 15 seconds)

The bottleneck is almost always the LLM call. Options:
- Switch to Gemini Flash (`DEFAULT_LLM_PROVIDER=gemini`) — typically 3–5s vs 15s for GPT-4o
- Reduce `_MAX_EVENTS_IN_PROMPT` from 50 to 20 (shorter prompt = faster response)
- Check `OTEL_ENABLED=true` and view the trace in Cloud Trace to see exactly where time is spent

---

## 9. Interview Questions

**Q1: How do you prevent an AI from hallucinating in error analysis?**

Three-layer approach:
1. **Grounding constraint in the prompt** — "Only use information present in the evidence. Never invent facts."
2. **Facts vs inference separation** — force the model to label each claim as fact (from evidence) or inference (from reasoning). This makes fabrication visible.
3. **Confidence threshold gate** — if the model reports < 0.6 confidence, override the likely_root_cause in code with "Evidence insufficient." This safety property is enforced by application logic, not just by the LLM's compliance with instructions.

---

**Q2: Why collect events from PostgreSQL rather than querying logs from Cloud Logging?**

Three reasons:
1. **Structured** — PostgreSQL events have indexed fields (level, event_type, session_id, timestamp) making time-window queries fast. Cloud Logging queries are slower for ad-hoc analysis.
2. **Android-specific** — these events come from the mobile app, not the backend. Cloud Logging only captures server logs. The client-side observability data (screen, session, request correlation) is unique to the Android pipeline.
3. **Controllable** — we define the schema, the PII filtering, and the retention policy. Cloud Logging is an external system we don't fully control.

---

**Q3: What is the confidence score and why is 0.6 the threshold?**

The confidence score is the LLM's self-assessed certainty about its root cause identification. It is a number from 0.0 to 1.0 included in the prompt instruction and extracted from the JSON response.

0.6 is the threshold because:
- Below 0.6 means the model has less than 60% confidence, which is essentially a coin flip plus noise
- At this point the risk of acting on a wrong diagnosis exceeds the benefit of the suggestion
- The master plan specifies this exact threshold: "When confidence < 0.6, respond: 'Evidence is insufficient — manual investigation required.'"

The threshold is enforced in application code regardless of what the LLM returns — the LLM might claim 0.75 confidence even when it shouldn't, so the code provides a safety net.

---

**Q4: How does the analysis pipeline use both PostgreSQL and ChromaDB?**

They serve different purposes:
- **PostgreSQL** stores the real-time evidence — the actual error events from the Android app, with their exact timestamps, messages, and correlation IDs. This is "what happened."
- **ChromaDB** stores the operational knowledge — embeddings of runbooks and historical incidents. Semantic similarity search finds the most relevant knowledge for the current error pattern. This is "what we know about this kind of problem."

The LLM prompt combines both: real evidence from PostgreSQL + relevant context from ChromaDB. Without PostgreSQL, the LLM would be reasoning without facts. Without ChromaDB, the LLM would lack domain-specific knowledge about this system.

---

**Q5: Why does the `POST /observability/events` endpoint not require authentication?**

Security trade-off with a clear justification:
- Events must be uploadable even when the user is logged out (crashes on the login screen, network errors before auth completes)
- The data contains no PII — `PiiFilter` strips everything before the event leaves the Android app
- The endpoint is rate-limited by IP via `RateLimitMiddleware`
- There is no way to read events back through this endpoint — it is write-only

An alternative would be to accept the device's refresh token as auth. That was not chosen because it would block event uploads during token rotation periods, creating gaps in observability at exactly the moments when errors are most likely.

---

**Q6: How would you extend this pipeline to anomaly detection (Phase 11)?**

The existing infrastructure already provides the foundation:
1. `observability_events` table is queryable over arbitrary time windows
2. `query_knowledge_base()` can retrieve anomaly detection runbooks

Phase 11 adds:
- **Rule-based detection** — SQL queries checking if error_rate > threshold in the last N minutes
- **Statistical detection** — compute rolling mean + std dev, alert when value > mean + 2σ
- **Trigger** — instead of an API call, a periodic Celery task runs the detection and automatically triggers `ErrorAnalysisService.analyse()` when an anomaly is detected
- **Notification** — the analysis result is pushed to the developer's device via FCM (Firebase already wired)

The analysis service itself needs no changes — it just gets called by a different trigger.

---

## 10. Exercise

1. **Run the pipeline end-to-end** — insert 5 ERROR events matching the INC-001
   pattern (connection pool errors) and verify the analysis returns `confidence > 0.7`
   with `related_documentation` containing `INC-001-db-connection-pool.md`.

2. **Test the safety gate** — insert a single vague event and verify:
   - `confidence < 0.6`
   - `likely_root_cause` contains "Evidence is insufficient"
   - `low_confidence_warning` is populated

3. **Add a new error pattern** — create a knowledge article at
   `knowledge/runbooks/llm-timeout.md` describing LLM timeout recovery steps.
   Re-seed ChromaDB. Insert events with `eventType: "network_timeout"`.
   Verify the new runbook appears in `related_documentation`.

4. **Inspect the prompt** — add `logger.debug("PROMPT: %s", prompt)` to
   `_build_prompt()`, set `LOG_LEVEL=DEBUG`, and run an analysis. Read the full
   prompt that was sent to the LLM. Does it contain the right evidence?
   Does it correctly separate runbook content from incident content?

5. **Test graceful degradation** — stop ChromaDB (`docker stop chromadb`),
   run an analysis, and verify: the API returns 200 (not 500), `knowledge_chunks_retrieved=0`,
   and the LLM still provides an answer based on the raw events alone.

---

## Phase 10 Summary

**What was built:**

```
Android events → PostgreSQL
  ObservabilityEvent ORM model (observability_events table)
  Alembic migration 0010
  ObservabilityEventRepository (bulk_insert, get_recent_errors, get_by_session)
  POST /api/v1/observability/events (ingest, no auth, 202 Accepted)

RAG knowledge retrieval
  RAGService.query_knowledge_base() — queries devops_knowledge ChromaDB collection
  Optional category filter: runbooks | incidents | architecture | deployment

Error analysis pipeline
  ErrorAnalysisService: 8-step pipeline
    1. Collect events from PostgreSQL
    2. Derive search query from error patterns
    3. Retrieve runbooks via RAG
    4. Retrieve historical incidents via RAG
    5. Build LLM prompt with safety constraints
    6. Call LLM (Gemini / OpenAI / Claude, 45s timeout)
    7. Parse structured JSON response
    8. Apply confidence gate (< 0.6 → manual investigation)

API
  POST /api/v1/analysis/errors (analyse recent errors, requires JWT)
  POST /api/v1/analysis/errors/session (analyse by session ID)
```

**What connects forward:**
- Phase 11 (Anomaly Detection) adds scheduled triggering of this same pipeline
- Phase 12 (Root Cause Analysis) extends the prompt with metrics and trace data
- Phase 13 (AI DevOps Assistant) uses this service as a tool the LLM can call
- Phase 14 (Android Dashboard) displays the `ErrorAnalysisResponse` in the UI

Say `NEXT` to continue to **Phase 11 — Anomaly Detection**.
