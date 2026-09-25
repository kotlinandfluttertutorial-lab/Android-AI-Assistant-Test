# AI DevOps Assistant — Complete Learning Guide

> **Purpose:** A single reference document covering all 20 phases of the AI DevOps
> Master Plan. Every concept is grounded in the actual code in this repository.
> Use this as your study companion, interview preparation, and architectural reference.

---

## Table of Contents

| Phase | Topic |
|-------|-------|
| [Phase 1](#phase-1--android-foundation) | Android Foundation |
| [Phase 2](#phase-2--android-observability) | Android Observability |
| [Phase 3](#phase-3--backend-fastapi) | Backend (FastAPI) |
| [Phase 4](#phase-4--devops-foundation) | DevOps Foundation |
| [Phase 5](#phase-5--docker) | Docker |
| [Phase 6](#phase-6--google-cloud) | Google Cloud |
| [Phase 7](#phase-7--infrastructure-as-code-terraform) | Infrastructure as Code (Terraform) |
| [Phase 8](#phase-8--observability-logs-metrics-traces) | Observability |
| [Phase 9](#phase-9--rag-retrieval-augmented-generation) | RAG |
| [Phase 10](#phase-10--ai-error-analysis) | AI Error Analysis |
| [Phase 11](#phase-11--anomaly-detection) | Anomaly Detection |
| [Phase 12](#phase-12--root-cause-analysis) | Root Cause Analysis |
| [Phase 13](#phase-13--ai-devops-assistant) | AI DevOps Assistant |
| [Phase 14](#phase-14--android-ai-devops-dashboard) | Android AI DevOps Dashboard |
| [Phase 15](#phase-15--aiops) | AIOps |
| [Phase 16](#phase-16--security) | Security |
| [Phase 17](#phase-17--testing) | Testing |
| [Phase 18](#phase-18--production-cicd) | Production CI/CD |
| [Phase 19](#phase-19--jenkins) | Jenkins |
| [Phase 20](#phase-20--kubernetes) | Kubernetes |
| [Appendix](#appendix--how-all-phases-connect) | How All Phases Connect |

---

## Phase 1 — Android Foundation

### Concept

Production Android development follows **Clean Architecture** — a strict layering
where each layer depends only on the one below it, never above it:

```
UI (Compose)
    ↓
ViewModel
    ↓
UseCase
    ↓
Repository (interface)
    ↓
DataSource (local Room + remote Retrofit)
```

The rule: **data flows down, results flow up.** A ViewModel never touches Room
directly. A Repository never knows about Compose.

### Module Structure

```
app/                  — entry point, Hilt setup, navigation host
core-common/          — ApiResult, DomainError, DispatcherProvider
core-ui/              — Material 3 design system, shared composables
core-network/         — Retrofit, OkHttp, interceptors
core-database/        — Room entities, DAOs
core-security/        — EncryptedSharedPreferences, BiometricManager
core-ai/              — WebSocket streaming client
domain/               — pure Kotlin entities, use cases, repository interfaces
data/                 — repository implementations, data sources
feature-*/            — one Gradle module per feature screen
```

### Key Pattern: ApiResult

Every async operation returns `ApiResult<T>`, a sealed class that represents
all possible states without exceptions crossing layer boundaries:

```kotlin
// core-common/src/main/kotlin/com/aiassistant/core/common/ApiResult.kt
sealed class ApiResult<out T> {
    data class Success<T>(val data: T) : ApiResult<T>()
    data class Error(val error: DomainError) : ApiResult<Nothing>()
    object Loading : ApiResult<Nothing>()
    object NetworkUnavailable : ApiResult<Nothing>()
}

// Usage in a ViewModel:
when (val result = getIncidents()) {
    is ApiResult.Success -> _uiState.value = UiState.Content(result.data)
    is ApiResult.Error   -> _uiState.value = UiState.Error(result.error.message)
    is ApiResult.NetworkUnavailable -> _uiState.value = UiState.Offline
    is ApiResult.Loading -> _uiState.value = UiState.Loading
}
```

### Key Pattern: DispatcherProvider

```kotlin
interface DispatcherProvider {
    val default: CoroutineDispatcher
    val io: CoroutineDispatcher
    val main: CoroutineDispatcher
}

// Tests inject TestDispatcherProvider — no real threads, instant execution
// Production injects DefaultDispatcherProvider backed by Dispatchers.IO
```

### Dependency Injection with Hilt

Every ViewModel is annotated `@HiltViewModel` with `@Inject constructor`. Every
module uses `@Module` + `@InstallIn`. No manual DI — Hilt generates it via KSP.

```kotlin
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val getIncidents: GetIncidentsUseCase,
    private val analyseErrors: AnalyseErrorsUseCase,
    private val dispatchers: DispatcherProvider
) : ViewModel()
```

### Interview Questions

**Q: What is the Dependency Inversion Principle and how does this codebase apply it?**
High-level modules (domain use cases) depend on interfaces, not implementations.
`GetIncidentsUseCase` depends on `IncidentRepository` (interface), not
`IncidentRepositoryImpl`. The data module provides the implementation; the domain
module never knows it exists.

**Q: Why have separate `domain` and `data` Gradle modules?**
The `domain` module is pure Kotlin — no Android dependencies, no Room, no Retrofit.
It can be tested on the JVM without an emulator. The `data` module implements the
repositories. Separating them means business rules are testable in milliseconds.

**Q: What does `SharingStarted.WhileSubscribed(5_000L)` do in `stateIn`?**
It keeps the upstream Flow active for 5 seconds after the last subscriber
disappears. This survives configuration changes (screen rotation drops the
collector for ~50ms). After 5 seconds with no subscriber, the Flow is cancelled,
saving resources when the user navigates away.

---

## Phase 2 — Android Observability

### Concept

Observability is the ability to understand what your app does in production from
the outside. The Android layer is the **data origin** — it generates structured
events that travel to the backend AI analysis pipeline.

### The Standard Event Model

```kotlin
// core-common/.../observability/ObservabilityEvent.kt
@Serializable
data class ObservabilityEvent(
    val timestamp: Long,          // epoch millis UTC
    val level: EventLevel,        // DEBUG, INFO, WARN, ERROR, CRITICAL
    val eventType: String,        // "network_error", "crash_unhandled", etc.
    val message: String,          // PII-stripped human-readable description
    val screen: String? = null,   // active Compose route at capture time
    val requestId: String? = null,// unique per HTTP call — links to backend log
    val traceId: String? = null,  // groups related events across one user flow
    val sessionId: String,        // groups all events in one app session
    val metadata: Map<String, String> = emptyMap() // no PII, string values only
)
```

### Three Correlation IDs

| ID | Scope | Purpose |
|----|-------|---------|
| `sessionId` | App launch → close | All events in one session |
| `traceId` | One user action | "tap Send → POST /chat → stream → render" |
| `requestId` | One HTTP call | Links Android log ↔ backend log |

### The Capture Pipeline

```
NetworkObservabilityInterceptor  (every HTTP call)
CrashObservabilityHandler        (unhandled exceptions)
AppLifecycleObserver             (foreground/background)
    │
    ▼ ObservabilityEventBus (SharedFlow, buffer=64)
    │
    ▼ ObservabilityManager (ArrayDeque, max 500 events)
    │
    ▼ ObservabilityUploadWorker (WorkManager, every 15 min)
    │
    ▼ POST /observability/events (batch)
```

### PII Filter

Applied **before** constructing any event — not before uploading:

```kotlin
// core-common/.../observability/PiiFilter.kt
object PiiFilter {
    fun filter(input: String): String {
        // Redacts: emails → [email], tokens → [token],
        //          IPs → [ip], phones → [phone], cards → [card]
    }
    fun filterMap(map: Map<String, String>): Map<String, String> =
        map.mapValues { (_, v) -> filter(v) }
}
```

### Interview Questions

**Q: Why use `tryEmit` in the event bus rather than `emit`?**
`tryEmit` is non-suspending. The OkHttp interceptor runs on a thread outside
the coroutine world — it cannot `suspend`. `tryEmit` attempts to emit without
blocking. If the buffer is full, the event is dropped (acceptable — losing one
event is better than blocking an HTTP call).

**Q: Why filter PII at capture time rather than just before upload?**
Defence in depth. Once PII enters the event model it can be logged locally,
stored to Room, or leak through a crash report. Filtering at construction means
it never enters the system at all.

**Q: What happens to buffered events during a crash?**
`CrashObservabilityHandler` calls `manager.drain()` inside `runBlocking` with a
2-second timeout. The drain empties the buffer; the WorkManager task uploads on
next launch. The raw bytes are preserved even when the process is killed.

---

## Phase 3 — Backend (FastAPI)

### Concept

The backend is a Python FastAPI service following the same layered architecture
as the Android app:

```
HTTP Request
    ↓ Router (api/)         — validates Pydantic schemas, returns responses
    ↓ Service (services/)   — business logic, no HTTP concepts
    ↓ Repository (repositories/) — SQL queries, wraps SQLAlchemy
    ↓ Model (models/)       — SQLAlchemy ORM table definitions
```

### Key FastAPI Patterns

**Dependency Injection via `Depends`:**

```python
@router.post("/chat")
async def send_message(
    body: SendMessageRequest,                    # Pydantic validation
    db: AsyncSession = Depends(get_db),          # injected DB session
    current_user: TokenPayload = Depends(get_current_user)  # injected auth
):
    service = AIOrchestrator(db)
    return await service.stream_chat(body.message, current_user.sub)
```

**Lifespan handler (startup/shutdown):**

```python
@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    startup_validation()    # exits with code 1 if env vars missing
    setup_tracing()         # OpenTelemetry
    await seed_knowledge()  # ChromaDB knowledge base
    yield
    # shutdown cleanup here
```

**Pydantic schemas separate from SQLAlchemy models:**

```python
# models/user.py — SQLAlchemy (database schema)
class User(Base):
    __tablename__ = "users"
    id: Mapped[uuid.UUID]
    email: Mapped[str]
    hashed_password: Mapped[str]

# schemas/user.py — Pydantic (API contract)
class UserResponse(BaseModel):
    id: uuid.UUID
    email: str
    role: str
    # hashed_password intentionally excluded
```

### Async Everywhere

```python
# Every route, service, and repository method is async
async def get_incident(incident_id: uuid.UUID, db: AsyncSession) -> Incident:
    result = await db.execute(
        select(Incident).where(Incident.id == incident_id)
    )
    return result.scalar_one_or_none()
```

While one request awaits the DB, the event loop handles other requests.

### Interview Questions

**Q: Why separate Pydantic schemas from SQLAlchemy models?**
SQLAlchemy models describe the database schema — column types, relationships.
Pydantic models validate API data and control serialization. Keeping them
separate means you can change a column name without breaking clients, and you
can exclude sensitive fields (like `hashed_password`) from API responses without
conditional logic.

**Q: What is the purpose of `startup_validation()` in `main.py`?**
It iterates `REQUIRED_ENV_VARS`, logs a structured ERROR for each missing
variable, and calls `sys.exit(1)` before the server binds to any port. A service
missing `AES_ENCRYPTION_KEY` must not start silently and serve degraded responses.

---

## Phase 4 — DevOps Foundation

### Concept

DevOps is the practice of automating the path from code change to running
production software. Every push triggers a pipeline that builds, tests, and
validates — catching problems in minutes, not weeks.

### The Four Workflows

| Workflow | Triggers | Purpose |
|----------|---------|---------|
| `android-ci.yml` | Every PR + main push | Android build/test/lint/scan |
| `backend-ci.yml` | Every PR + main push | Backend test/lint/Docker/deploy |
| `security-scan.yml` | Every PR + weekly | Security scanning |
| `release.yml` | Version tag push | Full production release |

### PR Required Gates (android-ci.yml)

```yaml
# Every PR to main must pass ALL of these:
validate:              # Gradle wrapper integrity
dependency-lint:       # Clean Architecture module deps (check-module-deps.sh)
hilt-ksp-gate:         # Hilt DI bindings via KSP compile
android-lint:          # lintDebug all modules
android-unit-tests:    # testDebugUnitTest all modules
ktlint-detekt:         # style + static analysis (changed .kt files only)
jacoco-gate:           # ≥70% combined domain+data instruction coverage
backend-unit-tests:    # pytest tests/unit/
backend-integration-tests: # pytest tests/integration/
```

### The Dependency Lint Script

`check-module-deps.sh` scans every `build.gradle.kts` and fails if any of these
forbidden dependency edges exist:

```
feature → feature   (feature modules are independent)
domain  → data      (domain must not know implementation details)
domain  → feature   (domain must not depend on UI)
data    → feature   (data must not depend on UI)
```

Architecture is enforced by CI, not just code review.

### Key YAML Patterns

```yaml
# Cancel in-flight runs when a newer commit arrives
concurrency:
  group: ${{ github.workflow }}-${{ github.ref }}
  cancel-in-progress: true

# Only run a step on the main branch
build-signed-apk:
  if: github.event_name == 'push' && github.ref == 'refs/heads/main'

# Parallel jobs (run simultaneously)
android-lint:
  needs: validate          # wait for validate, then run in parallel with others
backend-unit-tests:
  needs: []                # no dependency, starts immediately
```

### Interview Questions

**Q: What is the difference between CI and CD?**
CI (Continuous Integration) automates building and testing on every change.
CD (Continuous Delivery/Deployment) automatically deploys validated builds.
This project has both: PRs trigger full test suites (CI), and merges to main
automatically deploy to staging with a manual gate before production (CD).

**Q: Why pin action versions to `@v4` instead of `@latest`?**
Supply chain security. A compromised action at `@latest` could exfiltrate your
secrets. Pinning to a specific version means you only get what was reviewed.

---

## Phase 5 — Docker

### Concept

Docker solves "it works on my machine." A Docker **image** is an immutable
filesystem snapshot — your app, dependencies, and runtime, frozen in one artifact.
A **container** is a running instance. The same image behaves identically
everywhere.

### The Multi-Stage Dockerfile

```dockerfile
# backend/Dockerfile

# Stage 1: builder — has gcc, pip, build tools
FROM python:3.11-slim AS builder
RUN pip install --prefix=/install -r requirements.txt
# → /install contains only compiled packages

# Stage 2: production — no build tools, no pip, no gcc
FROM python:3.11-slim AS production
COPY --from=builder /install /usr/local    # copy only compiled packages
RUN adduser --system appuser               # non-root user
USER appuser
HEALTHCHECK CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health')"
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

**Why multi-stage:** The production image contains no `gcc`, no `pip`, no build
headers. If an attacker gains code execution, they can't compile exploits or
install packages. The image is also smaller — no cached wheels, no dev files.

### Docker Compose — Local Architecture

```yaml
# docker-compose.yml
services:
  postgres:   postgres:16-alpine    # relational data
  redis:      redis:7-alpine        # cache, rate limiting, Celery queue
  minio:      minio/minio:...       # document file storage (S3-compatible)
  chromadb:   chromadb/chroma:1.5.9 # vector database for RAG + memory
  backend:    build: ./backend      # FastAPI API server
  celery_worker: build: ./backend   # async task processor (same image, different CMD)
```

All services share `ai_assistant_net` bridge network and communicate by
**service name** (`postgres:5432`, `redis:6379`), not `localhost`.

### Health Checks and depends_on

```yaml
postgres:
  healthcheck:
    test: ["CMD-SHELL", "pg_isready -U aiassistant"]
    interval: 10s
    retries: 5

backend:
  depends_on:
    postgres:
      condition: service_healthy  # waits for pg_isready to pass
    redis:
      condition: service_healthy
```

Without `service_healthy`, the backend would start before PostgreSQL accepts
connections and crash immediately.

### Interview Questions

**Q: What is the difference between a named volume and a bind mount?**
A named volume (`postgres_data:/var/lib/postgresql/data`) is managed by Docker.
Data persists in a Docker-controlled location. A bind mount (`./backend/app:/app/app`)
maps a local directory into the container — used in development for live reload.
Named volumes are for production data persistence; bind mounts are for development.

**Q: Why does `celery_worker` use the same image as `backend`?**
All application code and dependencies are already in the backend image. The
Celery worker needs a different entry point (`celery worker` instead of `uvicorn`)
but the same code. One image, different `command`. Maintaining one image reduces
build complexity.

---

## Phase 6 — Google Cloud

### Concept

Google Cloud Platform (GCP) is the cloud infrastructure layer. This project
uses Cloud Run — Google's serverless container platform. You give it a Docker
image; it handles scaling, load balancing, TLS, and health monitoring.

### GCP Architecture

```
GitHub Actions (CI/CD)
    ↓
Artifact Registry          ← private Docker image registry
    ↓
Cloud Run (FastAPI)         ← serverless, scales 0–2 instances
    ├── Secret Manager      ← encrypted secrets (API keys, DB passwords)
    ├── Cloud Storage       ← file uploads (replaces MinIO)
    ├── Neon PostgreSQL     ← managed Postgres (external, serverless)
    └── Cloud Run (ChromaDB)← vector database as separate service
```

### Cloud Run Configuration

```yaml
# From cloud-run-deploy.yml
flags: >-
  --min-instances=0        # scale to zero when idle → $0 cost
  --max-instances=2        # hard cap → prevents runaway cost
  --cpu=1
  --memory=1Gi
  --concurrency=40         # requests per instance before scaling out
  --allow-unauthenticated  # auth is inside the app (Firebase JWT)
```

### Secret Injection (never in .env files)

```yaml
secrets: |-
  SECRET_KEY=SECRET_KEY:latest
  AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:latest
  OPENAI_API_KEY=OPENAI_API_KEY:latest
  DATABASE_URL=DATABASE_URL:latest
```

Cloud Run reads from Secret Manager at startup. The container sees plain env
vars. The secret value never appears in logs, configuration UI, or CI output.

### Workload Identity Federation (WIF) — no JSON key files

```yaml
# From cloud-run-deploy.yml
- uses: google-github-actions/auth@v2
  with:
    workload_identity_provider: ${{ secrets.GCP_WIF_PROVIDER }}
    service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}
```

GitHub Actions proves its identity to GCP via short-lived OIDC tokens. No
service account JSON key file ever exists. The token expires in minutes.
Breach surface is near zero.

### Interview Questions

**Q: Why use Cloud Run instead of a VM or Kubernetes?**
Cloud Run is the right choice when: the service is stateless HTTP, traffic is
variable, the team is small, and you don't want to manage infrastructure. It
scales to zero (free when idle), handles TLS automatically, and requires no
OS patching. A VM or Kubernetes is right for stateful workloads, complex
networking, or dedicated platform teams.

**Q: What happens during a Cloud Run deploy with zero downtime?**
Cloud Run creates a new revision (immutable snapshot). Traffic gradually shifts
to the new revision. If health checks fail, traffic stays on the old revision.
If they pass, the new revision serves 100% of traffic. The old revision remains
available for instant rollback.

---

## Phase 7 — Infrastructure as Code (Terraform)

### Concept

Infrastructure as Code means your GCP resources — Cloud Run services, storage
buckets, IAM roles — are defined in `.tf` files checked into Git. Instead of
clicking through the GCP console, you run `terraform apply`.

Benefits: reproducible (same code → same infrastructure), auditable (every
change is a git commit), reversible (terraform destroy), collaborative (one
state file, no conflicts).

### Module Structure

```
terraform/
├── main.tf           ← root: wires all modules
├── variables.tf      ← input declarations (no values)
├── outputs.tf        ← values printed after apply
├── providers.tf      ← pins google provider ~> 5.0
├── backend.tf        ← remote state in GCS bucket
├── environments/
│   ├── dev/terraform.tfvars    ← dev-specific values
│   └── prod/terraform.tfvars  ← prod-specific values
└── modules/
    ├── iam/            ← service account + WIF + IAM roles
    ├── artifact_registry/
    ├── cloud_run/      ← FastAPI backend + ChromaDB
    └── storage/        ← GCS bucket
```

### Remote State

```hcl
# terraform/backend.tf
terraform {
  backend "gcs" {
    bucket = "android-ai-assistant-89cec-tfstate"
    prefix = "terraform/state"
  }
}
```

GCS provides state locking — only one `terraform apply` runs at a time.
Per-environment isolation: `terraform init -backend-config="prefix=terraform/state/dev"`.

### The Four Commands

```bash
terraform init    # download providers, connect to state
terraform plan    # preview changes (READ ONLY, safe to run anytime)
terraform apply   # create/update resources
terraform destroy # tear down everything
```

Always run `plan` before `apply`. Treat the plan output as a code review.

### Principle of Least Privilege (IAM module)

```hcl
# modules/iam/main.tf
locals {
  project_roles = [
    "roles/run.developer",                  # deploy Cloud Run
    "roles/run.invoker",                    # call internal services
    "roles/secretmanager.secretAccessor",   # read secrets
    "roles/logging.logWriter",              # write logs
    "roles/cloudtrace.agent",               # write traces
    # NOT roles/owner, roles/editor, or any admin role
  ]
}
```

### Interview Questions

**Q: What is the state file and why must it be stored remotely?**
The state file maps `.tf` resource names to real GCP resource IDs. Without it,
Terraform can't determine what already exists. Remote state (GCS) provides a
shared source of truth with locking. Local state breaks the moment two people
run Terraform — their files diverge and the next `apply` corrupts both.

**Q: What is the bootstrapping paradox with the state bucket?**
The state bucket is the only resource created manually. You need somewhere to
store state before Terraform can manage anything. Create it once with
`gsutil mb` and enable versioning. Everything else is Terraform-managed.

---

## Phase 8 — Observability (Logs, Metrics, Traces)

### The Three Pillars

| Pillar | Question | Tool |
|--------|---------|------|
| Logs | What happened? | JSON → Cloud Logging + Loki |
| Metrics | How is the system behaving? | Prometheus → Grafana |
| Traces | Where did time go? | OpenTelemetry → Cloud Trace |

### Structured JSON Logs

```python
# backend/app/observability/logging_setup.py
class JsonFormatter(logging.Formatter):
    def format(self, record):
        return json.dumps({
            "timestamp":       "2026-08-26T14:32:01.123Z",
            "severity":        "ERROR",
            "message":         "request failed",
            "correlation_id":  "f47ac10b-...",  # from RequestLoggingMiddleware
            "user_id":         "user-123",
            "path":            "/api/v1/chat",
            "status_code":     500,
            "response_time_ms": 145.3
        })
```

Every request generates a JSON log line with a UUID `correlation_id`. The
Android app logs it from the response header. When debugging, search by
`correlation_id` to find every log line — Android and backend — for that request.

### Prometheus Metrics

```python
# Exposed at GET /metrics, scraped every 15s
# prometheus-fastapi-instrumentator automatically adds:
http_requests_total{method, path, status}  # counter
http_request_duration_seconds_bucket{le}   # histogram

# Custom counter in logging_middleware.py:
http_unhandled_exceptions_total{path}      # only incremented on true exceptions
```

### Alerting Rules (PromQL)

```yaml
# infrastructure/prometheus/alerting.rules.yml
- alert: HighHTTP5xxErrorRate
  expr: |
    (
      sum(rate(http_requests_total{status=~"5.."}[5m]))
      /
      sum(rate(http_requests_total[5m]))
    ) > 0.05
  for: 2m                # must be true for 2 continuous minutes
  labels:
    severity: critical
```

`rate(...[5m])` normalises for traffic volume. `for: 2m` prevents flapping.

### LLM Cost Alert

```yaml
- alert: LLMCostSpike
  expr: sum(rate(llm_token_cost_usd_total[1m])) * 60 > 0.10
  for: 5m
  labels:
    severity: critical
```

AI-specific observability: catches a runaway LLM retry loop before the
monthly bill arrives.

### OpenTelemetry Distributed Tracing

```python
# backend/app/observability/tracing.py
FastAPIInstrumentation().instrument()     # span per route
SQLAlchemyInstrumentation().instrument()  # span per query
HTTPXClientInstrumentation().instrument() # span per outbound call
RedisInstrumentation().instrument()       # span per command
```

Result: `POST /chat (345ms) → SQLAlchemy (12ms) + Redis (2ms) + OpenAI (310ms)`.
The 310ms OpenAI call is immediately visible as the bottleneck.

### Interview Questions

**Q: What is the difference between a counter, gauge, and histogram?**
Counter: only goes up (total requests, total errors). Gauge: goes up and down
(active connections, queue depth). Histogram: records distribution across buckets
(response time in 50ms, 100ms, 200ms slots) — enables percentiles (P95, P99).

**Q: Why use `rate()` in PromQL rather than counting total errors?**
`rate()` computes per-second rate over a time window, normalising for traffic
volume. 100 errors/min at peak (1% error rate) is very different from 100
errors/min at 3 AM (50% error rate). Rate-based alerts fire on error fraction,
not absolute count.

---

## Phase 9 — RAG (Retrieval-Augmented Generation)

### Concept

RAG gives an LLM access to private, current knowledge at query time — without
retraining. You retrieve relevant text from a vector database, inject it into
the prompt, and the LLM reasons over it.

**Critical distinction:** RAG is not training. Model weights don't change.
You're pasting relevant content into the prompt.

### Two Pipelines

```
INGESTION (runs once per document)
Document → Extract text → Chunk into 512-token pieces → Embed (all-MiniLM-L6-v2)
         → Store vectors in ChromaDB + metadata in PostgreSQL

QUERY (runs on every user question)
Question → Embed → Cosine similarity search in ChromaDB → Retrieve top-5 chunks
         → Build context with citations → Inject into LLM prompt → Answer
```

### Chunking Algorithm

```python
# backend/app/services/rag_service.py
def chunk_text(self, text, chunk_size=512, overlap=64):
    tokens = enc.encode(text)        # tiktoken tokenization
    stride  = chunk_size - overlap   # 512 - 64 = 448 tokens advance per step

    start = 0
    while start < len(tokens):
        end = min(start + chunk_size, len(tokens))
        chunks.append(enc.decode(tokens[start:end]))
        start += stride
```

Property 7 guarantee: every token appears in at least one chunk. `stride > 0`
ensures forward progress. Overlap means boundary-spanning sentences appear
in two chunks.

### Embeddings

`all-MiniLM-L6-v2` encodes text as 384-float vectors. Semantically similar
texts produce numerically similar vectors. Cosine similarity measures the
angle between two vectors — near 0° means semantically related.

**Why the same model for both ingestion and query?** Different models produce
incompatible vector spaces. Vectors from model A are meaningless compared to
vectors from model B.

### User Isolation (Property 8)

```python
# Each user's embeddings in a separate ChromaDB collection
collection_name = f"documents_{user_id}"

# PostgreSQL query also enforces isolation:
.where(Document.user_id == user_id)
```

Two enforcement layers: ChromaDB collection namespace + SQL WHERE clause.

### Citations (Property 9)

```python
# Every retrieved chunk includes:
citation = f"[Source: {chunk.document_name}, Page {chunk.page_number}]"
# For TXT/Markdown:
citation = f"[Source: {chunk.document_name}, Chars {start}-{end}]"
```

The LLM cannot hallucinate page numbers that weren't in the retrieved chunks.

### Interview Questions

**Q: What is hallucination and how does RAG reduce it?**
Hallucination is when the LLM generates plausible-sounding but false information.
RAG reduces it by injecting factual grounding: "answer ONLY based on the
provided context." The LLM still can hallucinate, but it's harder when the
correct answer is literally in its context window.

**Q: Why chunk with overlap rather than fixed non-overlapping windows?**
A meaningful sentence can span two chunks. Without overlap, it's split —
retrieval finds chunk 1 OR chunk 2 but not both. With 64-token overlap, any
short sentence appears in at least two chunks, making retrieval more robust.

---

## Phase 10 — AI Error Analysis

### Concept

Phase 10 combines Phase 8 observability data with Phase 9 RAG knowledge to
automatically diagnose production errors. The pipeline:

```
POST /api/v1/analysis/errors
    ↓ 1. Collect ObservabilityEvents (last 30 min, ERROR/CRITICAL)
    ↓ 2. Derive search query from event types + messages
    ↓ 3. RAG: retrieve runbooks + historical incidents from devops_knowledge
    ↓ 4. Build LLM prompt (evidence + context + JSON schema)
    ↓ 5. Call LLM (45s timeout)
    ↓ 6. Parse JSON response
    ↓ 7. Apply AI safety gate (confidence < 0.6 → override root cause)
    ↓ Return ErrorAnalysisResponse
```

### The Confidence Gate

```python
# backend/app/services/error_analysis_service.py
_LOW_CONFIDENCE_THRESHOLD = 0.6

if confidence < _LOW_CONFIDENCE_THRESHOLD:
    likely_root_cause = "Evidence is insufficient — manual investigation required."
    low_confidence_warning = f"Confidence {confidence:.2f} is below 0.6 threshold..."
```

This **overwrites** the LLM's output. If the model thinks it knows but only
has 40% confidence, the response is forced to admit uncertainty. Developers
cannot be misled by a plausible-sounding but unsupported root cause.

### Facts vs Inference Separation

```json
{
  "facts": [
    "DB connection pool at 20/20 at 14:32:01"
  ],
  "inferences": [
    "Pool exhausted likely due to slow queries from recent deployment"
  ]
}
```

The Android dashboard renders these separately. Developers know which parts
are observed data vs LLM reasoning.

### Fallback Chain (AI Safety Principle 10)

```python
if not events:        # no data
    return no_data_response()

if not parsed:        # LLM failed
    return ErrorAnalysisResponse(
        summary="AI analysis unavailable.",
        evidence=[raw events],  # show raw data
        confidence=0.0
    )

if confidence < 0.6:  # low confidence
    # override root cause, add warning
    # but still return full response
```

Never hide errors. Always show the developer something useful.

### Interview Questions

**Q: Why does the error analysis run as a system call (`user_id="system"`)?**
Error analysis is triggered by the system (anomaly detection, developer dashboard),
not a user typing a message. It shouldn't consume the user's rate limit or
appear in conversation history. `user_id="system"` keeps it separate from
user-initiated LLM calls in token usage tracking.

---

## Phase 11 — Anomaly Detection

### Concept

Phase 11 watches the observability data stream continuously and creates incidents
automatically when something unusual is detected — before a human notices.

### Three Detection Stages

**Stage 1 — Rule-based (implemented):**
```python
# Last 5 minutes of ObservabilityEvents
if error_rate > 0.05:     create_incident(severity="HIGH")  # 5% error rate
if error_count > 50:      create_incident(severity="HIGH")  # 50 absolute errors
```

**Stage 2 — Statistical (implemented):**
```python
stats = compute_event_rate_stats(window_minutes=60, bucket_minutes=5)
threshold = stats["mean"] + 2.0 * stats["std_dev"]   # 2-sigma rule
if stats["current"] > threshold: create_incident(severity="MEDIUM")
```

**Stage 3 — ML-based (documented upgrade path):**
Prophet or Isolation Forest. Learns seasonal patterns (Monday spikes). Not yet
implemented — start simple, add complexity when needed.

### Deduplication

```python
if await self._inc_repo.recent_trigger_exists(
    triggered_by=result.rule_name,
    within_minutes=5,
):
    continue  # skip — same rule fired in the last 5 minutes
```

Without dedup, a 30-minute outage generates 30 incidents. With dedup: one
incident stays OPEN for 30 minutes.

### Incident Creation + Phase 10 + Push Notification

```python
# Always: create the incident
incident = await self._inc_repo.create(...)

# Non-blocking: attach AI analysis (LLM failure cannot cancel the incident)
try:
    analysis = await ErrorAnalysisService(db).analyse(...)
    await self._inc_repo.attach_analysis(incident.id, ...)
except Exception:
    logger.warning("analysis failed — incident still created")

# Non-blocking: notify admin users via FCM
await self._notify_admins(incident.id, result.title, result.severity)
```

**Key principle:** incident creation is independent of AI analysis. If the
LLM is down, the developer still gets the incident.

### Interview Questions

**Q: Why have two Stage 1 rules (rate AND count)?**
The rate rule catches proportional failures. The count rule catches absolute
volume failures — at low traffic (3 AM), 60 errors might be 8% rate but the
count rule catches it regardless of traffic volume.

**Q: What would Stage 3 ML detection add?**
Seasonality awareness. Stage 2 computes a 60-minute baseline, but Monday
morning spikes look like anomalies against it (though they're normal). Prophet
or Isolation Forest learns "Monday 9 AM always has 3× normal volume" and
adjusts the baseline accordingly.

---

## Phase 12 — Root Cause Analysis

### Phase 10 vs Phase 12

| Dimension | Phase 10: Error Analysis | Phase 12: RCA |
|-----------|------------------------|---------------|
| Scope | Time window | Specific incident |
| Root cause | Single `likely_root_cause` string | Ranked `RootCauseCandidate` list |
| Evidence | ObservabilityEvents only | Events + server ErrorLogs |
| Timeline | Not built | Chronological merge of all sources |
| Chain of thought | Not exposed | Full LLM reasoning visible |
| Caching | No | Results persisted on Incident row |

### The Unified Timeline

```python
# Merge Android events (APP) + server logs (SRV) chronologically
[14:31:58] APP WARN  api_latency [ChatScreen]: POST /chat 1240ms
[14:32:01] SRV ERROR db_error    [/chat]: Connection pool exhausted (20/20)
[14:32:01] APP ERROR network_error[ChatScreen]: Connection refused
[14:32:15] APP ERROR crash_handled[ChatScreen]: NullPointerException
```

The causal chain is immediately visible on the timeline. Without merging, these
are four separate unrelated facts.

### Chain-of-Thought Prompting

```python
# The prompt instructs the LLM to reason step-by-step:
"""
Think step by step:
1. Analyse the timeline — what does the sequence of events show?
2. Hypothesise — what could have caused this?
3. Rank — which hypothesis is most supported by evidence?
"""
```

CoT produces better results because the LLM can't skip to a conclusion without
examining whether evidence actually supports it. The `chain_of_thought` field
in the response exposes this reasoning to the developer.

### Ranked Candidates

```json
{
  "root_cause_candidates": [
    {
      "rank": 1,
      "cause": "Database connection pool exhausted",
      "confidence": 0.84,
      "supporting_evidence": ["SRV 14:32:01 db_error: pool at 20/20"],
      "reasoning": "Pool at capacity 14 seconds before first app error..."
    },
    {
      "rank": 2,
      "cause": "Recent deployment changed query pattern",
      "confidence": 0.41,
      "reasoning": "No direct evidence but should be verified..."
    }
  ]
}
```

Ranking exposes uncertainty honestly. The developer sees both candidates and
decides how much weight to give each.

---

## Phase 13 — AI DevOps Assistant

### Concept

Phase 13 is the conversational interface on top of Phases 8–12. Instead of
opening separate screens, the developer asks a natural language question and
the assistant orchestrates the right tools.

### The ReAct Pattern (Reason + Act)

```
User: "Why did the API fail at 14:32?"

ROUND 1
  LLM: {"action": "tool_call", "tool": "search_logs", "params": {"level": "ERROR", "minutes": 30}}
  Tool: {count: 23, events: [...23 ERROR events around 14:32...]}

ROUND 2
  LLM: {"action": "tool_call", "tool": "search_incidents", "params": {"status": "OPEN"}}
  Tool: {incidents: [{id: "INC-001", title: "DB pool exhausted"}]}

ANSWER
  LLM: {"action": "answer", "text": "At 14:32, the API failed due to DB connection pool exhaustion (INC-001)..."}
```

### The Seven Tools

```python
tools = [
    search_logs(query, level, event_type, minutes, limit),     # Phase 8 data
    search_incidents(severity, status, limit),                  # Phase 11 data
    search_runbooks(query, category, top_k),                   # Phase 9 RAG
    analyse_errors(lookback_minutes, session_id),              # Phase 10
    get_rca(incident_id, evidence_window_min, force_rerun),    # Phase 12
    get_incident_summary(incident_id),                         # combined view
    create_incident(title, severity, description),             # write action
]
```

All tools except `create_incident` are read-only. `create_incident` has
`requires_confirmation=True` — the broker surfaces this to the HTTP endpoint,
which must present a confirmation dialog before invoking.

### Tool Call Trace — Transparency

```json
{
  "answer": "At 14:32, the API failed due to DB connection pool exhaustion...",
  "tool_calls": [
    {"tool_name": "search_logs", "params": {"level": "ERROR"}, "result": {...}},
    {"tool_name": "search_incidents", "params": {"status": "OPEN"}, "result": {...}}
  ],
  "citations": ["INC-001", "runbooks/db-connection-pool.md"]
}
```

The developer can verify the underlying data. The AI cannot invent log lines —
it can only reference what tools returned.

### Interview Questions

**Q: What is the difference between ReAct and standard RAG?**
RAG always retrieves from a vector store then generates. ReAct is iterative —
the LLM decides which tool to call, calls it, observes the result, and decides
what to do next. For DevOps questions, different questions need different tools:
"show errors" needs `search_logs`; "show incidents" needs `search_incidents`.
ReAct picks the right tool based on the question.

---

## Phase 14 — Android AI DevOps Dashboard

### Architecture

```
DashboardScreen.kt      ← Compose UI, observes StateFlow
    ↓
DashboardViewModel.kt   ← @HiltViewModel, parallel use case orchestration
    ↓
GetIncidentsUseCase     ← IncidentRepository.getIncidents()
AnalyseErrorsUseCase    ← DevOpsRepository.analyseErrors()
AskDevOpsAssistantUseCase ← DevOpsRepository.chat()
    ↓
IncidentRepositoryImpl  ← Retrofit: GET /incidents
DevOpsRepositoryImpl    ← Retrofit: POST /devops/chat, POST /analysis/errors
```

### Parallel Loading

```kotlin
// DashboardViewModel.kt — load() method
val incidentsDeferred = async(dispatchers.io) { getIncidents(limit = 20) }
val analysisDeferred  = async(dispatchers.io) { analyseErrors(lookbackMinutes = 30) }

val incidentsResult = incidentsDeferred.await()  // ~150ms
val analysisResult  = analysisDeferred.await()   // ~8s (LLM call)
```

The incident list appears in ~150ms. The AI analysis card appears ~8s later.
If analysis fails, `aiAnalysis` is null — the incident list still shows.

### Three Independent StateFlows

```kotlin
val uiState:          StateFlow<DashboardUiState>    // dashboard content
val chatState:        StateFlow<ChatUiState>          // DevOps assistant
val remediationState: StateFlow<RemediationUiState>  // Phase 15 AIOps
```

When the user submits a chat question, only `chatState` changes — the incident
list and analysis card don't reload. Independent concerns have independent state.

### Interview Questions

**Q: Why is `aiAnalysis` nullable in `DashboardUiState.Content`?**
If the LLM provider is down, the Phase 10 analysis call fails, but the developer
still needs to see their incidents. Nullable `aiAnalysis` means a failed analysis
produces a dashboard without an analysis card — not a dashboard error. AI Safety
Principle 10: if AI fails, show raw data.

---

## Phase 15 — AIOps

### The Full Loop

```
Observability Events (continuous)
    ↓ Phase 11
Anomaly Detected → Incident Created
    ↓ Phase 10
AI Error Analysis attached
    ↓ Phase 15
Remediation Recommendations generated (ranked, risk-tiered)
    ↓
📱 FCM Push Notification → Developer's phone
    ↓
Developer reviews in Android Dashboard
    ↓
Human Approves or Rejects each action
    ↓
[Phase 15 initial] Approval recorded. Manual execution.
[Phase 15 next]    Automated execution on approval.
```

### Remediation Action Catalogue

| Action | Risk | What it does |
|--------|------|-------------|
| `notify_slack` | LOW | Send Slack alert |
| `create_ticket` | LOW | Create Jira/GitHub issue |
| `restart_service` | MEDIUM | New Cloud Run revision (zero downtime) |
| `scale_up` | MEDIUM | Increase max-instances |
| `scale_down` | MEDIUM | Decrease max-instances |
| `rollback` | HIGH | Route to previous Cloud Run revision |
| `modify_config` | HIGH | Update env var or Secret Manager secret |

### The Approval Model

```python
# backend/app/services/remediation_service.py
async def approve(self, action_id, reviewer_user_id) -> RemediationAction:
    action.status      = "APPROVED"
    action.reviewed_by = reviewer_user_id  # audit trail
    action.reviewed_at = datetime.now(tz=UTC)
    await self._db.commit()
    # ← NO execution here. Intentional.
    # Phase 15 initial delivery: RECOMMENDATION ONLY
```

### Android RemediationCard

```kotlin
// The safety notice is hardcoded — not configurable
Text(
    text = "⚠️ Approval records your decision. No automated execution happens. " +
           "Execute manually using the params shown."
)

// HIGH risk extra warning
if (action.riskTier == "HIGH") {
    Text(
        text = "⚠️ HIGH RISK — verify the previous state is stable before approving.",
        color = MaterialTheme.colorScheme.error
    )
}
```

The human-approval principle is communicated to the developer directly in the UI.

---

## Phase 16 — Security

### Defence in Depth — Seven Layers

```
Layer 1: Transport        — TLS everywhere, certificate pinning (Android)
Layer 2: Authentication   — JWT (15-min) + opaque refresh tokens (30-day)
Layer 3: Authorization    — RBAC: user / premium / admin
Layer 4: Input            — prompt injection detection, request size limits
Layer 5: Rate limiting    — 60 req/min (user), 20 req/min (IP) via Redis
Layer 6: Secrets          — EncryptedSharedPreferences (Android), Secret Manager (GCP)
Layer 7: Audit            — all auth events, AI recommendations, approvals logged
```

### JWT Architecture

```python
# backend/app/security/jwt_handler.py

# Access token: 15-minute expiry, signed HS256
payload = {"sub": user_id, "role": role, "jti": uuid4(), "exp": now + 15min}
token = jwt.encode(payload, settings.SECRET_KEY, algorithm="HS256")

# Refresh token: 30-day, opaque, stored as SHA-256 hash
raw_token  = secrets.token_urlsafe(32)           # 256 bits entropy
token_hash = hashlib.sha256(raw_token.encode()).hexdigest()
# → store only token_hash in DB; return raw_token to client once
```

The refresh token is never stored in plaintext. If the database is breached,
the attacker gets hashes they can't reverse.

### Token Rotation and Replay Detection

`family_id` groups the rotation chain. When a token is used, a new one is
issued with the same `family_id`. If an already-invalidated token is submitted
(replay attack), the entire family is revoked immediately.

### Certificate Pinning (Android)

```xml
<!-- app/src/main/res/xml/network_security_config.xml -->
<domain-config cleartextTrafficPermitted="false">
    <domain includeSubdomains="true">ai-assistant-backend-xxx.asia-south1.run.app</domain>
    <pin-set expiration="2029-02-01">
        <pin digest="SHA-256">evuynPanUbLc9BdX...</pin>  <!-- leaf cert -->
        <pin digest="SHA-256">vh78KSg1Ry4Naqdg...</pin>  <!-- intermediate CA backup -->
    </pin-set>
</domain-config>
```

The backup pin (intermediate CA) ensures the app keeps working through leaf
certificate rotations. **Rotation procedure:** update pin + deploy new cert in
the **same release window** — never rotate the cert before the APK ships.

### Prompt Injection Detection

```python
# backend/app/services/safety_service.py
_INJECTION_PATTERNS = [
    re.compile(r"ignore\s+(all\s+)?previous\s+instructions?", re.IGNORECASE),
    re.compile(r"disregard\s+(all\s+)?previous", re.IGNORECASE),
    ...
]

if injection_detected:
    audit_log(event_type="PROMPT_INJECTION_DETECTED",
              sha256_hash=hashlib.sha256(sanitised_input).hexdigest())
    raise PromptInjectionError()
    # → HTTP 400, PROMPT_INJECTION_DETECTED
```

Log the SHA-256 hash (not raw text — it might contain PII).

### Rate Limiting (Redis sliding-window)

```python
# backend/app/middleware/rate_limit.py
# Authenticated: 60 req/min per user_id
key = f"rate:{user_id}:{minute_window}"
count = await redis.incr(key)
if count > 60: return HTTP 429 with Retry-After header

# Unauthenticated: 20 req/min per IP
key = f"rate:ip:{ip_addr}:{minute_window}"
```

Fail-open: if Redis is unreachable, rate limiting bypasses with a warning log.
A Redis failure should not take down the API.

---

## Phase 17 — Testing

### The Four Test Types

| Type | Question | Speed | Framework |
|------|---------|-------|----------|
| Unit | Does this function work? | ms | Kotest + MockK (Android) / pytest + mock (Python) |
| Integration | Do these components fit together? | s | TestClient + Docker services |
| Property | Does this invariant hold for all inputs? | s | Kotest PropTest (Android) / Hypothesis (Python) |
| UI (Compose) | Does the screen behave correctly? | min | ComposeTestRule |

### Android Unit Test Pattern

```kotlin
// feature-voice/src/test/kotlin/.../VoiceViewModelTest.kt
class VoiceViewModelTest : DescribeSpec({
    val testDispatcher = UnconfinedTestDispatcher()
    val mockSendMessage = mockk<SendMessageUseCase>()

    describe("full voice cycle") {
        it("idle → listening → transcribing → speaking → idle") {
            coEvery { mockSendMessage(any(), any()) } returns ApiResult.Success(testMessage())

            val viewModel = VoiceViewModel(mockSendMessage, TestDispatcherProvider(testDispatcher))
            viewModel.startListening()
            viewModel.uiState.value.shouldBeInstanceOf<VoiceUiState.Listening>()
            viewModel.onSpeechResult("Hello AI")
            viewModel.uiState.value.shouldBeInstanceOf<VoiceUiState.Transcribing>()
        }
    }
})
```

`UnconfinedTestDispatcher` makes coroutines execute synchronously — no need
to advance a virtual clock.

### Android Property Test Pattern

```kotlin
// core-ai/src/test/kotlin/.../StreamEventSchemaPropertyTest.kt
class StreamEventSchemaPropertyTest : DescribeSpec({
    it("valid token frame always produces StreamEvent.Token") {
        checkAll(Arb.string()) { randomText ->
            val frame = """{"type":"token","data":"${randomText.jsonEscape()}"}"""
            parseEvent(frame).shouldBeInstanceOf<StreamEvent.Token>()
        }
    }
})
```

`checkAll(Arb.string())` generates 100+ random strings. If any fail,
Kotest shows the minimal failing input (shrinking).

### Backend Unit Test Pattern

```python
# backend/tests/unit/test_auth_service.py
@pytest.mark.asyncio
async def test_logout_invalidates_all_refresh_tokens():
    repo = AsyncMock(spec=RefreshTokenRepository)
    with patch("app.services.auth_service.RefreshTokenRepository", return_value=repo):
        await logout_user(db=AsyncMock(), user_id=SAMPLE_USER_ID)
    repo.revoke_all_for_user.assert_called_once_with(SAMPLE_USER_ID)
```

Tests verify both the output (what was returned) AND behaviour (what was called).

### Backend Property Test Pattern

```python
# backend/tests/property/test_property_26_rag_format_enforcement.py
@given(
    ext=st.sampled_from(["pdf", "docx", "txt", "md"]),
    size=st.integers(min_value=1, max_value=50 * 1024 * 1024)
)
def test_valid_files_accepted(ext, size):
    """For ALL valid formats and ALL valid sizes ≤ 50MB, upload is accepted."""
    response = client.post("/documents", files={"file": (f"test.{ext}", data)})
    assert response.status_code == 202

@given(ext=st.sampled_from(["exe", "js", "zip"]))
def test_invalid_formats_rejected(ext, size):
    response = client.post("/documents", ...)
    assert response.status_code == 422
    mock_minio.put_object.assert_not_called()  # no storage I/O on invalid input
```

### The 70% Coverage Gate

JaCoCo gate on `domain` + `data` combined instruction coverage, enforced by CI:

```bash
# .github/scripts/check-coverage.sh
combined_pct = (domain_covered + data_covered) / (total_covered + total_missed) * 100
if combined_pct < 70: exit 1
```

The gate is per-PR. Coverage can only go up over the project lifetime.

### Interview Questions

**Q: Why is the 70% coverage gate on domain+data specifically, not all modules?**
UI code has diminishing test returns and requires an emulator. `domain` and
`data` contain the business rules and persistence logic — most bugs live here.
They're pure Kotlin, testable without Android framework, fast to run.

---

## Phase 18 — Production CI/CD

### What "Production" Means Beyond "Tests Pass"

Every merge to main should be deployable to production immediately. That requires
additional gates beyond testing:

```
Architecture enforcement    → check-module-deps.sh
DI correctness              → hilt-ksp-gate (KSP code generation)
Coverage                    → jacoco-gate (≥70%)
Security scans              → CodeQL, Gitleaks, Bandit, Trivy, pip-audit
Infrastructure validation   → Docker Compose, Nginx, Prometheus, Alembic
Certificate pin consistency → check-tls-pin.sh
```

### Infrastructure Validation Workflow

`infrastructure-validation.yml` runs on every PR touching infrastructure files:

| Job | What it validates |
|-----|-----------------|
| `validate-docker-compose` | `docker compose config --quiet` |
| `validate-nginx` | `nginx -t` inside container |
| `validate-prometheus` | `promtool check config` + `check rules` |
| `validate-alembic` | `upgrade head → downgrade base` on real PostgreSQL |
| Detect multiple Alembic heads | `alembic heads` must show exactly one |
| `validate-grafana` | JSON parse all dashboard files |

The Alembic bidirectional check is the most important: proves every migration
can be rolled back, catching migration chain bugs at PR time not at 2 AM.

### The Deployment Gate Sequence

```
Push to main
    ↓ build + push Docker image (tagged by git SHA)
    ↓ run Alembic migrations (Cloud Run Job — executes and exits)
    ↓ deploy to Cloud Run (new revision, rolling update)
    ↓ smoke test: GET /health must return 200
    ↓ GET /ready must return 200
    ↓ [BLOCKED] manual reviewer approval (GitHub Environment protection)
    ↓ deploy to production
    ↓ post-release smoke test
    ↓ Slack notification
```

### Image Immutability + Cosign Signing

```yaml
# Tag by git SHA — immutable
image: asia-south1-docker.pkg.dev/project/backend/api:sha-abc1234

# Run Trivy CRITICAL scan before pushing
- uses: aquasecurity/trivy-action@0.28.0
  with:
    exit-code: '1'
    severity: 'CRITICAL'

# Sign with keyless OIDC — no stored private key
- run: cosign sign --yes "$IMAGE@$DIGEST"
```

The cosign signature proves: "This image was built from commit `abc1234`
in this specific repository by GitHub Actions." Verifiable without a pre-shared key.

---

## Phase 19 — Jenkins

### Core Architecture

```
Jenkins Controller   ← web UI, pipeline engine, credential store
    │
    ├── Agent (Linux/Docker)    ← runs Android/Python builds
    ├── Agent (macOS)           ← runs iOS builds (if needed)
    └── Agent (ephemeral)       ← Docker container per job, destroyed after
```

### Jenkinsfile (Declarative Pipeline)

```groovy
pipeline {
    agent { docker { image 'python:3.11-slim' } }

    environment {
        SECRET_KEY = credentials('ai-assistant-secret-key')
    }

    stages {
        stage('Test') {
            steps {
                sh 'cd backend && pytest tests/unit/ --junit-xml=results.xml'
            }
            post { always { junit 'backend/results.xml' } }
        }

        stage('Deploy to Production') {
            when { branch 'main' }
            steps {
                input(
                    message: 'Deploy to production?',
                    submitter: 'devops-leads'   // only these users can approve
                )
                sh 'gcloud run deploy ai-assistant-backend ...'
            }
        }
    }

    post {
        failure { slackSend(channel: '#ci', message: "FAILED: ${JOB_NAME}") }
    }
}
```

### GitHub Actions vs Jenkins

| Dimension | GitHub Actions | Jenkins |
|-----------|---------------|---------|
| Setup | Zero (built-in) | Install + maintain server |
| Cost | Free for public repos | Infrastructure cost |
| Plugin ecosystem | GitHub Marketplace | 1,800+ plugins |
| Enterprise integration | GitHub-centric | LDAP, Artifactory, Jira |
| Best for | Greenfield, cloud-native | Enterprise, existing infra |

Jenkins is not implemented in this project — it's a learning target for
enterprise contexts where Jenkins is already deployed.

---

## Phase 20 — Kubernetes

### Six Core Concepts

**Pod** — smallest unit: one or more containers sharing network + storage.

**Deployment** — manages desired replica count, rolling updates, rollbacks.

**Service** — stable network endpoint (fixed IP) routing to Pods by label selector.

**ConfigMap** — non-secret config as env vars or files.

**Secret** — sensitive config (base64-encoded or from External Secrets Operator).

**HPA** — scales Pod replicas based on CPU/memory/custom metrics.

### Rolling Deployment (Zero Downtime)

```
kubectl apply -f deployment.yaml  (new image sha-xyz789)
    ↓ New Pod 1 starts → passes readinessProbe → Old Pod 1 terminated
    ↓ New Pod 2 starts → passes readinessProbe → Old Pod 2 terminated
    ↓ New Pod 3 starts → passes readinessProbe → Old Pod 3 terminated
    ↓ 100% traffic on new image
```

If the readiness probe fails, the rollout stops. Old Pods keep serving traffic.

### Probes — both required

```yaml
readinessProbe:      # controls traffic routing — remove from Service if fails
  httpGet:
    path: /ready
    port: 8000

livenessProbe:       # controls container lifecycle — restart if fails
  httpGet:
    path: /health
    port: 8000
```

Readiness: "am I ready to receive traffic?" (slow startup, connection pools)
Liveness: "am I still functioning?" (deadlocks, memory leaks)

### Cloud Run vs Kubernetes

| Use Cloud Run when... | Use Kubernetes when... |
|----------------------|----------------------|
| Stateless HTTP, variable traffic | Stateful workloads |
| Cost priority (scale to zero) | Fine-grained resource control |
| Small team | Dedicated platform team |
| Simple networking | Complex service mesh |
| No background workers | Long-running jobs, custom hardware |

**This project uses Cloud Run correctly.** The architecture is designed to
migrate to GKE Autopilot when scale demands it — the same YAML format, less
operational overhead.

---

## Appendix — How All Phases Connect

### The Complete Data Flow

```
Phase 2: Android instruments app
         └── ObservabilityEvent{crash_unhandled, http_error, api_latency}

Phase 8: Events stored in PostgreSQL, exposed via /metrics
         └── Prometheus scrapes → Grafana dashboards

Phase 9: DevOps knowledge base (runbooks, incidents) indexed in ChromaDB
         └── MiniLM-L6-v2 embeddings, 512-token chunks

Phase 11: AnomalyDetectionService (Celery beat, every 60s)
          └── error_rate > 5% → Incident created → Phase 10 fires

Phase 10: ErrorAnalysisService
          └── Evidence + RAG context + LLM → ErrorAnalysisResponse
          └── confidence=0.82, root_cause="DB pool exhausted"

Phase 12: RcaService (developer-initiated)
          └── Merged timeline + chain-of-thought + ranked candidates

Phase 13: DevOpsAssistantService
          └── ReAct loop: question → tool calls → grounded answer

Phase 14: Android Dashboard
          └── Parallel: incidents (150ms) + AI analysis (8s)
          └── Chat: POST /devops/chat → tool calls → answer with citations

Phase 15: RemediationService
          └── Ranked actions (LOW/MEDIUM/HIGH) + human approval
          └── FCM push notification → developer's phone

Phase 16: All of the above protected by:
          └── JWT auth, certificate pinning, prompt injection detection,
              rate limiting, AES-256 encryption, audit logs
```

### The Learning Progression

```
Phases 1–2:   Android foundation + observability (your expertise)
              The data origin layer — generates events that feed everything else

Phases 3–7:   Infrastructure (FastAPI, DevOps, Docker, GCP, Terraform)
              The platform layer — where the data lands and runs

Phases 8:     Observability (logs, metrics, traces)
              The data collection layer — making the platform visible

Phase 9:      RAG
              The knowledge retrieval layer — gives LLMs access to your docs

Phases 10–13: AI analysis pipeline
              The intelligence layer — turns data into diagnosis

Phase 14:     Android Dashboard
              The interface layer — where the developer sees everything

Phase 15:     AIOps
              The control layer — closes the loop with human-approved actions

Phases 16–20: Security, Testing, CI/CD, Jenkins, Kubernetes
              The production hardening layer — makes everything safe and reliable
```

### Career Trajectory

```
Android Developer → DevOps Engineer → GenAI Engineer → AIOps Engineer
     Phase 1-2          Phase 3-8          Phase 9-13        Phase 14-15
```

Every phase builds on what came before. The Android observability events
(Phase 2) are the raw material for everything in Phases 8–15. The RAG
infrastructure (Phase 9) powers both the user-facing document chat and the
AI error analysis pipeline. The security controls (Phase 16) protect every
API call made by every phase.

This is not a collection of independent topics. It is one system, built
incrementally, where each phase is a prerequisite for the next.

---

## Quick Reference: Files by Phase

| Phase | Key Files in This Repo |
|-------|----------------------|
| 1 | `core-common/`, `domain/`, `data/`, any `feature-*/` ViewModel |
| 2 | `core-common/.../observability/`, `app/.../observability/`, `core-network/.../observability/` |
| 3 | `backend/app/main.py`, `backend/app/services/`, `backend/app/api/` |
| 4 | `.github/workflows/android-ci.yml`, `.github/scripts/` |
| 5 | `backend/Dockerfile`, `docker-compose.yml` |
| 6 | `.github/workflows/cloud-run-deploy.yml` |
| 7 | `terraform/` (all files) |
| 8 | `backend/app/observability/`, `backend/app/middleware/logging_middleware.py`, `infrastructure/prometheus/` |
| 9 | `backend/app/services/rag_service.py` |
| 10 | `backend/app/services/error_analysis_service.py`, `backend/app/schemas/error_analysis.py` |
| 11 | `backend/app/services/anomaly_detection_service.py` |
| 12 | `backend/app/services/rca_service.py` |
| 13 | `backend/app/services/devops_assistant_service.py`, `backend/app/services/mcp_connectors/devops_connectors.py` |
| 14 | `feature-dashboard/`, `data/.../devops/`, `domain/.../devops/` |
| 15 | `backend/app/services/remediation_service.py`, `feature-dashboard/components/RemediationCard.kt` |
| 16 | `backend/app/security/jwt_handler.py`, `backend/app/middleware/rate_limit.py`, `core-security/`, `app/src/main/res/xml/network_security_config.xml` |
| 17 | `**/src/test/kotlin/`, `backend/tests/` |
| 18 | `.github/workflows/infrastructure-validation.yml`, `.github/scripts/check-coverage.sh` |
| 19 | Conceptual — no Jenkinsfile in this repo |
| 20 | Conceptual — no Kubernetes manifests in this repo |

---

*This document is generated from the AI DevOps Master Plan session.
Every code example is taken directly from the repository at
`j:\Android\AndroidStudioProjects\Kiro\TestBranch\Develop_Main\Android-AI-Assistant-Test`.*
