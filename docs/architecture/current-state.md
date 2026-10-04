# Current State Architecture — Android AI Assistant (Enterprise Edition)

> **Document Type:** Pre-integration Architecture Audit  
> **Date:** 2026-10-01  
> **Status:** Read-only survey — no application code was modified  
> **Scope:** Full repository prior to introducing MCP, Agent, and Document/RAG modules as first-class features  
> **Source of truth:** Live codebase at `Android-AI-Assistant-Test/`

---

## Table of Contents

1. [Repository Structure](#1-repository-structure)
2. [Android Architecture](#2-android-architecture)
   - 2.1 [Build & Language](#21-build--language)
   - 2.2 [Architectural Pattern — Clean Architecture + MVVM](#22-architectural-pattern--clean-architecture--mvvm)
   - 2.3 [Module Graph](#23-module-graph)
   - 2.4 [Dependency Injection (Hilt)](#24-dependency-injection-hilt)
   - 2.5 [UI Layer — Jetpack Compose](#25-ui-layer--jetpack-compose)
   - 2.6 [Network Layer](#26-network-layer)
   - 2.7 [WebSocket / Streaming](#27-websocket--streaming)
   - 2.8 [Local Storage (Room + DataStore)](#28-local-storage-room--datastore)
   - 2.9 [On-Device AI](#29-on-device-ai)
   - 2.10 [Existing ViewModels](#210-existing-viewmodels)
   - 2.11 [Existing Domain Repositories & Use Cases](#211-existing-domain-repositories--use-cases)
3. [Backend Architecture](#3-backend-architecture)
   - 3.1 [Stack](#31-stack)
   - 3.2 [API Layer](#32-api-layer)
   - 3.3 [Middleware Stack](#33-middleware-stack)
   - 3.4 [LLM Integration](#34-llm-integration)
   - 3.5 [Agent System](#35-agent-system)
   - 3.6 [RAG Pipeline](#36-rag-pipeline)
   - 3.7 [MCP Broker & Connectors](#37-mcp-broker--connectors)
   - 3.8 [Memory Service](#38-memory-service)
   - 3.9 [Authentication & Security](#39-authentication--security)
   - 3.10 [Celery Workers](#310-celery-workers)
   - 3.11 [Service Layer Summary](#311-service-layer-summary)
4. [Data Storage](#4-data-storage)
   - 4.1 [PostgreSQL Schema (Backend)](#41-postgresql-schema-backend)
   - 4.2 [ChromaDB (Vector Store)](#42-chromadb-vector-store)
   - 4.3 [Redis](#43-redis)
   - 4.4 [Object Storage — MinIO / GCS](#44-object-storage--minio--gcs)
   - 4.5 [Android Room Database](#45-android-room-database)
5. [Gemini Integration](#5-gemini-integration)
6. [Chat & WebSocket Contract](#6-chat--websocket-contract)
7. [Document Processing & RAG](#7-document-processing--rag)
8. [Infrastructure & DevOps](#8-infrastructure--devops)
9. [Existing Tests](#9-existing-tests)
10. [Existing Reusable Components](#10-existing-reusable-components)
11. [Known Issues & Duplication Risks](#11-known-issues--duplication-risks)

---

## 1. Repository Structure

```
Android-AI-Assistant-Test/
│
├── app/                        # Android application shell (1 module)
│
├── core-common/                # ApiResult, DispatcherProvider, DomainError, SessionManager, PiiFilter
├── core-ui/                    # Material3 theme, adaptive layouts, all shared Compose components
├── core-network/               # OkHttp, Retrofit, interceptors, federation, observability
├── core-database/              # Room v3, AppDatabase, all DAOs and entities
├── core-ai/                    # AIStreamClient (WebSocket), on-device engine interfaces, on-device RAG
├── core-security/              # BiometricAuthManager, SecureStorage, RootDetectionUtil
│
├── domain/                     # Domain models, repository interfaces, all use cases (pure Kotlin)
├── data/                       # Repository implementations, remote/local data sources, mappers
│
├── feature-auth/               # Login, register, onboarding, biometric, splash
├── feature-chat/               # Conversation list, chat detail, comparison mode
├── feature-code/               # Code editor, AI code analysis
├── feature-voice/              # Voice assistant (STT → AI → TTS)
├── feature-rag/                # Document list, document chat, file picker
├── feature-history/            # Conversation history search
├── feature-settings/           # App settings, cost dashboard
├── feature-profile/            # User profile, memory management
├── feature-dashboard/          # DevOps AI dashboard, incident list
├── feature-camera/             # CameraX capture, OCR, image analysis
├── feature-on-device-ai/       # On-device AI management, model download UI
├── feature-on-device-rag/      # On-device RAG chat, benchmark, model management
├── feature-persona/            # Persona editor and selector
├── feature-search/             # Semantic search
├── feature-notes/              # Notes with AI rewrite/summarise
├── feature-meeting/            # Meeting recording and summarisation
├── feature-resume/             # Resume and cover-letter generation
├── feature-email/              # AI email generation
├── feature-translator/         # Text translation
├── feature-productivity/       # Todos, calendar events, reminders, habits
│
├── flutter/                    # Flutter companion app (Riverpod, Dio, WebSocket) — secondary client
│
├── backend/                    # Python FastAPI backend
│   ├── app/
│   │   ├── agents/             # Agent base, orchestrator, planner, router, registry + 9 concrete agents
│   │   ├── api/                # 27 router modules (auth, chat, websocket, rag, mcp, memory, …)
│   │   ├── config/             # pydantic-settings Settings class
│   │   ├── database/           # Redis client module
│   │   ├── llm/                # LLMProvider ABC + GeminiProvider + LocalGemmaProvider
│   │   ├── middleware/          # logging, rate_limit, data_residency, request_size
│   │   ├── models/             # SQLAlchemy ORM models (25 tables)
│   │   ├── observability/      # Prometheus, structured logging, OpenTelemetry
│   │   ├── prompts/            # Prompt template files
│   │   ├── repositories/       # Data access layer (14 repositories)
│   │   ├── schemas/            # Pydantic request/response schemas
│   │   ├── security/           # JWT, bcrypt, RBAC, audit, encryption, lockout
│   │   ├── services/           # Business logic (17 services + mcp_connectors/)
│   │   └── workers/            # Celery workers (rag, notification, gdpr, anomaly, alert)
│   ├── alembic/                # Database migrations (16 versions)
│   └── tests/                  # Unit (62+ files), integration, property, security tests
│
├── infrastructure/             # Grafana, Loki, Nginx, Prometheus configs
├── terraform/                  # GCP Cloud Run + Artifact Registry + GCS IaC
├── chromadb-server/            # Standalone ChromaDB server wrapper (for Linux prod)
│
├── docker-compose.yml          # Local dev: postgres+pgvector, redis, minio, chromadb, backend, celery
├── docker-compose.local.yml    # Local override variant
└── docker-compose.prod.yml     # Production override variant
```

**Total module count:** 1 app + 6 core + 2 arch (domain/data) + 21 feature + flutter + backend = **32+ modules**

---

## 2. Android Architecture

### 2.1 Build & Language

| Dimension | Value |
|---|---|
| Language | Kotlin 2.0.21 (100% — no Java) |
| Build system | Gradle 8.11.1 + AGP 8.8.0 |
| Compile SDK | 35 |
| Min SDK | 26 |
| KSP | 2.0.21-1.0.25 (Hilt, Room code generation) |
| Compose BOM | 2024.09.03 |
| Lint | Detekt 1.23.7 + ktlint 1.3.1 |
| Product flavors | `local` / `stage` / `production` (different API base URLs) |

### 2.2 Architectural Pattern — Clean Architecture + MVVM

Every feature module follows the same vertical slice:

```
Feature Module
├── Screen (Composable)           ← observes ViewModel StateFlow, emits UI events
├── ViewModel (@HiltViewModel)    ← exposes StateFlow<UiState>, dispatches to use cases
├── UiState (sealed / data class) ← immutable UI snapshot, no Android dependencies
└── DI Module (@Module @InstallIn(SingletonComponent))

Domain Module (pure Kotlin, zero Android deps)
├── Model (data class)
├── Repository (interface)
└── UseCase (class — single-responsibility)

Data Module
├── RepositoryImpl                ← implements domain interfaces
├── RemoteDataSource              ← Retrofit service calls
├── LocalDataSource               ← Room DAO calls
└── Mapper                        ← remote ↔ domain ↔ entity conversions
```

Key constraints enforced:
- No `LiveData` — all reactive streams are `StateFlow` / `Flow`
- No XML layouts — 100% Jetpack Compose
- Domain layer has zero knowledge of Android framework
- Use cases are plain Kotlin classes with single-responsibility

### 2.3 Module Graph

```
:app
  ├── :core-common
  ├── :core-ui
  ├── :core-network
  ├── :core-database
  ├── :core-ai
  ├── :core-security
  ├── :domain
  ├── :data
  └── :feature-* (21 feature modules)
      └── all depend on :domain (use cases, models, repository interfaces)
          :data implements :domain interfaces, depends on :core-network, :core-database, :core-ai
```

### 2.4 Dependency Injection (Hilt)

- **`SingletonComponent`** bindings: `OkHttpClient`, `Retrofit`, `AppDatabase`, `AIStreamClient`, `ConnectivityObserver`, `SecureStorage`, all repository implementations
- Each feature module ships its own `@Module @InstallIn(SingletonComponent::class)`
- Named qualifiers: `@Named("wsBaseUrl")`, `@Named("isDebugBuild")`
- Data layer DI modules (in `:data`): `AgentDataModule`, `AuthDataModule`, `ConversationDataModule`, `DocumentDataModule`, `OnDeviceRagModule`, `SemanticSearchDataModule`, 18+ total

### 2.5 UI Layer — Jetpack Compose

- **Material3** everywhere with custom `AppTheme` (light/dark, `ThemeMode` via DataStore)
- **Adaptive navigation shell**: `NavigationBar` (compact) → `NavigationRail` (medium) → `PermanentNavigationDrawer` (expanded/tablet)
- **Shared components in `core-ui`**: `ChatBubble`, `StreamingMessage`, `MarkdownText`, `CodeBlock`, `MessageInputBar`, `TypingIndicator`, `LoadingIndicator`, `ErrorBanner`, `OfflineBanner`, `ShimmerSkeleton`, `ConnectivityStatusBar`, `AiModeIndicator`, `RagComponents`, `AdaptiveScaffold`, `TwoPaneLayout`

### 2.6 Network Layer

```
OkHttpClient
  ├── AuthInterceptor                    → attaches "Bearer <jwt>" from SecureStorage
  ├── CertificatePinningInterceptor      → SHA-256 SPKI hash enforcement (bypassed in debug)
  ├── RefreshTokenInterceptor            → authenticator() that retries on HTTP 401 via /auth/refresh
  ├── NetworkObservabilityInterceptor    → latency + status code structured events
  └── HttpLoggingInterceptor             → BODY (debug) / NONE (release)

Retrofit
  └── baseUrl from BuildConfig.API_BASE_URL (per flavor)
      └── KotlinxSerializationConverterFactory

Federation (core-network/federation/)
  ├── BackendEndpointSelector            → primary + failover endpoint selection
  ├── FederationHealthCheckWorker        → WorkManager periodic health probe
  ├── FailoverInterceptor                → redirects on 503/504
  └── FailoverBannerStateProvider        → exposes failover banner state to UI
```

Timeouts: connect 30 s, read 90 s, write 60 s.

### 2.7 WebSocket / Streaming

**Implementation:** `AIStreamClientImpl` in `core-ai`

- Connects to `wss://<host>/ws/chat/{conversationId}?token={jwt}`
- Implemented as cold `callbackFlow` over `OkHttpClient.newWebSocket`
- Reconnect strategy: exponential backoff 1 s → 2 s → 4 s → 8 s → 16 s (max 30 s), up to 5 attempts
- Parses structured JSON frames: `token` / `done` / `error` / `tool_call`
- **Known gap:** JWT is hardcoded as `"placeholder_jwt"` in `ChatDetailViewModel` — real token not yet wired from `SecureStorage`

### 2.8 Local Storage (Room + DataStore)

| Store | Technology | Contents |
|---|---|---|
| Room v3 | SQLite | Conversations, Messages (FTS4), Documents, Memories, Notes, Todos, CalendarEvents, Reminders, HabitDefinitions, HabitEntries, OnDeviceDocuments, OnDeviceChunks, QueryRoutingLog |
| DataStore (Preferences) | Jetpack DataStore | Theme mode, LLM provider preference, privacy mode |
| SecureStorage | `EncryptedSharedPreferences` | JWT access token, refresh token |
| File system | File I/O | Downloaded GGUF model files |

Room version 3 with migrations 1→2 and 2→3. FTS4 virtual tables on `ConversationFtsEntity` and `MessageFtsEntity`.

### 2.9 On-Device AI

| Component | Module | Status |
|---|---|---|
| `OnDeviceInferenceClient` | `core-ai` / `feature-on-device-ai` | **Stub** — simulates tokens with `delay(50ms)`. JNI bridge to llama.cpp has a `TODO` comment but is not implemented. |
| `MediaPipeInferenceEngine` | `core-ai` | Alternative engine interface |
| `MiniLmEmbeddingModel` | `core-ai` | **Live** — on-device sentence embedding for local RAG |
| `LocalVectorIndexImpl` | `core-ai` | **Live** — in-memory cosine-similarity vector index |
| `QueryRouterImpl` | `core-ai` | **Live** — routes to cloud or on-device based on `OnDeviceCapabilityState` |
| `RamMonitor` | `core-ai` / `feature-on-device-ai` | **Live** — emits event when free RAM < 512 MB |
| `OnDeviceModelManager` | `core-ai` / `feature-on-device-ai` | **Live** — download + SHA-256 verify + delete GGUF files |
| `HardwareCapabilityDetector` | duplicated | **Duplication risk** — see §11 |

### 2.10 Existing ViewModels

| Module | ViewModel | Key Responsibilities |
|---|---|---|
| feature-chat | `ChatViewModel` | Conversation list, Paging3, FTS search, pin/rename/delete |
| feature-chat | `ChatDetailViewModel` | WebSocket streaming, token accumulation, retry, export, suggestions |
| feature-chat | `ComparisonModeViewModel` | Side-by-side provider comparison |
| feature-code | `CodeViewModel` | Code editor state, AI analysis dispatch |
| feature-voice | `VoiceViewModel` | STT → AI → TTS state machine |
| feature-rag | `RAGViewModel` | Document list (Paging3), upload lifecycle, ingestion polling |
| feature-rag | `DocumentChatViewModel` | RAG Q&A chat with citations |
| feature-history | `HistoryViewModel` | Conversation history list |
| feature-settings | `SettingsViewModel` | Preferences, theme, provider, privacy |
| feature-settings | `CostDashboardViewModel` | Token usage and cost display |
| feature-profile | `ProfileViewModel` | User profile, memory management |
| feature-auth | `AuthViewModel` | Login/register/Google OAuth |
| feature-auth | `BiometricHelperViewModel` | Biometric unlock |
| feature-dashboard | `DashboardViewModel` | DevOps AI analysis, incident list |
| feature-camera | `CameraViewModel` | CameraX capture, OCR, image analysis |
| feature-on-device-rag | `OnDeviceRagViewModel` | On-device RAG chat |
| feature-on-device-rag | `ManageModelsViewModel` | Model download/delete |
| feature-on-device-rag | `BenchmarkViewModel` | On-device benchmark runs |
| feature-persona | `PersonaViewModel` | Persona CRUD, active persona selection |
| feature-search | `SemanticSearchViewModel` | Backend semantic search |

### 2.11 Existing Domain Repositories & Use Cases

**Repository interfaces** (in `:domain`, 25 total):
`AuthRepository`, `CodeRepository`, `ContextSuggestionRepository`, `ConversationRepository`, `CostDashboardRepository`, `DevOpsRepository`, `DocumentRepository`, `FederationRepository`, `IncidentRepository`, `MeetingRepository`, `MemoryRepository`, `MessageRepository`, `ModelFileRepository`, `NoteRepository`, `OnDeviceDocumentRepository`, `PersonaPreferencesRepository`, `PersonaRepository`, `ProductivityRepository`, `QueryMetricsRepository`, `QueryRoutingLogRepository`, `ResumeRepository`, `SemanticSearchRepository`, `TranslationRepository`, `UserRepository`

**Agent domain interfaces** (in `:domain/agent/`): `Agent.kt`, `AgentOrchestrator.kt`, `AgentRegistry.kt`, `AgentRouter.kt`, `AgentPlanner.kt`, `AgentRequest/Result/Event/Execution/Decision.kt`, `AgentGatewayRepository.kt`, `LlmClient.kt`, `ModelRouter.kt`, `Tool.kt`, `ToolRegistry.kt`

**Use case groups** (`/domain/usecase/`):
- auth: `LoginUseCase`, `RegisterUseCase`, `RefreshTokenUseCase`, `LoginWithGoogleUseCase`
- conversation: `SendMessageUseCase`, `CreateConversationUseCase`, `DeleteConversationUseCase`, `ExportConversationUseCase`, `GetConversationsUseCase`, `RegenerateMessageUseCase`, `SearchConversationsUseCase`, `SyncOfflineQueueUseCase`
- document: `UploadDocumentUseCase`, `QueryDocumentUseCase`, `DeleteDocumentUseCase`
- memory: `GetMemoriesUseCase`, `DeleteMemoryUseCase`
- ondevicerag: `OnDeviceIngestDocumentUseCase`, `OnDeviceQueryUseCase`, `RouteQueryUseCase`, `ManageOnDeviceModelsUseCase`, `BenchmarkOnDeviceUseCase`, `GetOnDeviceDocumentsUseCase`, `DeleteOnDeviceDocumentUseCase`
- persona: `CreatePersonaUseCase`, `DeletePersonaUseCase`, `SelectPersonaUseCase`
- productivity: 13 use cases (todos, calendar, reminders, habits)
- resume/email/code/search/suggestions/translator/meeting/devops: domain-specific use cases

---

## 3. Backend Architecture

### 3.1 Stack

| Component | Technology | Version |
|---|---|---|
| Framework | FastAPI (async) + Uvicorn | 0.141.1 / 0.34.0 |
| ORM | SQLAlchemy 2.x async + asyncpg | 2.0.35 / 0.29.0 |
| Database | PostgreSQL 16 + pgvector | pgvector 0.3.6 |
| Cache / Broker | Redis 7 | redis-py 5.1.1 |
| Task queue | Celery | 5.4.0 |
| Vector store | ChromaDB (embedded PersistentClient) | 0.5.20 |
| Object storage | MinIO (local) / GCS (production) | minio 7.2.9 / google-cloud-storage 2.18.2 |
| Auth | JWT HS256 (15-min access + 30-day refresh), bcrypt 12 rounds | PyJWT 2.14.0 / bcrypt 4.2.0 |
| Config | pydantic-settings | 2.5.2 |
| Observability | Prometheus, structured JSON logging, OpenTelemetry | prometheus-fastapi-instrumentator 8.1.0 |
| Migrations | Alembic | 1.14.1 |
| LLM providers | google-genai, openai, anthropic | 1.68.0, 1.51.2, 0.35.0 |
| Embeddings | sentence-transformers (CPU), tiktoken | 5.6.1, 0.8.0 |
| Document processing | pypdf, python-docx, pytesseract, Pillow | 6.16.1, 1.1.2, 0.3.13, 12.3.0 |

### 3.2 API Layer

**27 router modules** mounted in `app/main.py`:

```
/auth/*               register, login, logout, refresh, google OAuth
/api/v1/chat          non-streaming REST chat
/ws/chat/{id}         WebSocket streaming (primary AI interface)
/conversations/*      CRUD + pagination + pin/rename
/documents/*          upload, list, query, delete (RAG)
/jobs/{id}            ingestion job status polling
/memories/*           user memory CRUD
/code/analyze         code analysis
/mcp/tools            MCP tool discovery
/mcp/invoke           MCP tool invocation
/personas/*           persona CRUD
/productivity/*       todos, calendar, reminders, habits
/search/semantic      semantic search
/suggestions/context  context-aware suggestion chips
/generation/*         resume, cover letter, email generation
/images/analyze       image analysis
/transcription/       speech-to-text
/translation/         text translation
/users/me             user profile
/usage/cost           token cost dashboard
/prompts/templates/*  versioned prompt template CRUD
/notifications/*      FCM device token registration
/admin/*              admin endpoints (role-gated)
/analytics/*          usage analytics
/api/v1/observability/events  Android observability event upload
/analysis/errors      AI error analysis
/incidents/*          DevOps incident CRUD
/devops/chat          DevOps assistant chat
/data/export, /data/account  GDPR export + deletion
```

### 3.3 Middleware Stack

Applied outermost → innermost:

1. `RequestLoggingMiddleware` — structured JSON logs + correlation ID header
2. `RateLimitMiddleware` — Redis sliding-window per user/IP
3. `DataResidencyMiddleware` — geo-region enforcement
4. `RequestBodySizeLimitMiddleware` — guards oversized payloads
5. `CORSMiddleware` — origins from `settings.CORS_ORIGINS`

### 3.4 LLM Integration

**Two parallel provider abstraction layers exist** (duplication — see §11):

#### Layer 1: `services/llm_clients.py` (older)
Used directly by `AIOrchestrator`. Six concrete `BaseLLMClient` implementations:

| Class | Provider | Model |
|---|---|---|
| `GeminiClient` | Google Gemini (google-genai) | `gemini-3.1-flash-lite` (default) |
| `OpenAIClient` | OpenAI | GPT-4o via AsyncOpenAI |
| `ClaudeClient` | Anthropic | Claude 3.5 Sonnet via AsyncAnthropic |
| `OllamaClient` | Local Ollama | Configurable via `OLLAMA_BASE_URL` |
| `LlamaClient` | Llama 3.x | Routed through Ollama |
| `MistralClient` | Mistral | Routed through Ollama |

#### Layer 2: `llm/providers/` + `llm/base.py` + `llm/service.py` (newer)
`LLMProvider` ABC with `generate()` and `stream()` methods:

| Class | Provider | Features |
|---|---|---|
| `GeminiProvider` | Google Gemini (google-genai SDK) | Retry with backoff, fallback model, quota detection |
| `LocalGemmaProvider` | Gemma via Ollama | Zero external calls, delegates to `OllamaClient` |

`LLMService` wraps these providers and routes by complexity hint (`"simple"` / `"complex"` / `"local"`).

#### `AIOrchestrator` (`services/ai_orchestrator.py`)
The central LLM dispatch service used by the WebSocket router:
- Resolves provider (Layer 1 clients)
- Fetches conversation history from `MessageRepository`
- Retrieves memories from `MemoryService` (ChromaDB)
- Detects prompt injection (static regex + `SafetyService`)
- Applies safety filters
- Builds system prompt (system + memories + history + persona)
- Summarizes history when context window > 80%
- Streams tokens over WebSocket
- Buffers tokens in Redis on client disconnect
- Records token usage in `TokenUsageRepository`

### 3.5 Agent System

A full agent architecture already exists in `backend/app/agents/` AND `domain/agent/` (Android):

#### Backend agents (`backend/app/agents/`)

| File | Purpose |
|---|---|
| `base.py` | `Agent` ABC: `name`, `description`, `capabilities`, `execute()` (async generator), `can_handle()` |
| `models.py` | `AgentRequest`, `AgentResult`, `AgentExecution`, `AgentEvent` hierarchy (Started, Token, Thinking, ToolStarted, ToolCompleted, ToolFailed, Completed, Failed, Cancelled), `AgentCapability` enum, `AgentStatus` FSM, `AgentDecision` variants (Respond, CallTool, Retrieve, Wait, Finish) |
| `orchestrator.py` | `AgentOrchestrator`: routes → plans → executes per step, handles handoffs, timeouts, cancellation |
| `planner.py` | `AgentPlanner`: builds `AgentPlan` with step list, timeout, limit counters |
| `router.py` | `AgentRouter`: routes `AgentRequest` to first `Agent` where `can_handle()` returns True |
| `registry.py` | `AgentRegistry`: register/get/list agents by name |
| `llm_client.py` | LLM client bridge for agents |
| `model_router.py` | Routes to model based on complexity |
| `chat_agent.py` | Conversational text agent |
| `code_agent.py` | Code analysis / generation agent |
| `image_agent.py` | Image analysis agent |
| `pdf_agent.py` | PDF processing agent |
| `rag_agent.py` | RAG retrieval agent |
| `voice_agent.py` | Voice transcription agent |
| `web_agent.py` | Web search agent |
| `tool_agent.py` | Generic tool-calling agent |

#### Android agents (`data/agent/`)
Mirror implementations: `ChatAgent`, `CodeAgent`, `ImageAgent`, `PdfAgent`, `RagAgent`, `VoiceAgent`, `WebAgent`, `OnDeviceAgent`, `ToolAgent`, `ModelRouterImpl`, `AgentGateway`, `AgentExecutionLogger`

Tool implementations in `data/agent/tools/`: `CalculatorTool`, `DateTimeTool`, `DocumentSearchTool`, `WebSearchTool`

**Note:** The backend `AgentOrchestrator` is currently **not wired into the WebSocket router**. The router still calls `AIOrchestrator` (the service layer) directly. The agent infrastructure is built but not yet the primary code path.

### 3.6 RAG Pipeline

#### Ingestion (`RAGService.create_ingestion_job` → Celery `ingest_document_task`)

```
POST /documents (multipart)
  → MIME + extension + size validation
  → StorageService.upload() → MinIO/GCS bucket
  → JobRepository.create_job()
  → Celery: ingest_document_task.delay(document_id, user_id)

Worker:
  RAGService.extract_text(bytes, mime)
    ├── PDF    → pdfplumber primary, pytesseract OCR fallback
    ├── DOCX   → python-docx
    └── TXT/MD → direct read (UTF-8 + Latin-1 fallback)

  RAGService.chunk_text(text)
    └── fixed-size sliding window with overlap
        tiktoken tokenizer for accurate counts

  RAGService.embed_and_store(chunks)
    └── SentenceTransformer (all-MiniLM-L6-v2) → embeddings
    → ChromaDB: add_documents(collection="user_{user_id}")
    → PostgreSQL: DocumentChunk rows (chunk_text, page_number, embedding [pgvector])

  Job status: PENDING → PROCESSING → COMPLETED | FAILED
```

#### Retrieval (`RAGService.query_documents`)

```
POST /documents/query {query, top_k}
  → SentenceTransformer.embed(query)
  → ChromaDB.query(collection="user_{user_id}", embedding, n_results=top_k)
  → Fetch DocumentChunk metadata from PostgreSQL
  → Assemble context: "Source: {doc_name} p.{page}\n{chunk_text}\n---"
  → AIOrchestrator.complete(system_prompt + context + user_query)
  → Response + citations: [{document_name, page_number, chunk_text}]
```

Supported formats: PDF, DOCX, TXT, MD. Max file size configurable via `settings.MAX_UPLOAD_SIZE_MB`.

### 3.7 MCP Broker & Connectors

`MCPBroker` (`services/mcp_broker.py`):
- `MCPToolConnector` ABC: `tool_name`, `get_schema()`, `invoke(params, user_id)`, `requires_confirmation()`
- `MCPBroker.register(connector)` / `.discover()` / `.invoke(tool_name, params, user_id)`
- Every invocation writes an `AuditLog` row (mandatory, regardless of success/failure)
- `requires_confirmation()` → returns `confirmation_required` result without executing

**9 MCP connectors** in `services/mcp_connectors/`:
`GitHubConnector`, `GmailConnector`, `SlackConnector`, `JiraConnector`, `NotionConnector`, `GDriveConnector`, `GCalConnector`, `FigmaConnector`, `DevOpsConnectors`

**API endpoints:** `GET /mcp/tools` (schema discovery), `POST /mcp/invoke` (tool execution)

**Android domain model:** `MCPTool` in `domain/model/MCPTool.kt`

### 3.8 Memory Service

`MemoryService` (`services/memory_service.py`) + `MemoryRepository` (ChromaDB + PostgreSQL):

- Per-user ChromaDB collection (`"user_{user_id}_memories"`)
- Memory types: `FACT`, `PREFERENCE`, `WRITING_STYLE`, (+ `AGENT_STATE` targeted for new work)
- `store_memory(user_id, content, type)` — no-op when `user.privacy_mode = True`
- `retrieve_memories(user_id, query, top_k=3)` — semantic cosine similarity
- `delete_memory(user_id, memory_id)` — removes from both ChromaDB and PG
- Used by `AIOrchestrator` before every LLM call (context injection step 2)

### 3.9 Authentication & Security

| Component | File | What it does |
|---|---|---|
| JWT issuance/verification | `security/jwt_handler.py` | HS256 access token (15 min) + opaque refresh token (30 days, hash-only stored) |
| Password hashing | `security/password.py` | bcrypt 12 rounds |
| Account lockout | `security/lockout.py` | Redis counter; locks after N failed logins |
| RBAC | `security/rbac.py` | `require_roles`, `require_admin`, `require_premium_or_admin` FastAPI deps |
| Audit log | `security/audit.py` | `AuditService` — immutable `AuditLog` row per sensitive action |
| Field encryption | `security/encryption.py` | AES-256 for stored secrets |
| Differential privacy | `security/differential_privacy.py` | ε-DP for analytics queries |
| Input sanitization | `security/input_sanitizer.py` | HTML/script stripping before storage |
| User dependency | `security/dependencies.py` | `get_current_user` FastAPI dep → `User` ORM object |
| Email (verification) | `security/email_service.py` | Email verification flows |

### 3.10 Celery Workers

| Worker | Queue | Task | Purpose |
|---|---|---|---|
| `rag_worker.py` | `ingestion` | `ingest_document_task` | Document extraction → chunking → embedding → ChromaDB/PG |
| `notification_worker.py` | `notifications` | notification tasks | FCM push dispatch |
| `gdpr_worker.py` | `gdpr` | GDPR tasks | Account deletion erasure, export packaging |
| `anomaly_worker.py` | `celery` | anomaly detection | ML anomaly batch over observability events |
| `alert_worker.py` | `alerts` | spending alert | Evaluate spending thresholds + notify |

Celery is configured with:
- Redis as both broker and result backend
- `solo` pool on Windows, `prefork` on Linux
- Task time limit: 600 s (hard), 480 s (soft)
- Queues: `celery`, `ingestion`, `notifications`, `gdpr`, `alerts`

### 3.11 Service Layer Summary

| Service | File | Core Responsibility |
|---|---|---|
| `AIOrchestrator` | `services/ai_orchestrator.py` | Central LLM hub — memory, safety, streaming, token recording |
| `LLMService` | `llm/service.py` | Provider-agnostic LLM calling (newer layer) |
| `RAGService` | `services/rag_service.py` | Document ingestion + semantic retrieval + citations |
| `MemoryService` | `services/memory_service.py` | User memory store/retrieve (ChromaDB + PG) |
| `AuthService` | `services/auth_service.py` | JWT, refresh rotation, lockout |
| `SafetyService` | `services/safety_service.py` | Injection detection + response content filtering |
| `PromptService` | `services/prompt_service.py` | Versioned prompt template CRUD + rollback |
| `PersonaService` | `services/persona_service.py` | User persona management |
| `MCPBroker` | `services/mcp_broker.py` | Tool registry + dispatch + mandatory audit |
| `StorageService` | `services/storage_service.py` | MinIO/GCS abstraction |
| `CostService` | `services/cost_service.py` | Token usage aggregation + spending alerts |
| `SearchService` | `services/search_service.py` | Semantic search over user document corpus |
| `SuggestionsService` | `services/suggestions_service.py` | Context-aware continuation chips |
| `ProductivityService` | `services/productivity_service.py` | Todo/calendar/reminder/habit CRUD |
| `AnomalyDetectionService` | `services/anomaly_detection_service.py` | ML anomaly over observability events |
| `DevOpsAssistantService` | `services/devops_assistant_service.py` | RAG-backed DevOps chat |
| `RcaService` / `RemediationService` | `services/rca_service.py` etc. | RCA + remediation recommendations |

---

## 4. Data Storage

### 4.1 PostgreSQL Schema (Backend)

**25 tables** across 16 Alembic migrations (0001–0016):

| Table | Key Columns | Migration |
|---|---|---|
| `users` | id (uuid), email, password_hash, google_id, role, privacy_mode, fcm_token | 0001, 0006, 0008 |
| `conversations` | id, user_id, title, provider, is_pinned, is_deleted | 0001 |
| `messages` | id, conversation_id, role, content, input_tokens, output_tokens, model_name | 0001 |
| `documents` | id, user_id, title, file_path, mime_type, status, chunk_count | 0001 |
| `document_chunks` | id, document_id, user_id, chunk_text, chunk_index, page_number, **embedding (pgvector Vector)**, chroma_id | 0001, 0009, 0014, 0015 |
| `memories` | id, user_id, content, memory_type, **embedding (Vector)** | 0001 |
| `token_usage` | id, user_id, feature, input_tokens, output_tokens, cost_usd, model_name | 0001, 0007 |
| `personas` | id, user_id, name, system_prompt, is_active | 0001 |
| `prompt_templates` | id, author_id, name, content, version, is_active | 0001, 0005 |
| `refresh_tokens` | id, user_id, token_hash, family_id, is_revoked, expires_at | 0002 |
| `todo_items`, `calendar_events`, `reminders`, `habit_definitions`, `habit_entries` | productivity tables | 0003 |
| `api_keys` | id, user_id, key_hash, name, is_active | 0004 |
| `audit_logs` | id, user_id (nullable), action, resource_type, result_status | 0001 |
| `jobs` | id, user_id, job_type, status, payload, error_message | 0001 |
| `notes` | id, user_id, title, content, tags | 0001 |
| `observability_events` | id, user_id, event_type, payload | 0010 |
| `error_logs` | id, user_id, error_type, stack_trace, context | 0001 |
| `incidents` | id, user_id, title, severity, status, description | 0011 |
| `remediation_actions` | id, incident_id, action_type, description, status | 0013 |
| `spending_alerts` | id, user_id, threshold_usd, is_active | 0007 |
| `agent_execution` tables | agent execution state | 0016 |

`pgvector` extension is used on `document_chunks.embedding` and `memories.embedding` for ANN search.

### 4.2 ChromaDB (Vector Store)

- Version: 0.5.20 (embedded `PersistentClient`)
- Persist directory: `CHROMA_PERSIST_DIR` (defaults to `/tmp/chroma` in Docker)
- **Per-user collections** for isolation: `"user_{user_id}"` (documents), `"user_{user_id}_memories"` (memories)
- Operations: `add_documents`, `query` (cosine), `delete`
- Retry wrapper: `_chroma_op_with_retry` (3 attempts, 1 s base delay)
- **Note:** `docker-compose.yml` includes a `chromadb` service but the backend uses embedded mode — inconsistency (see §11)

### 4.3 Redis

Three distinct use cases:

| Use Case | Key Pattern | TTL | Used By |
|---|---|---|---|
| WebSocket token buffer | `ws:buffer:{user_id}:{conversation_id}` | TTL configurable | `_BufferingWebSocketProxy` — replays tokens on reconnect |
| Rate limiting | `rate_limit:{user_id}:{window}` | Sliding window | `RateLimitMiddleware` |
| Account lockout | `lockout:{email}` | Configurable | `lockout.py` |
| Celery broker | Default Celery Redis patterns | N/A | All Celery workers |
| Celery result backend | Default Celery Redis patterns | N/A | All Celery workers |
| Session / caching | `settings.REDIS_URL` | Varies | Various services |

Redis client: async `redis-py 5.1.1` singleton via `get_redis_client()` (`lru_cache`).

### 4.4 Object Storage — MinIO / GCS

`StorageService` abstracts both backends via `STORAGE_BACKEND` env var (`"minio"` or `"gcs"`):

- **MinIO** (local/stage): bucket `ai-assistant-docs`, presigned URL generation
- **GCS** (production): Google Cloud Storage bucket, IAM workload identity
- Operations: `upload(file_bytes, key)`, `download(key)`, `delete(key)`, presigned URL
- All document files are stored before being passed to the ingestion worker

### 4.5 Android Room Database

**Version 3**, migrations at `core-database/`:

| Entity | Notes |
|---|---|
| `ConversationEntity` | Local cache + FTS4 via `ConversationFtsEntity` |
| `MessageEntity` | FTS4 via `MessageFtsEntity`, syncStatus enum |
| `DocumentEntity` | Mirrors server document record |
| `MemoryEntity` | Local copy of user memories |
| `NoteEntity`, `TodoItemEntity`, `CalendarEventEntity`, `ReminderEntity` | Productivity data |
| `HabitDefinitionEntity`, `HabitEntryEntity` | Habit tracking |
| `OnDeviceDocumentEntity`, `OnDeviceChunkEntity` | Added in v3 — on-device RAG |
| `QueryRoutingLogEntity` | Added in v3 — cloud vs. on-device routing audit |

`SyncMessagesWorker` (WorkManager) drains an offline queue of messages to the backend.

---

## 5. Gemini Integration

Gemini is the default and primary LLM provider on the backend.

### Two Integration Points

**1. `services/llm_clients.py` → `GeminiClient`** (used by `AIOrchestrator`)
- Uses `google-genai` SDK (≥ 1.68.0, replaced deprecated `google-generativeai`)
- Model: `gemini-3.1-flash-lite` (configurable via `GEMINI_MODEL` env var)
- Async streaming: `AsyncIterator[str]` token stream
- Fallback: `GEMINI_FALLBACK_MODEL` on quota/rate-limit errors

**2. `llm/providers/gemini_provider.py` → `GeminiProvider`** (used by `LLMService`)
- `GeminiProvider.generate()` — non-streaming, full response + usage stats
- `GeminiProvider.stream()` — streaming token iterator
- `_generate_with_retry()` — exponential backoff, configurable max attempts
- `_build_config()` — `GenerateContentConfig` with `max_output_tokens`, `temperature`
- `_build_response()` — maps `genai.GenerateContentResponse` → `LLMResponse` (with `LLMUsage`)
- Fallback model: `settings.GEMINI_FALLBACK_MODEL`
- Quota detection: catches `ResourceExhausted` → `LLMQuotaError`

### Safety Configuration
Both providers apply Gemini's built-in safety filters via `GenerateContentConfig.safety_settings`. The backend `SafetyService` adds an additional layer of prompt injection detection and response filtering before/after Gemini calls.

---

## 6. Chat & WebSocket Contract

### Endpoint
```
WS  /ws/chat/{conversation_id}?token=<jwt>
```

### Client → Server
```jsonc
{ "user_message": "Hello!", "provider": "gemini" }  // send message
{ "type": "pong" }                                   // respond to heartbeat
```
`provider` optional; defaults to `"openai"`. Valid: `openai | gemini | claude | ollama | llama | mistral`

### Server → Client
```jsonc
{ "type": "token",     "data": "<text chunk>" }
{ "type": "done",      "usage": { "inputTokens": 123, "outputTokens": 456 } }
{ "type": "error",     "message": "<description>" }
{ "type": "tool_call", "toolName": "github", "toolInput": { ... } }
{ "type": "ping" }  // heartbeat every 30 s — client must send pong
```

### Android Event Mapping
```
"token"     → StreamEvent.Token(text)
"done"      → StreamEvent.Done(TokenUsage)
"error"     → StreamEvent.Error(message)
"tool_call" → StreamEvent.ToolCall(toolName, toolInput)
```

### Disconnect / Reconnect
- Backend `_BufferingWebSocketProxy` buffers `token` events in Redis (`ws:buffer:{user_id}:{conversation_id}`) on client disconnect
- On reconnect, `flush_token_buffer()` replays buffered tokens before new messages
- Android: exponential backoff reconnect (up to 5 attempts, max 30 s delay)

---

## 7. Document Processing & RAG

### Supported Formats
| Format | Extractor | Fallback |
|---|---|---|
| PDF | `pdfplumber` (page-level text) | `pytesseract` OCR on page images |
| DOCX | `python-docx` | — |
| TXT / MD | Direct UTF-8 read | Latin-1 decode |

### Chunking Strategy
- Fixed-size sliding window with configurable overlap
- `tiktoken` for accurate token counting per chunk
- Each chunk stores `chunk_index` and `page_number` for citation generation
- Property enforced: every token appears in ≥1 chunk

### Embedding Model
- `sentence-transformers` (CPU, `all-MiniLM-L6-v2`) — warmed up at startup
- On-device (Android): `MiniLmEmbeddingModel` (same model, runs locally)
- Vector dimension: 384

### Storage
- ChromaDB: embedding vectors (ANN retrieval)
- PostgreSQL `document_chunks`: text + metadata + `pgvector` embedding column (ANN backup / hybrid search)
- MinIO/GCS: raw file bytes

### Android On-Device RAG
- `OnDeviceIngestDocumentUseCase` → `MiniLmEmbeddingModel` → `LocalVectorIndexImpl` → Room (`OnDeviceChunkDao`)
- `OnDeviceQueryUseCase` → embed → `LocalVectorIndexImpl.query()` → Room chunk lookup → answer context
- Fully offline — no backend required

---

## 8. Infrastructure & DevOps

### Docker Compose (Local Dev)

| Service | Image | Port |
|---|---|---|
| `postgres` | pgvector/pgvector:pg16 | 5432 |
| `redis` | redis:7-alpine | 6379 |
| `minio` | quay.io/minio/minio:RELEASE.2024-09-13 | 9000 / 9001 |
| `chromadb` | chromadb/chroma:1.5.9 | 127.0.0.1:8001 (localhost-only) |
| `backend` | ./backend Dockerfile (multi-stage) | 8000 |
| `celery_worker` | ./backend Dockerfile | — (queues: celery/ingestion/notifications/gdpr/alerts) |

### GCP Production (Terraform)

| Resource | Details |
|---|---|
| Cloud Run | Backend service, auto-scaling |
| Artifact Registry | Docker images |
| GCS | Object storage (replaces MinIO) |
| Secret Manager | All secrets (API keys, JWT secret, DB password) |
| Cloud Build | CI/CD via `cloudbuild.yaml` |
| IAM | Workload Identity for Cloud Run → GCS/SM |

### CI/CD (GitHub Actions — 7 workflows)

| Workflow | Trigger | Purpose |
|---|---|---|
| `android-ci.yml` | Push to main/feature | Assemble, lint, unit tests |
| `backend-ci.yml` | Push to main/feature | pytest, ruff, coverage |
| `cloud-run-deploy.yml` | Release tag | Build → push AR → deploy Cloud Run |
| `security-scan.yml` | Schedule + push | gitleaks, trivy, OWASP dep-check |
| `infrastructure-validation.yml` | PR | terraform validate + plan |
| `flutter-ci.yml` | Push | Flutter build + tests |
| `release.yml` | Tag | Android APK/AAB + backend deploy |

---

## 9. Existing Tests

### Backend (Python)
**~62 test files** across 4 suites:

| Suite | Location | Coverage |
|---|---|---|
| Unit | `tests/unit/` (~50 files) | Auth, orchestrator, RAG, WebSocket, MCP, safety, memory, cost, prompts, agents, LLM clients, settings, GDPR, celery workers |
| Integration | `tests/integration/` (~12 files) | End-to-end request flows with DB |
| Property | `tests/property/` (+ `tests/unit/test_property_*.py`) | Hypothesis-based: RAG chunk coverage, token rotation, prompt rollback, context summarization |
| Security | `tests/security/` | JWT, rate limiting, input sanitization |

Notable unit tests: `test_mcp_broker.py`, `test_mcp_connectors.py`, `test_rag_service.py`, `test_ai_orchestrator_complete.py`, `test_websocket_router.py`, `test_gemini_provider.py`, `test_llm_service.py`

Framework: pytest 9.0.3 + pytest-asyncio 1.4.0 + hypothesis 6.115.3

### Android (Kotlin)
- `core-common`: **21 tests PASSING** (Kotest + JUnit4 + MockK confirmed working)
- `core-network`: 29 test files exist (not verified — requires full SDK sync)
- All other modules: test files exist, not executed in audit

### Flutter
Not executed — requires Flutter SDK.

---

## 10. Existing Reusable Components

### Android — Safe to reuse directly

| Component | Module | What it provides |
|---|---|---|
| `AIStreamClient` (interface) + `AIStreamClientImpl` | `core-ai` | WebSocket streaming, backoff, event parsing |
| `LlmClient` (domain interface) | `domain` | Contract for cloud LLM calls |
| `Agent` (domain ABC) | `domain` | Agent interface with `execute()` and `can_handle()` |
| `AgentOrchestrator` | `domain` | Multi-step agent execution coordinator |
| `AgentRegistry`, `AgentRouter`, `AgentPlanner` | `domain` | Agent routing and planning infrastructure |
| `Tool`, `ToolRegistry` | `domain` | Tool interface and registry |
| `MiniLmEmbeddingModel` | `core-ai` | On-device sentence embedding |
| `LocalVectorIndexImpl` | `core-ai` | In-memory vector search |
| `QueryRouterImpl` | `core-ai` | Cloud vs. on-device routing |
| `AppDatabase` + all DAOs | `core-database` | Room with all entities |
| `OkHttpClient` + `Retrofit` + interceptors | `core-network` | Fully configured HTTP stack |
| `ConnectivityObserver` | `core-network` | `isConnectedFlow: Flow<Boolean>` |
| `SecureStorage` | `core-security` | Encrypted token storage |
| `ApiResult<T>`, `DomainError`, `DispatcherProvider` | `core-common` | Result type, error hierarchy, dispatcher |
| All Compose components | `core-ui` | `ChatBubble`, `MarkdownText`, `CodeBlock`, `RagComponents`, etc. |
| `DocumentRepository` (interface + impl) | `domain` / `data` | Upload + query + delete documents |
| `OnDeviceDocumentRepository` | `domain` / `data` | On-device RAG document storage |
| `MemoryRepository` (interface + impl) | `domain` / `data` | Memory CRUD |

### Backend — Safe to reuse directly

| Component | File | What it provides |
|---|---|---|
| `Agent` ABC | `agents/base.py` | Agent contract for all agent implementations |
| `AgentOrchestrator` | `agents/orchestrator.py` | Full agent execution with timeout, handoffs, cancellation |
| `AgentRegistry`, `AgentRouter`, `AgentPlanner` | `agents/` | Agent routing + planning infrastructure |
| `AgentRequest`, `AgentResult`, `AgentEvent` | `agents/models.py` | Complete agent event model (15+ event types) |
| `RAGService` | `services/rag_service.py` | Full ingestion + retrieval + citations |
| `MCPBroker` | `services/mcp_broker.py` | Tool registry + dispatch + audit |
| All 9 MCP connectors | `services/mcp_connectors/` | GitHub, Gmail, Slack, Jira, Notion, GDrive, GCal, Figma, DevOps |
| `MemoryService` | `services/memory_service.py` | User memory store/retrieve |
| `StorageService` | `services/storage_service.py` | MinIO/GCS abstraction |
| `SafetyService` | `services/safety_service.py` | Injection detection + filtering |
| `PromptService` | `services/prompt_service.py` | Versioned prompt templates |
| `CostService` | `services/cost_service.py` | Token aggregation + spending alerts |
| `AuditService` | `security/audit.py` | Immutable audit log writer |
| `get_current_user`, `require_roles` | `security/dependencies.py`, `security/rbac.py` | Auth + RBAC FastAPI deps |
| `LLMProvider` ABC + `GeminiProvider` | `llm/base.py`, `llm/providers/` | Newer, cleaner LLM provider interface |
| `LLMService` | `llm/service.py` | Provider routing by complexity |
| `ingest_document_task` | `workers/rag_worker.py` | Existing Celery ingestion task |
| `get_redis_client()` / `get_redis` | `database/redis.py` | Async Redis singleton + FastAPI dep |

---

## 11. Known Issues & Duplication Risks

### Critical Issues

| # | Issue | Location | Impact |
|---|---|---|---|
| C1 | **JWT placeholder in WebSocket** | `ChatDetailViewModel` — `val jwt = "placeholder_jwt"` | All WebSocket connections use invalid JWT; backend rejects auth. Known gap, not yet wired to `SecureStorage`. |
| C2 | **On-device inference is a stub** | `OnDeviceInferenceClient.runLocalInference()` | Simulates tokens with `delay(50ms)`. JNI bridge to llama.cpp has a `TODO` comment but does not exist. |
| C3 | **pgvector not installed in `venv311`** | `backend/venv311/` | `ModuleNotFoundError: No module named 'pgvector'` — all 62 backend test files fail to collect until fixed. |

### Architectural Duplication

| # | Duplication | Risk |
|---|---|---|
| D1 | **Two LLM provider layers**: `services/llm_clients.py` (older `BaseLLMClient`) used by `AIOrchestrator` **and** `llm/providers/` (newer `LLMProvider`) used by `LLMService` | Adding a new provider requires updating both layers. New work must pick one and migrate `AIOrchestrator`. |
| D2 | **Agent orchestrator not wired to WebSocket router**: `AgentOrchestrator` in `agents/` exists but the WebSocket router still calls `AIOrchestrator` (service layer). Two code paths for the same user action. | Risk of parallel evolution — new agent features added to `agents/` won't surface unless the router is switched. |
| D3 | **`core-on-device-ai` duplicates `feature-on-device-ai`**: `DeviceCapabilityDetector`, `HardwareCapabilityDetector`, `OnDeviceModelManager`, `RamMonitor`, `OnDeviceInferenceClient`, and 7 other files exist identically in both modules. | Fixes and changes must be applied twice; likely to diverge. |
| D4 | **Flutter and Android duplicate WebSocket client**: `WebSocketService.dart` replicates all logic of `AIStreamClientImpl.kt` (connection, heartbeat, backoff, event parsing). | Protocol changes require dual maintenance. |
| D5 | **`app/HomeDashboard.kt` vs. `feature-dashboard/DashboardScreen.kt`**: the app module has an older dashboard partially superseded by the feature module. | Confusing entry points; dead code risk. |
| D6 | **Three modules for on-device model management**: `feature-on-device-rag/ManageModelsScreen`, `feature-on-device-ai`, and `core-on-device-ai` all touch model download/delete. | Logic scattered; any model management change touches 3 modules. |

### Minor Issues

| # | Issue |
|---|---|
| M1 | `docker-compose.yml` includes a `chromadb` service but the backend uses embedded `PersistentClient` — the service is unused in the default configuration. |
| M2 | Many Kotlin files have the module/purpose comment block duplicated verbatim (appears twice in the same file) — cosmetic code generation artifact. |
| M3 | `android-lint-check.ps1` and `ktlint-detekt-check.ps1` overlap with Gradle lint tasks — minor script redundancy. |

---

*End of Current State Architecture Document. No application code was modified.*
