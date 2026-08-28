# System Architecture — Android AI Assistant

**Last updated:** 2026-08-26
**Version:** 1.0

---

## High-level architecture

```
Android App (Kotlin + Compose)
  │  HTTPS + WebSocket
  ▼
Cloud Run — FastAPI backend (asia-south1)
  │
  ├── Neon PostgreSQL           ← user data, documents, conversations
  ├── Redis (Upstash / local)   ← rate limiting, Celery broker
  ├── ChromaDB (Cloud Run)      ← vector embeddings for RAG
  ├── Cloud Storage (GCS)       ← raw document files
  └── External APIs
        ├── OpenAI (GPT-4o)
        ├── Google Gemini
        └── Anthropic Claude
```

---

## Services and responsibilities

### FastAPI backend (Cloud Run)

- REST API for all client operations
- WebSocket for real-time chat streaming
- JWT authentication (PyJWT, bcrypt)
- RAG pipeline (upload → extract → chunk → embed → store → query)
- AI orchestration (multi-provider LLM with fallback)
- Background job dispatch via Celery

**URL:** `https://ai-assistant-backend-106071012091.asia-south1.run.app`
**Port:** 8000
**Scaling:** min=0, max=2, concurrency=40

### ChromaDB (Cloud Run, internal)

- Vector store for document embeddings
- Per-user collections: `documents_{user_id}`
- Shared collection: `devops_knowledge` (seeded from knowledge/ folder)
- Storage: ephemeral (wiped on new revision — re-index on deploy)

**Port:** 8001
**Ingress:** internal only (not reachable from public internet)

### Neon PostgreSQL (serverless)

- User accounts, sessions, refresh tokens
- Documents (metadata, ingestion status, file references)
- Conversations, messages
- Celery job tracking
- Alembic migrations applied via Cloud Run Job

**Suspend:** auto-suspends after 5 minutes of inactivity; resumes on first connection

### Cloud Storage (GCS)

- Raw document files (PDF, DOCX, TXT, MD)
- Object path: `{user_id}/{document_id}/{filename}`
- Bucket: `android-ai-assistant-89cec-files`
- Access: via HMAC keys (minio SDK compatible)

### Redis

- Rate limiting (sliding window per user per endpoint)
- Celery task broker and result backend
- Session cache

---

## Android app modules

```
app/                  — activity, navigation, DI wiring
core/                 — shared utilities
core-ui/              — Compose design system (Material 3)
core-network/         — Retrofit, OkHttp, interceptors
core-database/        — Room (local cache)
core-common/          — ObservabilityEvent, ApiResult, PiiFilter
core-security/        — SecureStorage, BiometricAuth, certificate pinning
domain/               — use cases, repository interfaces, domain models
data/                 — repository implementations, Retrofit APIs
feature-auth/         — login, register, biometric unlock
feature-chat/         — conversational AI, streaming responses
feature-rag/          — document upload, ingestion status, document Q&A
feature-voice/        — speech-to-text, voice commands
feature-meeting/      — meeting recording, transcription, summarization
feature-settings/     — provider selection, theme, API config
(+ 7 other feature modules)
```

---

## Authentication flow

```
Android → POST /auth/register or /auth/google → JWT (15min) + Refresh (7 days)
Android → API requests with Authorization: Bearer <JWT>
Backend → verify JWT signature + expiry → extract user_id from sub claim
Android → POST /auth/refresh when JWT expires → new JWT
```

Google Sign-In uses Android Credential Manager → ID token → verified server-side
by Google auth library against `GOOGLE_CLIENT_ID` and `GOOGLE_ANDROID_CLIENT_ID`.

---

## Security controls

| Layer | Control |
|-------|---------|
| API | JWT auth, rate limiting (per user, per endpoint) |
| Data | AES-256 encryption for stored LLM API keys |
| Transport | TLS everywhere; certificate pinning in Android app |
| Secrets | GCP Secret Manager (no secrets in code, env files, or images) |
| CI/CD | Workload Identity Federation (no service account key files) |
| Container | Non-root user, read-only filesystem |
| LLM | Input sanitization (sanitize_user_string), prompt injection detection |

---

## Observability stack

| Pillar | Tool | Where |
|--------|------|-------|
| Logs | Python JSON logger → Cloud Logging | Cloud Run stdout |
| Logs | python-logging-loki → Loki | docker-compose.prod.yml |
| Metrics | prometheus-fastapi-instrumentator → /metrics | Scraped by Prometheus |
| Traces | OpenTelemetry → Cloud Trace | Cloud Run via ADC |
| Dashboards | Grafana (3 pre-built) | http://localhost:3000 |
| Alerts | Prometheus alerting rules | infrastructure/prometheus/alerting.rules.yml |
| Android | ObservabilityEventBus → WorkManager → POST /events | Uploaded every 15 min |
