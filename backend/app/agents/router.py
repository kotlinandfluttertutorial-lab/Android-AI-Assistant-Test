# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : router.py
# Purpose : Deterministic AgentRouter — maps AgentRequests to registered Agents.
#
# Architecture Layer : Agent Core (Phase 2)
# Pattern Used       : Strategy
#
# Key Concepts:
#   - No LLM calls — routing is fully deterministic
#   - Priority order mirrors the Android DefaultAgentRouter exactly
#   - The orchestrator calls this; no hardcoded routing logic in orchestrator
#
# Dependencies: app.agents.registry, app.agents.models, app.agents.base
# ============================================================

"""AgentRouter — deterministic request routing."""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.agents.models import AgentCapability, AgentRequest

if TYPE_CHECKING:
    from app.agents.base import Agent
    from app.agents.registry import AgentRegistry

logger = logging.getLogger(__name__)

# Well-known metadata keys (match Android DefaultAgentRouter constants)
METADATA_KEY_AGENT_NAME = "agent_name"
METADATA_KEY_ATTACHMENT_TYPE = "attachment_type"
AGENT_NAME_CONVERSATIONAL = "conversational"


@dataclass(frozen=True)
class RoutingOutcome:
    """Result of AgentRouter.route().

    Attributes:
        agent:  The selected agent, or None when routing failed.
        reason: Human-readable explanation.
        failed: True when no agent could be selected.
    """

    agent: Agent | None
    reason: str
    failed: bool = False

    @classmethod
    def routed(cls, agent: Agent, reason: str) -> RoutingOutcome:
        return cls(agent=agent, reason=reason, failed=False)

    @classmethod
    def no_agent_found(cls, reason: str) -> RoutingOutcome:
        return cls(agent=None, reason=reason, failed=True)


class AgentRouter:
    """Routes an AgentRequest to the most appropriate registered Agent.

    Routing is **deterministic** — given the same registry state and request,
    the same agent is always selected.  No LLM calls are made.

    Priority order:
    1. Explicit ``metadata["agent_name"]``
    2. Capability match
    3. Attachment routing (image → IMAGE_UNDERSTANDING)
    4. Conversation context → "conversational" agent
    5. First capable agent
    6. No agent found
    """

    def route(
        self,
        request: AgentRequest,
        registry: AgentRegistry,
    ) -> RoutingOutcome:
        """Select the best agent for *request*.

        Args:
            request:  The request to route.
            registry: Current agent registry.

        Returns:
            :class:`RoutingOutcome`.
        """
        if registry.is_empty:
            return RoutingOutcome.no_agent_found("Agent registry is empty.")

        # ── 1. Explicit agent name ─────────────────────────────────────────
        explicit_name = (request.metadata or {}).get(METADATA_KEY_AGENT_NAME, "").strip()
        if explicit_name:
            agent = registry.get_or_none(explicit_name)
            if agent is None:
                return RoutingOutcome.no_agent_found(
                    f"Explicitly requested agent '{explicit_name}' is not registered."
                )
            return RoutingOutcome.routed(agent, f"explicit_name:{explicit_name}")

        # ── 2. Capability match ────────────────────────────────────────────
        caps = set(request.capabilities)
        if caps:
            candidates = [a for a in registry.find_by_capability(caps) if a.can_handle(request)]
            if candidates:
                caps_list = sorted(c.value for c in caps)
                return RoutingOutcome.routed(candidates[0], f"capability_match:{caps_list}")
            return RoutingOutcome.no_agent_found(
                f"No agent satisfies requested capabilities: {sorted(c.value for c in caps)}."
            )

        # ── 3. Attachment routing ─────────────────────────────────────────
        attachment_type = (request.metadata or {}).get(METADATA_KEY_ATTACHMENT_TYPE, "")
        if attachment_type == "image":
            image_caps: set[AgentCapability] = {AgentCapability.IMAGE_UNDERSTANDING}
            image_candidates = [
                a for a in registry.find_by_capability(image_caps) if a.can_handle(request)
            ]
            if image_candidates:
                return RoutingOutcome.routed(image_candidates[0], "attachment_type:image")

        # ── 4. Conversation context → prefer "conversational" ─────────────
        if request.conversation_id is not None:
            conv_agent = registry.get_or_none(AGENT_NAME_CONVERSATIONAL)
            if conv_agent is not None and conv_agent.can_handle(request):
                return RoutingOutcome.routed(conv_agent, "conversation_context")

        # ── 5. First capable agent ─────────────────────────────────────────
        first = next((a for a in registry.list() if a.can_handle(request)), None)
        if first is not None:
            return RoutingOutcome.routed(first, "first_capable")

        return RoutingOutcome.no_agent_found(
            f"No registered agent can handle request ({registry.size} agents checked)."
        )
