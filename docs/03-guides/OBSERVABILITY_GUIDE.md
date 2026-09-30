# Observability — Phase 8 Learning Guide

A thorough walkthrough of the three pillars of observability — structured logs,
metrics, and traces — and how each is implemented in this project.

---

## Table of Contents

1. [Why Observability?](#1-why-observability)
2. [The Three Pillars](#2-the-three-pillars)
3. [Pillar 1 — Structured Logging](#3-pillar-1--structured-logging)
4. [Pillar 2 — Metrics (Prometheus + Grafana)](#4-pillar-2--metrics-prometheus--grafana)
5. [Pillar 3 — Distributed Tracing (OpenTelemetry)](#5-pillar-3--distributed-tracing-opentelemetry)
6. [Correlation IDs — Linking All Three](#6-correlation-ids--linking-all-three)
7. [Alerting Rules](#7-alerting-rules)
8. [Loki — Log Aggregation](#8-loki--log-aggregation)
9. [Android Observability — Phase 2 Integration](#9-android-observability--phase-2-integration)
10. [The Full Observability Stack in Action](#10-the-full-observability-stack-in-action)
11. [Key Design Decisions & Trade-offs](#11-key-design-decisions--trade-offs)
12. [Interview Questions](#12-interview-questions)
13. [Exercises](#13-exercises)

---

## 1. Why Observability?

A production system without observability is a black box. When something breaks,
you don't know it until a user complains, and then you have no way to diagnose it.

**Observability** is the ability to understand the internal state of a system by
examining its external outputs. Three types of output give you this visibility:

```
Without observability:
  User: "The app is broken"
  Engineer: "I'll restart it and hope for the best"

With observability:
  Alert fires at 14:32:01 — 5xx error rate > 5% for 2 minutes
  Engineer opens Grafana — error spike on /chat/message
  Traces show: httpx call to OpenAI timing out at 30s
  Logs show: "LLM provider unavailable" in 47 consecutive requests
  Fix: switch to Gemini fallback, deploy in 3 minutes
  Total time to resolution: 8 minutes
```

The master plan's AI analysis pipeline depends entirely on observability data:
- **Phase 10** (AI Error Analysis) reads observability events from the Android app
- **Phase 11** (Anomaly Detection) runs statistical analysis over error rates
- **Phase 12** (Root Cause Analysis) correlates logs + metrics + traces
- **Phase 13** (DevOps Assistant) answers "why did the API fail at 14:32?" using all three pillars

Without good observability, the AI has nothing to analyse.

---

## 2. The Three Pillars

| Pillar | What it answers | Tool | Location |
|---|---|---|---|
| **Logs** | What happened? | Structured JSON → Cloud Logging / Loki | `app/observability/logging_setup.py` |
| **Metrics** | How is the system performing over time? | Prometheus → Grafana | `infrastructure/prometheus/` |
| **Traces** | Where did time go across a single request? | OpenTelemetry → Cloud Trace / Jaeger | `app/observability/tracing.py` |

They are complementary — each answers questions the others cannot:

```
Logs tell you: "POST /chat returned 500 at 14:32:01 for user abc-123"
Metrics tell you: "5xx rate jumped from 0.1% to 12% between 14:30 and 14:35"
Traces tell you: "The 500 was in the OpenAI call, which took 30s before timing out"
```

---

## 3. Pillar 1 — Structured Logging

### What makes a log "structured"?

```python
# ❌ Unstructured — opaque string, machine can't parse fields
logger.info("POST /chat 500 145ms user=abc-123")

# ✅ Structured — machine-parseable JSON
logger.info(
    "request",
    extra={
        "path":            "/chat",
        "method":          "POST",
        "status_code":     500,
        "response_time_ms": 145.3,
        "user_id":         "abc-123",
        "correlation_id":  "uuid-here",
    }
)
```

The `JsonFormatter` in `app/observability/logging_setup.py` converts every log
call to a single-line JSON object:

```json
{
    "timestamp":        "2026-08-26T14:32:01.123Z",
    "severity":         "ERROR",
    "message":          "request",
    "logger":           "app.middleware.logging_middleware",
    "path":             "/chat/message",
    "method":           "POST",
    "status_code":      500,
    "response_time_ms": 145.3,
    "user_id":          "abc-123",
    "correlation_id":   "8f7e3b2a-..."
}
```

Cloud Logging automatically promotes `severity`, `timestamp`, `message` to
first-class indexed columns. Every field from `extra={}` becomes a queryable label.

### `configure_logging()` — why call it first

```python
# backend/app/main.py — called BEFORE any other import
from app.observability.logging_setup import configure_logging
configure_logging()
```

If `logging.basicConfig()` runs first (triggered by an import), it installs a
plain-text formatter on the root logger. All subsequent logging calls go through
that formatter — including the JSON formatter added later. The JSON formatter
never gets a chance to format any output.

By calling `configure_logging()` first, the project owns the root logger before
any library touches it.

### Silencing noisy libraries

```python
# app/observability/logging_setup.py
for noisy_logger in ("uvicorn.access", "httpx", "httpcore", "multipart", "passlib"):
    logging.getLogger(noisy_logger).setLevel(logging.WARNING)
```

`uvicorn.access` logs every HTTP request in its own format — redundant because
`RequestLoggingMiddleware` already does this in structured JSON. Silencing it
eliminates duplicate log entries.

### The `RequestLoggingMiddleware`

```python
# app/middleware/logging_middleware.py — pure ASGI, not BaseHTTPMiddleware
class RequestLoggingMiddleware:
    async def __call__(self, scope, receive, send):
        correlation_id = str(uuid.uuid4())  # unique per request
        scope["correlation_id"] = correlation_id

        # Extract user_id from JWT (without verifying it — middleware runs before auth)
        user_id = _extract_user_id_from_scope(scope)

        start_time = time.perf_counter()
        # ... response wrapping ...
        logger.info("request", extra={
            "correlation_id": correlation_id,
            "user_id": user_id,
            "path": scope["path"],
            "status_code": status_code,
            "response_time_ms": elapsed_ms,
        })
```

**Why pure ASGI instead of `BaseHTTPMiddleware`?**

`BaseHTTPMiddleware` buffers the entire request body to make it available as a
Python object. For file uploads or streaming responses, this doubles memory usage
and adds latency. Pure ASGI middleware passes data through as raw bytes — no buffering.

### Searching logs in Cloud Logging

```bash
# All ERROR logs in the last hour
gcloud logging read \
  'resource.type="cloud_run_revision" AND severity=ERROR' \
  --limit=20 --freshness=1h

# All requests by a specific user
gcloud logging read \
  'resource.type="cloud_run_revision" AND jsonPayload.user_id="abc-123"' \
  --limit=50

# A specific request by correlation ID (full trace of one request)
gcloud logging read \
  'jsonPayload.correlation_id="8f7e3b2a-1234-5678-abcd-ef1234567890"' \
  --limit=50 --format="table(timestamp,severity,jsonPayload.message)"
```

---

## 4. Pillar 2 — Metrics (Prometheus + Grafana)

### What Prometheus does

Prometheus **scrapes** metric endpoints on a schedule (every 15 seconds). Each
scrape collects the current value of every registered metric:

```
# Prometheus scrapes http://api:8000/metrics every 15 seconds

# One example metric in the /metrics response:
http_requests_total{handler="/chat/message",method="POST",status="200"} 1234
http_requests_total{handler="/chat/message",method="POST",status="500"} 23
http_request_duration_seconds_bucket{handler="/chat/message",le="0.5"} 1150
http_request_duration_seconds_bucket{handler="/chat/message",le="2.0"} 1230
```

### `prometheus-fastapi-instrumentator`

This library auto-instruments every FastAPI route and exposes metrics at `/metrics`:

```python
# app/main.py
from prometheus_fastapi_instrumentator import Instrumentator

Instrumentator().instrument(
    app,
    latency_highr_buckets=(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0),
).expose(app)
```

Metrics automatically generated for every route:
- `http_requests_total{method, handler, status}` — request counts
- `http_request_duration_seconds{handler, le}` — latency histogram

### Custom metric in the middleware

```python
# app/middleware/logging_middleware.py
http_unhandled_exceptions_total = Counter(
    "http_unhandled_exceptions_total",
    "Total number of unhandled exceptions in HTTP request handlers.",
    labelnames=["path"],
)

# Incremented whenever a request handler throws an unhandled exception:
http_unhandled_exceptions_total.labels(path=scope["path"]).inc()
```

This metric is the basis for the `UnhandledExceptions` Prometheus alert rule.

### Prometheus metric types

| Type | Description | Example |
|---|---|---|
| **Counter** | Monotonically increasing number (only goes up) | `http_requests_total` |
| **Gauge** | A value that can go up and down | `active_connections` |
| **Histogram** | Samples and buckets them by value | `http_request_duration_seconds` |
| **Summary** | Like histogram but calculates percentiles on client side | (less common in Go/Python) |

### PromQL — Prometheus Query Language

```promql
# Error rate (fraction of requests returning 5xx)
(
  sum(rate(http_requests_total{status=~"5.."}[5m]))
  /
  sum(rate(http_requests_total[5m]))
)

# P95 latency across all routes
histogram_quantile(
  0.95,
  sum(rate(http_request_duration_seconds_bucket[5m])) by (le, handler)
)

# Requests per second
sum(rate(http_requests_total[1m]))
```

### Grafana — pre-provisioned dashboards

The `infrastructure/grafana/provisioning/` directory contains:

1. **`ai_cost_dashboard.json`** — LLM token usage and cost by provider
2. **`error_rates_dashboard.json`** — HTTP error rates (4xx, 5xx) over time
3. **`request_volume_dashboard.json`** — requests per second, latency percentiles

Grafana auto-loads these on startup via provisioning — no manual dashboard creation.
The datasources (Prometheus, Loki) are also auto-provisioned:

```yaml
# infrastructure/grafana/provisioning/datasources/datasources.yml
datasources:
  - name: Prometheus
    type: prometheus
    url: http://prometheus:9090
    isDefault: true

  - name: Loki
    type: loki
    url: http://loki:3100
```

### Start the local monitoring stack

```bash
docker compose -f docker-compose.local.yml up -d prometheus grafana loki

# Grafana: http://localhost:3000 (admin / local_grafana_password)
# Prometheus: http://localhost:9090
# Loki: http://localhost:3100
```

---

## 5. Pillar 3 — Distributed Tracing (OpenTelemetry)

### What traces show that logs can't

```
Trace: POST /chat/message  →  342ms total
  │
  ├── FastAPI route handler              2ms
  ├── SQLAlchemy: SELECT conversations  12ms
  ├── Redis: GET rate:user123:min_1      2ms
  ├── PromptBuilder.build()              1ms
  └── httpx: POST api.openai.com        325ms   ← 95% of latency here
        ├── TLS handshake               45ms
        └── API call                   280ms
```

A Prometheus alert tells you "P95 latency > 2s". A trace tells you *exactly*
which operation is slow. Without traces you'd have to add timing logs to every
function — a maintenance nightmare.

### OpenTelemetry auto-instrumentation

```python
# app/observability/tracing.py
FastAPIInstrumentor().instrument()       # adds a span for every route handler
SQLAlchemyInstrumentor().instrument()    # adds a span for every SQL query
HTTPXClientInstrumentor().instrument()   # adds a span for every outbound HTTP call
RedisInstrumentor().instrument()         # adds a span for every Redis command
```

These three lines add trace spans to hundreds of operations without any changes
to the application code. The instrumentation patches the libraries at import time.

### How spans propagate

```
Incoming request (HTTP header: traceparent: 00-abc123...-1)
      │
      ▼
FastAPI span: POST /chat/message  [trace_id=abc123]
      │
      ├── SQLAlchemy span: SELECT ...  [trace_id=abc123, parent=FastAPI span]
      │
      ├── Redis span: GET rate:...     [trace_id=abc123, parent=FastAPI span]
      │
      └── httpx span: POST openai.com [trace_id=abc123, parent=FastAPI span]
```

All spans share the same `trace_id`. In Cloud Trace, you see the full hierarchy
as a Gantt chart — which calls happened in parallel, which were sequential, and
exactly how long each one took.

### Cloud Trace export (production)

```python
# No configuration needed on Cloud Run:
from opentelemetry.exporter.cloud_trace import CloudTraceSpanExporter
exporter = CloudTraceSpanExporter()  # uses ADC automatically
```

Cloud Run's service account has `roles/cloudtrace.agent` (granted in
`terraform/modules/iam/main.tf`), so traces are exported without any
credentials file.

### Jaeger export (local development)

```yaml
# Add to docker-compose.local.yml for local trace exploration:
jaeger:
  image: jaegertracing/all-in-one:1.57
  ports:
    - "16686:16686"  # Jaeger UI
    - "4317:4317"    # OTLP gRPC
```

```bash
# In .env.local:
OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317
```

With Jaeger running, every request produces a trace visible at `http://localhost:16686`.

### Why `OTEL_ENABLED=false` in unit tests

```python
# tracing.py
enabled = os.environ.get("OTEL_ENABLED", "true").lower() in ("true", "1", "yes")
if not enabled:
    return  # no-op
```

Unit tests shouldn't need a running OTLP endpoint. Setting `OTEL_ENABLED=false`
in `tests/conftest.py` (or via environment) makes `setup_tracing()` a no-op —
no network calls, no timing overhead.

---

## 6. Correlation IDs — Linking All Three

A correlation ID links a log entry, a metric label, and a trace span to the
same HTTP request. This is what makes "I see an error, let me find the trace
and then the logs" possible.

```
                 ┌─────────────────────────────────────────────┐
Request arrives  │ correlation_id = "8f7e3b2a-..."              │
─────────────────┤                                              │
                 │  Log:                                         │
                 │    {"correlation_id": "8f7e3b2a", "status": 500}
                 │                                              │
                 │  Trace span:                                 │
                 │    span.set_attribute("correlation_id", "8f7e3b2a")
                 │                                              │
                 │  Response header:                            │
                 │    X-Correlation-ID: 8f7e3b2a               │
                 └─────────────────────────────────────────────┘
```

The `X-Correlation-ID` header is returned to the client. If a user reports a
problem, they can provide this header value and the engineer can find every
trace, log line, and metric for that exact request.

```bash
# Find all logs for one request
gcloud logging read 'jsonPayload.correlation_id="8f7e3b2a-..."' --limit=50
```

---

## 7. Alerting Rules

The `infrastructure/prometheus/alerting.rules.yml` defines 9 alert rules grouped
into four categories:

### Category 1 — HTTP errors

| Alert | Condition | Severity |
|---|---|---|
| `HighHTTP5xxErrorRate` | > 5% of requests return 5xx for 2 minutes | critical |
| `HighHTTP4xxErrorRate` | > 20% of requests return 4xx for 5 minutes | warning |
| `UnhandledExceptions` | Any unhandled exception in handlers | critical |

### Category 2 — Latency

| Alert | Condition | Severity |
|---|---|---|
| `HighP95Latency` | P95 response time > 2s for any route for 3 minutes | warning |
| `CriticalP99Latency` | P99 response time > 10s globally for 2 minutes | critical |

### Category 3 — LLM cost

| Alert | Condition | Severity |
|---|---|---|
| `LLMCostSpike` | > $0.10/minute LLM spend for 5 minutes | critical |
| `LLMHighTokenUsage` | > 10,000 output tokens/minute for 5 minutes | warning |

### Category 4 — Celery + availability

| Alert | Condition | Severity |
|---|---|---|
| `HighCeleryTaskFailureRate` | Task failure rate > 0.05/s for 2 minutes | warning |
| `BackendDown` | Backend `/metrics` unreachable for 1 minute | critical |

### How the `for` duration prevents false positives

```yaml
alert: HighHTTP5xxErrorRate
expr: (sum(rate(http_requests_total{status=~"5.."}[5m])) / ...) > 0.05
for: 2m   # ← must be true for 2 CONSECUTIVE minutes before alerting
```

Without `for: 2m`, a single failed request during a quiet period could trigger
the alert. The `for` duration adds a stabilisation window — transient spikes
(a deployment restart, one bad request) don't fire alerts.

### Testing rules locally

```bash
# Validate syntax
promtool check rules infrastructure/prometheus/alerting.rules.yml

# Unit test (requires test fixtures file)
promtool test rules infrastructure/prometheus/tests/alerting_test.yml
```

---

## 8. Loki — Log Aggregation

Loki is Grafana's log aggregation service. It receives structured logs from the
backend via the `python-logging-loki` handler and makes them searchable in
Grafana alongside metrics.

### How logs reach Loki

```
RequestLoggingMiddleware.ensure_loki_handler()
  │  (lazy init — only if LOKI_URL is set)
  │
  ▼
LokiHandler (python-logging-loki)
  │  POST to http://loki:3100/loki/api/v1/push
  ▼
Loki → stores in boltdb-shipper → filesystem

Grafana → queries Loki via LogQL
```

### LogQL — Loki's query language

```logql
# All ERROR logs
{application="android-ai-assistant"} | json | severity = "ERROR"

# Logs for a specific user
{application="android-ai-assistant"} | json | user_id = "abc-123"

# Logs containing "timeout"
{application="android-ai-assistant"} | json | message =~ ".*timeout.*"

# Rate of error logs over time (for Grafana graphs)
sum(rate({application="android-ai-assistant"} | json | severity="ERROR" [5m]))
```

### Why `max_label_names_per_series: 30`

Loki has a default limit of 15 labels per log stream. The backend's structured
logs include: `application`, `environment`, `severity`, `logger`, `path`,
`method`, `status_code`, `response_time_ms`, `correlation_id`, `user_id`.
That's 10 labels, with room to grow. The limit was raised to 30 in
`loki-config.yml` to prevent silently dropping labels.

---

## 9. Android Observability — Phase 2 Integration

The Android client (Phase 2) uploads structured events to the backend at
`POST /api/v1/observability/events` (no auth required).

### The complete flow

```
Android app (ObservabilityManager)
  │  captures events: HTTP errors, crashes, screen views, API latency
  │  buffers in memory (max 500 events)
  │  drains every 15 minutes via WorkManager
  │
  ▼
POST /api/v1/observability/events
  │  {events: [{timestamp, level, eventType, message, sessionId, ...}]}
  │
  ▼
ObservabilityEventRepository.bulk_insert()
  │  persists to PostgreSQL observability_events table
  │
  ▼
Phase 10: AI Error Analysis reads recent ERROR events
  │  correlates by sessionId, traceId
  │  retrieves runbooks from ChromaDB
  │  sends to LLM → ErrorAnalysisResponse
  │
  ▼
Phase 11: Anomaly Detection (Celery Beat, every 60s)
  │  ObservabilityEventRepository.count_errors_in_window()
  │  ObservabilityEventRepository.compute_event_rate_stats()
  │  if threshold exceeded → create Incident
  │
  ▼
Phase 12: Root Cause Analysis
  │  correlates Android events + server logs + deployment changes
  │  → RcaAnalysisResponse with ranked candidates + confidence
  │
  ▼
Phase 13: DevOps Assistant
     "Why did the API fail at 14:32?"
     → search_logs("connection refused", service="api")
     → get_metrics("error_rate", start=14:30, end=14:35)
     → search_incidents(severity="CRITICAL", status="OPEN")
     → LLM reasoning → grounded answer with citations
```

### The session and trace IDs

Android events carry a `sessionId` (stable per app launch) and optionally a
`traceId` (scoped to one user action). On the backend, these same IDs appear
in the structured logs emitted by `RequestLoggingMiddleware`.

This means the AI can say: "The 500 error on the backend (correlation_id=xyz,
which was triggered by Android session sess-abc, trace trace-def) was preceded
by 3 consecutive network_timeout events from the same session 30 seconds earlier."

---

## 10. The Full Observability Stack in Action

Tracing a production incident using all three pillars:

```
14:32:01 — User reports: "chat is broken"
      │
      ▼
Step 1: Check Prometheus / Grafana
  → error_rates_dashboard shows: 5xx rate jumped to 12% at 14:31:45
  → P99 latency shows: 31s (OpenAI timeout)
      │
      ▼
Step 2: Check the alert
  → HighHTTP5xxErrorRate fired at 14:33:45 (2 min stabilisation)
  → Route: /chat/message
      │
      ▼
Step 3: Check Cloud Logging
  gcloud logging read 'jsonPayload.path="/chat/message" AND severity=ERROR' --freshness=1h
  → 47 log entries, all at 14:31:45–14:35:22
  → "LLM provider unavailable: openai"
  → correlation_id: "8f7e3b2a-..."
      │
      ▼
Step 4: Check the trace for one failing request
  Cloud Trace → search by correlation_id or by "8f7e3b2a"
  → httpx span "POST api.openai.com" took 30.001s → timeout
  → FastAPI route span shows total 30.1s
  → No SQLAlchemy or Redis slowness
      │
      ▼
Step 5: Check Android observability events
  SELECT * FROM observability_events
    WHERE session_id IN (sessions active at 14:31-14:35)
    AND level IN ('ERROR', 'CRITICAL')
    ORDER BY timestamp_ms;
  → 89 "network_error" events from Android clients
  → All point to /chat/message returning 500
      │
      ▼
Conclusion (3 minutes after incident):
  OpenAI API was returning 503 (capacity issue on their end).
  The backend's 30-second timeout for LLM calls let requests pile up.

Fix:
  1. Add circuit breaker: if OpenAI returns 503 twice in 30s, switch to Gemini
  2. Reduce LLM_RATE_LIMIT_OPENAI to 20 (avoid hitting capacity)
  3. Add LLM_FALLBACK_PROVIDER=gemini (already set — but the circuit breaker
     wasn't triggering fast enough)
```

---

## 11. Key Design Decisions & Trade-offs

### 11.1 Structured JSON to stdout vs a logging library

Cloud Logging and Loki both accept structured data, but they expect it in
different formats. The `JsonFormatter` outputs to stdout in Cloud Logging's
expected JSON format. The `LokiHandler` sends directly to Loki's push API
with labels.

By making stdout the primary output and Loki secondary (lazy-loaded only when
`LOKI_URL` is set), the system works in all environments: local development,
CI (no Loki), staging (both), production Cloud Run (stdout → Cloud Logging).

### 11.2 Histogram buckets tuned for AI workloads

```python
Instrumentator().instrument(
    app,
    latency_highr_buckets=(0.05, 0.1, 0.2, 0.5, 1.0, 2.0, 5.0),
)
```

Standard web applications have latency < 500ms. LLM calls take 1-15 seconds.
The histogram buckets extend to 5.0 seconds to capture meaningful percentiles
for LLM-backed endpoints. Without the 2.0 and 5.0 buckets, the P95/P99 latency
calculations for `/chat/message` would be inaccurate.

### 11.3 Auto-instrumentation vs manual spans

Auto-instrumentation (FastAPI, SQLAlchemy, httpx, Redis) adds spans to hundreds
of operations with four lines. Manual spans would require instrumenting every
service function individually — hundreds of code changes with high maintenance burden.

The trade-off: auto-instrumentation produces spans you didn't ask for (Redis
pipeline commands, SQLAlchemy connection pool events). These add noise to traces
but the signal-to-noise ratio is still high enough to be useful.

### 11.4 Fail-open Redis availability for rate limiting

```python
# app/middleware/rate_limit.py
try:
    count = await redis_client.incr(key)
except Exception:
    # Redis unavailable — fail open (allow request through)
    return await self.app(scope, receive, send)
```

When Redis is down, rate limiting silently allows all requests through rather
than returning 503 to every user. For a portfolio project, denying all traffic
because a cache is down would be a worse experience than temporarily not enforcing
rate limits. For a financial or healthcare system, fail-closed would be correct.

### 11.5 Sampling strategy for traces

The current configuration traces 100% of requests. For a low-traffic system,
this is fine. For high-traffic production:

```python
# Add a sampler to reduce trace volume:
from opentelemetry.sdk.trace.sampling import TraceIdRatioBased
provider = TracerProvider(
    sampler=TraceIdRatioBased(0.1),  # trace 10% of requests
    resource=resource,
)
```

100% sampling on high traffic generates enormous trace data costs and storage.
A common strategy: 100% for errors (always trace failures), 5-10% for success.

---

## 12. Interview Questions

**1. What is the difference between logs, metrics, and traces? When would you use each?**

Logs are time-stamped records of discrete events — "request X failed with error Y".
Use them to debug specific incidents. Metrics are numerical measurements over time
— "5% of requests failed in the last 5 minutes". Use them for dashboards and alerts.
Traces are end-to-end recordings of a single request's journey through all services.
Use them to find latency bottlenecks and understand cause-effect relationships.

You typically use all three together: metrics alert you to a problem, logs tell you
what failed, traces tell you where time was spent.

**2. Why emit JSON logs instead of plain text?**

Plain text logs are human-readable but machine-unreadable. A log line like
`"POST /chat 500 145ms"` requires regex parsing to extract fields. JSON logs
allow Cloud Logging, Loki, and Elasticsearch to index individual fields and
serve queries like "all requests > 1 second returning 500 for user abc-123"
without parsing every log line. JSON also adds metadata automatically (timestamp,
severity, logger name) without changing the calling code.

**3. What is a Prometheus histogram and how do you calculate P95 latency from it?**

A histogram samples observations (e.g. request durations) and counts them
into configurable buckets (e.g. < 0.5s, < 1.0s, < 2.0s). Each scrape gives
you the cumulative count in each bucket. P95 latency means "95% of requests
completed in under X seconds". In PromQL:

```promql
histogram_quantile(0.95, sum(rate(http_request_duration_seconds_bucket[5m])) by (le))
```

The `le` label means "less than or equal to" the bucket boundary. `histogram_quantile`
interpolates between bucket boundaries to estimate the actual percentile.

**4. What is OpenTelemetry and why is it preferred over vendor-specific SDKs?**

OpenTelemetry is a vendor-neutral standard for collecting and exporting traces,
metrics, and logs. It separates the instrumentation API (how you emit telemetry)
from the exporter (where it goes). With OpenTelemetry, you write instrumentation
code once and can export to Jaeger, Cloud Trace, Datadog, New Relic, or any other
backend by swapping the exporter — no code changes. Vendor-specific SDKs lock you
in: switching from Datadog to Jaeger requires rewriting all instrumentation.

**5. What is a correlation ID and how does it work in this project?**

A correlation ID is a UUID generated per request that appears in every log line,
every trace span, and the `X-Correlation-ID` response header for that request.
In `RequestLoggingMiddleware`, the UUID is generated with `uuid.uuid4()`, stored
in the ASGI scope, added to every log call via `extra={"correlation_id": id}`,
and returned as a response header.

When a user reports a problem and provides the correlation ID from their browser's
network tab, the engineer can run a single log query to find every log line from
that exact request — across middleware, route handlers, and background tasks.

---

## 13. Exercises

**Exercise 1 — View live traces in Grafana**

1. Start the local stack: `docker compose -f docker-compose.local.yml up -d`
2. Add Jaeger to `docker-compose.local.yml`:
   ```yaml
   jaeger:
     image: jaegertracing/all-in-one:1.57
     ports:
       - "16686:16686"
       - "4317:4317"
   ```
3. Add `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317` to `.env.local`
4. Make a few API calls
5. Open `http://localhost:16686` → search for service "ai-assistant-backend"
6. Find the slowest trace — which operation took the most time?

**Exercise 2 — Write a PromQL query for a new metric**

Add a custom Prometheus counter to `app/middleware/rate_limit.py` that counts
rate-limited requests per user:

```python
rate_limited_requests_total = Counter(
    "rate_limited_requests_total",
    "Total requests that were rate-limited.",
    labelnames=["user_id"],
)
```

Then write a PromQL query that shows the rate of rate-limited requests per minute
for each user, averaged over 5 minutes. Open `http://localhost:9090` and test it.

**Exercise 3 — Add a new alert rule**

Add an alert that fires when a single user makes more than 100 requests in
5 minutes (potential abuse):

```yaml
- alert: SingleUserHighRequestRate
  expr: |
    sum(rate(http_requests_total[5m])) by (user_id) > 0.33
  for: 2m
  labels:
    severity: warning
  annotations:
    summary: "High request rate from user {{ $labels.user_id }}"
    description: >
      User {{ $labels.user_id }} is making {{ $value | humanize }} requests/second.
```

Validate the rule: `promtool check rules infrastructure/prometheus/alerting.rules.yml`

**Exercise 4 — Query structured logs in Cloud Logging**

After deploying to Cloud Run, use Cloud Logging to answer these questions:
1. What is the average `response_time_ms` for `POST /chat/message` in the last 24 hours?
2. How many requests returned status 401 today?
3. Find the correlation ID of the slowest request in the last hour

```bash
# Cloud Logging query for question 1:
gcloud logging read \
  'resource.type="cloud_run_revision" AND jsonPayload.path="/chat/message"' \
  --limit=1000 \
  --format="csv(jsonPayload.response_time_ms)"
```

**Exercise 5 — Understand the `for` duration in alert rules**

1. Open `http://localhost:9090` (Prometheus)
2. Temporarily change `HighHTTP5xxErrorRate` `for: 2m` to `for: 10s` in
   `alerting.rules.yml` and reload Prometheus:
   ```bash
   curl -X POST http://localhost:9090/-/reload
   ```
3. Send a failing request: `curl http://localhost:8000/nonexistent` (404)
4. Watch the alert in `http://localhost:9090/alerts` — it will transition
   from `inactive` → `pending` → `firing`
5. With `for: 10s`, how quickly does it fire?
6. Reset to `for: 2m` and explain why the longer duration is safer in production
