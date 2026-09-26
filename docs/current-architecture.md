# Current Architecture — Android AI Assistant (Enterprise Edition)

> **Phase 0 Repository Audit**  
> Date: 2026-09-26  
> Status: Read-only survey. No application code was modified.

---

## Table of Contents

1. [Repository Overview](#1-repository-overview)
2. [Current Architecture](#2-current-architecture)
   - 2.1 [Android](#21-android)
   - 2.2 [Flutter Companion App](#22-flutter-companion-app)
   - 2.3 [Backend (FastAPI)](#23-backend-fastapi)
   - 2.4 [Infrastructure](#24-infrastructure)
3. [Current AI Request Flow](#3-current-ai-request-flow)
4. [Current Chat Flow](#4-current-chat-flow)
5. [Current Code Flow](#5-current-code-flow)
6. [Current RAG Flow](#6-current-rag-flow)
7. [Current PDF Flow](#7-current-pdf-flow)
8. [Current Tool (MCP) Flow](#8-current-tool-mcp-flow)
9. [Current On-Device Gemma Flow](#9-current-on-device-gemma-flow)
10. [Existing Reusable Services](#10-existing-reusable-services)
11. [Existing API Contracts](#11-existing-api-contracts)
12. [Existing WebSocket Contracts](#12-existing-websocket-contracts)
13. [Existing Database Models](#13-existing-database-models)
14. [Existing Infrastructure](#14-existing-infrastructure)
15. [Existing Problems / Duplication](#15-existing-problems--duplication)
16. [Recommended Integration Points](#16-recommended-integration-points)
17. [Build & Test Status](#17-build--test-status)

---

## 1. Repository Overview

```
Android-AI-Assistant-Test/
├── app/                        # Android application shell
├── core-common/                # ApiResult, DispatcherProvider, DomainError, SessionManager
├── core-ui/                    # Material3 theme, adaptive layouts, reusable Compose components
├── core-network/               # OkHttp, Retrofit, interceptors, federation, observability
├── core-database/              # Room v3, all DAOs and entities
├── core-ai/                    # AIStreamClient (WebSocket), on-device engine interfaces
├── core-security/              # BiometricAuthManager, SecureStorage, RootDetectionUtil
├── core-on-device-ai/          # On-device model management, RAM monitor, inference stub
├── domain/                     # Domain models, repository interfaces, all use cases
├── data/                       # Repository implementations, remote/local data sources
├── feature-auth/               # Login, register, onboarding, biometric, splash
├── feature-chat/               # Conversation list, chat detail, comparison mode
├── feature-code/               # Code editor, AI code analysis
├── feature-voice/              # Voice assistant (STT → AI → TTS)
├── feature-rag/                # Document list, document chat, file picker
├── feature-history/            # Conversation history, search
├── feature-settings/           # Settings, cost dashboard
├── feature-profile/            # Profile, memory list
├── feature-dashboard/          # DevOps AI dashboard, incident list
├── feature-camera/             # CameraX capture, OCR, image analysis
├── feature-on-device-ai/       # On-device AI management, model download
├── feature-on-device-rag/      # On-device RAG chat, benchmark, model management
├── feature-persona/            # Persona editor and selector
├── feature-search/             # Semantic search
├── feature-notes/              # Notes with AI rewrite/summarise
├── feature-meeting/            # Meeting recording and summarisation
├── feature-resume/             # Resume and cover-letter generation
├── feature-email/              # AI email generation
├── feature-translator/         # Text translation
├── feature-productivity/       # Todos, calendar events, reminders, habits
├── backend/                    # Python FastAPI backend
├── flutter/                    # Flutter companion app (Riverpod, Dio, WebSocket)
├── terraform/                  # GCP Cloud Run + Artifact Registry + GCS IaC
├── docker-compose.yml          # Local dev: Postgres (pgvector), Redis, MinIO, ChromaDB, backend, Celery
└── gradle/libs.versions.toml   # Version catalog (AGP 8.8.0, Kotlin 2.0.21, Compose BOM 2024.09.03)
```

**Module count:** 1 app + 7 core + 2 arch (domain/data) + 21 feature + flutter + backend = **32+ modules**

---

## 2. Current Architecture

### 2.1 Android

#### Language & Build

| Dimension | Value |
|---|---|
| Language | Kotlin 2.0.21 (100 % — no Java) |
| Build system | Gradle 8.11.1 + AGP 8.8.0 |
| Min SDK | Inferred API 26+ (Instant.now(), BiometricPrompt) |
| Target SDK | Inferred API 34/35 |
| Kotlin Symbol Processing | KSP 2.0.21-1.0.25 (Hilt, Room) |
| Lint / style | Detekt 1.23.7 + ktlint 1.3.1 |

#### Architectural Pattern

**Strict Clean Architecture + MVVM** enforced across all feature modules:

```
Feature Module
├── Screen (Composable)          — observes ViewModel StateFlow
├── ViewModel (@HiltViewModel)   — exposes StateFlow<UiState>, dispatches to use cases
├── UiState (data class / sealed) — immutable UI snapshot
└── DI Module (@Module @InstallIn)

Domain Module
├── Model (data class)           — pure Kotlin, no Android deps
├── Repository (interface)       — contracts only
└── UseCase (class)              — single-responsibility, calls repository

Data Module
├── RepositoryImpl               — implements domain interfaces
├── RemoteDataSource             — Retrofit service calls
├── LocalDataSource              — Room DAO calls
└── Mapper                       — remote ↔ domain ↔ entity conversions
```

Every ViewModel is `@HiltViewModel` injected. Use cases are plain Kotlin classes injected via constructor. No `LiveData` — everything is `StateFlow` / `Flow`. No XML layouts — 100 % Jetpack Compose.

#### Dependency Injection (Hilt)

- **SingletonComponent** bindings: `OkHttpClient`, `Retrofit`, `AppDatabase`, `AIStreamClient`, `ConnectivityObserver`, `SecureStorage`, all repository implementations.
- **Feature-scoped DI modules**: each feature module ships its own `@Module @InstallIn(SingletonComponent::class)` (e.g., `ChatModule`, `RAGModule`, `VoiceModule`).
- **Named qualifiers**: `@Named("wsBaseUrl")` for the WebSocket URL, `@Named("isDebugBuild")` for debug/release switching in certificate pinning.

#### Jetpack Compose UI

- **Material3** everywhere with custom `AppTheme` (light/dark, `ThemeMode` preference via DataStore).
- **Adaptive navigation shell** (`AppNavigationShell`): `NavigationBar` (compact) → `NavigationRail` (medium) → `PermanentNavigationDrawer` (expanded/tablet).
- **Bottom nav tabs**: Chat · History · Docs · Voice · Settings.
- **Navigation**: `NavHostController` + `androidx.navigation.compose`. Each feature exports a route constant and a `NavGraphBuilder` extension (`ChatNavigation`, `RAGNavigation`, etc.).
- **Shared Compose components** in `core-ui`: `ChatBubble`, `StreamingMessage`, `MarkdownText`, `CodeBlock`, `MessageInputBar`, `TypingIndicator`, `LoadingIndicator`, `ErrorBanner`, `OfflineBanner`, `ShimmerSkeleton`, `ConnectivityStatusBar`, `AiModeIndicator`, `RagComponents`.

#### ViewModels Inventory

| Module | ViewModel | Responsibilities |
|---|---|---|
| feature-chat | `ChatViewModel` | Conversation list, Paging3, FTS search, pin/rename/delete |
| feature-chat | `ChatDetailViewModel` | WebSocket streaming, token accumulation, retry, export, suggestions |
| feature-chat | `ComparisonModeViewModel` | Side-by-side provider comparison |
| feature-code | `CodeViewModel` | Code editor state, language/action selection, AI analysis |
| feature-voice | `VoiceViewModel` | STT→AI→TTS state machine |
| feature-rag | `RAGViewModel` | Document list (Paging3), upload lifecycle, ingestion polling |
| feature-rag | `DocumentChatViewModel` | RAG Q&A chat with citations |
| feature-history | `HistoryViewModel` | Conversation history list |
| feature-settings | `SettingsViewModel` | Preferences (theme, provider, privacy) |
| feature-settings | `CostDashboardViewModel` | Token usage and cost display |
| feature-profile | `ProfileViewModel` | User profile, memory management |
| feature-auth | `AuthViewModel` | Login/register/Google OAuth |
| feature-auth | `BiometricHelperViewModel` | Biometric unlock |
| feature-dashboard | `DashboardViewModel` | DevOps AI analysis, incident list |
| feature-camera | `CameraViewModel` | CameraX capture, OCR, image analysis |
| feature-on-device-ai | `OnDeviceAiModule` | (Module only — no public ViewModel) |
| feature-on-device-rag | `OnDeviceRagViewModel` | On-device RAG chat |
| feature-on-device-rag | `ManageModelsViewModel` | Model download/delete |
| feature-on-device-rag | `BenchmarkViewModel` | On-device benchmark runs |
| feature-persona | `PersonaViewModel` | Persona CRUD, active persona selection |
| feature-search | `SemanticSearchViewModel` | Backend semantic search |

#### Repositories (Domain Interfaces → Data Implementations)

Defined in `:domain`, implemented in `:data`. Examples:

| Interface | Implementation |
|---|---|
| `ConversationRepository` | `ConversationRepositoryImpl` (local Room + remote Retrofit) |
| `MessageRepository` | `MessageRepositoryImpl` |
| `DocumentRepository` | `DocumentRepositoryImpl` (upload + poll ingestion job) |
| `MemoryRepository` | `MemoryRepositoryImpl` |
| `AuthRepository` | `AuthRepositoryImpl` |
| `PersonaRepository` | `PersonaRepositoryImpl` |
| `OnDeviceDocumentRepository` | `OnDeviceDocumentRepositoryImpl` (Room only) |
| `QueryRoutingLogRepository` | `QueryRoutingLogRepositoryImpl` |
| `ModelFileRepository` | `ModelFileRepositoryImpl` (file system) |
| `SemanticSearchRepository` | `SemanticSearchRepositoryImpl` |

Total: 25 domain repository interfaces, 25 matching implementations.

#### Network Layer (`core-network`)

```
OkHttpClient
  ├── AuthInterceptor            — attaches "Bearer <jwt>" from SecureStorage
  ├── CertificatePinningInterceptor — SHA-256 SPKI hash enforcement (bypassed in debug)
  ├── RefreshTokenInterceptor    — authenticator() that retries on HTTP 401 via /auth/refresh
  ├── NetworkObservabilityInterceptor — latency + status code structured events
  └── HttpLoggingInterceptor     — BODY in debug / NONE in release (binary → HEADERS)

Retrofit
  └── baseUrl from EnvironmentConfig (BuildConfig flavor field)
      └── KotlinxSerializationConverterFactory

Federation layer (core-network/federation/)
  ├── BackendEndpointSelector    — primary + failover endpoint selection
  ├── FederationHealthCheckWorker — periodic health probe (WorkManager)
  ├── FailoverInterceptor        — redirects to failover on 503/504
  └── FailoverBannerStateProvider — exposes failover banner state to UI
```

Timeouts: connect 30 s, read 90 s (Cloud Run cold-start headroom), write 60 s.

#### WebSocket (`core-ai` — `AIStreamClientImpl`)

- Connects to `wss://<host>/ws/chat/{conversationId}?token={jwt}`.
- Implemented as a cold `callbackFlow` over `OkHttpClient.newWebSocket`.
- Reconnects with **exponential backoff**: 1 s → 2 s → 4 s → 8 s → 16 s (max 30 s), up to 5 attempts.
- Parses structured JSON frames: `token` / `done` / `error` / `tool_call`.
- `Done` events close the flow normally (no reconnect).
- `Error` events surface as `StreamEvent.Error`; reconnect is manual (user taps retry).

#### Local Storage

| Store | Technology | Contents |
|---|---|---|
| Room database v3 | SQLite via Room | Users, Conversations, Messages (+ FTS4), Documents, Memories, Notes, Todos, CalendarEvents, Reminders, HabitDefinitions, HabitEntries, OnDeviceDocuments, OnDeviceChunks, QueryRoutingLog |
| DataStore (Preferences) | Jetpack DataStore | Theme mode, LLM provider preference, privacy mode, settings flags |
| SecureStorage | `EncryptedSharedPreferences` (security-crypto alpha) | JWT access token, refresh token |
| File system | File I/O | Downloaded GGUF model files (on-device AI) |

Room `AppDatabase` is at version 3. Migrations 1→2 and 2→3 exist in `DatabaseMigrations.kt`. FTS4 virtual tables on `ConversationFtsEntity` and `MessageFtsEntity` enable full-text search.

#### On-Device AI (`core-on-device-ai` / `feature-on-device-ai`)

- `OnDeviceInferenceClient` — implements `AIStreamClient`. Uses a `Channel<MessagePayload>` + `callbackFlow` to emit `StreamEvent` tokens. Currently **stub JNI** — `runLocalInference` simulates token streaming with `delay(50ms)`. Real inference requires replacing one function body with a JNI call to llama.cpp.
- `MediaPipeInferenceEngine` — MediaPipe LLM Inference API integration path (alternative engine).
- `MiniLmEmbeddingModel` — on-device sentence embedding for local RAG.
- `LocalVectorIndexImpl` — in-memory cosine-similarity vector index for on-device RAG retrieval.
- `QueryRouterImpl` — routes queries either to on-device or cloud path based on `OnDeviceCapabilityState`.
- `RamMonitor` — emits `RamEvent.BelowThreshold` when free RAM < 512 MB; cancels in-flight inference.
- `HardwareCapabilityDetector` / `DeviceCapabilityDetector` — checks RAM, CPU cores, storage to qualify device for on-device inference.
- `OnDeviceModelManager` — download, verify (SHA-256), delete GGUF model files.

The `ON_DEVICE_PROVIDER_ID` constant in `core-ai` is used by `ChatDetailViewModel` to branch to `OnDeviceInferenceClient` instead of the cloud `AIStreamClientImpl`.

---

### 2.2 Flutter Companion App

Located in `/flutter/`. A parallel client targeting the same backend.

| Dimension | Value |
|---|---|
| State management | Riverpod (flutter_riverpod) |
| HTTP client | Dio with `AuthInterceptor` (JWT Bearer) |
| WebSocket | `web_socket_channel` (WebSocketService wrapper) |
| Navigation | GoRouter (inferred from `main_shell.dart`) |

**Screens present:** `ChatScreen`, `ConversationsScreen`, `HomeScreen`, `LoginScreen`, `RegisterScreen`, `SettingsScreen` (on-device AI toggle).

**WebSocket contract:** identical to Android — connects to `/ws/chat/{conversationId}?token=<jwt>`, handles `token` / `done` / `error` / `tool_call` / `ping` / `pong`.

**State:** `ChatProvider` (Riverpod `StateNotifier`), `AuthProvider`, `ConversationsProvider`, `AiProviderProvider`, `OnDeviceAiProvider`.

**Shared widgets:** `MessageBubble`, `TypingIndicator`, `ProviderSelector`, `ConnectionStatusBanner`, `EmptyState`, `ErrorView`, `LoadingIndicator`.

The Flutter app is a **secondary client** — it is not wired into the Android Gradle build and ships separately.

---

### 2.3 Backend (FastAPI)

#### Stack

| Component | Technology |
|---|---|
| Framework | FastAPI (async) + Uvicorn |
| ORM | SQLAlchemy 2.x async + asyncpg |
| Database | PostgreSQL 16 + pgvector extension |
| Cache / Broker | Redis 7 |
| Task queue | Celery (workers: rag / notification / gdpr / anomaly / alert) |
| Vector DB | ChromaDB 1.5.9 (embedded `PersistentClient`) |
| Object storage | MinIO (local/stage) / GCS (production) |
| Auth | JWT (HS256, 15-min access + 30-day refresh), bcrypt (12 rounds) |
| Config | pydantic-settings (`Settings` class, `.env` file) |
| Observability | Prometheus (prometheus-fastapi-instrumentator), structured JSON logging, OpenTelemetry (optional) |
| Migrations | Alembic |

#### API Layer Structure

```
backend/app/api/
  auth/          POST /auth/register, /auth/login, /auth/logout, /auth/refresh, /auth/google
  chat/          POST /chat/message, POST /api/v1/chat  (non-streaming REST)
  websocket/     WS   /ws/chat/{conversation_id}?token=  (streaming)
  conversations/ GET/POST/DELETE/PATCH /conversations/*
  rag/           POST/GET/DELETE /documents/*, GET /jobs/{id}
  memory/        GET/POST/DELETE /memories/*
  code/          POST /code/analyze
  mcp/           GET /mcp/tools, POST /mcp/invoke
  personas/      CRUD /personas/*
  productivity/  CRUD /todos, /calendar, /reminders, /habits
  search/        POST /search/semantic
  suggestions/   GET /suggestions/context
  generation/    POST /resumes, /covers, /emails
  images/        POST /images/analyze
  transcription/ POST /transcription/
  translation/   POST /translation/
  users/         GET/PUT /users/me
  usage/         GET /usage/cost
  prompts/       CRUD /prompts/templates/*
  notifications/ POST /notifications/device-token
  admin/         GET/POST /admin/* (admin role required)
  analytics/     GET /analytics/*
  observability/ POST /api/v1/observability/events
  analysis/      POST /analysis/errors
  incidents/     CRUD /incidents/*
  devops/        POST /devops/chat
  data/          GET /data/export (GDPR), DELETE /data/account
```

#### Middleware Stack (outermost → innermost)

1. `RequestLoggingMiddleware` — structured logs + correlation ID
2. `RateLimitMiddleware` — Redis sliding-window per user/IP
3. `DataResidencyMiddleware` — geo-region enforcement
4. `RequestBodySizeLimitMiddleware` — guards oversized payloads
5. `CORSMiddleware` — configured from `settings.CORS_ORIGINS`

#### Service Layer

| Service | Purpose |
|---|---|
| `AIOrchestrator` | Central LLM hub: resolves provider, builds prompt (system + memories + history + user msg), injection detection, safety filter, streams via WebSocket, records token usage |
| `LLMService` (llm/service.py) | Provider-agnostic LLM calling abstracted through `LLMProvider` base class |
| `RAGService` | Document ingestion pipeline + semantic retrieval with citations |
| `MemoryService` | Store / retrieve / delete long-term user memories, respects privacy_mode |
| `AuthService` | JWT issuance, refresh rotation (replay detection), account lockout |
| `SafetyService` | Prompt injection detection (`InjectionDetector`) + response content filtering |
| `PromptService` | Versioned prompt template CRUD + rollback |
| `PersonaService` | User persona management |
| `MCPBroker` | MCP tool registry + dispatcher with mandatory audit log per invocation |
| `StorageService` | MinIO / GCS abstraction |
| `CostService` | Token usage aggregation + spending alert evaluation |
| `SearchService` | Semantic search over user's document corpus |
| `SuggestionsService` | Context-aware continuation chip suggestions |
| `ProductivityService` | Todo / calendar / reminder / habit CRUD |
| `MemoryRepository` | ChromaDB per-user collection wrapper + PG metadata |
| `AnomalyDetectionService` | ML anomaly detection over observability events |
| `DevOpsAssistantService` | RAG-backed DevOps chat against knowledge base |
| `RcaService` / `RemediationService` | Root-cause analysis + remediation recommendations |

#### LLM Provider Adapters (`services/llm_clients.py`)

Six concrete `BaseLLMClient` implementations:

| Class | Provider | Notes |
|---|---|---|
| `GeminiClient` | Google Gemini (google-genai SDK) | Default; `gemini-3.1-flash-lite` in compose |
| `OpenAIClient` | OpenAI GPT-4o | AsyncOpenAI |
| `ClaudeClient` | Anthropic Claude 3.5 Sonnet | AsyncAnthropic |
| `OllamaClient` | Local Ollama endpoint | Zero external calls |
| `LlamaClient` | Llama 3.x via Ollama | Routed to `OLLAMA_BASE_URL` |
| `MistralClient` | Mistral via Ollama | Routed to `OLLAMA_BASE_URL` |

**New LLM abstraction layer** (`llm/providers/`): two additional `LLMProvider` (base class from `llm/base.py`) implementations exist alongside the older service-layer adapters:

- `GeminiProvider` — new SDK-based implementation with retry, fallback model, quota detection.
- `LocalGemmaProvider` — delegates to `OllamaClient` with `gemma3:latest`; zero external calls.

#### Celery Workers

| Worker | Queue | Tasks |
|---|---|---|
| `rag_worker.py` | `ingestion` | `ingest_document_task` — async document extraction → chunking → embedding → ChromaDB/PG |
| `notification_worker.py` | `notifications` | Push notification dispatch via FCM |
| `gdpr_worker.py` | `gdpr` | Account deletion data erasure, export packaging |
| `anomaly_worker.py` | `celery` | Anomaly detection batch processing |
| `alert_worker.py` | `alerts` | Spending alert evaluation + notification dispatch |

#### Security Layer (`security/`)

- `jwt_handler.py` — HS256 JWT creation/verification, refresh token generation + hashing.
- `password.py` — bcrypt (12 rounds) hash/verify.
- `lockout.py` — failed-login counter with Redis; account locked after N failures.
- `rbac.py` — `require_roles`, `require_admin`, `require_premium_or_admin` FastAPI dependencies.
- `audit.py` — `AuditService` writes `AuditLog` rows for every sensitive action (MCP invoke, admin ops, login).
- `encryption.py` — AES-256 field-level encryption for stored secrets.
- `differential_privacy.py` — ε-differential privacy for analytics queries.
- `input_sanitizer.py` — HTML/script injection scrubbing before storage.
- `dependencies.py` — `get_current_user` FastAPI dependency.

---

### 2.4 Infrastructure

| Layer | Technology |
|---|---|
| Cloud | GCP (Cloud Run, Artifact Registry, GCS, Secret Manager, Cloud Build) |
| IaC | Terraform (modules: cloudrun, storage, iam, networking, monitoring) |
| Container | Docker multi-stage (`Dockerfile`, `entrypoint.sh`) |
| Local dev | Docker Compose: postgres+pgvector, redis, minio, chromadb, backend, celery_worker |
| CI/CD | GitHub Actions (android-ci, backend-ci, cloud-run-deploy, security-scan, infrastructure-validation, release) |
| Monitoring | Prometheus (prometheus-fastapi-instrumentator), Grafana (disabled on Windows dev), Loki |
| Secrets | GCP Secret Manager (production), `.env.local` / `.env.stage` files (development) |

---

## 3. Current AI Request Flow

### Cloud Path (standard)

```
User types in ChatDetailScreen (Android)
  │
  ▼
ChatDetailViewModel.sendMessage(content)
  │  optimistic user message added to UiState
  │
  ▼
SendMessageUseCase(conversationId, content, provider)
  │  persists user message via MessageRepository → MessageRemoteDataSource
  │  → POST /conversations/{id}/messages
  │
  ▼
AIStreamClientImpl.connect(conversationId, jwt)          [core-ai]
  │  opens OkHttp WebSocket:
  │  wss://<host>/ws/chat/{conversationId}?token={jwt}
  │
  ▼  [backend — websocket/router.py]
authenticate_websocket(token)                            JWT validation
  │
  ▼
flush_token_buffer(user_id, conversationId, ws)          Redis buffered tokens from prior disconnect
  │
  ▼
HeartbeatMonitor.run()                                   30-s ping/pong task
  │
  ▼
_handle_messages() loop
  │  receives {"user_message": "...", "provider": "gemini"}
  │
  ▼
AIOrchestrator.stream_chat(conversation_id, user_message, provider, user_id, ws)
  │
  ├─ 1. Fetch last N messages from MessageRepository (conversation history)
  ├─ 2. MemoryService.retrieve_memories(user_id, user_message, top_k=3)
  │       → embed query → ChromaDB cosine search → PG metadata lookup
  ├─ 3. _detect_prompt_injection(user_message) → HTTP 400 if detected
  ├─ 4. _apply_safety_filters(user_message)    → SafetyFilterError if blocked
  ├─ 5. Build prompt: system_prompt + memories + history + user_message
  │       context window > 80%? → summarize older history via LLM
  ├─ 6. Resolve provider → GeminiClient / OpenAIClient / ClaudeClient / etc.
  ├─ 7. BaseLLMClient.stream(context) → AsyncIterator[str] (tokens)
  │       on quota error → fallback to GEMINI_FALLBACK_MODEL
  │
  ▼
  for token in stream:
    ws.send_json({"type": "token", "data": token})
      [_BufferingWebSocketProxy: if client disconnected → buffer_token() in Redis]
  │
  ▼
  ws.send_json({"type": "done", "usage": {inputTokens, outputTokens}})
  │
  ▼
  TokenUsageRepository.record(user_id, conversation_id, feature, tokens)
  MessageRepository.save(assistant_message)
  │
  ▼  [back on Android — AIStreamClientImpl callbackFlow]
StreamEvent.Token → ChatDetailUiState.streamingText += token
StreamEvent.Done  → commit assistant Message, clear streaming state
StreamEvent.Error → show retry banner (manual reconnect only)
```

### On-Device Path

```
User selects "On-device (Gemma)" provider
  │
  ▼
ChatDetailViewModel.sendMessage(content)  [provider = ON_DEVICE_PROVIDER_ID]
  │
  ▼
OnDeviceInferenceClient.connect(conversationId, jwt="placeholder")
  │  no network call — jwt parameter is accepted but never sent
  │
  ├─ RAM pre-flight check (< 512 MB → StreamEvent.Error)
  ├─ RamMonitor.observe() concurrent monitor during inference
  │
  ▼
runLocalInference(payload)
  │  CURRENT: simulated tokens (delay 50ms, ~10 words)
  │  FUTURE:  JNI → llama.cpp → real GGUF inference
  │
  ▼
StreamEvent.Token (×N) → ChatDetailViewModel accumulates text
StreamEvent.Done → commit assistant message
```

---

## 4. Current Chat Flow

```
┌─────────────────────────────────────────────┐
│ ChatListScreen                              │
│   ChatViewModel.uiState (StateFlow)         │
│   ├── isOffline (ConnectivityObserver)      │
│   ├── searchResults (FTS search)            │
│   └── pagedConversations (Paging3)          │
│       ← GroupedConversations (Today/        │
│         Yesterday/Last7Days/Older)          │
│                                             │
│ Actions: createConversation()               │
│          deleteConversation()               │
│          pinConversation()                  │
│          renameConversation()               │
│          setSearchQuery()                   │
└─────────────────────────────────────────────┘
           │ navigate(ChatRoute.DETAIL + conversationId)
           ▼
┌─────────────────────────────────────────────┐
│ ChatDetailScreen                            │
│   ChatDetailViewModel.uiState (StateFlow)   │
│   ├── messages: List<Message>               │
│   ├── streamingText: String                 │
│   ├── isStreaming: Boolean                  │
│   ├── isTypingIndicatorVisible: Boolean     │
│   ├── isRunningOnDevice: Boolean            │
│   ├── error: DomainError?                   │
│   ├── showRetryOption: Boolean              │
│   ├── provider: String                      │
│   └── continuationSuggestion: ContextSugg? │
│                                             │
│ User actions:                               │
│   sendMessage(content)                      │
│     → optimistic UI → use case → WebSocket │
│   regenerateMessage(messageId)              │
│   retryStreaming()                          │
│   exportConversation(format)                │
│   dismissError()                            │
│   setProvider(provider)                     │
│   checkContinuationSuggestion()             │
│   acceptContinuationSuggestion()            │
└─────────────────────────────────────────────┘

Backend conversation persistence:
  ConversationRepository
    ├── local: ConversationDao (Room) — offline cache
    └── remote: ConversationApiService (Retrofit)
              MessageApiService
  SyncMessagesWorker (WorkManager) — offline queue drain
```

---

## 5. Current Code Flow

```
CodeEditorScreen
  │  user enters code, selects language + action (EXPLAIN / DEBUG / REFACTOR / etc.)
  │
  ▼
CodeViewModel.analyzeCode(code, language, action)
  │
  ▼
AnalyzeCodeUseCase(CodeAnalysisRequest)
  │
  ▼
CodeRepositoryImpl → CodeRemoteDataSource
  │  POST /code/analyze
  │  body: { code, language, action }
  │
  ▼  [backend — api/code/router.py]
InjectionDetector.check_input(code)        prompt injection guard
  │
  ▼
LLMService.complete(prompt)               non-streaming REST call
  │  prompt built by PromptBuilder using CodeAnalysisPrompt template
  │
  ▼
GeminiProvider / configured provider
  │
  ▼
CodeAnalysis { explanation, suggestions, correctedCode, language }
  │
  ▼
CodeViewModel.uiState → CodeAnalysisScreen displays result
  ├── syntax-highlighted corrected code (CodeBlock in core-ui)
  └── markdown explanation (MarkdownText in core-ui)
```

---

## 6. Current RAG Flow

### Ingestion Pipeline

```
User selects file in FilePickerBottomSheet (RAGScreen)
  │  supported: PDF / DOCX / TXT / MD (max size checked client-side)
  │
  ▼
RAGViewModel.uploadDocument(uri, mimeType)
  │
  ▼
UploadDocumentUseCase → DocumentRepositoryImpl → DocumentRemoteDataSource
  │  POST /documents  (multipart/form-data)
  │
  ▼  [backend — rag/router.py]
Property 26: MIME type + extension + size validation  ← BEFORE any I/O
  │
  ▼
StorageService.upload(file) → MinIO bucket
  │
  ▼
JobRepository.create_job(document_id)  → job_id returned to client
  │
  ▼
Celery task: ingest_document_task.delay(document_id, user_id)
  │
  ▼  [rag_worker.py / rag_service.py]
RAGService._extract_text(file_bytes, mime)
  ├── PDF  → pdfplumber (primary) → pytesseract OCR (fallback)
  ├── DOCX → python-docx
  └── TXT/MD → direct read

RAGService._chunk_text(text)
  └── fixed-size sliding window with overlap
      Property 7: every token appears in ≥1 chunk

RAGService._embed_chunks(chunks)
  └── SentenceTransformer model (warmed up at startup)

ChromaDB: add_documents(collection=user_{user_id}, embeddings, metadata)
  └── per-user collection isolation (Property 8)

PostgreSQL: DocumentChunk rows with page_number, chunk_index, text

Job status: PENDING → PROCESSING → COMPLETED | FAILED
```

### Query / Retrieval Pipeline

```
User asks question in DocumentChatScreen
  │
  ▼
DocumentChatViewModel → QueryDocumentUseCase
  │  POST /documents/query  (or /documents/{id}/query for single-doc scope)
  │  body: { query, document_id?, top_k }
  │
  ▼  [backend]
RAGService.query(user_id, query, document_id?, top_k=5)
  │
  ├── embed(query) → SentenceTransformer
  ├── ChromaDB.query(collection=user_{user_id}, embedding, n_results=top_k)
  ├── Fetch chunk metadata from PG (document_name, page_number)
  │
  ▼
Assemble context: "Source: {doc_name} p.{page}\n{chunk_text}\n---"
  │
  ▼
AIOrchestrator.complete(system_prompt + context + user_query)
  │
  ▼
Response + citations: [{document_name, page_number, chunk_text}]
  Property 9: every response includes citations
  │
  ▼
DocumentChatViewModel.uiState → citations displayed in RagComponents (core-ui)
```

---

## 7. Current PDF Flow

PDF is one of four supported document formats in the RAG pipeline. There is no separate "PDF screen" — PDFs flow entirely through the RAG pipeline described in §6.

```
PDF selected via FilePickerBottomSheet
  │
  ▼
Upload → MinIO  (same as RAG flow §6)
  │
  ▼
ingest_document_task [Celery worker]
  │
  ▼
RAGService._extract_text(bytes, "application/pdf")
  │
  ├── Primary: pdfplumber.open(bytes) → page.extract_text()
  │     → text per page, page number tracked
  │
  └── Fallback (if page text empty or extraction fails):
        pytesseract OCR on page image (ExtractionError stage="ocr")
  │
  ▼
Chunk → Embed → ChromaDB (per-user collection)
PG: DocumentChunk rows with page_number
  │
  ▼
Query returns citations with page_number for each chunk
  (Property 9: citation completeness)
```

---

## 8. Current Tool (MCP) Flow

```
AIOrchestrator.stream_chat() encounters LLM tool_call response
  │  (provider emits a tool_call event / function_call block)
  │
  ▼  [backend — services/mcp_broker.py]
MCPBroker.invoke(tool_name, params, user_id)
  │
  ├── AuditService.log_mcp_invoke(...)        ALWAYS written (Property 12)
  │
  ├── Tool not found? → AuditLog(status="error") → MCPToolResult(error)
  ├── Requires confirmation? → AuditLog(status="confirmation_required")
  │     → MCPToolResult(confirmation_required) → client must confirm
  │
  ▼
MCPToolConnector.invoke(params)
  │  dispatches to the matching connector:
  │
  ├── GitHubConnector    — create/list issues, PRs
  ├── GmailConnector     — read/send emails
  ├── SlackConnector     — post messages, list channels
  ├── JiraConnector      — create/update tickets
  ├── NotionConnector    — create/read pages
  ├── GDriveConnector    — list/read files
  ├── GCalConnector      — create/list events
  ├── FigmaConnector     — read designs
  └── DevOpsConnectors   — CI/CD triggers, deployment status
  │
  ▼
AuditLog(status="success" | "error")
  │
  ▼
MCPToolResult returned to AIOrchestrator
  │
  ▼
Orchestrator resumes LLM call with tool result injected into context
  │
  ▼
Stream continues → WS token events to Android client

Android side:
  StreamEvent.ToolCall → ChatDetailViewModel handles transparently
  (tokens continue after tool completes — tool execution is server-side)
```

**MCP tool discovery** (Android):
```
MCPTool domain model ← SemanticSearchApiService / dedicated MCP API
GET /mcp/tools  →  List<MCPToolSchema>
POST /mcp/invoke → MCPToolResult
```

---

## 9. Current On-Device Gemma Flow

```
User selects "Gemma (On-device)" in ProviderSelector or Settings
  │
  ▼
OnDeviceCapabilityChecker.checkCapability()
  ├── HardwareCapabilityDetector: RAM ≥ 4 GB? CPU cores ≥ 4? Storage ≥ 2 GB free?
  └── emits OnDeviceCapabilityState.CAPABLE | INSUFFICIENT_RAM | INSUFFICIENT_STORAGE | ...
  │
  ▼  [if CAPABLE]
OnDeviceModelManager.getActiveModel()
  ├── Looks for downloaded GGUF file in app's files directory
  └── returns File? (null if not yet downloaded)
  │
  ▼  [if model not downloaded]
feature-on-device-rag ManageModelsScreen
  │  user taps "Download" for a model from ModelManifest
  │
  ▼
OnDeviceModelManager.downloadModel(manifest)
  ├── HTTP GET from manifest.downloadUrl
  ├── SHA-256 integrity check on completion
  └── saves to app files dir
  │
  ▼  [model ready]
OnDeviceAiModule provides OnDeviceInferenceClient(ramMonitor, modelFile)
  │  Hilt injects this as AIStreamClient when provider == ON_DEVICE_PROVIDER_ID
  │
  ▼
ChatDetailViewModel.sendMessage(content)  [provider = "on_device"]
  │
  ▼
OnDeviceInferenceClient.connect(conversationId, jwt) → callbackFlow
  │  ZERO network calls (Req 31.2)
  │
  ├── RAM pre-flight: availableRam < 512 MB? → StreamEvent.Error("Insufficient resources")
  │
  ├── RamMonitor.observe() concurrent: if drops below threshold during inference
  │     → StreamEvent.Error → cancels inference
  │
  ▼
runLocalInference(payload)
  │  CURRENT IMPLEMENTATION (stub):
  │    builds simulatedResponse string
  │    splits into words, emits StreamEvent.Token per word with delay(50ms)
  │    emits StreamEvent.Done with rough token count estimate
  │
  │  PRODUCTION INTEGRATION POINT (TODO in code):
  │    val session = LlamaCppBridge.createSession(modelFile.absolutePath)
  │    session.tokenize(prompt).forEach { token → StreamEvent.Token(token) }
  │    session.getUsage() → StreamEvent.Done(TokenUsage)
  │
  ▼
ChatDetailViewModel processes tokens identically to cloud path
  └── isRunningOnDevice = true → AiModeIndicator shows "On-device" badge in UI

On-device RAG (feature-on-device-rag):
  OnDeviceIngestDocumentUseCase → OnDeviceDocumentRepositoryImpl
    → MiniLmEmbeddingModel.embed(chunk) → LocalVectorIndexImpl.add(embedding)
    → OnDeviceDocumentDao (Room) / OnDeviceChunkDao (Room)

  OnDeviceQueryUseCase
    → MiniLmEmbeddingModel.embed(query)
    → LocalVectorIndexImpl.query(embedding, topK)
    → OnDeviceRagViewModel assembles answer context
    → OnDeviceInferenceClient.connect() → local generation
```

---

## 10. Existing Reusable Services

### Android (reusable across features)

| Service / Component | Module | What it provides |
|---|---|---|
| `AIStreamClient` (interface) | core-ai | Contract for streaming AI; both cloud and on-device implementations |
| `AIStreamClientImpl` | core-ai | Cloud WebSocket streaming (OkHttp callbackFlow + backoff) |
| `NetworkModule` (OkHttp+Retrofit) | core-network | Fully configured HTTP client singleton |
| `ConnectivityObserver` | core-network | `isConnectedFlow: Flow<Boolean>` — offline detection |
| `SecureStorage` | core-security | `get(key)` / `put(key, value)` on EncryptedSharedPreferences |
| `BiometricAuthManager` | core-security | Biometric prompt abstraction |
| `AppDatabase` + all DAOs | core-database | Room database with all entity DAOs |
| `DispatcherProvider` | core-common | Testable coroutine dispatcher injection |
| `ApiResult<T>` | core-common | `Success / Error / Loading / NetworkUnavailable` sealed class |
| `DomainError` | core-common | Typed error hierarchy (`StreamingInterrupted`, `NetworkUnavailable`, etc.) |
| `ObservabilityEventBus` | core-common | In-process event bus for structured observability events |
| `PiiFilter` | core-common | PII redaction for log safety |
| `SessionManager` | core-common | Current user session state |
| `MiniLmEmbeddingModel` | core-ai | On-device sentence embeddings |
| `LocalVectorIndexImpl` | core-ai | In-memory cosine-similarity vector search |
| `QueryRouterImpl` | core-ai | Cloud vs on-device routing decision |
| All shared Compose widgets | core-ui | `ChatBubble`, `StreamingMessage`, `MessageInputBar`, `TypingIndicator`, `MarkdownText`, `CodeBlock`, `ErrorBanner`, `OfflineBanner`, `ShimmerSkeleton`, `ConnectivityStatusBar`, `AdaptiveScaffold`, `TwoPaneLayout` |
| `AppTheme` / `AppIcons` | core-ui | Design tokens, icons, theme |
| `BackendEndpointSelector` | core-network | Primary + failover endpoint selection |
| `FederationHealthCheckWorker` | core-network | WorkManager periodic health probe |

### Backend (reusable across routes)

| Service | What it provides |
|---|---|
| `AIOrchestrator` | Central LLM dispatch with memory, safety, streaming |
| `MemoryService` | User memory store/retrieve (ChromaDB + PG) |
| `RAGService` | Document ingestion + semantic retrieval |
| `SafetyService` | Injection detection + response filtering |
| `MCPBroker` | Tool discovery + dispatch + audit |
| `StorageService` | MinIO/GCS abstraction |
| `CostService` | Token cost aggregation |
| `PromptService` | Versioned prompt template management |
| `AuditService` | Security audit log writer |
| `get_current_user` dependency | JWT → `User` FastAPI dependency |
| `require_roles` / `require_admin` | RBAC FastAPI dependencies |
| `_ProviderRateLimiter` | Redis sliding-window rate limiter (per provider) |

---

## 11. Existing API Contracts

### Authentication

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/auth/register` | None | `{email, password, display_name}` | `{access_token, refresh_token, user}` |
| POST | `/auth/login` | None | `{email, password}` | `LoginResponse` |
| POST | `/auth/refresh` | Bearer | `{refresh_token}` | `RefreshResponse` |
| POST | `/auth/logout` | Bearer | — | `LogoutResponse` |
| POST | `/auth/google` | None | `{id_token}` | `GoogleAuthResponse` |

### Chat / Conversations

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/chat/message` | Bearer | `{conversation_id, user_message, provider}` | `{reply, usage}` |
| POST | `/api/v1/chat` | Bearer | same | same |
| GET | `/conversations` | Bearer | `?page&page_size&is_pinned` | Paginated list |
| POST | `/conversations` | Bearer | `{title, provider}` | `Conversation` |
| GET | `/conversations/{id}` | Bearer | — | `Conversation` |
| PATCH | `/conversations/{id}` | Bearer | `{title?, is_pinned?}` | `Conversation` |
| DELETE | `/conversations/{id}` | Bearer | — | 204 |

### RAG Documents

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/documents` | Bearer | multipart: `file` + `title` | `{document_id, job_id}` |
| GET | `/documents` | Bearer | `?page&page_size` | Paginated list |
| POST | `/documents/query` | Bearer | `{query, top_k}` | `{answer, citations}` |
| POST | `/documents/{id}/query` | Bearer | `{query, top_k}` | `{answer, citations}` |
| DELETE | `/documents/{id}` | Bearer | — | 204 |
| GET | `/jobs/{job_id}` | Bearer | — | `{status: PENDING|PROCESSING|COMPLETED|FAILED}` |

### Code Analysis

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/code/analyze` | Bearer | `{code, language, action}` | `CodeAnalysis` |

### Memory

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/memories` | Bearer | — | `List<Memory>` |
| DELETE | `/memories/{id}` | Bearer | — | 204 |

### MCP Tools

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| GET | `/mcp/tools` | Bearer | — | `List<MCPToolSchema>` |
| POST | `/mcp/invoke` | Bearer | `{tool_name, params}` | `MCPToolResult` |

### Observability

| Method | Path | Auth | Request | Response |
|---|---|---|---|---|
| POST | `/api/v1/observability/events` | Bearer | `List<ObservabilityEvent>` | 202 |

### Health

| Method | Path | Auth | Response |
|---|---|---|---|
| GET | `/health` | None | `{status: "ok"}` 200 |
| GET | `/ready` | None | `{status, dependencies: {database, redis}}` 200 / 503 |

---

## 12. Existing WebSocket Contracts

### Endpoint

```
WS  /ws/chat/{conversation_id}?token=<jwt>
```

**Authentication:** `token` query parameter (JWT). Invalid/missing → close code 4001 + error JSON.

### Client → Server Messages

```jsonc
// Send a user message
{"user_message": "Hello!", "provider": "gemini"}

// Respond to server heartbeat
{"type": "pong"}
```

`provider` is optional; defaults to `"openai"`. Valid values: `openai | gemini | claude | ollama | llama | mistral`.

### Server → Client Messages

```jsonc
// Streaming token
{"type": "token", "data": "<text chunk>"}

// Stream complete
{"type": "done", "usage": {"inputTokens": 123, "outputTokens": 456}}

// Error
{"type": "error", "message": "<description>"}

// Tool call (transparent to client — stream continues)
{"type": "tool_call", "toolName": "github", "toolInput": {...}}

// Heartbeat (every 30 s — client must pong)
{"type": "ping"}
```

### Mid-Stream Disconnect Behaviour

When the client disconnects mid-stream, the backend `_BufferingWebSocketProxy` buffers remaining `token` events in Redis (`RPUSH ws:buffer:{user_id}:{conversation_id}`). On reconnect, `flush_token_buffer()` replays them before any new messages are processed.

### Android Event Mapping

```
WS frame "token"      → StreamEvent.Token(text)
WS frame "done"       → StreamEvent.Done(TokenUsage)
WS frame "error"      → StreamEvent.Error(message)
WS frame "tool_call"  → StreamEvent.ToolCall(toolName, toolInput)
```

---

## 13. Existing Database Models

### PostgreSQL (Backend — SQLAlchemy ORM)

| Table | Key Columns | Notes |
|---|---|---|
| `users` | id (uuid pk), email (unique), password_hash, google_id, display_name, role (user/premium/admin), is_active, push_token, fcm_token, privacy_mode | bcrypt pw hash; Google OAuth linked via google_id |
| `conversations` | id, user_id (fk), title, provider, is_pinned, is_deleted | Soft delete |
| `messages` | id, conversation_id (fk), role (user/assistant/system/tool), content, input_tokens, output_tokens, provider, model_name | Token usage per message |
| `documents` | id, user_id (fk), title, file_path (MinIO), mime_type, status, chunk_count | Ingestion status |
| `document_chunks` | id, document_id (fk), user_id (fk), chunk_text, chunk_index, page_number, embedding (pgvector Vector) | pgvector column for similarity search |
| `memories` | id, user_id (fk), content, memory_type, embedding (Vector) | ChromaDB-mirrored; PG for metadata |
| `token_usage` | id, user_id (fk), feature (UsageFeature enum), input_tokens, output_tokens, cost_usd, model_name | Per-request cost tracking |
| `personas` | id, user_id (fk), name, system_prompt, is_active | Custom AI persona |
| `prompt_templates` | id, author_id (fk), name, content, version, is_active | Versioned; rollback creates new version |
| `refresh_tokens` | id, user_id (fk), token_hash, family_id, is_revoked, expires_at | Rotation + replay detection |
| `api_keys` | id, user_id (fk), key_hash, name, is_active | Service API keys |
| `audit_logs` | id, user_id (fk, nullable), action, resource_type, resource_id, result_status | Immutable audit trail |
| `jobs` | id, user_id (fk), job_type, status, payload, error_message | Async task tracking |
| `notes` | id, user_id (fk), title, content, tags | AI-rewriteable notes |
| `todo_items` | id, user_id (fk), title, description, due_date, is_completed, priority | |
| `calendar_events` | id, user_id (fk), title, start_time, end_time, location | |
| `reminders` | id, user_id (fk), title, remind_at, is_dismissed | |
| `habit_definitions` | id, user_id (fk), name, target_frequency, unit | |
| `habit_entries` | id, habit_id (fk), user_id (fk), logged_at, value | |
| `observability_events` | id, user_id (fk), event_type, payload, created_at | Uploaded from Android |
| `error_logs` | id, user_id (fk), error_type, stack_trace, context | AI error analysis source |
| `incidents` | id, user_id (fk), title, severity, status, description | DevOps incident tracking |
| `remediation_actions` | id, incident_id (fk), action_type, description, status | |
| `spending_alerts` | id, user_id (fk), threshold_usd, is_active | |

### Android Room Database (v3)

| Entity | Key Columns |
|---|---|
| `UserEntity` | id, email, displayName, role |
| `ConversationEntity` | id, title, provider, isPinned, isDeleted, updatedAt |
| `ConversationFtsEntity` | (FTS4 virtual — rowid, title, content) |
| `MessageEntity` | id, conversationId, role, content, syncStatus, createdAt |
| `MessageFtsEntity` | (FTS4 virtual — rowid, content) |
| `DocumentEntity` | id, title, mimeType, status, chunkCount |
| `MemoryEntity` | id, content, memoryType, createdAt |
| `NoteEntity` | id, title, content, tags |
| `TodoItemEntity` | id, title, dueDate, isCompleted, priority |
| `CalendarEventEntity` | id, title, startTime, endTime, location |
| `ReminderEntity` | id, title, remindAt, isDismissed |
| `HabitDefinitionEntity` | id, name, targetFrequency, unit |
| `HabitEntryEntity` | id, habitId, loggedAt, value |
| `OnDeviceDocumentEntity` | id, title, filePath, status (v3) |
| `OnDeviceChunkEntity` | id, documentId, chunkText, chunkIndex (v3) |
| `QueryRoutingLogEntity` | id, query, routedTo (cloud/ondevice), latencyMs (v3) |

---

## 14. Existing Infrastructure

### Docker Compose (Local / Windows Dev)

| Service | Image | Port | Notes |
|---|---|---|---|
| `postgres` | pgvector/pgvector:pg16 | 5432 | pgvector extension pre-installed |
| `redis` | redis:7-alpine | 6379 | Broker + cache + token buffer |
| `minio` | minio RELEASE.2024-09-13 | 9000 / 9001 | Object storage (dev/stage) |
| `chromadb` | chromadb/chroma:1.5.9 | 127.0.0.1:8001 | Bound to localhost only (CVE-2026-45829) |
| `backend` | ./backend Dockerfile | 8000 | Hot-reload via volume mount |
| `celery_worker` | ./backend Dockerfile | — | All queues: celery/ingestion/notifications/gdpr/alerts |

`docker-compose.local.yml` and `docker-compose.prod.yml` are variant overrides.

### GCP Production (Terraform)

| Resource | Details |
|---|---|
| Cloud Run | Backend service, auto-scaling, min-instances configurable |
| Artifact Registry | Docker image storage |
| GCS | File/document object storage (replaces MinIO) |
| Secret Manager | All secrets (API keys, DB passwords, JWT secret) |
| Cloud Build | CI/CD pipeline (`cloudbuild.yaml`) |
| IAM | Service account with Workload Identity for Cloud Run → GCS/SM |

**Terraform modules:** `cloudrun`, `storage`, `iam`, `networking`, `monitoring`  
**Environments:** `terraform/environments/` (dev / stage / prod)

### CI/CD (GitHub Actions)

| Workflow | Trigger | What it does |
|---|---|---|
| `android-ci.yml` | Push to main/feature | Assemble, lint (detekt+ktlint), unit tests |
| `backend-ci.yml` | Push to main/feature | pytest unit+integration, ruff lint, coverage |
| `cloud-run-deploy.yml` | Release tag | Build Docker → push AR → deploy Cloud Run |
| `security-scan.yml` | Schedule / push | gitleaks, trivy, OWASP dependency-check |
| `infrastructure-validation.yml` | PR | `terraform validate + plan` |
| `release.yml` | Tag | Android release APK/AAB + backend deploy |

---

## 15. Existing Problems / Duplication

### Critical Issues

1. **`pgvector` missing from `venv311`** — `ModuleNotFoundError: No module named 'pgvector'` blocks collection of all 31 backend unit test files. The package is declared in `requirements.txt` but was not installed in the `venv311` virtual environment on this machine. **No backend tests can be run until this is resolved.**

2. **JWT placeholder in `ChatDetailViewModel`** — Line `val jwt = "placeholder_jwt"` is hardcoded. The real token is not yet read from `SecureStorage`. Every WebSocket connection uses an invalid JWT in the current Android build, meaning the backend will reject all WebSocket auth attempts. This is a **known integration gap** noted in the code comment.

3. **On-device inference is a stub** — `OnDeviceInferenceClient.runLocalInference()` simulates tokens with `delay(50ms)` and a canned response string. No JNI bridge to llama.cpp or MediaPipe exists yet. The code comment has a `TODO` with the exact integration point.

### Architectural Duplication

4. **Two parallel LLM provider layers on the backend:**
   - `services/llm_clients.py` — older adapters (`BaseLLMClient`, `GeminiClient`, `OpenAIClient`, etc.) used directly by `AIOrchestrator`.
   - `llm/providers/` — newer `LLMProvider` base class with `GeminiProvider` and `LocalGemmaProvider`.
   These two abstraction layers serve the same purpose with different interfaces and are not yet unified.

5. **`core-on-device-ai` duplicates `feature-on-device-ai`** — Both modules contain the same source files: `DeviceCapabilityDetector.kt`, `HardwareCapabilityDetector.kt`, `ModelManifest.kt`, `OnDeviceAiInitializer.kt`, `OnDeviceCapabilityChecker.kt`, `OnDeviceCapabilityState.kt`, `OnDeviceEngine.kt`, `OnDeviceInferenceClient.kt`, `OnDeviceModelManager.kt`, `RamMonitor.kt`, `StubOnDeviceEngine.kt`, `OnDeviceAiModule.kt`. This is an apparent copy-paste duplication — the modules likely intend different scopes (library vs. feature UI) but currently ship identical code.

6. **Flutter and Android duplicate the WebSocket client** — `WebSocketService.dart` in Flutter replicates all of `AIStreamClientImpl.kt`: connection logic, heartbeat, exponential backoff, event parsing. Any protocol change must be applied in both places.

7. **`features-on-device-rag/ManageModelsViewModel` and `ManageModelsScreen`** exist in `feature-on-device-rag` while model management logic also exists in `feature-on-device-ai` and `core-on-device-ai`. Three modules touch the same concern.

8. **`app/HomeDashboard.kt` and `app/HomeDashboardViewModel.kt`** exist alongside `feature-dashboard/DashboardScreen.kt` and `feature-dashboard/DashboardViewModel.kt` — the app module appears to have an older dashboard implementation that was partially superseded by the `feature-dashboard` module.

### Minor Issues

9. **Double-commented file headers** — Many Kotlin files have the module/purpose comment block duplicated verbatim (appears twice in the same file). This is cosmetic but indicates a code generation issue.

10. **`android-lint-check.ps1` and `ktlint-detekt-check.ps1`** both run lint — there is minor redundancy between the PowerShell scripts and the Gradle lint tasks already configured.

11. **`docker-compose.yml` includes a `chromadb` service** but the backend comment says "ChromaDB runs embedded in-process (PersistentClient) — no separate service". The Docker Compose `chromadb` service is inconsistent with the `CHROMA_PERSIST_DIR: /tmp/chroma` backend env var.

---

## 16. Recommended Integration Points

These are the natural seams where the Agent Architecture should plug in. **Nothing is created here — this is guidance only.**

### Android

| Integration Point | Where | What to add |
|---|---|---|
| JWT auth for WebSocket | `ChatDetailViewModel.startStreaming()` | Replace `"placeholder_jwt"` with `SecureStorage.get(KEY_ACCESS_TOKEN)` injected via `AuthRepository` |
| Agent task routing | `ChatDetailViewModel.sendMessage()` | Insert a `QueryRouterImpl`-style decision before streaming: cloud vs. on-device vs. agent-task |
| Agent response protocol | `AIStreamClientImpl.parseEvent()` | Add `"agent_step"` / `"agent_thinking"` / `"agent_result"` event types alongside existing `token/done/error/tool_call` |
| Tool execution feedback | `ChatDetailScreen` | Add a tool-call progress composable (currently `StreamEvent.ToolCall` is silently ignored in UI) |
| On-device engine | `OnDeviceInferenceClient.runLocalInference()` | Drop-in JNI call replacing the simulation body |

### Backend

| Integration Point | Where | What to add |
|---|---|---|
| Agent orchestration layer | New `services/agent_service.py` | Sits between the WebSocket router and `AIOrchestrator`; manages multi-step reasoning, tool loops |
| LLM provider unification | `llm/providers/` + `services/llm_clients.py` | Migrate `AIOrchestrator` to use the new `LLMProvider` base class; retire `BaseLLMClient` |
| Agent WebSocket events | `api/websocket/router.py` + `AIOrchestrator` | Emit `agent_step` / `agent_thinking` events over existing WS connection (backward-compatible addition) |
| Tool loop | `MCPBroker.invoke()` | Add `max_iterations` guard, intermediate result streaming |
| Memory for agent state | `MemoryService` | Add `AGENT_STATE` memory type alongside existing types for cross-session agent context |
| Prompt templates | `PromptService` | Add agent-specific system prompt templates with versioning |

### Infrastructure

| Integration Point | Where | What to add |
|---|---|---|
| Agent task queue | Celery `workers/` | Add `agent_worker.py` on new `agents` queue for long-running autonomous tasks |
| Agent state store | Redis | Keyed agent execution state (`agent:{user_id}:{session_id}`) |
| Observability | `ObservabilityEventBus` (Android) + `/api/v1/observability/events` | Add `AGENT_STEP_START`, `AGENT_STEP_END`, `TOOL_CALL`, `TOOL_RESULT` event types |

---

## 17. Build & Test Status

### Android (Gradle 8.11.1 / Kotlin 2.0.21)

| Module | Test run | Result |
|---|---|---|
| `core-common` | `./gradlew :core-common:test` | **PASSED** — 21 test cases, 0 failures, 0 errors |
| `core-network` | `./gradlew :core-network:test` | **TIMED OUT** — Gradle test execution exceeded 5 minutes (likely downloading Android SDK components on first run). Tests exist (29 Kotlin test files). |
| All others | Not executed in this audit | Not attempted — would require full Android SDK sync |

**Android test infrastructure is functional.** The `core-common` run confirmed Kotest + JUnit4 + MockK wiring is working. Full test suite requires Android SDK platform components to be fully synced.

### Backend (Python 3.12.10)

| Test suite | Result |
|---|---|
| Unit tests (31 files) | **ALL FAILED TO COLLECT** |
| Integration tests | Not attempted |
| Property tests | Not attempted |
| Security tests | Not attempted |

**Root cause:** `ModuleNotFoundError: No module named 'pgvector'` — the `pgvector` Python package is in `requirements.txt` but is not installed in the `venv311` virtual environment on this machine.

**Fix required before any backend tests can run:**
```bash
cd backend
venv311\Scripts\pip.exe install pgvector
```
Or reinstall all requirements:
```bash
venv311\Scripts\pip.exe install -r requirements.txt
```

**Test files confirmed to exist:** 31 unit tests, 19 integration tests, 12 property tests (total ~62 test files covering auth, orchestrator, RAG, WebSocket, MCP, safety, memory, cost, GDPR, and more).

### Flutter

Not attempted — requires Flutter SDK + `flutter pub get`.

---

*End of Phase 0 Architecture Audit. No application code was modified.*
