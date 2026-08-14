"""Unit tests for app.api.prompts.router — HTTP layer tests.

Uses a minimal FastAPI test app with mocked PromptService and get_current_user
to validate HTTP contract: status codes, response bodies, error handling,
and RBAC enforcement.

Covers:
- GET  /prompts            — list all template names (admin only)
- GET  /prompts/{name}     — get active version (admin only)
- GET  /prompts/{name}/history — list all versions (admin only)
- PATCH /prompts/{name}   — create new version (admin only)
- POST /prompts/{name}/rollback?version=V — rollback (admin only)
- RBAC: non-admin users receive HTTP 403 on all endpoints
- TemplateNotFoundError surfaces as HTTP 404

Requirements: 25.1, 25.2
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.prompts.router import router as prompts_router
from app.database import get_db
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Helpers / fixtures
# ---------------------------------------------------------------------------

AUTHOR_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
ADMIN_USER_ID = uuid.UUID("cccccccc-cccc-cccc-cccc-cccccccccccc")
NON_ADMIN_USER_ID = uuid.UUID("dddddddd-dddd-dddd-dddd-dddddddddddd")
_NOW = datetime(2024, 6, 1, 12, 0, 0, tzinfo=timezone.utc)


def _admin_payload() -> TokenPayload:
    """Return a TokenPayload representing an admin user."""
    return TokenPayload(
        sub=str(ADMIN_USER_ID),
        role="admin",
        jti=str(uuid.uuid4()),
        iat=_NOW,
        exp=_NOW,
    )


def _user_payload() -> TokenPayload:
    """Return a TokenPayload representing a regular (non-admin) user."""
    return TokenPayload(
        sub=str(NON_ADMIN_USER_ID),
        role="user",
        jti=str(uuid.uuid4()),
        iat=_NOW,
        exp=_NOW,
    )


def _make_template_dict(
    name: str = "test_template",
    version: int = 1,
    content: str = "Hello {{ name }}",
    is_active: bool = True,
    author_id: uuid.UUID = AUTHOR_ID,
) -> dict:
    """Return a dict matching the PromptTemplateResponse schema."""
    return {
        "id": str(uuid.uuid4()),
        "name": name,
        "version": version,
        "content": content,
        "author_id": str(author_id),
        "is_active": is_active,
        "created_at": _NOW.isoformat(),
        "updated_at": _NOW.isoformat(),
    }


def _make_mock_template(
    name: str = "test_template",
    version: int = 1,
    content: str = "Hello {{ name }}",
    is_active: bool = True,
    author_id: uuid.UUID = AUTHOR_ID,
) -> MagicMock:
    """Return a MagicMock ORM row with the necessary attributes."""
    row = MagicMock()
    row.id = uuid.uuid4()
    row.name = name
    row.version = version
    row.content = content
    row.is_active = is_active
    row.author_id = author_id
    row.created_at = _NOW
    row.updated_at = _NOW
    return row


# ---------------------------------------------------------------------------
# Test app factory — avoids importing the full main.py app
# ---------------------------------------------------------------------------


def _build_test_app(current_user: TokenPayload) -> FastAPI:
    """Build a minimal FastAPI app with only the prompts router registered.

    Overrides get_current_user and get_db so that no live infrastructure is
    needed during tests.

    Args:
        current_user: The TokenPayload to return from get_current_user.

    Returns:
        A configured FastAPI test application.
    """
    test_app = FastAPI()
    test_app.include_router(prompts_router)

    # Override authentication: always return the provided user
    async def _override_current_user() -> TokenPayload:
        return current_user

    # Override database session: return a plain AsyncMock
    async def _override_get_db():
        yield AsyncMock()

    test_app.dependency_overrides[get_current_user] = _override_current_user
    test_app.dependency_overrides[get_db] = _override_get_db
    return test_app


def _admin_client() -> TestClient:
    return TestClient(_build_test_app(_admin_payload()))


def _user_client() -> TestClient:
    return TestClient(_build_test_app(_user_payload()))


# ---------------------------------------------------------------------------
# GET /prompts — list all template names
# ---------------------------------------------------------------------------


class TestListPromptNames:
    """Tests for GET /prompts."""

    def test_admin_gets_200(self) -> None:
        """Admin receives HTTP 200 with the list of template names."""
        with patch(
            "app.api.prompts.router.PromptService.list_template_names",
            new=AsyncMock(return_value=["code_helper", "meeting_summary"]),
        ):
            response = _admin_client().get("/prompts")

        assert response.status_code == 200
        body = response.json()
        assert body["names"] == ["code_helper", "meeting_summary"]
        assert body["total"] == 2

    def test_non_admin_gets_403(self) -> None:
        """Non-admin user receives HTTP 403."""
        response = _user_client().get("/prompts")
        assert response.status_code == 403

    def test_empty_template_store_returns_empty_names(self) -> None:
        """When no templates exist, names is [] and total is 0."""
        with patch(
            "app.api.prompts.router.PromptService.list_template_names",
            new=AsyncMock(return_value=[]),
        ):
            response = _admin_client().get("/prompts")

        assert response.status_code == 200
        body = response.json()
        assert body["names"] == []
        assert body["total"] == 0


# ---------------------------------------------------------------------------
# GET /prompts/{name} — get the active version
# ---------------------------------------------------------------------------


class TestGetCurrentPrompt:
    """Tests for GET /prompts/{name}."""

    def test_admin_gets_active_template(self) -> None:
        """Admin receives the active template version."""
        template = _make_mock_template(name="code_helper", version=3, is_active=True)
        with patch(
            "app.api.prompts.router.PromptService.get_current_template",
            new=AsyncMock(return_value=template),
        ):
            response = _admin_client().get("/prompts/code_helper")

        assert response.status_code == 200
        body = response.json()
        assert body["name"] == "code_helper"
        assert body["version"] == 3
        assert body["is_active"] is True

    def test_non_admin_gets_403(self) -> None:
        """Non-admin user receives HTTP 403."""
        response = _user_client().get("/prompts/code_helper")
        assert response.status_code == 403

    def test_unknown_template_returns_404(self) -> None:
        """TemplateNotFoundError surfaces as HTTP 404."""
        from app.services.prompt_service import TemplateNotFoundError

        with patch(
            "app.api.prompts.router.PromptService.get_current_template",
            new=AsyncMock(
                side_effect=TemplateNotFoundError("No active template found")
            ),
        ):
            response = _admin_client().get("/prompts/nonexistent")

        assert response.status_code == 404
        assert (
            "nonexistent" in response.json()["detail"]
            or "template" in response.json()["detail"].lower()
        )

    def test_response_contains_content_field(self) -> None:
        """Response body includes the template content."""
        template = _make_mock_template(content="You are a code assistant. {{ code }}")
        with patch(
            "app.api.prompts.router.PromptService.get_current_template",
            new=AsyncMock(return_value=template),
        ):
            response = _admin_client().get("/prompts/test_template")

        assert "content" in response.json()
        assert "code assistant" in response.json()["content"]

    def test_response_contains_author_id(self) -> None:
        """Response body includes the author_id field."""
        template = _make_mock_template(author_id=AUTHOR_ID)
        with patch(
            "app.api.prompts.router.PromptService.get_current_template",
            new=AsyncMock(return_value=template),
        ):
            response = _admin_client().get("/prompts/test_template")

        assert response.json()["author_id"] == str(AUTHOR_ID)


# ---------------------------------------------------------------------------
# GET /prompts/{name}/history — list all versions
# ---------------------------------------------------------------------------


class TestGetPromptHistory:
    """Tests for GET /prompts/{name}/history."""

    def test_admin_gets_version_history(self) -> None:
        """Admin receives the full version history for a named template."""
        versions = [
            _make_mock_template(version=1, is_active=False),
            _make_mock_template(version=2, is_active=False),
            _make_mock_template(version=3, is_active=True),
        ]
        with patch(
            "app.api.prompts.router.PromptService.list_versions",
            new=AsyncMock(return_value=versions),
        ):
            response = _admin_client().get("/prompts/test_template/history")

        assert response.status_code == 200
        body = response.json()
        assert body["name"] == "test_template"
        assert body["total"] == 3
        assert len(body["versions"]) == 3

    def test_non_admin_gets_403(self) -> None:
        """Non-admin user receives HTTP 403."""
        response = _user_client().get("/prompts/test_template/history")
        assert response.status_code == 403

    def test_unknown_template_returns_404(self) -> None:
        """Returns HTTP 404 when no versions exist for the named template."""
        with patch(
            "app.api.prompts.router.PromptService.list_versions",
            new=AsyncMock(return_value=[]),
        ):
            response = _admin_client().get("/prompts/nonexistent/history")

        assert response.status_code == 404

    def test_versions_ordered_ascending(self) -> None:
        """Versions in the response are ordered from v1 to vN."""
        versions = [
            _make_mock_template(version=1, is_active=False),
            _make_mock_template(version=2, is_active=True),
        ]
        with patch(
            "app.api.prompts.router.PromptService.list_versions",
            new=AsyncMock(return_value=versions),
        ):
            response = _admin_client().get("/prompts/test_template/history")

        nums = [v["version"] for v in response.json()["versions"]]
        assert nums == sorted(nums)
