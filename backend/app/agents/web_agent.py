# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : web_agent.py
# Purpose : WebAgent — provider-independent web search agent.
#           Returns cited results without hardcoding any search API.
#
# Architecture Layer : Agent Core (Phase 6)
# Pattern Used       : Adapter (implements Agent)
#
# Key Concepts:
#   - Provider-independent: WebSearchProvider is a protocol; the agent
#     never references a concrete search implementation
#   - Default stub provider always returns an empty list so the agent
#     can surface a "not yet configured" message safely
#   - Citations: each result maps to an AgentCitation with URL as
#     document_id and snippet as excerpt
#
# Request metadata keys:
#   "query"       — search query (falls back to request.input)
#   "max_results" — integer 1–20 (default 5)
#
# Streaming protocol:
#   Started → StatusChanged(RUNNING) → RetrievalCompleted
#   → Token(formatted results) → Completed
# ============================================================

"""WebAgent — provider-independent web search as an Agent."""

from __future__ import annotations

import logging
from collections.abc import AsyncIterator
from typing import Protocol, runtime_checkable

from app.agents.base import Agent
from app.agents.models import (
    AgentCapability,
    AgentCitation,
    AgentCompletedEvent,
    AgentError,
    AgentEvent,
    AgentExecution,
    AgentFailedEvent,
    AgentRequest,
    AgentResult,
    AgentRetrievalCompletedEvent,
    AgentStartedEvent,
    AgentStatus,
    AgentStatusChangedEvent,
    AgentTokenEvent,
)

logger = logging.getLogger(__name__)

WEB_AGENT_NAME = "web-search"
_DEFAULT_MAX_RESULTS = 5


@runtime_checkable
class WebSearchResult(Protocol):
    """Structural type for web search results."""

    title: str
    url: str
    snippet: str


class WebSearchProvider(Protocol):
    """Protocol for pluggable web search backends.

    Implement this protocol and pass an instance to WebAgent to enable
    real web search.  The default stub always returns an empty list and
    sets `is_configured=False` so WebAgent surfaces the setup message.
    """

    @property
    def provider_name(self) -> str: ...

    @property
    def is_configured(self) -> bool: ...

    async def search(
        self, query: str, max_results: int
    ) -> list[dict[str, str]]:
        """Return a list of {title, url, snippet} dicts."""
        ...


class _StubWebSearchProvider:
    """Safe no-network stub used when no real provider is configured."""

    @property
    def provider_name(self) -> str:
        return "stub"

    @property
    def is_configured(self) -> bool:
        return False

    async def search(
        self, query: str, max_results: int
    ) -> list[dict[str, str]]:
        return []


# Module-level provider — swap out in tests or when a real key is available.
_default_provider: _StubWebSearchProvider = _StubWebSearchProvider()


class WebAgent(Agent):
    """Agent that performs web searches via an injected WebSearchProvider.

    When the provider is not configured, emits a user-friendly
    PROVIDER_NOT_CONFIGURED failure with setup instructions.
    """

    def __init__(
        self,
        provider: _StubWebSearchProvider | None = None,
    ) -> None:
        self._provider = provider or _default_provider

    @property
    def name(self) -> str:
        return WEB_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            f"Web search agent (provider: {self._provider.provider_name}). "
            "Returns titles, URLs, snippets, and citations."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({
            AgentCapability.SEMANTIC_SEARCH,
            AgentCapability.TEXT_GENERATION,
        })

    async def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        yield AgentStartedEvent(execution_id=execution.execution_id, agent_name=self.name)
        yield AgentStatusChangedEvent(
            execution_id=execution.execution_id, status=AgentStatus.RUNNING
        )

        # ── Provider configured? ─────────────────────────────────────────────
        if not self._provider.is_configured:
            yield self._failed(
                execution, request,
                "PROVIDER_NOT_CONFIGURED",
                "Web search is not yet configured. Add a search API key in Settings.",
            )
            return

        # ── Parse parameters ─────────────────────────────────────────────────
        metadata = request.metadata or {}
        query = (metadata.get("query") or request.input or "").strip()
        if not query:
            yield self._failed(execution, request, "BLANK_QUERY",
                               "A non-empty search query is required.")
            return

        raw_max = metadata.get("max_results", "")
        try:
            max_results = max(1, min(20, int(raw_max)))
        except (ValueError, TypeError):
            max_results = _DEFAULT_MAX_RESULTS

        # ── Search ───────────────────────────────────────────────────────────
        try:
            results = await self._provider.search(query, max_results)
        except Exception as exc:
            logger.warning("WebAgent: search failed: %s", exc)
            yield self._failed(execution, request, "SEARCH_ERROR",
                               f"Web search failed: {exc}")
            return

        yield AgentRetrievalCompletedEvent(query=query, chunk_count=len(results))

        if not results:
            msg = f"No web results found for: {query}"
            yield AgentTokenEvent(token=msg)
            yield AgentCompletedEvent(result=AgentResult(
                execution_id=execution.execution_id,
                request_id=request.request_id,
                agent_name=self.name,
                status=AgentStatus.COMPLETED,
                content=msg,
                metadata={"query": query, "result_count": "0"},
            ))
            return

        # ── Format response ───────────────────────────────────────────────────
        lines = [f"Web search results for: **{query}**", ""]
        citations: list[AgentCitation] = []
        for i, r in enumerate(results, 1):
            title = r.get("title", "")
            url = r.get("url", "")
            snippet = r.get("snippet", "")
            timestamp = r.get("timestamp")
            lines.append(f"{i}. **{title}**")
            lines.append(f"   {snippet}")
            lines.append(f"   Source: {url}")
            if timestamp:
                lines.append(f"   Published: {timestamp}")
            lines.append("")
            citations.append(AgentCitation(
                document_id=url,
                document_name=title,
                excerpt=snippet[:300],
            ))

        formatted = "\n".join(lines).rstrip()
        yield AgentTokenEvent(token=formatted)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=formatted,
            citations=citations,
            metadata={
                "query": query,
                "result_count": str(len(results)),
                "provider": self._provider.provider_name,
            },
        ))

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        msg: str,
    ) -> AgentFailedEvent:
        return AgentFailedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=WEB_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=msg),
        ))
