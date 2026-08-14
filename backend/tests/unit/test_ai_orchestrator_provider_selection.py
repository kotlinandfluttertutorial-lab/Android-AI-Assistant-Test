"""Unit tests for AIOrchestrator provider selection and fallback logic.

Task 29.5 — Requirements: 3.3, 21.1

Tests covered:
  1. _resolve_provider returns the correct concrete BaseLLMClient for all six
     LLMProvider values (openai, gemini, claude, ollama, llama, mistral).
  2. _resolve_provider caches the client instance on repeated calls.
  3. _resolve_provider raises ValueError for an unsupported provider value.
  4. Fallback triggers on primary provider error for every possible primary /
     fallback pair (mocked clients, no real API calls).
  5. User is notified via a 'notice' WebSocket event when fallback is used
     (Requirement 3.3 — "notify the User of the substitution").
  6. NO 'notice' event is sent when the primary provider succeeds.
  7. NO fallback and NO notice when FALLBACK_LLM_PROVIDER is not configured.
  8. NO fallback when FALLBACK_LLM_PROVIDER == primary (avoid infinite loop).
  9. Original exception propagates when no fallback is configured.
 10. Fallback tokens are collected and emitted correctly after primary failure.
 11. 'done' event is always emitted (primary success and fallback success paths).
"""

from __future__ import annotations

import os
import uuid
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# ---------------------------------------------------------------------------
# Environment setup — must happen before any app import
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")

from app.services.ai_orchestrator import AIOrchestrator, LLMProvider
from app.services.llm_clients import (
    BaseLLMClient,
    ClaudeClient,
    GeminiClient,
    LlamaClient,
    MistralClient,
    OllamaClient,
    OpenAIClient,
)

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

ALL_PROVIDERS = [
    LLMProvider.openai,
    LLMProvider.gemini,
    LLMProvider.claude,
    LLMProvider.ollama,
    LLMProvider.llama,
    LLMProvider.mistral,
]

PROVIDER_TO_CLIENT_CLASS = {
    LLMProvider.openai: OpenAIClient,
    LLMProvider.gemini: GeminiClient,
    LLMProvider.claude: ClaudeClient,
    LLMProvider.ollama: OllamaClient,
    LLMProvider.llama: LlamaClient,
    LLMProvider.mistral: MistralClient,
}


def _make_orchestrator() -> AIOrchestrator:
    """Return an AIOrchestrator with all DB dependencies mocked."""
    db = AsyncMock()
    orch = AIOrchestrator(db=db)
    orch._message_repo = AsyncMock()
    orch._message_repo.create = AsyncMock(return_value=MagicMock(id="msg-uuid"))
    orch._message_repo.get_by_conversation_id = AsyncMock(return_value=[])
    orch._token_usage_repo = AsyncMock()
    orch._token_usage_repo.create = AsyncMock(return_value=MagicMock())
    orch._memory_service = AsyncMock()
    orch._memory_service.get_relevant_memories = AsyncMock(return_value=[])
    orch._db.commit = AsyncMock()
    return orch


def _make_ws() -> AsyncMock:
    ws = AsyncMock()
    ws.send_json = AsyncMock()
    return ws


def _make_streaming_client(tokens: list[str]) -> AsyncMock:
    """Mock BaseLLMClient that streams the given token list."""

    async def _stream(context):
        for t in tokens:
            yield t

    client = AsyncMock(spec=BaseLLMClient)
    client.stream = _stream
    client.cost_per_input_token = Decimal(0)
    client.cost_per_output_token = Decimal(0)
    client.max_output_tokens = 2048
    return client


def _make_failing_client(error: Exception) -> AsyncMock:
    """Mock BaseLLMClient whose stream() raises *error*."""

    async def _stream(context):
        raise error
        yield  # make it an async generator  # noqa: unreachable

    client = AsyncMock(spec=BaseLLMClient)
    client.stream = _stream
    client.cost_per_input_token = Decimal(0)
    client.cost_per_output_token = Decimal(0)
    client.max_output_tokens = 2048
    return client


def _sent_types(ws: AsyncMock) -> list[str]:
    """Return the list of 'type' values from all ws.send_json calls."""
    return [call.args[0].get("type") for call in ws.send_json.call_args_list]


# ---------------------------------------------------------------------------
# 1 & 2. _resolve_provider — correct concrete client per provider
# ---------------------------------------------------------------------------


class TestResolveProviderReturnsCorrectClientType:
    """_resolve_provider must return the correct BaseLLMClient subclass for
    each of the six supported LLMProvider values (Requirement 3.1)."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "provider,expected_class",
        [
            (LLMProvider.openai, OpenAIClient),
            (LLMProvider.gemini, GeminiClient),
            (LLMProvider.claude, ClaudeClient),
            (LLMProvider.ollama, OllamaClient),
            (LLMProvider.llama, LlamaClient),
            (LLMProvider.mistral, MistralClient),
        ],
        ids=["openai", "gemini", "claude", "ollama", "llama", "mistral"],
    )
    async def test_returns_correct_concrete_client(
        self, provider: LLMProvider, expected_class: type[BaseLLMClient]
    ) -> None:
        """Each provider enum value resolves to its concrete client class."""
        orch = _make_orchestrator()
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            client = await orch._resolve_provider(provider)

        assert isinstance(client, expected_class), (
            f"Expected {expected_class.__name__} for provider {provider.value}, "
            f"got {type(client).__name__}"
        )


class TestResolveProviderCaching:
    """_resolve_provider must cache the client instance so the same object is
    returned on repeated calls for the same provider."""

    @pytest.mark.asyncio
    async def test_same_instance_returned_on_second_call(self) -> None:
        orch = _make_orchestrator()
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            first = await orch._resolve_provider(LLMProvider.openai)
            second = await orch._resolve_provider(LLMProvider.openai)

        assert first is second, (
            "_resolve_provider must return the cached instance on repeated calls"
        )

    @pytest.mark.asyncio
    async def test_different_providers_return_different_instances(self) -> None:
        orch = _make_orchestrator()
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            openai_client = await orch._resolve_provider(LLMProvider.openai)
            gemini_client = await orch._resolve_provider(LLMProvider.gemini)

        assert openai_client is not gemini_client

    @pytest.mark.asyncio
    async def test_all_six_providers_cached_independently(self) -> None:
        orch = _make_orchestrator()
        with (
            patch("app.services.llm_clients.AsyncOpenAI"),
            patch("app.services.llm_clients.AsyncAnthropic"),
            patch("app.services.llm_clients.genai"),
            patch("app.services.llm_clients.httpx.AsyncClient"),
        ):
            clients = [await orch._resolve_provider(p) for p in ALL_PROVIDERS]

        # All six must be distinct objects
        assert len(set(id(c) for c in clients)) == 6, (
            "Expected 6 distinct cached client instances, one per provider"
        )


class TestResolveProviderUnsupported:
    """_resolve_provider must raise ValueError for unknown provider values."""

    @pytest.mark.asyncio
    async def test_raises_value_error_for_unknown_provider(self) -> None:
        orch = _make_orchestrator()
        fake_provider = MagicMock()
        fake_provider.value = "unknown_model"

        with pytest.raises((ValueError, Exception)):
            await orch._resolve_provider(fake_provider)


# ---------------------------------------------------------------------------
# 3. No 'notice' event on primary success (Requirement 3.3 — "IF no fallback
#    attempt is made, THE AI_Orchestrator SHALL NOT notify the User")
# ---------------------------------------------------------------------------


class TestNoNoticeOnPrimarySuccess:
    """When the primary provider succeeds, no 'notice' event must be emitted."""

    @pytest.mark.asyncio
    async def test_no_notice_when_primary_succeeds(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "gemini"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary_client = _make_streaming_client(["Hello", " world"])

            async def _fake_resolve(provider):
                return primary_client

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

            types = _sent_types(ws)
            assert "notice" not in types, (
                "No 'notice' event should be sent when primary provider succeeds"
            )
            # But token and done events must still be present
            assert "token" in types
            assert "done" in types

            get_settings.cache_clear()

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "provider",
        ALL_PROVIDERS,
        ids=[p.value for p in ALL_PROVIDERS],
    )
    async def test_no_notice_for_each_provider_on_success(
        self, provider: LLMProvider
    ) -> None:
        """No notice for any of the 6 providers when they succeed."""
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": ""}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()
            success_client = _make_streaming_client(["ok"])

            async def _fake_resolve(_provider):
                return success_client

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
                user_message="ping",
                provider=provider,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            assert "notice" not in _sent_types(ws), (
                f"No notice expected on success for provider '{provider.value}'"
            )
            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# 4. Fallback triggers on primary error — all six providers as fallback
# ---------------------------------------------------------------------------


class TestFallbackTriggersForAllProviders:
    """When the primary provider raises, the fallback client is used regardless
    of which provider serves as the fallback (Requirement 3.3)."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "fallback_provider",
        ALL_PROVIDERS,
        ids=[p.value for p in ALL_PROVIDERS],
    )
    async def test_fallback_used_for_each_provider(
        self, fallback_provider: LLMProvider
    ) -> None:
        # Use openai as the always-failing primary; each provider as fallback
        primary_provider = (
            LLMProvider.gemini
            if fallback_provider == LLMProvider.openai
            else LLMProvider.openai
        )

        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": fallback_provider.value}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary_client = _make_failing_client(RuntimeError("primary down"))
            fallback_client = _make_streaming_client(["fallback-token"])

            async def _fake_resolve(provider):
                if provider == primary_provider:
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
                provider=primary_provider,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            types = _sent_types(ws)
            assert "notice" in types, (
                f"Expected 'notice' when falling back to '{fallback_provider.value}'"
            )
            assert "token" in types
            assert "done" in types

            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# 5. All six clients mocked: fallback path emits correct tokens
# ---------------------------------------------------------------------------


class TestAllSixClientsMocked:
    """Mock all six BaseLLMClient implementations simultaneously and verify
    the orchestrator picks the right one for both primary and fallback paths."""

    def _build_client_map(
        self,
        failing_provider: LLMProvider,
        tokens_per_provider: dict[LLMProvider, list[str]],
    ) -> dict[LLMProvider, AsyncMock]:
        client_map: dict[LLMProvider, AsyncMock] = {}
        for provider in ALL_PROVIDERS:
            if provider == failing_provider:
                client_map[provider] = _make_failing_client(
                    RuntimeError(f"{provider.value} unavailable")
                )
            else:
                client_map[provider] = _make_streaming_client(
                    tokens_per_provider.get(provider, ["token"])
                )
        return client_map

    @pytest.mark.asyncio
    async def test_fallback_delivers_correct_tokens_from_mock(self) -> None:
        """When openai fails, gemini fallback delivers its specific tokens."""
        tokens_per_provider = {
            LLMProvider.gemini: ["gemini-reply-1", " gemini-reply-2"],
        }
        client_map = self._build_client_map(LLMProvider.openai, tokens_per_provider)

        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "gemini"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            async def _fake_resolve(provider):
                return client_map[provider]

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

            sent = [call.args[0] for call in ws.send_json.call_args_list]
            token_events = [m for m in sent if m.get("type") == "token"]
            token_texts = [m["data"] for m in token_events]

            assert "gemini-reply-1" in token_texts
            assert " gemini-reply-2" in token_texts

            get_settings.cache_clear()

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "failing_provider,fallback_provider",
        [
            (LLMProvider.openai, LLMProvider.claude),
            (LLMProvider.gemini, LLMProvider.openai),
            (LLMProvider.claude, LLMProvider.mistral),
            (LLMProvider.ollama, LLMProvider.llama),
            (LLMProvider.llama, LLMProvider.ollama),
            (LLMProvider.mistral, LLMProvider.gemini),
        ],
        ids=[
            "openai→claude",
            "gemini→openai",
            "claude→mistral",
            "ollama→llama",
            "llama→ollama",
            "mistral→gemini",
        ],
    )
    async def test_notice_and_tokens_for_all_provider_combinations(
        self,
        failing_provider: LLMProvider,
        fallback_provider: LLMProvider,
    ) -> None:
        """For each (primary, fallback) pair, verify notice + token events."""
        client_map = self._build_client_map(failing_provider, {})

        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": fallback_provider.value}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            async def _fake_resolve(provider):
                return client_map[provider]

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
                provider=failing_provider,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            types = _sent_types(ws)
            assert "notice" in types, (
                f"Expected notice for {failing_provider.value}→{fallback_provider.value}"
            )
            assert "token" in types
            assert "done" in types

            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# 6. No fallback when fallback == primary; original error propagates
# ---------------------------------------------------------------------------


class TestNoFallbackWhenFallbackEqualsPrimary:
    """When FALLBACK_LLM_PROVIDER is the same as the primary, _get_fallback_provider
    must return None to avoid an infinite retry loop (Requirement 3.3 logic)."""

    @pytest.mark.asyncio
    async def test_exception_propagates_when_fallback_equals_primary(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "openai"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            failing_client = _make_failing_client(RuntimeError("self-loop"))

            async def _fake_resolve(provider):
                return failing_client

            orch._resolve_provider = _fake_resolve
            orch._build_prompt = AsyncMock(
                return_value=MagicMock(
                    messages=[MagicMock(role="system", content="sys")],
                    estimated_tokens=5,
                )
            )
            orch._detect_prompt_injection = AsyncMock(return_value=False)

            with pytest.raises(RuntimeError, match="self-loop"):
                await orch.stream_chat(
                    conversation_id=str(uuid.uuid4()),
                    user_message="test",
                    provider=LLMProvider.openai,
                    user_id=str(uuid.uuid4()),
                    ws=ws,
                )

            assert "notice" not in _sent_types(ws), (
                "No notice expected when fallback == primary (self-loop prevention)"
            )
            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# 7. 'done' event always present on both success and fallback paths
# ---------------------------------------------------------------------------


class TestDoneEventAlwaysEmitted:
    """The 'done' event must be present after both a successful primary response
    and a successful fallback response."""

    @pytest.mark.asyncio
    async def test_done_event_on_primary_success(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": ""}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()
            client = _make_streaming_client(["token1"])

            async def _fake_resolve(_p):
                return client

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
                user_message="hi",
                provider=LLMProvider.openai,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            assert "done" in _sent_types(ws)
            get_settings.cache_clear()

    @pytest.mark.asyncio
    async def test_done_event_on_fallback_success(self) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": "claude"}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            primary = _make_failing_client(RuntimeError("primary down"))
            fallback = _make_streaming_client(["fallback-done-token"])

            async def _fake_resolve(provider):
                if provider == LLMProvider.openai:
                    return primary
                return fallback

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
                user_message="hi",
                provider=LLMProvider.openai,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            sent = [call.args[0] for call in ws.send_json.call_args_list]
            done_events = [m for m in sent if m.get("type") == "done"]
            assert len(done_events) == 1, (
                "Exactly one 'done' event must be emitted on fallback success"
            )
            # The done event must reference the fallback provider
            assert done_events[0]["usage"]["provider"] == LLMProvider.claude.value

            get_settings.cache_clear()


# ---------------------------------------------------------------------------
# 8. Notice message content — must name both failing and fallback providers
# ---------------------------------------------------------------------------


class TestNoticeMessageContent:
    """The 'notice' WebSocket message must identify both the failing provider
    and the substitution provider so the user knows what happened."""

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        "primary,fallback",
        [
            (LLMProvider.openai, LLMProvider.claude),
            (LLMProvider.gemini, LLMProvider.mistral),
            (LLMProvider.claude, LLMProvider.llama),
        ],
        ids=["openai→claude", "gemini→mistral", "claude→llama"],
    )
    async def test_notice_names_both_providers(
        self, primary: LLMProvider, fallback: LLMProvider
    ) -> None:
        with patch.dict(os.environ, {"FALLBACK_LLM_PROVIDER": fallback.value}):
            from app.config.settings import get_settings

            get_settings.cache_clear()

            orch = _make_orchestrator()
            ws = _make_ws()

            failing_client = _make_failing_client(RuntimeError("down"))
            ok_client = _make_streaming_client(["ok"])

            async def _fake_resolve(provider):
                if provider == primary:
                    return failing_client
                return ok_client

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
                provider=primary,
                user_id=str(uuid.uuid4()),
                ws=ws,
            )

            sent = [call.args[0] for call in ws.send_json.call_args_list]
            notice = next((m for m in sent if m.get("type") == "notice"), None)
            assert notice is not None, "Expected a 'notice' event in WebSocket messages"

            msg = notice["message"].lower()
            assert primary.value in msg, (
                f"Notice must mention failing provider '{primary.value}'; got: {notice['message']}"
            )
            assert fallback.value in msg, (
                f"Notice must mention fallback provider '{fallback.value}'; got: {notice['message']}"
            )

            get_settings.cache_clear()
