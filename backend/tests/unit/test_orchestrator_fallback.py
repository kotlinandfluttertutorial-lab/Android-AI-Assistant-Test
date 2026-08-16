"""Unit tests for AIOrchestrator fallback behaviour.

Tests that when the primary LLM provider raises an exception:
  1. The orchestrator falls back to the configured FALLBACK_LLM_PROVIDER.
  2. The user is notified of the substitution via a 'notice' WebSocket event.
  3. If no fallback is configured (empty string), the original exception propagates.
  4. If FALLBACK_LLM_PROVIDER equals the primary, no fallback is attempted.

Requirements: 3.3
"""

from __future__ import annotations

import os
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from app.services.ai_orchestrator import AIOrchestrator, LLMProvider


def _make_orchestrator():
    """Return an AIOrchestrator with mocked DB dependencies."""
    db = AsyncMock()
    orch = AIOrchestrator(db=db)

    # Stub internal components to isolate fallback logic
    orch._message_repo = AsyncMock()
    orch._message_repo.create = AsyncMock(return_value=MagicMock(id="msg-uuid"))
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=[])
    orch._token_usage_repo = AsyncMock()
    orch._token_usage_repo.create = AsyncMock(return_value=MagicMock())
    orch._memory_service = AsyncMock()
    orch._memory_service.get_relevant_memories = AsyncMock(return_value=[])
    orch._db.commit = AsyncMock()
    return orch


def _make_ws():
    """Return a mock WebSocket."""
    ws = AsyncMock()
    ws.send_json = AsyncMock()
    return ws


# ---------------------------------------------------------------------------
# _get_fallback_provider
# ---------------------------------------------------------------------------


class TestGetFallbackProvider:
    def setup_method(self) -> None:
        from app.config.settings import get_settings

        get_settings.cache_clear()

    def teardown_method(self) -> None:
        from app.config.settings import get_settings

        get_settings.cache_clear()

    def test_returns_none_when_not_configured(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": ""}):
            from app.config.settings import get_settings

            get_settings.cache_clear()
            orch = _make_orchestrator()
            assert orch._get_fallback_provider(LLMProvider.openai) is None

    def test_returns_fallback_provider_when_configured(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "gemini"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()
            orch = _make_orchestrator()
            result = orch._get_fallback_provider(LLMProvider.openai)
            assert result == LLMProvider.gemini

    def test_returns_none_when_fallback_equals_primary(self) -> None:
        """Fallback == primary would cause infinite retry; must return None."""
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "openai"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()
            orch = _make_orchestrator()
            assert orch._get_fallback_provider(LLMProvider.openai) is None

    def test_returns_none_for_invalid_provider_name(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "nonexistent_model"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()
            orch = _make_orchestrator()
            assert orch._get_fallback_provider(LLMProvider.openai) is None


# ---------------------------------------------------------------------------
# stream_chat fallback integration
# ---------------------------------------------------------------------------


class TestStreamChatFallback:
    """Integration-level tests for the fallback path in stream_chat()."""

    def setup_method(self) -> None:
        from app.config.settings import get_settings

        get_settings.cache_clear()

    def teardown_method(self) -> None:
        from app.config.settings import get_settings

        get_settings.cache_clear()

    def _make_streaming_client(self, tokens: list[str]):
        """Return a mock BaseLLMClient that streams the given tokens."""
        from app.services.llm_clients import BaseLLMClient

        async def _stream(context):
            for t in tokens:
                yield t

        client = AsyncMock(spec=BaseLLMClient)
        client.stream = _stream
        client.cost_per_input_token = 0
        client.cost_per_output_token = 0
        from decimal import Decimal

        client.cost_per_input_token = Decimal(0)
        client.cost_per_output_token = Decimal(0)
        # Required for _to_llm_prompt_context clamping (Requirement 25.5)
        client.max_output_tokens = 2048
        return client

    def _make_failing_client(self, error: Exception):
        """Return a mock BaseLLMClient whose stream() raises ``error``."""
        from app.services.llm_clients import BaseLLMClient

        async def _stream(context):
            raise error
            yield  # make it a generator  # noqa: unreachable

        client = AsyncMock(spec=BaseLLMClient)
        client.stream = _stream
        # Required for _to_llm_prompt_context clamping (Requirement 25.5)
        client.max_output_tokens = 2048
        return client

    @pytest.mark.asyncio
    async def test_fallback_is_used_when_primary_fails(self) -> None:
        """When the primary provider raises, the fallback provider's tokens arrive."""
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "gemini"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary_client = self._make_failing_client(RuntimeError("API down"))
            fallback_client = self._make_streaming_client(["Hello", " world"])

            import uuid

            async def _fake_resolve(provider):
                if provider == LLMProvider.openai:
                    return primary_client
                return fallback_client

            orch._resolve_provider = _fake_resolve
            orch._build_prompt = AsyncMock(
                return_value=MagicMock(
                    messages=[MagicMock(role="system", content="sys")],
                    estimated_tokens=10,
                )
            )
            orch._detect_prompt_injection = AsyncMock(return_value=False)

            await orch.stream_chat(
                conversation_id=str(uuid.uuid4()),
                user_message="hi",
                provider=LLMProvider.openai,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            # Collect all messages sent to the WebSocket
            sent = [call.args[0] for call in ws.send_json.call_args_list]
            message_types = [m.get("type") for m in sent]

            # A 'notice' event must have been sent (Requirement 3.3)
            assert (
                "notice" in message_types
            ), "User was not notified of fallback substitution"

            # Token events must have been sent (fallback actually ran)
            assert "token" in message_types

            # Done event must be present
            assert "done" in message_types

    @pytest.mark.asyncio
    async def test_notice_message_mentions_both_providers(self) -> None:
        """The notice message must identify the failing and fallback providers."""
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "claude"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary_client = self._make_failing_client(RuntimeError("timeout"))
            fallback_client = self._make_streaming_client(["fallback token"])

            import uuid

            async def _fake_resolve(provider):
                if provider == LLMProvider.openai:
                    return primary_client
                return fallback_client

            orch._resolve_provider = _fake_resolve
            orch._build_prompt = AsyncMock(
                return_value=MagicMock(
                    messages=[MagicMock(role="system", content="sys")],
                    estimated_tokens=5,
                )
            )
            orch._detect_prompt_injection = AsyncMock(return_value=False)

            await orch.stream_chat(
                conversation_id=str(uuid.uuid4()),
                user_message="test",
                provider=LLMProvider.openai,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            sent = [call.args[0] for call in ws.send_json.call_args_list]
            notice = next((m for m in sent if m.get("type") == "notice"), None)
            assert notice is not None
            # The notice must mention both the failing provider and the fallback
            assert "openai" in notice["message"].lower()
            assert "claude" in notice["message"].lower()

    @pytest.mark.asyncio
    async def test_no_notice_when_no_fallback_configured(self) -> None:
        """Without a fallback configured, exception propagates and no notice is sent."""
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": ""}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary_client = self._make_failing_client(RuntimeError("quota exceeded"))
            import uuid

            async def _fake_resolve(provider):
                return primary_client

            orch._resolve_provider = _fake_resolve
            orch._build_prompt = AsyncMock(
                return_value=MagicMock(
                    messages=[MagicMock(role="system", content="sys")],
                    estimated_tokens=5,
                )
            )
            orch._detect_prompt_injection = AsyncMock(return_value=False)

            with pytest.raises(RuntimeError, match="quota exceeded"):
                await orch.stream_chat(
                    conversation_id=str(uuid.uuid4()),
                    user_message="test",
                    provider=LLMProvider.openai,
                    user_id=str(uuid.uuid4()),
                    ws=ws,
                )

            sent = [call.args[0] for call in ws.send_json.call_args_list]
            assert not any(
                m.get("type") == "notice" for m in sent
            ), "No notice should be sent when no fallback is configured"
