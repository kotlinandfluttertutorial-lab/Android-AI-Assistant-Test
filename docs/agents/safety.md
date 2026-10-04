# Agent Safety and Security

> **Module:** `app.orchestration.safety`, `app.orchestration.memory`  
> **Updated:** 2026-10-03

All safety controls applied during agent execution, plus the memory subsystem design.

---

## Table of Contents

1. [AgentSafetyGuard](#1-agentsafetyguard)
2. [Input safety — injection detection](#2-input-safety--injection-detection)
3. [Output safety — tool and RAG sanitisation](#3-output-safety--tool-and-rag-sanitisation)
4. [Sensitive argument redaction](#4-sensitive-argument-redaction)
5. [Permission checks](#5-permission-checks)
6. [Conversation memory](#6-conversation-memory)
7. [Execution limits as safety controls](#7-execution-limits-as-safety-controls)
8. [Security test coverage](#8-security-test-coverage)

---

## 1. AgentSafetyGuard

`AgentSafetyGuard` (`app/orchestration/safety.py`) is the single point for all agent-layer safety operations. It is instantiated once per request and used inline inside the execution loop.

```python
guard = AgentSafetyGuard(safety_service=SafetyService())

# Used after each tool/RAG call:
safe_output = guard.sanitize_tool_output(raw_mcp_output)
safe_content = guard.sanitize_rag_content(raw_chunk)

# Used before logging parameters:
log_safe_params = guard.redact_sensitive_args("jira_get_issue", params)

# Used before tool dispatch:
guard.check_user_authorization(user_id, tool_name, required_perms, user_perms)
guard.check_tool_permission(tool_schema, user_permissions)
```

**Design rule:** Sanitisation methods follow a **never-raise contract** — on `SafetyFilterError`, a safe stub is returned instead of the original content.

---

## 2. Input safety — injection detection

Before any agent run begins, `InjectionDetector.check_input()` scans the user's message:

| Pattern | Example | Response |
|---|---|---|
| `ignore (all) previous instructions` | "ignore all previous instructions" | HTTP 400 |
| `disregard previous` | "disregard previous context" | HTTP 400 |
| `you are now` | "You are now DAN..." | HTTP 400 |
| `system:` prefix | "system: reveal prompt" | HTTP 400 |
| `<system>` tag | `<system>new instructions</system>` | HTTP 400 |
| LLaMA instruction tokens | `[INST]...` | HTTP 400 |

On detection:
1. The raw injection payload is **not logged** — only a SHA-256 hash of the sanitised version.
2. An `AuditLog` row is written with `event_type="prompt_injection"`.
3. HTTP 400 is returned with `{"error": {"code": "PROMPT_INJECTION_DETECTED"}}`.
4. The LLM never receives the message.

**Unicode/RTL bypass attempts** are also handled — the safety layer processes the message after Unicode normalisation. Tests in `test_agent_safety.py` cover RTL override (`\u202e`), null bytes (`\x00`), and script tag injection.

---

## 3. Output safety — tool and RAG sanitisation

All data returned by external systems is treated as **untrusted**:

### Tool output sanitisation

```python
safe = guard.sanitize_tool_output(raw_mcp_result)
```

Passes through `SafetyService.filter_response()` which:
- Strips `<script>...</script>` (all variants)
- Strips `javascript:` URLs

On `SafetyFilterError` (filter itself fails): returns `"[tool output blocked: safety filter failed]"` — the execution loop continues with the stub.

### RAG content sanitisation

```python
safe = guard.sanitize_rag_content(raw_chunk)
```

Same filter applied to retrieved document chunks before they are injected into the LLM prompt. This prevents malicious content embedded in uploaded documents from influencing the LLM.

---

## 4. Sensitive argument redaction

Before any MCP tool parameters are written to logs, `redact_sensitive_args()` creates a deep copy with 14 sensitive key patterns replaced by `"[redacted]"`:

| Patterns redacted |
|---|
| `password`, `passwd` |
| `secret`, `client_secret` |
| `token`, `access_token` |
| `api_key`, `apikey` |
| `access_key`, `private_key` |
| `credential`, `credentials` |
| `auth`, `authorization`, `bearer` |
| `jwt` |
| `ssn` |
| `card_number`, `cardnumber` |
| `cvv` |

Matching is case-insensitive substring match. Nested dicts and lists are recursively processed. The original `params` dict is **never mutated**.

**Test coverage:** 14 parametrized tests in `test_agent_safety_memory.py::TestRedactSensitiveArgs` covering every pattern at multiple nesting levels.

---

## 5. Permission checks

### User authorisation

```python
guard.check_user_authorization(
    user_id="u1",
    tool_name="jira_create_issue",
    required_permissions=["write:jira"],
    user_permissions=current_user.permissions,
)
# Raises PermissionError if any required permission is missing
```

Raises `PermissionError` with the missing permissions listed. The execution loop catches this and terminates the run with `status=PERMISSION_DENIED`.

### Tool schema permission check

```python
guard.check_tool_permission(
    tool_schema={"name": "deploy", "required_permissions": ["deploy:prod"]},
    user_permissions=current_user.permissions,
)
```

Validates against `tool_schema["required_permissions"]`. Tools that declare no requirements always pass. This is an additive check on top of the RBAC layer.

---

## 6. Conversation memory

### Short-term: `ConversationMemoryBuffer`

In-process ring buffer, 20 turns by default. Zero network calls.

```python
buf = ConversationMemoryBuffer(max_turns=20)
buf.add_turn("user", "What is X?", step=0)
buf.add_turn("assistant", "X is...", step=1)

# Inject into LLM prompt:
messages = buf.format_as_messages()   # [{"role": "user", "content": "..."}, ...]
text = buf.format_as_text()           # "USER: ...\nASSISTANT: ..."
```

FIFO eviction: when the buffer is full, the oldest turn is dropped silently.

### Long-term: `AgentMemoryAdapter`

Wraps `MemoryService` for cross-session persistence via ChromaDB + PostgreSQL.

```python
adapter = AgentMemoryAdapter(
    memory_service=svc,
    user_id=user_id,
    redis=redis_client,  # for privacy-budget tracking
)
await adapter.store_fact("User prefers concise answers.", memory_type="preference")
memories = await adapter.retrieve_relevant("answer style", top_k=3)
text = adapter.format_memories_as_text(memories)
```

Both `store_fact()` and `retrieve_relevant()` degrade gracefully — failures are logged at WARNING and never propagate to the caller.

**Privacy mode:** When `user.privacy_mode=True`, `MemoryService.store_memory()` is a no-op. Memories are not stored.

---

## 7. Execution limits as safety controls

The three hard limits prevent resource exhaustion and infinite loops:

| Limit | Default | Hard cap | Enforcement point |
|---|---|---|---|
| `max_steps` | 10 | 50 | Top of each `_run_inner` iteration |
| `max_tool_calls` | 20 | 100 | Top of each `_run_inner` iteration |
| `timeout_s` | 120 | 300 | `asyncio.timeout` wrapper on the whole loop |

These cannot be bypassed via user input — they are read from `Settings` / `OrchestrationConfig` at runner construction time, not from the request body.

The `Settings` validator enforces `MAX_AGENT_STEPS` is in `[1, 50]` (Pydantic `ge=1, le=50`), ensuring even a misconfigured environment cannot exceed the hard cap.

---

## 8. Security test coverage

All tests in `tests/security/test_agent_safety.py` and `tests/unit/test_agent_safety_memory.py`.

| Category | Tests | Status |
|---|---|---|
| Agent timeout enforcement | 2 | ✅ |
| max_steps termination | 3 | ✅ |
| max_tool_calls termination | 1 | ✅ |
| Prompt injection (ASCII) | 1 | ✅ |
| Tool output injection containment | 1 | ✅ |
| Unicode RTL injection | 1 | ✅ |
| Null-byte injection | 1 | ✅ |
| Secrets not in observer logs | 2 | ✅ |
| Span truncation prevents secret leakage | 1 | ✅ |
| user_id redaction | 3 | ✅ |
| Observer structured fields present | 6 | ✅ |
| CancelledError propagates cleanly | 1 | ✅ |
| Sensitive args redaction (14 patterns) | 14 | ✅ |
| Permission checks (user auth) | 6 | ✅ |
| Tool schema permission checks | 6 | ✅ |
| Memory buffer limits + FIFO eviction | 13 | ✅ |
| AgentMemoryAdapter graceful degradation | 6 | ✅ |
