# INC-003 — ChromaDB Cold Start — Empty RAG Results

**Date:** 2026-08-05
**Duration:** 8 minutes (11:02 – 11:10 UTC)
**Severity:** LOW
**Status:** RESOLVED
**Services affected:** ai-assistant-backend — RAG query and document query endpoints
**Reported by:** Manual smoke test after deployment

---

## Timeline

| Time (UTC) | Event |
|------------|-------|
| 11:00:00 | New backend revision deployed (unrelated feature change) |
| 11:02:00 | Smoke test: `POST /documents/query` returns empty results |
| 11:02:30 | No alert fired (no error rate spike — endpoint returned HTTP 200) |
| 11:04:00 | Engineer checks logs; sees ChromaDB `NOT reachable` startup warning |
| 11:05:00 | ChromaDB Cloud Run service identified as scaled to zero |
| 11:06:00 | First real request to ChromaDB triggers cold start |
| 11:10:00 | ChromaDB warm; RAG queries returning correct results |

---

## Symptoms

- `POST /documents/query` returned HTTP 200 with `answer: ""` and `citations: []`
- No 5xx errors — the endpoint succeeded but with empty results
- Startup log: `STARTUP: ChromaDB NOT reachable at chromadb:8001 — RAG queries will return empty results`
- `query_documents()` returned `QueryResult(query=..., retrieved_chunks=[], context="")`
  because `_query_chroma()` caught the connection error and returned `[]` (graceful degradation)

---

## Root Cause

ChromaDB is deployed as a Cloud Run service with `min-instances=0`. When the
backend service deployed a new revision, the startup probe sent a ChromaDB
health check during `lifespan()`. ChromaDB was scaled to zero (no traffic for
4+ hours) and took ~4 minutes to cold-start.

The backend startup check logged a warning but did NOT fail startup — by design,
ChromaDB unavailability is non-fatal and the service started successfully.

The first few RAG queries after deploy hit ChromaDB during its cold start window
and received connection refused errors. The graceful degradation path returned
empty results silently.

This is expected behaviour, but it caused silent data loss for 8 minutes that
was only caught by a manual smoke test — not automated monitoring.

---

## Evidence

1. Startup log: `STARTUP: ChromaDB NOT reachable at chromadb:8001`
2. `query_documents` returned `retrieved_chunks=[]` for a query that should have
   returned 5 results
3. ChromaDB Cloud Run service showed last invocation > 4 hours before incident
4. No Prometheus alert fired because `/documents/query` returned HTTP 200

---

## Fix Applied

1. **Immediate:** No action needed — ChromaDB warmed up on its own after 4 minutes
2. **Follow-up:** Added a smoke test to the CI/CD pipeline that calls
   `/documents/query` with a known query after every deploy and asserts
   `total_chunks > 0`
3. **Follow-up:** Added Prometheus metric `rag_retrieved_chunks_total` counter
   so we can alert on zero retrievals during business hours
4. **Follow-up:** ChromaDB deployment changed to `min-instances=0` with a
   startup probe that the backend retries for 60 seconds before giving up

---

## Lessons Learned

- Silent empty results are harder to detect than errors
- Graceful degradation is valuable but must be paired with metrics that expose
  when it is actually happening
- Health checks that run at startup only tell you about that moment — they don't
  tell you if a dependency recovers or degrades later
- Every RAG query path needs an observable counter, not just a request counter

---

## Prevention

- Post-deploy smoke test in `cloud-run-deploy.yml` checks `/documents/query` result count
- `rag_retrieved_chunks_total` metric added to `rag_service.py` (`query_documents` method)
- Alerting rule `RAGRetrievalEmpty` fires if chunk count is zero for > 5 minutes
  during business hours

---

## Note on ChromaDB persistence

ChromaDB on Cloud Run uses ephemeral storage. A new revision wipes the vector index.
The backend re-indexes knowledge base documents on startup via `seed_knowledge.py`
and re-indexes user documents via `POST /api/v1/rag/reindex` (available to admins).
This is documented in the deployment runbook.

---

## Related

- Runbook: `service-restart.md` (restart ChromaDB to force cold start resolution)
- Runbook: `cloud-run-deploy.md` (post-deploy verification steps)
- Architecture: `rag-pipeline.md` (ChromaDB ephemeral storage explanation)
