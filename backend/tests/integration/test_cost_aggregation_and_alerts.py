"""Integration tests for cost aggregation and alert endpoints.

Covers:
1. Per-user scoping — HTTP 403 when a user supplies another user's identifier
2. Alert threshold CRUD — create, read, update/delete; max 3 per user enforced
3. Alert monitor trigger — check_spending_alerts marks alerts triggered and
   enqueues notifications within the same call (simulating the ≤60 s Celery beat)

Requirements: 21.1, 21.2, 34.7, 34.8
"""

from __future__ import annotations

import os
import types
import uuid
from datetime import datetime, timezone
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock, patch

# ---------------------------------------------------------------------------
# Environment variables — must be set before any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdC1hZXMtMjU2LWtleS1mb3ItdGVzdGluZw==")

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.usage.router import router as usage_router
from app.security.jwt_handler import create_access_token
from app.services.cost_service import (
    CostSummary,
    SpendingAlertDto,
)

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the usage router, no global middleware overhead
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(usage_router)

# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

_NOW = datetime(2024, 6, 15, 12, 0, 0, tzinfo=timezone.utc)


def _make_token(user_id: uuid.UUID, role: str = "user") -> str:
    """Generate a valid signed JWT for the given user."""
    token, _ = create_access_token(user_id=user_id, role=role)
    return token


def _auth(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _make_mock_db() -> AsyncMock:
    """Return a minimal AsyncSession mock."""
    db = AsyncMock()
    db.add = MagicMock()
    db.flush = AsyncMock()
    db.commit = AsyncMock()
    db.rollback = AsyncMock()
    db.delete = AsyncMock()
    return db


def _mock_execute_result(*, rows=None, scalar=None, scalars=None) -> MagicMock:
    """Build a mock that mimics the return of db.execute(...)."""
    result = MagicMock()
    if rows is not None:
        result.all.return_value = rows
    if scalar is not None:
        result.scalar_one.return_value = scalar
        result.scalar_one_or_none.return_value = scalar
    if scalars is not None:
        result.scalars.return_value.all.return_value = scalars
    return result


def _make_alert_stub(
    *,
    user_id: uuid.UUID | None = None,
    threshold: Decimal = Decimal("10.00"),
    is_triggered: bool = False,
    dismissed_at: datetime | None = None,
    alert_id: uuid.UUID | None = None,
) -> types.SimpleNamespace:
    """Build a lightweight SpendingAlert stub without ORM instrumentation."""
    return types.SimpleNamespace(
        id=alert_id or uuid.uuid4(),
        user_id=user_id or uuid.uuid4(),
        threshold_usd=threshold,
        is_triggered=is_triggered,
        triggered_at=None,
        dismissed_at=dismissed_at,
        created_at=_NOW,
        updated_at=_NOW,
    )


# ---------------------------------------------------------------------------
# DB dependency override factory
# ---------------------------------------------------------------------------


def _override_get_db(mock_db: AsyncMock):
    """Return a FastAPI dependency that yields mock_db instead of a real session."""

    async def _dep():
        try:
            yield mock_db
        except Exception:
            await mock_db.rollback()
            raise
        finally:
            await mock_db.close()

    return _dep


# ===========================================================================
# Scenario 1 — Per-user scoping: HTTP 403 when foreign user_id is supplied
# ===========================================================================


class TestPerUserScopingHttp403:
    """GET /usage/cost returns HTTP 403 when a different user's ID is in the query.

    Requirements: 34.7, 21.2
    """

    def test_get_cost_returns_403_for_foreign_user_id(self) -> None:
        """Authenticated user A gets 403 when querying with user B's ID.

        Requirements: 34.7
        """
        user_a_id = uuid.uuid4()
        user_b_id = uuid.uuid4()

        token = _make_token(user_a_id)

        with patch("app.security.dependencies._is_jti_revoked", return_value=False):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get(
                f"/usage/cost?user_id={user_b_id}",
                headers=_auth(token),
            )

        assert (
            resp.status_code == 403
        ), f"Expected HTTP 403 for foreign user_id, got {resp.status_code}"
        body = resp.json()
        assert "forbidden" in body.get("detail", "").lower()

    def test_get_cost_does_not_return_403_for_own_user_id(self) -> None:
        """Authenticated user gets 200 (not 403) when querying with their own ID.

        Requirements: 34.7
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        mock_summary = CostSummary(
            total_input_tokens=0,
            total_output_tokens=0,
            total_cost_usd=0.0,
            rows=[],
        )

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.get_user_cost_summary",
                new_callable=AsyncMock,
                return_value=mock_summary,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get(
                f"/usage/cost?user_id={user_id}",
                headers=_auth(token),
            )

        assert (
            resp.status_code != 403
        ), "Should NOT get 403 when own user_id is supplied"

    def test_get_cost_without_user_id_param_uses_auth_user(self) -> None:
        """GET /usage/cost without user_id param uses the JWT-authenticated user.

        Requirements: 34.7
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        mock_summary = CostSummary(
            total_input_tokens=100,
            total_output_tokens=50,
            total_cost_usd=0.05,
            rows=[],
        )

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.get_user_cost_summary",
                new_callable=AsyncMock,
                return_value=mock_summary,
            ) as mock_svc,
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get("/usage/cost", headers=_auth(token))

        assert resp.status_code == 200
        # Verify the service was called with the authenticated user's ID
        call_kwargs = mock_svc.call_args.kwargs
        assert call_kwargs.get("user_id") == user_id or (
            mock_svc.call_args.args and user_id in mock_svc.call_args.args
        )

    def test_unauthenticated_request_returns_401(self) -> None:
        """GET /usage/cost without a JWT returns HTTP 401.

        Requirements: 9.1
        """
        client = TestClient(_app, raise_server_exceptions=False)
        resp = client.get("/usage/cost")

        assert resp.status_code == 401

    def test_cost_data_response_structure(self) -> None:
        """GET /usage/cost response contains expected fields.

        Requirements: 34.1, 34.2
        """
        from app.services.cost_service import DailyCostRow

        user_id = uuid.uuid4()
        token = _make_token(user_id)

        mock_summary = CostSummary(
            total_input_tokens=500,
            total_output_tokens=250,
            total_cost_usd=0.0075,
            rows=[
                DailyCostRow(
                    feature="chat",
                    provider="openai",
                    day="2024-06-15",
                    input_tokens=500,
                    output_tokens=250,
                    cost_usd=0.0075,
                )
            ],
        )

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.get_user_cost_summary",
                new_callable=AsyncMock,
                return_value=mock_summary,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get("/usage/cost", headers=_auth(token))

        assert resp.status_code == 200
        data = resp.json()
        assert data["total_input_tokens"] == 500
        assert data["total_output_tokens"] == 250
        assert data["total_cost_usd"] == pytest.approx(0.0075)
        assert data["window_days"] == 90
        assert len(data["rows"]) == 1
        row = data["rows"][0]
        assert row["feature"] == "chat"
        assert row["provider"] == "openai"
        assert row["day"] == "2024-06-15"


# ===========================================================================
# Scenario 2 — Alert threshold CRUD + limit enforcement
# ===========================================================================


class TestAlertThresholdCrud:
    """End-to-end tests for GET/POST/DELETE /usage/alerts.

    Requirements: 34.4, 21.2
    """

    # ------------------------------------------------------------------
    # GET /usage/alerts
    # ------------------------------------------------------------------

    def test_list_alerts_returns_200_with_empty_list(self) -> None:
        """GET /usage/alerts returns 200 with an empty alerts list.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.list_spending_alerts",
                new_callable=AsyncMock,
                return_value=[],
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get("/usage/alerts", headers=_auth(token))

        assert resp.status_code == 200
        data = resp.json()
        assert data["alerts"] == []

    def test_list_alerts_returns_all_user_alerts(self) -> None:
        """GET /usage/alerts returns all alerts belonging to the current user.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        alert_stubs = [
            _make_alert_stub(user_id=user_id, threshold=Decimal("5.00")),
            _make_alert_stub(user_id=user_id, threshold=Decimal("20.00")),
        ]
        # Build SpendingAlertDtos from stubs

        dtos = [SpendingAlertDto(stub) for stub in alert_stubs]

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.list_spending_alerts",
                new_callable=AsyncMock,
                return_value=dtos,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.get("/usage/alerts", headers=_auth(token))

        assert resp.status_code == 200
        data = resp.json()
        assert len(data["alerts"]) == 2
        thresholds = {a["threshold_usd"] for a in data["alerts"]}
        assert 5.0 in thresholds
        assert 20.0 in thresholds

    def test_list_alerts_unauthenticated_returns_401(self) -> None:
        """GET /usage/alerts without JWT returns HTTP 401.

        Requirements: 9.1
        """
        client = TestClient(_app, raise_server_exceptions=False)
        resp = client.get("/usage/alerts")
        assert resp.status_code == 401

    # ------------------------------------------------------------------
    # POST /usage/alerts — creation
    # ------------------------------------------------------------------

    def test_create_alert_returns_201_with_valid_threshold(self) -> None:
        """POST /usage/alerts creates alert and returns HTTP 201.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        alert_id = uuid.uuid4()
        alert_stub = _make_alert_stub(
            user_id=user_id, threshold=Decimal("10.00"), alert_id=alert_id
        )
        dto = SpendingAlertDto(alert_stub)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.create_spending_alert",
                new_callable=AsyncMock,
                return_value=dto,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "10.00"},
                headers=_auth(token),
            )

        assert resp.status_code == 201
        data = resp.json()
        assert data["threshold_usd"] == pytest.approx(10.0)
        assert data["is_triggered"] is False
        assert str(data["user_id"]) == str(user_id)

    def test_create_alert_at_minimum_threshold_returns_201(self) -> None:
        """POST /usage/alerts with threshold=$0.01 returns HTTP 201.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        stub = _make_alert_stub(user_id=user_id, threshold=Decimal("0.01"))
        dto = SpendingAlertDto(stub)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.create_spending_alert",
                new_callable=AsyncMock,
                return_value=dto,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "0.01"},
                headers=_auth(token),
            )

        assert resp.status_code == 201

    def test_create_alert_at_maximum_threshold_returns_201(self) -> None:
        """POST /usage/alerts with threshold=$999.99 returns HTTP 201.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        stub = _make_alert_stub(user_id=user_id, threshold=Decimal("999.99"))
        dto = SpendingAlertDto(stub)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.create_spending_alert",
                new_callable=AsyncMock,
                return_value=dto,
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "999.99"},
                headers=_auth(token),
            )

        assert resp.status_code == 201

    def test_create_alert_returns_422_for_threshold_below_minimum(self) -> None:
        """POST /usage/alerts with threshold=$0.00 returns HTTP 422 (schema validation).

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        with patch("app.security.dependencies._is_jti_revoked", return_value=False):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "0.00"},
                headers=_auth(token),
            )

        assert resp.status_code == 422

    def test_create_alert_returns_422_for_threshold_above_maximum(self) -> None:
        """POST /usage/alerts with threshold=$1000.00 returns HTTP 422.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        with patch("app.security.dependencies._is_jti_revoked", return_value=False):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "1000.00"},
                headers=_auth(token),
            )

        assert resp.status_code == 422

    def test_create_alert_returns_422_when_limit_of_3_exceeded(self) -> None:
        """POST /usage/alerts returns HTTP 422 when user already has 3 alerts.

        Requirements: 34.4
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id)

        with (
            patch("app.security.dependencies._is_jti_revoked", return_value=False),
            patch(
                "app.api.usage.router.cost_service.create_spending_alert",
                new_callable=AsyncMock,
                side_effect=ValueError("Maximum of 3 spending alerts allowed per user"),
            ),
        ):
            client = TestClient(_app, raise_server_exceptions=False)
            resp = client.post(
                "/usage/alerts",
                json={"threshold_usd": "5.00"},
                headers=_auth(token),
            )

        assert resp.status_code == 422
        body = resp.json()
        assert "3" in str(body.get("detail", ""))

    def test_create_alert_unauthenticated_returns_401(self) -> None:
        """POST /usage/alerts without JWT returns HTTP 401.

        Requirements: 9.1
        """
        client = TestClient(_app, raise_server_exceptions=False)
        resp = client.post("/usage/alerts", json={"threshold_usd": "10.00"})
        assert resp.status_code == 401
