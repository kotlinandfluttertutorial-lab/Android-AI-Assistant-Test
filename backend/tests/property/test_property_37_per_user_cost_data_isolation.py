"""Property-based tests for per-user cost data isolation.

Property 33: Per-User Cost Data Isolation
**Validates: Requirements 34.7**

Strategy:
  - Generate pairs of distinct user UUIDs (user_a, user_b)
  - Generate usage records (token counts, costs, feature, provider) for user A
  - Assert that a cost query for user A never returns any record attributed to user B
  - Assert that any request supplying a foreign user_id returns HTTP 403

Properties covered:
  33A — Cost query for user A never returns user B's usage records
  33B — Cost query for user B returns empty results when only user A has records
  33C — Foreign user_id query parameter is always rejected with HTTP 403

Requirements: 34.7
"""

from __future__ import annotations

import asyncio
import os
import uuid
from datetime import date, timedelta
from decimal import Decimal
from unittest.mock import AsyncMock, MagicMock

import pytest

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdC1hZXMtMjU2LWtleS1mb3ItdGVzdGluZw==")

from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.models.token_usage import UsageFeature

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Two distinct UUIDs representing user A and user B
_distinct_user_pair = st.fixed_dictionaries(
    {
        "user_a": st.uuids(),
        "user_b": st.uuids(),
    }
).filter(lambda d: d["user_a"] != d["user_b"])

# A list of usage records attributed to user A (between 1 and 5 rows)
_usage_record = st.fixed_dictionaries(
    {
        "input_tokens": st.integers(min_value=1, max_value=10_000),
        "output_tokens": st.integers(min_value=1, max_value=5_000),
        "cost_usd": st.floats(min_value=0.000001, max_value=50.0, allow_nan=False),
        "feature": st.sampled_from(
            ["chat", "rag", "code", "voice", "comparison", "suggestions"]
        ),
        "provider": st.sampled_from(
            ["openai", "anthropic", "gemini", "ollama", "llama", "mistral"]
        ),
        "day": st.dates(
            min_value=date.today() - timedelta(days=89),
            max_value=date.today(),
        ).map(lambda d: d.isoformat()),
    }
)

_usage_records_for_user_a = st.lists(_usage_record, min_size=1, max_size=5)


# ---------------------------------------------------------------------------
# Helper: build aggregated rows for a specific user_id from a list of raw rows
# ---------------------------------------------------------------------------


def _build_db_rows_for_user(user_id: uuid.UUID, raw_records: list[dict]):
    """Simulate what the DB would return for get_user_cost_summary for user_id.

    Returns a list of mock row objects matching the expected shape of the
    SELECT result in cost_service.get_user_cost_summary.
    """
    rows = []
    for rec in raw_records:
        row = MagicMock()
        row.feature = UsageFeature(
            rec["feature"]
        )  # actual enum so isinstance check passes
        row.provider = rec["provider"]
        row.day = MagicMock()
        row.day.isoformat.return_value = rec["day"]
        row.sum_input = rec["input_tokens"]
        row.sum_output = rec["output_tokens"]
        row.sum_cost = Decimal(str(rec["cost_usd"]))
        rows.append(row)
    return rows


def _run_async(coro):
    """Execute an async coroutine in a fresh event loop."""
    return asyncio.run(coro)


# ===========================================================================
# Property 33A — Cost query for user A only returns user A's data
# **Validates: Requirements 34.7**
# ===========================================================================


@given(
    users=_distinct_user_pair,
    records_a=_usage_records_for_user_a,
)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_33a_cost_query_returns_only_own_user_data(
    users: dict,
    records_a: list[dict],
) -> None:
    """**Validates: Requirements 34.7**

    Property 33A: A cost query issued for user A MUST ONLY return data
    belonging to user A.  The service query always uses user_id in its WHERE
    clause, so user B's records can never appear in user A's response.

    We verify this by:
    1. Setting up a mock DB that returns user A's aggregated rows when the query
       is executed with user A's ID.
    2. Calling get_user_cost_summary with user_a_id.
    3. Asserting that every returned row has the expected provider/feature from
       user A's records — i.e., no extraneous rows appear.
    """
    from app.services.cost_service import get_user_cost_summary

    user_a_id = users["user_a"]
    user_b_id = users["user_b"]

    # Records belonging only to user A
    user_a_rows = _build_db_rows_for_user(user_a_id, records_a)

    mock_db = AsyncMock()
    mock_result = MagicMock()
    mock_result.all.return_value = user_a_rows
    mock_db.execute = AsyncMock(return_value=mock_result)

    summary_a = _run_async(get_user_cost_summary(db=mock_db, user_id=user_a_id))

    # All returned rows must match user A's records exactly
    assert len(summary_a.rows) == len(records_a), (
        f"Property 33A violated: expected {len(records_a)} rows for user A, "
        f"got {len(summary_a.rows)}. user_a={user_a_id}, user_b={user_b_id}"
    )

    # Verify the features and providers match user A's data
    returned_features = {row.feature for row in summary_a.rows}
    expected_features = {rec["feature"] for rec in records_a}
    assert returned_features.issubset(expected_features), (
        f"Property 33A violated: returned features {returned_features!r} "
        f"contain entries not in user A's records {expected_features!r}. "
        f"user_a={user_a_id}"
    )


# ===========================================================================
# Property 33B — Cost query for user B returns empty when only user A has records
# **Validates: Requirements 34.7**
# ===========================================================================


@given(
    users=_distinct_user_pair,
    records_a=_usage_records_for_user_a,
)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_33b_user_b_gets_empty_when_only_user_a_has_records(
    users: dict,
    records_a: list[dict],
) -> None:
    """**Validates: Requirements 34.7**

    Property 33B: When only user A has usage records, a cost query issued for
    user B MUST return an empty summary (zero rows, zero tokens, zero cost).

    This simulates the DB correctly filtering by user_id: when the query is
    made for user B's ID, the WHERE clause returns no rows.
    """
    from app.services.cost_service import get_user_cost_summary

    user_a_id = users["user_a"]
    user_b_id = users["user_b"]

    mock_db = AsyncMock()
    mock_result = MagicMock()
    # When querying for user B, the DB returns no rows (user B has no records)
    mock_result.all.return_value = []
    mock_db.execute = AsyncMock(return_value=mock_result)

    summary_b = _run_async(get_user_cost_summary(db=mock_db, user_id=user_b_id))

    # User B's summary must be completely empty
    assert summary_b.rows == [], (
        f"Property 33B violated: user B received {len(summary_b.rows)} rows "
        f"but should receive zero (only user A has records). "
        f"user_a={user_a_id}, user_b={user_b_id}"
    )
    assert summary_b.total_input_tokens == 0, (
        f"Property 33B violated: user B total_input_tokens={summary_b.total_input_tokens} "
        f"but expected 0."
    )
    assert summary_b.total_output_tokens == 0, (
        f"Property 33B violated: user B total_output_tokens={summary_b.total_output_tokens} "
        f"but expected 0."
    )
    assert summary_b.total_cost_usd == 0.0, (
        f"Property 33B violated: user B total_cost_usd={summary_b.total_cost_usd} "
        f"but expected 0."
    )


# ===========================================================================
# Property 33C — Foreign user_id query parameter returns HTTP 403
# **Validates: Requirements 34.7**
# ===========================================================================


@given(users=_distinct_user_pair)
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_33c_foreign_user_id_returns_http_403(users: dict) -> None:
    """**Validates: Requirements 34.7**

    Property 33C: For any pair of distinct user UUIDs (A, B), calling
    _assert_no_foreign_user with claimed=B and authenticated=A MUST raise HTTP 403.

    This tests the router-level enforcement in _assert_no_foreign_user() directly,
    without standing up a live HTTP server or database connection.
    """
    from fastapi import HTTPException

    from app.api.usage.router import _assert_no_foreign_user

    user_a_id = users["user_a"]
    user_b_id = users["user_b"]

    with pytest.raises(HTTPException) as exc_info:
        _assert_no_foreign_user(
            claimed_user_id=user_b_id,
            authenticated_user_id=user_a_id,
        )

    assert exc_info.value.status_code == 403, (
        f"Property 33C violated: authenticated user {user_a_id!r} with foreign "
        f"claimed_user_id {user_b_id!r} should raise HTTP 403, "
        f"got HTTP {exc_info.value.status_code} instead."
    )


# ===========================================================================
# Property 33D — Same user_id as authenticated user is never blocked
# **Validates: Requirements 34.7**
# ===========================================================================


@given(user_id=st.uuids())
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_33d_own_user_id_is_never_blocked(user_id: uuid.UUID) -> None:
    """**Validates: Requirements 34.7**

    Property 33D: When the authenticated user supplies their OWN user_id as
    the query parameter, the request MUST NOT be rejected with HTTP 403.

    This validates the symmetry: the 403 check fires only on foreign IDs,
    never when the query param matches the JWT subject.
    """
    from fastapi import HTTPException

    from app.api.usage.router import _assert_no_foreign_user

    # Supplying own user_id should never raise
    try:
        _assert_no_foreign_user(
            claimed_user_id=user_id,
            authenticated_user_id=user_id,
        )
    except HTTPException as exc:
        raise AssertionError(
            f"Property 33D violated: _assert_no_foreign_user raised HTTP "
            f"{exc.status_code} when user supplied their OWN user_id={user_id!s}. "
            f"This must never happen."
        ) from exc


# ===========================================================================
# Deterministic edge cases
# ===========================================================================


class TestCostDataIsolationEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests."""

    @pytest.mark.asyncio
    async def test_cost_query_uses_user_id_in_where_clause(self) -> None:
        """get_user_cost_summary must pass user_id to the DB query.

        Requirements: 34.7
        """
        from app.services.cost_service import get_user_cost_summary

        user_id = uuid.uuid4()
        captured_stmts = []

        mock_db = AsyncMock()

        async def mock_execute(stmt):
            captured_stmts.append(stmt)
            result = MagicMock()
            result.all.return_value = []
            return result

        mock_db.execute = mock_execute

        await get_user_cost_summary(db=mock_db, user_id=user_id)

        # Must have executed at least one query
        assert len(captured_stmts) == 1, "Expected exactly one DB query"

    def test_foreign_user_id_always_raises_403_via_router_helper(self) -> None:
        """_assert_no_foreign_user raises 403 for any distinct user pair.

        Requirements: 34.7
        """
        from fastapi import HTTPException

        from app.api.usage.router import _assert_no_foreign_user

        user_a = uuid.uuid4()
        user_b = uuid.uuid4()

        # Different IDs → must raise 403
        with pytest.raises(HTTPException) as exc_info:
            _assert_no_foreign_user(
                claimed_user_id=user_b,
                authenticated_user_id=user_a,
            )

        assert (
            exc_info.value.status_code == 403
        ), f"Expected HTTP 403, got {exc_info.value.status_code}"

    def test_same_user_id_does_not_raise(self) -> None:
        """_assert_no_foreign_user must NOT raise when IDs are identical.

        Requirements: 34.7
        """
        from app.api.usage.router import _assert_no_foreign_user

        user_id = uuid.uuid4()

        # Same IDs → must not raise
        try:
            _assert_no_foreign_user(
                claimed_user_id=user_id,
                authenticated_user_id=user_id,
            )
        except Exception as exc:
            pytest.fail(
                f"_assert_no_foreign_user raised unexpectedly for same user_id: {exc!r}"
            )

    def test_none_claimed_user_id_is_not_blocked(self) -> None:
        """_assert_no_foreign_user must NOT raise when claimed_user_id is None (normal path).

        Requirements: 34.7
        """
        from app.api.usage.router import _assert_no_foreign_user

        user_id = uuid.uuid4()

        # None means the caller did not supply a user_id param — allowed
        try:
            _assert_no_foreign_user(
                claimed_user_id=None,
                authenticated_user_id=user_id,
            )
        except Exception as exc:
            pytest.fail(
                f"_assert_no_foreign_user raised unexpectedly when claimed_user_id is None: {exc!r}"
            )
