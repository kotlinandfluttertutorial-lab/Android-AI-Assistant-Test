# Testing Strategy
## Android AI Assistant — Enterprise Edition

---

## Overview

The project requires a minimum test coverage of **70%** on both Android and backend codebases.
All new features must include both unit tests and property-based tests where applicable.
CI gates block merges when coverage drops below the threshold.

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
  run: ./gradlew koverVerify  # fails if < 70%

# backend-ci.yml
- name: Check coverage
  run: pytest --cov=app --cov-fail-under=70
```

Coverage is measured on the `main` branch and every pull request. Merges to `main` are blocked
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
        val result = useCase("user@example.com", "password123!")
        assertThat(result.isSuccess).isTrue()
    }
}
```

### Backend Unit Test Conventions

```python
@pytest.mark.asyncio
async def test_login_returns_tokens_on_valid_credentials(mock_user_repo, mock_jwt):
    service = AuthService(mock_user_repo, mock_jwt)
    result = await service.login("user@example.com", "password123!")
    assert result.access_token is not None
```

---

## Property-Based Tests

All 30 named properties from the design document are listed below.

### Property 1 — JWT Round-Trip
**Validates: Requirement 1.2**
For any valid user credentials, encoding a JWT and then decoding it returns the original subject and role.

### Property 2 — Refresh Token Rotation
**Validates: Requirement 1.4**
For any valid refresh token, using it once produces a new token, and the original token is then revoked.

### Property 3 — Password Hash Non-Reversibility
**Validates: Requirement 9.3**
For any password string `p`, `hash(p) != p` and `verify(p, hash(p)) == True` and `hash(p) != hash(p + "x")`.

### Property 4 — Rate Limit Counter Monotonicity
**Validates: Requirement 9.9**
For any sequence of N requests within one minute from one user, the request count never decreases.
On the 61st request within the window, HTTP 429 is returned.

### Property 5 — Chunking Coverage
**Validates: Requirement 4.3**
For any document text `T` of length > 512 tokens, splitting into 512-token chunks with 64-token
overlap produces chunks where every token in `T` appears in at least one chunk.

### Property 6 — Chunk Overlap Invariant
**Validates: Requirement 4.3**
For any adjacent chunks `C_i` and `C_{i+1}`, they share exactly 64 tokens.

### Property 7 — User Isolation in Vector Store
**Validates: Requirement 4.5**
For any two users U1 and U2, embeddings ingested by U1 are never returned in queries by U2.

### Property 8 — Memory Top-K Retrieval
**Validates: Requirement 7.2**
For any user with N ≥ 3 memories, retrieval always returns exactly 3 results.
For N < 3, retrieval returns all N memories.

### Property 9 — RAG Round-Trip
**Validates: Requirement 4.9**
For any document containing verbatim phrase `P`, querying `P` after ingestion returns a result
referencing the source document.

### Property 10 — FTS Search Coverage
**Validates: Requirement 11.2**
For any message `M` containing word `W`, searching `W` returns a conversation containing `M`.

### Property 11 — Paging Completeness
**Validates: Requirement 11.1**
Fetching all pages of conversations with page_size=20 returns all conversations with no duplicates.

### Property 12 — Pagination Non-Overlap
**Validates: Requirement 11.1**
For any two consecutive pages, no item appears in both pages.

### Property 13 — Token Count Positivity
**Validates: Requirement 2.9**
For any completed message, `input_tokens > 0` and `output_tokens ≥ 0`.

### Property 14 — Cost Calculation Consistency
**Validates: Requirement 3.6**
For any message with known token counts, `cost = input_tokens * cost_per_input + output_tokens * cost_per_output`.

### Property 15 — Soft Delete Visibility
**Validates: Requirement 11.4**
After soft-deleting a conversation, it does not appear in the default conversation list.
It remains accessible via direct ID lookup (for admin purposes).

### Property 16 — Prompt Injection Detection Completeness
**Validates: Requirement 9.6**
For any string containing a known injection pattern, `detect_prompt_injection` returns `True`.

### Property 17 — Note Summary Length
**Validates: Requirement 13.2**
For any note of any length, the AI-generated summary never exceeds 150 words.

### Property 18 — Cover Letter Length
**Validates: Requirement 14.2**
For any combination of job description and resume data, the generated cover letter never exceeds 400 words.

### Property 19 — Offline Queue Order Preservation
**Validates: Requirement 10.2**
For any sequence of N queued messages, they are delivered to the backend in the same order they were queued.

### Property 20 — WorkManager Retry Limit
**Validates: Requirement 10.6**
For any message that fails delivery, exactly 3 retry attempts are made before marking the message as `failed`.

### Property 21 — Biometric Data Locality
**Validates: Requirement 1.7**
The biometric authentication flow never transmits biometric data outside the device (verified by
inspecting outbound network calls during biometric unlock).

### Property 22 — RBAC Enforcement
**Validates: Requirement 9.2**
For any endpoint marked as `admin`-only, all requests with `user` or `premium` roles return HTTP 403.

### Property 23 — Chunk Size Upper Bound
**Validates: Requirement 4.3**
For any chunk produced by the RAG chunker, `token_count ≤ max_chunk_size`.

### Property 24 — Conversation Message Ordering
**Validates: Requirement 2.3**
For any conversation with N messages, retrieving the messages always returns them ordered by `created_at` ascending.

### Property 25 — TodoItem Due Date Filter
**Validates: Requirement 25**
For any set of TodoItems, filtering by `due_before=D` returns only items where `due_date ≤ D`.

### Property 26 — Habit Streak Monotonicity
**Validates: Requirement 28**
For any habit, logging an entry on a new consecutive day increases the streak by exactly 1.

### Property 27 — Reminder Trigger Time Ordering
**Validates: Requirement 27**
For any list of reminders, retrieving by `trigger_time` ASC returns them in chronological order.

### Property 28 — API Key Encryption Round-Trip
**Validates: Requirement 9.10**
For any API key string `K`, `decrypt(encrypt(K)) == K`.

### Property 29 — AES Encryption Non-Determinism
**Validates: Requirement 9.10**
For any API key string `K`, two calls to `encrypt(K)` produce different ciphertexts (random nonce).
Both ciphertexts decrypt correctly to `K`.

### Property 30 — MCP Audit Log Completeness
**Validates: Requirement 8.7**
For any MCP tool invocation (success or failure), exactly one audit log entry is created with the
correct `user_id`, `tool_name`, `timestamp`, and `status`.

---

## Compose UI Tests

```kotlin
@HiltAndroidTest
class LoginScreenTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun loginScreen_displaysEmailAndPasswordFields() {
        composeRule.onNodeWithTag("email_field").assertIsDisplayed()
        composeRule.onNodeWithTag("password_field").assertIsDisplayed()
        composeRule.onNodeWithTag("login_button").assertIsDisplayed()
    }

    @Test
    fun loginScreen_showsErrorOnEmptySubmit() {
        composeRule.onNodeWithTag("login_button").performClick()
        composeRule.onNodeWithText("Email is required").assertIsDisplayed()
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
}
```

### Backend API Integration Test

```python
@pytest.mark.asyncio
async def test_create_conversation_returns_201(auth_client):
    response = await auth_client.post("/conversations", json={"title": "Test", "provider": "openai"})
    assert response.status_code == 201
    assert response.json()["title"] == "Test"
```

---

## CI Pipeline

### `android-ci.yml`
1. Checkout + setup JDK 17
2. `./gradlew ktlintCheck` — style
3. `./gradlew detekt` — static analysis (zero error tolerance)
4. `./gradlew testDebugUnitTest` — unit + property tests
5. `./gradlew koverVerify` — coverage gate ≥ 70%

### `backend-ci.yml`
1. Checkout + setup Python 3.11
2. `ruff check .` — linting (zero error tolerance)
3. `mypy app/` — type checking (zero error tolerance)
4. `pytest --cov=app --cov-fail-under=70` — tests + coverage gate
