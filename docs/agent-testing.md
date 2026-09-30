# Agent Testing Guide

## Test Suite Overview

| Module | Framework | Tests | Location |
|--------|-----------|-------|----------|
| `:domain` | JUnit 4 (via vintage) + Kotest | 795 | `domain/src/test/` |
| `:data` | JUnit 4/5 + Kotest + MockK + Turbine | 747 | `data/src/test/` |
| Backend | pytest + asyncio | 279 | `backend/tests/unit/agents/` |

## Running the Test Suites

### Android Domain Tests
```bash
./gradlew :domain:testReleaseUnitTest --rerun-tasks
```

### Android Data Tests
```bash
./gradlew :data:testReleaseUnitTest --rerun-tasks
```

### Android Build Verification
```bash
# KSP seed pass (required first to avoid FileAlreadyExistsException)
./gradlew :data:kspDebugKotlin
# Release compile
./gradlew :data:compileReleaseKotlin :domain:compileReleaseKotlin
```

### Backend Tests
```bash
cd backend
./venv311/Scripts/python.exe -m pytest tests/unit/agents/ -q
```

### Backend Lint + Type Check
```bash
./venv311/Scripts/python.exe -m ruff check app/agents/
./venv311/Scripts/python.exe -m mypy app/agents/ --ignore-missing-imports
```

## Key Test Files by Phase

### Phase 3–5 (Orchestrator, Planner, Registry)
- `AgentOrchestratorTest.kt` — routing failures, handoffs, timeout, cancellation
- `AgentPlannerTest.kt` — single/multi-step plans, limit violations
- `AgentRegistryTest.kt` — register, unregister, capability lookup

### Phase 6 (Agents: Web, Image, Voice)
- `WebAgentTest.kt` — 15 tests: provider not configured, blank query, success, citations
- `ImageAgentTest.kt` — 13 tests: missing image, OCR success, vision, remote errors
- `VoiceAgentTest.kt` — 16 tests: STT→LLM→TTS pipeline, cancellation

### Phase 7 (On-Device)
- `OnDeviceAgentTest.kt` — 33 tests: LOCAL_ONLY, RAM exhaustion, no-network, cancellation

### Phase 8 (Memory, Multi-Agent)
- `AgentMemoryTest.kt` — 40 tests: all memory scopes, assembler, safety limits
- `MultiAgentWorkflowTest.kt` — 23 tests: 4-step chain, handoff events, recursion protection

### Phase 9 (Agent UX, Observability)
- `AgentModeTest.kt` — 50 tests: all modes, capabilities, routing hints, status labels
- `AgentStreamingTest.kt` — 20 tests: all 15 AgentEvent types, streaming sequences

### Phase 10 (Security)
- `AgentAuthGuardTest.kt` — authentication validation, sentinel rejection

## Testing Patterns

### Standard ViewModel pattern (feature-chat)
```kotlin
class MyAgentTest : DescribeSpec({
    val testDispatcher = UnconfinedTestDispatcher()
    beforeSpec { Dispatchers.setMain(testDispatcher) }
    afterSpec { Dispatchers.resetMain() }

    describe("MyAgent") {
        it("success path") {
            val agent = MyAgent(mockProvider)
            every { mockProvider.isConfigured } returns true
            coEvery { mockProvider.doWork(any()) } returns Result.success("result")

            val events = agent.execute(req(), exec()).toList()
            events.filterIsInstance<AgentEvent.Completed>()
                .first().result.status shouldBe AgentStatus.COMPLETED
        }
    }
})
```

### Standard domain test pattern
```kotlin
class AgentXTest {
    @Test
    fun `success emits Completed`() = runTest {
        val agent = AgentX(mockk(relaxed = true))
        val events = agent.execute(req(), exec()).toList()
        events.filterIsInstance<AgentEvent.Completed>().size shouldBe 1
    }
}
```

### Testing concurrency limits
```kotlin
@Test
fun `concurrency limit emits CONCURRENCY_LIMIT_EXCEEDED`() = runTest {
    val orchestrator = DefaultAgentOrchestrator(
        registry = reg,
        router = DefaultAgentRouter(),
        planner = DefaultAgentPlanner(),
        maxConcurrentPerUser = 1,   // ← set limit to 1 for test
    )
    // Launch two concurrent requests from same user
    val job1 = launch { orchestrator.execute(req("u1")).toList() }
    val events2 = orchestrator.execute(req("u1")).toList()
    job1.join()

    events2.filterIsInstance<AgentEvent.Failed>()
        .first().result.error?.code shouldBe "CONCURRENCY_LIMIT_EXCEEDED"
}
```

### Backend agent test pattern
```python
@pytest.mark.asyncio
async def test_web_agent_success():
    provider = MagicMock()
    provider.is_configured = True
    provider.search = AsyncMock(return_value=[
        {"title": "Result", "url": "https://example.com", "snippet": "desc"}
    ])
    agent = WebAgent(provider=provider)
    req = AgentRequest(user_id="u1", input="query")
    exec_ = AgentExecution(request=req, agent_name="web-search")
    events = [e async for e in agent.execute(req, exec_)]
    assert any(isinstance(e, AgentCompletedEvent) for e in events)
```

## Security Test Checklist

When adding a new agent or modifying auth/authz code:

- [ ] Test with `userId = ""` → expect `UNAUTHENTICATED`
- [ ] Test with `userId = "anonymous"` → expect `UNAUTHENTICATED` (Phase 10)
- [ ] Test with valid userId → expect success
- [ ] Test tool permission denied → expect `PERMISSION_DENIED`
- [ ] Test tool timeout → expect `TOOL_TIMEOUT`
- [ ] Test multi-user isolation: user A cannot access user B's documents
- [ ] Test JWT absent in ChatAgent → expect `UNAUTHENTICATED`
- [ ] Test LOCAL_ONLY when model not ready → expect `LOCAL_ONLY_UNAVAILABLE` (no silent cloud fallback)
- [ ] Test concurrency limit → 6th concurrent request from same user → `CONCURRENCY_LIMIT_EXCEEDED`
- [ ] Verify no prompt/response content in `AgentObservabilityRecord.toMetadata()`
