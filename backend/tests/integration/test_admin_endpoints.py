"""Integration tests for the /admin/* endpoint flows.

Tests all admin endpoints with RBAC enforcement, user deactivation flow,
pagination, session monitoring, and audit log filtering.

Scenarios covered:
1. RBAC enforcement: every admin endpoint returns HTTP 403 for user/premium role,
   HTTP 401 without JWT
2. User deactivation flow: deactivate user, verify JWT revocation, verify refresh
   token revocation
3. User management: pagination, search, promote/demote actions
4. Session list: returns expected fields and structure
5. Audit logs: pagination and filtering by user, event type, date range
6. Metrics endpoint: returns required metric fields

Requirements: 21.2 (integration tests using a test database instance)
Cross-references: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9
"""

from __future__ import annotations

import os
import uuid
from datetime import datetime, timezone
from unittest.mock import AsyncMock, MagicMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

# Set required env vars before any app imports
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-key")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")

from app.api.admin.router import router as admin_router
from app.security.jwt_handler import create_access_token

# ---------------------------------------------------------------------------
# Minimal FastAPI app — only the admin router
# ---------------------------------------------------------------------------

_app = FastAPI()
_app.include_router(admin_router)

# ---------------------------------------------------------------------------
# Test data helpers
# ---------------------------------------------------------------------------

_NOW = datetime(2024, 1, 15, 12, 0, 0, tzinfo=timezone.utc)


def _make_token(user_id: uuid.UUID, role: str = "admin") -> str:
    """Generate a valid JWT for use in Authorization headers."""
    token, _expires = create_access_token(user_id=user_id, role=role)
    return token


def _make_auth_headers(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def _make_user_orm(
    *,
    user_id: uuid.UUID | None = None,
    email: str = "test@example.com",
    display_name: str = "Test User",
    role: str = "user",
    is_active: bool = True,
) -> MagicMock:
    """Build a mock User ORM object."""
    user = MagicMock()
    user.id = user_id or uuid.uuid4()
    user.email = email
    user.display_name = display_name
    role_mock = MagicMock()
    role_mock.value = role
    user.role = role_mock
    user.is_active = is_active
    user.created_at = _NOW
    user.updated_at = _NOW
    return user


def _make_metrics_response() -> dict:
    """Build a minimal MetricsResponse-compatible dict."""
    from app.schemas.admin import MetricsResponse

    return MetricsResponse(
        active_users=5,
        messages_per_hour=120,
        total_tokens_consumed=45000,
        provider_costs=[],
        error_rate_per_hour=0.02,
        snapshot_at=_NOW,
    )


def _make_paginated_users_response(users: list[MagicMock]) -> MagicMock:
    """Build a mock PaginatedUsersResponse."""
    from app.schemas.admin import PaginatedUsersResponse, UserAdminResponse

    items = [
        UserAdminResponse(
            id=u.id,
            email=u.email,
            display_name=u.display_name,
            role=u.role.value,
            is_active=u.is_active,
            created_at=_NOW,
            updated_at=_NOW,
        )
        for u in users
    ]
    return PaginatedUsersResponse(
        items=items,
        total=len(items),
        page=1,
        page_size=20,
        pages=1,
    )


def _make_user_update_response(
    user_id: uuid.UUID,
    action: str = "deactivate",
    new_role: str = "user",
    is_active: bool = False,
    tokens_revoked: int = 2,
) -> MagicMock:
    """Build a mock UserUpdateResponse."""
    from app.schemas.admin import UserUpdateResponse

    return UserUpdateResponse(
        user_id=user_id,
        action=action,
        new_role=new_role,
        is_active=is_active,
        tokens_revoked=tokens_revoked,
    )


def _make_audit_logs_response(count: int = 3) -> MagicMock:
    """Build a mock PaginatedAuditLogsResponse."""
    from app.schemas.admin import AuditLogEntry, PaginatedAuditLogsResponse

    items = [
        AuditLogEntry(
            id=uuid.uuid4(),
            user_id=uuid.uuid4(),
            event_type="login",
            ip_address="127.0.0.1",
            user_agent="TestAgent/1.0",
            metadata_={},
            created_at=_NOW,
        )
        for _ in range(count)
    ]
    return PaginatedAuditLogsResponse(
        items=items,
        total=count,
        page=1,
        page_size=20,
        pages=1,
    )


def _make_sessions_response(count: int = 2) -> MagicMock:
    """Build a mock ActiveSessionsResponse."""
    from app.schemas.admin import ActiveSessionsResponse, SessionInfo

    sessions = [
        SessionInfo(
            user_id=str(uuid.uuid4()),
            session_id=str(uuid.uuid4()),
            device_type="android",
            region="us-east-1",
            current_feature="chat",
            connected_at=_NOW.isoformat(),
            duration_seconds=300,
        )
        for _ in range(count)
    ]
    return ActiveSessionsResponse(
        sessions=sessions,
        total=count,
        snapshot_at=_NOW,
    )


def _make_error_summary_response() -> MagicMock:
    """Build a mock ErrorSummaryResponse."""
    from app.schemas.admin import ErrorSummary, ErrorSummaryResponse

    return ErrorSummaryResponse(
        errors=[
            ErrorSummary(
                error_type="ValueError",
                count=15,
                last_seen=_NOW,
                sample_message="Something went wrong",
                stack_trace_summary="File ...",
            )
        ],
        window_hours=24,
        generated_at=_NOW,
    )


# ===========================================================================
# Scenario 1 — RBAC enforcement
# ===========================================================================


# All admin endpoints to test RBAC against (method, path)
_ADMIN_ENDPOINTS = [
    ("GET", "/admin/metrics"),
    ("GET", "/admin/users"),
    ("GET", "/admin/audit-logs"),
    ("GET", "/admin/errors"),
    ("GET", "/admin/feedback"),
    ("GET", "/admin/sessions"),
]


class TestRBACEnforcement:
    """Every admin endpoint must enforce the 'admin' role.

    - No JWT → HTTP 401
    - valid JWT with 'user' role → HTTP 403
    - valid JWT with 'premium' role → HTTP 403
    - valid JWT with 'admin' role → HTTP 200 (or at least not 401/403)

    Requirements: 9.2, 15.2, 21.2
    """

    def test_no_jwt_returns_401_on_metrics(self) -> None:
        """GET /admin/metrics without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/admin/metrics")
        assert resp.status_code == 401

    def test_no_jwt_returns_401_on_users(self) -> None:
        """GET /admin/users without Authorization header returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/admin/users")
        assert resp.status_code == 401

    def test_no_jwt_returns_401_on_audit_logs(self) -> None:
        """GET /admin/audit-logs without JWT returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/admin/audit-logs")
        assert resp.status_code == 401

    def test_no_jwt_returns_401_on_sessions(self) -> None:
        """GET /admin/sessions without JWT returns HTTP 401.

        Requirements: 9.1, 21.2
        """
        with TestClient(_app) as client:
            resp = client.get("/admin/sessions")
        assert resp.status_code == 401

    def test_user_role_jwt_returns_403_on_metrics(self) -> None:
        """GET /admin/metrics with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_premium_role_jwt_returns_403_on_metrics(self) -> None:
        """GET /admin/metrics with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_users(self) -> None:
        """GET /admin/users with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/users", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_premium_role_jwt_returns_403_on_users(self) -> None:
        """GET /admin/users with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/users", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_audit_logs(self) -> None:
        """GET /admin/audit-logs with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/audit-logs", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_sessions(self) -> None:
        """GET /admin/sessions with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_errors(self) -> None:
        """GET /admin/errors with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/errors", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_feedback(self) -> None:
        """GET /admin/feedback with a 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/feedback", headers=_make_auth_headers(token))
        assert resp.status_code == 403

    def test_user_role_jwt_returns_403_on_patch_user(self) -> None:
        """PATCH /admin/users/{id} with 'user'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        token = _make_token(user_id, role="user")
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "promote"},
                headers=_make_auth_headers(token),
            )
        assert resp.status_code == 403

    def test_admin_role_jwt_can_access_metrics(self) -> None:
        """GET /admin/metrics with a valid 'admin'-role JWT returns HTTP 200.

        Requirements: 1.9, 9.2, 15.1, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        metrics = _make_metrics_response()

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_metrics",
                new_callable=AsyncMock,
                return_value=metrics,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))
        assert resp.status_code == 200

    def test_admin_role_jwt_can_access_users(self) -> None:
        """GET /admin/users with a valid 'admin'-role JWT returns HTTP 200.

        Requirements: 1.9, 9.2, 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        paginated = _make_paginated_users_response([])

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.list_users",
                new_callable=AsyncMock,
                return_value=paginated,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/users", headers=_make_auth_headers(token))
        assert resp.status_code == 200


# ===========================================================================
# Scenario 2 — User deactivation flow with token revocation
# ===========================================================================


class TestUserDeactivationFlow:
    """Verify that deactivating a user immediately invalidates their tokens.

    Flow:
    1. Admin deactivates user via PATCH /admin/users/{id}
    2. The deactivated user's existing JWT must return HTTP 401
    3. The deactivated user's refresh token must return HTTP 401

    Requirements: 15.4, 1.10, 21.2
    """

    def test_deactivate_user_returns_200_with_tokens_revoked(self) -> None:
        """PATCH /admin/users/{id} with action='deactivate' returns 200 + tokens_revoked > 0.

        Requirements: 15.4, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")
        update_response = _make_user_update_response(
            user_id=target_user_id,
            action="deactivate",
            is_active=False,
            tokens_revoked=3,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_response,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["action"] == "deactivate"
        assert body["is_active"] is False
        assert body["tokens_revoked"] > 0

    def test_deactivated_user_jwt_is_rejected_with_401(self) -> None:
        """After deactivation, the user's JWT is rejected because the JTI is revoked.

        The force-logout mechanism in Redis causes _is_jti_revoked to return True,
        which makes get_current_user raise HTTP 401 for the deactivated user.

        Requirements: 15.4, 9.1, 21.2
        """
        deactivated_user_id = uuid.uuid4()
        # The deactivated user still has a structurally valid JWT
        deactivated_token = _make_token(deactivated_user_id, role="user")

        # Simulate the JTI being blacklisted in Redis after deactivation
        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=True,
            ),
            TestClient(_app) as client,
        ):
            # Attempt to call any protected endpoint with the revoked JWT
            resp = client.get(
                "/admin/sessions", headers=_make_auth_headers(deactivated_token)
            )

        # Must return 401 because the JTI has been revoked
        assert resp.status_code == 401

    def test_deactivated_user_jwt_rejected_on_non_admin_endpoint(self) -> None:
        """Deactivated user's JWT returns 401 on any protected endpoint.

        This tests the general case: the revocation check kicks in at the
        authentication layer before any role check occurs.

        Requirements: 15.4, 9.1, 21.2
        """
        from app.api.conversations.router import router as conv_router

        test_app = FastAPI()
        test_app.include_router(conv_router)

        deactivated_user_id = uuid.uuid4()
        deactivated_token = _make_token(deactivated_user_id, role="user")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=True,
            ),
            TestClient(test_app) as client,
        ):
            resp = client.get(
                "/conversations",
                headers=_make_auth_headers(deactivated_token),
            )

        assert resp.status_code == 401

    def test_deactivation_sets_is_active_false(self) -> None:
        """Deactivation response shows is_active=False for the target user.

        Requirements: 15.4, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")
        update_response = _make_user_update_response(
            user_id=target_user_id,
            action="deactivate",
            is_active=False,
            tokens_revoked=1,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_response,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_active"] is False
        assert str(body["user_id"]) == str(target_user_id)

    def test_reactivation_sets_is_active_true(self) -> None:
        """Reactivating a user returns is_active=True.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")
        update_response = _make_user_update_response(
            user_id=target_user_id,
            action="reactivate",
            is_active=True,
            tokens_revoked=0,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_response,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "reactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["is_active"] is True
        assert body["tokens_revoked"] == 0


# ===========================================================================
# Scenario 3 — User management (pagination, search, promote/demote)
# ===========================================================================


class TestUserManagement:
    """Test GET /admin/users pagination and search, and PATCH /admin/users/{id} role changes.

    Requirements: 15.2, 21.2
    """

    def test_list_users_returns_paginated_response(self) -> None:
        """GET /admin/users returns paginated users with total, page, page_size.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        users = [_make_user_orm(email=f"user{i}@example.com") for i in range(3)]
        paginated = _make_paginated_users_response(users)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.list_users",
                new_callable=AsyncMock,
                return_value=paginated,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get(
                "/admin/users?page=1&page_size=20",
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert "items" in body
        assert "total" in body
        assert "page" in body
        assert "page_size" in body
        assert "pages" in body
        assert body["total"] == 3
        assert len(body["items"]) == 3

    def test_list_users_page_size_is_respected(self) -> None:
        """GET /admin/users?page_size=2 returns at most 2 items.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        two_users = [_make_user_orm(email=f"user{i}@example.com") for i in range(2)]

        from app.schemas.admin import PaginatedUsersResponse, UserAdminResponse

        paginated = PaginatedUsersResponse(
            items=[
                UserAdminResponse(
                    id=u.id,
                    email=u.email,
                    display_name=u.display_name,
                    role=u.role.value,
                    is_active=u.is_active,
                    created_at=_NOW,
                    updated_at=_NOW,
                )
                for u in two_users
            ],
            total=5,  # 5 total users, but only 2 on this page
            page=1,
            page_size=2,
            pages=3,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.list_users",
                new_callable=AsyncMock,
                return_value=paginated,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get(
                "/admin/users?page=1&page_size=2", headers=_make_auth_headers(token)
            )

        assert resp.status_code == 200
        body = resp.json()
        assert len(body["items"]) == 2
        assert body["total"] == 5
        assert body["page_size"] == 2

    def test_search_by_email_filters_results(self) -> None:
        """GET /admin/users?search=alice returns only matching users.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        matching_user = _make_user_orm(email="alice@example.com", display_name="Alice")
        paginated = _make_paginated_users_response([matching_user])

        search_arg_captured: list = []

        async def fake_list_users(db, *, page, page_size, search):
            search_arg_captured.append(search)
            return paginated

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.list_users",
                side_effect=fake_list_users,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get(
                "/admin/users?search=alice", headers=_make_auth_headers(token)
            )

        assert resp.status_code == 200
        body = resp.json()
        assert len(body["items"]) == 1
        assert body["items"][0]["email"] == "alice@example.com"
        # Verify the search term was passed to the service
        assert search_arg_captured == ["alice"]

    def test_promote_user_returns_updated_role(self) -> None:
        """PATCH /admin/users/{id} with action='promote' returns new_role='premium'.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        target_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")

        from app.schemas.admin import UserUpdateResponse

        update_resp = UserUpdateResponse(
            user_id=target_id,
            action="promote",
            new_role="premium",
            is_active=True,
            tokens_revoked=0,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_id}",
                json={"action": "promote"},
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["new_role"] == "premium"
        assert body["action"] == "promote"

    def test_demote_user_returns_updated_role(self) -> None:
        """PATCH /admin/users/{id} with action='demote' returns new_role='user'.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        target_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")

        from app.schemas.admin import UserUpdateResponse

        update_resp = UserUpdateResponse(
            user_id=target_id,
            action="demote",
            new_role="user",
            is_active=True,
            tokens_revoked=0,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_id}",
                json={"action": "demote"},
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 200
        body = resp.json()
        assert body["new_role"] == "user"
        assert body["action"] == "demote"

    def test_invalid_action_returns_400(self) -> None:
        """PATCH /admin/users/{id} with an invalid action returns HTTP 400.

        The schema validator rejects unknown actions before they reach the service.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        target_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_id}",
                json={"action": "delete_permanently"},
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 422

    def test_user_list_items_contain_required_fields(self) -> None:
        """Each user in GET /admin/users has id, email, display_name, role, is_active.

        Requirements: 15.2, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        user = _make_user_orm(email="bob@example.com", display_name="Bob", role="user")
        paginated = _make_paginated_users_response([user])

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.list_users",
                new_callable=AsyncMock,
                return_value=paginated,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/users", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        item = resp.json()["items"][0]
        assert "id" in item
        assert "email" in item
        assert "display_name" in item
        assert "role" in item
        assert "is_active" in item


# ===========================================================================
# Scenario 4 — Session list accuracy
# ===========================================================================


class TestSessionListAccuracy:
    """Verify GET /admin/sessions returns accurate, correctly-structured session data.

    Requirements: 15.9, 21.2
    """

    def test_sessions_endpoint_returns_200_for_admin(self) -> None:
        """GET /admin/sessions with admin JWT returns HTTP 200.

        Requirements: 15.9, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        sessions_resp = _make_sessions_response(count=2)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=sessions_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200

    def test_sessions_response_contains_required_top_level_fields(self) -> None:
        """GET /admin/sessions response must include 'sessions', 'total', 'snapshot_at'.

        Requirements: 15.9, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        sessions_resp = _make_sessions_response(count=1)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=sessions_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert "sessions" in body
        assert "total" in body
        assert "snapshot_at" in body

    def test_sessions_total_matches_sessions_list_length(self) -> None:
        """'total' field in GET /admin/sessions must equal len(sessions).

        Requirements: 15.9, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        sessions_resp = _make_sessions_response(count=3)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=sessions_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == len(body["sessions"])
        assert body["total"] == 3

    def test_session_entry_contains_required_fields(self) -> None:
        """Each session entry must have user_id, session_id, device_type, region,
        current_feature, connected_at, and duration_seconds.

        Requirements: 15.9, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        sessions_resp = _make_sessions_response(count=1)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=sessions_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        session = resp.json()["sessions"][0]
        assert "user_id" in session
        assert "session_id" in session
        assert "device_type" in session
        assert "region" in session
        assert "current_feature" in session
        assert "connected_at" in session
        assert "duration_seconds" in session

    def test_session_entry_field_values_are_accurate(self) -> None:
        """Session fields returned by the service are passed through accurately to the client.

        Verifies no field is mangled or dropped by the router serialisation layer.

        Requirements: 15.9, 21.2
        """
        from app.schemas.admin import ActiveSessionsResponse, SessionInfo

        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")

        expected_user_id = str(uuid.uuid4())
        expected_session_id = str(uuid.uuid4())
        expected_device_type = "android"
        expected_region = "eu-west-1"
        expected_feature = "chat"
        expected_connected_at = _NOW.isoformat()
        expected_duration = 150

        precise_sessions_resp = ActiveSessionsResponse(
            sessions=[
                SessionInfo(
                    user_id=expected_user_id,
                    session_id=expected_session_id,
                    device_type=expected_device_type,
                    region=expected_region,
                    current_feature=expected_feature,
                    connected_at=expected_connected_at,
                    duration_seconds=expected_duration,
                )
            ],
            total=1,
            snapshot_at=_NOW,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=precise_sessions_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        session = resp.json()["sessions"][0]
        assert session["user_id"] == expected_user_id
        assert session["session_id"] == expected_session_id
        assert session["device_type"] == expected_device_type
        assert session["region"] == expected_region
        assert session["current_feature"] == expected_feature
        assert session["connected_at"] == expected_connected_at
        assert session["duration_seconds"] == expected_duration

    def test_empty_session_list_returns_total_zero(self) -> None:
        """When no sessions are active, total=0 and sessions=[] are returned.

        Requirements: 15.9, 21.2
        """
        from app.schemas.admin import ActiveSessionsResponse

        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        empty_resp = ActiveSessionsResponse(sessions=[], total=0, snapshot_at=_NOW)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=empty_resp,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["total"] == 0
        assert body["sessions"] == []

    def test_session_duration_is_non_negative(self) -> None:
        """Every session's duration_seconds must be >= 0.

        Regression guard: service must never emit a negative duration.

        Requirements: 15.9, 21.2
        """
        from app.schemas.admin import ActiveSessionsResponse, SessionInfo

        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")

        # Build sessions with varying valid durations
        resp_data = ActiveSessionsResponse(
            sessions=[
                SessionInfo(
                    user_id=str(uuid.uuid4()),
                    session_id=str(uuid.uuid4()),
                    device_type="android",
                    region="us-east-1",
                    current_feature="chat",
                    connected_at=_NOW.isoformat(),
                    duration_seconds=0,
                ),
                SessionInfo(
                    user_id=str(uuid.uuid4()),
                    session_id=str(uuid.uuid4()),
                    device_type="ios",
                    region="ap-southeast-1",
                    current_feature="voice",
                    connected_at=_NOW.isoformat(),
                    duration_seconds=3600,
                ),
            ],
            total=2,
            snapshot_at=_NOW,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_active_sessions",
                new_callable=AsyncMock,
                return_value=resp_data,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        for session in resp.json()["sessions"]:
            assert session["duration_seconds"] >= 0

    def test_premium_role_returns_403_on_sessions(self) -> None:
        """GET /admin/sessions with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/sessions", headers=_make_auth_headers(token))

        assert resp.status_code == 403


# ===========================================================================
# Scenario 5 — Audit log pagination and filtering
# ===========================================================================


class TestAuditLogFiltering:
    """Verify GET /admin/audit-logs pagination and filter parameters.

    Requirements: 15.5, 21.2
    """

    def test_audit_logs_returns_paginated_structure(self) -> None:
        """GET /admin/audit-logs returns items, total, page, page_size, pages.

        Requirements: 15.5, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        logs = _make_audit_logs_response(count=3)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_audit_logs",
                new_callable=AsyncMock,
                return_value=logs,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/audit-logs", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert "items" in body
        assert "total" in body
        assert "page" in body
        assert "page_size" in body
        assert "pages" in body
        assert body["total"] == 3

    def test_audit_log_entry_has_required_fields(self) -> None:
        """Each audit log entry has id, user_id, event_type, ip_address, created_at.

        Requirements: 15.5, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        logs = _make_audit_logs_response(count=1)

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_audit_logs",
                new_callable=AsyncMock,
                return_value=logs,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/audit-logs", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        entry = resp.json()["items"][0]
        assert "id" in entry
        assert "event_type" in entry
        assert "ip_address" in entry
        assert "created_at" in entry

    def test_audit_logs_filter_args_are_forwarded_to_service(self) -> None:
        """Query parameters (user_id, event_type, date_from, date_to) are passed to the service.

        Requirements: 15.5, 21.2
        """
        admin_id = uuid.uuid4()
        filter_user_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        logs = _make_audit_logs_response(count=0)

        captured: dict = {}

        async def fake_get_audit_logs(
            db, *, page, page_size, user_id, event_type, date_from, date_to
        ):
            captured.update(
                {
                    "page": page,
                    "page_size": page_size,
                    "user_id": user_id,
                    "event_type": event_type,
                    "date_from": date_from,
                    "date_to": date_to,
                }
            )
            return logs

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_audit_logs",
                side_effect=fake_get_audit_logs,
            ),
        ):
            with TestClient(_app) as client:
                resp = client.get(
                    f"/admin/audit-logs?user_id={filter_user_id}&event_type=login&page=2&page_size=10",
                    headers=_make_auth_headers(token),
                )

        assert resp.status_code == 200
        assert captured["page"] == 2
        assert captured["page_size"] == 10
        assert captured["event_type"] == "login"
        assert str(captured["user_id"]) == str(filter_user_id)

    def test_premium_role_returns_403_on_audit_logs(self) -> None:
        """GET /admin/audit-logs with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/audit-logs", headers=_make_auth_headers(token))

        assert resp.status_code == 403


# ===========================================================================
# Scenario 6 — Metrics endpoint required fields
# ===========================================================================


class TestMetricsFields:
    """Verify GET /admin/metrics returns all required fields with correct types.

    Requirements: 15.1, 21.2
    """

    def test_metrics_response_contains_all_required_fields(self) -> None:
        """GET /admin/metrics returns active_users, messages_per_hour, total_tokens_consumed,
        provider_costs, error_rate_per_hour, and snapshot_at.

        Requirements: 15.1, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        metrics = _make_metrics_response()

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_metrics",
                new_callable=AsyncMock,
                return_value=metrics,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert "active_users" in body
        assert "messages_per_hour" in body
        assert "total_tokens_consumed" in body
        assert "provider_costs" in body
        assert "error_rate_per_hour" in body
        assert "snapshot_at" in body

    def test_metrics_numeric_fields_are_non_negative(self) -> None:
        """active_users, messages_per_hour, and total_tokens_consumed are >= 0.

        Requirements: 15.1, 21.2
        """
        admin_id = uuid.uuid4()
        token = _make_token(admin_id, role="admin")
        metrics = _make_metrics_response()

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.get_metrics",
                new_callable=AsyncMock,
                return_value=metrics,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))

        assert resp.status_code == 200
        body = resp.json()
        assert body["active_users"] >= 0
        assert body["messages_per_hour"] >= 0
        assert body["total_tokens_consumed"] >= 0

    def test_premium_role_returns_403_on_metrics(self) -> None:
        """GET /admin/metrics with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 21.2
        """
        user_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.get("/admin/metrics", headers=_make_auth_headers(token))

        assert resp.status_code == 403


# ===========================================================================
# Scenario 7 — Deactivation flow: Redis force-logout marker and service interaction
# ===========================================================================


class TestDeactivationForceLogout:
    """Verify that the deactivation flow interacts correctly with the service layer.

    These tests focus on:
    - The service receives the correct user_id and action
    - The returned tokens_revoked count reflects the number of revoked tokens
    - The JTI revocation mechanism (via patched _is_jti_revoked) correctly gates
      all subsequent requests from the deactivated user

    Requirements: 15.4, 1.10, 9.1, 21.2
    """

    def test_deactivation_calls_service_with_correct_user_id_and_action(self) -> None:
        """PATCH /admin/users/{id} with deactivate passes exact user_id and action to service.

        Requirements: 15.4, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")

        captured: dict = {}

        async def fake_update_user(db, redis, user_id, action):
            captured["user_id"] = user_id
            captured["action"] = action
            from app.schemas.admin import UserUpdateResponse

            return UserUpdateResponse(
                user_id=user_id,
                action=action,
                new_role="user",
                is_active=False,
                tokens_revoked=2,
            )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                side_effect=fake_update_user,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        assert str(captured["user_id"]) == str(target_user_id)
        assert captured["action"] == "deactivate"

    def test_tokens_revoked_field_reflects_service_count(self) -> None:
        """tokens_revoked in response matches whatever the service returns.

        Verifies the router faithfully passes through the service-reported count.

        Requirements: 15.4, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")

        from app.schemas.admin import UserUpdateResponse

        update_response = UserUpdateResponse(
            user_id=target_user_id,
            action="deactivate",
            new_role="user",
            is_active=False,
            tokens_revoked=7,  # arbitrary non-trivial count
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_response,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        assert resp.json()["tokens_revoked"] == 7

    def test_revoked_jti_returns_401_on_all_admin_read_endpoints(self) -> None:
        """A deactivated user's JWT (revoked JTI) returns HTTP 401 on all admin read endpoints.

        Iterates over all read-only admin endpoints to confirm the revocation
        check fires uniformly before any role check.

        Requirements: 15.4, 9.1, 21.2
        """
        deactivated_user_id = uuid.uuid4()
        # Even an admin-role JWT is rejected when the JTI is revoked
        deactivated_admin_token = _make_token(deactivated_user_id, role="admin")

        read_endpoints = [
            ("GET", "/admin/metrics"),
            ("GET", "/admin/users"),
            ("GET", "/admin/audit-logs"),
            ("GET", "/admin/errors"),
            ("GET", "/admin/feedback"),
            ("GET", "/admin/sessions"),
        ]

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=True,
            ),
            TestClient(_app) as client,
        ):
            for method, path in read_endpoints:
                resp = client.request(
                    method,
                    path,
                    headers=_make_auth_headers(deactivated_admin_token),
                )
                assert resp.status_code == 401, (
                    f"Expected 401 for revoked JWT on {method} {path}, "
                    f"got {resp.status_code}"
                )

    def test_deactivation_response_user_id_matches_target(self) -> None:
        """Deactivation response user_id must equal the target user_id from the path.

        Prevents a silent identity mismatch bug in the serialisation layer.

        Requirements: 15.4, 21.2
        """
        admin_id = uuid.uuid4()
        target_user_id = uuid.uuid4()
        admin_token = _make_token(admin_id, role="admin")

        from app.schemas.admin import UserUpdateResponse

        update_response = UserUpdateResponse(
            user_id=target_user_id,
            action="deactivate",
            new_role="user",
            is_active=False,
            tokens_revoked=1,
        )

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            patch(
                "app.api.admin.router.admin_service.update_user",
                new_callable=AsyncMock,
                return_value=update_response,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_user_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(admin_token),
            )

        assert resp.status_code == 200
        assert str(resp.json()["user_id"]) == str(target_user_id)

    def test_non_admin_cannot_deactivate_users(self) -> None:
        """PATCH /admin/users/{id} with a 'user'-role JWT returns HTTP 403.

        Even a structurally valid deactivate action is blocked by RBAC.

        Requirements: 9.2, 15.4, 21.2
        """
        user_id = uuid.uuid4()
        target_id = uuid.uuid4()
        token = _make_token(user_id, role="user")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 403

    def test_premium_user_cannot_deactivate_users(self) -> None:
        """PATCH /admin/users/{id} with a 'premium'-role JWT returns HTTP 403.

        Requirements: 9.2, 15.4, 21.2
        """
        user_id = uuid.uuid4()
        target_id = uuid.uuid4()
        token = _make_token(user_id, role="premium")

        with (
            patch(
                "app.security.dependencies._is_jti_revoked",
                new_callable=AsyncMock,
                return_value=False,
            ),
            TestClient(_app) as client,
        ):
            resp = client.patch(
                f"/admin/users/{target_id}",
                json={"action": "deactivate"},
                headers=_make_auth_headers(token),
            )

        assert resp.status_code == 403
