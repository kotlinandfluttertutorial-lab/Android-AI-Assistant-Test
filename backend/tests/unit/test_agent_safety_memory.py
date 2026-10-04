"""Unit tests for orchestration safety controls and conversation memory.

Covers:
  - AgentSafetyGuard.sanitize_tool_output: clean pass-through, harmful
    content stripped, SafetyFilterError → safe stub (never raises)
  - AgentSafetyGuard.sanitize_rag_content: same contract as tool output
  - AgentSafetyGuard.redact_sensitive_args: password/token/secret/api_key
    redacted at every nesting level; non-sensitive keys untouched
  - AgentSafetyGuard.check_user_authorization: grants when perms satisfied,
    raises PermissionError on any missing permission
  - AgentSafetyGuard.check_tool_permission: grants when schema has no
    required_permissions, grants when all held, raises on missing
  - _is_sensitive_key: pattern coverage for all 14 registered patterns
  - ConversationMemoryBuffer: max_turns enforcement, FIFO eviction,
    format_as_text, format_as_messages, clear, is_empty
  - ConversationTurn: frozen dataclass, defaults
  - AgentMemoryAdapter.store_fact: delegates to MemoryService, graceful
    degradation on any exception
  - AgentMemoryAdapter.retrieve_relevant: returns MemoryEntry list, returns
    [] on any exception
  - AgentMemoryAdapter.format_memories_as_text: empty list → "", non-empty
    → header + bullet lines
  - OrchestrationConfig.from_settings: reads MAX_AGENT_STEPS,
    MAX_AGENT_TOOL_CALLS, AGENT_TIMEOUT_SECONDS; **overrides take precedence;
    invalid values still trigger __post_init__ validation
  - Settings: MAX_AGENT_STEPS, MAX_AGENT_TOOL_CALLS, AGENT_TIMEOUT_SECONDS
    present with correct defaults and validation bounds

Security assertions:
  - Sensitive args (password, token, secret, api_key, auth, jwt, bearer,
    ssn, card_number, cvv, credential, access_key, private_key, passwd)
    are replaced with "[redacted]" before reaching any logger.
  - Tool output and RAG content that trip SafetyFilterError are replaced
    by safe stubs — never propagated to the caller.
  - PermissionError is raised (not swallowed) when authorization fails,
    so the execution loop can abort with PERMISSION_DENIED.
  - Memory storage failures never propagate exceptions (graceful degradation).
  - Memory retrieval failures return [] (graceful degradation).

No production credentials.  All external services are mocked.
google.genai is stubbed to prevent import-time side effects.
"""

from __future__ import annotations

import sys
import uuid
from dataclasses import dataclass
from typing import Any
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Stub google.genai before any app import that might pull it in transitively
# ---------------------------------------------------------------------------
_g = MagicMock()
sys.modules.setdefault("google.genai", _g)
sys.modules.setdefault("google.genai.types", _g)

# ---------------------------------------------------------------------------
# Module under test — import after the stub is in place
# ---------------------------------------------------------------------------
from app.orchestration.safety import (
    AgentSafetyGuard,
    _is_sensitive_key,
    _REDACTED_PLACEHOLDER,
)
from app.orchestration.memory import (
    AgentMemoryAdapter,
    ConversationMemoryBuffer,
    ConversationTurn,
    _DEFAULT_BUFFER_SIZE,
)
from app.orchestration.config import OrchestrationConfig
from app.services.safety_service import SafetyFilterError, SafetyService


# ===========================================================================
# Helpers / fixtures
# ===========================================================================


def _guard(safety_service: SafetyService | None = None) -> AgentSafetyGuard:
    """Return a guard backed by the given SafetyService (or a real one)."""
    return AgentSafetyGuard(safety_service=safety_service)


@dataclass
class _FakeMemoryEntry:
    content: str
    memory_type: str
    relevance_score: float = 1.0


def _fake_settings(
    max_steps: int = 10,
    max_tool_calls: int = 20,
    timeout_s: float = 120.0,
) -> MagicMock:
    """Return a minimal mock that satisfies OrchestrationConfig.from_settings."""
    s = MagicMock()
    s.MAX_AGENT_STEPS = max_steps
    s.MAX_AGENT_TOOL_CALLS = max_tool_calls
    s.AGENT_TIMEOUT_SECONDS = timeout_s
    return s


# ===========================================================================
# _is_sensitive_key — pattern coverage
# ===========================================================================


class TestIsSensitiveKey:
    """Verify that _is_sensitive_key matches all registered credential patterns."""

    @pytest.mark.parametrize(
        "key",
        [
            "password",
            "PASSWORD",
            "user_password",
            "passwd",
            "PASSWD",
            "secret",
            "client_secret",
            "MY_SECRET",
            "token",
            "access_token",
            "TOKEN",
            "api_key",
            "API_KEY",
            "apikey",
            "APIKEY",
            "access_key",
            "ACCESS_KEY",
            "private_key",
            "PRIVATE_KEY",
            "credential",
            "credentials",
            "auth",
            "authorization",
            "AUTH_HEADER",
            "bearer",
            "BEARER_TOKEN",
            "jwt",
            "JWT_SECRET",
            "ssn",
            "SSN",
            "card_number",
            "cardnumber",
            "CARD_NUMBER",
            "cvv",
            "CVV",
        ],
    )
    def test_sensitive_keys_are_detected(self, key: str) -> None:
        assert _is_sensitive_key(key) is True, f"Expected '{key}' to be detected as sensitive"

    @pytest.mark.parametrize(
        "key",
        [
            "username",
            "email",
            "first_name",
            "query",
            "content",
            "message",
            "tool_name",
            "limit",
            "offset",
            "page",
            "role",
            "user_id",
        ],
    )
    def test_non_sensitive_keys_are_not_detected(self, key: str) -> None:
        assert _is_sensitive_key(key) is False, f"Expected '{key}' NOT to be detected as sensitive"


# ===========================================================================
# AgentSafetyGuard — sanitize_tool_output
# ===========================================================================


class TestSanitizeToolOutput:
    """Tool output is treated as untrusted; harmful content must be stripped."""

    def test_clean_output_returned_unchanged(self) -> None:
        guard = _guard()
        result = guard.sanitize_tool_output("This is safe content.")
        assert result == "This is safe content."

    def test_script_tag_is_stripped(self) -> None:
        guard = _guard()
        raw = "Normal text <script>evil()</script> more text"
        result = guard.sanitize_tool_output(raw)
        assert "<script>" not in result
        assert "evil()" not in result
        assert "Normal text" in result

    def test_javascript_scheme_is_stripped(self) -> None:
        guard = _guard()
        raw = "Click here: javascript:alert(1)"
        result = guard.sanitize_tool_output(raw)
        assert "javascript:" not in result.lower()

    def test_safety_filter_error_returns_stub(self) -> None:
        """When SafetyService.filter_response raises SafetyFilterError,
        a safe stub is returned — the exception must NOT propagate."""
        bad_svc = MagicMock(spec=SafetyService)
        bad_svc.filter_response.side_effect = SafetyFilterError("cannot redact")
        guard = _guard(safety_service=bad_svc)
        result = guard.sanitize_tool_output("<script>really bad</script>")
        assert result == "[tool output blocked: safety filter failed]"

    def test_non_string_input_is_coerced(self) -> None:
        guard = _guard()
        result = guard.sanitize_tool_output(42)  # type: ignore[arg-type]
        assert isinstance(result, str)
        assert "42" in result

    def test_empty_string_is_returned_as_is(self) -> None:
        guard = _guard()
        assert guard.sanitize_tool_output("") == ""


# ===========================================================================
# AgentSafetyGuard — sanitize_rag_content
# ===========================================================================


class TestSanitizeRagContent:
    """RAG chunks are treated as untrusted; same contract as tool output."""

    def test_clean_content_returned_unchanged(self) -> None:
        guard = _guard()
        assert guard.sanitize_rag_content("Plain document text.") == "Plain document text."

    def test_script_tag_stripped_from_rag_chunk(self) -> None:
        guard = _guard()
        chunk = "Summary: <script src='x'></script> end"
        result = guard.sanitize_rag_content(chunk)
        assert "<script" not in result
        assert "Summary:" in result

    def test_safety_filter_error_returns_stub(self) -> None:
        bad_svc = MagicMock(spec=SafetyService)
        bad_svc.filter_response.side_effect = SafetyFilterError("no redact")
        guard = _guard(safety_service=bad_svc)
        result = guard.sanitize_rag_content("evil content")
        assert result == "[rag content blocked: safety filter failed]"

    def test_non_string_rag_content_is_coerced(self) -> None:
        guard = _guard()
        result = guard.sanitize_rag_content({"key": "value"})  # type: ignore[arg-type]
        assert isinstance(result, str)


# ===========================================================================
# AgentSafetyGuard — redact_sensitive_args
# ===========================================================================


class TestRedactSensitiveArgs:
    """Sensitive keys must be redacted; non-sensitive keys must be preserved."""

    def test_password_is_redacted(self) -> None:
        guard = _guard()
        params = {"username": "alice", "password": "s3cr3t"}
        result = guard.redact_sensitive_args("login", params)
        assert result["username"] == "alice"
        assert result["password"] == _REDACTED_PLACEHOLDER

    def test_token_is_redacted(self) -> None:
        guard = _guard()
        params = {"url": "https://example.com", "token": "tok_abc123"}
        result = guard.redact_sensitive_args("fetch_url", params)
        assert result["url"] == "https://example.com"
        assert result["token"] == _REDACTED_PLACEHOLDER

    def test_api_key_is_redacted(self) -> None:
        guard = _guard()
        params = {"model": "gemini-pro", "api_key": "AIzaSy..."}
        result = guard.redact_sensitive_args("llm_call", params)
        assert result["model"] == "gemini-pro"
        assert result["api_key"] == _REDACTED_PLACEHOLDER

    def test_secret_is_redacted(self) -> None:
        guard = _guard()
        params = {"client_id": "abc", "client_secret": "xyz"}
        result = guard.redact_sensitive_args("oauth", params)
        assert result["client_id"] == "abc"
        assert result["client_secret"] == _REDACTED_PLACEHOLDER

    @pytest.mark.parametrize(
        "key",
        [
            "password",
            "passwd",
            "secret",
            "token",
            "api_key",
            "access_key",
            "private_key",
            "credential",
            "auth",
            "bearer",
            "jwt",
            "ssn",
            "card_number",
            "cvv",
        ],
    )
    def test_all_sensitive_key_patterns_are_redacted(self, key: str) -> None:
        guard = _guard()
        params = {key: "sensitive_value", "safe_key": "safe_value"}
        result = guard.redact_sensitive_args("tool", params)
        assert result[key] == _REDACTED_PLACEHOLDER
        assert result["safe_key"] == "safe_value"

    def test_nested_dict_is_redacted(self) -> None:
        guard = _guard()
        params = {
            "config": {
                "host": "localhost",
                "password": "db_pass",
            }
        }
        result = guard.redact_sensitive_args("db_query", params)
        assert result["config"]["host"] == "localhost"
        assert result["config"]["password"] == _REDACTED_PLACEHOLDER

    def test_nested_list_is_redacted(self) -> None:
        guard = _guard()
        # "items" is not sensitive so it won't be redacted at the top level;
        # each nested dict has a "token" key that should be redacted.
        params = {
            "items": [
                {"token": "t1"},
                {"token": "t2"},
            ]
        }
        result = guard.redact_sensitive_args("batch_call", params)
        assert isinstance(result["items"], list)
        assert result["items"][0]["token"] == _REDACTED_PLACEHOLDER
        assert result["items"][1]["token"] == _REDACTED_PLACEHOLDER

    def test_original_params_not_mutated(self) -> None:
        guard = _guard()
        original = {"password": "original_secret", "name": "Alice"}
        guard.redact_sensitive_args("create_user", original)
        assert original["password"] == "original_secret"  # must not be mutated

    def test_empty_params_returns_empty_dict(self) -> None:
        guard = _guard()
        result = guard.redact_sensitive_args("noop", {})
        assert result == {}

    def test_non_sensitive_params_unchanged(self) -> None:
        guard = _guard()
        params = {"query": "SELECT * FROM users", "limit": 10}
        result = guard.redact_sensitive_args("db_select", params)
        assert result == params


# ===========================================================================
# AgentSafetyGuard — check_user_authorization
# ===========================================================================


class TestCheckUserAuthorization:
    """User must hold ALL required permissions; missing any → PermissionError."""

    def test_grants_when_all_permissions_held(self) -> None:
        guard = _guard()
        # No exception expected
        guard.check_user_authorization(
            user_id="user-1",
            tool_name="create_ticket",
            required_permissions=["write:jira", "read:jira"],
            user_permissions=["read:jira", "write:jira", "admin"],
        )

    def test_raises_when_one_permission_missing(self) -> None:
        guard = _guard()
        with pytest.raises(PermissionError, match="write:jira"):
            guard.check_user_authorization(
                user_id="user-2",
                tool_name="create_ticket",
                required_permissions=["write:jira", "read:jira"],
                user_permissions=["read:jira"],
            )

    def test_raises_when_all_permissions_missing(self) -> None:
        guard = _guard()
        with pytest.raises(PermissionError):
            guard.check_user_authorization(
                user_id="user-3",
                tool_name="admin_tool",
                required_permissions=["admin:all"],
                user_permissions=[],
            )

    def test_grants_when_no_permissions_required(self) -> None:
        guard = _guard()
        # Tool requires nothing — must always succeed
        guard.check_user_authorization(
            user_id="user-4",
            tool_name="list_public",
            required_permissions=[],
            user_permissions=[],
        )

    def test_error_message_contains_tool_name(self) -> None:
        guard = _guard()
        with pytest.raises(PermissionError, match="secret_tool"):
            guard.check_user_authorization(
                user_id="u",
                tool_name="secret_tool",
                required_permissions=["top:secret"],
                user_permissions=[],
            )

    def test_error_message_contains_user_id(self) -> None:
        guard = _guard()
        with pytest.raises(PermissionError, match="alice"):
            guard.check_user_authorization(
                user_id="alice",
                tool_name="restricted",
                required_permissions=["perm:x"],
                user_permissions=[],
            )


# ===========================================================================
# AgentSafetyGuard — check_tool_permission
# ===========================================================================


class TestCheckToolPermission:
    """Tool schemas without required_permissions are always allowed."""

    def test_grants_when_schema_has_no_required_permissions(self) -> None:
        guard = _guard()
        schema = {"name": "echo", "description": "Echoes input."}
        guard.check_tool_permission(schema, user_permissions=["nothing"])

    def test_grants_when_user_holds_required_permissions(self) -> None:
        guard = _guard()
        schema = {
            "name": "create_pr",
            "required_permissions": ["write:github"],
        }
        guard.check_tool_permission(schema, user_permissions=["write:github", "read:github"])

    def test_raises_when_user_missing_required_permission(self) -> None:
        guard = _guard()
        schema = {
            "name": "deploy",
            "required_permissions": ["deploy:prod"],
        }
        with pytest.raises(PermissionError, match="deploy:prod"):
            guard.check_tool_permission(schema, user_permissions=["read:logs"])

    def test_raises_on_partial_permissions(self) -> None:
        guard = _guard()
        schema = {
            "name": "manage_users",
            "required_permissions": ["read:users", "write:users"],
        }
        with pytest.raises(PermissionError, match="write:users"):
            guard.check_tool_permission(schema, user_permissions=["read:users"])

    def test_empty_required_permissions_list_always_grants(self) -> None:
        guard = _guard()
        schema = {"name": "noop", "required_permissions": []}
        guard.check_tool_permission(schema, user_permissions=[])

    def test_unknown_tool_name_in_error_message(self) -> None:
        guard = _guard()
        schema = {"required_permissions": ["perm:x"]}  # no "name" key
        with pytest.raises(PermissionError, match="<unknown>"):
            guard.check_tool_permission(schema, user_permissions=[])


# ===========================================================================
# ConversationTurn — dataclass contract
# ===========================================================================


class TestConversationTurn:
    def test_creation_with_defaults(self) -> None:
        turn = ConversationTurn(role="user", content="Hello")
        assert turn.role == "user"
        assert turn.content == "Hello"
        assert turn.step == 0

    def test_creation_with_explicit_step(self) -> None:
        turn = ConversationTurn(role="assistant", content="Hi there", step=3)
        assert turn.step == 3

    def test_frozen_prevents_mutation(self) -> None:
        turn = ConversationTurn(role="user", content="original")
        with pytest.raises((AttributeError, TypeError)):
            turn.content = "mutated"  # type: ignore[misc]


# ===========================================================================
# ConversationMemoryBuffer — ring buffer behaviour
# ===========================================================================


class TestConversationMemoryBuffer:
    def test_default_max_turns(self) -> None:
        buf = ConversationMemoryBuffer()
        assert buf.max_turns == _DEFAULT_BUFFER_SIZE

    def test_is_empty_initially(self) -> None:
        buf = ConversationMemoryBuffer()
        assert buf.is_empty()
        assert len(buf) == 0

    def test_add_turn_increments_length(self) -> None:
        buf = ConversationMemoryBuffer()
        buf.add_turn("user", "Hello")
        assert len(buf) == 1
        assert not buf.is_empty()

    def test_fifo_eviction_on_overflow(self) -> None:
        buf = ConversationMemoryBuffer(max_turns=3)
        buf.add_turn("user", "first", step=0)
        buf.add_turn("assistant", "second", step=1)
        buf.add_turn("user", "third", step=2)
        buf.add_turn("assistant", "fourth", step=3)  # evicts "first"
        assert len(buf) == 3
        roles_contents = [(t.role, t.content) for t in buf.turns]
        assert ("user", "first") not in roles_contents
        assert ("assistant", "fourth") in roles_contents

    def test_max_turns_at_capacity(self) -> None:
        buf = ConversationMemoryBuffer(max_turns=2)
        buf.add_turn("user", "A")
        buf.add_turn("assistant", "B")
        assert len(buf) == 2
        buf.add_turn("user", "C")
        assert len(buf) == 2

    def test_clear_empties_buffer(self) -> None:
        buf = ConversationMemoryBuffer(max_turns=5)
        buf.add_turn("user", "msg1")
        buf.add_turn("assistant", "msg2")
        buf.clear()
        assert buf.is_empty()
        assert len(buf) == 0

    def test_turns_returns_insertion_order(self) -> None:
        buf = ConversationMemoryBuffer(max_turns=5)
        buf.add_turn("user", "alpha", step=0)
        buf.add_turn("assistant", "beta", step=1)
        turns = buf.turns
        assert turns[0].content == "alpha"
        assert turns[1].content == "beta"

    def test_format_as_text_empty_buffer(self) -> None:
        buf = ConversationMemoryBuffer()
        assert buf.format_as_text() == ""

    def test_format_as_text_non_empty(self) -> None:
        buf = ConversationMemoryBuffer()
        buf.add_turn("user", "Hello", step=0)
        buf.add_turn("assistant", "World", step=1)
        text = buf.format_as_text()
        assert "USER: Hello" in text
        assert "ASSISTANT: World" in text

    def test_format_as_text_custom_separator(self) -> None:
        buf = ConversationMemoryBuffer()
        buf.add_turn("user", "Hi")
        buf.add_turn("assistant", "Hello")
        text = buf.format_as_text(separator=" | ")
        assert " | " in text

    def test_format_as_messages_empty(self) -> None:
        buf = ConversationMemoryBuffer()
        assert buf.format_as_messages() == []

    def test_format_as_messages_shape(self) -> None:
        buf = ConversationMemoryBuffer()
        buf.add_turn("user", "Tell me a joke")
        buf.add_turn("assistant", "Why did the AI...")
        msgs = buf.format_as_messages()
        assert msgs == [
            {"role": "user", "content": "Tell me a joke"},
            {"role": "assistant", "content": "Why did the AI..."},
        ]

    def test_invalid_max_turns_raises(self) -> None:
        with pytest.raises(ValueError, match="max_turns"):
            ConversationMemoryBuffer(max_turns=0)

    def test_single_turn_buffer(self) -> None:
        buf = ConversationMemoryBuffer(max_turns=1)
        buf.add_turn("user", "first")
        buf.add_turn("user", "second")  # evicts "first"
        assert len(buf) == 1
        assert buf.turns[0].content == "second"


# ===========================================================================
# AgentMemoryAdapter — store_fact
# ===========================================================================


class TestAgentMemoryAdapterStoreFact:
    """store_fact delegates to MemoryService; failures must not propagate."""

    @pytest.mark.asyncio
    async def test_store_fact_calls_memory_service(self) -> None:
        svc = MagicMock()
        svc.store_memory = AsyncMock(return_value=MagicMock())

        # Patch MemoryType so import succeeds without DB
        with patch("app.orchestration.memory.AgentMemoryAdapter.store_fact") as mock_sf:
            mock_sf.return_value = None  # will test the real impl below
        # Real impl test — patch MemoryType only
        with patch("app.models.memory.MemoryType") as mt:
            mt.return_value = "fact"
            mt.fact = "fact"
            adapter = AgentMemoryAdapter(
                memory_service=svc,
                user_id=uuid.UUID("00000000-0000-0000-0000-000000000001"),
            )
            # We'll call the method directly; MemoryType import will be attempted
            # inside; mock it so we don't need a real DB model.
            with patch("builtins.__import__", side_effect=_allow_all_except_db):
                pass  # guard removed — rely on graceful degradation test below

        # Graceful degradation test: the real method catches ImportError
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000001"),
        )
        # Patch the inner import inside store_fact by mocking the module
        mock_mem_type = MagicMock()
        mock_mem_type.fact = "fact"
        with patch.dict("sys.modules", {"app.models.memory": MagicMock(MemoryType=mock_mem_type)}):
            await adapter.store_fact("User likes dark mode.")
        svc.store_memory.assert_called_once()

    @pytest.mark.asyncio
    async def test_store_fact_degrades_gracefully_on_service_error(self) -> None:
        """store_fact must NOT raise when MemoryService raises."""
        svc = MagicMock()
        svc.store_memory = AsyncMock(side_effect=RuntimeError("DB offline"))
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000002"),
        )
        mock_mem_type = MagicMock()
        mock_mem_type.fact = "fact"
        with patch.dict("sys.modules", {"app.models.memory": MagicMock(MemoryType=mock_mem_type)}):
            # Must not raise — graceful degradation
            await adapter.store_fact("Some fact")

    @pytest.mark.asyncio
    async def test_store_fact_degrades_gracefully_on_import_error(self) -> None:
        """When app.models.memory is unavailable, store_fact must not raise."""
        svc = MagicMock()
        svc.store_memory = AsyncMock()
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000003"),
        )
        with patch.dict("sys.modules", {"app.models.memory": None}):  # type: ignore[dict-item]
            await adapter.store_fact("Some fact")  # must not raise


# ===========================================================================
# AgentMemoryAdapter — retrieve_relevant
# ===========================================================================


class TestAgentMemoryAdapterRetrieveRelevant:
    @pytest.mark.asyncio
    async def test_returns_entries_on_success(self) -> None:
        entries = [
            _FakeMemoryEntry("User prefers dark mode.", "preference"),
            _FakeMemoryEntry("User's name is Alice.", "fact"),
        ]
        svc = MagicMock()
        svc.get_relevant_memories = AsyncMock(return_value=entries)
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000004"),
        )
        result = await adapter.retrieve_relevant("What does the user prefer?", top_k=2)
        assert len(result) == 2
        assert result[0].content == "User prefers dark mode."
        svc.get_relevant_memories.assert_called_once_with(
            user_id=adapter._user_id,
            query="What does the user prefer?",
            top_k=2,
        )

    @pytest.mark.asyncio
    async def test_returns_empty_list_on_service_error(self) -> None:
        """retrieve_relevant must return [] and not raise on any failure."""
        svc = MagicMock()
        svc.get_relevant_memories = AsyncMock(side_effect=Exception("ChromaDB down"))
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000005"),
        )
        result = await adapter.retrieve_relevant("anything")
        assert result == []

    @pytest.mark.asyncio
    async def test_default_top_k_is_three(self) -> None:
        svc = MagicMock()
        svc.get_relevant_memories = AsyncMock(return_value=[])
        adapter = AgentMemoryAdapter(
            memory_service=svc,
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000006"),
        )
        await adapter.retrieve_relevant("query")
        _, kwargs = svc.get_relevant_memories.call_args
        assert (
            kwargs.get("top_k", None) == 3
            or svc.get_relevant_memories.call_args[1].get("top_k") == 3
            or svc.get_relevant_memories.call_args[0][2] == 3
        )


# ===========================================================================
# AgentMemoryAdapter — format_memories_as_text
# ===========================================================================


class TestAgentMemoryAdapterFormatMemories:
    def _adapter(self) -> AgentMemoryAdapter:
        return AgentMemoryAdapter(
            memory_service=MagicMock(),
            user_id=uuid.UUID("00000000-0000-0000-0000-000000000007"),
        )

    def test_empty_list_returns_empty_string(self) -> None:
        adapter = self._adapter()
        assert adapter.format_memories_as_text([]) == ""

    def test_non_empty_list_includes_header(self) -> None:
        adapter = self._adapter()
        entries = [_FakeMemoryEntry("Likes dark mode.", "preference")]
        result = adapter.format_memories_as_text(entries)
        assert "Relevant memories:" in result
        assert "Likes dark mode." in result

    def test_custom_header(self) -> None:
        adapter = self._adapter()
        entries = [_FakeMemoryEntry("Fact A.", "fact")]
        result = adapter.format_memories_as_text(entries, header="Context:")
        assert result.startswith("Context:")

    def test_multiple_entries_are_bullet_lines(self) -> None:
        adapter = self._adapter()
        entries = [
            _FakeMemoryEntry("Entry 1.", "fact"),
            _FakeMemoryEntry("Entry 2.", "preference"),
        ]
        result = adapter.format_memories_as_text(entries)
        lines = result.splitlines()
        # First line is the header
        assert lines[0] == "Relevant memories:"
        # Subsequent lines start with "- "
        for line in lines[1:]:
            assert line.startswith("- ")

    def test_memory_type_appears_in_output(self) -> None:
        adapter = self._adapter()
        entries = [_FakeMemoryEntry("Some fact.", "fact")]
        result = adapter.format_memories_as_text(entries)
        assert "[fact]" in result


# ===========================================================================
# OrchestrationConfig.from_settings — Settings bridge
# ===========================================================================


class TestOrchestrationConfigFromSettings:
    def test_reads_max_agent_steps_from_settings(self) -> None:
        cfg = OrchestrationConfig.from_settings(_fake_settings(max_steps=7))
        assert cfg.max_steps == 7

    def test_reads_max_agent_tool_calls_from_settings(self) -> None:
        cfg = OrchestrationConfig.from_settings(_fake_settings(max_tool_calls=15))
        assert cfg.max_tool_calls == 15

    def test_reads_agent_timeout_from_settings(self) -> None:
        cfg = OrchestrationConfig.from_settings(_fake_settings(timeout_s=90.0))
        assert cfg.timeout_s == 90.0

    def test_override_takes_precedence_over_settings(self) -> None:
        cfg = OrchestrationConfig.from_settings(
            _fake_settings(max_steps=10),
            max_steps=3,
        )
        assert cfg.max_steps == 3

    def test_multiple_overrides(self) -> None:
        cfg = OrchestrationConfig.from_settings(
            _fake_settings(max_steps=10, max_tool_calls=20, timeout_s=120.0),
            max_steps=5,
            max_tool_calls=10,
            timeout_s=60.0,
        )
        assert cfg.max_steps == 5
        assert cfg.max_tool_calls == 10
        assert cfg.timeout_s == 60.0

    def test_default_settings_produce_valid_config(self) -> None:
        cfg = OrchestrationConfig.from_settings(_fake_settings())
        assert cfg.max_steps == 10
        assert cfg.max_tool_calls == 20
        assert cfg.timeout_s == 120.0

    def test_hard_cap_still_enforced_via_post_init(self) -> None:
        """OrchestrationConfig.__post_init__ should reject values over the hard cap."""
        with pytest.raises(ValueError, match="max_steps"):
            OrchestrationConfig.from_settings(_fake_settings(max_steps=999))

    def test_hard_timeout_cap_enforced(self) -> None:
        with pytest.raises(ValueError, match="timeout"):
            OrchestrationConfig.from_settings(_fake_settings(timeout_s=9999.0))


# ===========================================================================
# Settings fields — presence and default values
# ===========================================================================


class TestSettingsFields:
    """Verify the three new Settings fields exist with correct defaults/bounds."""

    def test_max_agent_steps_default_and_type(self) -> None:
        from app.config.settings import Settings

        s = Settings()
        assert isinstance(s.MAX_AGENT_STEPS, int)
        assert s.MAX_AGENT_STEPS == 10

    def test_max_agent_tool_calls_default_and_type(self) -> None:
        from app.config.settings import Settings

        s = Settings()
        assert isinstance(s.MAX_AGENT_TOOL_CALLS, int)
        assert s.MAX_AGENT_TOOL_CALLS == 20

    def test_agent_timeout_seconds_default_and_type(self) -> None:
        from app.config.settings import Settings

        s = Settings()
        assert isinstance(s.AGENT_TIMEOUT_SECONDS, float)
        assert s.AGENT_TIMEOUT_SECONDS == 120.0

    def test_max_agent_steps_lower_bound(self) -> None:
        from pydantic import ValidationError
        from app.config.settings import Settings

        with pytest.raises((ValidationError, ValueError)):
            Settings(MAX_AGENT_STEPS=0)

    def test_max_agent_steps_upper_bound(self) -> None:
        from pydantic import ValidationError
        from app.config.settings import Settings

        with pytest.raises((ValidationError, ValueError)):
            Settings(MAX_AGENT_STEPS=51)

    def test_max_agent_tool_calls_upper_bound(self) -> None:
        from pydantic import ValidationError
        from app.config.settings import Settings

        with pytest.raises((ValidationError, ValueError)):
            Settings(MAX_AGENT_TOOL_CALLS=101)

    def test_agent_timeout_lower_bound(self) -> None:
        from pydantic import ValidationError
        from app.config.settings import Settings

        with pytest.raises((ValidationError, ValueError)):
            Settings(AGENT_TIMEOUT_SECONDS=0.0)

    def test_agent_timeout_upper_bound(self) -> None:
        from pydantic import ValidationError
        from app.config.settings import Settings

        with pytest.raises((ValidationError, ValueError)):
            Settings(AGENT_TIMEOUT_SECONDS=301.0)


# ===========================================================================
# Helper used only in one test — defined here to keep imports local
# ===========================================================================


def _allow_all_except_db(name: str, *args: Any, **kwargs: Any) -> Any:
    """Side effect for __import__ that blocks only specific modules."""
    if name in ("app.models.db",):
        raise ImportError(name)
    return __import__(name, *args, **kwargs)
