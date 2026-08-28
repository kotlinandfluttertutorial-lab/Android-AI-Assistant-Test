# AI DevOps Assistant — Complete Project Reference

> One document covering all 20 phases: what was built, why, how it works,
> and how every component connects to the next.
>
> **Reading order:** Top to bottom follows the learning path.
> Android → DevOps → Cloud → GenAI → AIOps.

---

## Table of Contents

1. [Project Vision](#1-project-vision)
2. [Architecture Overview](#2-architecture-overview)
3. [Phase 1–2 — Android Foundation & Observability](#3-phase-12--android-foundation--observability)
4. [Phase 3 — Backend (FastAPI)](#4-phase-3--backend-fastapi)
5. [Phase 4–5 — DevOps Foundation & Docker](#5-phase-45--devops-foundation--docker)
6. [Phase 6–7 — Google Cloud & Terraform](#6-phase-67--google-cloud--terraform)
7. [Phase 8 — Observability (Logs/Metrics/Traces)](#7-phase-8--observability-logsmetricstraces)
8. [Phase 9 — RAG Knowledge Base](#8-phase-9--rag-knowledge-base)
9. [Phase 10 — AI Error Analysis](#9-phase-10--ai-error-analysis)
10. [Phase 11 — Anomaly Detection](#10-phase-11--anomaly-detection)
11. [Phase 12 — Root Cause Analysis](#11-phase-12--root-cause-analysis)
12. [Phase 13 — AI DevOps Assistant](#12-phase-13--ai-devops-assistant)
13. [Phase 14 — Android AI DevOps Dashboard](#13-phase-14--android-ai-devops-dashboard)
14. [Phase 15 — AIOps](#14-phase-15--aiops)
15. [Phase 16 — Security](#15-phase-16--security)
16. [Phase 17 — Testing](#16-phase-17--testing)
17. [Phase 18 — Production CI/CD](#17-phase-18--production-cicd)
18. [Phase 19 — Jenkins](#18-phase-19--jenkins)
19. [Phase 20 — Kubernetes](#19-phase-20--kubernetes)
20. [Interview Cheat Sheet](#20-interview-cheat-sheet)
21. [Running the Project](#21-running-the-project)
22. [File Map](#22-file-map)

---

## 1. Project Vision

Build an **AI DevOps Assistant** that monitors a production Android + FastAPI system,
detects anomalies, performs AI-powered root cause analysis, and closes the AIOps loop
by notifying the developer and guiding them through remediation — with human approval
before any production action is taken.

```
Android App → Backend Services → Observability → AI Analysis → Dashboard
                                                      ↕
                                          Human Approval Required
```

### What the system can do

| Capability | How it works |
|-----------|-------------|
| Detect error rate spikes | Stage 1 rule: error_rate > 5% in 5 min → HIGH incident |
| Detect statistical anomalies | Stage 2: current > mean + 2σ over 60-min window |
| Analyse errors with AI | RAG over knowledge base + LLM → structured JSON with confidence |
| Root cause analysis | Multi-source evidence + chain-of-thought LLM → ranked candidates |
| Answer DevOps questions | ReAct loop: tool calls → grounded answer with citations |
| Recommend remediation | Risk-tiered actions (LOW/MEDIUM/HIGH) — recommendation only |
| Notify developer | FCM push notification to Android app on incident creation |
| Dashboard | Android Compose UI showing incidents, AI analysis, approve/reject |

### AI Safety Principles (enforced throughout)

1. Never invent data — only analyse real logs and events
2. Never claim unsupported root causes — every conclusion cites evidence
3. Provide confidence levels — communicate uncertainty explicitly (0.6 gate)
4. Separate facts from inferences — label each clearly
5. Never expose secrets — PII scrubbed before any LLM call
6. **Require human approval — no production action without explicit approval**
7. Validate tool outputs before using them in prompts
8. Protect against prompt injection — sanitize all inputs
9. Audit trail — log every AI recommendation and human decision
10. Graceful degradation — fall back to raw data if AI analysis fails

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│  Android App (Kotlin + Jetpack Compose)                          │
│                                                                  │
│  feature-auth  feature-chat  feature-rag  feature-dashboard      │
│  core-network  core-ui  core-common  core-security               │
│  ObservabilityEventBus → WorkManager → POST /observability/events│
└──────────────────────────────┬──────────────────────────────────┘
                               │ HTTPS
                               ▼
┌─────────────────────────────────────────────────────────────────┐
│  Cloud Run — FastAPI Backend (asia-south1)                        │
│                                                                  │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────────────┐   │
│  │ /auth    │ │ /chat    │ │ /rag     │ │ /devops/chat     │   │
│  │ /users   │ │ /ws/chat │ │ /analysis│ │ /incidents       │   │
│  └──────────┘ └──────────┘ └──────────┘ └──────────────────┘   │
│                                                                  │
│  AIOrchestrator  ErrorAnalysisService  RcaService               │
│  AnomalyDetectionService  RemediationService  DevOpsAssistant   │
│  RAGService  MCPBroker (7 DevOps tools)                         │
└──────┬────────────┬──────────────┬────────────────┬────────────┘
       │            │              │                │
       ▼            ▼              ▼                ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐ ┌────────────────┐
│  Neon    │ │  Redis   │ │  ChromaDB    │ │  Cloud Storage │
│ Postgres │ │ (cache,  │ │  (vectors,   │ │  (documents,   │
│ (primary)│ │  Celery) │ │  devops_kb)  │ │   raw files)   │
└──────────┘ └──────────┘ └──────────────┘ └────────────────┘

       │            │
       ▼            ▼
┌─────────────────────────────────────────────────────────────────┐
│  LLM Providers                                                   │
│  OpenAI (GPT-4o)  Google Gemini  Anthropic Claude               │
│  Primary → Fallback → Emergency fallback                        │
└─────────────────────────────────────────────────────────────────┘

Observability:
  stdout (JSON) → Cloud Logging
  /metrics      → Prometheus → Grafana (3 dashboards)
  OpenTelemetry → Cloud Trace
  Celery beat   → Anomaly detection every 60s
  FCM           → Developer Android device
```

---

## 3. Phase 1–2 — Android Foundation & Observability

### Module structure

```
app/                  — activity, navigation, DI wiring, HomeDashboard
core-common/          — ApiResult, DomainError, DispatcherProvider, ObservabilityEvent
core-ui/              — AppTheme (Material 3), Spacing, Color, shared components
core-network/         — Retrofit, OkHttp interceptors, ObservabilityUploadWorker
core-database/        — Room DAOs, entities, converters
core-security/        — SecureStorage, BiometricAuth, CertificatePinningInterceptor
domain/               — models, repository interfaces, use cases
data/                 — repository implementations, Retrofit services, DI modules
feature-auth/         — login, register, biometric unlock, Google sign-in
feature-chat/         — conversational AI, streaming WebSocket, comparison mode
feature-rag/          — document upload, ingestion polling, per-document Q&A
feature-dashboard/    — AI DevOps Dashboard (Phase 14)
feature-voice/        — speech-to-text
feature-settings/     — provider selection, theme, cost dashboard
(+12 other feature modules)
```

### Architecture pattern

```
Compose UI (DashboardScreen)
    ↓ collects StateFlow
ViewModel (DashboardViewModel) — @HiltViewModel
    ↓ calls use cases
UseCase (GetIncidentsUseCase) — single-responsibility
    ↓ calls repository interface
Repository Interface (IncidentRepository) — domain layer contract
    ↓ implemented by
RepositoryImpl (IncidentRepositoryImpl) — data layer
    ↓ calls
RemoteDataSource → Retrofit → Backend API
```

### Observability pipeline (Phase 2)

```
HTTP call (OkHttp interceptor)
  ↓ emits ObservabilityEvent (PII-filtered)
ObservabilityEventBus (SharedFlow, buffer=64, non-blocking)
  ↓ collected by
ObservabilityManager (in-memory ring buffer, max 500 events)
  ↓ drained every 15 min by
ObservabilityUploadWorker (WorkManager, CONNECTED constraint)
  ↓ POST /api/v1/observability/events
Backend → observability_events PostgreSQL table
  ↓ read by Phase 10/11/12/13
AI analysis pipelines
```

**PII filtering** (before any event leaves the device):
- Email addresses → `[email]`
- Phone numbers → `[phone]`
- Bearer/JWT tokens → `[token]`
- Authorization headers → `[redacted]`
- Credit cards → `[card]`
- IPv4 addresses → `[ip]`

---

## 4. Phase 3 — Backend (FastAPI)

### Directory structure

```
backend/app/
├── api/           — FastAPI routers (25+ endpoints)
│   ├── auth/      ├── chat/       ├── rag/
│   ├── analysis/  ├── incidents/  ├── devops/
│   ├── observability/             ├── admin/
├── services/      — Business logic
│   ├── ai_orchestrator.py         (LLM routing, streaming, safety)
│   ├── rag_service.py             (embed, store, query)
│   ├── error_analysis_service.py  (Phase 10)
│   ├── anomaly_detection_service.py (Phase 11)
│   ├── rca_service.py             (Phase 12)
│   ├── devops_assistant_service.py (Phase 13)
│   └── remediation_service.py     (Phase 15)
├── models/        — SQLAlchemy ORM (13+ tables)
├── repositories/  — Data access layer
├── schemas/       — Pydantic request/response models
├── workers/       — Celery tasks
│   ├── rag_worker.py, notification_worker.py
│   ├── anomaly_worker.py (Phase 11 — every 60s)
│   └── alert_worker.py (spending alerts)
├── middleware/    — Logging, rate limiting, data residency
├── observability/ — JSON formatter, OpenTelemetry setup
└── config/settings.py — All configuration with Pydantic validation
```

### Key endpoints

| Method | Path | Purpose |
|--------|------|---------|
| POST | `/api/v1/auth/login` | Authenticate, receive JWT + refresh token |
| POST | `/api/v1/auth/google` | Exchange Google ID token for JWT |
| WS | `/ws/chat/{conversation_id}` | Streaming AI chat |
| POST | `/api/v1/documents` | Upload document for RAG ingestion |
| POST | `/api/v1/documents/query` | Semantic search with AI answer |
| POST | `/api/v1/observability/events` | Ingest Android events (no auth) |
| POST | `/api/v1/analysis/errors` | AI error analysis |
| GET | `/api/v1/incidents` | List incidents |
| POST | `/api/v1/incidents/{id}/rca` | Run RCA for incident |
| POST | `/api/v1/devops/chat` | ReAct DevOps assistant |
| POST | `/api/v1/incidents/{id}/remediation/recommend` | Remediation plan |
| POST | `/api/v1/incidents/{id}/remediation/{action_id}/approve` | Human approval |

---

## 5. Phase 4–5 — DevOps Foundation & Docker

### CI pipeline (GitHub Actions)

```
Push to GitHub
    ↓
.github/workflows/
├── android-ci.yml      — Build, test, lint, Detekt, security scan
├── backend-ci.yml      — pytest, Bandit, pip-audit, Docker build, deploy
├── release.yml         — Tag-triggered: sign APK/AAB, Firebase distribution
└── security-scan.yml  — Trivy, Gitleaks, CodeQL, TLS pin check
```

### Docker multi-stage build

```dockerfile
# Stage 1: builder — installs dependencies
FROM python:3.11-slim AS builder
RUN pip install --target /install -r requirements.txt

# Stage 2: production — lean runtime image
FROM python:3.11-slim AS production
# Copy only installed packages, not build tools
COPY --from=builder /install /usr/local/lib/python3.11/site-packages
# Non-root user (security requirement)
RUN useradd --no-create-home appuser
USER appuser
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
```

### docker-compose.yml (local development)

```
Services: postgres:16, redis:7, minio, chromadb, backend (api), celery_worker
```

### docker-compose.prod.yml (full observability)

```
Adds: prometheus:9090, loki:3100, grafana:3000
```

---

## 6. Phase 6–7 — Google Cloud & Terraform

### GCP services used

| Service | Purpose | Cost |
|---------|---------|------|
| Cloud Run | Serverless containers (FastAPI + ChromaDB) | ₹0–150/mo |
| Neon PostgreSQL | Serverless managed DB | ₹0 (free tier) |
| Artifact Registry | Docker image registry | ₹0–40/mo |
| Secret Manager | Credentials store | ₹0–5/mo |
| Cloud Storage | Raw documents + HMAC for minio SDK | ₹0–80/mo |
| Cloud Logging | Structured log storage | ₹0 (50GB free) |
| Cloud Trace | Distributed tracing (OpenTelemetry) | ₹0 (free tier) |

**Total: ₹200–775/month** (LLM usage is the main variable cost)

### Terraform structure

```
terraform/
├── providers.tf, variables.tf, backend.tf, main.tf, outputs.tf
└── modules/
    ├── iam/              — service account + WIF + project roles
    ├── artifact_registry/ — Docker registry
    ├── storage/          — GCS bucket + HMAC → Secret Manager
    └── cloud_run/        — FastAPI + ChromaDB services
environments/
├── dev/terraform.tfvars   — min-instances=0, 512Mi, lower token limits
└── prod/terraform.tfvars  — max-instances=2, 1Gi, full token limits
```

**Workload Identity Federation**: GitHub Actions authenticates to GCP with
short-lived OIDC tokens. No service account JSON key file exists anywhere.

### Deployment steps

```powershell
# 1. Create state bucket (one-time)
gsutil mb -l asia-south1 gs://android-ai-assistant-89cec-tfstate

# 2. Init and apply Terraform
cd terraform
terraform init -backend-config="prefix=terraform/state/prod"
terraform apply -var-file="environments/prod/terraform.tfvars"

# 3. Seed knowledge base after ChromaDB starts
python backend/scripts/seed_knowledge.py
```

---

## 7. Phase 8 — Observability (Logs/Metrics/Traces)

### Three pillars

| Pillar | Question it answers | Implementation |
|--------|-------------------|----------------|
| **Logs** | What happened? | JSON formatter → Cloud Logging + Loki |
| **Metrics** | How is the system behaving? | Prometheus → Grafana (3 dashboards) |
| **Traces** | Where did time go? | OpenTelemetry → Cloud Trace |

### Structured logging (JSON to stdout)

Every log line is valid JSON — Cloud Logging auto-parses fields:

```json
{
  "timestamp": "2026-08-26T14:32:01.123Z",
  "severity": "ERROR",
  "message": "request",
  "correlation_id": "a1b2c3d4",
  "user_id": "user-456",
  "path": "/api/v1/chat",
  "status_code": 500,
  "response_time_ms": 3201.5
}
```

### Prometheus metrics exposed at `/metrics`

| Metric | Type | What it measures |
|--------|------|-----------------|
| `http_requests_total` | Counter | Request count by endpoint and status |
| `http_request_duration_seconds` | Histogram | P50/P95/P99 latency |
| `celery_queue_depth` | Gauge | Pending tasks |
| `celery_failed_tasks_total` | Counter | Failed tasks by task name |
| `llm_token_cost_usd_total` | Counter | LLM spend by provider |
| `llm_output_tokens_total` | Counter | Token usage by provider |

### Alerting rules (10 rules across 5 groups)

| Alert | Condition | Severity |
|-------|-----------|----------|
| `HighHTTP5xxErrorRate` | > 5% 5xx for 2 min | critical |
| `HighP95Latency` | P95 > 2s for 3 min | warning |
| `CriticalP99Latency` | P99 > 10s for 2 min | critical |
| `LLMCostSpike` | > $0.10/min for 5 min | critical |
| `HighCeleryTaskFailureRate` | > 0.05/s for 2 min | warning |
| `BackendDown` | `/metrics` unreachable for 1 min | critical |

### OpenTelemetry

Auto-instruments: FastAPI (span per route), SQLAlchemy (span per query),
httpx (span per outbound call), Redis (span per command).

Exports to Cloud Trace on Cloud Run (via ADC — no config needed) or local Jaeger
(`OTEL_EXPORTER_OTLP_ENDPOINT=http://jaeger:4317`).

---

## 8. Phase 9 — RAG Knowledge Base

### Knowledge documents

```
knowledge/
├── runbooks/
│   ├── service-restart.md      — restart Cloud Run (force new revision)
│   ├── database-recovery.md    — Neon cold start, pool exhaustion, bad URL
│   ├── scaling.md              — increase max-instances, warm instances
│   └── rollback.md             — route traffic to previous revision
├── incidents/
│   ├── INC-001-db-connection-pool.md   — pool exhaustion by LLM calls (47 min)
│   ├── INC-002-llm-timeout.md          — OpenAI rate limit + broken fallback
│   └── INC-003-chroma-cold-start.md    — empty RAG results after deploy
├── architecture/
│   ├── system-overview.md     — full service map, auth flow, security
│   ├── rag-pipeline.md        — ingestion + retrieval diagram
│   └── api-endpoints.md       — all endpoints with examples
└── deployment/
    ├── cloud-run-deploy.md    — full deploy guide, post-deploy checklist
    ├── secrets-management.md  — rotation, HMAC keys, emergency procedures
    └── migrations.md          — Alembic commands, two-phase deploy, Neon tips
```

### RAG pipeline

```
Documents → Text extraction (pypdf/python-docx/UTF-8)
         → Tiktoken chunking (512 tokens, 64 overlap)
         → SentenceTransformer embedding (all-MiniLM-L6-v2, 384-dim)
         → ChromaDB collection: devops_knowledge
         → Cosine similarity search at query time (top-K=5)
         → Context string with citations → LLM prompt → Answer
```

### Seeding

```bash
# Runs automatically at every startup (non-fatal)
python backend/scripts/seed_knowledge.py

# Or via admin endpoint
curl -X POST http://localhost:8000/api/v1/admin/rag/reindex \
  -H "Authorization: Bearer ADMIN_JWT"
```

---

## 9. Phase 10 — AI Error Analysis

### Pipeline

```
POST /api/v1/analysis/errors
  {"lookback_minutes": 30}
         ↓
1. Collect ERROR/CRITICAL ObservabilityEvents (last 30 min from PostgreSQL)
2. Derive search query from event types + messages
3. RAG: query devops_knowledge (runbooks category, top-5)
4. RAG: query devops_knowledge (incidents category, top-5)
5. Build LLM prompt: evidence + runbook context + incident context
6. LLM call: AIOrchestrator.complete() [45s timeout]
7. Parse JSON response (handles markdown fences)
8. Confidence gate: < 0.6 → override with "Evidence insufficient"
         ↓
ErrorAnalysisResponse {
  severity, summary, evidence, possible_causes,
  likely_root_cause, confidence (0.0–1.0),
  recommended_fix, related_documentation,
  facts_vs_inference: {facts: [...], inferences: [...]},
  low_confidence_warning, events_analysed, llm_provider
}
```

**Confidence gate (applied in code, not just the prompt):**
```python
if confidence < 0.6:
    likely_root_cause = "Evidence is insufficient — manual investigation required."
    low_confidence_warning = f"Confidence {confidence:.2f} is below 0.6..."
```

---

## 10. Phase 11 — Anomaly Detection

### Detection cycle (every 60 seconds via Celery beat)

```
AnomalyDetectionService.run_detection_cycle()
    │
    ├── Stage 1: Rule-based
    │   ├── error_rate = count_errors(5min) / count_all(5min)
    │   │   > 5% → HIGH incident ("error_rate" trigger)
    │   └── error_count = count_errors(5min)
    │       > 50 → HIGH incident ("error_count" trigger)
    │
    └── Stage 2: Statistical
        stats = compute_event_rate_stats("ERROR", window=60min, bucket=5min)
        current > mean + 2σ → MEDIUM incident ("error_spike_statistical" trigger)
    │
    ├── Dedup: recent_trigger_exists(rule, within=5min) → skip if duplicate
    │
    └── For each anomaly:
        1. IncidentRepository.create() → Incident (status: OPEN)
        2. ErrorAnalysisService.analyse() → attach Phase 10 analysis
        3. _notify_admins() → send_push_notification.delay() via FCM
```

### Thresholds (matches Prometheus alerting rules)

```python
ERROR_RATE_THRESHOLD  = 0.05   # 5% — matches HighHTTP5xxErrorRate
ERROR_COUNT_THRESHOLD = 50     # absolute count
STAT_STD_MULTIPLIER   = 2.0    # mean + 2σ — alerts on top 2.5%
DEDUP_WINDOW_MIN      = 5      # prevents 60 identical incidents per hour
```

---

## 11. Phase 12 — Root Cause Analysis

### What makes RCA different from error analysis

| | Phase 10 Error Analysis | Phase 12 RCA |
|-|------------------------|--------------|
| Scope | Time window | Specific incident (by ID) |
| Evidence | ObservabilityEvents only | Events + server ErrorLogs |
| Output | Single `likely_root_cause` | **Ranked candidates** (each with own confidence) |
| Reasoning | Implicit | Explicit `chain_of_thought` field |
| Timeline | Not produced | Correlated across all sources |

### Pipeline (9 steps)

```
POST /api/v1/incidents/{id}/rca
  1. Load Incident + Phase 10 analysis
  2. Collect ObservabilityEvents (around detected_at ± 30min)
  3. Collect server ErrorLogs (around detected_at ± 30min)
  4. Build correlated timeline (APP + SRV events, oldest→newest)
  5. Derive search query → RAG (runbooks + incidents)
  6. Build chain-of-thought prompt:
     "Analyse → Hypothesise → Rank → Conclude"
  7. LLM call [60s timeout]
  8. Parse → build RootCauseCandidate list (per-candidate confidence)
  9. Safety gate → persist on Incident row (rca_candidates_json)
         ↓
RcaAnalysisResponse {
  rca_id, incident_id, summary,
  root_cause_candidates: [{rank, cause, confidence, supporting_evidence, reasoning}],
  overall_confidence, timeline, chain_of_thought,
  investigation_steps, related_documentation,
  low_confidence_warning
}
```

### Caching

First call runs the full pipeline. Subsequent calls return cached result.
`force_rerun: true` to override.

---

## 12. Phase 13 — AI DevOps Assistant

### The 7 DevOps tools

| Tool | Data source | Example use |
|------|------------|-------------|
| `search_logs` | `observability_events` table | "What errors happened at 14:32?" |
| `search_incidents` | `incidents` table | "Show critical open incidents" |
| `search_runbooks` | `devops_knowledge` ChromaDB | "How do I restart the service?" |
| `analyse_errors` | Phase 10 pipeline | "What is causing current errors?" |
| `get_rca` | Phase 12 pipeline | "What is the root cause of INC-xxx?" |
| `get_incident_summary` | `incidents` + Phase 10/12 data | "Tell me about INC-xxx" |
| `create_incident` | `incidents` table (write) | "Create an incident" (requires confirmation) |

### ReAct loop

```
User: "Why did the API fail at 14:32?"
    ↓
Prompt: [SYSTEM: tools description] [USER: question]
    ↓
LLM → {"action": "tool_call", "tool": "search_logs", "params": {"level":"ERROR","minutes":60}}
    ↓
MCPBroker.invoke("search_logs", ...) → {count: 23, events: [...]}
    ↓
Messages: [..., TOOL RESULT: {23 events}]
    ↓
LLM → {"action": "tool_call", "tool": "search_incidents", "params": {"status":"OPEN"}}
    ↓
MCPBroker.invoke("search_incidents", ...) → {INC-001: ...}
    ↓
LLM → {"action": "answer", "text": "At 14:32, the API...", "citations": ["INC-001"]}
    ↓
DevOpsChatResponse {answer, citations, tool_calls, rounds_used}
```

Max 3 tool rounds per question. Grounding constraint: "Only use data from tool results."

---

## 13. Phase 14 — Android AI DevOps Dashboard

### What the screen shows

```
┌─────────────────────────────────┐
│  AI DevOps Dashboard        [↻] │
├─────────────────────────────────┤
│  Critical  2  │  High  5        │
│  Medium    1  │  Open  8        │
├─────────────────────────────────┤
│  AI Error Analysis          ▾   │
│  DB connection pool exhausted   │
│  Confidence: ████████░░  87%   │
│  Root cause: LLM calls hold…    │
│  Fix: Add asyncio.wait_for…     │
├─────────────────────────────────┤
│  Recent Incidents (8)           │
│  INC-xxx  DB timeout  HIGH●     │
│  INC-yyy  OOM error   MED●      │
├─────────────────────────────────┤
│  DevOps Assistant               │
│  [Ask anything…           →]   │
│  "At 14:32 the API returned…"   │
│  Sources: INC-001, runbook.md   │
└─────────────────────────────────┘
```

### Clean Architecture data flow

```
IncidentApiService (Retrofit) → GET /incidents
DevOpsApiService   (Retrofit) → POST /devops/chat, POST /analysis/errors
     ↓ mapped to domain models
IncidentRepositoryImpl → IncidentRepository interface
DevOpsRepositoryImpl   → DevOpsRepository interface
     ↓ used by
GetIncidentsUseCase, AnalyseErrorsUseCase, AskDevOpsAssistantUseCase
     ↓ called by
DashboardViewModel (HiltViewModel, parallel async loading)
     ↓ StateFlow<DashboardUiState>
DashboardScreen (Compose, PullToRefreshBox)
     └── IncidentCountsRow, AiAnalysisCard, IncidentListItem, DevOpsChatCard
```

### Components

| Component | What it shows |
|-----------|--------------|
| `IncidentCountsRow` | Critical/High/Medium/Open chip counts |
| `AiAnalysisCard` | Expandable: summary, confidence bar, root cause, recommended fix |
| `IncidentListItem` | Title, severity badge, status badge, AI summary, confidence |
| `DevOpsChatCard` | Quick question chips + text input + answer + citations |
| `RemediationCard` | Risk-tiered actions with Approve/Reject buttons (Phase 15) |
| `StatusBadge/SeverityBadge` | Coloured chips: red=CRITICAL, amber=MEDIUM, blue=LOW |

---

## 14. Phase 15 — AIOps

### The complete AIOps loop

```
Android events uploaded → observability_events table
         ↓ (every 60s — Celery beat)
AnomalyDetectionService.run_detection_cycle()
  Stage 1: error_rate > 5% OR error_count > 50
  Stage 2: current > mean + 2σ
         ↓ (on anomaly)
Incident created (status: OPEN)
  + Phase 10 analysis attached (ai_summary, ai_confidence, ai_recommended_fix)
  + RemediationService.recommend() → ranked actions stored
  + _notify_admins() → FCM push to all admin users with registered devices
         ↓
📱 Push notification on Android phone
  Title: "🟠 HIGH Incident Detected"
  Body:  incident title
  Data:  {type: "incident_created", incident_id: "...", screen: "devops/dashboard"}
         ↓
Developer opens Android Dashboard
  Sees incident, AI analysis, remediation recommendations
  Taps "Approve" or "Reject" on each action
         ↓
POST /incidents/{id}/remediation/{action_id}/approve
  → status: RECOMMENDED → APPROVED
  → reviewed_by, reviewed_at recorded
  ⚠️ NO AUTO-EXECUTION — developer executes manually using params shown
```

### Remediation action catalogue

| Action type | Risk tier | What it does |
|------------|-----------|-------------|
| `notify_slack` | LOW | Send alert to Slack channel |
| `create_ticket` | LOW | Create Jira/GitHub issue |
| `restart_service` | MEDIUM | `gcloud run services update` (new revision, zero-downtime) |
| `scale_up` | MEDIUM | Increase max-instances |
| `scale_down` | MEDIUM | Decrease max-instances |
| `rollback` | HIGH | Route traffic to previous Cloud Run revision |
| `modify_config` | HIGH | Update Cloud Run env var or Secret Manager secret |

**HIGH risk actions show an extra warning** in the Android UI before the Approve button.

---

## 15. Phase 16 — Security

### Security controls by layer

| Layer | Control |
|-------|---------|
| Transport | TLS everywhere, Android NetworkSecurityConfig (no plain HTTP), certificate pinning |
| Authentication | JWT (HS256, 15min expiry), refresh token rotation (7 days), biometric unlock |
| Authorization | RBAC (user/premium/admin), user_id scoping on all DB queries |
| API | Rate limiting (Redis sliding window, per-user), input sanitization, body size limit |
| Secrets | GCP Secret Manager (never in code/images/.env), WIF (no SA key files) |
| Data at rest | AES-256-GCM for LLM API keys, bcrypt work factor 12 for passwords |
| Container | Non-root user (appuser), read-only filesystem, minimal base image |
| Dependencies | Trivy (image CVE scan), pip-audit (Python deps), pinned versions with CVE comments |
| LLM | Prompt injection detection, safety filters on all output, PII scrubbing |
| AIOps | Human approval before any production action |

### Key authentication flow

```
Android → POST /auth/google {id_token}
Backend → verify with Google, issue JWT (15min) + refresh (7 days)
Android → API calls with Authorization: Bearer <JWT>
Backend → jwt.decode(token, SECRET_KEY) → extract user_id, role
Android → POST /auth/refresh when JWT expires → new JWT
```

---

## 16. Phase 17 — Testing

### Testing pyramid

```
E2E (Compose instrumented)       ← feature interactions, navigation
Integration (FastAPI TestClient) ← API contracts, DB wiring
Unit (pytest + Kotest + MockK)   ← business logic, state transitions
Property (Hypothesis)            ← invariants on random inputs
AI eval (golden set + metrics)   ← retrieval quality, safety gate
```

### Key test patterns

**Android ViewModel with Turbine:**
```kotlin
vm.uiState.test {
    assertThat(awaitItem()).isInstanceOf(DashboardUiState.Loading::class.java)
    val content = awaitItem() as DashboardUiState.Content
    assertThat(content.incidents).hasSize(3)
}
```

**Property-based chunk coverage:**
```python
@given(text=st.text(min_size=10, max_size=10_000))
def test_every_token_appears_in_at_least_one_chunk(text):
    original_tokens = set(enc.encode(text))
    covered = set()
    for chunk in service.chunk_text(text):
        covered.update(enc.encode(chunk.text))
    assert original_tokens.issubset(covered)
```

**AI safety gate test:**
```python
def test_low_confidence_triggers_warning():
    response = analyse_errors_with_sparse_data(event_count=1)
    assert response.confidence < 0.6
    assert "manual investigation" in response.low_confidence_warning.lower()
```

---

## 17. Phase 18 — Production CI/CD

### Complete pipeline (8 minutes end-to-end)

```
git push origin main
         ↓
GitHub Actions: backend-ci.yml
  1. pip install (cached)
  2. Bandit + pip-audit (security, 2 min)
  3. pytest unit/ (1 min)
  4. pytest integration/ + Docker services (2 min)
  5. Docker build (multi-stage, production target)
  6. Trivy image scan (CRITICAL block)
  7. Push to Artifact Registry: sha-${{ github.sha }}
  8. Alembic migration Cloud Run Job
  9. gcloud run deploy ai-assistant-backend --image=sha-...
  10. Smoke test: GET /health → 200 (5 retries × 8s)
```

**Why SHA tags, not `:latest`:**
Every Cloud Run revision maps to an exact git commit. Rollback = deploy a previous SHA.

**Branch strategy:**
```
main    ← production only; every push auto-deploys
develop ← integration; CI runs, no deploy
feature/* ← PR to develop; CI runs
```

---

## 18. Phase 19 — Jenkins

The same pipeline as GitHub Actions, expressed as a Groovy `Jenkinsfile`:

```groovy
pipeline {
    agent { docker { image 'python:3.11-slim' } }

    stages {
        stage('Install') { steps { sh 'pip install -r backend/requirements.txt' } }

        stage('Security Scan') {
            parallel {
                stage('Bandit') { steps { sh 'bandit -r backend/app/' } }
                stage('pip-audit') { steps { sh 'pip-audit -r backend/requirements.txt' } }
            }
        }

        stage('Tests') { steps { sh 'pytest backend/tests/unit/' } }
        stage('Build') { steps { sh "docker build --target production -t ${IMAGE}:${GIT_COMMIT} backend/" } }
        stage('Deploy') {
            when { branch 'main' }
            steps { sh "gcloud run deploy ai-assistant-backend --image=${IMAGE}:${GIT_COMMIT} ..." }
        }
    }
}
```

**When to choose Jenkins vs GitHub Actions:**
- Jenkins: existing enterprise infra, on-premises, complex multi-repo orchestration
- GitHub Actions: green-field, cloud-native, GitHub-hosted, small team

---

## 19. Phase 20 — Kubernetes

### Cloud Run vs Kubernetes decision

| Use Cloud Run | Use Kubernetes |
|--------------|---------------|
| ≤ 10 services | 10+ services with complex routing |
| Scale to zero needed (₹0 idle) | Steady traffic, always-on workloads |
| Small team (1–5 engineers) | Dedicated platform team |
| Simple HTTP services | Stateful workloads, persistent volumes |
| GCP-native | Multi-cloud, on-premises |

**This project uses Cloud Run** — correct choice at portfolio scale.

### If this project were on Kubernetes

```yaml
# api-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: api-deployment
spec:
  replicas: 2
  template:
    spec:
      containers:
      - name: api
        image: asia-south1-docker.pkg.dev/.../api:sha-abc123
        readinessProbe:
          httpGet: { path: /ready, port: 8000 }
        resources:
          limits: { cpu: "1", memory: "1Gi" }
---
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  scaleTargetRef: { kind: Deployment, name: api-deployment }
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource: { name: cpu, target: { averageUtilization: 70 } }
```

---

## 20. Interview Cheat Sheet

### "Walk me through your architecture"

> "The backend is a FastAPI app on Google Cloud Run — serverless, scales to zero,
> costs nothing when idle. It connects to Neon PostgreSQL, Redis (rate limiting +
> Celery), ChromaDB (vector store for RAG), and three LLM providers with fallback.
> The Android client talks to the backend over HTTPS with JWT auth and certificate
> pinning. All secrets live in GCP Secret Manager — nothing in code or environment
> files. CI/CD runs on GitHub Actions with Workload Identity Federation — no
> service account key files anywhere."

### "How does the RAG pipeline work?"

> "When a document is uploaded, we extract text, chunk it into 512-token pieces
> with 64-token overlap using tiktoken, generate 384-dimensional embeddings with
> all-MiniLM-L6-v2, and store them in ChromaDB. At query time, we embed the
> question with the same model, cosine-similarity search the collection for the
> top-5 most relevant chunks, assemble them into a context string with citations,
> and inject them into the LLM prompt. The LLM reasons over real retrieved
> content — it can't hallucinate events that didn't happen."

### "How do you test AI systems?"

> "Three approaches. Property testing: assert invariants on random inputs —
> 'every input token must appear in at least one chunk'. Golden set evaluation:
> maintain known (query, expected_behavior) pairs and verify tool selection and
> citation presence, not exact wording. Statistical testing: run the same query
> 10 times, check the desired behavior occurs ≥ 8/10 times. For safety gates —
> the 0.6 confidence threshold — I test that sparse input produces low confidence
> and that the backend overrides the root cause field in application code, not
> just in the prompt."

### "What is AIOps and what did you build?"

> "AIOps applies AI to IT operations — detecting anomalies, diagnosing root causes,
> and proposing remediation automatically. I built a complete AIOps loop: a Celery
> beat task detects anomalies every 60 seconds using statistical thresholds, creates
> an Incident record, runs AI error analysis and root cause analysis, generates
> ranked remediation recommendations, and sends an FCM push notification to my
> Android phone. On the phone I see the AI's analysis with confidence scores and
> can approve or reject each recommended action. No automated execution happens
> without explicit human approval — the system assists, not replaces."

### "Why Cloud Run instead of Kubernetes?"

> "Cloud Run is the right tool at this scale. It scales to zero — costs nothing
> when idle. It handles HTTPS, health checks, and rolling deploys out of the box.
> No cluster to maintain, no node pool upgrades, no CNI plugin configuration.
> For a 3-service system (FastAPI, ChromaDB, Celery) with a 1-person team,
> Kubernetes would add 10 hours/week of operations overhead for no benefit.
> I know when K8s makes sense — 10+ services, complex inter-service networking,
> stateful workloads, or compliance requiring on-premises. None of those apply here."

### "How do you secure an LLM application?"

> "Four layers. Input: sanitize all user input before it reaches the LLM —
> strip HTML, check for prompt injection patterns (static + LLM-based detection).
> Prompt design: user content is clearly labelled, never interpolated raw into
> system instructions. Output: every LLM response passes through a safety filter
> that redacts PII and checks policy violations before returning to the client.
> Data: PII is filtered on the Android device before events leave the phone.
> The LLM never sees raw credentials, real names, or phone numbers."

---

## 21. Running the Project

### Backend locally

```bash
# Start services
docker-compose up -d postgres redis chromadb

# Seed knowledge base
python backend/scripts/seed_knowledge.py

# Run migrations
cd backend && alembic upgrade head

# Start API
uvicorn app.main:app --reload --port 8000

# Start Celery worker + beat
celery -A app.workers.celery_app worker --beat --loglevel=info
```

### Backend on Cloud Run

```bash
# One-time setup
.\scripts\setup-gcs.ps1
.\scripts\setup-iam.ps1
.\scripts\setup-wif.ps1

# Deploy
.\scripts\deploy-cloud-run.ps1

# Seed knowledge (after ChromaDB warms up)
curl -X POST https://ai-assistant-backend-106071012091.asia-south1.run.app/api/v1/admin/rag/reindex \
  -H "Authorization: Bearer ADMIN_JWT"
```

### Android

```bash
# Cloud flavour (points to Cloud Run)
./gradlew assembleCloudDebug

# Local flavour (points to localhost:8000)
./gradlew assembleLocalDebug
```

### Key environment variables

| Variable | Where | What |
|----------|-------|------|
| `SECRET_KEY` | Secret Manager | JWT signing key |
| `AES_ENCRYPTION_KEY` | Secret Manager | AES-256 key for stored API keys |
| `DATABASE_URL` | Secret Manager | Neon PostgreSQL connection string |
| `REDIS_URL` | Secret Manager | Redis connection URL |
| `MINIO_ACCESS_KEY` | Secret Manager | GCS HMAC Access ID |
| `MINIO_SECRET_KEY` | Secret Manager | GCS HMAC Secret |
| `GEMINI_API_KEY` | Secret Manager | Google Gemini API key |
| `OPENAI_API_KEY` | Secret Manager | OpenAI API key |
| `MINIO_ENDPOINT` | Cloud Run env | `storage.googleapis.com` |
| `MINIO_BUCKET_NAME` | Cloud Run env | `android-ai-assistant-89cec-files` |
| `CHROMA_HOST` | Cloud Run env | ChromaDB Cloud Run URL (no https://) |
| `DEFAULT_LLM_PROVIDER` | Cloud Run env | `gemini` |
| `OTEL_ENABLED` | Cloud Run env | `true` |

---

## 22. File Map

### Backend key files

```
backend/app/
├── main.py                              — FastAPI app factory, all routers registered
├── config/settings.py                   — All config with Pydantic validation
├── services/
│   ├── ai_orchestrator.py               — LLM routing, streaming, safety
│   ├── rag_service.py                   — RAG pipeline + query_knowledge_base()
│   ├── error_analysis_service.py        — Phase 10: events → LLM → structured analysis
│   ├── anomaly_detection_service.py     — Phase 11: Stage 1/2 + FCM notifications
│   ├── rca_service.py                   — Phase 12: chain-of-thought RCA
│   ├── devops_assistant_service.py      — Phase 13: ReAct tool loop
│   └── remediation_service.py          — Phase 15: risk-tiered recommendations
├── workers/
│   ├── anomaly_worker.py               — Celery beat every 60s
│   ├── notification_worker.py          — FCM push notification tasks
│   ├── rag_worker.py                   — Document ingestion pipeline
│   └── metrics.py                      — Prometheus Celery + LLM metrics
├── observability/
│   ├── logging_setup.py                — JSON formatter + configure_logging()
│   └── tracing.py                      — OpenTelemetry setup
├── models/
│   ├── user.py, conversation.py, message.py, document.py
│   ├── observability_event.py          — Phase 10: Android events
│   ├── incident.py                     — Phase 11: anomaly incidents
│   └── remediation_action.py          — Phase 15: remediation actions
└── repositories/
    ├── observability_event_repository.py — includes aggregation queries
    ├── incident_repository.py            — includes attach_rca(), attach_analysis()
    └── remediation_service.py            — approve, reject, list
```

### Android key files

```
feature-dashboard/src/main/kotlin/com/aiassistant/feature/dashboard/
├── DashboardScreen.kt                  — main Compose UI
├── DashboardViewModel.kt               — HiltViewModel, parallel loading
├── DashboardUiState.kt                 — sealed class + ChatUiState + RemediationUiState
├── DashboardNavigation.kt              — DashboardRoute + navGraph extension
└── components/
    ├── StatusBadge.kt                  — severity + status coloured chips
    ├── IncidentListItem.kt             — incident row with AI summary
    ├── AiAnalysisCard.kt               — expandable card with confidence bar
    ├── DevOpsChatCard.kt               — quick questions + chat input + answer
    └── RemediationCard.kt              — risk-tiered actions with approve/reject

core-common/src/main/kotlin/.../observability/
├── ObservabilityEvent.kt               — domain model + EventType constants
├── ObservabilityEventBus.kt            — SharedFlow event bus
├── ObservabilityManager.kt             — in-memory ring buffer + drain()
├── PiiFilter.kt                        — regex PII redaction
└── SessionManager.kt                   — session/trace/request ID management

core-network/src/main/kotlin/.../observability/
├── NetworkObservabilityInterceptor.kt  — OkHttp interceptor, emits events
└── ObservabilityUploadWorker.kt        — WorkManager upload every 15 min
```

### Infrastructure key files

```
terraform/
├── modules/iam/          — service account + WIF + 7 project roles
├── modules/storage/      — GCS bucket + HMAC → Secret Manager
├── modules/cloud_run/    — FastAPI + ChromaDB Cloud Run services
└── modules/artifact_registry/

infrastructure/
├── prometheus/prometheus.yml            — scrape configs
├── prometheus/alerting.rules.yml        — 10 alerting rules
├── grafana/provisioning/datasources/    — Prometheus + Loki auto-provisioned
└── grafana/provisioning/dashboards/     — 3 pre-built dashboards

knowledge/                               — RAG knowledge base (13 documents)
scripts/
├── seed_knowledge.py                    — seeds devops_knowledge ChromaDB
├── deploy-cloud-run.ps1                 — manual deploy script
├── setup-gcs.ps1                        — bucket + HMAC + IAM
└── setup-wif.ps1                        — Workload Identity Federation
```

---

*This document covers all 20 phases of the AI DevOps Assistant project.*
*For deeper learning on any phase, see the corresponding `*_GUIDE.md` file in this directory.*
