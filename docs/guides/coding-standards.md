# Coding Standards
## Android AI Assistant — Enterprise Edition

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

Violations are detected by Detekt's `NoRulesViolation` check on import patterns.

---

## Hilt DI Conventions

### Module Organisation

Each module that provides dependencies has a `di/` package with a single Hilt `@Module`:

```
core-network/src/main/kotlin/.../di/NetworkModule.kt
core-database/src/main/kotlin/.../di/DatabaseModule.kt
feature-chat/src/main/kotlin/.../di/ChatModule.kt
data/src/main/kotlin/.../di/AuthDataModule.kt
```

### Scoping

| Scope | Annotation | Use for |
|-------|-----------|---------|
| Application | `@Singleton` | Repository implementations, OkHttp, Retrofit, Room |
| ViewModel | `@HiltViewModel` | All ViewModels |
| Activity | `@ActivityScoped` | Activity-level helpers (rare) |

### Repository Binding

Repository implementations are bound using `@Binds` in `@Module` classes:

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

The `DispatcherProvider` interface (in `core-common`) enables test injection of `TestCoroutineDispatcher`.

---

## UiState Convention

Every feature screen has a sealed `UiState` data class:

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
Never expose `MutableStateFlow` directly from a ViewModel.

---

## Educational Header Format

Every generated source file (Kotlin and Python) must begin with an Educational Header block.

### Kotlin Header Template

```kotlin
/*
 * ============================================================
 * Android AI Assistant (Enterprise Edition)
 * ============================================================
 * Module     : <module name, e.g., feature-chat>
 * File       : <filename.kt>
 * Purpose    : <one-line description>
 *
 * Architecture Layer : <Domain / Data / Feature-X / Core-UI / etc.>
 * Pattern Used       : <MVVM ViewModel / Use Case / Repository / Hilt Module / etc.>
 *
 * Key Concepts:
 *   - <concept 1>
 *   - <concept 2>
 *
 * Dependencies:
 *   - <dependency 1>
 * ============================================================
 */
```

### Python Header Template

```python
# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : <module/service name>
# File    : <filename.py>
# Purpose : <one-line description>
#
# Architecture Layer : <API Router / Service / Repository / Model / Schema / etc.>
# Pattern Used       : <Repository Pattern / FastAPI Router / Pydantic Schema / etc.>
#
# Key Concepts:
#   - <concept 1>
#
# Dependencies:
#   - <dependency 1>
# ============================================================
```

### Header Rules

- Headers are added to all generated source files in `src/main/kotlin/` and `backend/app/`
- Headers are **not** added to: test files, `__init__.py`, generated build artifacts, migration files
- The header must accurately describe the file's purpose, layer, and patterns
- Headers are updated when a file's purpose or architecture layer changes

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
- [ ] No `TODO` or `FIXME` without a linked issue
- [ ] No hardcoded secrets or API keys
- [ ] Clean Architecture dependency rules respected
- [ ] No direct Room DAO access from feature modules
- [ ] StateFlow not exposed as MutableStateFlow from ViewModels
