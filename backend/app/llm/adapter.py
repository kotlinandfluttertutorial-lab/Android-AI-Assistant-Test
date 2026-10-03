# ============================================================
# Android AI Assistant — Backend
# Module  : llm
# File    : adapter.py
# Purpose : LLMServiceAdapter — bridges LLMService to the LLMAdapter
#           protocol expected by ActionDispatcher / SingleAgentRunner.
#
# Design rules:
#   - Zero credentials or hardcoded values.
#   - Translates keyword arguments one-to-one; no business logic.
#   - Satisfies the @runtime_checkable LLMAdapter Protocol at runtime.
# ============================================================
"""LLMServiceAdapter — adapts :class:`~app.llm.service.LLMService` to
the :class:`~app.orchestration.dispatcher.LLMAdapter` protocol.

Why this adapter is needed
--------------------------
:class:`~app.orchestration.dispatcher.ActionDispatcher` expects an ``LLMAdapter``
whose ``generate()`` method takes a plain prompt string plus keyword arguments
and returns a plain string::

    async def generate(self, prompt: str, *, system_prompt: str = "", ...) -> str

:class:`~app.llm.service.LLMService` exposes::

    async def generate(self, request: LLMRequest) -> LLMResponse

The adapter is a thin translation layer between the two shapes.

Usage::

    from app.llm.service import get_llm_service
    from app.llm.adapter import LLMServiceAdapter

    adapter = LLMServiceAdapter(get_llm_service())
    # Now adapter satisfies the LLMAdapter protocol and can be passed to
    # SingleAgentRunner(llm=adapter, ...)
"""

from __future__ import annotations

import logging

from app.llm.base import LLMRequest
from app.llm.service import LLMService

logger = logging.getLogger(__name__)


class LLMServiceAdapter:
    """Wraps :class:`~app.llm.service.LLMService` to satisfy the
    :class:`~app.orchestration.dispatcher.LLMAdapter` protocol.

    Args:
        service: Shared :class:`~app.llm.service.LLMService` instance.
                 Typically obtained via
                 :func:`~app.llm.service.get_llm_service`.

    Example::

        from app.llm.service import get_llm_service
        from app.llm.adapter import LLMServiceAdapter
        from app.orchestration import SingleAgentRunner

        runner = SingleAgentRunner(
            registry=registry,
            llm=LLMServiceAdapter(get_llm_service()),
        )
    """

    def __init__(self, service: LLMService) -> None:
        self._service = service

    async def generate(
        self,
        prompt: str,
        *,
        system_prompt: str = "",
        user_id: str | None = None,
        max_tokens: int | None = None,
        temperature: float | None = None,
        rag_context: list[str] | None = None,
    ) -> str:
        """Generate a completion and return the response text.

        Translates the keyword-argument form expected by
        :class:`~app.orchestration.dispatcher.LLMAdapter` into an
        :class:`~app.llm.base.LLMRequest` and delegates to
        :meth:`~app.llm.service.LLMService.generate`.

        Args:
            prompt:        The user message / agent decision content.
            system_prompt: Optional system / instruction prefix.
            user_id:       Authenticated user ID for rate-limiting and audit.
            max_tokens:    Override the default max-output-tokens cap.
            temperature:   Override the default sampling temperature.
            rag_context:   Retrieved document chunks to include in the prompt.

        Returns:
            Plain-text response string from the LLM provider.

        Raises:
            :class:`~app.llm.exceptions.LLMError`: Re-raises any
            ``LLMError`` from the provider so the dispatcher's
            try/except can surface a safe error message.
        """
        request = LLMRequest(
            prompt=prompt,
            system_prompt=system_prompt,
            user_id=user_id,
            max_output_tokens=max_tokens,
            temperature=temperature,
            rag_context=rag_context or [],
        )
        response = await self._service.generate(request)
        return response.text


def get_llm_adapter() -> LLMServiceAdapter:
    """Return a shared :class:`LLMServiceAdapter` backed by the module-level
    :class:`~app.llm.service.LLMService` singleton.

    Suitable as a FastAPI dependency or for direct use in service factories::

        from app.llm.adapter import get_llm_adapter
        from fastapi import Depends

        @router.post("/agent/execute")
        async def execute(
            ...
            llm: LLMServiceAdapter = Depends(get_llm_adapter),
        ): ...
    """
    from app.llm.service import get_llm_service

    return LLMServiceAdapter(get_llm_service())
