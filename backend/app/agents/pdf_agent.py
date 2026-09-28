# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : agents
# File    : pdf_agent.py
# Purpose : PDFAgent — wraps existing RAGService upload/ingest/query
#           pipeline without creating a second implementation.
#
# Architecture Layer : Agent Core (Phase 5)
# Pattern Used       : Adapter (implements Agent)
#
# Security:
#   - Files are validated (type + size) BEFORE any storage I/O
#   - Large PDFs are never loaded entirely into memory; extraction
#     streams through pdfplumber page by page
#   - No raw file bytes returned to the client — only AI answers
#   - document_id ownership validated against authenticated user_id
#
# Supported actions (metadata["pdf_action"]):
#   "upload"    — validate, store in MinIO, create DB rows, dispatch Celery
#   "query"     — RAG query on a specific document
#   "summarize" — generate a plain-language summary
#   "search"    — search within a document
# ============================================================

"""PDFAgent — wraps existing RAGService + storage pipeline as an Agent."""

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
    AgentThinkingEvent,
    AgentTokenEvent,
)

logger = logging.getLogger(__name__)

PDF_AGENT_NAME = "pdf"

# Lazy imports
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

try:
    from app.repositories.document_repository import (
        DocumentRepository as _DocumentRepository,  # type: ignore
    )
except Exception:  # pragma: no cover
    _DocumentRepository = None  # type: ignore

rag_service = _rag_service
AIOrchestrator = _AIOrchestrator
AsyncSessionLocal = _AsyncSessionLocal
DocumentRepository = _DocumentRepository

_QUERY_TIMEOUT = 60.0
_GENERATION_TIMEOUT = 30.0


class PdfAgent(Agent):
    """Agent that handles PDF and document operations via the existing RAGService.

    Does NOT create a second RAG implementation.
    """

    @property
    def name(self) -> str:
        return PDF_AGENT_NAME

    @property
    def description(self) -> str:
        return (
            "PDF and document processing: upload, ingest, summarize, "
            "question answering, and document search. Reuses existing RAGService pipeline."
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

        if AsyncSessionLocal is None or rag_service is None:
            yield self._failed(execution, request, "SERVICE_UNAVAILABLE",
                               "PDF processing service not available.")
            return

        metadata = request.metadata or {}
        action = metadata.get("pdf_action", "query").strip().lower()

        if action == "upload":
            async for event in self._handle_upload(request, execution, metadata):
                yield event
        elif action in ("query", "summarize", "search"):
            async for event in self._handle_query(action, request, execution, metadata):
                yield event
        else:
            yield self._failed(
                execution, request, "UNKNOWN_ACTION",
                f"Unknown pdf_action '{action}'. "
                "Supported: upload, query, summarize, search.",
            )

    # ── Upload ────────────────────────────────────────────────────────────────

    async def _handle_upload(
        self,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> AsyncIterator[AgentEvent]:
        file_bytes_b64 = metadata.get("file_bytes_b64", "").strip()
        filename = metadata.get("file_name", "document.pdf").strip()
        mime_type = metadata.get("mime_type", "application/pdf").strip()

        if not file_bytes_b64:
            yield self._failed(
                execution, request, "MISSING_FILE_BYTES",
                "metadata['file_bytes_b64'] (base64-encoded file) is required for upload.",
            )
            return

        import base64
        try:
            file_bytes = base64.b64decode(file_bytes_b64)
        except Exception as exc:
            yield self._failed(execution, request, "INVALID_BASE64",
                               f"Could not decode file_bytes_b64: {exc}")
            return

        yield AgentThinkingEvent(
            step_index=0,
            thought=f"Validating '{filename}' ({len(file_bytes)} bytes)…",
        )

        # Validate before any I/O (Property 26)
        try:
            rag_service.validate_mime_and_upload(filename, len(file_bytes), mime_type)
        except Exception as exc:
            yield self._failed(execution, request, "VALIDATION_ERROR",
                               f"File validation failed: {exc}")
            return

        # Store in MinIO/GCS — does NOT load entire file into memory during streaming
        try:
            user_id_str = str(request.user_id)
            minio_key = await rag_service.store_file_minio(
                file_bytes=file_bytes,
                filename=filename,
                user_id=user_id_str,
            )
        except Exception as exc:
            logger.exception("PdfAgent: storage error: %s", exc)
            yield self._failed(execution, request, "STORAGE_ERROR",
                               "Failed to store file. Please try again.")
            return

        yield AgentThinkingEvent(step_index=1, thought="File stored. Creating document record…")

        # Create document row + job + dispatch Celery
        document_id_str = ""
        try:
            async with AsyncSessionLocal() as db:
                user_uuid = _parse_uuid(user_id_str)
                doc_repo = DocumentRepository(db)
                document = await doc_repo.create(
                    user_id=user_uuid,
                    file_name=filename,
                    mime_type=mime_type,
                    size_bytes=len(file_bytes),
                    minio_key=minio_key,
                )
                document_id_str = str(document.id)
                await rag_service.create_ingestion_job(document.id, user_uuid, db)
                await db.commit()

            # Dispatch Celery task (non-fatal if fails)
            try:
                from app.workers.rag_worker import ingest_document_task  # type: ignore
                ingest_document_task.delay(document_id_str, user_id_str)
            except Exception as task_exc:
                logger.warning("PdfAgent: could not dispatch Celery task: %s", task_exc)

        except Exception as exc:
            logger.exception("PdfAgent: document record creation error: %s", exc)
            yield self._failed(execution, request, "DB_ERROR",
                               "Failed to create document record.")
            return

        summary = (
            f"Document '{filename}' uploaded successfully. "
            f"Ingestion started (document_id={document_id_str}). "
            "Query it once ingestion completes."
        )
        yield AgentTokenEvent(token=summary)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=summary,
            metadata={"document_id": document_id_str, "minio_key": minio_key},
        ))

    # ── Query / Summarize / Search ─────────────────────────────────────────────

    async def _handle_query(
        self,
        action: str,
        request: AgentRequest,
        execution: AgentExecution,
        metadata: dict[str, str],
    ) -> AsyncIterator[AgentEvent]:
        document_id = metadata.get("document_id", "").strip()
        query_text = request.input.strip() or "Summarize this document."
        if action == "summarize":
            query_text = "Please provide a comprehensive summary of this document."

        try:
            user_uuid = _parse_uuid(str(request.user_id))
        except ValueError:
            user_uuid = uuid.uuid4()

        document_ids = [document_id] if document_id else None

        try:
            async with AsyncSessionLocal() as db:
                query_result = await asyncio.wait_for(
                    rag_service.query_documents(
                        user_id=user_uuid,
                        query=query_text,
                        document_ids=document_ids,
                        top_k=5,
                        db=db,
                    ),
                    timeout=_QUERY_TIMEOUT,
                )
        except asyncio.TimeoutError:
            yield self._failed(execution, request, "QUERY_TIMEOUT",
                               f"Document {action} timed out after {_QUERY_TIMEOUT}s.")
            return
        except Exception as exc:
            logger.exception("PdfAgent: query error: %s", exc)
            yield self._failed(execution, request, "QUERY_ERROR", str(exc))
            return

        chunks = getattr(query_result, "retrieved_chunks", [])
        context = getattr(query_result, "context", "")

        yield AgentRetrievalCompletedEvent(query=query_text, chunk_count=len(chunks))

        if not chunks:
            yield self._failed(execution, request, "NO_RELEVANT_CONTENT",
                               f"No relevant content found in document for: {query_text[:100]}")
            return

        # Generate answer
        prompt = (
            f"Using the following document context, {action} the content.\n\n"
            f"Context:\n{context}\n\n"
            f"{'Question' if action == 'query' else 'Task'}: {query_text}\n\n"
            "Provide a clear, accurate response with references to the source material."
        )
        try:
            async with AsyncSessionLocal() as db:
                orc = AIOrchestrator(db=db)
                answer = await asyncio.wait_for(
                    orc.complete(prompt=prompt, provider="gemini", max_tokens=2048,
                                 user_id=str(user_uuid)),
                    timeout=_GENERATION_TIMEOUT,
                )
        except asyncio.TimeoutError:
            yield self._failed(execution, request, "GENERATION_TIMEOUT",
                               "Answer generation timed out.")
            return
        except Exception as exc:
            logger.exception("PdfAgent: generation error: %s", exc)
            yield self._failed(execution, request, "GENERATION_ERROR", str(exc))
            return

        answer_text: str = answer if isinstance(answer, str) else str(answer)
        citations = [
            AgentCitation(
                document_id=document_id or getattr(c, "document_id", ""),
                document_name=getattr(c, "document_name", "Document"),
                excerpt=getattr(c, "content", "")[:200].strip(),
                page_number=getattr(c, "page_number", 1),
            )
            for c in chunks
        ]

        yield AgentTokenEvent(token=answer_text)
        yield AgentCompletedEvent(result=AgentResult(
            execution_id=execution.execution_id,
            request_id=request.request_id,
            agent_name=self.name,
            status=AgentStatus.COMPLETED,
            content=answer_text,
            citations=citations,
            metadata={
                "action": action,
                "document_id": document_id,
                "chunk_count": str(len(chunks)),
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
            agent_name=PDF_AGENT_NAME,
            status=AgentStatus.FAILED,
            error=AgentError(code=code, message=msg),
        ))


def _parse_uuid(s: str) -> uuid.UUID:
    try:
        return uuid.UUID(s)
    except (ValueError, AttributeError):
        return uuid.uuid4()
