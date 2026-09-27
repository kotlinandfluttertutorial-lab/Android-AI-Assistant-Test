# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : rag_agent.py
# Purpose : RAGAgent — wraps the existing RAGService.query_documents()
#           pipeline without creating a second RAG implementation.
#
# Architecture Layer : Agent Core (Phase 4)
# Pattern Used       : Adapter (implements Agent)
#
# Key Concepts:
#   - DOES NOT create a second RAG implementation
#   - Calls RAGService.query_documents() (the same path as /documents/query)
#   - Then calls AIOrchestrator.complete() to generate an answer from context
#   - Returns AgentResult with citations populated from RetrievedChunk list
#   - Supports Code→RAG handoff: rag_context in metadata for next step
#
# Request metadata keys:
#   "document_ids"  — comma-separated document UUIDs (optional)
#   "top_k"         — number of chunks (default 5)
# ============================================================

"""RAGAgent — wraps existing RAGService.query_documents() as an Agent."""

from __future__ import annotations

import asyncio
import logging
import uuid
from collections.abc import AsyncIterator

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

RAG_AGENT_NAME = "rag"
_DEFAULT_TOP_K = 5
_RAG_TIMEOUT = 60.0

# Lazy imports — guarded to keep module importable without pgvector
try:
    from app.services.rag_service import rag_service as _rag_service  # type: ignore
except Exception:  # pragma: no cover
    _rag_service = None  # type: ignore

try:
    from app.services.ai_orchestrator import AIOrchestrator as _AIOrchestrator  # type: ignore
except Exception:  # pragma: no cover
    _AIOrchestrator = None  # type: ignore

try:
    from app.database import AsyncSessionLocal as _AsyncSessionLocal  # type: ignore
except Exception:  # pragma: no cover
    _AsyncSessionLocal = None  # type: ignore

rag_service = _rag_service
AIOrchestrator = _AIOrchestrator
AsyncSessionLocal = _AsyncSessionLocal


class RagAgent(Agent):
    """Agent that retrieves document chunks via the existing RAGService and
    generates a cited answer using AIOrchestrator.

    Does NOT create a second RAG implementation.  Uses the same
    ``rag_service.query_documents()`` path as ``POST /documents/query``.
    """

    @property
    def name(self) -> str:
        return RAG_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "Semantic document retrieval and Q&A via the existing RAGService "
            "(pgvector similarity search + AIOrchestrator answer generation)."
        )

    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({
            AgentCapability.DOCUMENT_RETRIEVAL,
            AgentCapability.TEXT_GENERATION,
            AgentCapability.STREAMING,
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

        if AsyncSessionLocal is None or rag_service is None or AIOrchestrator is None:
            yield self._failed(execution, request, "SERVICE_UNAVAILABLE",
                               "RAG service or database not available.")
            return

        # Parse metadata
        metadata = request.metadata or {}
        raw_doc_ids = metadata.get("document_ids", "")
        document_ids: list[str] | None = (
            [d.strip() for d in raw_doc_ids.split(",") if d.strip()]
            if raw_doc_ids else None
        )
        top_k = int(metadata.get("top_k", str(_DEFAULT_TOP_K)))

        # Parse user_id
        try:
            user_uuid = uuid.UUID(request.user_id)
        except (ValueError, AttributeError):
            user_uuid = uuid.uuid4()

        # ── Step 1: Retrieve chunks via existing RAGService ─────────────────
        try:
            async with AsyncSessionLocal() as db:
                query_result = await asyncio.wait_for(
                    rag_service.query_documents(
                        user_id=user_uuid,
                        query=request.input,
                        document_ids=document_ids,
                        top_k=top_k,
                        db=db,
                    ),
                    timeout=_RAG_TIMEOUT,
                )
        except asyncio.TimeoutError:
            yield self._failed(execution, request, "RETRIEVAL_TIMEOUT",
                               f"RAG retrieval timed out after {_RAG_TIMEOUT}s.")
            return
        except Exception as exc:
            logger.exception("RagAgent: retrieval error: %s", exc)
            yield self._failed(execution, request, "RETRIEVAL_ERROR", str(exc))
            return

        chunks = getattr(query_result, "retrieved_chunks", [])
        context = getattr(query_result, "context", "")

        yield AgentRetrievalCompletedEvent(
            query=request.input,
            chunk_count=len(chunks),
        )

        if not chunks:
            yield self._failed(execution, request, "NO_RELEVANT_CONTENT",
                               "No relevant document chunks found for the query.")
            return

        # ── Step 2: Generate answer via AIOrchestrator ──────────────────────
        answer_prompt = (
            f"Using the following retrieved document context, answer the question.\n\n"
            f"Context:\n{context}\n\n"
            f"Question: {request.input}\n\n"
            "Provide a clear, concise answer with references to the source material."
        )

        try:
            async with AsyncSessionLocal() as db:
                orchestrator = AIOrchestrator(db=db)
                answer = await asyncio.wait_for(
                    orchestrator.complete(
                        prompt=answer_prompt,
                        provider=(request.provider or "gemini"),
                        max_tokens=2048,
                        user_id=request.user_id,
                    ),
                    timeout=30.0,
                )
        except asyncio.TimeoutError:
            yield self._failed(execution, request, "GENERATION_TIMEOUT",
                               "Answer generation timed out.")
            return
        except Exception as exc:
            logger.exception("RagAgent: generation error: %s", exc)
            yield self._failed(execution, request, "GENERATION_ERROR", str(exc))
            return

        answer_text: str = answer if isinstance(answer, str) else str(answer)

        # ── Build citations from retrieved chunks ────────────────────────────
        citations: list[AgentCitation] = []
        for chunk in chunks:
            doc_name = getattr(chunk, "document_name", "Unknown")
            doc_id_str = getattr(chunk, "document_id", "") or ""
            page_num = getattr(chunk, "page_number", 1)
            content_text = getattr(chunk, "content", "")
            citations.append(
                AgentCitation(
                    document_id=doc_id_str,
                    document_name=doc_name,
                    excerpt=content_text[:200].strip(),
                    page_number=page_num,
                )
            )

        yield AgentTokenEvent(token=answer_text)

        result = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=answer_text,
            citations=citations,
            metadata={
                "rag_context": context[:2000],  # truncated for handoff metadata
                "chunk_count": str(len(chunks)),
            },
        )
        yield AgentCompletedEvent(result=result)

    @staticmethod
    def _failed(
        execution: AgentExecution,
        request: AgentRequest,
        code: str,
        message: str,
    ) -> AgentFailedEvent:
        r = AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=RAG_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=message),
        )
        return AgentFailedEvent(result=r)
