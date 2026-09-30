"""Unit tests for app.api.analysis.router — Phase 10.

Tests cover all three endpoints:

  POST /analysis/errors
    - Returns 200 with a well-formed ErrorAnalysisResponse
    - Request body is forwarded to ErrorAnalysisService.analyse()
    - service.analyse() exception → 500 with informative detail

  POST /analysis/errors/session
    - Returns 200 and delegates to ErrorAnalysisService.analyse()
    - session_id query param is forwarded correctly
    - lookback_minutes defaults to 60 when omitted
    - service.analyse() exception → 500

  GET /analysis/errors/{event_id}
    - 200 when the event exists in the DB
    - 404 when the event does not exist (get_by_id returns None)
    - UUID validation: non-UUID path segment → 422 Unprocessable Entity
    - service.analyse() exception → 500

  Auth guard
    - All three endpoints return 401/403 when JWT dependency not satisfied
      (confirmed by removing the dependency override)

Teaching notes (Phase 10 — AI Error Analysis):
  Router-layer testing verifies:
  1. HTTP mechanics — correct status codes, request body parsing, path params
  2. Dependency injection — auth guard fires, db session is provided
  3. Error translation — service exceptions → correct HTTP errors

  The router itself does almost no business logic. Its job is:
    a) Validate HTTP inputs (FastAPI does this automatically)
    b) Verify the event exists before calling the expensive service (GET route)
    c) Translate service exceptions to HTTP 500
    d) Log for observability

  Test strategy: mock ErrorAnalysisService and ObservabilityEventRepository at
  the router module level. This tests ONLY the router, not the service — the
  service has its own test file (test_error_analysis_service.py).

Requirements: Phase 10
"""

from __future__ import annotations

import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

# Ensure env vars before any app import
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")

from app.api.analysis.router import router
from app.database import get_db
from app.schemas.error_analysis import (
    AnalyseErrorRequest,
    ErrorAnalysisResponse,
    ErrorSeverity,
    FactsVsInference,
)
from app.security.dependencies import get_current_user


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def _make_jwt_user(sub: str = "user-phase10-test") -> MagicMock:
    user = MagicMock()
    user.sub  = sub
    user.role = "developer"
    return user


def _make_analysis_response(
    analysis_id: str | None = None,
    severity: ErrorSeverity = ErrorSeverity.HIGH,
    confidence: float = 0.87,
    summary: str = "Connection pool exhausted",
    likely_root_cause: str = "Slow queries holding pool connections",
    events_analysed: int = 12,
) -> ErrorAnalysisResponse:
    """Return a fully-populated ErrorAnalysisResponse for use as mock return value."""
    return ErrorAnalysisResponse(
        analysis_id=analysis_id or str(uuid.uuid4()),
        severity=severity,
        summary=summary,
        evidence=["Pool at 20/20 connections", "Latency spike +340%"],
        possible_causes=["Pool too small", "Slow query in deployment"],
        likely_root_cause=likely_root_cause,
        confidence=confidence,
        recommended_fix="1. Increase pool size 10→20. 2. Add query timeout 10s.",
        related_documentation=["runbooks/db-connection.md"],
        facts_vs_inference=FactsVsInference(
            facts=["Pool at capacity 20/20"],
            inferences=["New deployment may have introduced slow query"],
        ),
        low_confidence_warning=None,
        events_analysed=events_analysed,
        knowledge_chunks_retrieved=3,
        llm_provider="gemini",
    )


def _make_test_app() -> tuple[FastAPI, MagicMock]:
    """Return a minimal FastAPI app with auth + db overrides applied.

    Returns:
        (app, jwt_user_mock) — the app is ready for TestClient, jwt_user_mock
        can be inspected to verify user context was propagated.
    """
    app       = FastAPI()
    jwt_user  = _make_jwt_user()
    db_mock   = AsyncMock()

    app.include_router(router)
    app.dependency_overrides = {
        get_current_user: lambda: jwt_user,
        get_db:           lambda: db_mock,
    }
    return app, jwt_user


# ---------------------------------------------------------------------------
# POST /analysis/errors
# ---------------------------------------------------------------------------


class TestAnalyseErrorsPost:
    """POST /analysis/errors — the main error analysis endpoint."""

    def _client(
        self,
        service_return: ErrorAnalysisResponse | None = None,
        service_raises: Exception | None = None,
    ) -> TestClient:
        app, _ = _make_test_app()

        mock_service = AsyncMock()
        if service_raises:
            mock_service.analyse.side_effect = service_raises
        else:
            mock_service.analyse.return_value = (
                service_return or _make_analysis_response()
            )

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            return TestClient(app, raise_server_exceptions=False)

    def test_returns_200_with_well_formed_response(self) -> None:
        expected = _make_analysis_response(summary="DB pool exhausted")
        client   = self._client(service_return=expected)

        resp = client.post("/analysis/errors", json={})

        assert resp.status_code == 200
        body = resp.json()
        assert body["summary"]             == "DB pool exhausted"
        assert body["severity"]            == "HIGH"
        assert float(body["confidence"])   == pytest.approx(0.87, abs=0.01)
        assert "analysis_id" in body

    def test_response_contains_facts_vs_inference_block(self) -> None:
        client = self._client()
        resp   = client.post("/analysis/errors", json={})
        body   = resp.json()

        assert "facts_vs_inference"        in body
        assert "facts"                     in body["facts_vs_inference"]
        assert "inferences"                in body["facts_vs_inference"]

    def test_response_contains_ai_metadata_fields(self) -> None:
        client = self._client()
        resp   = client.post("/analysis/errors", json={})
        body   = resp.json()

        assert "events_analysed"            in body
        assert "knowledge_chunks_retrieved" in body
        assert "llm_provider"               in body

    def test_request_body_forwarded_to_service(self) -> None:
        """The router must pass the exact request body to ErrorAnalysisService.analyse."""
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            client.post(
                "/analysis/errors",
                json={"lookback_minutes": 45, "provider": "openai"},
            )

        mock_service.analyse.assert_awaited_once()
        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.lookback_minutes == 45
        assert call_arg.provider         == "openai"

    def test_service_exception_returns_500(self) -> None:
        client = self._client(service_raises=RuntimeError("DB connection lost"))
        resp   = client.post("/analysis/errors", json={})
        assert resp.status_code == 500
        assert "AI error analysis failed" in resp.json()["detail"]

    def test_lookback_minutes_below_1_returns_422(self) -> None:
        """Pydantic validation: lookback_minutes must be >= 1."""
        app, _ = _make_test_app()
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.post("/analysis/errors", json={"lookback_minutes": 0})
        assert resp.status_code == 422

    def test_lookback_minutes_above_1440_returns_422(self) -> None:
        """Pydantic validation: lookback_minutes must be <= 1440 (24 hours)."""
        app, _ = _make_test_app()
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.post("/analysis/errors", json={"lookback_minutes": 1441})
        assert resp.status_code == 422

    def test_default_lookback_minutes_is_30(self) -> None:
        """When no lookback_minutes is supplied the default is 30."""
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            client.post("/analysis/errors", json={})

        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.lookback_minutes == 30

    def test_requires_authentication(self) -> None:
        """Without auth override the endpoint must return 401 or 403."""
        app = FastAPI()
        app.include_router(router)
        # No dependency_overrides → real get_current_user runs → no JWT → 401/403
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.post("/analysis/errors", json={})
        assert resp.status_code in (401, 403)


# ---------------------------------------------------------------------------
# POST /analysis/errors/session
# ---------------------------------------------------------------------------


class TestAnalyseSessionPost:
    """POST /analysis/errors/session — session-scoped shortcut endpoint."""

    def _client_with_mock(self) -> tuple[TestClient, AsyncMock]:
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        patcher = patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        )
        patcher.start()
        client = TestClient(app, raise_server_exceptions=False)
        return client, mock_service

    def test_returns_200_for_valid_session_id(self) -> None:
        client, _ = self._client_with_mock()
        resp = client.post(
            "/analysis/errors/session",
            params={"session_id": "sess-abc-123"},
        )
        assert resp.status_code == 200

    def test_session_id_forwarded_to_service(self) -> None:
        client, mock_service = self._client_with_mock()
        client.post(
            "/analysis/errors/session",
            params={"session_id": "sess-xyz-789"},
        )

        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.session_id == "sess-xyz-789"

    def test_lookback_minutes_defaults_to_60(self) -> None:
        client, mock_service = self._client_with_mock()
        client.post(
            "/analysis/errors/session",
            params={"session_id": "sess-abc-123"},
            # no lookback_minutes → should default to 60
        )

        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.lookback_minutes == 60

    def test_lookback_minutes_can_be_overridden(self) -> None:
        client, mock_service = self._client_with_mock()
        client.post(
            "/analysis/errors/session",
            params={"session_id": "sess-abc-123", "lookback_minutes": 120},
        )

        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.lookback_minutes == 120

    def test_service_exception_returns_500(self) -> None:
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.side_effect = RuntimeError("LLM timeout")

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post(
                "/analysis/errors/session",
                params={"session_id": "sess-abc-123"},
            )

        assert resp.status_code == 500

    def test_missing_session_id_returns_422(self) -> None:
        """session_id is a required query parameter — omitting it → 422."""
        app, _ = _make_test_app()
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.post("/analysis/errors/session")
        assert resp.status_code == 422

    def test_requires_authentication(self) -> None:
        app    = FastAPI()
        app.include_router(router)
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.post(
            "/analysis/errors/session",
            params={"session_id": "sess-abc-123"},
        )
        assert resp.status_code in (401, 403)


# ---------------------------------------------------------------------------
# GET /analysis/errors/{event_id}
# ---------------------------------------------------------------------------


class TestAnalyseEventGet:
    """GET /analysis/errors/{event_id} — analyse a single known event."""

    _VALID_UUID = str(uuid.uuid4())

    def _client(
        self,
        event_exists: bool = True,
        service_return: ErrorAnalysisResponse | None = None,
        service_raises: Exception | None = None,
    ) -> TestClient:
        """Build a TestClient with both the repo and service mocked.

        ObservabilityEventRepository is imported inside the route function body,
        so we patch it at its definition module, not at the router module.
        """
        app, _ = _make_test_app()

        # Mock the repo so the router can verify the event exists
        mock_event = MagicMock() if event_exists else None
        mock_repo  = AsyncMock()
        mock_repo.get_by_id.return_value = mock_event

        # Mock the service
        mock_service = AsyncMock()
        if service_raises:
            mock_service.analyse.side_effect = service_raises
        else:
            mock_service.analyse.return_value = (
                service_return or _make_analysis_response()
            )

        with (
            # Now imported at module level in the router, so patch there
            patch(
                "app.api.analysis.router.ObservabilityEventRepository",
                return_value=mock_repo,
            ),
            patch(
                "app.api.analysis.router.ErrorAnalysisService",
                return_value=mock_service,
            ),
        ):
            return TestClient(app, raise_server_exceptions=False)

    def test_returns_200_when_event_exists(self) -> None:
        client = self._client(event_exists=True)
        resp   = client.get(f"/analysis/errors/{self._VALID_UUID}")
        assert resp.status_code == 200

    def test_response_body_is_error_analysis_response(self) -> None:
        expected = _make_analysis_response(summary="OOM error on worker pod")
        client   = self._client(service_return=expected)
        resp     = client.get(f"/analysis/errors/{self._VALID_UUID}")
        body     = resp.json()

        assert body["summary"]   == "OOM error on worker pod"
        assert "analysis_id"     in body
        assert "confidence"      in body
        assert "facts_vs_inference" in body

    def test_returns_404_when_event_does_not_exist(self) -> None:
        """Router must return 404 (not 500) when the event UUID is valid but unknown."""
        client = self._client(event_exists=False)
        resp   = client.get(f"/analysis/errors/{self._VALID_UUID}")
        assert resp.status_code == 404
        assert "not found" in resp.json()["detail"].lower()

    def test_404_detail_includes_the_event_id(self) -> None:
        client = self._client(event_exists=False)
        resp   = client.get(f"/analysis/errors/{self._VALID_UUID}")
        assert self._VALID_UUID in resp.json()["detail"]

    def test_non_uuid_path_segment_returns_422(self) -> None:
        """FastAPI UUID path parameter validation: non-UUID → 422."""
        app, _ = _make_test_app()
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.get("/analysis/errors/not-a-uuid")
        assert resp.status_code == 422

    def test_event_id_passed_to_service_as_string(self) -> None:
        """The router must forward event_id as a string UUID to the service request."""
        app, _       = _make_test_app()
        mock_repo    = AsyncMock()
        mock_repo.get_by_id.return_value = MagicMock()  # event exists
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        with (
            patch(
                "app.api.analysis.router.ObservabilityEventRepository",
                return_value=mock_repo,
            ),
            patch(
                "app.api.analysis.router.ErrorAnalysisService",
                return_value=mock_service,
            ),
        ):
            client = TestClient(app, raise_server_exceptions=False)
            client.get(f"/analysis/errors/{self._VALID_UUID}")

        call_arg: AnalyseErrorRequest = mock_service.analyse.call_args[0][0]
        assert call_arg.event_id == self._VALID_UUID

    def test_service_exception_returns_500(self) -> None:
        client = self._client(
            event_exists=True,
            service_raises=RuntimeError("LLM timed out"),
        )
        resp = client.get(f"/analysis/errors/{self._VALID_UUID}")
        assert resp.status_code == 500

    def test_requires_authentication(self) -> None:
        app    = FastAPI()
        app.include_router(router)
        client = TestClient(app, raise_server_exceptions=False)
        resp   = client.get(f"/analysis/errors/{self._VALID_UUID}")
        assert resp.status_code in (401, 403)


# ---------------------------------------------------------------------------
# Response schema contract tests
# ---------------------------------------------------------------------------


class TestResponseSchema:
    """Verify the response JSON always matches the documented ErrorAnalysisResponse schema."""

    _REQUIRED_FIELDS = {
        "analysis_id",
        "severity",
        "summary",
        "evidence",
        "possible_causes",
        "likely_root_cause",
        "confidence",
        "recommended_fix",
        "related_documentation",
        "facts_vs_inference",
        "events_analysed",
        "knowledge_chunks_retrieved",
        "llm_provider",
    }

    def test_all_required_fields_present_in_post_response(self) -> None:
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post("/analysis/errors", json={})

        body = resp.json()
        for field in self._REQUIRED_FIELDS:
            assert field in body, f"Missing required field: {field}"

    def test_confidence_is_float_between_0_and_1(self) -> None:
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response(confidence=0.73)

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post("/analysis/errors", json={})

        confidence = resp.json()["confidence"]
        assert 0.0 <= confidence <= 1.0

    def test_severity_is_one_of_valid_enum_values(self) -> None:
        valid_severities = {"CRITICAL", "HIGH", "MEDIUM", "LOW"}
        app, _           = _make_test_app()
        mock_service     = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response(
            severity=ErrorSeverity.CRITICAL
        )

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post("/analysis/errors", json={})

        assert resp.json()["severity"] in valid_severities

    def test_facts_vs_inference_contains_lists(self) -> None:
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response()

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post("/analysis/errors", json={})

        fvi = resp.json()["facts_vs_inference"]
        assert isinstance(fvi["facts"],      list)
        assert isinstance(fvi["inferences"], list)

    def test_low_confidence_warning_can_be_null(self) -> None:
        """low_confidence_warning is Optional — must be null when confidence >= 0.6."""
        app, _       = _make_test_app()
        mock_service = AsyncMock()
        mock_service.analyse.return_value = _make_analysis_response(confidence=0.90)

        with patch(
            "app.api.analysis.router.ErrorAnalysisService",
            return_value=mock_service,
        ):
            client = TestClient(app, raise_server_exceptions=False)
            resp   = client.post("/analysis/errors", json={})

        assert resp.json()["low_confidence_warning"] is None
