# Phase 17 — Testing Guide

> **Learning goal:** Understand the full testing pyramid for this project —
> unit tests, integration tests, AI/RAG evaluation, and property-based tests —
> and know how to run each.
>
> **Career connection:** "How do you test AI systems?" is one of the hardest
> interview questions. This guide gives you a complete answer.

---

## 1. The Testing Pyramid

```
                    ┌──────────┐
                    │   E2E    │  ← Android instrumented tests
                   /│  Tests   │\    (slowest, most realistic)
                  / └──────────┘ \
                 /                \
        ┌───────────────────────────────┐
        │       Integration Tests        │  ← FastAPI TestClient + Docker
        └───────────────────────────────┘
       /                                   \
      /                                     \
┌─────────────────────────────────────────────────┐
│                   Unit Tests                     │  ← fastest, most isolated
│  (JUnit5/Kotest + pytest + MockK + unittest.mock) │
└─────────────────────────────────────────────────┘
```

More tests at the bottom, fewer at the top. Unit tests run in < 5 seconds.
Integration tests run in < 60 seconds. E2E tests run in < 5 minutes.

---

## 2. Android Testing

### Unit tests — JUnit 5 + Kotest + MockK

**Where:** `<module>/src/test/kotlin/`
**Runs:** On the JVM, no emulator needed. Part of every CI build.
**Framework:** Kotest `DescribeSpec` + MockK for mocking

```kotlin
// feature-rag/src/test/kotlin/.../RAGViewModelTest.kt
class RAGViewModelTest : DescribeSpec({
    describe("uploadDocument") {
        it("transitions to UploadInProgress on start") {
            val mockUseCase = mockk<UploadDocumentUseCase>()
            val vm = RAGViewModel(uploadDocumentUseCase = mockUseCase, ...)

            vm.uploadDocument(uri="content://...", fileName="test.pdf", ...)

            vm.uiState.value shouldBe RAGUiState.UploadInProgress(
                fileName = "test.pdf", isOffline = false
            )
        }
    }
})
```

**What to test in ViewModels:**
- State transitions (Loading → Content → Error)
- Error handling (network unavailable, server error)
- Business logic (file size validation, polling start/stop)
- Offline behaviour

### ViewModel tests — TestCoroutineDispatcher

```kotlin
// Use TestScope + UnconfinedTestDispatcher for deterministic coroutine control
@ExtendWith(InstantTaskExecutorExtension::class)
class DashboardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Test
    fun `refresh loads incidents and analysis in parallel`() = runTest {
        val mockIncidents = mockk<GetIncidentsUseCase>()
        coEvery { mockIncidents(any(), any(), any()) } returns ApiResult.Success(listOf())

        val vm = DashboardViewModel(
            getIncidents = mockIncidents,
            analyseErrors = mockk { coEvery { invoke(any(), any()) } returns ApiResult.NetworkUnavailable },
            dispatchers = TestDispatcherProvider(testDispatcher),
            ...
        )

        vm.uiState.test {
            awaitItem() shouldBe DashboardUiState.Loading
            awaitItem() shouldBeInstanceOf DashboardUiState.Content::class
        }
    }
}
```

### Flow testing — Turbine

Turbine is a library for testing `StateFlow` and `Flow` emissions:

```kotlin
// turbine makes Flow testing readable
vm.uiState.test {
    assertThat(awaitItem()).isInstanceOf(DashboardUiState.Loading::class.java)
    val content = awaitItem() as DashboardUiState.Content
    assertThat(content.incidents).hasSize(3)
    cancelAndConsumeRemainingEvents()
}
```

### Compose UI tests — ComposeTestRule

```kotlin
// feature-auth/src/androidTest/kotlin/.../LoginScreenTest.kt
@RunWith(AndroidJUnit4::class)
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun loginButton_disabled_when_email_empty() {
        composeTestRule.setContent {
            AppTheme { LoginScreen(viewModel = fakeViewModel) }
        }

        composeTestRule
            .onNodeWithContentDescription("Login button")
            .assertIsNotEnabled()
    }

    @Test
    fun errorMessage_displayed_on_invalid_credentials() {
        // ...
        composeTestRule
            .onNodeWithText("Invalid email or password")
            .assertIsDisplayed()
    }
}
```

---

## 3. Backend Testing

### Unit tests — pytest + unittest.mock

**Where:** `backend/tests/unit/`
**Runs:** `pytest backend/tests/unit/ -x` — no DB, no network needed
**Framework:** pytest + `unittest.mock.AsyncMock` + `pytest-asyncio`

```python
# backend/tests/unit/test_anomaly_detection_service.py
@pytest.mark.asyncio
async def test_stage1_fires_on_high_error_rate():
    db = AsyncMock()
    obs_repo = AsyncMock()
    obs_repo.count_errors_in_window.return_value = 30
    obs_repo.count_all_in_window.return_value = 100  # 30% error rate > 5% threshold

    service = AnomalyDetectionService(db)
    service._obs_repo = obs_repo
    service._inc_repo = AsyncMock()
    service._inc_repo.recent_trigger_exists.return_value = False
    service._inc_repo.create.return_value = MagicMock(id=uuid.uuid4())

    summary = await service.run_detection_cycle()

    assert summary.triggered_count >= 1
    service._inc_repo.create.assert_called_once()
```

### API integration tests — FastAPI TestClient

```python
# backend/tests/integration/test_incidents_api.py
@pytest.mark.asyncio
async def test_list_incidents_requires_auth():
    async with AsyncClient(app=app, base_url="http://test") as client:
        response = await client.get("/api/v1/incidents")
    assert response.status_code == 401

async def test_list_incidents_returns_content(auth_headers):
    async with AsyncClient(app=app, base_url="http://test") as client:
        response = await client.get("/api/v1/incidents", headers=auth_headers)
    assert response.status_code == 200
    assert "incidents" in response.json()
```

### Property-based tests — Hypothesis

```python
# backend/tests/unit/test_property_rag_chunk_coverage.py
from hypothesis import given, settings
from hypothesis import strategies as st

@given(text=st.text(min_size=10, max_size=10_000))
@settings(max_examples=100)
def test_chunk_coverage_guarantees_every_token_appears(text):
    """Property 7: every token must appear in at least one chunk."""
    service = RAGService()
    chunks = service.chunk_text(text)

    if not chunks:
        return  # empty input is fine

    # Reconstruct all unique token IDs across all chunks
    import tiktoken
    enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
    original_tokens = set(enc.encode(text))
    covered_tokens: set[int] = set()
    for chunk in chunks:
        covered_tokens.update(enc.encode(chunk.text))

    # Every original token must appear in at least one chunk
    assert original_tokens.issubset(covered_tokens)
```

---

## 4. AI/RAG Evaluation

Testing AI systems requires different techniques than testing deterministic code.

### Retrieval quality — Precision@K

```python
# Does the RAG system return the right chunks for known queries?
known_pairs = [
    ("What caused the DB connection pool exhaustion?",
     "incidents/INC-001-db-connection-pool.md"),
    ("How do I restart the service?",
     "runbooks/service-restart.md"),
]

for query, expected_source in known_pairs:
    results = await rag_service.query_knowledge_base(query=query, top_k=3)
    sources = [r["source"] for r in results]
    assert expected_source in sources, f"Expected {expected_source} in top-3 for: {query}"
```

### Faithfulness — does the answer use the context?

```python
# The answer must reference content from context_used
def check_faithfulness(answer: str, context_used: str) -> bool:
    # Extract key claims from the answer
    # Each claim should be traceable to a sentence in context_used
    # Simple heuristic: at least 70% of key noun phrases in answer
    # appear somewhere in context_used
    ...
```

### Confidence calibration

```python
# Low-confidence responses (< 0.6) must include the warning
def test_low_confidence_triggers_warning():
    response = analyse_errors_with_sparse_data(event_count=1)
    assert response.confidence < 0.6
    assert response.low_confidence_warning is not None
    assert "manual investigation" in response.low_confidence_warning.lower()
```

### Prompt regression tests

```python
# Golden set: known queries with expected output properties
GOLDEN_SET = [
    {
        "question": "Why did the API fail at 14:32?",
        "expect_tools": ["search_logs", "search_incidents"],
        "expect_citations_contain": ["incidents/"],
    },
    {
        "question": "How do I restart the backend?",
        "expect_tools": ["search_runbooks"],
        "expect_citations_contain": ["runbooks/service-restart.md"],
    },
]

@pytest.mark.asyncio
@pytest.mark.slow  # requires LLM call
async def test_devops_assistant_golden_set(db):
    service = DevOpsAssistantService(db)
    for case in GOLDEN_SET:
        response = await service.ask(case["question"])
        tools_used = [tc.tool_name for tc in response.tool_calls]
        for expected_tool in case["expect_tools"]:
            assert expected_tool in tools_used, (
                f"Expected tool '{expected_tool}' in {tools_used} "
                f"for question: {case['question']}"
            )
```

---

## 5. Running Tests

### Android

```powershell
# Unit tests (no emulator)
.\gradlew test

# Module-specific
.\gradlew :feature-rag:test
.\gradlew :feature-dashboard:test

# Instrumented UI tests (emulator/device required)
.\gradlew connectedAndroidTest
```

### Backend

```bash
# All unit tests
cd backend
pytest tests/unit/ -x --timeout=30

# All integration tests (requires Docker stack)
docker-compose up -d postgres redis chromadb
pytest tests/integration/ -x --timeout=60

# RAG-specific tests
pytest tests/unit/test_rag_service.py tests/unit/test_rag_retrieval.py -v

# AI tests (slow — require LLM calls)
pytest tests/ai/ -v -m "not slow"  # skip LLM calls in CI
pytest tests/ai/ -v -m "slow"      # run LLM tests manually

# Coverage report
pytest tests/unit/ --cov=app --cov-report=html
```

### CI pipeline

The GitHub Actions pipeline in `.github/workflows/backend-ci.yml` runs:
1. `pytest tests/unit/` — always runs, fast
2. `pytest tests/integration/` — runs with Docker services
3. Security scan (Bandit, pip-audit, Trivy)
4. Coverage check (80% minimum on changed files)

---

## 6. What NOT to Test

Some things are not worth testing:

| Don't test | Why |
|-----------|-----|
| ORM model field definitions | Changing a field name would break compilation anyway |
| Simple property access | `incident.severity` returning a string isn't business logic |
| Third-party library internals | Trust SQLAlchemy, Pydantic, etc. |
| Every single LLM response | LLMs are non-deterministic — test properties, not exact strings |
| Trivial UI rendering without interaction | Use snapshot tests sparingly |

Focus tests on: business rules, state transitions, error handling, security
boundaries, and known-failure scenarios.

---

## 7. Interview Questions

**Q1: How do you test AI/LLM systems when output is non-deterministic?**

Three approaches:

1. **Property testing** — assert properties of the output rather than exact strings.
   "The answer contains a citation" not "The answer is exactly 'X'".
   "Confidence < 0.6 when only 1 event is provided" not exact confidence value.

2. **Golden set evaluation** — maintain a set of known (query, expected_behavior)
   pairs. Check that the right tools were called, the right sources were cited,
   and the confidence gate fired when expected. Don't check exact wording.

3. **Statistical testing** — run the same query 10 times, check that the desired
   behavior occurs >= 8/10 times. This acknowledges non-determinism while still
   giving a pass/fail signal.

Temperature=0 helps — lower temperature makes the LLM more deterministic, making
test results more reproducible.

---

**Q2: What is property-based testing? When is it better than example-based testing?**

Property-based testing generates hundreds of random inputs and checks invariants
(properties that must always be true). The Hypothesis library does this in Python.

Example: "Every token in the input must appear in at least one chunk" is a property.
Instead of testing a few specific strings, Hypothesis generates 100 random strings
of varying lengths and verifies the property on all of them.

Better than example-based when:
- The input space is large (e.g., all possible strings)
- You want to find edge cases you haven't thought of
- The invariant is clearer than any specific example

Example-based is better when the logic is simple or when you're documenting
specific expected behaviors.

---

**Q3: What is the test pyramid and why does it matter?**

The test pyramid has many fast unit tests at the bottom, fewer slower integration
tests in the middle, and very few slow end-to-end tests at the top.

Unit tests: milliseconds each, run hundreds per second, mock all dependencies.
Integration tests: seconds each, use real DB/cache/external services, find wiring bugs.
E2E tests: minutes each, run the full stack, find regression that other tests miss.

If the pyramid is inverted (more E2E than unit), your test suite is slow, flaky,
and hard to debug. A single E2E test failure might require 10 minutes to reproduce.
A unit test failure is instant.

---

## Phase 17 Summary

Testing in this project covers:

| Layer | Framework | What it tests |
|-------|-----------|--------------|
| Android ViewModel | JUnit 5 + Kotest + Turbine | State transitions, coroutine flows |
| Android Compose UI | ComposeTestRule + semantics | Accessibility, interactions |
| Backend unit | pytest + AsyncMock | Business logic, error paths |
| Backend integration | FastAPI TestClient + Docker | API contracts, DB wiring |
| RAG retrieval | Custom eval + known pairs | Precision@K, source attribution |
| AI safety | Property tests | Confidence gate, PII filtering |
| AI golden set | Regression tests | Tool selection, citation presence |
| Chunk coverage | Hypothesis | Property 7 (every token in ≥1 chunk) |

Say `NEXT` to continue to **Phase 18 — Production CI/CD**.
