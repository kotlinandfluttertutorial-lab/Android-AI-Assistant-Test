# Phase 12 — Root Cause Analysis Guide

> **Learning goal:** Understand why RCA is different from error analysis, how
> chain-of-thought prompting produces better ranked candidates than a single
> answer, and how to correlate evidence across multiple data sources.
>
> **Career connection:** Every SRE and on-call engineer spends most of their
> incident time asking "why did this happen?" RCA is how you answer that
> systematically rather than by instinct.

---

## 1. Concept — What Is Root Cause Analysis?

RCA is the process of finding not just *what* broke, but *why* it broke, so you
can prevent it from happening again.

```
Error Analysis (Phase 10):   "The API returned 500 errors"
Root Cause Analysis (Phase 12): "The API returned 500 errors BECAUSE
                                  the DB connection pool was exhausted
                                  BECAUSE LLM calls held connections
                                  open for 30+ seconds
                                  BECAUSE there was no LLM timeout set"
```

The key word is **because** — chaining back to the true origin of a failure, not
just its symptom.

---

## 2. How Phase 12 Differs from Phase 10

| Dimension | Phase 10 Error Analysis | Phase 12 RCA |
|-----------|------------------------|--------------|
| **Scope** | Time window (last N min) | Specific incident (by ID) |
| **Evidence** | Observability events only | Obs events + server error logs + deployment docs |
| **Output** | Single `likely_root_cause` | Ranked `root_cause_candidates` list |
| **Confidence** | One overall score | Per-candidate score + overall score |
| **Reasoning** | Implicit (inside prompt) | Explicit `chain_of_thought` field |
| **Timeline** | Not produced | Correlated timeline across all sources |
| **Persistence** | Not stored | Saved on the Incident row |
| **When called** | Immediately on anomaly detection | After incident is created, on demand |

Phase 10 is the quick first look. Phase 12 is the deeper investigation.
Both pipelines share the same building blocks (RAG, LLM, confidence gate) but
Phase 12 gets more evidence, thinks harder, and stores its work.

---

## 3. Architecture — The Complete RCA Pipeline

```
POST /api/v1/incidents/{id}/rca
              │
              ▼
RcaService.run(incident_id, request)
              │
Step 1: Load Incident row (Phase 11 data: title, severity, detected_at,
        metric_value, threshold_value, Phase 10 ai_summary, ai_confidence)
              │
Step 2: Collect Evidence
  ├── ObservabilityEventRepository.get_recent_errors()
  │     → ERROR/WARN Android events in window around detected_at
  └── ErrorLogRepository.get_errors_around_time()
        → server-side Python exceptions (with stack traces) in same window
              │
Step 3: Build Timeline
  → Merge + chronologically sort all events from all sources
  → Tag each event with source: "APP" (Android) or "SRV" (server)
              │
Step 4: RAG Retrieval
  ├── query_knowledge_base(search_query, categories=["runbooks"])
  └── query_knowledge_base(search_query, categories=["incidents"])
              │
Step 5: Build Chain-of-Thought Prompt
  → Incident context (title, severity, threshold breach)
  → Phase 10 preliminary analysis (ai_summary, ai_confidence)
  → Correlated timeline (APP + SRV events, oldest → newest)
  → Relevant runbooks
  → Relevant historical incidents
  → Chain-of-thought instruction: "Analyse → Hypothesise → Rank → Conclude"
              │
Step 6: LLM Reasoning (AIOrchestrator.complete, 60s timeout)
  → Produces structured JSON with:
     - chain_of_thought (the step-by-step reasoning)
     - root_cause_candidates (ranked, each with confidence + evidence)
     - investigation_steps
     - related_documentation
              │
Step 7: Parse JSON response (handles markdown fences)
              │
Step 8: Apply AI Safety Gate
  → Sort candidates by confidence descending
  → overall_confidence = top candidate's confidence
  → overall_confidence < 0.6 → override top candidate cause,
    populate low_confidence_warning
              │
Step 9: Persist result on Incident row
  → attach_rca(incident_id, rca_analysis_id, rca_summary,
               rca_confidence, rca_candidates_json,
               rca_investigation_steps_json)
              │
              ▼
RcaAnalysisResponse
  {
    "rca_id": "uuid",
    "incident_id": "uuid",
    "summary": "DB connection pool exhausted by LLM calls",
    "root_cause_candidates": [
      {"rank":1, "cause":"LLM calls holding DB connections open",
       "confidence": 0.82, "supporting_evidence": [...], "reasoning": "..."},
      {"rank":2, "cause":"Pool size too small for concurrent traffic",
       "confidence": 0.34, "supporting_evidence": [...], "reasoning": "..."}
    ],
    "overall_confidence": 0.82,
    "timeline": [{...}, {...}],
    "chain_of_thought": "Step 1: I see connection errors starting at 14:32...",
    "investigation_steps": ["1. Check current pool_size setting", ...],
    "related_documentation": ["database-recovery.md", "INC-001-db-connection-pool.md"],
    "observability_events_count": 23,
    "error_logs_count": 8,
    "knowledge_chunks_count": 10,
    "low_confidence_warning": null
  }
```

---

## 4. Chain-of-Thought Prompting — Why It Produces Better Candidates

### What chain-of-thought is

Chain-of-thought (CoT) prompting explicitly instructs the LLM to reason step-by-step
before giving an answer, rather than jumping directly to a conclusion.

```
WITHOUT CoT:
  "Given these errors, what is the root cause?"
  → LLM answers immediately, often picking the first pattern it recognises

WITH CoT:
  "Think step by step:
   1. Analyse the timeline — what pattern do you see?
   2. Hypothesise potential causes — list them all
   3. Evaluate each hypothesis against the evidence
   4. Rank candidates by likelihood"
  → LLM considers multiple hypotheses before committing
```

### Why it matters for RCA

RCA often involves multiple plausible causes. A direct prompt will confidently
state one — and be wrong 30–40% of the time. CoT forces the model to:

1. **Notice the timeline** — "errors started 2 minutes after events X and Y"
2. **Generate hypotheses** — "this could be A, B, or C"
3. **Check each against evidence** — "B is inconsistent because..."
4. **Rank with reasoning** — "A has 3 pieces of supporting evidence vs 1 for C"

The `chain_of_thought` field in the response exposes this reasoning so engineers
can read it, check whether it makes sense, and catch when the model went wrong.

### The prompt structure

```python
# From rca_service.py _build_prompt()
"Think step by step: first analyse the timeline, then hypothesise causes, then rank them."
```

Plus the four-phase instruction:
```
ANALYSE → HYPOTHESISE → RANK → CONCLUDE
```

This mirrors how a senior SRE actually investigates an incident on paper.

---

## 5. Multi-Source Evidence and Timeline Correlation

### Why correlate multiple sources?

A single data source tells a partial story:

```
Android observability events only:
  14:31:55  WARN  api_latency   POST /chat  took 8230ms
  14:32:01  ERROR http_error    POST /chat  returned 500
  → "Something went wrong at 14:32"

Server error logs only:
  14:32:01  asyncpg.TooManyConnectionsError in POST /api/v1/chat
  14:32:03  asyncpg.TooManyConnectionsError in POST /api/v1/chat
  → "DB pool exhausted"

Combined correlated timeline:
  14:31:55  APP  WARN   api_latency   POST /chat slow (8230ms) ← latency spike first
  14:32:01  APP  ERROR  http_error    POST /chat → 500
  14:32:01  SRV  ERROR  TooManyConnectionsError in /api/v1/chat ← server confirms pool
  14:32:03  SRV  ERROR  TooManyConnectionsError in /api/v1/chat (still happening)
  → "Pool exhausted, preceded by latency spike — LLM calls holding connections"
```

The timeline shows the *sequence* of events, which is critical for determining cause
vs effect. The latency spike (APP) happened *before* the 500 errors (APP+SRV).
This sequencing is lost when evidence is presented as flat lists.

### Source tags in the timeline

| Tag | Source | What it captures |
|-----|--------|-----------------|
| `APP` | `observability_events` | Android-side errors, screen context, user flow |
| `SRV` | `error_logs` | Python exceptions with stack traces, endpoint paths |
| `DEPLOY` | (future) | Deployment events, config changes |
| `METRIC` | (future) | Prometheus point-in-time snapshots |

---

## 6. Per-Candidate Confidence — Why a Ranked List Is Better

Phase 10 returns one `likely_root_cause`. This hides uncertainty.

Phase 12 returns a ranked list where each candidate has its own confidence:

```json
"root_cause_candidates": [
  {"rank": 1, "cause": "LLM calls holding DB connections",  "confidence": 0.82},
  {"rank": 2, "cause": "Pool size too small",               "confidence": 0.34},
  {"rank": 3, "cause": "Recent deployment changed query",   "confidence": 0.12}
]
```

**What this tells you:**

- Candidate 1 (82%) is likely correct — investigate this first
- Candidate 2 (34%) is a reasonable secondary hypothesis — don't dismiss it
- Candidate 3 (12%) is a long shot but should not be ignored completely

**Without the list, the same response would say:**
```
"likely_root_cause": "LLM calls holding DB connections"
```
...hiding that candidates 2 and 3 exist. An engineer might fix candidate 1,
see the problem recur (because candidate 2 was also contributing), and have to
investigate from scratch.

### How candidates are ranked

The LLM assigns confidence to each candidate based on supporting evidence count
and strength. `rca_service.py` then:
1. Sorts by confidence descending
2. Re-numbers ranks (1 = highest confidence)
3. Sets `overall_confidence` = top candidate's confidence
4. Applies the 0.6 gate on `overall_confidence`

---

## 7. Implementation — Key Files

| File | Purpose |
|------|---------|
| `backend/app/schemas/rca.py` | `RootCauseCandidate`, `TimelineEvent`, `RcaAnalysisResponse`, `RcaRequest` |
| `backend/app/services/rca_service.py` | Full 9-step RCA pipeline |
| `backend/app/repositories/error_log_repository.py` | `get_errors_around_time()` — server error logs |
| `backend/app/models/incident.py` | RCA fields: `rca_analysis_id`, `rca_summary`, `rca_confidence`, `rca_candidates_json`, `rca_investigation_steps_json` |
| `backend/app/repositories/incident_repository.py` | `attach_rca()` — persist RCA results |
| `backend/alembic/versions/0012_add_rca_fields_to_incidents.py` | DB migration |
| `backend/app/api/incidents/router.py` | `POST /incidents/{id}/rca`, `GET /incidents/{id}/rca` |

---

## 8. Caching Behaviour

RCA is expensive (LLM call, DB queries, RAG search). Once run, the result is
stored on the Incident row. Subsequent `POST /incidents/{id}/rca` calls return
the cached result without re-running the pipeline.

```bash
# First call — runs the full pipeline (~10–30s)
POST /api/v1/incidents/{id}/rca

# Second call — returns cached result instantly
POST /api/v1/incidents/{id}/rca
# Response: same rca_id, llm_provider="cached", timeline=[] (not stored)

# Force re-run (e.g. after adding more evidence or fixing ChromaDB)
POST /api/v1/incidents/{id}/rca
Content-Type: application/json
{"force_rerun": true}

# Read-only fetch of cached result
GET /api/v1/incidents/{id}/rca
```

**Note:** The cached result does not include the full `timeline` list (not stored
on the Incident row to avoid large JSON columns). Re-run with `force_rerun: true`
to get the full timeline.

---

## 9. How to Test the RCA Pipeline End-to-End

### Step 1 — Create an incident

```bash
JWT=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -d '{"email":"test@test.com","password":"pass"}' | jq -r .access_token)

# Manually create an incident (or let the anomaly worker create one)
INCIDENT_ID=$(curl -s -X POST http://localhost:8000/api/v1/incidents \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"title":"DB connection pool exhausted","severity":"HIGH","triggered_by":"manual"}' \
  | jq -r .id)

echo "Incident ID: $INCIDENT_ID"
```

### Step 2 — Insert supporting evidence

```bash
# Insert error events that the RCA pipeline will find
curl -s -X POST http://localhost:8000/api/v1/observability/events \
  -H "Content-Type: application/json" \
  -d '{
    "events": [
      {"timestamp":'"$(date +%s)000"',"level":"WARN","eventType":"api_latency",
       "message":"POST /chat took 8230ms","sessionId":"s1","metadata":{"latency_ms":"8230","endpoint":"/chat"}},
      {"timestamp":'"$(date +%s)000"',"level":"ERROR","eventType":"http_error",
       "message":"POST /chat returned HTTP 500","sessionId":"s1","metadata":{"http_status":"500"}},
      {"timestamp":'"$(date +%s)000"',"level":"ERROR","eventType":"network_error",
       "message":"asyncpg.TooManyConnectionsError: pool exhausted","sessionId":"s1","metadata":{}}
    ]
  }'
```

### Step 3 — Run the RCA

```bash
curl -s -X POST http://localhost:8000/api/v1/incidents/$INCIDENT_ID/rca \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"evidence_window_minutes": 30}' | jq '
    {
      summary: .summary,
      top_candidate: .root_cause_candidates[0].cause,
      top_confidence: .root_cause_candidates[0].confidence,
      overall_confidence: .overall_confidence,
      candidates_count: (.root_cause_candidates | length),
      steps: .investigation_steps[0:2],
      related_docs: .related_documentation[0:3],
      events_used: .observability_events_count
    }
  '
```

### Step 4 — Verify the ranked candidates

```bash
curl -s http://localhost:8000/api/v1/incidents/$INCIDENT_ID/rca \
  -H "Authorization: Bearer $JWT" | \
  jq '.root_cause_candidates[] | {rank, cause, confidence}'
```

Expected output (with INC-001-like evidence):
```json
{"rank": 1, "cause": "LLM calls holding DB connections open...", "confidence": 0.82}
{"rank": 2, "cause": "Connection pool size too small...",         "confidence": 0.31}
```

### Step 5 — Test the confidence safety gate

```bash
# Create an incident with no supporting evidence
EMPTY_ID=$(curl -s -X POST http://localhost:8000/api/v1/incidents \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"title":"Ambiguous error","severity":"LOW"}' | jq -r .id)

curl -s -X POST http://localhost:8000/api/v1/incidents/$EMPTY_ID/rca \
  -H "Authorization: Bearer $JWT" \
  -d '{}' | jq '{overall_confidence, low_confidence_warning}'
# Verify: confidence < 0.6, low_confidence_warning populated
```

---

## 10. Debug — Common Issues

### RCA returns candidates with confidence 0.0

The LLM responded but didn't assign confidence values. Check:
1. `chain_of_thought` field — does it show reasoning or was it empty?
2. If empty: the LLM call failed (check provider key and timeout)
3. If reasoning is present but confidence is 0.0: the JSON parser may have
   failed to extract the confidence field — add `LOG_LEVEL=DEBUG` and check
   the raw LLM response

### `error_logs_count` is always 0

The `error_logs` table is only written by `RequestLoggingMiddleware` when
Python exceptions are raised inside route handlers. It won't contain Android
network errors (those go to `observability_events`).

To verify it's working:
```bash
curl http://localhost:8000/api/v1/nonexistent-route  # triggers a 404 → not an error_log
```

To actually write to `error_logs`, trigger an unhandled exception — the
`http_unhandled_exceptions_total` Prometheus counter increments at the same time.

### `knowledge_chunks_count` is 0

ChromaDB is either empty or unreachable. Fix:
```bash
python backend/scripts/seed_knowledge.py
# or
curl -X POST http://localhost:8000/api/v1/admin/rag/reindex \
  -H "Authorization: Bearer ADMIN_JWT"
```

### RCA takes > 30 seconds

The bottleneck is almost always the LLM call (Step 6). Options:
- Switch to Gemini Flash: `{"provider": "gemini"}` in the request body
- Reduce `_LLM_MAX_TOKENS` in `rca_service.py` from 1536 to 1024
- Use the cached result (call `GET /incidents/{id}/rca` after first POST)

---

## 11. Interview Questions

**Q1: What is the difference between error analysis and root cause analysis?**

Error analysis (Phase 10) asks "what went wrong and what is the most likely cause?"
It scopes to a time window, produces a single `likely_root_cause`, and is triggered
automatically on anomaly detection. It's fast (< 30 seconds) and good for quick triage.

Root cause analysis (Phase 12) asks "why did this specific incident happen, with ranked
alternative hypotheses?" It's incident-scoped (takes an incident ID), collects more
evidence sources, correlates them into a timeline, and produces multiple ranked candidates.
It's designed for deeper investigation after the incident is confirmed. The key
addition is the *ranked list* — RCA acknowledges that the real cause might be
candidate 2, not candidate 1.

---

**Q2: What is chain-of-thought prompting? Why does it improve RCA quality?**

Chain-of-thought prompting instructs the LLM to reason step-by-step before giving
an answer, rather than jumping directly to a conclusion. The instruction is explicit:
"Analyse → Hypothesise → Rank → Conclude."

For RCA, this matters because:
1. It forces the model to notice the *sequence* of events in the timeline
2. It generates multiple hypotheses before committing to one
3. It checks each hypothesis against the evidence
4. The reasoning is captured in the `chain_of_thought` field and can be audited

Without CoT, an LLM given the same evidence will typically produce the first
plausible answer rather than the best one. Studies show CoT reduces reasoning
errors by 20–40% on multi-step problems.

---

**Q3: How does the confidence gate work at Phase 12, and how does it differ from Phase 10?**

Both phases use the same threshold (0.6): if confidence is below it, the response
says "Evidence is insufficient — manual investigation required."

The difference is *what* the confidence applies to:
- Phase 10: one overall `confidence` for the single `likely_root_cause`
- Phase 12: each `RootCauseCandidate` has its own confidence score, and
  `overall_confidence` is set to the top candidate's score

This matters because in Phase 12 you might have candidate 1 at 0.82 and candidate 2
at 0.34. The gate fires on `overall_confidence` (0.82 → no warning). But if
candidate 1 is only 0.52, the gate fires even if the analysis found candidates.

In both phases, the safety property is enforced in application code, not just in
the LLM prompt — the code overrides the result regardless of what the model says.

---

**Q4: Why is the RCA result cached on the Incident row rather than in a separate table?**

Two reasons:

1. **Simplicity at this scale:** The incident is the natural owner of its analysis.
   Storing RCA fields on the Incident row avoids a JOIN for the most common query
   (list incidents with their latest RCA summary for the dashboard).

2. **Sufficient for Phase 12:** A separate `rca_analyses` table would be needed if
   you wanted to store *multiple RCA runs* per incident (each re-run would be a new
   row). The current design stores only the most recent run. `force_rerun: true`
   overwrites the previous result.

The upgrade path: add a `rca_analyses` table with `incident_id` FK and store
the full JSON there. Keep the summary fields on the Incident row as a denormalized
cache for dashboard performance.

---

**Q5: How would you add deployment event correlation to Phase 12?**

Currently, deployment context comes only from static docs in ChromaDB (`knowledge/deployment/`).
To add live deployment event correlation:

1. **Add a `DeploymentEvent` model:**
   ```python
   class DeploymentEvent(Base):
       __tablename__ = "deployment_events"
       id: Mapped[uuid.UUID] = uuid_pk()
       service: Mapped[str]          # "ai-assistant-backend"
       version: Mapped[str]          # git SHA or image tag
       deployed_by: Mapped[str]      # GitHub Actions run ID or user
       deployed_at: Mapped[datetime]
       notes: Mapped[str | None]     # "feat: add LLM timeout"
   ```

2. **Add a webhook endpoint** that receives deployment events from GitHub Actions:
   ```yaml
   # In cloud-run-deploy.yml, after successful deploy:
   - name: Record deployment event
     run: |
       curl -X POST ${{ secrets.SERVICE_URL }}/api/v1/deployments \
         -H "Authorization: Bearer ${{ secrets.SYSTEM_TOKEN }}" \
         -d '{"service":"backend","version":"${{ github.sha }}"}'
   ```

3. **Query in RCA evidence collection:**
   ```python
   deployments = await deployment_repo.get_around_time(
       centre=incident.detected_at,
       window_minutes=60,
   )
   ```

4. **Add `DEPLOY` tagged events to the timeline.** The LLM will then see "deployment
   at 14:28" in the timeline alongside "errors starting at 14:32" and can reason
   about the causal connection.

---

**Q6: How do you know when the RCA result is good enough to act on?**

Three signals:

1. **`overall_confidence > 0.75` + single dominant candidate** — the top candidate
   has substantially higher confidence than candidate 2. The gap (0.75 vs 0.20)
   indicates the model is confident in its ranking. Safe to act.

2. **`chain_of_thought` cites specific evidence** — the reasoning references actual
   timestamps, error messages, and metric values from the timeline. Generic reasoning
   ("this could be a DB issue") without specific citations is a red flag.

3. **`investigation_steps` are verifiable** — "Check `pool_size` in the settings"
   is actionable. "Investigate the database" is not. Good steps narrow the search.

When all three signals are present, the RCA result is reliable enough to start
investigation. The engineer still verifies the conclusion — the AI provides a
starting point, not a final verdict.

---

## 12. Exercise

1. **Run an RCA on INC-001 evidence** — insert 3 events matching the DB connection
   pool pattern (latency spike, http_error 500, TooManyConnectionsError) and verify
   the top candidate mentions "connection pool" with confidence > 0.7.

2. **Inspect `chain_of_thought`** — read the full `chain_of_thought` field from the
   response. Does it follow the Analyse → Hypothesise → Rank pattern? Does it cite
   specific timestamps from the timeline?

3. **Test caching** — call `POST /incidents/{id}/rca` twice. The second call should
   return `llm_provider: "cached"` and complete in < 100ms.

4. **Force a re-run** — add a new observability event, then call
   `POST /incidents/{id}/rca` with `{"force_rerun": true}`. Verify
   `observability_events_count` increases and `rca_id` changes.

5. **Compare Phase 10 vs Phase 12 on the same incident** — for an incident that has
   both `ai_summary` (Phase 10) and `rca_summary` (Phase 12) populated, compare
   the two summaries. Phase 12 should be more specific and reference the timeline.

---

## Phase 12 Summary

**What was built:**

```
RCA schema (backend/app/schemas/rca.py)
  RootCauseCandidate  — rank, cause, per-candidate confidence, supporting_evidence, reasoning
  TimelineEvent       — timestamp, source (APP/SRV), level, event_type, message
  RcaAnalysisResponse — ranked candidates, overall confidence, timeline, chain_of_thought,
                        investigation_steps, related_documentation, safety gate
  RcaRequest          — evidence_window_minutes, provider, force_rerun

ErrorLogRepository (backend/app/repositories/error_log_repository.py)
  get_errors_around_time(centre, window_minutes) — server errors around incident.detected_at

RcaService (backend/app/services/rca_service.py)
  9-step pipeline: load → collect → timeline → RAG → chain-of-thought prompt →
                   LLM → parse → safety gate → persist

Incident model + migration 0012
  5 new fields: rca_analysis_id, rca_summary, rca_confidence,
                rca_candidates_json, rca_investigation_steps_json

IncidentRepository.attach_rca() — persist RCA results

API (backend/app/api/incidents/router.py)
  POST /incidents/{id}/rca  — trigger RCA (cached by default)
  GET  /incidents/{id}/rca  — fetch cached result
```

**Connection to next phases:**
- Phase 13 (AI DevOps Assistant) uses incidents + RCA results as tool outputs
  when answering "what caused the incident last night?"
- Phase 14 (Android Dashboard) shows `root_cause_candidates` ranked list in the
  incident detail screen
- Phase 15 (AIOps) closes the loop: RCA → human reviews → approve remediation

Say `NEXT` to continue to **Phase 13 — AI DevOps Assistant**.
