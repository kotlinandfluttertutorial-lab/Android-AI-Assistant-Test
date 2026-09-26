# AI Assistant — Flutter Client

A production-quality Flutter application that consumes the **Android AI Assistant FastAPI backend**.

This client is built for learning, experimentation, and future cross-platform support.
The native Android application (Kotlin/Compose) remains the primary client — this Flutter app
is an independent second client sharing the same backend.

---

## Flutter & Dart Versions

| Tool    | Minimum required |
|---------|-----------------|
| Flutter | 3.22.0          |
| Dart    | 3.3.0           |

Check your installed version:
```bash
flutter --version
```

---

## Screens

| Screen | Route | Description |
|--------|-------|-------------|
| Login | `/login` | Email/password auth, show/hide password, validation, error states |
| Register | `/register` | Create account (password ≥ 12 chars) |
| Home | `/home` | Greeting, hero card, 3 quick-action shortcuts, recent chats |
| Chats | `/conversations` | Paginated list, swipe-to-delete, long-press export (MD/PDF), cache-first |
| Chat | `/chat/:id` | WebSocket streaming, suggestion chips, offline queue, stop/retry |
| Incidents | `/incidents` | Severity filter chips, AI summary line, open-count badge |
| Incident Detail | `/incidents/:id` | Header, RCA card with confidence bar, remediation approve/reject |
| DevOps AI | `/devops` | REST-based ReAct assistant, tool-call badges, citations, suggestions |
| Error Analysis | `/analysis/errors` | AI error analysis — facts/inferences, confidence, recommended fix |
| Settings | `/settings` | Provider selector, theme, on-device AI status, account, env info |

---

## Architecture

```
Presentation (Screens)
        ↓
State Management (Riverpod Notifiers)
        ↓
Domain (Models, Interfaces)
        ↓
Data (Repositories, API clients)
        ↓
Backend (FastAPI REST + WebSocket)
        ↓
Local Cache (SharedPreferences + SecureStorage)
```

### Feature-oriented folder structure

```
flutter/lib/
├── main.dart                      Entry point — loads SharedPreferences, launches app
│
├── app/
│   ├── app.dart                   Root widget — MaterialApp.router
│   ├── providers/core_providers.dart   Riverpod infra: Dio, SecureStorage, Prefs
│   ├── router/app_router.dart     GoRouter (5-tab shell + full-screen routes)
│   └── theme/                     app_colors.dart, app_theme.dart (M3 + extensions)
│
├── core/
│   ├── constants/                 app_constants.dart, api_config.dart
│   ├── error/                     app_error.dart, error_mapper.dart
│   ├── network/                   dio_client.dart, auth_interceptor.dart,
│   │                              websocket_service.dart
│   ├── storage/
│   │   ├── secure_storage.dart           JWT tokens
│   │   ├── app_preferences.dart          Non-sensitive settings
│   │   ├── pending_message_queue.dart    Offline message queue (FIFO, max 50)
│   │   ├── conversation_cache.dart       Local conversation list cache (max 100)
│   │   └── *_provider.dart              Riverpod providers for each storage class│   └── utils/                     app_logger.dart, result.dart, date_formatter.dart
│
├── features/
│   ├── auth/                      Login, Register, AuthRepository, AuthStateNotifier
│   ├── chat/                      ChatNotifier (WebSocket + offline queue), ChatScreen
│   ├── conversations/             ConversationsNotifier (cache-first), list screen
│   ├── home/                      HomeScreen with quick actions (Incidents + DevOps)
│   ├── incidents/                 IncidentsScreen, IncidentDetailScreen,
│   │                              RCA card, remediation approve/reject
│   ├── devops/                    DevOpsChatScreen (REST ReAct, tool badges, citations)
│   ├── analysis/                  ErrorAnalysisScreen (facts/inferences, confidence, fix)
│   ├── observability/             ObservabilityService (event capture + auto-upload)
│   ├── ai_providers/              AiProvider model, SelectedProviderNotifier
│   ├── on_device_ai/              OnDeviceAiService interface + StubImpl
│   └── settings/                  SettingsScreen (provider, theme, on-device AI)
│
└── shared/
    └── widgets/
        ├── app_button.dart             Primary + outlined buttons
        ├── app_text_field.dart         Consistent text field
        ├── app_card.dart               Card with optional accent border
        ├── message_bubble.dart         User (right) + AI (left) chat bubbles
        ├── typing_indicator.dart       3-dot bounce animation
        ├── suggestion_chip_row.dart    Pre-built query chips (animated show/hide)
        ├── loading_indicator.dart      Full-screen spinner + skeleton shimmer
        ├── error_view.dart             Error with optional retry
        ├── empty_state.dart            Icon + title + subtitle + optional CTA
        ├── provider_selector.dart      Horizontal chip row for AI provider selection
        ├── connection_status_banner.dart   Offline warning banner
        └── main_shell.dart             5-tab NavigationBar shell
```

---

## Navigation Structure

```
ShellRoute  (MainShell — 5-tab bottom NavigationBar)
  ├── /home          Home — greeting, hero card, quick actions, recent chats
  ├── /conversations Conversation list — cache-first, swipe-to-delete
  ├── /incidents     Incidents list — filter by severity, AI summary
  ├── /devops        DevOps AI assistant — REST chat, suggestion chips
  └── /settings      Settings — provider, theme, account

Full-screen (no bottom nav)
  ├── /chat/new          New chat (ChatScreen)
  ├── /chat/:id          Existing chat (ChatScreen)
  └── /incidents/:id     Incident detail (RCA + remediation)

Auth (redirected to when unauthenticated)
  ├── /login
  └── /register
```

---

## Dependencies

| Package                  | Version  | Purpose                                      |
|--------------------------|----------|----------------------------------------------|
| `flutter_riverpod`       | ^2.5.1   | State management                             |
| `go_router`              | ^14.2.0  | Declarative navigation + auth guard          |
| `dio`                    | ^5.4.3+1 | HTTP client with interceptors                |
| `web_socket_channel`     | ^3.0.1   | WebSocket (streaming AI responses)           |
| `freezed_annotation`     | ^2.4.4   | Immutable data classes (code-gen)            |
| `json_annotation`        | ^4.9.0   | JSON serialisation (code-gen)                |
| `flutter_secure_storage` | ^9.2.2   | JWT token storage (Keychain / Keystore)      |
| `shared_preferences`     | ^2.2.3   | Non-sensitive preferences + local caches     |
| `flutter_markdown`       | ^0.7.3+1 | Markdown + code block rendering in chat      |
| `intl`                   | ^0.19.0  | Date formatting                              |
| `uuid`                   | ^4.4.2   | Local message / queue entry IDs              |
| `equatable`              | ^2.0.5   | Value equality for domain models             |
| `logger`                 | ^2.4.0   | Structured console logging                   |

**Dev:** `build_runner`, `freezed`, `json_serializable`, `riverpod_annotation`,
`riverpod_generator`, `flutter_lints`, `mocktail`

---

## Local Setup

### 1. Install Flutter

Follow the [official guide](https://docs.flutter.dev/get-started/install).
```bash
flutter doctor
```

### 2. Start the backend

```bash
# from repo root
docker compose up -d
```

Starts PostgreSQL, Redis, ChromaDB, and the FastAPI backend on port **8000**.

### 3. Install Flutter dependencies

```bash
cd flutter
flutter pub get
```

### 4. (Optional) Run code generation

If you modify any `@freezed` or `@JsonSerializable` annotated models:
```bash
flutter pub run build_runner build --delete-conflicting-outputs
```

---

## Environment Configuration

Select the backend at build/run time with `--dart-define=ENV=<value>`:

| Environment   | Flag                          | Backend URL                                        |
|--------------|------------------------------|----------------------------------------------------|
| **local**    | `--dart-define=ENV=local`    | `http://10.0.2.2:8000` (Android emulator → host)  |
| **stage**    | `--dart-define=ENV=stage`    | `https://api-stage.ai-assistant.example.com`       |
| **production** | `--dart-define=ENV=production` | `https://api.ai-assistant.example.com`           |

`10.0.2.2` is the Android emulator's loopback alias for the host machine.
For a physical device on the same LAN, replace with your machine's IP.

No secrets are hard-coded. Never pass API keys via `--dart-define`.

---

## Running the Application

```bash
# Android emulator (local backend)
flutter run --dart-define=ENV=local

# iOS simulator
flutter run --dart-define=ENV=local -d "iPhone 15"

# List connected devices
flutter devices
flutter run --dart-define=ENV=local -d <device_id>
```

---

## Running Tests

```bash
cd flutter

# All unit + widget tests
flutter test

# With coverage
flutter test --coverage
genhtml coverage/lcov.info -o coverage/html

# Integration tests (requires running backend)
docker compose up -d
flutter test integration_test/ --dart-define=ENV=local

# Lint + format
flutter analyze
dart format --set-exit-if-changed .
```

### Test file inventory

| File | Tests | Coverage |
|------|-------|----------|
| `test/core/error/error_mapper_test.dart` | 8 | ErrorMapper HTTP → AppError mapping |
| `test/core/utils/result_test.dart` | 8 | Result<T> sealed class |
| `test/core/utils/date_formatter_test.dart` | 6 | Relative time, ISO parse |
| `test/core/storage/pending_message_queue_test.dart` | 14 | FIFO, cap, retry, JSON round-trip |
| `test/core/storage/conversation_cache_test.dart` | 11 | Cache read/write/upsert/clear |
| `test/features/auth/auth_models_test.dart` | 8 | Auth request/response models |
| `test/features/auth/auth_repository_test.dart` | 3 | Login/logout/restoreSession |
| `test/features/auth/login_screen_test.dart` | 3 | Form validation widget tests |
| `test/features/chat/chat_state_test.dart` | 6 | UiMessage + ChatState |
| `test/features/conversations/conversation_model_test.dart` | 5 | Conversation + Message models |
| `test/features/incidents/incident_models_test.dart` | 20 | All incident domain models |
| `test/features/devops/devops_models_test.dart` | 14 | DevOps request/response/turn models |
| `test/features/analysis/analysis_models_test.dart` | 18 | Error analysis domain models |
| `test/features/observability/observability_models_test.dart` | 12 | Observability event models |
| `test/features/ai_providers/ai_provider_model_test.dart` | 5 | Provider model + KnownProviders |
| `test/shared/widgets/app_button_test.dart` | 5 | AppButton widget |
| `test/shared/widgets/message_bubble_test.dart` | 5 | MessageBubble widget |
| `test/shared/widgets/typing_indicator_test.dart` | 2 | TypingIndicator animation |
| `test/shared/widgets/suggestion_chip_row_test.dart` | 5 | SuggestionChipRow widget |
| `integration_test/app_test.dart` | 3 | Launch → login redirect → register nav |

---

## API Integration

All API clients live in `features/<feature>/data/`.
They return `Result<T>` — never throw — and map errors via `ErrorMapper`.

### Endpoints consumed

| Feature | Endpoints |
|---------|-----------|
| Auth | `POST /auth/login`, `/register`, `/refresh`, `/logout` |
| Chat | `POST /api/v1/chat`, `WS /ws/chat/{id}?token=` |
| Conversations | `GET/POST /conversations`, `GET /conversations/{id}/messages`, `DELETE /conversations/{id}` |
| Incidents | `GET /incidents`, `GET /incidents/{id}`, `POST /incidents/{id}/rca`, `GET /incidents/{id}/rca` |
| Remediation | `POST /incidents/{id}/remediation/recommend`, `/approve`, `/reject` |
| DevOps AI | `POST /devops/chat`, `GET /devops/tools` |

### Auth flow

```
POST /auth/login  → {access_token, refresh_token, …}
  ↓
SecureStorage (flutter_secure_storage — Keychain / Keystore)
  ↓
AuthInterceptor → Authorization: Bearer <token> on every request
  ↓
Auto-refresh on 401 → POST /auth/refresh
  ↓
On refresh failure → clearAll() + redirect to /login
```

### Chat — WebSocket streaming

```
POST /conversations  → {id}  (create conversation if new)
  ↓
WS /ws/chat/{id}?token=<jwt>
  ↓
Client → {"user_message": "…", "provider": "gemini"}
  ↓
Server → {"type": "token", "data": "…"}  ×N  (streamed chunks)
         {"type": "done",  "usage": {…}}      (complete)
```

### Chat — Offline queue

```
sendMessage()
  ↓
WebSocket connect fails (no network)
  ↓
PendingMessageQueue.enqueue()   — persisted to SharedPreferences
  ↓
User sees "offline" banner + Retry button
  ↓
On reconnect → flush queue → send all pending messages in FIFO order
```

### DevOps AI — REST (no streaming)

```
POST /devops/chat  {question, provider?}
  ↓
Backend runs full ReAct tool-calling loop (server-side)
  ↓
Response: {answer, citations, tool_calls, rounds_used, llm_provider}
```

---

## WebSocket Architecture

```
ChatNotifier
  ├── sendMessage(text)
  │     ├── [online]  WebSocketService.connect → send message
  │     └── [offline] PendingMessageQueue.enqueue → show offline banner
  │
  ├── retryPendingMessages()
  │     └── reconnect → flush queue → send all pending
  │
  ├── _onWsMessage(WsMessage)
  │     ├── type=token   → append token to streaming bubble
  │     ├── type=done    → mark streaming complete
  │     ├── type=error   → mark bubble as error
  │     └── type=ping    → send pong immediately
  │
  └── stopGeneration()
        └── close WebSocket → backend stops streaming
```

Reconnection: exponential back-off, max 5 attempts, then shows error state.
Token buffering: backend buffers mid-stream tokens in Redis; flushed on reconnect.

---

## Incidents Feature

### Screens

**`IncidentsScreen`** (`/incidents`)
- Filter chips: All / Critical / High / Medium / Low
- Stats bar: total + open count
- Severity-coloured left-border cards
- AI summary line per incident (when available)
- Pull-to-refresh

**`IncidentDetailScreen`** (`/incidents/:id`)
- Severity gradient header card
- Root Cause Analysis section:
  - Confidence progress bar (green ≥ 80%, amber ≥ 60%, red < 60%)
  - Low-confidence warning when `overall_confidence < 0.6`
  - Ranked root cause candidates (#1, #2, #3)
  - Numbered investigation steps
- Remediation section:
  - AI-recommended actions with risk tier badge (LOW / MEDIUM / HIGH)
  - **Approve** / **Reject** buttons for pending actions
  - Extra confirmation dialog for HIGH-risk actions
  - Approval records human decision but does NOT auto-execute

> **AI Safety:** All remediation actions are recommendation-only. No automated production
> changes are made without explicit human approval. High-risk actions require a second
> confirmation step.

---

## DevOps AI Assistant

**`DevOpsChatScreen`** (`/devops`)

REST-based Q&A assistant backed by `POST /devops/chat`.

The backend runs a ReAct tool-calling loop server-side and returns a complete grounded
answer in one response (not streamed).

UI features:
- Optimistic loading bubble (shows "Running tools…" with TypingIndicator while waiting)
- Answer rendered as Markdown
- Tool-call badges showing which tools were used (e.g. `search_logs`, `get_metrics`)
- Citation links (long-press to copy)
- Footer: LLM provider + rounds used
- Suggestion chips: 7 pre-built DevOps queries
- Clear history button

### Example queries

```
Why did the API fail at 14:32?
Show me recent critical incidents
What is the current error rate?
Have we seen this error before?
What changed before the latest incident?
Generate an incident report for the latest open incident
What are the most likely root causes?
```

---

## Local Caches

| Cache | Storage | Capacity | Eviction |
|-------|---------|----------|---------|
| Conversation list | SharedPreferences | 100 items | LRU (oldest removed on overflow) |
| Pending messages | SharedPreferences | 50 items | Oldest dropped on overflow; expired after 5 retries |

Both caches are cleared on logout.

---

## Observability

The Flutter client captures structured telemetry events and uploads them to the backend
AI analysis pipeline via `POST /api/v1/observability/events` (no auth required).

### ObservabilityService

Location: `lib/features/observability/services/observability_service.dart`

```dart
// Capture an event
observabilityService.networkError(
  url: '/chat/message',
  statusCode: 503,
  message: 'AI service unavailable',
);

// Events are buffered in memory and flushed:
//  - Every 60 seconds (configurable timer)
//  - When 50 events accumulate
//  - When the app is backgrounded (via AppLifecycleObserver)
//  - On logout
```

### PII filtering

The service applies a final-pass sanitizer before upload:
- Strips `Bearer <token>` patterns from messages
- Redacts email addresses to `[EMAIL]`
- Strips URL query parameters (may contain tokens)
- Removes sensitive metadata keys (`token`, `password`, `secret`, `api_key`, etc.)

Events are **not re-queued** on upload failure — telemetry loss is acceptable to avoid
degrading the user experience.

---

## AI Error Analysis

`ErrorAnalysisScreen` at `/analysis/errors` calls `POST /analysis/errors` to run
the backend AI error analysis pipeline against recent observability events.

The screen displays:
- Severity + one-line summary
- Likely root cause
- Confidence bar (green ≥ 80%, amber ≥ 60%, red < 60%)
- Low-confidence warning when `confidence < 0.6` (manual investigation required)
- Facts vs Inferences — clearly separated (AI safety principle)
- Ranked possible causes
- Recommended fix — labelled as **AI suggestion only**
- Collapsible evidence log
- Metadata (LLM provider, events analysed, knowledge chunks retrieved)

---

Interface: `lib/features/on_device_ai/domain/on_device_ai_service.dart`

Current: `StubOnDeviceAiService` — returns `unsupported`.

States: `checking → unsupported | supported → loadingModel → ready → running`

To add a real backend (e.g. Gemma via llama.cpp FFI):
1. Implement `OnDeviceAiService`
2. Override `onDeviceAiServiceProvider` in `ProviderScope`
3. `ChatNotifier.sendMessage` already checks `provider.isOnDevice` — when true,
   the WebSocket path is skipped entirely; no inference request leaves the device

---

## Remaining TODOs

- [ ] Generate Freezed models for API responses (`build_runner`)
- [ ] Real on-device inference backend (e.g. Gemma GGUF via llama.cpp FFI)
- [ ] FCM push notification registration (`PUT /notifications/device-token`)
- [ ] RAG document upload / query screens
- [ ] iOS Bundle ID + signing configuration
- [ ] Tablet two-pane layout (conversations list + chat side-by-side)
- [ ] Google Sign-In (`POST /auth/google`)

---

## Completed from original plan

- [x] Material 3 theme with extended DevOps/AI color tokens
- [x] GoRouter with auth guard (5-tab shell + full-screen routes)
- [x] Login + Register screens
- [x] Home screen with hero card + 3 quick-action shortcuts
- [x] AI chat screen (WebSocket streaming, suggestion chips, offline queue)
- [x] Conversations list (cache-first, swipe-to-delete, export Markdown/PDF)
- [x] Incidents screen + detail (filter, RCA, remediation approve/reject + high-risk gate)
- [x] DevOps AI assistant screen (REST ReAct, tool badges, citations)
- [x] AI Error Analysis screen (severity, facts/inferences, confidence, fix suggestion)
- [x] Observability service (event capture, PII sanitizer, auto-flush, lifecycle observer)
- [x] Offline message queue (FIFO, max 50, auto-flush on reconnect, Retry button)
- [x] Local conversation cache (SharedPreferences, max 100)
- [x] AI provider abstraction (6 providers + on-device stub)
- [x] Settings screen (provider, theme, on-device AI, account)
- [x] iOS platform stubs (Info.plist, AppDelegate, Podfile)
- [x] 80+ unit + widget tests across 22 test files
- [x] Integration test skeleton (launch → login redirect → register navigation)
