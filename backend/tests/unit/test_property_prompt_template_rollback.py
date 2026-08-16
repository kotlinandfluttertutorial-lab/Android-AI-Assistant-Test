"""Property-based tests for prompt template version rollback.

Property 16: Prompt Template Version Rollback
**Validates: Requirements 25.2**

Uses Hypothesis to generate N sequential template updates, roll back to a
random version V, and verify:

- 16a: Template content after rollback exactly matches the content at version V.
- 16b: The full version history from 1 to N remains intact after rollback
       (all N original version rows are preserved; the rollback adds exactly
       one new row, bringing the total to N + 1).
- 16c: The rolled-back row has a version number strictly greater than N
       (it is a new version, not an in-place mutation).
- 16d: Exactly one row is active after rollback — the newly created one.

Test strategy
-------------
The tests use a lightweight **fake in-memory repository** instead of a live
database session.  This follows the pattern established by
``test_property_token_usage_invariant.py`` and ``test_prompt_template_service.py``:
all storage is in a plain Python list, transaction management is a no-op, and
the ``PromptTemplateService`` business logic is exercised unchanged.

The fake repository faithfully implements the same version-sequencing and
active-flag management contract as ``PromptTemplateRepository`` so the tests
validate real service behaviour.
"""

from __future__ import annotations

import os

# Set required env vars before any app module imports (mirrors conftest.py).
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone

import pytest
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

from app.repositories.prompt_template_repository import TemplateNotFoundError
from app.services.prompt_template_service import PromptTemplateService

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------

_AUTHOR_ID = uuid.UUID("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
_NOW = datetime(2024, 1, 1, tzinfo=timezone.utc)

# ---------------------------------------------------------------------------
# Fake in-memory PromptTemplate row
# ---------------------------------------------------------------------------


@dataclass
class _FakeTemplateRow:
    """Lightweight stand-in for the SQLAlchemy PromptTemplate ORM object."""

    id: uuid.UUID = field(default_factory=uuid.uuid4)
    name: str = "chat_system"
    content: str = ""
    version: int = 1
    author_id: uuid.UUID = field(default_factory=lambda: _AUTHOR_ID)
    is_active: bool = True
    created_at: datetime = field(default_factory=lambda: _NOW)
    updated_at: datetime = field(default_factory=lambda: _NOW)

    def __repr__(self) -> str:  # pragma: no cover
        return (
            f"<FakeRow name={self.name!r} version={self.version} "
            f"is_active={self.is_active}>"
        )


# ---------------------------------------------------------------------------
# Fake in-memory repository
# ---------------------------------------------------------------------------


class _FakePromptTemplateRepository:
    """Pure-Python implementation of PromptTemplateRepository for testing.

    Stores all rows in a plain list.  Implements the same versioning contract
    as the real repository:
    - ``create_version``: inserts a new row with version = max + 1 and
      deactivates any previously active row for the same name.
    - ``rollback``: delegates to ``create_version`` with the historical content.
    - ``get_active``, ``get_version``, ``list_versions``: read-only queries on
      the in-memory list.

    All methods are ``async`` so that ``PromptTemplateService`` (which uses
    ``await self._repo.*``) can call them without modification.
    """

    def __init__(self) -> None:
        self._rows: list[_FakeTemplateRow] = []

    # ------------------------------------------------------------------
    # Read operations
    # ------------------------------------------------------------------

    async def get_active(self, name: str) -> _FakeTemplateRow:
        """Return the active row for *name*.

        Raises:
            TemplateNotFoundError: When no active version exists.
        """
        for row in reversed(self._rows):
            if row.name == name and row.is_active:
                return row
        raise TemplateNotFoundError(
            f"No active prompt template found with name={name!r}"
        )

    async def get_version(self, name: str, version: int) -> _FakeTemplateRow:
        """Return the specific version row for *name*.

        Raises:
            TemplateNotFoundError: When the requested version does not exist.
        """
        for row in self._rows:
            if row.name == name and row.version == version:
                return row
        raise TemplateNotFoundError(
            f"No version {version} found for template name={name!r}"
        )

    async def list_versions(self, name: str) -> list[_FakeTemplateRow]:
        """Return all version rows for *name*, sorted by version ascending."""
        return sorted(
            [r for r in self._rows if r.name == name],
            key=lambda r: r.version,
        )

    # ------------------------------------------------------------------
    # Write operations
    # ------------------------------------------------------------------

    async def create_version(
        self,
        name: str,
        content: str,
        author_id: uuid.UUID,
    ) -> _FakeTemplateRow:
        """Create a new version row and deactivate the previous active row."""
        existing = await self.list_versions(name)
        next_version = (max(r.version for r in existing) + 1) if existing else 1

        # Deactivate any currently active version for this name.
        for row in self._rows:
            if row.name == name and row.is_active:
                row.is_active = False

        new_row = _FakeTemplateRow(
            name=name,
            content=content,
            version=next_version,
            author_id=author_id,
            is_active=True,
        )
        self._rows.append(new_row)
        return new_row

    async def rollback(
        self,
        name: str,
        version_number: int,
        author_id: uuid.UUID | None = None,
    ) -> _FakeTemplateRow:
        """Non-destructively restore content from *version_number*.

        Creates a new version whose content is copied from the historical row.
        All existing rows are preserved unchanged.

        Raises:
            TemplateNotFoundError: When *version_number* does not exist.
        """
        target = await self.get_version(name, version_number)
        effective_author = author_id if author_id is not None else target.author_id
        return await self.create_version(
            name=name,
            content=target.content,
            author_id=effective_author,
        )

    # ------------------------------------------------------------------
    # Introspection helpers (used by property tests directly)
    # ------------------------------------------------------------------

    def all_rows_for(self, name: str) -> list[_FakeTemplateRow]:
        """Return all rows for *name* (any is_active state), version ASC."""
        return sorted(
            [r for r in self._rows if r.name == name],
            key=lambda r: r.version,
        )

    def count_active(self, name: str) -> int:
        """Return the number of active rows for *name*."""
        return sum(1 for r in self._rows if r.name == name and r.is_active)


# ---------------------------------------------------------------------------
# Hypothesis strategies
# ---------------------------------------------------------------------------

# Template name: lowercase letters and underscores, 3–30 chars.
st_template_name = st.text(
    alphabet="abcdefghijklmnopqrstuvwxyz_",
    min_size=3,
    max_size=30,
)

# Template content: any printable text, 1–200 chars, guaranteed non-empty.
st_content = st.text(
    alphabet=st.characters(whitelist_categories=("L", "N", "P", "Z")),
    min_size=1,
    max_size=200,
)

# Number of sequential updates to apply: 2–10 (small enough to be fast).
st_num_updates = st.integers(min_value=2, max_value=10)


# ---------------------------------------------------------------------------
# Helper: build a populated service + repo with N versions
# ---------------------------------------------------------------------------


async def _create_service_with_n_versions(
    name: str,
    contents: list[str],
) -> tuple[PromptTemplateService, _FakePromptTemplateRepository]:
    """Apply ``len(contents)`` sequential updates and return (service, repo).

    After this call the repo contains exactly ``len(contents)`` version rows
    numbered 1 … N, with version N being the active one.
    """
    repo = _FakePromptTemplateRepository()
    service = PromptTemplateService(repo)
    for content in contents:
        await service.update_template(name, content, _AUTHOR_ID)
    return service, repo


# ---------------------------------------------------------------------------
# Property 16a + 16b + 16c + 16d
# **Validates: Requirements 25.2**
# ---------------------------------------------------------------------------


@given(
    name=st_template_name,
    contents=st.lists(st_content, min_size=2, max_size=10),
    rollback_index=st.integers(min_value=0, max_value=9),
)
@settings(max_examples=100, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_16_prompt_template_version_rollback(
    name: str,
    contents: list[str],
    rollback_index: int,
) -> None:
    """**Validates: Requirements 25.2**

    Property 16: Prompt Template Version Rollback

    Given N sequential template updates and a rollback to a random version V:

    - 16a: The active template content after rollback exactly matches the
           content that was stored at version V.
    - 16b: The full version history from version 1 to version N is intact after
           rollback — all N original version rows still exist and their content
           has not changed.
    - 16c: The new row created by rollback has a version number > N (it is an
           additive, non-destructive operation).
    - 16d: Exactly one row is active after rollback — the newly created one.
    """
    n = len(contents)
    # Clamp rollback_index to a valid 0-based index into contents (so V ∈ [1, N])
    v_index = rollback_index % n  # 0-based index
    target_version = v_index + 1  # 1-based version number

    # --- Setup: create N sequential versions ---
    service, repo = await _create_service_with_n_versions(name, contents)

    # Sanity-check the setup: we should have exactly N version rows numbered 1..N
    all_rows_before = repo.all_rows_for(name)
    assert (
        len(all_rows_before) == n
    ), f"[Setup] Expected {n} rows before rollback, got {len(all_rows_before)}"
    for i, row in enumerate(all_rows_before):
        assert (
            row.version == i + 1
        ), f"[Setup] Row at index {i} should have version {i + 1}, got {row.version}"
        assert row.content == contents[i], (
            f"[Setup] Row v{i + 1} content mismatch: "
            f"expected {contents[i]!r}, got {row.content!r}"
        )

    # --- Action: rollback to version V ---
    rolled_back_row = await service.rollback_template(name, target_version, _AUTHOR_ID)

    # --- Assertions ---

    # 16a: Active template content matches the content at version V
    active_content = await service.get_template(name)
    expected_content = contents[v_index]
    assert active_content == expected_content, (
        f"[Property 16a] After rollback to v{target_version}, active content "
        f"should be {expected_content!r} but got {active_content!r}"
    )

    # Also verify the returned row carries the correct content
    assert rolled_back_row.content == expected_content, (
        f"[Property 16a] Returned rollback row content should be {expected_content!r}, "
        f"got {rolled_back_row.content!r}"
    )

    # 16b: All N original versions (1..N) still exist with their original content
    all_rows_after = repo.all_rows_for(name)
    # Total rows = N originals + 1 rollback row = N + 1
    assert len(all_rows_after) == n + 1, (
        f"[Property 16b] After rollback, expected {n + 1} total rows "
        f"(N originals + 1 rollback), got {len(all_rows_after)}"
    )

    # Check that all original version rows (1..N) are preserved unchanged
    for i in range(n):
        original_row = all_rows_after[i]  # list is sorted by version ASC
        assert original_row.version == i + 1, (
            f"[Property 16b] Original version {i + 1} is missing or misnumbered: "
            f"got version {original_row.version}"
        )
        assert original_row.content == contents[i], (
            f"[Property 16b] Original v{i + 1} content was mutated: "
            f"expected {contents[i]!r}, got {original_row.content!r}"
        )

    # 16c: The rollback row has a version strictly greater than N
    rollback_row_version = all_rows_after[-1].version  # newest = last after sort
    assert (
        rollback_row_version > n
    ), f"[Property 16c] Rollback row version {rollback_row_version} must be > N={n}"
    # Specifically it should be N + 1
    assert rollback_row_version == n + 1, (
        f"[Property 16c] Rollback row version should be N+1={n + 1}, "
        f"got {rollback_row_version}"
    )

    # 16d: Exactly one row is active after rollback
    active_count = repo.count_active(name)
    assert active_count == 1, (
        f"[Property 16d] Exactly one row must be active after rollback, "
        f"got {active_count} active rows"
    )

    # The single active row must be the rollback row (version N+1)
    active_row = await service.get_active_template(name)
    assert active_row.version == n + 1, (
        f"[Property 16d] The active row must be the rollback row (v{n + 1}), "
        f"got v{active_row.version}"
    )


# ---------------------------------------------------------------------------
# Property 16 — repeated rollbacks preserve history
# **Validates: Requirements 25.2**
# ---------------------------------------------------------------------------


@given(
    name=st_template_name,
    contents=st.lists(st_content, min_size=3, max_size=8),
    rollback_indices=st.lists(
        st.integers(min_value=0, max_value=7), min_size=2, max_size=4
    ),
)
@settings(max_examples=50, suppress_health_check=[HealthCheck.too_slow], deadline=None)
@pytest.mark.asyncio
async def test_16_repeated_rollbacks_preserve_all_history(
    name: str,
    contents: list[str],
    rollback_indices: list[int],
) -> None:
    """**Validates: Requirements 25.2**

    Property 16 (extended): Performing multiple successive rollbacks never
    deletes any existing row.  After K rollbacks the total row count is
    N + K (N original versions plus one new row per rollback).

    Each rollback still produces a row whose content matches the historical
    version it targeted.
    """
    n = len(contents)
    service, repo = await _create_service_with_n_versions(name, contents)

    total_versions_so_far = n  # grows by 1 with each rollback

    for rollback_index in rollback_indices:
        # Clamp index to the number of *original* versions (1..N) so we always
        # target a version that existed before any rollback was performed.
        v_index = rollback_index % n  # 0-based into original contents
        target_version = v_index + 1  # 1-based version number

        await service.rollback_template(name, target_version, _AUTHOR_ID)
        total_versions_so_far += 1

        # Total rows must grow by exactly 1 each time
        all_rows = repo.all_rows_for(name)
        assert len(all_rows) == total_versions_so_far, (
            f"[16-repeated] After rollback, expected {total_versions_so_far} total rows, "
            f"got {len(all_rows)}"
        )

        # Original N rows (1..N) must still be intact
        for i in range(n):
            row = all_rows[i]
            assert (
                row.version == i + 1
            ), f"[16-repeated] Original v{i + 1} is missing after rollback"
            assert (
                row.content == contents[i]
            ), f"[16-repeated] Original v{i + 1} content was mutated"

        # Active content must match the targeted historical version
        active_content = await service.get_template(name)
        assert active_content == contents[v_index], (
            f"[16-repeated] Active content should be {contents[v_index]!r} "
            f"(v{target_version}), got {active_content!r}"
        )

        # Exactly one row is active
        assert (
            repo.count_active(name) == 1
        ), "[16-repeated] More than one active row detected after rollback"


# ---------------------------------------------------------------------------
# Deterministic edge cases
# ---------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_16_rollback_to_version_1_of_2() -> None:
    """**Validates: Requirements 25.2**

    Edge case: minimum N=2 — roll back from v2 to v1.
    The simplest meaningful rollback scenario.
    """
    name = "test_template"
    v1_content = "You are a helpful assistant."
    v2_content = "You are a coding assistant."

    service, repo = await _create_service_with_n_versions(
        name, [v1_content, v2_content]
    )

    # Active should be v2 at this point
    assert await service.get_template(name) == v2_content

    # Rollback to v1
    await service.rollback_template(name, version=1, author_id=_AUTHOR_ID)

    # Active content should now match v1
    assert await service.get_template(name) == v1_content

    # 3 total rows: v1, v2, v3(rollback)
    all_rows = repo.all_rows_for(name)
    assert len(all_rows) == 3

    # v1 and v2 content intact
    assert all_rows[0].content == v1_content
    assert all_rows[1].content == v2_content

    # Only one active row
    assert repo.count_active(name) == 1
    assert (await service.get_active_template(name)).version == 3


@pytest.mark.asyncio
async def test_16_rollback_to_latest_version_is_valid() -> None:
    """**Validates: Requirements 25.2**

    Edge case: rolling back to the current active version (N) creates a new
    row N+1 with the same content.  History still grows by exactly 1.
    """
    name = "test_template"
    contents = ["v1 content", "v2 content", "v3 content"]
    n = len(contents)

    service, repo = await _create_service_with_n_versions(name, contents)

    # Roll back to the currently active version (N=3)
    await service.rollback_template(name, version=n, author_id=_AUTHOR_ID)

    # Content should remain v3's content
    assert await service.get_template(name) == contents[-1]

    # Total rows = N + 1
    assert len(repo.all_rows_for(name)) == n + 1

    # Exactly 1 active row
    assert repo.count_active(name) == 1


@pytest.mark.asyncio
async def test_16_rollback_nonexistent_version_raises() -> None:
    """**Validates: Requirements 25.2**

    Edge case: requesting a rollback to a version that does not exist raises
    ``TemplateNotFoundError`` without modifying the repository.
    """
    name = "test_template"
    service, repo = await _create_service_with_n_versions(
        name, ["v1 content", "v2 content"]
    )

    rows_before = len(repo.all_rows_for(name))

    with pytest.raises(TemplateNotFoundError):
        await service.rollback_template(name, version=999, author_id=_AUTHOR_ID)

    # No rows should have been added
    rows_after = len(repo.all_rows_for(name))
    assert rows_after == rows_before, (
        f"Repository should not have been modified: "
        f"{rows_before} rows before, {rows_after} after failed rollback"
    )


@pytest.mark.asyncio
async def test_16_rollback_content_exact_match_all_versions() -> None:
    """**Validates: Requirements 25.2**

    Deterministic smoke test: with 5 known versions, rolling back to each one
    in turn must produce exact content matches and each time grow history by 1.
    """
    name = "smoke_template"
    contents = [
        "System prompt v1: basic assistant",
        "System prompt v2: coding assistant",
        "System prompt v3: creative writer",
        "System prompt v4: data analyst",
        "System prompt v5: customer support",
    ]
    n = len(contents)

    for target_v in range(1, n + 1):
        # Fresh setup for each targeted version
        service, repo = await _create_service_with_n_versions(name, contents)

        await service.rollback_template(name, version=target_v, author_id=_AUTHOR_ID)

        # 16a: active content matches the targeted version
        active = await service.get_template(name)
        assert active == contents[target_v - 1], (
            f"After rollback to v{target_v}: expected {contents[target_v - 1]!r}, "
            f"got {active!r}"
        )

        # 16b: all N originals intact
        all_rows = repo.all_rows_for(name)
        assert len(all_rows) == n + 1
        for i in range(n):
            assert all_rows[i].version == i + 1
            assert all_rows[i].content == contents[i]

        # 16c: rollback row version = N + 1
        assert all_rows[-1].version == n + 1

        # 16d: exactly one active row
        assert repo.count_active(name) == 1
