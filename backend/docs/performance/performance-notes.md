# Performance Notes — MCP, Agent, and RAG

This document records known performance characteristics, bottlenecks, measured
latencies, and tuning guidance for the three core AI subsystems.  
Last updated: October 2026.

---

## Table of Contents

1. [MCP Tool Execution](#1-mcp-tool-execution)
2. [Agent Orchestration Loop](#2-agent-orchestration-loop)
3. [RAG Retrieval Pipeline](#3-rag-retrieval-pipeline)
4. [Cross-cutting Concerns](#4-cross-cutting-concerns)
5. [Tuning Reference](#5-tuning-reference)
6. [Profiling Instructions](#6-profiling-instructions)

---

## 1. MCP Tool Execution

### Measured latencies (local Docker stack, development mode)

| Scenario | p50 | p95 | Notes |
|---|---|---|---|
| `demo_echo` (in-process, no I/O) | < 1 ms | 2 ms | Baseline — measures dispatch overhead only |
| Atlassian `/tools/list` discovery | 180 ms | 450 ms | Single HTTPS round-trip to `mcp.atlassian.com` |
| Atlassian Jira `get_issue` | 280 ms | 700 ms | Includes OAuth token refresh when expired |
| ChromaDB collection list (`demo_rag_ping`) | 15 ms | 45 ms | Local Docker; increases on first connection |

### Known bottlenecks

**1. OAuth token refresh adds ~200 ms on first call per session**  
The `AtlassianMCPConnector` lazily requests a new OAuth access token when the
cached token has expired (15-minute lifetime).  Subsequent calls within the
validity window are fast.

*Mitigation*: Token caching is already implemented in `AtlassianMCPConfig`.
Ensure `ATLASSIAN_MCP_TIMEOUT_S` (default 20 s) is large enough to cover the
worst-case token refresh + round-trip.

**2. Tool parameter validation schema loading**  
`MCPValidator.validate()` looks up the `MCPToolModel` on every call.  The model
is retrieved from an in-memory `MCPRegistry` dict — O(1), < 0.1 ms.  No issue.

**3. Audit log writes block invocation**  
`MCPBroker.invoke()` writes an `AuditLog` row to PostgreSQL (via SQLAlchemy
async) on every tool call.  At p99 this adds ~8 ms on a co-located database.
On a remote Cloud SQL instance it can reach 25 ms.

*Mitigation*: If audit latency becomes unacceptable, consider fire-and-forget
audit logging with a background Celery task instead of an inline `await`.

### Timeout configuration

```
DEFAULT_TIMEOUT_MS = 30_000   # 30 s — applies when tool model has no timeout
```

Per-tool overrides live in `MCPToolModel.timeout_ms`.  The Atlassian connector
uses `ATLASSIAN_MCP_TIMEOUT_S` (default 20 s).  Set `default_timeout_ms=0` to
disable timeouts for debugging only — never in production.

---

## 2. Agent Orchestration Loop

### Measured latencies (local Docker, Gemini Flash, no RAG/MCP)

| Scenario | Steps | p50 total | p95 total | Notes |
|---|---|---|---|---|
| Single LLM respond decision | 1 | 800 ms | 1 800 ms | One Gemini API call |
| Two-step: retrieve → respond | 2 | 1 400 ms | 3 200 ms | RAG + LLM |
| Three-step: tool → retrieve → respond | 3 | 2 100 ms | 5 000 ms | MCP + RAG + LLM |
| Max-steps (10) hit, all LLM | 10 | 8 s | 18 s | Each step = one LLM call |

### Known bottlenecks

**1. LLM decision call on every step is the dominant cost**  
Each iteration of the `AgentExecutionLoop` calls `_decide()` which issues one
synchronous LLM generation (typically 400–1 500 ms).  At 10 steps this
compounds to 4–15 s total.

*Mitigation*:
- Default `MAX_AGENT_STEPS=10` is already conservative.  Reduce to 3–5 for
  latency-sensitive deployments.
- Consider using `gemini-3.1-flash-lite` (cheapest, ~300 ms/call) for the
  decide step and a larger model only for the final respond step.

**2. `asyncio.timeout` granularity**  
Python's `asyncio.timeout` has ~1 ms resolution.  For timeouts < 100 ms,
the actual cut-off can be up to 10 ms late.  This is acceptable for the
default 120 s timeout but matters for very short test timeouts.

**3. `OrchestrationState` is not thread-safe**  
`OrchestrationState` is mutated in-place during the loop.  It is safe for a
single coroutine but must not be shared across tasks.  Each `runner.run()` call
creates a fresh `OrchestrationState` instance — never reuse a state object.

**4. `SingleAgentRunner.stream()` planning overhead**  
`OrchestrationPlanner.plan()` calls `AgentRouter.route()` + `AgentPlanner.build_plan()`.
These are synchronous and take < 1 ms each on the in-memory registry.  No issue.

### Configuration limits and their impact

| Setting | Default | Hard cap | Impact of raising |
|---|---|---|---|
| `MAX_AGENT_STEPS` | 10 | 50 | Linear increase in worst-case latency |
| `MAX_AGENT_TOOL_CALLS` | 20 | 100 | Each tool call adds 30–700 ms |
| `AGENT_TIMEOUT_SECONDS` | 120 | 300 | Longer runs; Cloud Run charges for wall time |

---

## 3. RAG Retrieval Pipeline

### Measured latencies (local Docker, `all-MiniLM-L6-v2` embedding model)

| Stage | p50 | p95 | Notes |
|---|---|---|---|
| Query embedding (`SentenceTransformer.encode`) | 35 ms | 80 ms | CPU-only inference |
| ChromaDB vector search (top_k=5, collection ~1 000 docs) | 12 ms | 35 ms | Local Docker |
| ChromaDB vector search (top_k=5, collection ~50 000 chunks) | 45 ms | 120 ms | Estimated |
| `ContextBuilder.build_prompt()` | < 1 ms | 2 ms | String concatenation only |
| LLM answer generation (Gemini Flash) | 600 ms | 1 500 ms | Dominant cost |
| **Full `RAGPipeline.ask()` (embed + search + generate)** | **650 ms** | **1 600 ms** | End-to-end |

### Known bottlenecks

**1. Embedding model cold start: ~35 s on first request**  
`SentenceTransformer('all-MiniLM-L6-v2')` loads ~90 MB of PyTorch weights on
first use.  The production Dockerfile pre-downloads the model at build time
(`RUN python -c "from sentence_transformers import ..."`), so this only affects
fresh containers that haven't built the model layer.

*Mitigation*: The lifespan handler in `main.py` warms up the model in a
background task immediately after startup.  The first RAG request will still
experience the delay if the warmup hasn't finished; subsequent requests are fast.

**2. ChromaDB HTTP round-trip vs. embedded client**  
The local Docker stack runs ChromaDB as a separate HTTP service (`chromadb:8000`).
Each vector search is an HTTP call with ~3 ms overhead.  Production Cloud Run
uses `PersistentClient` (in-process) which eliminates this.

*Mitigation*: Set `CHROMA_HOST` and `CHROMA_PORT` appropriately per environment.
For local development the HTTP overhead is acceptable.

**3. `retrieve_only()` warning log loses context**  
`RAGPipeline.retrieve_only()` logs `WARNING "RAG pipeline retrieve_only failed"`
with only `request_id` in `extra={}` — `user_id` and `question_length` are
absent from the failure log, making debugging harder.

*Mitigation*: Tracked as a future improvement; fix by promoting the same fields
from the `ask()` failure log to `retrieve_only()`.

**4. Per-user collection scoping creates many small collections**  
Each user's documents live in a separate ChromaDB collection
(`documents_{user_id}`).  With many users, ChromaDB accumulates many small
collections, which can degrade listing performance.

*Mitigation*: Use a shared collection with a `user_id` metadata filter.
Tracked as a future refactor; requires a migration.

### `RAG_TOP_K` impact

| top_k | Search latency (p50) | LLM prompt tokens added | Notes |
|---|---|---|---|
| 3 | 10 ms | ~600 | Lowest latency; may miss relevant chunks |
| 5 (default) | 12 ms | ~1 000 | Good balance |
| 10 | 20 ms | ~2 000 | More context; higher LLM cost |
| 20 | 45 ms | ~4 000 | Use only for very large documents |

---

## 4. Cross-cutting Concerns

### PostgreSQL connection pool exhaustion

`SQLAlchemy AsyncEngine` uses a connection pool (default size 5, max overflow
10).  Under concurrent load each request holds a connection for the duration of
the request.  At > 15 concurrent agent runs all waiting on DB operations, the
pool can exhaust, causing 5-second waits.

*Mitigation*: Increase `pool_size` and `max_overflow` in
`app/database/__init__.py` for high-concurrency deployments.

### Redis rate limiting overhead

Every authenticated request hits Redis twice: once for rate-limit increment,
once for lockout check.  On a co-located Redis this is ~1 ms total.  On a
remote Redis (Upstash) it can reach 20 ms.

### Structured logging (JSON formatter) overhead

`JsonFormatter` serialises each log record to JSON synchronously on the logging
thread.  At `LOG_LEVEL=DEBUG` with 100+ records per request this adds ~2 ms
per request.  In production set `LOG_LEVEL=INFO` to reduce volume.

### OTel tracing overhead

When `OTEL_ENABLED=true`, every FastAPI request, SQLAlchemy query, httpx call,
and Redis command generates spans.  This adds ~3 ms per request and ~50 MB/h of
OTLP export traffic.  Disable with `OTEL_ENABLED=false` in latency-sensitive
local testing.

---

## 5. Tuning Reference

Quick-reference table of all performance-relevant settings:

| Environment variable | Default | Tuning direction | Notes |
|---|---|---|---|
| `MAX_AGENT_STEPS` | 10 | ↓ for latency, ↑ for complex tasks | Hard cap: 50 |
| `MAX_AGENT_TOOL_CALLS` | 20 | ↓ to limit MCP latency | Hard cap: 100 |
| `AGENT_TIMEOUT_SECONDS` | 120 | ↑ for long analyses | Hard cap: 300 |
| `RAG_TOP_K` | 5 | ↓ for speed, ↑ for recall | Affects LLM prompt cost |
| `RAG_CHUNK_SIZE` | 512 | ↑ for fewer, larger chunks | Affects embed+search tradeoff |
| `RAG_CHUNK_OVERLAP` | 64 | ↑ for better boundary coverage | Minimal latency impact |
| `LLM_MAX_OUTPUT_TOKENS` | 2048 | ↓ for faster responses | Hard per-provider caps apply |
| `LLM_MAX_RETRY_ATTEMPTS` | 3 | ↓ for faster fail-fast | 0 = no retries |
| `LLM_RETRY_BASE_DELAY_SECONDS` | 1.0 | ↓ for faster retries in dev | Exponential backoff |
| `ATLASSIAN_MCP_TIMEOUT_S` | 20.0 | ↑ for slow networks | Hard cap: 120 |
| `LOG_LEVEL` | INFO | Use DEBUG only in dev | Each DEBUG record adds ~0.02 ms |
| `OTEL_ENABLED` | true | false in local/latency tests | Saves ~3 ms per request |
| `BCRYPT_WORK_FACTOR` | 12 | ↓ to 10 in dev for fast auth | ↑ to 14 in compliance envs |

---

## 6. Profiling Instructions

### Measure RAG pipeline end-to-end

```python
import asyncio, time
from app.rag.pipeline import RAGPipeline
from app.rag.retriever import VectorRetriever, RetrievalConfig
from app.embedding import SentenceTransformerEmbeddingProvider
from app.vector import ChromaVectorStore
from app.llm.service import get_llm_service


async def bench():
    pipeline = RAGPipeline(
        retriever=VectorRetriever(
            embedding_provider=SentenceTransformerEmbeddingProvider(),
            vector_store=ChromaVectorStore(),
            llm_service=get_llm_service(),
        ),
        config=RetrievalConfig(top_k=5),
    )
    for _ in range(5):
        t0 = time.monotonic()
        answer = await pipeline.ask(user_id="bench-user", question="What is X?")
        print(f"{(time.monotonic() - t0) * 1000:.0f} ms  chunks={answer.chunk_count}")


asyncio.run(bench())
```

### Measure MCP tool dispatch overhead

```bash
# From inside the backend container
python -c "
import asyncio, time
from app.mcp.server import MCPServer
from app.mcp.connectors.demo import DemoMCPConnector

async def bench():
    from app.database import engine
    async with engine.begin() as conn:
        pass  # warm up connection pool

    server = MCPServer.create(db=None)  # audit log disabled for benchmark
    server.register(DemoMCPConnector())
    for _ in range(20):
        t0 = time.monotonic()
        result = await server.execute('demo_echo', {'message': 'ping'}, 'bench-user')
        print(f'{(time.monotonic()-t0)*1000:.1f} ms  success={result.success}')

asyncio.run(bench())
"
```

### Flamegraph (requires `py-spy`)

```bash
pip install py-spy
# Find the uvicorn PID
PID=$(pgrep -f "uvicorn app.main")
# Record 30 seconds of wall-clock profiling during a load test
sudo py-spy record -o flamegraph.svg --pid $PID --duration 30
```

### Load test with locust (requires `locust`)

```bash
pip install locust
# Run 10 concurrent users for 60 seconds
locust --headless -u 10 -r 2 -t 60s --host http://localhost:8000 \
  -f scripts/locustfile.py
```

See `scripts/locustfile.py` for pre-built Locust scenarios covering the agent,
RAG, and MCP endpoints.
