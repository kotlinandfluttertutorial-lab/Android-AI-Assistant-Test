# Testing Strategy
## Android AI Assistant — Enterprise Edition

---

## Overview

The project requires a minimum test coverage of **70%** on both Android and backend codebases.
All new features include both unit tests and property-based tests where applicable. CI gates
block merges when coverage drops below the threshold.

---

## Android Testing Stack

| Layer | Framework | Purpose |
|-------|-----------|---------|
| Unit tests | JUnit5 + MockK | Use cases, ViewModels, mappers, utilities |
| Property-based tests | Kotest PropTest | Invariants on domain logic and data transformations |
| Robolectric tests | Robolectric 4.x | Android-specific unit tests without emulator |
| Compose UI tests | Compose Test + Espresso | Screen rendering, interactions, navigation |
| Integration tests | JUnit5 + Room in-memory | Repository + DAO integration |

---

## Backend Testing Stack

| Layer | Framework | Purpose |
|-------|-----------|---------|
| Unit tests | pytest | Services, repositories, security, utilities |
| Property-based tests | Hypothesis | Invariants on business logic and data schemas |
| API integration tests | pytest + HTTPX | Endpoint request/response contracts |
| Database tests | pytest + asyncpg test DB | Repository + migration testing |

---

## Coverage Gates (CI)

```yaml
# android-ci.yml
- name: Check coverage
  run: ./gradlew koverVerify  # fails if combined domain+data coverage < 70%

# backend-ci.yml
- name: Check coverage
  run: pytest --cov=app --cov-fail-under=70
```

Coverage is measured on every pull request and the `main` branch. Merges to `main` are blocked
if coverage drops below 70%.

---

## Unit Tests

### Android Unit Test Conventions

```kotlin
@ExtendWith(MockKExtension::class)
class LoginUseCaseTest {
    @MockK lateinit var authRepository: AuthRepository
    private lateinit var useCase: LoginUseCase

    @BeforeEach
    fun setUp() { useCase = LoginUseCase(authRepository) }

    @Test
    fun `returns tokens on valid credentials`() = runTest {
        coEvery { authRepository.login(any(), any()) } returns Result.success(fakeTokens)
        val result = useCase("user@example.com", "ValidPass123!")
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `rejects password shorter than 12 characters`() = runTest {
        val result = useCase("user@example.com", "short")
        assertThat(result.isFailure).isTrue()
    }
}
```

### Backend Unit Test Conventions

```python
@pytest.mark.asyncio
async def test_login_returns_tokens_on_valid_credentials(mock_user_repo, mock_jwt):
    service = AuthService(mock_user_repo, mock_jwt)
    result = await service.login("user@example.com", "ValidPass123!")
    assert result.access_token is not None
    assert result.refresh_token is not None
```

---

## Property-Based Tests

All 30 named properties from the design document:

### Property 1 — JWT Round-Trip
**Validates: Requirement 1.2**
For any valid credentials, encoding a JWT then decoding it returns the original subject and role.

### Property 2 — Refresh Token Rotation
**Validates: Requirement 1.4**
For any valid refresh token, using it once produces a new token, and the original is then revoked.

### Property 3 — Password Hash Non-Reversibility
**Validates: Requirement 9.3**
For any password `p`, `hash(p) != p` and `verify(p, hash(p)) == True` and `hash(p) != hash(p+"x")`.

### Property 4 — Rate Limit Counter Monotonicity
**Validates: Requirement 9.9**
For any sequence of N requests within one minute from one user, the count never decreases.
On the 61st request, HTTP 429 is returned.

### Property 5 — Chunking Coverage
**Validates: Requirement 4.3**
For any document text `T` > 512 tokens, splitting into 512-token chunks with 64-token overlap
produces chunks where every token in `T` appears in at least one chunk.

### Property 6 — Chunk Overlap Invariant
**Validates: Requirement 4.3**
For any adjacent chunks `C_i` and `C_{i+1}`, they share exactly 64 tokens.

### Property 7 — User Isolation in Vector Store
**Validates: Requirement 4.5**
Embeddings ingested by user U1 are never returned in queries by user U2.

### Property 8 — Memory Top-K Retrieval
**Validates: Requirement 7.2**
For N ≥ 3 memories, retrieval always returns exactly 3. For N < 3, returns all N.

### Property 9 — RAG Round-Trip
**Validates: Requirement 4.9**
For any document containing phrase `P`, querying `P` after ingestion returns a result referencing
the source document.

### Property 10 — FTS Search Coverage
**Validates: Requirement 11.2**
For any message `M` containing word `W`, searching `W` returns a conversation containing `M`.

### Property 11 — Paging Completeness
**Validates: Requirement 11.1**
Fetching all pages (page_size=20) returns all conversations with no duplicates.

### Property 12 — Pagination Non-Overlap
**Validates: Requirement 11.1**
For any two consecutive pages, no item appears in both.

### Property 13 — Token Count Positivity
**Validates: Requirement 2.9**
For any completed message, `input_tokens > 0` and `output_tokens ≥ 0`.

### Property 14 — Cost Calculation Consistency
**Validates: Requirement 3.6**
For any message, `cost = input_tokens × cost_per_input + output_tokens × cost_per_output`.

### Property 15 — Soft Delete Visibility
**Validates: Requirement 11.4**
After soft-deleting a conversation, it does not appear in the default list. It remains accessible
via direct ID lookup for admin purposes.

### Property 16 — Prompt Injection Detection Completeness
**Validates: Requirement 9.6**
For any string containing a known injection pattern, `detect_prompt_injection` returns `True`.

### Property 17 — Note Summary Length
**Validates: Requirement 13.2**
For any note of any length, the AI-generated summary never exceeds 150 words.

### Property 18 — Cover Letter Length
**Validates: Requirement 14.2**
For any combination of job description and resume data, the cover letter never exceeds 400 words.

### Property 19 — Offline Queue Order Preservation
**Validates: Requirement 10.2**
For any sequence of N queued messages, they are delivered to the backend in the same order queued.

### Property 20 — WorkManager Retry Limit
**Validates: Requirement 10.6**
For any message that fails delivery, exactly 3 retry attempts are made before marking `failed`.

### Property 21 — Biometric Data Locality
**Validates: Requirement 1.7**
The biometric authentication flow never transmits biometric data outside the device.

### Property 22 — RBAC Enforcement
**Validates: Requirement 9.2**
For any admin-only endpoint, all requests with `user` or `premium` role return HTTP 403.

### Property 23 — Chunk Size Upper Bound
**Validates: Requirement 4.3**
For any chunk produced by the RAG chunker, `token_count ≤ max_chunk_size`.

### Property 24 — Conversation Message Ordering
**Validates: Requirement 2.3**
For any conversation with N messages, retrieval always returns them ordered by `created_at` ASC.

### Property 25 — TodoItem Due Date Filter
**Validates: Requirement 29.1**
For any set of TodoItems, filtering by `due_before=D` returns only items where `due_date ≤ D`.

### Property 26 — Habit Streak Monotonicity
**Validates: Requirement 29.7**
For any habit, logging an entry on a new consecutive day increases the streak by exactly 1.

### Property 27 — WebSocket Reconnection Backoff
**Validates: Requirement 26.4**
For N ∈ [1, 5] disconnect events, reconnection intervals are 1 s → 2 s → 4 s → 8 s → 16 s
capped at 30 s; total attempts ≤ 5.

### Property 28 — API Key Encryption Round-Trip
**Validates: Requirement 9.10**
For any API key string `K`, `decrypt(encrypt(K)) == K`.

### Property 29 — Productivity Sync Conflict Resolution
**Validates: Requirement 29.9**
For any sequence of local and remote updates with `updated_at` timestamps, the authoritative
version is always the one with the latest `updated_at`.

### Property 30 — MCP Audit Log Completeness
**Validates: Requirement 8.7**
For any MCP tool invocation (success or failure), exactly one audit log entry is created with
the correct `user_id`, `tool_name`, `timestamp`, and `status`.

---

## Compose UI Tests

```kotlin
@HiltAndroidTest
class LoginScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginScreen_displaysAllRequiredFields() {
        composeRule.onNodeWithTag("email_field").assertIsDisplayed()
        composeRule.onNodeWithTag("password_field").assertIsDisplayed()
        composeRule.onNodeWithTag("login_button").assertIsDisplayed()
    }

    @Test
    fun loginScreen_showsValidationErrorOnShortPassword() {
        composeRule.onNodeWithTag("email_field").performTextInput("user@example.com")
        composeRule.onNodeWithTag("password_field").performTextInput("short")
        composeRule.onNodeWithTag("login_button").performClick()
        composeRule.onNodeWithText("Password must be at least 12 characters").assertIsDisplayed()
    }
}
```

---

## Integration Tests

### Room Integration Test

```kotlin
@RunWith(AndroidJUnit4::class)
class ConversationDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: ConversationDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        dao = db.conversationDao()
    }

    @Test
    fun insertAndRetrieve_returnsCorrectConversation() = runTest {
        dao.insert(fakeConversation)
        val result = dao.getById(fakeConversation.id).first()
        assertThat(result?.title).isEqualTo(fakeConversation.title)
    }

    @Test
    fun deleteConversation_cascadesMessages() = runTest {
        dao.insert(fakeConversation)
        messageDao.insert(fakeMessage.copy(conversationId = fakeConversation.id))
        dao.delete(fakeConversation.id)
        val messages = messageDao.getByConversationId(fakeConversation.id).first()
        assertThat(messages).isEmpty()
    }
}
```

### Backend API Integration Test

```python
@pytest.mark.asyncio
async def test_create_conversation_returns_201(auth_client):
    response = await auth_client.post(
        "/api/v1/conversations",
        json={"title": "Integration Test Chat", "provider": "openai"}
    )
    assert response.status_code == 201
    assert response.json()["title"] == "Integration Test Chat"
```

---

## CI Pipeline Summary

### `android-ci.yml`

1. Checkout + setup JDK 17
2. `./gradlew ktlintCheck` — style (zero tolerance)
3. `./gradlew detekt` — static analysis (zero tolerance)
4. `./gradlew testDebugUnitTest` — unit + property tests
5. `./gradlew koverVerify` — coverage gate ≥ 70%
6. Upload test reports as CI artifacts

### `backend-ci.yml`

1. Checkout + setup Python 3.11
2. Spin up PostgreSQL 15 and Redis 7 services
3. `ruff check .` — linting (zero tolerance)
4. `mypy app/` — type checking (zero tolerance)
5. `pytest --cov=app --cov-fail-under=70` — tests + coverage gate
6. Upload coverage to Codecov
