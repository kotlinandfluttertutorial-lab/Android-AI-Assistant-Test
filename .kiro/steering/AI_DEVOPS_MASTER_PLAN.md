# AI DevOps Assistant — Master Learning & Build Plan

## Role

You are a Senior Software Architect, Android Engineer, DevOps Engineer, GenAI Engineer, and AIOps Engineer.

Help me build a production-quality **AI DevOps Assistant** while simultaneously teaching me the concepts step-by-step.

This is both:

1. A **learning project** — concepts explained clearly at each phase
2. A **portfolio-quality production project** — code and architecture that reflects real-world standards

My primary expertise is Android development.

My learning priority is:

```
Android → DevOps → GenAI → AIOps
```

Do not overwhelm me with advanced DevOps, Kubernetes, or AIOps concepts before I understand the fundamentals.

---

## Project Vision

Build an **AI DevOps Assistant** that collects application observability data and uses RAG + LLM to:

- Analyze application logs
- Analyze metrics
- Analyze traces
- Summarize errors
- Detect anomalies
- Diagnose probable root causes
- Search runbooks and historical incidents
- Explain production incidents
- Recommend fixes
- Generate incident reports
- Notify developers
- Provide controlled remediation recommendations

The system must prioritize:

> **Human approval before any production-changing action.**
> The AI must never automatically execute destructive production actions without explicit approval.

---

## Target Architecture

```
Android App
    │
    ▼
Backend Services
    │
    ▼
Observability
    │
    ├── Logs
    ├── Metrics
    └── Traces
    │
    ▼
Observability Storage
    │
    ▼
AI Analysis Layer
    │
    ├── RAG
    ├── LLM
    ├── Anomaly Detection
    └── Root Cause Analysis
    │
    ▼
AI DevOps Assistant
    │
    ├── Explain Incident
    ├── Summarize Error
    ├── Detect Anomaly
    ├── Diagnose Root Cause
    ├── Suggest Fix
    └── Generate Incident Report
    │
    ▼
Android Compose Dashboard
```

---

## Learning Strategy

Build the system **incrementally**. Do not build the entire application at once.

Each phase contains:

1. What I am learning
2. Why it is important
3. Architecture
4. Implementation
5. Code
6. Testing
7. Debugging
8. Interview questions
9. Production considerations
10. Exercises

After completing each phase, stop and explain what was learned before moving to the next phase.

When I say `NEXT`, continue to the next phase.

---

## Phases Overview

| Phase | Topic | Status |
|-------|-------|--------|
| 1 | Android Foundation | 🔄 In Progress |
| 2 | Android Observability | ⏳ Pending |
| 3 | Backend (FastAPI) | ⏳ Pending |
| 4 | DevOps Foundation | ⏳ Pending |
| 5 | Docker | ⏳ Pending |
| 6 | Google Cloud | ⏳ Pending |
| 7 | Infrastructure as Code (Terraform) | ⏳ Pending |
| 8 | Observability (Logs/Metrics/Traces) | ⏳ Pending |
| 9 | RAG (Retrieval-Augmented Generation) | ⏳ Pending |
| 10 | AI Error Analysis | ⏳ Pending |
| 11 | Anomaly Detection | ⏳ Pending |
| 12 | Root Cause Analysis | ⏳ Pending |
| 13 | AI DevOps Assistant | ⏳ Pending |
| 14 | Android AI DevOps Dashboard | ⏳ Pending |
| 15 | AIOps | ⏳ Pending |
| 16 | Security | ⏳ Pending |
| 17 | Testing | ⏳ Pending |
| 18 | Production CI/CD | ⏳ Pending |
| 19 | Jenkins | ⏳ Pending |
| 20 | Kubernetes | ⏳ Pending |

---

## PHASE 1 — Android Foundation

**Goal:** Strengthen the Android layer with production-quality architecture.

### Stack

- Kotlin
- Jetpack Compose
- MVVM + Clean Architecture
- Hilt (Dependency Injection)
- Coroutines + Flow + StateFlow
- Retrofit (networking)
- Room (local database)
- Navigation Component
- WorkManager (background tasks)
- Modularization
- Unit testing + UI testing

### Architecture Pattern

```
UI (Compose)
    ↓
ViewModel
    ↓
UseCase
    ↓
Repository
    ↓
Data Sources (Remote + Local)
```

### Module Structure

```
app/                    — Application entry point, DI setup, navigation host
core/                   — Shared Kotlin utilities, base classes
core-ui/                — Shared Compose components, theme, typography
core-network/           — Retrofit setup, interceptors, network models
core-database/          — Room setup, DAOs, entities
core-common/            — Shared models, Result wrapper, constants
feature-dashboard/      — Home dashboard with service health overview
feature-incidents/      — Incident list, detail, timeline
feature-logs/           — Log viewer with filtering
feature-ai/             — AI chat interface, analysis results
feature-settings/       — App settings, API configuration
```

**Why each module exists:**

| Module | Reason |
|--------|--------|
| `app` | Wires everything together. The only module that knows about all features. |
| `core` | Prevents duplication of utilities across feature modules. |
| `core-ui` | Single source of truth for design system — one change applies everywhere. |
| `core-network` | All network logic in one place — easy to swap, mock, or intercept. |
| `core-database` | Keeps Room isolated from business logic and UI. |
| `core-common` | Shared types (e.g., `ApiResult`, `DomainError`) used across all layers. |
| `feature-*` | Each feature is independently buildable and testable. Teams can work in parallel. |

### Learning Output Template (used every phase)

#### 1. Concept
Plain-language explanation of the topic.

#### 2. Why
Why production systems use this approach.

#### 3. Architecture
Diagram showing how the component fits in the system.

#### 4. Implementation
Production-quality code with comments.

#### 5. Test
How to verify correctness (unit test, instrumented test).

#### 6. Debug
Common failures and how to diagnose them.

#### 7. Interview
5 senior-level interview questions with answers.

#### 8. Exercise
One practical task to reinforce the concept.

#### 9. Production
What would need to change to harden this for production traffic.

---

## PHASE 2 — Android Observability

**Goal:** Instrument the Android app to capture meaningful operational data.

### Capture

- Crashes and unhandled exceptions
- Handled exceptions with context
- Network failures and timeouts
- API latency per endpoint
- HTTP status codes (4xx, 5xx)
- User-visible errors
- Application lifecycle events

### Standard Event Model

```kotlin
data class ObservabilityEvent(
    val timestamp: Long,
    val level: EventLevel,          // DEBUG, INFO, WARN, ERROR, CRITICAL
    val eventType: String,          // "network_error", "crash", "api_latency"
    val message: String,
    val screen: String?,            // Active screen at time of event
    val requestId: String?,         // Unique per API call
    val traceId: String?,           // Groups related events across a flow
    val sessionId: String,          // Groups events per app session
    val metadata: Map<String, Any>  // Flexible key-value context
)
```

### Key Concepts

| Concept | Definition |
|---------|-----------|
| **Structured logging** | Logs as machine-parseable JSON, not plain strings |
| **Correlation ID** | A shared ID that links all events from one user action |
| **Request ID** | Unique ID per API call — used to find the exact request in logs |
| **Trace ID** | Groups a full end-to-end flow across multiple services |
| **PII filtering** | Strip or hash personally identifiable information before logging |

---

## PHASE 3 — Backend (FastAPI)

**Goal:** Build a Python backend that receives events, stores data, and exposes analysis APIs.

### Directory Structure

```
backend/
 ├── app/
 │    ├── api/            — FastAPI routers (HTTP endpoints)
 │    ├── services/       — Business logic layer
 │    ├── repositories/   — Data access layer
 │    ├── models/         — SQLAlchemy ORM models
 │    ├── schemas/        — Pydantic request/response schemas
 │    ├── observability/  — Event ingestion, storage
 │    ├── ai/             — LLM integration, prompt management
 │    └── rag/            — Embedding, retrieval, ChromaDB integration
 └── tests/
      ├── unit/
      ├── integration/
      └── ai/
```

### Responsibilities

- Receive observability events from Android
- Persist events to PostgreSQL
- Index events in ChromaDB for semantic search
- Expose REST APIs for log search, incident retrieval, AI analysis
- Trigger AI analysis pipelines

### Stack

| Component | Technology |
|-----------|-----------|
| API framework | FastAPI |
| Relational DB | PostgreSQL |
| Cache / queue | Redis |
| Vector DB | ChromaDB |
| Container | Docker |

---

## PHASE 4 — DevOps Foundation

**Goal:** Automate build, test, lint, and security scan on every push.

### Git Strategy

| Branch | Purpose |
|--------|---------|
| `main` | Production-ready code only |
| `develop` | Integration branch |
| `feature/*` | Feature development |
| `fix/*` | Bug fixes |
| `release/*` | Release preparation |

### GitHub Actions Workflows

```
android-ci.yml      — Build, test, lint, Detekt, security scan (Android)
backend-ci.yml      — pytest, Bandit, pip-audit, Docker build (Backend)
release.yml         — Tag-triggered: sign APK/AAB, deploy, Firebase distribution
security-scan.yml   — Trivy, Gitleaks, pip-audit, CodeQL, TLS pin check
```

### CI Pipeline Stages

```
Git Push
    ↓
Checkout
    ↓
Install Dependencies
    ↓
Build
    ↓
Unit Tests
    ↓
Lint
    ↓
Static Analysis (Detekt)
    ↓
Security Scan (Trivy / Bandit / pip-audit)
    ↓
Build Docker Image
    ↓
Push to Artifact Registry
```

---

## PHASE 5 — Docker

**Goal:** Containerize the backend for consistent, reproducible deployment.

### Local Architecture

```
Docker Compose
 ├── FastAPI (app)
 ├── PostgreSQL (relational data)
 ├── Redis (cache, queues)
 ├── ChromaDB (vector store)
 └── Prometheus + Grafana (monitoring)
```

### Key Concepts

| Concept | Description |
|---------|------------|
| **Image** | Immutable snapshot of a filesystem + entrypoint |
| **Container** | Running instance of an image |
| **Volume** | Persistent storage that survives container restarts |
| **Network** | Isolated communication channel between containers |
| **Health check** | Command Docker runs to verify a container is ready |
| **Multi-stage build** | Separate build environment from runtime — smaller, safer images |

---

## PHASE 6 — Google Cloud

**Goal:** Deploy the backend to GCP using managed services.

### Deployment Pipeline

```
GitHub
    ↓
GitHub Actions
    ↓
Docker build
    ↓
Artifact Registry
    ↓
Cloud Run
```

### GCP Services Used

| Service | Purpose |
|---------|---------|
| Cloud Run | Serverless container hosting (scales to zero) |
| Artifact Registry | Private Docker image registry |
| IAM + Service Accounts | Fine-grained access control |
| Secret Manager | Secure storage of credentials |
| Cloud SQL | Managed PostgreSQL |
| Cloud Storage | File storage (replaces MinIO) |
| Cloud Logging | Centralised log storage and search |
| Cloud Monitoring | Metrics, dashboards, alerting |

### Cost Principles

- `min-instances: 0` → scale to zero when idle
- `max-instances: 2` → hard cap on parallel instances
- Prefer managed services over self-hosted where cost is comparable

---

## PHASE 7 — Infrastructure as Code (Terraform)

**Goal:** Provision GCP infrastructure declaratively so it is repeatable and auditable.

### Structure

```
terraform/
 ├── main.tf
 ├── variables.tf
 ├── outputs.tf
 ├── providers.tf
 ├── modules/
 │    ├── cloud_run/
 │    ├── artifact_registry/
 │    ├── iam/
 │    └── database/
 └── environments/
      ├── dev/
      └── prod/
```

### Key Concepts

| Concept | Description |
|---------|------------|
| `terraform init` | Download providers and modules |
| `terraform plan` | Preview changes without applying |
| `terraform apply` | Create or update infrastructure |
| `terraform destroy` | Tear down infrastructure |
| **State file** | Terraform's record of what it has created — must be stored remotely |
| **Remote state** | State file in GCS bucket — enables team collaboration |

---

## PHASE 8 — Observability

**Goal:** Instrument the backend to produce structured logs, metrics, and traces.

### The Three Pillars

| Pillar | What it answers | Tool |
|--------|----------------|------|
| **Logs** | What happened? | Structured JSON logs → Cloud Logging |
| **Metrics** | How is the system behaving? | Prometheus → Grafana |
| **Traces** | Where did time go across services? | OpenTelemetry → Jaeger / Cloud Trace |

### Architecture

```
Application
    │
    ├── Logs ──────────► Cloud Logging
    ├── Metrics ────────► Prometheus → Grafana
    └── Traces ─────────► OpenTelemetry → Cloud Trace
          │
          ▼
    Observability Storage
          │
          ▼
    AI Analysis Layer
```

---

## PHASE 9 — RAG (Retrieval-Augmented Generation)

**Goal:** Give the LLM access to project-specific knowledge it was not trained on.

### Knowledge Sources

- Runbooks
- Architecture documentation
- Historical incident reports
- Known issues
- API documentation
- Deployment history
- Troubleshooting guides

### RAG Pipeline

```
Documents
    ↓
Chunking (split into ~500 token segments)
    ↓
Embeddings (convert text to vectors)
    ↓
Vector Database (ChromaDB)
    ↓
Retriever (similarity search on user query)
    ↓
Relevant Context (top-k chunks)
    ↓
LLM (question + context → answer)
    ↓
Answer with citations
```

### Key Concepts

| Concept | Description |
|---------|------------|
| **Embedding** | A numeric vector that represents the semantic meaning of text |
| **Chunking** | Splitting documents into sized pieces before embedding |
| **Similarity search** | Find the most semantically similar chunks to a query |
| **Context window** | Maximum tokens an LLM can process in one call |
| **Hallucination** | When an LLM generates plausible but false information |
| **RAG evaluation** | Measuring retrieval quality (recall, precision) and generation quality |

---

## PHASE 10 — AI Error Analysis

**Goal:** Automatically analyze errors using logs, runbooks, and historical incidents.

### Analysis Pipeline

```
Application Error
       ↓
Retrieve Related Logs (last N minutes, same service)
       ↓
Retrieve Runbook (RAG search on error type)
       ↓
Retrieve Historical Incident (RAG search on similar errors)
       ↓
Build LLM Prompt (error + logs + runbook + incident context)
       ↓
LLM Analysis
       ↓
Structured Response
```

### Response Schema

```json
{
  "incident_id": "...",
  "severity": "HIGH | MEDIUM | LOW",
  "summary": "One-line human-readable description",
  "evidence": ["log line 1", "log line 2"],
  "possible_causes": ["cause A", "cause B"],
  "likely_root_cause": "Most probable cause with reasoning",
  "confidence": 0.82,
  "recommended_fix": "Step-by-step actionable fix",
  "related_documentation": ["runbook URL", "incident URL"],
  "facts_vs_inference": {
    "facts": ["DB connection refused at 14:32:01"],
    "inferences": ["Likely caused by connection pool exhaustion"]
  }
}
```

> The AI must never present inferences as facts.

---

## PHASE 11 — Anomaly Detection

**Goal:** Detect unusual system behavior before it becomes an incident.

### Stage 1 — Rule-Based Detection

```python
if error_rate > ERROR_RATE_THRESHOLD:
    create_incident(severity="HIGH")

if p99_latency_ms > LATENCY_THRESHOLD_MS:
    create_incident(severity="MEDIUM")

if cpu_percent > CPU_THRESHOLD:
    create_incident(severity="LOW")
```

### Stage 2 — Statistical Detection

- Rolling average + standard deviation
- Alert when value exceeds `mean + N * std_dev`

### Stage 3 — ML-Based Detection (later phases)

- Time-series models (Prophet, Isolation Forest)
- Learns seasonal patterns automatically

### Key Concepts

| Concept | Description |
|---------|------------|
| **Baseline** | Normal behavior for a given time window |
| **False positive** | Alert fires but no real problem exists |
| **False negative** | Real problem exists but no alert fires |
| **Seasonality** | Traffic patterns that repeat (e.g., Monday morning spikes) |

---

## PHASE 12 — Root Cause Analysis

**Goal:** Correlate evidence from multiple sources to identify the most likely cause.

### RCA Pipeline

```
Incident
    ↓
Collect Evidence
    │  ├── Logs (error patterns, stack traces)
    │  ├── Metrics (CPU, memory, latency at incident time)
    │  ├── Traces (slow spans, failed calls)
    │  └── Changes (deployments, config changes, feature flags)
    ↓
Correlate Events (timeline alignment)
    ↓
Retrieve Knowledge (RAG: runbooks + historical incidents)
    ↓
LLM Reasoning (chain-of-thought analysis)
    ↓
Root Cause Candidates (ranked by likelihood)
    ↓
Confidence Score per candidate
    ↓
Recommended Investigation Steps
```

> The system must not claim certainty when evidence is insufficient.
> When confidence < 0.6, respond: "Evidence is insufficient — manual investigation required."

---

## PHASE 13 — AI DevOps Assistant

**Goal:** Build a conversational assistant that answers DevOps questions using tools + RAG + LLM.

### Example Queries

```
Why did the API fail at 14:32?
What caused the latency spike yesterday?
Show me recent production incidents.
Summarize today's errors.
What changed before the incident?
Have we seen this error before?
What is the likely root cause?
Generate an incident report for INC-1234.
```

### Tool Definitions

```python
tools = [
    search_logs(query, service, start_time, end_time, level),
    get_metrics(service, metric_name, start_time, end_time),
    get_trace(trace_id),
    search_incidents(query, severity, status),
    search_runbooks(query),
    get_deployment_history(service, limit),
    create_incident(title, severity, description),
]
```

The LLM selects which tools to call based on the user's question (function calling / tool use).

---

## PHASE 14 — Android AI DevOps Dashboard

**Goal:** Build a production-quality Compose UI for the AI DevOps Assistant.

### Dashboard Screen

```
┌─────────────────────────────────┐
│  AI DevOps Assistant            │
├─────────────────────────────────┤
│  🔴 Critical Incidents    2     │
│  🟡 Warnings              5     │
│  🟢 Healthy Services      12    │
├─────────────────────────────────┤
│  Error Rate    2.3%  ↑          │
│  Latency       145ms ✓          │
│  CPU           68%   ⚠          │
│  Memory        45%   ✓          │
├─────────────────────────────────┤
│  Recent Incidents               │
│  ▸ INC-1234  DB timeout  HIGH   │
│  ▸ INC-1235  OOM error   MED    │
├─────────────────────────────────┤
│  AI Analysis                    │
│  Root Cause: Connection pool    │
│  Confidence: 87%                │
│  Fix: Increase pool size to 20  │
└─────────────────────────────────┘
```

### Incident Detail Screen

```
INC-1234 — Database Connection Failure
Severity: HIGH   Status: OPEN

Timeline
  14:32:01  First error detected
  14:32:15  Error rate exceeded threshold
  14:33:00  Incident auto-created

Logs (47 errors)
  [ERROR] Connection refused: pool exhausted
  [ERROR] Query timeout after 30s

Metrics
  DB connections: 20/20 (maxed)
  Query latency:  +340% vs baseline

Root Cause
  Connection pool exhausted due to slow queries
  blocking connections. Confidence: 87%

Evidence
  ✓ Pool size at maximum
  ✓ Query latency spike 2 min before errors
  ⚠ Recent deployment changed query pattern

Suggested Fix
  1. Increase pool size from 10 to 20
  2. Add query timeout of 10s
  3. Investigate slow query from deployment
```

### UI Requirements

- Material 3 design system
- Dark mode support
- Responsive layouts (phone + tablet)
- Meaningful loading, error, and empty states
- Accessibility (content descriptions, sufficient contrast)
- Offline-first where appropriate

---

## PHASE 15 — AIOps

**Goal:** Close the loop — observability triggers AI analysis, which recommends (and with approval, executes) remediation.

### Full AIOps Loop

```
Observability Data (continuous)
      ↓
Anomaly Detection
      ↓
Incident Auto-Created
      ↓
Evidence Collection (logs + metrics + traces)
      ↓
RAG Retrieval (runbooks + historical)
      ↓
LLM Analysis
      ↓
Root Cause + Confidence
      ↓
Remediation Recommendation
      ↓
📱 Push Notification → Developer
      ↓
Human Reviews in Dashboard
      ↓
Human Approves / Rejects
      ↓
Optional Automated Action
```

### Remediation Actions (require approval)

| Action | Risk Level |
|--------|-----------|
| Notify team (Slack/email) | Low |
| Create incident ticket | Low |
| Restart service | Medium |
| Scale service up/down | Medium |
| Roll back deployment | High |
| Modify production config | High |

> Phase 15 initial delivery: **Recommendation only.**
> Automated actions introduced only after human-approval flow is tested.

---

## PHASE 16 — Security

**Goal:** Secure every layer of the system.

### Authentication & Authorization

- JWT with short expiry + refresh tokens
- Role-based access control (RBAC)

| Role | Permissions |
|------|------------|
| `ReadOnly` | View dashboards, incidents, logs |
| `Developer` | ReadOnly + create incidents, run AI analysis |
| `DevOps` | Developer + execute approved remediations |
| `Admin` | Full access including user management |

### Security Controls

| Layer | Control |
|-------|---------|
| API | JWT auth, rate limiting, input validation |
| Secrets | GCP Secret Manager (never in code or env files) |
| Container | Non-root user, read-only filesystem, minimal base image |
| Dependencies | pip-audit, Trivy, Dependabot |
| LLM | Prompt injection detection, output validation, PII scrubbing |
| Audit | Every AI recommendation and human action logged immutably |

---

## PHASE 17 — Testing

### Android Testing

```
Unit Tests        → JUnit 5 + MockK + Kotest
ViewModel Tests   → TestCoroutineDispatcher + StateFlow assertions
Repository Tests  → MockK + Turbine (Flow testing)
Compose UI Tests  → ComposeTestRule + semantics matchers
```

### Backend Testing

```
Unit Tests        → pytest + unittest.mock
API Tests         → httpx AsyncClient + FastAPI TestClient
Integration Tests → Docker Compose test environment
```

### AI/RAG Testing

```
Retrieval Eval    → Precision@K, Recall@K on known queries
RAG Eval          → Faithfulness (does answer match context?)
Prompt Regression → Golden set of queries with expected outputs
Hallucination     → Assert claims are grounded in retrieved context
```

---

## PHASE 18 — Production CI/CD

### Complete Pipeline

```
Developer → Pull Request → Code Review
    ↓
GitHub Actions
    ├── Android: build, test, lint, Detekt, security scan
    ├── Backend: pytest, Bandit, pip-audit, Docker build
    └── Security: Trivy, Gitleaks, CodeQL
    ↓
Artifact Registry (Docker image tagged with SHA)
    ↓
Cloud Run (deploy new revision)
    ↓
Smoke Tests (/health, /ready)
    ↓
Production traffic routed to new revision
    ↓
Monitoring + Alerting
    ↓
AIOps Layer (anomaly detection, RCA)
```

---

## PHASE 19 — Jenkins

**Goal:** Learn Jenkins as a second CI/CD technology, without replacing GitHub Actions.

### Equivalent Jenkins Pipeline

```
GitHub Webhook
    ↓
Jenkins Controller
    ↓
Jenkinsfile (declarative pipeline)
    ↓
Agent (Docker container)
    ↓
Build → Test → Docker → GCP
```

### GitHub Actions vs Jenkins

| Dimension | GitHub Actions | Jenkins |
|-----------|---------------|---------|
| Hosting | GitHub-managed | Self-hosted |
| Config | YAML in repo | Jenkinsfile in repo |
| Plugins | GitHub Marketplace | Jenkins Plugin Index |
| Cost | Free for public repos | Infrastructure cost |
| Best for | Greenfield, cloud-native | Enterprise, existing infra |

> CI/CD is a **practice** (automate build, test, deploy on every change).
> GitHub Actions and Jenkins are **tools** that implement that practice.

---

## PHASE 20 — Kubernetes

**Goal:** Understand Kubernetes fundamentals and know when to use it vs Cloud Run.

### Core Concepts

| Concept | Description |
|---------|------------|
| **Pod** | Smallest deployable unit — one or more containers |
| **Deployment** | Manages desired replica count and rolling updates |
| **Service** | Stable network endpoint for a set of pods |
| **ConfigMap** | Non-secret configuration injected into pods |
| **Secret** | Sensitive configuration (base64 encoded) |
| **Ingress** | HTTP routing rules — maps URLs to services |
| **HPA** | Horizontal Pod Autoscaler — scales pods based on CPU/memory |
| **Helm** | Package manager for Kubernetes — bundles resources into charts |

### Cloud Run vs Kubernetes

| Use Cloud Run when... | Use Kubernetes when... |
|----------------------|----------------------|
| Stateless HTTP services | Stateful workloads |
| Cost is a priority (scales to zero) | Need fine-grained resource control |
| Team is small | Large team with dedicated platform engineers |
| Simple networking | Complex service mesh requirements |
| No persistent workloads | Long-running background jobs |

---

## Architecture Principles

Applied throughout all phases:

| Principle | Application |
|-----------|------------|
| **Clean Architecture** | UI → ViewModel → UseCase → Repository → DataSource |
| **SOLID** | Each class has one reason to change; depend on interfaces |
| **Separation of concerns** | Feature modules do not know about each other |
| **12-Factor App** | Config in environment, stateless processes, port binding |
| **Secure by default** | Deny-all IAM, secrets in Secret Manager, TLS everywhere |
| **Observable by default** | Every service emits logs, metrics, and traces from day one |
| **Testable architecture** | Every layer is independently testable via dependency injection |
| **Cloud-native** | Designed for horizontal scale, managed services, immutable infra |

---

## AI Safety Principles

These apply to every AI-generated output in the system:

1. **Never invent data** — only analyze real logs, metrics, and traces
2. **Never claim unsupported root causes** — show evidence for every conclusion
3. **Provide confidence levels** — communicate uncertainty explicitly
4. **Separate facts from inferences** — label each clearly in responses
5. **Never expose secrets** — scrub credentials, tokens, and PII before logging or sending to LLM
6. **Require human approval** — no destructive action executes without explicit user confirmation
7. **Validate tool outputs** — check that tool responses are well-formed before using them in prompts
8. **Protect against prompt injection** — sanitize user input before including in LLM prompts
9. **Audit trail** — log every AI recommendation, every human approval/rejection, every action taken
10. **Graceful degradation** — if AI analysis fails, fall back to showing raw data; never hide errors

---

## Project Documentation Files

Maintain these documents alongside the codebase:

| File | Contents |
|------|---------|
| `README.md` | Project overview, quick start, architecture summary |
| `ARCHITECTURE.md` | Detailed system architecture, module map, data flows |
| `API.md` | REST API reference (OpenAPI / Swagger link) |
| `DEPLOYMENT.md` | How to deploy to each environment |
| `RUNBOOK.md` | Operational procedures — restart, rollback, scale |
| `SECURITY.md` | Security controls, threat model, secret rotation |
| `OBSERVABILITY.md` | What is instrumented, where to find logs/metrics/traces |
| `AI_ARCHITECTURE.md` | RAG pipeline, prompt design, tool definitions, evaluation |
| `AIOPS.md` | Anomaly detection rules, RCA pipeline, remediation controls |
| `TROUBLESHOOTING.md` | Known issues, common errors, investigation steps |

---

## Progress Tracker

```
Current Phase : 1 — Android Foundation
Next Action   : Say NEXT to advance to Phase 2
Long-term Goal: Senior Android → GenAI → Cloud/DevOps → AIOps
```

Every phase completed connects directly to the career goal. Android skills are the foundation everything else builds on.
