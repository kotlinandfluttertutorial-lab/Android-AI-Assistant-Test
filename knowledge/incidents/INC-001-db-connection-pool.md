# INC-001 — Database Connection Pool Exhaustion

**Date:** 2026-07-14
**Duration:** 47 minutes (14:32 – 15:19 UTC)
**Severity:** HIGH
**Status:** RESOLVED
**Services affected:** ai-assistant-backend, all API endpoints
**Reported by:** Prometheus alert `HighHTTP5xxErrorRate`

---

## Timeline

| Time (UTC) | Event |
|------------|-------|
| 14:32:01 | First `asyncpg ConnectionDoesNotExistError` in logs |
| 14:32:15 | Error rate crosses 5% threshold; `HighHTTP5xxErrorRate` alert fires |
| 14:33:00 | Incident auto-created by monitoring |
| 14:35:00 | On-call engineer receives page |
| 14:38:00 | Engineer identifies connection pool exhaustion in logs |
| 14:42:00 | Root cause confirmed: slow LLM calls holding DB connections open |
| 14:55:00 | Mitigation deployed: pool_size increased from 5 → 15, LLM timeout reduced to 30s |
| 15:19:00 | Error rate returns to < 0.1%; incident closed |

---

## Symptoms

- HTTP 500 errors on all POST endpoints (`/chat`, `/documents/query`, `/rag/query`)
- GET endpoints (`/health`, `/documents`) remained operational
- Error rate: 23% at peak
- Log pattern:
  ```
  asyncpg.exceptions.TooManyConnectionsError: sorry, too many clients already
  sqlalchemy.exc.OperationalError: connection pool exhausted
  ```
- P95 latency: 8.3s (baseline: 145ms)
- Prometheus metric: `celery_queue_depth` showed 0 — not a Celery issue

---

## Root Cause

The AI chat endpoint (`POST /api/v1/chat`) held a SQLAlchemy DB connection open
for the entire duration of the LLM call. LLM calls to OpenAI were taking 15–45
seconds due to long streaming responses.

With `pool_size=5` and `max_overflow=10` (15 total connections), and 40 concurrent
requests per instance, all 15 connections were held by requests waiting for LLM
responses. New requests could not acquire a connection and failed with 500.

**Contributing factor:** `startup_cpu_boost=true` brought the service from 0 to
3 instances simultaneously during a traffic spike, which tripled the connection
demand instantly.

---

## Evidence

1. Log timestamp correlation: `TooManyConnectionsError` always preceded by a spike
   in `http_request_duration_seconds` for `/api/v1/chat`
2. Neon console showed 45/100 connections used (pool * instances)
3. OpenAI API latency at incident time: avg 22s (normal: 3–8s)
4. Trace: `httpx POST api.openai.com` span held open for 28s while SQLAlchemy
   session remained checked out

---

## Fix Applied

1. **Immediate:** Increased `pool_size` from 5 to 15 in `database/__init__.py`
2. **Immediate:** Added `asyncio.wait_for(..., timeout=30.0)` around all LLM calls
   (already present in `query_documents` — added to chat handler)
3. **Follow-up:** Refactored chat handler to release DB connection before the LLM
   call and re-acquire after. DB session is now checked in/out within narrow scopes.
4. **Follow-up:** Added `pool_timeout=10` to SQLAlchemy engine config so requests
   fail fast (HTTP 503) rather than queuing indefinitely

---

## Lessons Learned

- Never hold a DB connection open across a slow external API call
- Pool size must account for max-instances × concurrency × average hold time
- LLM timeout must be set everywhere — not just in the RAG endpoint
- `pool_pre_ping=True` masks the real problem (reconnects silently); add pool metrics

---

## Prevention

- Prometheus alert `HighHTTP5xxErrorRate` now has a lower threshold (2% not 5%)
- Added `db_connection_pool_used` gauge metric to expose pool utilisation
- Architecture review: all handlers must release DB session before any external call

---

## Related

- Runbook: `database-recovery.md` (Recovery D — pool exhaustion)
- Runbook: `scaling.md` (increase max-instances to spread pool load)
