# Phase 8 — Observability Guide

> **Learning goal:** Understand the three pillars of observability, know what this
> project instruments, and be able to query each pillar to diagnose a production issue.
>
> **Career connection:** Every senior backend/DevOps/SRE role asks "how do you debug
> a production problem?" Observability is the answer. This guide gives you the vocabulary
> and the working examples.

---

## 1. Concept — What Is Observability?

**Monitoring** asks a pre-defined question: "Is CPU above 80%?"
**Observability** lets you ask questions you haven't thought of yet.

A system is observable when you can understand its internal state purely from its
external outputs — without modifying the code or connecting a debugger.

The three outputs that make a system observable are called **the three pillars**:

```
┌─────────────────────────────────────────────────────────────────┐
│  LOGS          │  METRICS         │  TRACES                     │
│                │                  │                             │
│  What happened │  How is the      │  Where did time go?         │
│  at 14:32:01?  │  system behaving │  Which service was slow?    │
│                │  over time?      │                             │
│  "DB connection│  error_rate=2.3% │  POST /chat took 800ms:     │
│  refused at    │  p95_latency=    │  ├── DB query     12ms      │
│  14:32:01"     │  145ms           │  ├── Redis        2ms       │
│                │  cpu=68%         │  └── OpenAI call  786ms ← ! │
└─────────────────────────────────────────────────────────────────┘
```

Without all three you are flying blind:
- **Logs without metrics**: you see errors but can't tell if it's 1 or 10,000/minute
- **Metrics without logs**: the error rate is high but you don't know why
- **Logs + metrics without traces**: you know something is slow but not which service

---

## 2. Why — Production Systems Use All Three

A real incident investigation follows this pattern:

```
1. Alert fires         (metrics)   → error rate > 5%
2. Which endpoint?     (metrics)   → POST /chat is the worst offender
3. What's the error?   (logs)      → "Connection pool exhausted"
4. Why now?            (traces)    → OpenAI latency 10x higher than baseline,
                                     holding DB connections open waiting for response
5. Fix                              → increase connection pool size, add LLM timeout
```

Each pillar answered a different question. Without all three, step 4 is a guess.

---

## 3. Architecture — How This Project's Observability Is Wired

```
Android App
  │
  ├── NetworkObservabilityInterceptor    → ObservabilityEventBus
  ├── AppLifecycleObserver               → ObservabilityEventBus
  ├── ObservabilityNavTracker            → ObservabilityEventBus
  │
  ▼
  ObservabilityManager (in-memory buffer, max 500 events)
  │
  ▼
  ObservabilityUploadWorker (WorkManager, every 15 min)
  │  POST /api/v1/observability/events
  ▼

Backend (FastAPI on Cloud Run)
  │
  ├── LOGS ────────────────────────────────────────────────────────
  │   RequestLoggingMiddleware        → stdout (JSON)
  │   JsonFormatter (logging_setup.py)→ Cloud Logging (auto-parsed)
  │   LokiHandler                     → Loki (docker-compose.prod.yml)
  │                                      ↓ Grafana Log Explorer
  │
  ├── METRICS ──────────────────────────────────────────────────────
  │   prometheus-fastapi-instrumentator → /metrics endpoint
  │   workers/metrics.py (Celery + LLM) → /metrics endpoint (fixed)
  │   logging_middleware.py (unhandled) → /metrics endpoint
  │                                        ↓
  │   Prometheus (scrapes every 15s)    → Grafana dashboards
  │   alerting.rules.yml                → Alertmanager → Slack/email
  │
  └── TRACES ───────────────────────────────────────────────────────
      OpenTelemetry (tracing.py)
        FastAPI instrumentation     → span per route
        SQLAlchemy instrumentation  → span per query
        httpx instrumentation       → span per outbound call
        Redis instrumentation       → span per command
                                        ↓
      Cloud Trace (via ADC on Cloud Run)
        or
      Local Jaeger (OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317)
```

---

## 4. Pillar 1 — Logs

### What is structured logging?

Traditional logging:
```
2026-08-26 14:32:01 ERROR - Connection refused
```
Hard to parse, impossible to filter by user or correlation ID.

Structured logging:
```json
{
  "timestamp":       "2026-08-26T14:32:01.123Z",
  "severity":        "ERROR",
  "message":         "request",
  "correlation_id":  "a1b2c3d4",
  "user_id":         "user-456",
  "path":            "/api/v1/chat",
  "method":          "POST",
  "status_code":     500,
  "response_time_ms": 3201.5
}
```

Every field is individually queryable. Cloud Logging promotes `severity`, `message`,
and `timestamp` automatically. Custom fields (`correlation_id`, `user_id`) become
indexed labels you can filter on.

### What this project logs

| Where | What | Fields |
|-------|------|--------|
| `RequestLoggingMiddleware` | Every HTTP request | correlation_id, user_id, path, method, status_code, response_time_ms |
| `RequestLoggingMiddleware` | Unhandled exceptions | As above + full stack trace in exc_info |
| `lifespan` / startup | Service startup events | STARTUP_VALIDATION_PASSED/FAILED, ChromaDB connectivity |
| `tracing.py` | OTel setup | OTEL enabled/disabled, exporter target |
| Any `logger.info/error()` call | Business logic events | message + any extra={} fields |

### How to query logs

**Cloud Logging (production — Cloud Run):**
```bash
# All errors in the last hour
gcloud logging read \
  'resource.type="cloud_run_revision" AND severity=ERROR' \
  --project=android-ai-assistant-89cec \
  --limit=50 \
  --format=json | jq '.[] | {time: .timestamp, msg: .jsonPayload.message, corr: .jsonPayload.correlation_id}'

# Find a specific correlation ID across all log entries
gcloud logging read \
  'jsonPayload.correlation_id="a1b2c3d4-..."' \
  --project=android-ai-assistant-89cec
```

**Grafana + Loki (docker-compose.prod.yml):**
```logql
# All ERROR logs from the backend
{application="android-ai-assistant"} |= `"severity":"ERROR"`

# Logs for a specific user
{application="android-ai-assistant"} | json | user_id = "user-456"

# Slow requests (> 1 second)
{application="android-ai-assistant"} | json | response_time_ms > 1000

# All logs for one request trace
{application="android-ai-assistant"} | json | correlation_id = "a1b2c3d4"
```

**Local development:**
```bash
# Pretty-print JSON logs from Docker
docker logs android-ai-assistant-api-1 | jq '.'

# Filter to errors only
docker logs android-ai-assistant-api-1 | jq 'select(.severity == "ERROR")'
```

### Key concept: Correlation ID

Every HTTP request gets a UUID called the correlation ID. It is:
- Added to the outgoing response as `X-Correlation-ID` header
- Logged with every log entry for that request
- Added to `X-Request-ID` header on Android → received by the backend
- Used to join Android logs with backend logs for the same request

```
Android log:   "POST /chat → HTTP 500 (320ms)" requestId="a1b2c3d4"
Backend log:   "request" correlation_id="a1b2c3d4" status_code=500 response_time_ms=318.2
```

---

## 5. Pillar 2 — Metrics

### What are metrics?

Metrics are numeric time-series. Each data point is: `(timestamp, value, labels)`.

```
http_requests_total{method="POST", handler="/api/v1/chat", status="200"} 1847
http_requests_total{method="POST", handler="/api/v1/chat", status="500"} 23
http_request_duration_seconds_bucket{le="0.2", handler="/api/v1/chat"}   1820
llm_token_cost_usd_total{provider="openai"}                              0.0847
```

Prometheus scrapes `/metrics` every 15 seconds and stores these time-series.
Grafana queries Prometheus to build dashboards.

### What this project exposes at `/metrics`

| Metric | Type | Labels | What it measures |
|--------|------|--------|-----------------|
| `http_requests_total` | Counter | method, handler, status | Request count per endpoint and status code |
| `http_request_duration_seconds` | Histogram | handler | Request latency (P50/P95/P99) |
| `http_unhandled_exceptions_total` | Counter | path | Unhandled exception count |
| `celery_queue_depth` | Gauge | queue | Pending tasks in queue |
| `celery_active_tasks` | Gauge | queue | Running tasks |
| `celery_failed_tasks_total` | Counter | task_name | Permanently failed tasks |
| `celery_completed_tasks_total` | Counter | task_name | Successfully completed tasks |
| `llm_token_cost_usd_total` | Counter | provider | Cumulative LLM spend in USD |
| `llm_input_tokens_total` | Counter | provider | Cumulative input tokens |
| `llm_output_tokens_total` | Counter | provider | Cumulative output tokens |

### How to query metrics (PromQL)

**HTTP error rate (5xx) over last 5 minutes:**
```promql
sum(rate(http_requests_total{status=~"5.."}[5m]))
/
sum(rate(http_requests_total[5m]))
```

**P95 response time per endpoint:**
```promql
histogram_quantile(
  0.95,
  sum(rate(http_request_duration_seconds_bucket[5m])) by (le, handler)
)
```

**LLM cost rate in USD/minute:**
```promql
sum(rate(llm_token_cost_usd_total[1m])) by (provider) * 60
```

**Requests per second by endpoint:**
```promql
sum(rate(http_requests_total[1m])) by (handler)
```

### Grafana dashboards (auto-provisioned)

| Dashboard | What it shows |
|-----------|--------------|
| `request_volume_dashboard` | Request rate, status code breakdown, P95 latency, Celery queue depth |
| `error_rates_dashboard` | HTTP error rate, unhandled exceptions by path, Celery failures, top error paths |
| `ai_cost_dashboard` | LLM cost per provider, token rates, cumulative cost gauge |

Access locally: http://localhost:3000 (admin / changeme)

### Alerting rules

Rules in `infrastructure/prometheus/alerting.rules.yml` fire when:

| Alert | Condition | Severity |
|-------|-----------|----------|
| `HighHTTP5xxErrorRate` | > 5% of requests return 5xx for 2 min | critical |
| `HighHTTP4xxErrorRate` | > 20% return 4xx for 5 min | warning |
| `UnhandledExceptions` | Any unhandled exception in handlers | critical |
| `HighP95Latency` | P95 > 2s for any endpoint for 3 min | warning |
| `CriticalP99Latency` | P99 > 10s for 2 min | critical |
| `LLMCostSpike` | LLM spend > $0.10/min for 5 min | critical |
| `LLMHighTokenUsage` | > 10,000 output tokens/min for 5 min | warning |
| `HighCeleryTaskFailureRate` | Any task failing > 0.05/s for 2 min | warning |
| `CeleryQueueBacklog` | Queue depth > 100 for 10 min | warning |
| `BackendDown` | Prometheus can't scrape /metrics for 1 min | critical |

Validate rules locally:
```bash
promtool check rules infrastructure/prometheus/alerting.rules.yml
```

---

## 6. Pillar 3 — Traces

### What are distributed traces?

A trace represents a single request as it flows through your system. It consists of **spans** — one per unit of work:

```
Trace: POST /api/v1/chat  (800ms total)
  │
  ├── Span: FastAPI route handler          [0ms → 800ms]
  │     ├── Span: SQLAlchemy SELECT user   [2ms → 14ms]   12ms
  │     ├── Span: Redis GET rate_limit     [14ms → 16ms]  2ms
  │     ├── Span: httpx POST api.openai.com [16ms → 802ms] 786ms ← bottleneck
  │     └── Span: SQLAlchemy INSERT message [802ms → 810ms] 8ms
```

Without tracing, you know "POST /chat is slow" but not which step.
With tracing, you click the trace and immediately see the OpenAI call is the bottleneck.

### What this project traces

OpenTelemetry auto-instruments four libraries:

| Library | What gets a span |
|---------|-----------------|
| FastAPI | Every HTTP route (method, path, status code, duration) |
| SQLAlchemy | Every SQL query (statement, duration) |
| httpx | Every outbound HTTP call (URL, method, status, duration) |
| Redis | Every Redis command (command name, key, duration) |

Each span automatically carries the W3C `traceparent` header so spans from
different services are stitched into one trace tree.

### Environment variables

| Variable | Default | Effect |
|----------|---------|--------|
| `OTEL_ENABLED` | `true` | Set to `false` in unit tests |
| `OTEL_SERVICE_NAME` | `ai-assistant-backend` | Label in Cloud Trace / Jaeger |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | _(empty)_ | Empty = Cloud Trace via ADC. Set to `http://jaeger:4317` for local Jaeger |

### How to view traces

**Cloud Trace (production):**
```
GCP Console → Cloud Trace → Trace Explorer
Filter: service.name = "ai-assistant-backend"
Sort by: Latency (descending)
```

**Local Jaeger (docker-compose.prod.yml — add Jaeger service):**

Add to `docker-compose.prod.yml`:
```yaml
jaeger:
  image: jaegertracing/all-in-one:1.57
  ports:
    - "16686:16686"   # Jaeger UI
    - "4317:4317"     # OTLP gRPC
  environment:
    COLLECTOR_OTLP_ENABLED: "true"
```

Set `OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317` in `.env`, then open:
http://localhost:16686

---

## 7. Android Observability Pipeline

```
Event captured
  │
  ├── NetworkObservabilityInterceptor  (OkHttp)    → every HTTP call
  ├── ObservabilityNavTracker          (Compose)   → every screen change
  ├── AppLifecycleObserver             (Lifecycle) → foreground/background
  │   [future: CrashHandler]                       → unhandled exceptions
  │
  ▼
ObservabilityEventBus (SharedFlow, buffer=64, non-blocking emit)
  │
  ▼
ObservabilityManager (in-memory ring buffer, max 500 events)
  │
  ▼ (every 15 minutes, when CONNECTED)
ObservabilityUploadWorker (WorkManager CoroutineWorker)
  │  POST /api/v1/observability/events
  ▼
Backend ingest → stores events → AI analysis pipeline (Phase 10)
```

### ObservabilityEvent fields

| Field | Type | Purpose |
|-------|------|---------|
| `timestamp` | Long | Epoch millis (UTC) when event was captured |
| `level` | EventLevel | DEBUG / INFO / WARN / ERROR / CRITICAL |
| `eventType` | String | Machine-readable category (use EventType constants) |
| `message` | String | Human-readable, PII-filtered description |
| `screen` | String? | Active Compose route at time of event |
| `requestId` | String? | UUID per HTTP call — joins Android log ↔ backend log |
| `traceId` | String? | Groups related events across a user flow |
| `sessionId` | String | Groups all events within one app launch |
| `metadata` | Map<String, String> | Extensible key-value context, all values PII-filtered |

### PII filtering

`PiiFilter` applies regex redaction before any event is created:
- Email addresses → `[email]`
- Phone numbers → `[phone]`
- Bearer/JWT tokens → `[token]`
- Authorization headers → `[redacted]`
- Credit card numbers → `[card]`
- IPv4 addresses → `[ip]`

**Rule:** PII is stripped at the capture site. Events in the buffer are already safe.

---

## 8. Debug — Common Issues

### `/metrics` returns no Celery or LLM metrics

**Cause (fixed in Phase 8):** Celery metrics used a separate `CollectorRegistry` that
was never merged into the default registry.

**Verify the fix:**
```bash
curl http://localhost:8000/metrics | grep celery_
# Should show: celery_queue_depth, celery_active_tasks, celery_failed_tasks_total, etc.

curl http://localhost:8000/metrics | grep llm_
# Should show: llm_token_cost_usd_total, llm_input_tokens_total, llm_output_tokens_total
```

### Cloud Logging shows plain text instead of JSON

**Cause:** `configure_logging()` was not called before a library triggered `basicConfig()`.

**Fix:** Ensure `from app.observability.logging_setup import configure_logging; configure_logging()`
is the very first executable line in `main.py` (before any other imports that touch logging).

**Verify:**
```bash
docker logs android-ai-assistant-api-1 2>&1 | head -5
# Should start with: {"timestamp": "2026-...", "severity": "INFO", ...}
# NOT: INFO:     Started server process
```

### OTel spans not appearing in Cloud Trace

1. Check `OTEL_ENABLED=true` in Cloud Run env vars
2. The Cloud Run service account needs `roles/cloudtrace.agent`
   ```bash
   gcloud projects add-iam-policy-binding android-ai-assistant-89cec \
     --member="serviceAccount:ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com" \
     --role="roles/cloudtrace.agent"
   ```
3. Verify the tracing module loaded:
   ```bash
   gcloud run services logs read ai-assistant-backend --region=asia-south1 | grep OTEL
   # Should show: OTEL: tracing configured — service=ai-assistant-backend
   ```

### Android events not reaching the backend

1. Check WorkManager is scheduled:
   ```kotlin
   // In debug build — check WorkManager status
   WorkManager.getInstance(context)
       .getWorkInfosForUniqueWork("observability_upload")
       .get()
       .forEach { Timber.d("WorkInfo: $it") }
   ```
2. Check the backend URL in `ObservabilityUploadWorker` — it needs the Cloud Run URL
3. Check that the `/api/v1/observability/events` endpoint exists on the backend

### Prometheus alerting rules fail validation

```bash
promtool check rules infrastructure/prometheus/alerting.rules.yml
# Output should be: Checking infrastructure/prometheus/alerting.rules.yml
#                   SUCCESS: 10 rules found
```

Common errors:
- Metric name typo → check `curl http://localhost:8000/metrics | grep <name>`
- PromQL syntax error → test expression in Prometheus UI → Graph tab

---

## 9. Interview Questions

**Q1: What are the three pillars of observability? How do they differ?**

Logs, metrics, and traces. Logs are discrete timestamped events that describe what
happened ("DB connection refused at 14:32:01"). Metrics are numeric time-series that
show how the system behaves over time (error rate, latency histograms, CPU). Traces
show the path of a single request across services and where time was spent within
each service. You need all three — logs tell you what, metrics tell you how much,
traces tell you where.

---

**Q2: What is a correlation ID and why is it important?**

A correlation ID is a UUID generated once per HTTP request and propagated through
every log entry, response header, and service call for that request. It lets you
find all log lines for a single request across multiple log entries, multiple services,
and the mobile app. Without it, logs for one user's failing request are buried among
thousands of unrelated entries with no way to group them.

---

**Q3: What is the difference between a Prometheus Counter and a Gauge?**

A Counter is a monotonically increasing value that only goes up (or resets to zero on
process restart). Use it for things you count: requests, errors, tokens. You query the
*rate of change* with `rate(counter[5m])`, not the raw value.

A Gauge can go up or down. Use it for things that have a current level: queue depth,
active connections, memory usage. You query the raw value directly.

Mistake to avoid: never use a Gauge for request count (it could decrease if you restart
and lose state). Never use a Counter for queue depth (it can only go up, not reflect
items being consumed).

---

**Q4: What is OpenTelemetry and why use it instead of a vendor SDK?**

OpenTelemetry is a vendor-neutral standard for distributed tracing, metrics, and logs.
Instead of calling `datadog.trace.start()` or `newrelic.add_custom_attribute()` directly,
you call the OTel API. The *exporter* decides where data goes (Cloud Trace, Jaeger,
Datadog, etc.) — configured by an env var, not code.

This means you can switch from Cloud Trace to Datadog by changing
`OTEL_EXPORTER_OTLP_ENDPOINT` — no code changes. It also means auto-instrumentation
patches libraries (FastAPI, SQLAlchemy, httpx) without any changes to those libraries'
call sites.

---

**Q5: A user reports the app is slow. Walk me through how you would diagnose it.**

1. **Check the alert dashboard** — is there an active `HighP95Latency` alert? If yes,
   which endpoint is it on?
2. **Check metrics** — query `histogram_quantile(0.95, ...)` in Grafana to confirm
   which endpoint and time window is affected.
3. **Check traces** — open Cloud Trace, filter to the slow endpoint and time window,
   sort by latency descending. Click the slowest trace — which span is the bottleneck?
   Is it a DB query, an LLM call, or an outbound HTTP call?
4. **Check logs** — filter by the slow time window and endpoint in Cloud Logging.
   Any errors? Any unusually high `response_time_ms`? Any correlation IDs that
   appear in both Android and backend logs?
5. **Cross-reference** — if it's a DB query, check the SQLAlchemy span for the
   query text. If it's an LLM call, check `llm_token_cost_usd_total` — were
   unusually large prompts being sent?

---

**Q6: What is the `for` clause in a Prometheus alerting rule?**

The `for` clause specifies how long the condition must be continuously true before
the alert fires. Without it, a single spike (one bad scrape) would trigger a page.

```yaml
- alert: HighHTTP5xxErrorRate
  expr: (rate(http_requests_total{status=~"5.."}[5m]) / rate(http_requests_total[5m])) > 0.05
  for: 2m    # must stay above 5% for 2 full minutes before firing
```

This reduces false positives from transient spikes. The trade-off: real incidents
take `for` duration longer to notify. For `BackendDown` (the service is completely
unreachable) we use `for: 1m` — short because the impact is total. For warning-level
latency spikes we use `for: 3m` — longer because transient spikes are common.

---

## 10. Exercise

After the docker-compose.prod.yml stack is running:

1. **Generate a 5xx error** — call a protected endpoint without a JWT:
   ```bash
   curl -X POST http://localhost:8000/api/v1/chat \
     -H "Content-Type: application/json" \
     -d '{"message": "test"}'
   # Should return 401 or 403
   ```
   Then check Grafana → error_rates_dashboard → verify the 4xx spike appears.

2. **Find the request in Loki** — copy the `X-Correlation-ID` from the response header:
   ```bash
   curl -I http://localhost:8000/api/v1/chat | grep -i correlation
   # x-correlation-id: a1b2c3d4-...
   ```
   Open Grafana → Explore → Loki datasource → query:
   ```logql
   {application="android-ai-assistant"} | json | correlation_id = "a1b2c3d4-..."
   ```

3. **Verify the metrics fix** — confirm Celery metrics now appear:
   ```bash
   curl -s http://localhost:8000/metrics | grep "^celery_"
   # Should show 4+ metric families
   ```

4. **Trigger an alerting rule** — send 100 requests with invalid JSON to generate
   unhandled exceptions, then check Prometheus → Alerts tab to see
   `UnhandledExceptions` in PENDING state.

5. **Check a trace** — open Jaeger at http://localhost:16686 (after adding the Jaeger
   service to docker-compose.prod.yml), find the slowest trace, and identify which
   span consumed the most time.

---

## Phase 8 Summary

**What was built this phase:**

| Component | File | What it does |
|-----------|------|-------------|
| Celery metrics fix | `backend/app/workers/metrics.py` | Merged into default registry — now visible at /metrics |
| JSON log formatter | `backend/app/observability/logging_setup.py` | Cloud Logging-compatible JSON stdout |
| OpenTelemetry tracing | `backend/app/observability/tracing.py` | Auto-instruments FastAPI/SQLAlchemy/httpx/Redis |
| Alerting rules | `infrastructure/prometheus/alerting.rules.yml` | 10 rules across 5 groups |
| WorkManager upload | `core-network/.../ObservabilityUploadWorker.kt` | Drains buffer → POST /events every 15min |
| Screen tracking | `app/.../observability/ObservabilityNavTracker.kt` | SCREEN_VIEW events per navigation |
| Lifecycle tracking | `app/.../observability/AppLifecycleObserver.kt` | APP_FOREGROUND/BACKGROUND events |

**What was already complete (Phase 2):**
- `ObservabilityEvent`, `ObservabilityEventBus`, `ObservabilityManager`, `PiiFilter`, `SessionManager`
- `NetworkObservabilityInterceptor` (OkHttp)
- `RequestLoggingMiddleware` (structured logging + Loki shipping)
- Prometheus `/metrics` (prometheus-fastapi-instrumentator)
- Grafana dashboards (3 pre-built)
- Docker Compose observability stack (Prometheus, Loki, Grafana)

**Next phase:** Phase 9 — RAG (Retrieval-Augmented Generation).
Say `NEXT` to continue.
