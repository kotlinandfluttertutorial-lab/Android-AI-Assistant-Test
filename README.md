# Android AI Assistant — Enterprise Edition

A production-ready, full-stack AI platform with **two independent client applications**
sharing a common FastAPI backend:

| Client | Language | Status |
|--------|----------|--------|
| **Android** (Kotlin/Compose) | Native Android, offline-first | Primary client |
| **Flutter** (`flutter/`) | Dart/Flutter, cross-platform | Learning / secondary client |

The Android application is the primary client. The Flutter application is an independent
cross-platform client built for learning, experimentation, and future platform expansion.
Both clients consume the same FastAPI backend.

```
                 ┌─────────────────┐
                 │  FastAPI Backend │
                 └────────┬────────┘
                          │
              ┌───────────┴───────────┐
              │                       │
        ┌─────▼─────┐           ┌─────▼─────┐
        │  Android  │           │  Flutter  │
        │ Kotlin    │           │   Dart    │
        │ Compose   │           │  Material │
        └───────────┘           └───────────┘
```

---

A production-ready, full-stack AI platform featuring an offline-first Android application,
a FastAPI backend, multi-LLM orchestration, RAG document Q&A, voice assistance, MCP tool
integrations, and enterprise-grade security with comprehensive observability.

---

## Features at a Glance

- **Multi-LLM Chat** — Stream responses from GPT-4o, Gemini 1.5 Pro, Claude 3.5 Sonnet, Ollama, Llama 3.x, or Mistral
- **RAG Document Q&A** — Upload PDFs, DOCX, and Markdown; get cited answers with source references
- **On-Device RAG** — Fully offline retrieval + generation pipeline (Gemma GGUF + MiniLM-L6-v2); see `feature-on-device-rag`
- **AI Memory** — Persistent user memory injected into every conversation
- **Voice Assistant** — Speech-to-text, AI response, text-to-speech pipeline
- **Image Understanding** — OCR, barcode/QR scanning, vision analysis
- **MCP Tool Integrations** — GitHub, Gmail, Google Drive, Calendar, Slack, Jira, Notion, Figma
- **Productivity Suite** — To-Do, Calendar, Reminders, Habit Tracker — all AI-enhanced, all offline-first
- **AI Persona** — Customisable assistant personas (`feature-persona`)
- **Universal Search** — Cross-module semantic search (`feature-search`)
- **Dashboard** — Unified home screen with AI insights (`feature-dashboard`)
- **Offline-First** — Works without network; auto-syncs when connectivity returns
- **Enterprise Security** — JWT rotation, RBAC, bcrypt (work factor 12), AES-256, certificate pinning, prompt injection detection
- **Flutter Client** — Cross-platform second client (`flutter/`) with Incidents, DevOps AI assistant, Error Analysis, offline queue, and local cache
- **Observability** — Structured event telemetry from both Android and Flutter clients uploaded to the AI analysis pipeline

---

## Prerequisites

Install all required tools before starting. Estimated install time on a clean OS: 10–12 minutes.

| Tool | Required Version | Download |
|------|-----------------|---------|
| Android Studio | Hedgehog (2023.1.1) or newer | [developer.android.com/studio](https://developer.android.com/studio) |
| JDK | 17 (LTS) | [adoptium.net](https://adoptium.net) — select Temurin 17 |
| Flutter | 3.22.0+ | [docs.flutter.dev/get-started/install](https://docs.flutter.dev/get-started/install) |
| Git | 2.40+ | [git-scm.com](https://git-scm.com) |
| Docker Desktop | 4.24+ (includes Docker Engine 24+, Compose v2) | [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop) |
| Python | 3.11+ | [python.org/downloads](https://www.python.org/downloads) |
| pip | 23+ (bundled with Python 3.11) | — |

**Note:** Docker Desktop must be running before you start any infrastructure services.

---

## Quick Setup (~15 minutes)

> **Timing note:** The 15-minute target excludes initial Docker image pulls on a fresh machine
> (~5–8 min depending on connection speed). All subsequent runs are much faster.

### Step 1 — Clone the repository (30 seconds)

```bash
git clone https://github.com/your-org/Android-AI-Assistant.git
cd Android-AI-Assistant
```

### Step 2 — Configure environment variables (2 minutes)

```bash
cp backend/.env.example backend/.env
```

Open `backend/.env` and fill in at minimum:

```env
DATABASE_URL=postgresql+asyncpg://postgres:postgres@localhost:5432/aiassistant
REDIS_URL=redis://localhost:6379/0
SECRET_KEY=replace-with-a-random-256-bit-key-minimum-32-chars
ENCRYPTION_KEY=replace-with-exactly-32-byte-aes-key!

# Configure at least one LLM provider:
OPENAI_API_KEY=sk-...         # get from platform.openai.com
# GEMINI_API_KEY=AIza...      # get from console.cloud.google.com
# ANTHROPIC_API_KEY=sk-ant-...

# Docker Compose Postgres credentials (must match DATABASE_URL above)
POSTGRES_USER=postgres
POSTGRES_PASSWORD=postgres
GRAFANA_PASSWORD=admin
```

### Step 3 — Start infrastructure services (2 minutes on subsequent runs)

```bash
docker compose up postgres redis chromadb minio prometheus grafana loki -d
```

Wait for all services to show `healthy` or `started`:

```bash
docker compose ps
```

### Step 4 — Apply database migrations (30 seconds)

```bash
cd backend
pip install -r requirements.txt
alembic upgrade head
```

### Step 5 — Start the backend server

Open a terminal in the project root:

```bash
cd backend
uvicorn app.main:app --reload --host 0.0.0.0 --port 8080
```

The API is available at: `http://localhost:8080`  
Swagger / OpenAPI docs: `http://localhost:8080/docs`

### Step 6 — Start the Celery worker (separate terminal)

```bash
cd backend
celery -A app.workers.celery_app worker --loglevel=info
```

The Celery worker processes background jobs (document ingestion, push notifications).

### Step 7 — Run the Android app

1. Open Android Studio: **File → Open** → select the project root folder
2. Accept the prompt to download required SDKs if shown
3. Wait for Gradle sync to complete (~3–5 minutes on first run)
4. Place your Firebase `google-services.json` in `app/`:
   - Go to [console.firebase.google.com](https://console.firebase.google.com)
   - Create a project → Add Android app → download `google-services.json`
   - Copy it to `app/google-services.json`
5. Click **▶ Run** or press **Shift+F10** — select an emulator (API 26+) or connected device

**Minimum API Level:** Android 8.0 (API 26)  
**Target API Level:** 35 (Android 15)  
**Kotlin:** 2.0.21 · **AGP:** 8.8.0 · **Compose BOM:** 2024.09.03

---

## Flutter Application

A production-quality cross-platform client (`flutter/`) that consumes the same FastAPI backend.
Built for learning, experimentation, and future cross-platform support.

### Flutter screens

| Screen | Route | Description |
|--------|-------|-------------|
| Login | `/login` | Email/password, show/hide password, validation, error banners |
| Register | `/register` | Create account (password ≥ 12 chars) |
| Home | `/home` | Greeting, hero card, 3 quick-action shortcuts, recent chats |
| Chats | `/conversations` | Cache-first list, swipe-to-delete, long-press export (MD/PDF) |
| Chat | `/chat/:id` | WebSocket streaming, offline queue, suggestion chips, stop/retry |
| Incidents | `/incidents` | Severity filter chips, AI summary line, open-count badge |
| Incident Detail | `/incidents/:id` | RCA confidence bar, remediation approve/reject with safety gate |
| DevOps AI | `/devops` | REST ReAct assistant, tool-call badges, citations, 7 suggestions |
| Error Analysis | `/analysis/errors` | AI error analysis with facts/inferences, confidence, fix suggestion |
| Settings | `/settings` | Provider, theme, on-device AI status, account, env badge |

### Flutter quick start

```bash
# 1. Start the backend (from repo root)
docker compose up -d

# 2. Install dependencies
cd flutter
flutter pub get

# 3. Run on Android emulator (points to localhost:8000 via 10.0.2.2)
flutter run --dart-define=ENV=local
```

### Flutter test commands

```bash
cd flutter
flutter test                                          # 80+ unit + widget tests
flutter test integration_test/ --dart-define=ENV=local  # E2E (backend required)
flutter analyze                                       # static analysis
dart format --set-exit-if-changed .                   # format check
```

### Key Flutter architecture decisions

| Concern | Approach |
|---------|----------|
| State management | Riverpod `AsyncNotifier` + `FamilyNotifier` |
| Navigation | GoRouter with auth guard |
| HTTP | Dio + `AuthInterceptor` (silent JWT refresh + 401 retry) |
| Streaming | WebSocket to `/ws/chat/{id}?token=<jwt>` |
| Offline chat | `PendingMessageQueue` (SharedPreferences, FIFO, max 50) |
| Local cache | `ConversationCache` (SharedPreferences, max 100 conversations) |
| Secure storage | `flutter_secure_storage` (Keychain/Keystore) |
| On-device AI | `OnDeviceAiService` interface + `StubOnDeviceAiService` |
| Observability | `ObservabilityService` → `POST /api/v1/observability/events` |

See [`flutter/README.md`](flutter/README.md) for full documentation.

---

## Running the Full Docker Compose Stack

To run the entire backend stack (backend + Celery) inside Docker:

```bash
docker compose up -d
```

This starts all services: PostgreSQL, Redis, ChromaDB, MinIO, FastAPI backend, Celery worker,
Nginx (port 443), Prometheus, Grafana, and Loki.

Access points:
- API: `https://localhost/api/v1/` (Nginx TLS — needs a self-signed cert for local dev)
- Direct backend: `http://localhost:8080`
- Grafana: `http://localhost:3000` (user: `admin`, password: see `GRAFANA_PASSWORD` in `.env`)
- MinIO console: `http://localhost:9001`
- Prometheus: `http://localhost:9090`

---

## Running Tests

### Android

```bash
# Unit tests (includes property-based tests)
./gradlew testDebugUnitTest

# Coverage check (≥ 70% required — blocks CI on failure)
./gradlew koverVerify

# Static analysis + style
./gradlew ktlintCheck detekt
```

### Flutter

```bash
cd flutter

# Unit + widget tests (80+ tests across 22 test files)
flutter test

# Lint + format check
flutter analyze
dart format --set-exit-if-changed .

# Integration tests (requires running backend)
docker compose up -d
flutter test integration_test/ --dart-define=ENV=local
```

### Backend

```bash
cd backend

# All tests with coverage gate
pytest --cov=app --cov-fail-under=70

# Linting
ruff check .

# Type checking
mypy app/
```

---

## Environment Variables Reference

See [`docs/DEVOPS_GUIDE.md`](docs/DEVOPS_GUIDE.md) for the complete variable reference.

Key variables:

| Variable | Required | Description |
|----------|---------|-------------|
| `DATABASE_URL` | Yes | PostgreSQL async connection string |
| `REDIS_URL` | Yes | Redis connection string |
| `SECRET_KEY` | Yes | JWT signing key (min 32 chars) |
| `ENCRYPTION_KEY` | Yes | AES-256 key for API key storage (exactly 32 bytes) |
| `OPENAI_API_KEY` | At least one LLM key | OpenAI GPT-4o |
| `GEMINI_API_KEY` | At least one LLM key | Google Gemini 1.5 Pro |
| `ANTHROPIC_API_KEY` | At least one LLM key | Anthropic Claude 3.5 Sonnet |
| `OLLAMA_BASE_URL` | Optional | Self-hosted Ollama (no key needed) |

---

## Documentation

All architecture and design documents are in `/docs`:

| Document | Contents |
|----------|---------|
| [`docs/project/PROJECT_VISION.md`](docs/project/PROJECT_VISION.md) | Vision, goals, success criteria |
| [`docs/project/prd.md`](docs/project/prd.md) | All functional requirements |
| [`docs/architecture/SYSTEM_ARCHITECTURE.md`](docs/architecture/SYSTEM_ARCHITECTURE.md) | High-level Mermaid component diagram |
| [`docs/architecture/ANDROID_ARCHITECTURE.md`](docs/architecture/ANDROID_ARCHITECTURE.md) | Clean Architecture, MVVM, module graph |
| [`docs/architecture/BACKEND_ARCHITECTURE.md`](docs/architecture/BACKEND_ARCHITECTURE.md) | FastAPI modular monolith, service layer |
| [`docs/architecture/AI_ARCHITECTURE.md`](docs/architecture/AI_ARCHITECTURE.md) | AI Orchestrator, providers, memory injection |
| [`docs/architecture/RAG_ARCHITECTURE.md`](docs/architecture/RAG_ARCHITECTURE.md) | RAG ingestion and retrieval pipeline |
| [`docs/architecture/DATABASE_DESIGN.md`](docs/architecture/DATABASE_DESIGN.md) | ER diagram and full table reference |
| [`docs/api/API_SPECIFICATION.md`](docs/api/API_SPECIFICATION.md) | All REST endpoints + WebSocket events |
| [`docs/guides/SECURITY_GUIDE.md`](docs/guides/SECURITY_GUIDE.md) | JWT lifecycle, RBAC, bcrypt, cert pinning |
| [`docs/guides/PERFORMANCE_GUIDE.md`](docs/guides/PERFORMANCE_GUIDE.md) | Performance targets, Paging 3, metrics |
| [`docs/guides/TESTING_STRATEGY.md`](docs/guides/TESTING_STRATEGY.md) | Unit tests, 30 property-based tests, CI gates |
| [`docs/guides/DEVOPS_GUIDE.md`](docs/guides/DEVOPS_GUIDE.md) | Docker Compose, GitHub Actions CI/CD |
| [`docs/integrations/MCP_INTEGRATION.md`](docs/integrations/MCP_INTEGRATION.md) | MCP Broker, all 8 connectors, adding new ones |
| [`docs/guides/CODING_STANDARDS.md`](docs/guides/CODING_STANDARDS.md) | Kotlin/Python style, Educational Header format |
| [`docs/guides/DEPLOYMENT_GUIDE.md`](docs/guides/DEPLOYMENT_GUIDE.md) | Production deployment, cert pinning, Play Store |
| [`docs/environment-configuration.md`](docs/environment-configuration.md) | Stage/Production flavor configuration |
| [`docs/environments.md`](docs/environments.md) | Local/Stage/Production architecture |
| [`docs/on-device-rag.md`](docs/on-device-rag.md) | On-device RAG pipeline (offline) |

---

## Architecture Overview

```
Android App (Kotlin + Jetpack Compose, API 26+)
  │  HTTPS/WSS + Certificate Pinning (SHA-256)
  ▼
Nginx Reverse Proxy (TLS 1.3 termination)  [local/Docker only]
  │                                          Cloud Run handles TLS in production
  ▼
FastAPI Backend (Python 3.11, async)
  ├── Auth Service    — JWT + RBAC + bcrypt + token rotation
  ├── AI Orchestrator — GPT-4o / Gemini / Claude / Ollama / Llama / Mistral
  ├── RAG Pipeline    — ChromaDB vector store + Celery ingestion workers
  ├── Memory Service  — ChromaDB user-scoped memory embeddings
  └── MCP Broker      — 8 external tool connectors (GitHub, Gmail, Drive, ...)
       │
       ├── PostgreSQL 15+  (relational data)
       ├── Redis 7+        (cache + Celery broker)
       ├── ChromaDB        (vector store)
       └── GCS / MinIO     (document object storage — GCS in production, MinIO locally)
       │
       └── Observability: Prometheus + Grafana + Loki
```


---

## On-Device RAG

The app includes a fully on-device Retrieval-Augmented Generation pipeline that runs
inference without any network connection. See [`docs/on-device-rag.md`](docs/on-device-rag.md)
for the complete portfolio documentation.

### On-Device Models

| Model | Role | Variant | Size |
|---|---|---|---|
| Gemma 2B | Text generation (generation-only) | INT4 GGUF | ~1.5 GB |
| Gemma 7B | Text generation (high-quality option) | INT4 GGUF | ~4 GB |
| MiniLM-L6-v2 | Embedding / retrieval | TFLite float16 | ~90 MB |

**Key constraint:** Gemma handles generation only. MiniLM-L6-v2 handles retrieval. The two never share inference calls � this is enforced structurally by the `OnDeviceInferenceEngine` interface which exposes no embedding or search methods (verified by Property 41).

### Minimum Hardware Requirements

| Requirement | Value |
|---|---|
| NPU or dedicated GPU memory | >= 4 GB |
| CPU fallback | Supported (Battery Saver mode activates automatically) |
| Available storage | >= 2 GB (Gemma 2B INT4) / >= 5 GB (Gemma 7B INT4) |
| RAM during inference | >= 512 MB available (enforced � inference cancels below threshold) |

### Supported Document Formats

PDF (`.pdf`), Plain text (`.txt`), Markdown (`.md`). Maximum file size: **50 MB**.

### Running the Benchmark

```bash
./gradlew assembleDebug
./benchmarks/on_device_rag_benchmark.sh
```

Results are saved to `benchmarks/results/benchmark_<timestamp>.json`.
---

## Contributing

1. Read [`docs/CODING_STANDARDS.md`](docs/CODING_STANDARDS.md) before writing code
2. Add an **Educational Header** to every new source file (Kotlin and Python)
3. Write unit tests **and** property-based tests for new functionality
4. Run `./gradlew ktlintCheck detekt testDebugUnitTest` and `cd backend && ruff check . && mypy app/ && pytest` before pushing
5. PRs require all CI checks to pass and coverage ≥ 70%

---

## License

[MIT License](LICENSE)
