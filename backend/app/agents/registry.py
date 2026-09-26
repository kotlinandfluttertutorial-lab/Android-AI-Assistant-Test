# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : registry.py
# Purpose : Thread-safe registry of Agent instances keyed by name.
#
# Architecture Layer : Agent Core (Phase 2)
# Pattern Used       : Registry
#
# Key Concepts:
#   - Thread-safe via threading.Lock (async code only reads; writes happen
#     at startup during DI wiring)
#   - The orchestrator never contains hardcoded agent-specific routing logic;
#     all routing goes through the registry
#   - DefaultAgentRegistry is the sole implementation; the interface pattern
#     from base.py applies to agents, not to the registry itself
#
# Dependencies: app.agents.base, app.agents.models, threading
# ============================================================

"""AgentRegistry — central store for Agent instances."""

from __future__ import annotations

import threading
from typing import TYPE_CHECKING

from app.agents.models import AgentCapability

if TYPE_CHECKING:
    from app.agents.base import Agent


class AgentNotFoundError(Exception):
    """Raised when no agent is registered under the requested name."""

    def __init__(self, name: str) -> None:
        super().__init__(f"No agent registered with name '{name}'.")
        self.name = name


class AgentRegistry:
    """Thread-safe in-memory registry of Agent instances.

    ## Contract
    - Names are case-sensitive.
    - Registering a second agent under an existing name replaces the first.
    - All public methods are thread-safe.

    ## Usage

    ```python
    registry = AgentRegistry()
    registry.register(ConversationalAgent())
    registry.register(CodeAnalysisAgent())

    agent = registry.get("conversational")
    capable = registry.find_by_capability({AgentCapability.TOOL_USE})
    ```
    """

    def __init__(self) -> None:
        self._store: dict[str, Agent] = {}
        self._lock = threading.Lock()

    # ── Write operations ──────────────────────────────────────────────────────

    def register(self, agent: Agent) -> None:
        """Register *agent* under its name.  Replaces any existing entry."""
        with self._lock:
            self._store[agent.name] = agent

    def unregister(self, name: str) -> None:
        """Remove the agent registered under *name*.  No-op if not found."""
        with self._lock:
            self._store.pop(name, None)

    # ── Read operations ───────────────────────────────────────────────────────

    def get(self, name: str) -> Agent:
        """Return the agent registered under *name*.

        Raises:
            AgentNotFoundError: No agent registered under *name*.
        """
        with self._lock:
            agent = self._store.get(name)
        if agent is None:
            raise AgentNotFoundError(name)
        return agent

    def get_or_none(self, name: str) -> Agent | None:
        """Return the agent registered under *name*, or ``None`` if not found."""
        with self._lock:
            return self._store.get(name)

    def list(self) -> list[Agent]:
        """Return an immutable snapshot of all registered agents."""
        with self._lock:
            return list(self._store.values())

    def find_by_capability(
        self,
        capabilities: set[AgentCapability],
    ) -> list[Agent]:
        """Return agents that declare support for *every* capability in the set.

        Returns all agents when *capabilities* is empty.
        Returns an empty list when no agent satisfies the constraint.
        """
        with self._lock:
            agents = list(self._store.values())

        if not capabilities:
            return agents

        return [a for a in agents if capabilities.issubset(a.capabilities)]

    # ── Convenience ──────────────────────────────────────────────────────────

    @property
    def is_empty(self) -> bool:
        """True when no agents are registered."""
        with self._lock:
            return len(self._store) == 0

    @property
    def size(self) -> int:
        """Number of registered agents."""
        with self._lock:
            return len(self._store)

    def __repr__(self) -> str:
        names = ", ".join(f"'{n}'" for n in sorted(self._store))
        return f"<AgentRegistry agents=[{names}]>"
