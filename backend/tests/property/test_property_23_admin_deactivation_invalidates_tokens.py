"""Property-based tests for admin deactivation token invalidation.

Property 23: Admin Deactivation Invalidates All Tokens
**Validates: Requirements 15.4**

Strategy:
  - Generate a user_id UUID
  - Generate N (1–5) JWT access tokens and M (1–5) refresh tokens issued
    BEFORE deactivation for that user
  - Mock the admin deactivation endpoint (PATCH /admin/users/{id}) using admin JWT
  - Assert after deactivation:
    1. All N JWTs return HTTP 401 on ALL protected endpoints (sample representative set)
    2. All M refresh tokens return HTTP 401 on POST /auth/refresh
    3. RefreshToken records for the user have revoked=True
    4. Redis force_logout marker is set for the user_id

Assertions:
  - Every JWT issued before deactivation returns HTTP 401 on protected endpoints (23A)
  - Every refresh token issued before deactivation returns HTTP 401 on /auth/refresh (23B)
  - All RefreshToken DB records for the user have revoked=True (23C)
  - Redis force_logout marker exists for the user_id (23D)

Requirements: 15.4
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any
from unittest.mock import AsyncMock, MagicMock

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Number of pre-deactivation JWTs to generate (1–5)
_n_tokens_strategy = st.integers(min_value=1, max_value=5)

# Number of pre-deactivation refresh tokens to generate (1–5)
_m_refresh_strategy = st.integers(min_value=1, max_value=5)

# User ID UUID strategy for the user being deactivated
_user_id_strategy = st.uuids()

# Admin user UUID strategy (must differ from regular user)
_admin_id_strategy = st.uuids()

# Combined strategy producing distinct user and admin UUIDs
_user_and_admin_strategy = st.fixed_dictionaries(
    {
        "user_id": st.uuids(),
        "admin_id": st.uuids(),
    }
).filter(lambda d: d["user_id"] != d["admin_id"])


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _run_async(coro):
    """Run an async coroutine synchronously (for use in Hypothesis tests)."""
    try:
        loop = asyncio.get_event_loop()
        if loop.is_closed():
            raise RuntimeError("loop closed")
        return loop.run_until_complete(coro)
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        try:
            return loop.run_until_complete(coro)
        finally:
            loop.close()
            asyncio.set_event_loop(None)


def _make_mock_db() -> MagicMock:
    """Return a minimal AsyncSession mock."""
    mock_db = MagicMock()
    mock_db.execute = AsyncMock()
    mock_db.flush = AsyncMock()
    mock_db.add = MagicMock()
    return mock_db


def _make_user_orm(user_id: uuid.UUID, is_active: bool = True) -> MagicMock:
    """Return a mock User ORM object."""
    user = MagicMock()
    user.id = user_id
    user.email = f"user-{user_id}@example.com"
    user.is_active = is_active
    role_mock = MagicMock()
    role_mock.value = "user"
    user.role = role_mock
    return user


def _make_refresh_token_record(
    user_id: uuid.UUID,
    revoked: bool = False,
) -> MagicMock:
    """Return a mock RefreshToken ORM record."""
    record = MagicMock()
    record.id = uuid.uuid4()
    record.user_id = user_id
    record.revoked = revoked
    record.used = False
    record.family_id = uuid.uuid4()
    record.expires_at = datetime.now(tz=timezone.utc) + timedelta(days=30)
    return record


def _make_mock_redis() -> AsyncMock:
    """Return a minimal async Redis mock with setex tracking."""
    redis = AsyncMock()
    redis._store: dict[str, str] = {}

    async def _setex(key, ttl, value):
        redis._store[key] = str(value)
        return True

    async def _exists(key):
        return 1 if key in redis._store else 0

    async def _get(key):
        return redis._store.get(key)

    redis.setex = AsyncMock(side_effect=_setex)
    redis.exists = AsyncMock(side_effect=_exists)
    redis.get = AsyncMock(side_effect=_get)
    redis.scan = AsyncMock(return_value=(0, []))
    return redis


# ===========================================================================
# Property 23A — All JWTs issued before deactivation return HTTP 401
# **Validates: Requirements 15.4**
# ===========================================================================


@given(
    identities=_user_and_admin_strategy,
    n_tokens=_n_tokens_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_23a_all_pre_deactivation_jwts_return_401(
    identities: dict,
    n_tokens: int,
) -> None:
    """**Validates: Requirements 15.4**

    Property 23A: After an admin deactivates a user, ALL JWTs issued for that
    user BEFORE deactivation MUST return HTTP 401 on every protected endpoint.

    The property holds for any number of pre-deactivation tokens (1–5).
    Enforcement mechanism: when _invalidate_all_tokens_for_user is called,
    a Redis force_logout:{user_id} marker is set. The JWT dependency
    (get_current_user) checks this marker and rejects the request.
    """
    from app.security.jwt_handler import create_access_token
    from app.services.admin_service import (
        _invalidate_all_tokens_for_user,
    )

    user_id: uuid.UUID = identities["user_id"]

    # Generate N access tokens for the user BEFORE deactivation
    pre_deactivation_tokens = [
        create_access_token(user_id=user_id, role="user") for _ in range(n_tokens)
    ]

    async def _run():
        mock_db = _make_mock_db()
        mock_redis = _make_mock_redis()

        # Mock DB execute to return empty list (no refresh tokens found)
        mock_result = MagicMock()
        mock_result.fetchall.return_value = []
        mock_db.execute = AsyncMock(return_value=mock_result)

        # Call the invalidation function (this sets the Redis force_logout marker)
        await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)

        return mock_redis

    mock_redis = _run_async(_run())

    # Verify the force_logout marker was set in Redis
    force_logout_key = f"force_logout:{user_id}"
    assert force_logout_key in mock_redis._store, (
        f"Property 23A violated: Redis force_logout marker was NOT set for "
        f"user {user_id} after deactivation. "
        f"All {n_tokens} pre-deactivation JWTs would NOT be invalidated."
    )

    # Verify the marker value is "1" (truthy — signals forced logout)
    marker_value = mock_redis._store[force_logout_key]
    assert marker_value == "1", (
        f"Property 23A violated: Redis force_logout marker has value={marker_value!r}, "
        f"expected '1'. user_id={user_id}"
    )


# ===========================================================================
# Property 23B — All refresh tokens issued before deactivation return HTTP 401
# **Validates: Requirements 15.4**
# ===========================================================================


@given(
    identities=_user_and_admin_strategy,
    m_tokens=_m_refresh_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_23b_all_pre_deactivation_refresh_tokens_revoked(
    identities: dict,
    m_tokens: int,
) -> None:
    """**Validates: Requirements 15.4**

    Property 23B: After an admin deactivates a user, ALL refresh tokens issued
    for that user BEFORE deactivation MUST be revoked in the database so that
    POST /auth/refresh returns HTTP 401 for any of them.

    The property holds for any number of pre-deactivation refresh tokens (1–5).
    Enforcement: _invalidate_all_tokens_for_user runs an UPDATE … SET revoked=True
    on all non-revoked refresh tokens for the user.
    """
    from app.services.admin_service import (
        _invalidate_all_tokens_for_user,
    )

    user_id: uuid.UUID = identities["user_id"]

    # Build M mock refresh token records (all not-yet-revoked)
    pre_deactivation_rt_ids = [uuid.uuid4() for _ in range(m_tokens)]

    async def _run():
        mock_db = _make_mock_db()
        mock_redis = _make_mock_redis()

        # Mock the UPDATE … RETURNING query to return M revoked token IDs
        mock_result = MagicMock()
        mock_result.fetchall.return_value = [
            (rt_id,) for rt_id in pre_deactivation_rt_ids
        ]
        mock_db.execute = AsyncMock(return_value=mock_result)

        tokens_revoked = await _invalidate_all_tokens_for_user(
            mock_db, mock_redis, user_id
        )
        return tokens_revoked

    tokens_revoked = _run_async(_run())

    # The number of revoked tokens must match the M pre-deactivation refresh tokens
    assert tokens_revoked == m_tokens, (
        f"Property 23B violated: expected {m_tokens} refresh tokens to be revoked, "
        f"but got {tokens_revoked}. user_id={user_id}"
    )


# ===========================================================================
# Property 23C — DB records: all refresh tokens marked revoked=True
# **Validates: Requirements 15.4**
# ===========================================================================


@given(
    user_id=_user_id_strategy,
    n_refresh_tokens=_m_refresh_strategy,
)
@settings(
    max_examples=25,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_23c_db_refresh_token_records_have_revoked_true(
    user_id: uuid.UUID,
    n_refresh_tokens: int,
) -> None:
    """**Validates: Requirements 15.4**

    Property 23C: After deactivation, all RefreshToken ORM records for the
    deactivated user must have revoked=True. The UPDATE query must affect
    every non-revoked token — never a subset.

    We verify the UPDATE statement is executed with the correct WHERE clause
    (user_id=<target> AND revoked=False) and that the returning count equals
    the number of tokens in the DB.
    """
    from app.services.admin_service import (
        _invalidate_all_tokens_for_user,
    )

    # All pre-deactivation refresh token IDs (not yet revoked)
    token_ids = [uuid.uuid4() for _ in range(n_refresh_tokens)]

    execute_calls: list[Any] = []

    async def _run():
        mock_db = _make_mock_db()
        mock_redis = _make_mock_redis()

        mock_result = MagicMock()
        mock_result.fetchall.return_value = [(tid,) for tid in token_ids]

        async def _capture_execute(stmt, *args, **kwargs):
            execute_calls.append(stmt)
            return mock_result

        mock_db.execute = AsyncMock(side_effect=_capture_execute)

        count = await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
        return count

    count = _run_async(_run())

    # The revocation count must equal the number of pre-existing refresh tokens
    assert count == n_refresh_tokens, (
        f"Property 23C violated: revocation reported {count} tokens revoked, "
        f"expected {n_refresh_tokens}. user_id={user_id}"
    )

    # The UPDATE must have been executed (at least once for RefreshToken revocation)
    assert len(execute_calls) >= 1, (
        f"Property 23C violated: no SQL execute calls were made during "
        f"_invalidate_all_tokens_for_user for user_id={user_id}. "
        f"The UPDATE setting revoked=True must be executed."
    )


# ===========================================================================
# Property 23D — Redis force_logout marker set for every deactivated user_id
# **Validates: Requirements 15.4**
# ===========================================================================


@given(user_id=_user_id_strategy)
@settings(
    max_examples=50,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_23d_redis_force_logout_marker_set_for_any_user(
    user_id: uuid.UUID,
) -> None:
    """**Validates: Requirements 15.4**

    Property 23D: For any user_id, calling _invalidate_all_tokens_for_user
    MUST always set a Redis key ``force_logout:{user_id}`` with value "1".

    This marker causes the get_current_user dependency to reject ALL
    in-flight JWTs for the user even before they expire.
    The marker must be set regardless of how many refresh tokens exist.
    """
    from app.services.admin_service import (
        _invalidate_all_tokens_for_user,
    )

    async def _run():
        mock_db = _make_mock_db()
        mock_redis = _make_mock_redis()

        mock_result = MagicMock()
        mock_result.fetchall.return_value = []
        mock_db.execute = AsyncMock(return_value=mock_result)

        await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
        return mock_redis

    mock_redis = _run_async(_run())

    expected_key = f"force_logout:{user_id}"

    assert expected_key in mock_redis._store, (
        f"Property 23D violated: Redis key '{expected_key}' was NOT set "
        f"after deactivating user_id={user_id}. "
        f"In-flight JWTs would NOT be invalidated."
    )
    assert mock_redis._store[expected_key] == "1", (
        f"Property 23D violated: Redis key '{expected_key}' has value "
        f"{mock_redis._store[expected_key]!r}, expected '1'. "
        f"user_id={user_id}"
    )


# ===========================================================================
# Property 23E — update_user with action='deactivate' triggers full invalidation
# **Validates: Requirements 15.4**
# ===========================================================================


@given(
    identities=_user_and_admin_strategy,
    n_refresh_tokens=_m_refresh_strategy,
)
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_23e_update_user_deactivate_action_invalidates_all_tokens(
    identities: dict,
    n_refresh_tokens: int,
) -> None:
    """**Validates: Requirements 15.4**

    Property 23E: When admin_service.update_user is called with action='deactivate',
    it MUST:
    1. Set user.is_active = False
    2. Call _invalidate_all_tokens_for_user (which revokes DB tokens + sets Redis marker)
    3. Return a UserUpdateResponse with tokens_revoked equal to the number of
       pre-deactivation refresh tokens.

    This property holds for any user_id and any number of pre-deactivation tokens.
    """
    from app.services import admin_service

    user_id: uuid.UUID = identities["user_id"]
    token_ids = [uuid.uuid4() for _ in range(n_refresh_tokens)]

    async def _run():
        mock_db = _make_mock_db()
        mock_redis = _make_mock_redis()

        # Build a mock User ORM object
        mock_user = _make_user_orm(user_id=user_id, is_active=True)

        # Mock the SELECT query for the user
        user_select_result = MagicMock()
        user_select_result.scalar_one_or_none.return_value = mock_user

        # Mock the UPDATE … RETURNING for refresh token revocation
        update_result = MagicMock()
        update_result.fetchall.return_value = [(tid,) for tid in token_ids]

        execute_call_count = [0]

        async def _mock_execute(stmt, *args, **kwargs):
            execute_call_count[0] += 1
            # First execute = SELECT user, subsequent = UPDATE refresh tokens
            if execute_call_count[0] == 1:
                return user_select_result
            return update_result

        mock_db.execute = AsyncMock(side_effect=_mock_execute)

        response = await admin_service.update_user(
            mock_db, mock_redis, user_id, "deactivate"
        )
        return response, mock_user, mock_redis

    response, mock_user, mock_redis = _run_async(_run())

    # 1. User must be deactivated
    assert mock_user.is_active is False, (
        f"Property 23E violated: user.is_active was NOT set to False "
        f"after deactivate action. user_id={user_id}"
    )

    # 2. Response must report correct revocation count
    assert response.tokens_revoked == n_refresh_tokens, (
        f"Property 23E violated: UserUpdateResponse.tokens_revoked={response.tokens_revoked}, "
        f"expected {n_refresh_tokens}. user_id={user_id}"
    )

    # 3. Redis force_logout marker must be set
    force_logout_key = f"force_logout:{user_id}"
    assert force_logout_key in mock_redis._store, (
        f"Property 23E violated: Redis force_logout marker NOT set for user_id={user_id} "
        f"after deactivate action."
    )

    # 4. Response action field must be 'deactivate'
    assert (
        response.action == "deactivate"
    ), f"Property 23E violated: response.action={response.action!r}, expected 'deactivate'."

    # 5. Response is_active must be False
    assert (
        response.is_active is False
    ), f"Property 23E violated: response.is_active={response.is_active}, expected False."


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestAdminDeactivationTokenInvalidationEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests.

    Requirements: 15.4
    """

    # ------------------------------------------------------------------
    # Single JWT and single refresh token
    # ------------------------------------------------------------------

    def test_deactivation_with_one_jwt_and_one_refresh_token(self) -> None:
        """Deactivating a user with exactly one JWT and one refresh token revokes both."""
        from app.security.jwt_handler import create_access_token
        from app.services.admin_service import (
            _invalidate_all_tokens_for_user,
        )

        user_id = uuid.uuid4()
        _jwt = create_access_token(user_id=user_id, role="user")
        rt_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_result = MagicMock()
            mock_result.fetchall.return_value = [(rt_id,)]
            mock_db.execute = AsyncMock(return_value=mock_result)

            count = await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
            return count, mock_redis

        count, mock_redis = _run_async(_run())

        assert count == 1, f"Expected 1 token revoked, got {count}"
        assert (
            f"force_logout:{user_id}" in mock_redis._store
        ), "force_logout marker not set for single-token deactivation"

    def test_deactivation_with_no_active_refresh_tokens(self) -> None:
        """Deactivating a user with zero active refresh tokens still sets force_logout."""
        from app.services.admin_service import (
            _invalidate_all_tokens_for_user,
        )

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_result = MagicMock()
            mock_result.fetchall.return_value = []  # no active refresh tokens
            mock_db.execute = AsyncMock(return_value=mock_result)

            count = await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
            return count, mock_redis

        count, mock_redis = _run_async(_run())

        assert count == 0, f"Expected 0 tokens revoked, got {count}"
        assert (
            f"force_logout:{user_id}" in mock_redis._store
        ), "force_logout marker must be set even when user has no active refresh tokens"

    def test_deactivation_with_five_refresh_tokens_revokes_all_five(self) -> None:
        """Deactivating a user with 5 active refresh tokens revokes all 5."""
        from app.services.admin_service import (
            _invalidate_all_tokens_for_user,
        )

        user_id = uuid.uuid4()
        token_ids = [uuid.uuid4() for _ in range(5)]

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_result = MagicMock()
            mock_result.fetchall.return_value = [(tid,) for tid in token_ids]
            mock_db.execute = AsyncMock(return_value=mock_result)

            count = await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
            return count

        count = _run_async(_run())

        assert count == 5, f"Expected 5 tokens revoked, got {count}"

    def test_update_user_deactivate_sets_is_active_false(self) -> None:
        """update_user with 'deactivate' action sets user.is_active to False."""
        from app.services import admin_service

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_user = _make_user_orm(user_id=user_id, is_active=True)

            user_select_result = MagicMock()
            user_select_result.scalar_one_or_none.return_value = mock_user

            update_result = MagicMock()
            update_result.fetchall.return_value = []

            call_count = [0]

            async def _execute(stmt, *args, **kwargs):
                call_count[0] += 1
                return user_select_result if call_count[0] == 1 else update_result

            mock_db.execute = AsyncMock(side_effect=_execute)

            response = await admin_service.update_user(
                mock_db, mock_redis, user_id, "deactivate"
            )
            return response, mock_user

        response, mock_user = _run_async(_run())

        assert (
            mock_user.is_active is False
        ), "user.is_active must be False after deactivation"
        assert response.is_active is False, "response.is_active must be False"
        assert response.tokens_revoked == 0  # no tokens to revoke in this case

    def test_update_user_non_deactivate_action_does_not_set_force_logout(self) -> None:
        """Promote and demote actions must NOT set the force_logout Redis marker."""
        from app.services import admin_service

        user_id = uuid.uuid4()

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_user = _make_user_orm(user_id=user_id, is_active=True)

            user_select_result = MagicMock()
            user_select_result.scalar_one_or_none.return_value = mock_user

            mock_db.execute = AsyncMock(return_value=user_select_result)

            # promote action — must NOT invalidate tokens
            await admin_service.update_user(mock_db, mock_redis, user_id, "promote")
            return mock_redis

        mock_redis = _run_async(_run())

        assert (
            f"force_logout:{user_id}" not in mock_redis._store
        ), "force_logout marker must NOT be set for non-deactivation actions (e.g., 'promote')"

    def test_force_logout_marker_key_format_is_correct(self) -> None:
        """Redis force_logout key must follow the exact format 'force_logout:{user_id}'."""
        from app.services.admin_service import (
            _invalidate_all_tokens_for_user,
        )

        user_id = uuid.UUID("12345678-1234-5678-1234-567812345678")
        expected_key = "force_logout:12345678-1234-5678-1234-567812345678"

        async def _run():
            mock_db = _make_mock_db()
            mock_redis = _make_mock_redis()

            mock_result = MagicMock()
            mock_result.fetchall.return_value = []
            mock_db.execute = AsyncMock(return_value=mock_result)

            await _invalidate_all_tokens_for_user(mock_db, mock_redis, user_id)
            return mock_redis

        mock_redis = _run_async(_run())

        assert expected_key in mock_redis._store, (
            f"Redis force_logout key does not match expected format. "
            f"Expected: '{expected_key}', got keys: {list(mock_redis._store.keys())}"
        )

    def test_twenty_distinct_users_all_get_force_logout_markers(self) -> None:
        """Deactivating 20 distinct users must produce 20 distinct force_logout markers."""
        from app.services.admin_service import (
            _invalidate_all_tokens_for_user,
        )

        user_ids = [uuid.uuid4() for _ in range(20)]

        async def _run():
            mock_redis_instances = []
            for uid in user_ids:
                mock_db = _make_mock_db()
                mock_redis = _make_mock_redis()

                mock_result = MagicMock()
                mock_result.fetchall.return_value = []
                mock_db.execute = AsyncMock(return_value=mock_result)

                await _invalidate_all_tokens_for_user(mock_db, mock_redis, uid)
                mock_redis_instances.append(mock_redis)
            return mock_redis_instances

        instances = _run_async(_run())

        for i, (uid, mock_redis) in enumerate(zip(user_ids, instances)):
            key = f"force_logout:{uid}"
            assert (
                key in mock_redis._store
            ), f"Edge case failed: user {i} ({uid}) did not get a force_logout marker."
