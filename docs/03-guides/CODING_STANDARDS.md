# Coding Standards
## Android AI Assistant — Enterprise Edition

---

## Educational Header Format

Every generated source file (Kotlin and Python) **must** begin with an Educational Header block.
Headers are required on all files in `src/main/kotlin/` and `backend/app/`. They are **not**
added to test files, `__init__.py`, generated build artifacts, or Alembic migration files.

### Kotlin Header Template

```kotlin
/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : <module name, e.g., feature-chat>
 * File       : <filename.kt>
 * Purpose    : <one-line description of what this file does>
 *
 * Architecture Layer : <Domain / Data / Feature-<name> / Core-UI / Core-Network / etc.>
 * Pattern Used       : <MVVM ViewModel / Use Case / Repository / Hilt Module / Compose Screen / etc.>
 *
 * Key Concepts:
 *   - <concept 1: explain a design decision made in this file>
 *   - <concept 2: explain a non-obvious implementation choice>
 *
 * Dependencies:
 *   - <dependency 1: library or module this file depends on>
 *   - <dependency 2: why this dependency is needed>
 * ============================================================
 */
```

#### Kotlin Example — Use Case

```kotlin
/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : domain
 * File       : SendMessageUseCase.kt
 * Purpose    : Validates and sends a user message to the AI Orchestrator
 *
 * Architecture Layer : Domain
 * Pattern Used       : Use Case (Single Responsibility — one public function)
 *
 * Key Concepts:
 *   - Message text is validated against the 32,000-character limit before any
 *     repository call, keeping validation in the domain layer (not the UI).
 *   - Returns Flow<ApiResult<Message>> so the ViewModel can observe streaming
 *     progress without blocking.
 *
 * Dependencies:
 *   - MessageRepository (domain interface — data module provides the implementation)
 *   - DispatcherProvider (core-common — enables test injection of TestDispatcher)
 * ============================================================
 */
```

#### Kotlin Example — ViewModel

```kotlin
/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : feature-chat
 * File       : ChatDetailViewModel.kt
 * Purpose    : Manages UI state for the ChatDetail screen and drives WebSocket streaming
 *
 * Architecture Layer : Feature-Chat
 * Pattern Used       : MVVM ViewModel with StateFlow + Hilt injection
 *
 * Key Concepts:
 *   - StreamEvent tokens are debounced at 16 ms to avoid recomposition on every token.
 *   - Disconnect handling follows Requirement 2.8: the user must initiate reconnect;
 *     the ViewModel does not auto-reconnect.
 *
 * Dependencies:
 *   - SendMessageUseCase (domain)
 *   - AIStreamClient (core-ai)
 * ============================================================
 */
```

---

### Python Header Template

```python
# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : <module/service name, e.g., services>
# File    : <filename.py>
# Purpose : <one-line description of what this file does>
#
# Architecture Layer : <API Router / Service / Repository / Model / Schema / Worker / etc.>
# Pattern Used       : <Repository Pattern / FastAPI Router / Pydantic Schema / Celery Task / etc.>
#
# Key Concepts:
#   - <concept 1: explain a design decision made in this file>
#   - <concept 2: explain a non-obvious implementation choice>
#
# Dependencies:
#   - <dependency 1: library or module this file depends on>
#   - <dependency 2: why this dependency is needed>
# ============================================================
```

#### Python Example — Service

```python
# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services
# File    : ai_orchestrator.py
# Purpose : Orchestrates all LLM provider interactions for chat, RAG, and generation
#
# Architecture Layer : Service
# Pattern Used       : Provider Adapter Pattern (BaseLLMClient abstract class)
#
# Key Concepts:
#   - All LLM calls pass through this single class so features never depend on a
#     specific provider — switching providers requires no caller changes.
#   - Prompt injection detection runs before any provider call; on detection,
#     HTTP 400 is returned and the input is never forwarded.
#
# Dependencies:
#   - BaseLLMClient (llm_clients.py) — concrete provider adapters
#   - MemoryService — injects top-3 user memories into every prompt
#   - SafetyService — prompt injection detection and input sanitisation
# ============================================================
```

#### Python Example — FastAPI Router

```python
# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/auth
# File    : router.py
# Purpose : FastAPI router for all /auth/* endpoints
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI APIRouter with Depends for dependency injection
#
# Key Concepts:
#   - Routers are thin HTTP adapters — all business logic lives in AuthService.
#   - Rate limiting is enforced via RateLimitMiddleware at the app level, not here.
#
# Dependencies:
#   - AuthService (services/auth_service.py)
#   - Pydantic schemas (schemas/auth.py)
# ============================================================
```

### Header Rules

| Rule | Detail |
|------|--------|
| Required on | All files in `src/main/kotlin/` and `backend/app/` |
| Excluded from | Test files, `__init__.py`, generated artifacts, migration files |
| Must be accurate | Purpose, layer, and pattern must reflect the file's actual content |
| Must be updated | When a file's purpose or architecture layer changes |

---

## Kotlin Style

### Tools

| Tool | Configuration | Tolerance |
|------|--------------|-----------|
| ktlint | `.editorconfig` | Zero errors — CI blocks on any violation |
| Detekt | `config/detekt/detekt.yml` | Zero errors — CI blocks on any violation |

### Naming Conventions

| Construct | Convention | Example |
|-----------|-----------|---------|
| Classes / Interfaces | PascalCase | `ChatDetailViewModel` |
| Functions | camelCase | `sendMessage()` |
| Properties | camelCase | `isLoading` |
| Constants | SCREAMING_SNAKE_CASE | `MAX_RETRY_ATTEMPTS` |
| Packages | lowercase, dot-separated | `com.aiassistant.feature.chat` |
| Files | PascalCase matching class | `ChatDetailViewModel.kt` |
| Test files | `<Subject>Test.kt` | `LoginUseCaseTest.kt` |

### Compose Naming

- Composable functions: PascalCase (treated as types) → `ChatBubble()`, `ErrorBanner()`
- State objects: `<Feature>UiState` data class → `ChatDetailUiState`
- Preview functions: `@Preview` with `Preview` suffix → `ChatBubblePreview()`

---

## Clean Architecture Dependency Rules

The dependency direction is strictly unidirectional and enforced by Gradle module structure:

```
feature-* → domain ← data
                ↑
           core-common
```

**Forbidden dependencies:**
- `domain` must NOT depend on Android, Room, Retrofit, or any third-party library
- `feature-*` must NOT depend on `data` directly
- `core-*` modules must NOT depend on `feature-*` or `data`
- `app` module may depend on all modules (it is the composition root)

Violations are detected by Detekt's import pattern checks in CI.

---

## Hilt DI Conventions

Each module that provides dependencies has a `di/` package with a Hilt `@Module`:

```
core-network/src/main/kotlin/.../di/NetworkModule.kt
core-database/src/main/kotlin/.../di/DatabaseModule.kt
feature-chat/src/main/kotlin/.../di/ChatModule.kt
data/src/main/kotlin/.../di/AuthDataModule.kt
```

| Scope | Annotation | Use for |
|-------|-----------|---------|
| Application | `@Singleton` | Repositories, OkHttp, Retrofit, Room |
| ViewModel | `@HiltViewModel` | All ViewModels |
| Activity | `@ActivityScoped` | Activity-level helpers (rare) |

Repository implementations are bound with `@Binds`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class AuthDataModule {
    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository
}
```

---

## Coroutines and Flow Patterns

### ViewModel Pattern

```kotlin
@HiltViewModel
class ChatDetailViewModel @Inject constructor(
    private val sendMessageUseCase: SendMessageUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatDetailUiState())
    val uiState: StateFlow<ChatDetailUiState> = _uiState.asStateFlow()

    fun sendMessage(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            sendMessageUseCase(text)
                .onSuccess { /* update state */ }
                .onFailure { /* handle error */ }
            _uiState.update { it.copy(isLoading = false) }
        }
    }
}
```

### UI Collection

Always use `repeatOnLifecycle(STARTED)` to avoid collecting when the UI is backgrounded:

```kotlin
@Composable
fun ChatDetailScreen(viewModel: ChatDetailViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // ...
}
```

### Dispatcher Conventions

| Operation | Dispatcher | Why |
|-----------|-----------|-----|
| CPU-bound logic | `Dispatchers.Default` | Avoids blocking IO dispatcher |
| Database (Room) | `Dispatchers.IO` | Blocking SQLite operations |
| Network (Retrofit) | Retrofit handles internally | |
| UI updates | `Dispatchers.Main` (via StateFlow collect) | |

---

## UiState Convention

Every feature screen has a UiState data class:

```kotlin
data class LoginUiState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isLoggedIn: Boolean = false
)
```

ViewModels expose `StateFlow<XUiState>`. Screens observe with `collectAsStateWithLifecycle()`.
**Never expose `MutableStateFlow` directly from a ViewModel.**

---

## Python Style

### Tools

| Tool | Tolerance |
|------|-----------|
| ruff | Zero errors |
| mypy (strict mode) | Zero errors |

### Type Annotations

All Python code must be fully type-annotated:

```python
async def login(self, email: str, password: str) -> AuthTokens: ...
```

### Pydantic Models

Use Pydantic v2 `model_config` with `from_attributes=True` for ORM-compatible schemas:

```python
class ConversationResponse(BaseModel):
    id: UUID
    title: str
    model_config = ConfigDict(from_attributes=True)
```

### Async Conventions

All database and I/O operations are `async`. Synchronous blocking calls are forbidden in
FastAPI request handlers and Celery async tasks.

---

## Code Review Checklist

Before merging any PR:

- [ ] ktlint / ruff passes (zero violations)
- [ ] Detekt / mypy passes (zero violations)
- [ ] All new functions have unit tests
- [ ] Property-based tests added for new invariants
- [ ] Educational Headers present on all new source files
- [ ] No `TODO` or `FIXME` without a linked issue number
- [ ] No hardcoded secrets or API keys
- [ ] Clean Architecture dependency rules respected
- [ ] No direct Room DAO access from feature modules
- [ ] `MutableStateFlow` not exposed from ViewModels
- [ ] All new endpoints covered in `API_SPECIFICATION.md`
