# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : model_router.py
# Purpose : Selects CLOUD, ON_DEVICE, or AUTO inference path for a request.
#
# Architecture Layer : Agent Core (Phase 2)
# Pattern Used       : Strategy
#
# Key Concepts:
#   - Deterministic routing — no LLM calls made here
#   - Backend "on-device" means LOCAL (Ollama/Gemma running locally)
#   - ModelRouter wraps LLMServiceAdapter (cloud) and LocalGemmaAdapter
#   - Does NOT rewrite GeminiProvider or LocalGemmaProvider
#
# Dependencies: app.agents.llm_client, app.agents.models,
#               app.config.settings
# ============================================================

"""ModelRouter — selects the inference path and returns an LLMClient."""

from __future__ import annotations

import enum
import logging

from app.agents.llm_client import LLMClient, LLMServiceAdapter, LocalGemmaAdapter
from app.agents.models import AgentRequest

logger = logging.getLogger(__name__)


class InferencePath(enum.StrEnum):
    """Requested or resolved inference path."""

    CLOUD = "CLOUD"
    """Always use the cloud LLM provider (LLMService → GeminiProvider)."""

    ON_DEVICE = "ON_DEVICE"
    """Always use the local/on-device provider (LocalGemmaProvider via Ollama)."""

    AUTO = "AUTO"
    """Let ModelRouter decide: prefer local when available, fall back to cloud."""


class ModelRoutingDecision:
    """Result of a ModelRouter.route() call.

    Attributes:
        path:             Resolved path (never AUTO).
        provider_name:    Canonical provider id.
        client:           Ready-to-use LLMClient for this decision.
        fallback_occurred: True when AUTO/ON_DEVICE preference couldn't be met.
        reason:           Short human-readable explanation.
    """

    __slots__ = ("client", "fallback_occurred", "path", "provider_name", "reason")

    def __init__(
        self,
        path: InferencePath,
        provider_name: str,
        client: LLMClient,
        *,
        fallback_occurred: bool = False,
        reason: str = "",
    ) -> None:
        if path == InferencePath.AUTO:
            raise ValueError(
                "ModelRoutingDecision.path must be resolved (CLOUD or ON_DEVICE), not AUTO."
            )
        if not provider_name.strip():
            raise ValueError("ModelRoutingDecision.provider_name must not be blank.")
        self.path = path
        self.provider_name = provider_name
        self.client = client
        self.fallback_occurred = fallback_occurred
        self.reason = reason

    def __repr__(self) -> str:
        return (
            f"<ModelRoutingDecision path={self.path.value} "
            f"provider={self.provider_name!r} fallback={self.fallback_occurred}>"
        )


class ModelRouter:
    """Selects the appropriate LLMClient for an AgentRequest.

    ## Routing rules (deterministic — no LLM calls):

    1. If ``request.provider`` is set, honour it directly.
    2. ``preference=CLOUD``     → always return a cloud client.
    3. ``preference=ON_DEVICE`` → return local client if available;
       fall back to cloud with ``fallback_occurred=True``.
    4. ``preference=AUTO``      → prefer local when available, else cloud.

    ## Usage

    ```python
    router = ModelRouter()
    decision = await router.route(request, preference=InferencePath.AUTO)
    text = await decision.client.generate(request.input)
    ```
    """

    def __init__(
        self,
        cloud_client: LLMClient | None = None,
        local_client: LLMClient | None = None,
    ) -> None:
        # Injected for testability; defaults to the real adapters
        self._cloud: LLMClient = cloud_client or LLMServiceAdapter()
        self._local: LLMClient = local_client or LocalGemmaAdapter()

    async def route(
        self,
        request: AgentRequest,
        preference: InferencePath = InferencePath.AUTO,
    ) -> ModelRoutingDecision:
        """Resolve the inference path and return a ready LLMClient.

        Args:
            request:    The agent request to route.
            preference: Caller's preferred inference path.

        Returns:
            Resolved :class:`ModelRoutingDecision`.
        """
        # ── Explicit provider hint ─────────────────────────────────────────
        explicit = (request.provider or "").strip().lower()
        if explicit:
            if explicit in ("gemma", "ollama", "llama", "mistral", "on_device"):
                return self._on_device_decision("explicit_provider:" + explicit)
            return self._cloud_decision("explicit_provider:" + explicit)

        # ── Preference-based routing ───────────────────────────────────────
        if preference == InferencePath.CLOUD:
            return self._cloud_decision("preference:CLOUD")

        local_ready = self._local.is_available

        if preference == InferencePath.ON_DEVICE:
            if local_ready:
                return self._on_device_decision("preference:ON_DEVICE")
            logger.warning(
                "ON_DEVICE routing requested but local provider unavailable; "
                "falling back to cloud (request_id=%s)",
                request.request_id,
            )
            return ModelRoutingDecision(
                path=InferencePath.CLOUD,
                provider_name=self._cloud.provider_name,
                client=self._cloud,
                fallback_occurred=True,
                reason="preference:ON_DEVICE but local unavailable → cloud",
            )

        # AUTO
        if local_ready:
            return self._on_device_decision("auto:local_available")
        return self._cloud_decision("auto:local_unavailable → cloud")

    def _cloud_decision(self, reason: str) -> ModelRoutingDecision:
        return ModelRoutingDecision(
            path=InferencePath.CLOUD,
            provider_name=self._cloud.provider_name,
            client=self._cloud,
            reason=reason,
        )

    def _on_device_decision(self, reason: str) -> ModelRoutingDecision:
        return ModelRoutingDecision(
            path=InferencePath.ON_DEVICE,
            provider_name=self._local.provider_name,
            client=self._local,
            reason=reason,
        )
