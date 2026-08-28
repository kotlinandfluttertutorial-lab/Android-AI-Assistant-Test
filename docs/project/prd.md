# Product Requirements Document (PRD)
## Android AI Assistant — Enterprise Edition

**Version:** 1.0  
**Status:** Active  
**Last Updated:** 2025

---

## Executive Summary

The Android AI Assistant (Enterprise Edition) is a production-ready, full-stack AI platform. It
combines an offline-first Android application with a FastAPI backend, multi-LLM orchestration, a
RAG pipeline, MCP tool integrations, and enterprise-grade security and observability. This PRD
covers all functional requirements for the platform.

---

## Functional Requirements Summary

### Requirement 1 — User Authentication
Secure registration (email + 12-character minimum password), JWT issuance (15-min access / 30-day
refresh with rotation), account lockout after 5 failed attempts, Google OAuth2 sign-in, biometric
unlock, RBAC with `user` / `premium` / `admin` roles, and full refresh-token invalidation on logout.

### Requirement 2 — AI Chat
Multi-turn conversations with streaming responses (first token ≤ 500 ms), Markdown rendering with
syntax highlighting, full message history management with automatic summarisation at 80% context
window, regenerate message, copy/share/export, typing indicator, and per-message token usage
tracking.

### Requirement 3 — Multi-LLM Provider Support
Support for OpenAI GPT-4o, Google Gemini 1.5 Pro, Anthropic Claude 3.5 Sonnet, Ollama
(self-hosted), Llama 3.x, and Mistral. Runtime provider switching without app restart, automatic
fallback on provider failure, rate limit enforcement, Ollama privacy guarantee (no external
transmission), and per-provider token cost tracking.

### Requirement 4 — RAG Pipeline (Document Ingestion)
Document upload (PDF, DOCX, TXT, Markdown; max 50 MB), OCR for scanned PDFs, configurable
chunking (default 512 tokens / 64 overlap), embedding generation and vector storage in ChromaDB,
user-scoped retrieval (top-K=5), cited responses (document + page), round-trip property
guarantee, and cleanup within 60 seconds of document deletion.

### Requirement 5 — Voice Assistant
On-device speech-to-text with end-of-speech detection (1,500 ms silence), voice-to-AI pipeline,
text-to-speech output with user-selected voice profile, interrupt control (stop within 300 ms),
optional wake-word activation, and graceful microphone-permission handling with settings deep-link.

### Requirement 6 — Image Understanding
Camera and gallery input (max 4096×4096 px, max 10 MB), OCR with bounding boxes,
`no_text_found` indicator when no text detected, vision-capable LLM routing, barcode and QR code
scanning, unsupported-provider error handling, and camera/gallery permission deep-link.

### Requirement 7 — AI Memory
Vector-based user memory storage (ChromaDB `memories_{user_id}`), top-3 memory injection into
every prompt, user-facing Memory screen (view / edit / delete), deletion within 10 seconds, no
cross-user sharing, and privacy mode that disables capture without deleting existing memories.

### Requirement 8 — MCP Tool Integration
MCP Broker implementing the Model Context Protocol, 8 out-of-the-box connectors (GitHub, Gmail,
Google Drive, Google Calendar, Slack, Jira, Notion, Figma), automatic tool invocation by the AI
Orchestrator within 30 seconds, confirmation dialogs for write operations, structured error
responses, open/closed extensibility (one class per new connector), and 90-day audit logging.

### Requirement 9 — Security
JWT validation on every request (HTTP 401 on failure), RBAC enforcement (HTTP 403 on failure),
bcrypt passwords (work factor 12), EncryptedSharedPreferences for Android credentials, certificate
pinning, prompt injection detection (HTTP 400 + audit log), parameterised SQL, 90-day audit log
retention, 60 req/min rate limiting (HTTP 429), IP-based 20 req/min on public endpoints, and
AES-256-GCM API key encryption.

### Requirement 10 — Offline-First Architecture
Room as single source of truth, cache of 500 conversations / 10,000 messages, offline message
queue via WorkManager, automatic sync within 30 seconds of connectivity restoration (server-wins
for messages, local-wins for preferences), persistent offline banner, full read access to cached
conversations, and 3-retry exponential backoff (5 s, 2×, 60 s cap) with failure notification.

### Requirement 11 — Conversation History and Search
Paginated list (Paging 3, 20 items/page), FTS search within 300 ms, pin / rename / soft-delete
(cache removal within 5 seconds), date-category grouping (Today / Yesterday / Last 7 Days /
Older), and Markdown / PDF export within 10 seconds.

### Requirement 12 — Code Assistant
Syntax-highlighted code editor (Kotlin, Java, Python, JavaScript, C++, SQL; max 500 lines / 50,000
characters), code explanation (what/why/improvements), bug fixing with inline change comments,
unit test generation (Arrange-Act-Assert, min 1 test per function), single-tap clipboard copy, and
language identifier in every code response.

### Requirement 13 — Notes and Productivity
Markdown notes editor with live preview (renders within 500 ms), AI summarisation (≤ 150 words;
truncated to exactly 150 if exceeded), AI rewrite (learned or neutral-professional style), Room +
backend sync with `syncStatus`, and tag-based filtering (max 50 tags/note, max 50 chars/label).

### Requirement 14 — Resume and Email Generation
ATS-optimised resume (Markdown, within 30 seconds), cover letter ≤ 400 words (job description
required), PDF/DOCX export to Downloads, structured email generation (subject / greeting / body
≤ 300 words / closing), and grammar correction with inline diff highlighting.

### Requirement 15 — Admin Dashboard
Real-time metrics (active users, messages/hour, token usage, per-provider cost refreshed within
60 s, error rates), user management (view / search / promote / demote / deactivate with immediate
token invalidation), feature-usage analytics, paginated filterable audit log, error monitoring
(top-10 errors last 24 hours with stack traces), feedback management with CSV export, Firebase
Remote Config UI, and real-time session monitor.

### Requirement 16 — Notifications
FCM push notification on RAG ingestion completion and queued message retry success, permission
request during onboarding (deny → all notifications suppressed for install lifetime),
per-category notification preferences, and automatic push token rotation.

### Requirement 17 — Performance and Scalability
Cold start ≤ 2 seconds (Snapdragon 700-series), REST API p95 ≤ 200 ms at 1,000 concurrent users,
first streaming token ≤ 500 ms, RAG ingestion ≤ 30 seconds for a 10-page PDF, horizontal
scalability behind load balancer, and Paging 3 (20 items/page).

### Requirement 18 — Analytics and Observability
Structured JSON request logs with correlation ID, Prometheus `/metrics` endpoint, Grafana
dashboards (AI cost / request volume / error rates), Loki log aggregation (indexed by correlation
ID and user ID), unhandled exception logging, and Firebase Crashlytics + Analytics integration.

### Requirement 19 — Android Architecture and Code Quality
Gradle multi-module Clean Architecture (`app`, `core-*`, `domain`, `data`, `feature-*`), strict
layer dependency direction (feature → domain ← data), Hilt DI, Coroutines + Flow, ktlint +
Detekt zero-error tolerance, JaCoCo ≥ 70% coverage on `domain` + `data`.

### Requirement 20 — Backend Architecture and Code Quality
FastAPI modular monolith, Pydantic v2 schemas, SQLAlchemy 2.x ORM, Alembic migrations, Celery +
Redis background workers, pytest ≥ 70% coverage, ruff + mypy zero-error tolerance.

### Requirement 21 — CI/CD and DevOps
GitHub Actions CI for Android (ktlint, Detekt, unit tests, coverage gate) and backend (ruff,
mypy, pytest, coverage gate), Docker Compose full stack, Alembic migration automation, and
`.env.example` environment management.

### Requirement 22 — Documentation
`/docs` with 16+ Markdown documents, Educational Headers on every source file (Kotlin and
Python), README with clean-install setup completable in under 15 minutes, and Mermaid
architecture diagrams in each doc.

### Requirement 23 — Meeting Assistant
Meeting recording and transcription (timestamped, speaker attribution), AI-generated meeting
summary, action item extraction (assignee + description), PDF / Markdown export, state machine
(Idle → Recording → Processing → Complete), and microphone-permission deep-link.

### Requirement 24 — Translator
Online translation via AI Orchestrator (all language pairs), offline via bundled on-device model,
text and speech input, and language pair persistence in DataStore.

### Requirement 25 — Productivity Suite — To-Do
TodoItem CRUD with title / description / due date / priority / tags, AI-generated todo lists
(up to 20 items, user confirmation before persist), Paging 3 list with completion and due-date
filters, local-first Room + backend sync.

### Requirement 26 — Productivity Suite — Calendar
Monthly/weekly CalendarView, CalendarEvent CRUD, Google Calendar MCP connector merge (local
events take precedence on title conflicts), AI-suggested meeting times (3–10 slots).

### Requirement 27 — Productivity Suite — Reminders
Reminder CRUD with trigger time / iCal RRULE recurrence / linked TodoItem, AlarmManager exact
alarms (`SCHEDULE_EXACT_ALARM` on Android 12+), local notifications, AI-suggested reminders
(user confirmation before persist), and in-app fallback when notification permission denied.

### Requirement 28 — Productivity Suite — Habit Tracker
HabitDefinition CRUD (daily/weekly recurrence), HabitEntry logging, streak tracking, AI-generated
insights (minimum 7 days of entries required), patterns / best days / streak predictions.

### Requirement 29 — Productivity Suite — Sync
All four Productivity sub-types use local-first + last-write-wins (`updated_at`) conflict
resolution with `syncStatus` field (`pending` → `processing` → `ready` / `failed`).

---

## Technical Constraints

| Constraint | Value |
|------------|-------|
| Minimum Android SDK | 26 (Android 8.0) |
| Target Android SDK | 35 |
| JDK | 17 |
| Python | 3.11+ |
| PostgreSQL | 15+ |
| Redis | 7+ |
| Vector Store | ChromaDB (latest) |
| Object Storage | MinIO (latest) |
| Background Jobs | Celery 5+ |
| Nginx | 1.25+ |

---

## Out of Scope for v1.0

- iOS or web client
- Custom LLM fine-tuning or training
- Real-time collaborative editing
- HIPAA / PCI-DSS compliance certification (controls are aligned, not certified)
