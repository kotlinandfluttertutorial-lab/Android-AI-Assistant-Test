# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit/agents
# File    : test_agent_status.py
# Purpose : Unit tests for AgentStatus lifecycle and transition rules.
# ============================================================

"""Unit tests for AgentStatus."""

import pytest

from app.agents.models import AgentStatus

# ---------------------------------------------------------------------------
# is_terminal
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "status",
    [
        AgentStatus.COMPLETED,
        AgentStatus.PARTIAL,
        AgentStatus.FAILED,
        AgentStatus.CANCELLED,
    ],
)
def test_is_terminal_true(status: AgentStatus) -> None:
    assert status.is_terminal is True


@pytest.mark.parametrize(
    "status",
    [
        AgentStatus.REQUESTED,
        AgentStatus.STARTED,
        AgentStatus.RUNNING,
        AgentStatus.WAITING,
    ],
)
def test_is_terminal_false(status: AgentStatus) -> None:
    assert status.is_terminal is False


# ---------------------------------------------------------------------------
# is_success
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "status",
    [
        AgentStatus.COMPLETED,
        AgentStatus.PARTIAL,
    ],
)
def test_is_success_true(status: AgentStatus) -> None:
    assert status.is_success is True


@pytest.mark.parametrize(
    "status",
    [
        AgentStatus.REQUESTED,
        AgentStatus.STARTED,
        AgentStatus.RUNNING,
        AgentStatus.WAITING,
        AgentStatus.FAILED,
        AgentStatus.CANCELLED,
    ],
)
def test_is_success_false(status: AgentStatus) -> None:
    assert status.is_success is False


# ---------------------------------------------------------------------------
# can_transition_to — valid forward transitions
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "from_status, to_status",
    [
        (AgentStatus.REQUESTED, AgentStatus.STARTED),
        (AgentStatus.REQUESTED, AgentStatus.CANCELLED),
        (AgentStatus.STARTED, AgentStatus.RUNNING),
        (AgentStatus.STARTED, AgentStatus.WAITING),
        (AgentStatus.STARTED, AgentStatus.FAILED),
        (AgentStatus.STARTED, AgentStatus.CANCELLED),
        (AgentStatus.RUNNING, AgentStatus.WAITING),
        (AgentStatus.RUNNING, AgentStatus.COMPLETED),
        (AgentStatus.RUNNING, AgentStatus.PARTIAL),
        (AgentStatus.RUNNING, AgentStatus.FAILED),
        (AgentStatus.RUNNING, AgentStatus.CANCELLED),
        (AgentStatus.WAITING, AgentStatus.RUNNING),
        (AgentStatus.WAITING, AgentStatus.FAILED),
        (AgentStatus.WAITING, AgentStatus.CANCELLED),
    ],
)
def test_valid_transition(from_status: AgentStatus, to_status: AgentStatus) -> None:
    assert from_status.can_transition_to(to_status) is True


# ---------------------------------------------------------------------------
# can_transition_to — invalid transitions
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "from_status, to_status",
    [
        (AgentStatus.REQUESTED, AgentStatus.RUNNING),
        (AgentStatus.REQUESTED, AgentStatus.COMPLETED),
        (AgentStatus.REQUESTED, AgentStatus.PARTIAL),
        (AgentStatus.REQUESTED, AgentStatus.FAILED),
    ],
)
def test_invalid_transition_from_requested(
    from_status: AgentStatus, to_status: AgentStatus
) -> None:
    assert from_status.can_transition_to(to_status) is False


@pytest.mark.parametrize(
    "terminal",
    [
        AgentStatus.COMPLETED,
        AgentStatus.PARTIAL,
        AgentStatus.FAILED,
        AgentStatus.CANCELLED,
    ],
)
def test_no_transition_from_terminal(terminal: AgentStatus) -> None:
    for next_status in AgentStatus:
        assert terminal.can_transition_to(next_status) is False, (
            f"Expected {terminal} → {next_status} to be forbidden"
        )
