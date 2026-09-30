# Agent Security

## Phase 10 Hardening Summary

### Authentication

Every agent request must carry an authenticated user ID. The system enforces
this at two independent layers:

**Layer 1 — AgentAuthGuard (domain, Phase 10)**

```kotlin
// domain/agent/AgentAuthGuard.kt
AgentAuthGuard.validate(request) // → Authenticated | Unauthenticated
```

Rejects: blank userId, `"anonymous"`, `"guest"`, `"unknown"`, `"placeholder"`.

**Layer 2 — DefaultAgentOrchestrator (domain, Phase 10)**

The orchestrator validates every request via `AgentAuthGuard.validate()`
before routing. Unauthenticated requests emit `AgentEvent.Failed(code=UNAUTHENTICATED)`
without reaching any agent.

**Layer 3 — ToolAgent (data)**

Android `ToolAgent` independently verifies `request.userId.trim().isBlank()`
and emits `UNAUTHENTICATED` if blank.

**Layer 4 — Backend JWT (existing)**

`get_current_user()` in `backend/app/security/dependencies.py`:
- Decodes HS256 JWT; validates signature, expiry, and required claims (`sub`, `role`, `jti`, `iat`, `exp`).
- Performs Redis JTI revocation check (gracefully degrades if Redis is unavailable).
- `user_id` is always `uuid.UUID(current_user.sub)` — never from request body.

### Authorization — Ownership Verification

**Conversations:** `ConversationRepository.get_by_id(id, user_id=current_user_id)` — Phase 10 added `user_id` parameter (optional for background workers, required for API handlers).

**Documents:** `DocumentRepository.get_by_id(id, user_id=current_user_id)` — optional `user_id` parameter enforces ownership when provided. Always pass `user_id` in API handlers.

**RAG vector search:** `search_chunks_by_embedding` joins `DocumentChunk → Document` and filters `Document.user_id = user_id`. User isolation is enforced at the database layer.

**Document status updates:** `DocumentRepository.update_status()` now accepts optional `user_id` — background Celery workers may omit it but must not expose results in API responses.

### Android userId="anonymous" Prevention

**Before Phase 10:** `AgentGateway.resolveUserId()` fell back to `"anonymous"` which passes `ToolAgent`'s `isBlank()` check.

**After Phase 10:** Falls back to `"__unauthenticated__"` which `AgentAuthGuard.isAuthenticated()` rejects. `assertAuthenticatedUserId()` throws `IllegalStateException` at the gateway before the request reaches the orchestrator.

Feature modules must supply an authenticated `AgentContext.userId` (from `SecureStorage.getUserId()`).

### ChatAgent JWT Fail-Fast

**Before Phase 10:** `secureStorage.getJwt() ?: ""` — proceeded with empty token.

**After Phase 10:**
```kotlin
val jwt = secureStorage?.getJwt()
if (jwt.isNullOrBlank()) {
    emit(AgentEvent.Failed(..., code = "UNAUTHENTICATED"))
    return@flow
}
```

### Concurrency Limits

**DefaultAgentOrchestrator** enforces `maxConcurrentPerUser = 5` (default). Per-user active execution counts are tracked in a `ConcurrentHashMap<String, AtomicInteger>`. Excess requests emit `AgentEvent.Failed(code=CONCURRENCY_LIMIT_EXCEEDED)`.

### Tool Security

All 7 security checks in `ToolAgent.execute()`:

1. **Authentication** — userId must be authenticated.
2. **Tool name present** — `metadata["tool_name"]` non-blank.
3. **Tool exists** — `ToolRegistry.get()` throws `ToolNotFoundException`.
4. **Permission check** — caller's declared permissions ⊇ tool's required permissions.
5. **Input validation** — `tool.validate(args)` before `execute()`.
6. **Confirmation gate** — write tools require `confirmed=true`.
7. **Timeout** — `withTimeoutOrNull(schema.timeoutMs)`.

**Forbidden in tool implementations:**
- `ProcessBuilder` / `Runtime.exec()` / shell commands
- Arbitrary filesystem access
- `System.getenv()` environment access
- Direct database access outside the user's own data
- Stack traces in error output
- API keys or secrets in any field

### Data Isolation

| Resource | Isolation mechanism |
|----------|---------------------|
| Conversations | `WHERE user_id = ?` in list queries + ownership check in get_by_id |
| Documents | `WHERE user_id = ?` in list + optional user_id in get_by_id |
| Document chunks | Join to parent Document with `Document.user_id = user_id` filter |
| RAG search | `search_chunks_by_embedding` always scoped to caller's user_id |
| Agent executions | FK cascade on `users.id`; `agent_sessions/executions` store user_id |
| Memory | Retrieved memories scoped by userId; LongTermMemory requires approvedByUser=true |

### What Is Never Logged

The `AgentExecutionLogger` and `AgentObservabilityRecord` are designed with
privacy-first field exclusion:

**Never logged:**
- Prompt text / user message content
- LLM response content
- JWT tokens
- API keys or passwords
- Tool parameters or output
- User PII (emails, phone numbers, names)

**Always logged (structured):**
- `request_id`, `execution_id`, `agent`, `model`, `status`, `duration_ms`
- `tool` name (not params), `error_code` (not stack trace)
- Token counts (not content)

### Sensitive Information in Memory

`LongTermMemory` requires explicit user approval (`approvedByUser = true`).
No agent automatically persists user message content to long-term memory.
`isPrivacyMode = true` on `AgentContext` suppresses memory persistence at every layer.

### Known Remaining Risks

1. **MCPBroker no self-auth** — trusts callers; no independent permission gate.
   Mitigation: `ToolAgent` enforces all checks before calling `MCPBroker`.

2. **Redis JTI revocation degradation** — if Redis is unavailable, revoked JWTs
   are accepted. Mitigation: short JWT expiry (15 min), monitoring alert on Redis down.

3. **Gradle dependency enforcement** — `feature-chat MUST NOT depend on :data`
   is enforced at Gradle `resolutionStrategy` level but only catches runtime
   class-level imports, not transitive API exposure.
