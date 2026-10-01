# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : interfaces
# File    : core.py
# Purpose : Domain-level ABCs for MCP, Agent, Document/RAG, Memory,
#           and Embedding integration.
#
# Architecture Layer : Domain (Dependency Inversion boundary)
# Pattern Used       : Abstract Base Class (ABC)
#
# Design Rules:
#   1. No import of any infrastructure library (chromadb, sqlalchemy,
#      redis, sentence_transformers, minio, pdfplumber, google.generativeai, …).
#   2. No import from app.services.*, app.workers.*, or app.database.*.
#   3. Allowed imports: stdlib, app.agents.models, app.llm.base,
#      app.schemas.mcp.
#   4. Every ABC has exactly one abstract method group — single responsibility.
#   5. Value objects (dataclasses) used in signatures are defined here so
#      callers depend only on this module.
#
# Existing ABCs that are NOT redefined here (they already exist):
#   - LLMProvider      → app.llm.base
#   - Agent            → app.agents.base
#   - MCPToolConnector → app.services.mcp_broker
#
# New ABCs defined here:
#   IPlanner, IAgentExecutor, IMemoryStore, IDocumentLoader,
#   IEmbeddingProvider, IVectorStore, IRetriever, IMCPClient, IToolExecutor
# ============================================================

"""Core domain ABCs — infrastructure-free contracts for every integration point.

Usage::

    from app.interfaces.core import IMemoryStore, MemoryEntry

    class MyMemoryStore(IMemoryStore):
        async def store(self, entry: MemoryEntry) -> str: ...
        async def retrieve(self, user_id, query, top_k) -> list[MemoryEntry]: ...
        async def delete(self, user_id, memory_id) -> None: ...
        async def delete_all(self, user_id) -> None: ...
"""

from __future__ import annotations

import enum
from abc import ABC, abstractmethod
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from typing import Any

# Internal domain imports only — no infrastructure
from app.agents.models import AgentEvent, AgentExecution, AgentRequest
from app.schemas.mcp import MCPToolResult, MCPToolSchema

# ============================================================
# Value objects (shared across multiple ABCs)
# ============================================================


class MemoryType(str, enum.Enum):
    """Category of a stored memory entry.

    Mirrors the backend ``MemoryType`` ORM enum without importing SQLAlchemy.
    """

    FACT = "fact"
    PREFERENCE = "preference"
    WRITING_STYLE = "writing_style"
    AGENT_STATE = "agent_state"


@dataclass
class MemoryEntry:
    """A single unit of long-term user memory.

    Infrastructure-free value object.  Concrete implementations map this
    to and from their own storage representation (PG row, Redis hash, etc.).

    Attributes:
        user_id:        Owner of this memory.
        content:        Plain-text content of the memory.
        memory_type:    Semantic category.
        memory_id:      Stable identifier.  Empty string when not yet persisted.
        relevance_score: Cosine similarity to the query that retrieved this entry
                         (0.0–1.0).  0.0 when not produced by semantic search.
        metadata:       Arbitrary additional key-value pairs.
    """

    user_id: str
    content: str
    memory_type: MemoryType = MemoryType.FACT
    memory_id: str = ""
    relevance_score: float = 0.0
    metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.user_id.strip():
            raise ValueError("MemoryEntry.user_id must not be blank.")
        if not self.content.strip():
            raise ValueError("MemoryEntry.content must not be blank.")
        if not (0.0 <= self.relevance_score <= 1.0):
            raise ValueError(
                f"MemoryEntry.relevance_score must be in [0.0, 1.0], "
                f"got {self.relevance_score}."
            )


@dataclass
class EmbeddingVector:
    """A dense float embedding vector and its source text.

    Attributes:
        values:     The embedding values.
        dimensions: Length of ``values``; validated at construction.
        source_text: The text that was embedded.
        model_name:  Name of the embedding model that produced this vector.
    """

    values: list[float]
    source_text: str = ""
    model_name: str = ""

    def __post_init__(self) -> None:
        if not self.values:
            raise ValueError("EmbeddingVector.values must not be empty.")

    @property
    def dimensions(self) -> int:
        return len(self.values)


@dataclass
class DocumentContent:
    """Extracted plain-text content from a source document.

    Attributes:
        document_id:   Stable identifier (matches the DB row / storage key).
        text:          Full extracted plain-text.
        page_count:    Number of pages in the source document (0 if not applicable).
        mime_type:     MIME type of the source file (e.g. ``"application/pdf"``).
        filename:      Original filename.
        extraction_metadata: Arbitrary metadata from the extractor
                             (e.g. author, creation date, OCR confidence).
    """

    document_id: str
    text: str
    page_count: int = 0
    mime_type: str = ""
    filename: str = ""
    extraction_metadata: dict[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not self.document_id.strip():
            raise ValueError("DocumentContent.document_id must not be blank.")


@dataclass(frozen=True)
class DocumentChunk:
    """One chunk of text produced during document ingestion.

    Attributes:
        chunk_id:       Stable identifier (e.g. ``"{doc_id}_chunk_{index}"``).
        document_id:    Parent document identifier.
        document_name:  Human-readable document name for citations.
        user_id:        Owner of the document.
        chunk_index:    Zero-based position within the document.
        page_number:    Source page (1-based); None if not applicable.
        text:           Chunk plain-text content.
        char_start:     Start character offset in the full document text.
        char_end:       End character offset in the full document text.
    """

    chunk_id: str
    document_id: str
    document_name: str
    user_id: str
    chunk_index: int
    text: str
    page_number: int | None = None
    char_start: int = 0
    char_end: int = 0

    def __post_init__(self) -> None:
        if not self.chunk_id.strip():
            raise ValueError("DocumentChunk.chunk_id must not be blank.")
        if not self.text.strip():
            raise ValueError("DocumentChunk.text must not be blank.")


@dataclass(frozen=True)
class StoredChunk:
    """A :class:`DocumentChunk` that has been persisted in a vector store.

    Extends the chunk with the embedding so callers can inspect what was stored.
    """

    chunk: DocumentChunk
    embedding: EmbeddingVector


@dataclass
class RetrievedChunk:
    """A chunk returned by a :class:`IRetriever` query.

    Attributes:
        chunk:          The stored chunk.
        similarity:     Cosine similarity to the query (0.0–1.0).
        retrieval_path: How this chunk was found
                        (``"ann"``, ``"bm25"``, ``"pgvector"``, ``"rrf"``).
    """

    chunk: DocumentChunk
    similarity: float
    retrieval_path: str = "ann"

    def __post_init__(self) -> None:
        if not (0.0 <= self.similarity <= 1.0):
            raise ValueError(
                f"RetrievedChunk.similarity must be in [0.0, 1.0], got {self.similarity}."
            )


@dataclass
class RetrievalResult:
    """Complete output of one :class:`IRetriever` query.

    Attributes:
        query:          The original query string.
        chunks:         Retrieved chunks, ordered by relevance (highest first).
        answer:         LLM-generated answer if the retriever includes generation;
                        empty string otherwise.
        citations:      Formatted citation strings for display.
    """

    query: str
    chunks: list[RetrievedChunk] = field(default_factory=list)
    answer: str = ""
    citations: list[dict[str, Any]] = field(default_factory=list)

    @property
    def has_results(self) -> bool:
        return bool(self.chunks)


@dataclass
class ToolExecutionRequest:
    """Input for one :class:`IToolExecutor` call.

    Attributes:
        tool_name:  Name of the tool to invoke (matches :attr:`ToolSchema.name`).
        args:       String-valued arguments for the tool.
        user_id:    Authenticated user ID.
        timeout_ms: Per-call timeout in milliseconds; 0 = use tool's own default.
    """

    tool_name: str
    args: dict[str, str]
    user_id: str
    timeout_ms: int = 0

    def __post_init__(self) -> None:
        if not self.tool_name.strip():
            raise ValueError("ToolExecutionRequest.tool_name must not be blank.")
        if not self.user_id.strip():
            raise ValueError("ToolExecutionRequest.user_id must not be blank.")


@dataclass
class ToolExecutionResult:
    """Outcome of one :class:`IToolExecutor` call.

    Attributes:
        tool_name:   Name of the tool that was invoked.
        success:     True when execution completed without error.
        output:      Tool output text; empty on failure.
        error:       Human-safe error message; empty on success.
        duration_ms: Wall-clock execution time.
    """

    tool_name: str
    success: bool
    output: str = ""
    error: str = ""
    duration_ms: int = 0

    @property
    def is_empty(self) -> bool:
        return not self.output.strip()


# ============================================================
# IPlanner
# ============================================================


class IPlanner(ABC):
    """Abstract planner — builds execution plans and enforces runtime limits.

    Replaces direct use of the concrete :class:`~app.agents.planner.AgentPlanner`
    so tests and future alternative planners can be injected without depending
    on the concrete class.

    The existing :class:`~app.agents.planner.AgentPlanner` satisfies this
    interface and can be adapted with a thin wrapper.

    Design note:
        ``build_plan`` is synchronous (no I/O); ``check_limits`` is also
        synchronous.  This matches the existing concrete implementation and
        keeps planning deterministic and testable without event loops.
    """

    @abstractmethod
    def build_plan(
        self,
        request: AgentRequest,
        agent_name: str,
        available_agent_names: list[str],
    ) -> list[str]:
        """Build an ordered list of agent names to execute for *request*.

        Args:
            request:               The incoming agent request.
            agent_name:            Name of the initially selected agent.
            available_agent_names: All registered agent names (for multi-step
                                   plan validation).

        Returns:
            Ordered list of agent names.  Single-element for simple requests;
            multiple elements for multi-step plans.

        Raises:
            ValueError: If the plan violates hard limits (max_steps, etc.).
        """

    @abstractmethod
    def max_steps(self) -> int:
        """Return the hard limit on plan steps for this planner instance."""

    @abstractmethod
    def max_tool_calls(self) -> int:
        """Return the hard limit on total tool calls per plan."""

    @abstractmethod
    def timeout_ms(self) -> int:
        """Return the hard execution timeout in milliseconds."""


# ============================================================
# IAgentExecutor
# ============================================================


class IAgentExecutor(ABC):
    """Abstract executor — drives a single agent's execution lifecycle.

    Separates the *orchestration* concern (which agent, in what order) from
    the *execution* concern (how to run one agent, handle errors, emit events).

    The concrete :class:`~app.agents.orchestrator.AgentOrchestrator` handles
    orchestration; ``IAgentExecutor`` implementations handle the inner loop
    for one agent step.

    Note on typing:
        ``execute`` returns ``AsyncIterator[AgentEvent]`` to match the existing
        :meth:`~app.agents.base.Agent.execute` signature and the WebSocket
        event-streaming pattern already in production.
    """

    @abstractmethod
    def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]:
        """Execute one agent step and yield :class:`~app.agents.models.AgentEvent` values.

        The generator always terminates with a terminal event
        (``AgentCompletedEvent``, ``AgentFailedEvent``, or ``AgentCancelledEvent``).

        Args:
            request:   The (possibly mutated) request for this step.
            execution: Pre-populated execution envelope with status STARTED.

        Yields:
            :class:`~app.agents.models.AgentEvent` instances in emission order.
        """

    @abstractmethod
    async def cancel(self, execution_id: str) -> None:
        """Request cancellation of the execution identified by *execution_id*.

        Implementations must honour this within one yield cycle.
        Calling cancel on an already-terminal execution is a no-op.

        Args:
            execution_id: The UUID string from :attr:`AgentExecution.execution_id`.
        """


# ============================================================
# IMemoryStore
# ============================================================


class IMemoryStore(ABC):
    """Abstract memory store — persists and retrieves long-term user memories.

    No dependency on ChromaDB, PostgreSQL, or Redis.  Implementations may
    back this with any combination of vector databases and relational stores.

    The existing :class:`~app.services.memory_service.MemoryService` wraps
    ChromaDB + PostgreSQL; it can implement this interface via an adapter.

    Privacy contract:
        When ``privacy_mode`` is active for a user, :meth:`store` MUST be
        a no-op.  The interface does not enforce this — callers are responsible
        for checking user preferences before calling :meth:`store`.
    """

    @abstractmethod
    async def store(self, entry: MemoryEntry) -> str:
        """Persist *entry* and return its stable ``memory_id``.

        Implementations MUST be idempotent: storing an entry that already
        has a ``memory_id`` updates the existing record rather than creating
        a duplicate.

        Args:
            entry: The memory to store.  ``entry.memory_id`` may be empty
                   for new memories.

        Returns:
            The persisted ``memory_id``.

        Raises:
            ValueError: If ``entry.user_id`` or ``entry.content`` is blank.
        """

    @abstractmethod
    async def retrieve(
        self,
        user_id: str,
        query: str,
        top_k: int = 5,
        memory_type: MemoryType | None = None,
    ) -> list[MemoryEntry]:
        """Return the *top_k* memories most semantically relevant to *query*.

        Memories are ordered by ``relevance_score`` descending.

        Args:
            user_id:     Restrict retrieval to memories owned by this user.
            query:       Natural-language query used for similarity search.
            top_k:       Maximum number of memories to return.
            memory_type: When provided, restrict to this type only.

        Returns:
            List of :class:`MemoryEntry` ordered by ``relevance_score`` desc.
            Empty list when no memories match or the store is empty.
        """

    @abstractmethod
    async def delete(self, user_id: str, memory_id: str) -> None:
        """Delete the memory identified by *memory_id* from *user_id*'s store.

        No-op when the memory does not exist.

        Args:
            user_id:   Owner of the memory (used for isolation enforcement).
            memory_id: The stable ID returned by :meth:`store`.
        """

    @abstractmethod
    async def delete_all(self, user_id: str) -> None:
        """Delete all memories belonging to *user_id*.

        Used for GDPR account deletion.

        Args:
            user_id: Owner whose memories should be purged.
        """


# ============================================================
# IDocumentLoader
# ============================================================


class IDocumentLoader(ABC):
    """Abstract document loader — extracts plain text from raw file bytes.

    No dependency on pdfplumber, MinIO, pytesseract, python-docx, or any
    storage layer.  Implementations receive raw bytes and return a
    :class:`DocumentContent` value object.

    The existing :class:`~app.services.rag_service.RAGService` contains the
    extraction logic; it can be adapted to this interface.

    Supported formats are declared by each implementation; callers should
    check :meth:`supports` before calling :meth:`load`.
    """

    @abstractmethod
    async def load(
        self,
        file_bytes: bytes,
        filename: str,
        document_id: str,
        mime_type: str = "",
    ) -> DocumentContent:
        """Extract plain text from *file_bytes*.

        Args:
            file_bytes:  Raw file content.
            filename:    Original filename (used for format detection when
                         ``mime_type`` is empty and for citation display).
            document_id: Stable document identifier.
            mime_type:   MIME type hint; may be empty — implementations fall
                         back to extension-based detection.

        Returns:
            :class:`DocumentContent` with extracted text and metadata.

        Raises:
            DocumentLoadError: When extraction fails irrecoverably.
            UnsupportedFormatError: When the format is not supported.
        """

    @abstractmethod
    def supports(self, mime_type: str, filename: str) -> bool:
        """Return True when this loader can handle the given format.

        Args:
            mime_type: MIME type of the file.
            filename:  Original filename (fallback for format detection).

        Returns:
            True if the loader supports this format.
        """

    @abstractmethod
    def max_file_size_bytes(self) -> int:
        """Return the maximum file size (bytes) this loader accepts.

        Files larger than this limit should be rejected before calling
        :meth:`load`.
        """


class DocumentLoadError(Exception):
    """Raised by :class:`IDocumentLoader` when extraction fails irrecoverably.

    Attributes:
        stage:     The pipeline stage where failure occurred (``"extraction"``,
                   ``"ocr"``, ``"decode"``).
        filename:  Source filename.
        detail:    Human-readable description of the failure.
    """

    def __init__(self, stage: str, filename: str, detail: str = "") -> None:
        self.stage = stage
        self.filename = filename
        self.detail = detail
        super().__init__(f"[{stage}] {filename}: {detail}" if detail else f"[{stage}] {filename}")


class UnsupportedFormatError(DocumentLoadError):
    """Raised when a file format is not supported by the loader."""

    def __init__(self, filename: str, mime_type: str = "") -> None:
        super().__init__(
            stage="format_check",
            filename=filename,
            detail=f"Unsupported format: mime_type={mime_type!r}",
        )


# ============================================================
# IEmbeddingProvider
# ============================================================


class IEmbeddingProvider(ABC):
    """Abstract embedding provider — converts text to dense float vectors.

    No dependency on sentence-transformers, google-genai, or any model library.
    Implementations may use a local model (SentenceTransformer), a cloud API
    (Gemini Embeddings, OpenAI Embeddings), or an on-device model.

    The existing ``RAGService._get_embedding_model()`` uses SentenceTransformer;
    it can be wrapped behind this interface.

    Thread-safety:
        Implementations must be safe to call concurrently from an async
        executor.  If the underlying model is not thread-safe, implementations
        must serialise access.
    """

    @abstractmethod
    async def embed(self, text: str) -> EmbeddingVector:
        """Embed a single text string.

        Args:
            text: The input text to embed.  Must not be empty.

        Returns:
            :class:`EmbeddingVector` for *text*.

        Raises:
            EmbeddingError: On model failure.
            ValueError: When *text* is empty.
        """

    @abstractmethod
    async def embed_batch(self, texts: list[str]) -> list[EmbeddingVector]:
        """Embed a list of text strings in a single batch call.

        Implementations may call :meth:`embed` sequentially when batching is
        not supported by the underlying model — callers should prefer this
        method for bulk operations regardless.

        Args:
            texts: Non-empty list of strings to embed.

        Returns:
            List of :class:`EmbeddingVector` in the same order as *texts*.

        Raises:
            EmbeddingError: On model failure.
            ValueError: When *texts* is empty.
        """

    @property
    @abstractmethod
    def model_name(self) -> str:
        """Name of the underlying embedding model (e.g. ``"all-MiniLM-L6-v2"``)."""

    @property
    @abstractmethod
    def embedding_dimension(self) -> int:
        """Dimensionality of vectors produced by this provider."""


class EmbeddingError(Exception):
    """Raised by :class:`IEmbeddingProvider` on model failure.

    Attributes:
        model_name: The model that raised the error.
        retryable:  True when a retry may succeed (transient failure).
    """

    def __init__(
        self, message: str, model_name: str = "", retryable: bool = False
    ) -> None:
        super().__init__(message)
        self.model_name = model_name
        self.retryable = retryable


# ============================================================
# IVectorStore
# ============================================================


class IVectorStore(ABC):
    """Abstract vector store — stores and searches dense float embeddings.

    No dependency on ChromaDB, pgvector, or any specific vector database.
    Implementations may use ChromaDB (existing production path), pgvector,
    FAISS, Qdrant, or an in-memory store for testing.

    Per-user isolation:
        All operations are scoped to ``user_id``.  Implementations MUST
        ensure cross-user data leakage is impossible.
    """

    @abstractmethod
    async def upsert(self, chunk: StoredChunk) -> None:
        """Store or update a :class:`StoredChunk`.

        Upsert semantics: when a chunk with the same
        :attr:`DocumentChunk.chunk_id` already exists for the user, it is
        replaced.

        Args:
            chunk: The chunk and its embedding to store.
        """

    @abstractmethod
    async def search(
        self,
        user_id: str,
        query_embedding: EmbeddingVector,
        top_k: int = 5,
        min_similarity: float = 0.0,
    ) -> list[RetrievedChunk]:
        """Return the *top_k* chunks most similar to *query_embedding*.

        Results are ordered by similarity descending.  Chunks from other
        users MUST NOT appear in results regardless of similarity.

        Args:
            user_id:         Restrict search to this user's documents.
            query_embedding: Embedding of the query text.
            top_k:           Maximum number of results.
            min_similarity:  Minimum cosine similarity threshold (0.0–1.0).
                             Chunks below this threshold are excluded.

        Returns:
            List of :class:`RetrievedChunk` ordered by ``similarity`` desc.
        """

    @abstractmethod
    async def delete_by_document(self, user_id: str, document_id: str) -> None:
        """Delete all chunks belonging to *document_id* for *user_id*.

        Used during document deletion and re-ingestion.

        Args:
            user_id:     Owner of the document.
            document_id: Document whose chunks should be removed.
        """

    @abstractmethod
    async def delete_all(self, user_id: str) -> None:
        """Delete all chunks belonging to *user_id*.

        Used for GDPR account deletion.

        Args:
            user_id: Owner whose chunks should be purged.
        """

    @abstractmethod
    async def count(self, user_id: str) -> int:
        """Return the number of stored chunks for *user_id*.

        Args:
            user_id: Owner to count chunks for.

        Returns:
            Non-negative chunk count.
        """


# ============================================================
# IRetriever
# ============================================================


class IRetriever(ABC):
    """Abstract retriever — answers queries over a user's document corpus.

    Combines :class:`IEmbeddingProvider` and :class:`IVectorStore` behind a
    single query interface.  Implementations decide the retrieval strategy
    (ANN-only, BM25-only, hybrid with RRF, etc.) without exposing that detail
    to callers.

    The existing :class:`~app.services.rag_service.RAGService` implements
    this logic; it can be adapted to this interface.
    """

    @abstractmethod
    async def retrieve(
        self,
        user_id: str,
        query: str,
        top_k: int = 5,
        document_ids: list[str] | None = None,
    ) -> RetrievalResult:
        """Retrieve chunks relevant to *query* from *user_id*'s corpus.

        Args:
            user_id:      Restrict retrieval to this user's documents.
            query:        Natural-language query string.
            top_k:        Maximum number of chunks to return.
            document_ids: When provided, restrict retrieval to these documents.
                          ``None`` searches the full corpus.

        Returns:
            :class:`RetrievalResult` with ranked chunks and an optional
            LLM-generated answer if the implementation includes generation.
        """

    @abstractmethod
    async def retrieve_and_generate(
        self,
        user_id: str,
        query: str,
        top_k: int = 5,
        document_ids: list[str] | None = None,
    ) -> RetrievalResult:
        """Retrieve relevant chunks and generate an answer with citations.

        Extends :meth:`retrieve` by passing the assembled context to an LLM
        and populating :attr:`RetrievalResult.answer` and
        :attr:`RetrievalResult.citations`.

        Args:
            user_id:      Restrict retrieval to this user's documents.
            query:        Natural-language query.
            top_k:        Maximum chunks to retrieve before generation.
            document_ids: Optional document scope restriction.

        Returns:
            :class:`RetrievalResult` with ``answer`` and ``citations`` populated.
        """


# ============================================================
# IMCPClient
# ============================================================


class IMCPClient(ABC):
    """Abstract MCP client — discovers and invokes registered MCP tools.

    Decouples callers (agents, orchestrators) from the concrete
    :class:`~app.services.mcp_broker.MCPBroker`, which requires a
    SQLAlchemy ``AsyncSession``.  ``IMCPClient`` has no infrastructure
    dependencies in its signature.

    The existing :class:`~app.services.mcp_broker.MCPBroker` can be wrapped
    behind this interface via an adapter that injects the session.

    Audit guarantee (inherited from MCPBroker):
        Every :meth:`invoke` call MUST write an ``AuditLog`` entry regardless
        of outcome.  Implementations must preserve this invariant.
    """

    @abstractmethod
    def discover(self) -> list[MCPToolSchema]:
        """Return the schemas of all registered MCP tools.

        Returns:
            List of :class:`~app.schemas.mcp.MCPToolSchema`.
            Empty list when no tools are registered.
        """

    @abstractmethod
    async def invoke(
        self,
        tool_name: str,
        params: dict[str, Any],
        user_id: str,
    ) -> MCPToolResult:
        """Invoke the named MCP tool on behalf of *user_id*.

        Args:
            tool_name: Name of the tool to invoke.
            params:    Tool-specific parameters.
            user_id:   String UUID of the requesting user.

        Returns:
            :class:`~app.schemas.mcp.MCPToolResult` with outcome details.
            Never raises — errors are surfaced via ``MCPToolResult.success=False``.
        """

    @abstractmethod
    def is_registered(self, tool_name: str) -> bool:
        """Return True when a tool with *tool_name* is registered.

        Args:
            tool_name: Tool name to check.

        Returns:
            True when the tool is available for invocation.
        """


# ============================================================
# IToolExecutor
# ============================================================


class IToolExecutor(ABC):
    """Abstract tool executor — validates and runs tools from the ToolRegistry.

    Separates the *execution pipeline* (validate → permission check → run →
    timeout enforcement → result wrapping) from callers.

    The existing :class:`~app.agents.tool_agent.ToolAgent` implements much of
    this logic; it can be refactored to implement this interface.

    Security contract (must be preserved by all implementations):
        1. :meth:`execute` MUST call validation before :meth:`run`.
        2. Permission checks are performed before any I/O.
        3. Execution is bounded by :attr:`ToolExecutionRequest.timeout_ms`
           (or the tool's own ``timeoutMs`` when 0).
        4. :class:`ToolExecutionResult` MUST NOT contain stack traces.
    """

    @abstractmethod
    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult:
        """Validate and execute the tool specified by *request*.

        Full pipeline:
            1. Look up the tool by ``request.tool_name``.
            2. Validate ``request.args`` via the tool's validate method.
            3. Check that ``request.user_id`` holds all required permissions.
            4. Invoke the tool under a timeout.
            5. Return a :class:`ToolExecutionResult`.

        Never throws.  All errors are returned as
        ``ToolExecutionResult(success=False, error=<safe message>)``.

        Args:
            request: The tool execution request.

        Returns:
            :class:`ToolExecutionResult` describing the outcome.
        """

    @abstractmethod
    def is_available(self, tool_name: str) -> bool:
        """Return True when the named tool is registered and usable.

        Args:
            tool_name: Tool name to check.

        Returns:
            True when the tool is registered in this executor's registry.
        """

    @abstractmethod
    def list_tools(self) -> list[str]:
        """Return the names of all tools available through this executor.

        Returns:
            Sorted list of tool names.
        """
