# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit
# File    : test_translation_router.py
# Purpose : Unit tests for POST /translate endpoint
#
# Architecture Layer : Test
# Pattern Used       : pytest + FastAPI TestClient with dependency overrides
#
# Key Concepts:
#   - Online vs. offline routing based on connectivity flag
#   - Input text length limit (10,000 chars)
#   - AIOrchestrator routing for online mode
#   - Offline stub response format
#   - JWT authentication bypass for tests
#
# Requirements: 10.5, 20.1, 20.2
# ============================================================

"""Unit tests for POST /translate endpoint.

Requirements: 10.5, 20.1, 20.2
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.translation.router import router as translation_router
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _fake_user() -> TokenPayload:
    return TokenPayload(
        sub="user-789",
        role="user",
        jti="jti-ghi",
        iat=datetime.now(tz=timezone.utc),
        exp=datetime(2099, 1, 1, tzinfo=timezone.utc),
    )


def _build_app() -> FastAPI:
    app = FastAPI()
    app.dependency_overrides[get_current_user] = lambda: _fake_user()
    app.include_router(translation_router)
    return app


@pytest.fixture()
def client() -> TestClient:
    return TestClient(_build_app(), raise_server_exceptions=False)


def _translate_payload(
    text: str = "Hello world",
    source: str = "en",
    target: str = "es",
    offline: bool = False,
    provider: str | None = None,
) -> dict:
    payload: dict = {
        "text": text,
        "source_language": source,
        "target_language": target,
        "offline": offline,
    }
    if provider is not None:
        payload["provider"] = provider
    return payload


# ---------------------------------------------------------------------------
# Input validation
# ---------------------------------------------------------------------------


class TestTranslationInputValidation:
    """Validate input constraints (Requirement 10.5)."""

    def test_rejects_text_over_10000_chars(self, client: TestClient) -> None:
        """Text exceeding 10,000 characters should return 422."""
        long_text = "a" * 10_001
        response = client.post(
            "/translate",
            json=_translate_payload(text=long_text),
        )
        assert response.status_code == 422

    def test_accepts_text_at_10000_chars(self, client: TestClient) -> None:
        """Text exactly 10,000 characters should be accepted."""
        text = "a" * 10_000
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = "translated"
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(text=text),
            )
        assert response.status_code in (200, 503)  # 503 only if LLM fails

    def test_rejects_missing_required_fields(self, client: TestClient) -> None:
        """Missing text/source_language/target_language should return 422."""
        response = client.post("/translate", json={"text": "Hello"})
        assert response.status_code == 422

    def test_requires_text_field(self, client: TestClient) -> None:
        """Missing text should return 422."""
        response = client.post(
            "/translate",
            json={"source_language": "en", "target_language": "fr"},
        )
        assert response.status_code == 422


# ---------------------------------------------------------------------------
# Offline routing (Requirement 10.5, 20.1)
# ---------------------------------------------------------------------------


class TestOfflineTranslation:
    """When offline=true, use on-device model without external call."""

    def test_offline_mode_returns_200(self, client: TestClient) -> None:
        """Offline translation should always succeed."""
        response = client.post(
            "/translate",
            json=_translate_payload(offline=True),
        )
        assert response.status_code == 200

    def test_offline_mode_sets_offline_mode_true(self, client: TestClient) -> None:
        """Response must indicate offline_mode=True."""
        response = client.post(
            "/translate",
            json=_translate_payload(
                text="Bonjour", source="fr", target="en", offline=True
            ),
        )
        assert response.status_code == 200
        assert response.json()["offline_mode"] is True

    def test_offline_mode_provider_is_offline_label(self, client: TestClient) -> None:
        """Offline provider should not be a cloud provider name."""
        response = client.post(
            "/translate",
            json=_translate_payload(offline=True),
        )
        assert response.status_code == 200
        provider = response.json()["provider"]
        assert "offline" in provider.lower() or "on-device" in provider.lower()

    def test_offline_mode_includes_source_text_in_response(
        self, client: TestClient
    ) -> None:
        """Offline translated text should contain the original text (stub behavior)."""
        text = "Good morning"
        response = client.post(
            "/translate",
            json=_translate_payload(text=text, offline=True),
        )
        assert response.status_code == 200
        # Stub response wraps original text
        assert text in response.json()["translated_text"]

    def test_offline_mode_does_not_call_ai_orchestrator(
        self, client: TestClient
    ) -> None:
        """AIOrchestrator must not be called in offline mode."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            response = client.post(
                "/translate",
                json=_translate_payload(offline=True),
            )
        assert response.status_code == 200
        MockOrch.assert_not_called()

    def test_offline_mode_echoes_language_pair(self, client: TestClient) -> None:
        """Response language codes must match the request."""
        response = client.post(
            "/translate",
            json=_translate_payload(
                text="Hello", source="en", target="de", offline=True
            ),
        )
        assert response.status_code == 200
        body = response.json()
        assert body["source_language"] == "en"
        assert body["target_language"] == "de"


# ---------------------------------------------------------------------------
# Online routing (Requirement 20.2)
# ---------------------------------------------------------------------------


class TestOnlineTranslation:
    """When offline=false, route to AIOrchestrator."""

    def test_online_mode_calls_ai_orchestrator(self, client: TestClient) -> None:
        """AIOrchestrator.complete should be called for online translations."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = "Hola mundo"
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(text="Hello world", offline=False),
            )
        assert response.status_code == 200
        mock_inst.complete.assert_called_once()

    def test_online_mode_sets_offline_mode_false(self, client: TestClient) -> None:
        """Response must indicate offline_mode=False for online translation."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = "Bonjour"
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(text="Hello", offline=False),
            )
        assert response.status_code == 200
        assert response.json()["offline_mode"] is False

    def test_online_mode_returns_translated_text(self, client: TestClient) -> None:
        """The AI Orchestrator's response text should be in translated_text."""
        expected = "Traducido correctamente"
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = expected
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(text="Correctly translated", offline=False),
            )
        assert response.status_code == 200
        assert response.json()["translated_text"] == expected

    def test_online_mode_returns_503_on_orchestrator_failure(
        self, client: TestClient
    ) -> None:
        """If AIOrchestrator raises, endpoint should return 503."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_inst.complete = AsyncMock(side_effect=Exception("LLM down"))
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(text="Hello", offline=False),
            )
        assert response.status_code == 503

    def test_online_mode_echoes_language_pair(self, client: TestClient) -> None:
        """Response language codes must match the request."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = "text"
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(
                    text="Hello", source="en", target="ja", offline=False
                ),
            )
        assert response.status_code == 200
        body = response.json()
        assert body["source_language"] == "en"
        assert body["target_language"] == "ja"


# ---------------------------------------------------------------------------
# Response schema
# ---------------------------------------------------------------------------


class TestTranslationResponseSchema:
    """Verify response schema for both modes."""

    def test_offline_response_has_required_fields(self, client: TestClient) -> None:
        """Offline response must contain all required fields."""
        response = client.post(
            "/translate",
            json=_translate_payload(offline=True),
        )
        assert response.status_code == 200
        body = response.json()
        for field in (
            "translated_text",
            "source_language",
            "target_language",
            "provider",
            "offline_mode",
        ):
            assert field in body, f"Missing field: {field}"

    def test_online_response_has_required_fields(self, client: TestClient) -> None:
        """Online response must contain all required fields."""
        with patch("app.api.translation.router.AIOrchestrator") as MockOrch:
            mock_inst = AsyncMock()
            mock_result = MagicMock()
            mock_result.text = "translated"
            mock_inst.complete = AsyncMock(return_value=mock_result)
            MockOrch.return_value = mock_inst
            response = client.post(
                "/translate",
                json=_translate_payload(offline=False),
            )
        assert response.status_code == 200
        body = response.json()
        for field in (
            "translated_text",
            "source_language",
            "target_language",
            "provider",
            "offline_mode",
        ):
            assert field in body, f"Missing field: {field}"


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------


class TestTranslationAuth:
    """Endpoint requires JWT authentication."""

    def test_unauthenticated_request_rejected(self) -> None:
        """Requests without JWT should be rejected."""
        app = FastAPI()
        app.include_router(translation_router)
        client = TestClient(app, raise_server_exceptions=False)
        response = client.post(
            "/translate",
            json=_translate_payload(),
        )
        assert response.status_code in (401, 403)
