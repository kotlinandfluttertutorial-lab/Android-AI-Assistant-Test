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
```

### Feature-oriented folder structure

```
flutter/lib/
├── main.dart                     Entry point — loads SharedPreferences, launches app
│
├── app/
│   ├── app.dart                  Root widget — MaterialApp.router
│   ├── providers/
│   │   └── core_providers.dart   Riverpod providers for Dio, SecureStorage, Prefs
│   ├── router/
│   │   └── app_router.dart       GoRouter config + auth guard
│   └── theme/
│       ├── app_colors.dart       Design token palette (light + dark)
│       └── app_theme.dart        Material 3 ThemeData + BuildContext extensions
│
├── core/
│   ├── constants/
│   │   ├── app_constants.dart    Timeouts, storage keys, pagination defaults
│   │   └── api_config.dart       Environment-driven URLs + endpoint paths
│   ├── error/
│   │   ├── app_error.dart        Typed error model (AppError, AppErrorType)
│   │   └── error_mapper.dart     Maps Dio exceptions → AppError
│   ├── network/
│   │   ├── dio_client.dart       Dio factory with interceptors
│   │   ├── auth_interceptor.dart Bearer token attach + silent refresh + 401 retry
│   │   └── websocket_service.dart WebSocket wrapper (reconnect, ping/pong, typed msgs)
│   ├── storage/
│   │   ├── secure_storage.dart   JWT tokens → flutter_secure_storage
│   │   └── app_preferences.dart  Non-sensitive prefs → SharedPreferences
│   └── utils/
│       ├── app_logger.dart       Centralised logger (DEBUG/INFO/WARN/ERROR)
│       ├── result.dart           Result<T> = Success<T> | Failure<T>
│       └── date_formatter.dart   Relative time, ISO parsing
│
├── features/
│   ├── auth/
│   │   ├── data/         auth_api.dart, auth_repository.dart
│   │   ├── domain/       auth_models.dart (AuthUser, LoginRequest/Response, etc.)
│   │   └── presentation/ login_screen.dart, register_screen.dart
│   │
│   ├── chat/
│   │   ├── domain/       chat_state.dart (UiMessage, ChatState)
│   │   ├── providers/    chat_provider.dart (ChatNotifier per conversation)
│   │   └── presentation/ chat_screen.dart (WebSocket streaming UI)
│   │
│   ├── conversations/
│   │   ├── data/         conversations_api.dart
│   │   ├── domain/       conversation_model.dart
│   │   ├── providers/    conversations_provider.dart
│   │   └── presentation/ conversations_screen.dart
│   │
│   ├── home/
│   │   └── presentation/ home_screen.dart
│   │
│   ├── ai_providers/
│   │   ├── domain/       ai_provider_model.dart (AiProvider, KnownProviders)
│   │   └── providers/    ai_provider_provider.dart (SelectedProviderNotifier)
│   │
│   ├── on_device_ai/
│   │   ├── domain/       on_device_ai_service.dart (interface + StubImpl)
│   │   └── providers/    on_device_ai_provider.dart
│   │
│   └── settings/
│       └── presentation/ settings_screen.dart
│
└── shared/
    └── widgets/
        ├── app_button.dart, app_text_field.dart, app_card.dart
        ├── message_bubble.dart, typing_indicator.dart
        ├── loading_indicator.dart, error_view.dart, empty_state.dart
        ├── provider_selector.dart, connection_status_banner.dart
        └── main_shell.dart
```

---

## Dependencies

| Package                  | Version  | Purpose                              |
|--------------------------|----------|--------------------------------------|
| `flutter_riverpod`       | ^2.5.1   | State management                     |
| `riverpod_annotation`    | ^2.3.5   | Code-gen annotations (optional)      |
| `go_router`              | ^14.2.0  | Declarative navigation + auth guard  |
| `dio`                    | ^5.4.3+1 | HTTP client with interceptors        |
| `web_socket_channel`     | ^3.0.1   | WebSocket (streaming AI responses)   |
| `freezed_annotation`     | ^2.4.4   | Immutable data classes (code-gen)    |
| `json_annotation`        | ^4.9.0   | JSON serialisation (code-gen)        |
| `flutter_secure_storage` | ^9.2.2   | JWT token storage (Keychain/Keystore)|
| `shared_preferences`     | ^2.2.3   | Non-sensitive preferences            |
| `flutter_markdown`       | ^0.7.3+1 | Markdown rendering in chat bubbles   |
| `intl`                   | ^0.19.0  | Date formatting                      |
| `uuid`                   | ^4.4.2   | Local message IDs                    |
| `equatable`              | ^2.0.5   | Value equality for domain models     |
| `logger`                 | ^2.4.0   | Structured console logging           |

**Dev dependencies:** `build_runner`, `freezed`, `json_serializable`, `riverpod_generator`,
`flutter_lints`, `mocktail`

---

## Local Setup

### 1. Install Flutter

Follow the [official guide](https://docs.flutter.dev/get-started/install).
Verify installation:
```bash
flutter doctor
```

### 2. Start the backend

From the **repository root**:
```bash
docker compose up -d
```
This starts PostgreSQL, Redis, ChromaDB, and the FastAPI backend on port **8000**.

### 3. Install Flutter dependencies

```bash
cd flutter
flutter pub get
```

### 4. (Optional) Run code generation

If you modify any `@freezed` or `@JsonSerializable` models:
```bash
flutter pub run build_runner build --delete-conflicting-outputs
```

---

## Environment Configuration

Select the backend environment at build/run time using `--dart-define=ENV=<value>`:

| Environment  | Command flag              | Backend URL                                      |
|-------------|--------------------------|--------------------------------------------------|
| **local**   | `--dart-define=ENV=local` | `http://10.0.2.2:8000` (Android emulator → host) |
| **stage**   | `--dart-define=ENV=stage` | `https://api-stage.ai-assistant.example.com`     |
| **prod**    | `--dart-define=ENV=production` | `https://api.ai-assistant.example.com`      |

> **Note:** `10.0.2.2` is the Android emulator's alias for `localhost` on the host machine.
> For a physical device on the same network, use your machine's LAN IP instead.

No secrets are hard-coded. URLs are compile-time constants only — never put API keys in `--dart-define`.

---

## Running the Application

```bash
# Android emulator (local backend)
flutter run --dart-define=ENV=local

# iOS simulator (local backend)
flutter run --dart-define=ENV=local -d "iPhone 15"

# Specific device
flutter devices
flutter run --dart-define=ENV=local -d <device_id>
```

---

## Running Tests

### Unit + widget tests
```bash
cd flutter
flutter test
```

### With coverage
```bash
flutter test --coverage
genhtml coverage/lcov.info -o coverage/html
```

### Integration tests (requires running backend)
```bash
docker compose up -d           # start backend first
flutter test integration_test/ --dart-define=ENV=local
```

### Lint + static analysis
```bash
flutter analyze
dart format --set-exit-if-changed .
```

---

## Backend Dependency

This Flutter app communicates with the FastAPI backend at:
- **REST:** `{BASE_URL}/auth/*`, `/chat/*`, `/conversations/*`, etc.
- **WebSocket:** `ws://{HOST}/ws/chat/{conversation_id}?token=<jwt>`

The backend must be running before the app can authenticate or send messages.
See the root `README.md` → *Quick Setup* for backend startup instructions.

---

## API Integration

All API clients live in `features/<feature>/data/`.
They return `Result<T>` (never throw) and map errors via `ErrorMapper`.

### Auth flow
```
POST /auth/login  → {access_token, refresh_token, …}
     ↓
SecureStorage (flutter_secure_storage)
     ↓
AuthInterceptor → Authorization: Bearer <token> on every request
     ↓
Auto-refresh on 401 → POST /auth/refresh
     ↓
On refresh failure → clearAll() + redirect to login
```

### Chat (WebSocket streaming)
```
POST /conversations  → {id}   (create if new)
     ↓
WS  /ws/chat/{id}?token=<jwt>
     ↓
Client sends: {"user_message": "…", "provider": "gemini"}
     ↓
Server streams: {"type": "token", "data": "…"}  ×N
                {"type": "done",  "usage": {…}}
```

### Providers supported
`gemini` | `openai` | `claude` | `ollama` | `llama` | `mistral`

---

## WebSocket Architecture

```
ChatNotifier
     │
     ├── sendMessage(text)
     │        └── WebSocketService.connect(conversationId, token)
     │                    └── ws://host/ws/chat/{id}?token=<jwt>
     │
     ├── _onWsMessage(WsMessage)
     │        ├── type=token   → append to streaming bubble
     │        ├── type=done    → mark streaming complete
     │        ├── type=error   → mark bubble as error
     │        └── type=ping    → send pong back immediately
     │
     └── stopGeneration()
              └── close WebSocket → backend stops streaming
```

Reconnection: exponential back-off, max 5 attempts, then shows error state.
Token buffering: backend buffers mid-stream tokens in Redis; flushed on reconnect.

---

## On-Device AI (Future)

The `OnDeviceAiService` interface is defined in:
`lib/features/on_device_ai/domain/on_device_ai_service.dart`

Current implementation: `StubOnDeviceAiService` (returns `unsupported`).

To add a real backend (e.g. Gemma via llama.cpp FFI):
1. Implement `OnDeviceAiService`
2. Override `onDeviceAiServiceProvider` in `ProviderScope`
3. In `ChatNotifier.sendMessage`, check `provider.isOnDevice` — if true, use the
   on-device service and **skip all network calls**

---

## Remaining TODOs

- [ ] Generate Freezed models for API responses (`build_runner`)
- [ ] Add real on-device inference backend (Phase 2)
- [ ] Add FCM push notification registration (`PUT /notifications/device-token`)
- [ ] Add RAG document upload / query screens
- [ ] Add DevOps assistant screen (POST /devops/chat)
- [ ] Add Incident dashboard screen
- [ ] iOS platform configuration (Bundle ID, signing)
- [ ] Tablet two-pane layout for conversations + chat
- [ ] Offline queue for messages sent without connectivity
- [ ] Add Google Sign-In (POST /auth/google)
