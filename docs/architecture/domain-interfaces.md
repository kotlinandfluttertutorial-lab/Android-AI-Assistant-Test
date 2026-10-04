# Domain Interfaces — MCP, Agent, Document/RAG, Memory, and LLM

> **Document Type:** Reference  
> **Date:** 2026-10-01  
> **Status:** Implemented and tested  
> **Scope:** Clean Architecture domain interfaces introduced to support MCP, Agent, and Document/RAG integration  
> **Related docs:** `current-state.md`, `target-architecture.md`, `implementation-plan.md`

---

## Table of Contents

1. [Design Principles](#1-design-principles)
2. [What Already Existed (Reused)](#2-what-already-existed-reused)
3. [New Backend Interfaces](#3-new-backend-interfaces)
4. [New Android Interfaces](#4-new-android-interfaces)
5. [Interface Dependency Map](#5-interface-dependency-map)
6. [Value Object Reference](#6-value-object-reference)
7. [Test Coverage](#7-test-coverage)
8. [Integration Guidance](#8-integration-guidance)

---

## 1. Design Principles

All interfaces in this layer follow four rules:

1. **Dependency Inversion** — high-level policy (agents, orchestrators, use cases) depends on abstractions in this package, never on concrete infrastructure (ChromaDB, SQLAlchemy, Redis, `sentence_transformers`, MinIO, `pdfplumber`, `google-genai`, Room).

2. **No infrastructure imports** — every interface file contains zero imports from infrastructure libraries. Verified by `TestNoInfrastructureImports` in the backend test suite and enforced structurally by package placement.

3. **Single responsibility** — each interface covers one concern. `IVectorStore` stores and searches vectors; it does not embed text. `IEmbeddingProvider` embeds text; it does not store anything.

4. **Extend, don't replace** — interfaces that already existed (`LLMProvider`, `Agent`, `MCPToolConnector` on the backend; `Agent`, `AgentOrchestrator`, `LlmClient`, `Tool`, `ToolRegistry` on Android) are reused directly. The new interfaces fill precisely the gaps identified in the pre-integration audit.

---

## 2. What Already Existed (Reused)

### Backend (unchanged, re-exported via `app.interfaces`)

| Symbol | Canonical location | What it provides |
|---|---|---|
| `LLMProvider` | `app/llm/base.py` | ABC for all LLM providers; `generate()` + `stream()` |
| `LLMRequest` / `LLMResponse` / `LLMUsage` | `app/llm/base.py` | Provider-agnostic request/response types |
| `Agent` | `app/agents/base.py` | ABC for all agent implementations |
| `AgentCapability` | `app/agents/models.py` | Capability enum used for agent routing |
| `AgentContext` / `AgentRequest` / `AgentResult` / `AgentExecution` | `app/agents/models.py` | Complete agent execution data model |
| `AgentEvent` hierarchy (13 subtypes) | `app/agents/models.py` | Streaming event model for WebSocket emission |
| `AgentDecision` hierarchy | `app/agents/models.py` | Decision types for agent reasoning |
| `MCPToolConnector` | `app/services/mcp_broker.py` | ABC for all MCP tool connectors |
| `MCPToolSchema` / `MCPToolResult` | `app/schemas/mcp.py` | MCP discovery and result types |

> These are **re-exported** from `app.interfaces` via lazy `__getattr__` so callers have a single import path without triggering infrastructure-heavy transitive imports.

### Android (unchanged, in-place)

| Symbol | Location | What it provides |
|---|---|---|
| `Agent` | `domain/agent/Agent.kt` | Interface for all agent implementations |
| `AgentOrchestrator` + `DefaultAgentOrchestrator` | `domain/agent/AgentOrchestrator.kt` | Agent execution coordinator |
| `AgentPlanner` + `DefaultAgentPlanner` | `domain/agent/AgentPlanner.kt` | Plan building and limit checking |
| `AgentRouter` + `DefaultAgentRouter` | `domain/agent/AgentRouter.kt` | Agent routing by capability |
| `AgentRegistry` + `DefaultAgentRegistry` | `domain/agent/AgentRegistry.kt` | Thread-safe agent store |
| `AgentContext` / `AgentRequest` / `AgentResult` / `AgentExecution` | `domain/agent/*.kt` | Execution data model |
| `AgentEvent` sealed class (13+ subtypes) | `domain/agent/AgentEvent.kt` | Streaming event model |
| `LlmClient` | `domain/agent/LlmClient.kt` | Interface for LLM completions and streaming |
| `LlmRequest` / `LlmResponse` / `LlmEvent` | `domain/agent/LlmClient.kt` | LLM input/output types |
| `Tool` | `domain/agent/Tool.kt` | Interface for executable tools |
| `ToolSchema` / `ToolResult` / `ToolPermission` | `domain/agent/Tool.kt` | Tool metadata and security types |
| `ToolRegistry` + `DefaultToolRegistry` | `domain/agent/ToolRegistry.kt` | Thread-safe tool store |
| `OnDeviceEmbeddingModel` | `core-common/RagContracts.kt` | On-device embedding lifecycle interface |
| `LocalVectorIndex` | `core-common/RagContracts.kt` | On-device vector index interface |
| `QueryRouter` | `core-common/RagContracts.kt` | Cloud vs. on-device routing interface |
| `MCPTool` | `domain/model/MCPTool.kt` | MCP tool domain entity |
| `Memory` / `MemoryType` | `domain/model/Memory.kt` | Memory domain entity |
| `Document` / `IngestionStatus` | `domain/model/Document.kt` | Document domain entity |
| `DocumentRepository` | `domain/repository/DocumentRepository.kt` | Network-level document CRUD contract |
| `MemoryRepository` | `domain/repository/MemoryRepository.kt` | Network-level memory CRUD contract |

---

## 3. New Backend Interfaces

All in `backend/app/interfaces/core.py`. Import via:

```python
from app.interfaces.core import IMemoryStore, MemoryEntry  # direct
from app.interfaces import IMemoryStore, MemoryEntry        # convenience re-export
```

---

### `IPlanner`

```python
class IPlanner(ABC):
    def build_plan(
        self,
        request: AgentRequest,
        agent_name: str,
        available_agent_names: list[str],
    ) -> list[str]: ...

    def max_steps(self) -> int: ...
    def max_tool_calls(self) -> int: ...
    def timeout_ms(self) -> int: ...
```

**Purpose:** Decouples callers from the concrete `AgentPlanner`. The existing `AgentPlanner` can be adapted via a thin wrapper without changing its implementation.  
**Does not replace:** `AgentPlanner` in `agents/planner.py` — the concrete class still exists and is used directly where the ABC is not yet wired.

---

### `IAgentExecutor`

```python
class IAgentExecutor(ABC):
    def execute(
        self,
        request: AgentRequest,
        execution: AgentExecution,
    ) -> AsyncIterator[AgentEvent]: ...

    async def cancel(self, execution_id: str) -> None: ...
```

**Purpose:** Separates the *execution* concern (run one agent step, emit events) from the *orchestration* concern (which agent, in what order). The `AgentOrchestrator` handles orchestration; `IAgentExecutor` implementations handle the per-step inner loop.

---

### `IMemoryStore`

```python
class IMemoryStore(ABC):
    async def store(self, entry: MemoryEntry) -> str: ...
    async def retrieve(
        self, user_id: str, query: str,
        top_k: int = 5, memory_type: MemoryType | None = None,
    ) -> list[MemoryEntry]: ...
    async def delete(self, user_id: str, memory_id: str) -> None: ...
    async def delete_all(self, user_id: str) -> None: ...
```

**Purpose:** Decouples agents from `MemoryService` (which depends on ChromaDB + PostgreSQL). Agents only see `MemoryEntry` value objects.  
**Privacy contract:** When `privacy_mode` is active, `store` must be a no-op. Enforced by callers, not the interface.

---

### `IDocumentLoader`

```python
class IDocumentLoader(ABC):
    async def load(
        self,
        file_bytes: bytes,
        filename: str,
        document_id: str,
        mime_type: str = "",
    ) -> DocumentContent: ...

    def supports(self, mime_type: str, filename: str) -> bool: ...
    def max_file_size_bytes(self) -> int: ...
```

**Purpose:** Decouples document extraction from `pdfplumber`, `python-docx`, `pytesseract`, and MinIO. Receives raw bytes; returns `DocumentContent`. Both on-device and cloud extraction paths can implement this interface.  
**Error types:** `DocumentLoadError`, `UnsupportedFormatError`.

---

### `IEmbeddingProvider`

```python
class IEmbeddingProvider(ABC):
    async def embed(self, text: str) -> EmbeddingVector: ...
    async def embed_batch(self, texts: list[str]) -> list[EmbeddingVector]: ...

    @property
    def model_name(self) -> str: ...

    @property
    def embedding_dimension(self) -> int: ...
```

**Purpose:** Decouples embedding calls from `sentence_transformers`. Covers local models, cloud embedding APIs, and future model changes without altering callers.  
**Error type:** `EmbeddingError(model_name, retryable)`.

---

### `IVectorStore`

```python
class IVectorStore(ABC):
    async def upsert(self, chunk: StoredChunk) -> None: ...
    async def search(
        self,
        user_id: str,
        query_embedding: EmbeddingVector,
        top_k: int = 5,
        min_similarity: float = 0.0,
    ) -> list[RetrievedChunk]: ...
    async def delete_by_document(self, user_id: str, document_id: str) -> None: ...
    async def delete_all(self, user_id: str) -> None: ...
    async def count(self, user_id: str) -> int: ...
```

**Purpose:** Decouples vector search from ChromaDB and pgvector. Per-user isolation is a mandatory contract — cross-user leakage is a security violation.

---

### `IRetriever`

```python
class IRetriever(ABC):
    async def retrieve(
        self, user_id: str, query: str,
        top_k: int = 5, document_ids: list[str] | None = None,
    ) -> RetrievalResult: ...

    async def retrieve_and_generate(
        self, user_id: str, query: str,
        top_k: int = 5, document_ids: list[str] | None = None,
    ) -> RetrievalResult: ...
```

**Purpose:** Single entry point for the full RAG retrieval pipeline. Implementations compose `IEmbeddingProvider` + `IVectorStore` + LLM internally. Callers only interact with `RetrievalResult`.

---

### `IMCPClient`

```python
class IMCPClient(ABC):
    def discover(self) -> list[MCPToolSchema]: ...

    async def invoke(
        self, tool_name: str, params: dict[str, Any], user_id: str,
    ) -> MCPToolResult: ...

    def is_registered(self, tool_name: str) -> bool: ...
```

**Purpose:** Decouples agents from `MCPBroker` (which requires a SQLAlchemy `AsyncSession`). Agents receive a session-free client; the adapter injects the session at construction time.  
**Audit guarantee:** Every `invoke` call must write an `AuditLog` entry — this is enforced by `MCPBroker` and must be preserved by any `IMCPClient` implementation.

---

### `IToolExecutor`

```python
class IToolExecutor(ABC):
    async def execute(self, request: ToolExecutionRequest) -> ToolExecutionResult: ...
    def is_available(self, tool_name: str) -> bool: ...
    def list_tools(self) -> list[str]: ...
```

**Purpose:** Enforces the tool security pipeline (lookup → validate → permission check → timeout → execute) as a contract. Agents never call `Tool.execute()` directly — they call `IToolExecutor.execute()`.  
**Never throws:** All errors are returned as `ToolExecutionResult(success=False, error=<safe message>)`.

---

## 4. New Android Interfaces

All in `domain/src/main/kotlin/com/aiassistant/domain/agent/`. Pure Kotlin, zero Android/SDK dependencies.

---

### `MemoryStore` — `MemoryStore.kt`

```kotlin
interface MemoryStore {
    suspend fun store(memory: Memory): String
    suspend fun retrieve(userId: String, query: String, topK: Int = 5, memoryType: MemoryType? = null): List<Memory>
    fun observe(userId: String): Flow<List<Memory>>
    suspend fun delete(userId: String, memoryId: String)
    suspend fun deleteAll(userId: String)
}
```

**Purpose:** Agent-level memory contract. Distinct from `MemoryRepository` (network CRUD) — `MemoryStore` is the execution-time read/write interface agents use.  
**Error type:** `MemoryStoreException(retryable: Boolean)`.

---

### `DocumentLoader` — `DocumentLoader.kt`

```kotlin
interface DocumentLoader {
    suspend fun load(fileBytes: ByteArray, filename: String, documentId: String, mimeType: String = ""): LoadedDocument
    fun supports(mimeType: String, filename: String): Boolean
    val maxFileSizeBytes: Long
}
```

**Value objects:** `LoadedDocument(documentId, text, pageCount, mimeType, filename, extractionMetadata)`.  
**Error types:** `open class DocumentLoadException(stage, filename, detail)`, `UnsupportedDocumentFormatException`.

---

### `EmbeddingProvider` — `EmbeddingProvider.kt`

```kotlin
interface EmbeddingProvider {
    val modelName: String
    val embeddingDimension: Int
    val isReady: Boolean
    suspend fun embed(text: String): EmbeddingVector
    suspend fun embedBatch(texts: List<String>): List<EmbeddingVector>
}
```

**Value object:** `EmbeddingVector(values: List<Float>, modelName, sourceText)` with a `cosineSimilarity(other)` method.  
**Relationship to existing:** Does NOT extend `OnDeviceEmbeddingModel` (which has lifecycle methods incompatible with cloud providers). The data layer adapts both.  
**Error type:** `EmbeddingException(modelName, retryable)`.

---

### `VectorStore` — `VectorStore.kt`

```kotlin
interface VectorStore {
    suspend fun upsert(chunk: VectorChunk, embedding: EmbeddingVector)
    suspend fun search(userId: String, queryEmbedding: EmbeddingVector, topK: Int = 5, minSimilarity: Float = 0f): List<VectorSearchResult>
    suspend fun deleteByDocument(userId: String, documentId: String)
    suspend fun deleteAll(userId: String)
    suspend fun count(userId: String): Int
}
```

**Value objects:** `VectorChunk` (chunkId, documentId, userId, text, pageNumber), `VectorSearchResult` (chunk, similarity, retrievalPath).  
**Relationship to existing:** Does NOT extend `LocalVectorIndex` from `core-common` (which uses `TextChunk` — bringing `core-common` into the domain layer's type graph). The `:data` adapter bridges both without introducing the dependency.

---

### `Retriever` — `Retriever.kt`

```kotlin
interface Retriever {
    suspend fun retrieve(userId: String, query: String, topK: Int = 5, documentIds: List<String>? = null): RetrievalResult
    suspend fun retrieveAndGenerate(userId: String, query: String, topK: Int = 5, documentIds: List<String>? = null): RetrievalResult
}
```

**Value objects:** `RetrievalResult(query, chunks, answer, citations)`, `RetrievalCitation(documentId, documentName, pageNumber, excerpt, similarity, retrievalPath)`.

---

### `MCPClient` — `MCPClient.kt`

```kotlin
interface MCPClient {
    suspend fun discoverTools(): List<MCPTool>
    suspend fun invoke(toolName: String, params: Map<String, String>, userId: String, confirmed: Boolean = false): MCPInvocationResult
    fun isRegistered(toolName: String): Boolean
}
```

**Value object:** `MCPInvocationResult(toolName, success, output, error, requiresConfirmation, metadata)`.  
**Confirmation flow:** When `requiresConfirmation = true`, the agent emits `AgentEvent.ToolConfirmationRequired`; the UI shows `ToolConfirmationDialog`; the user approves; the agent re-calls `invoke(confirmed = true)`.

---

### `ToolExecutor` — `ToolExecutor.kt`

```kotlin
interface ToolExecutor {
    suspend fun execute(request: ToolExecutionRequest): ToolExecutionResponse
    fun isAvailable(toolName: String): Boolean
    fun listTools(): List<String>
}
```

**Value objects:** `ToolExecutionRequest(toolName, args, userId, callerPermissions, timeoutMs)`, `ToolExecutionResponse(toolName, success, output, error, durationMs)` with `ToolExecutionResponse.failure(toolName, errorMessage)` factory.  
**Security pipeline enforced:** lookup → validate (`Tool.validate`) → permission check (`ToolPermission`) → `withTimeout` → `Tool.execute`.

---

## 5. Interface Dependency Map

### Backend

```
Callers (agents, orchestrators, routers)
    │
    ├── IPlanner            ← adapts AgentPlanner (agents/planner.py)
    ├── IAgentExecutor      ← implemented by concrete agents
    ├── IMemoryStore        ← adapts MemoryService (services/memory_service.py)
    ├── IDocumentLoader     ← adapts RAGService._extract_text (services/rag_service.py)
    ├── IEmbeddingProvider  ← adapts RAGService._get_embedding_model (SentenceTransformer)
    ├── IVectorStore        ← adapts ChromaDB / pgvector operations in RAGService
    ├── IRetriever          ← adapts RAGService.query_documents
    ├── IMCPClient          ← adapts MCPBroker (services/mcp_broker.py)
    └── IToolExecutor       ← adapts ToolAgent tool execution pipeline

── Already existed, imported from their canonical modules:
    ├── LLMProvider         (app/llm/base.py)
    ├── Agent               (app/agents/base.py)
    └── MCPToolConnector    (app/services/mcp_broker.py)
```

### Android

```
Callers (Agents, UseCases, ViewModels via use cases)
    │
    ├── MemoryStore         ← data: adapts MemoryRemoteDataSource or Room
    ├── DocumentLoader      ← data: adapts PdfRenderer / plain-text reader, or /documents/extract endpoint
    ├── EmbeddingProvider   ← data: adapts OnDeviceEmbeddingModel (on-device) or embedding API (cloud)
    ├── VectorStore         ← data: adapts LocalVectorIndex (on-device) or remote vector store (cloud)
    ├── Retriever           ← data: adapts OnDevice*Adapter or RemoteRetriever (POST /documents/query)
    ├── MCPClient           ← data: adapts MCPApiService (Retrofit → /mcp/tools + /mcp/invoke)
    └── ToolExecutor        ← data: DefaultToolExecutor backed by DefaultToolRegistry

── Already existed, unchanged:
    ├── LlmClient           (domain/agent/LlmClient.kt)
    ├── Agent               (domain/agent/Agent.kt)
    ├── AgentOrchestrator   (domain/agent/AgentOrchestrator.kt)
    ├── AgentPlanner        (domain/agent/AgentPlanner.kt)
    ├── AgentRouter         (domain/agent/AgentRouter.kt)
    ├── AgentRegistry       (domain/agent/AgentRegistry.kt)
    ├── Tool                (domain/agent/Tool.kt)
    └── ToolRegistry        (domain/agent/ToolRegistry.kt)
```

---

## 6. Value Object Reference

### Backend (`app/interfaces/core.py`)

| Class | Key fields | Validation |
|---|---|---|
| `MemoryType` | `FACT`, `PREFERENCE`, `WRITING_STYLE`, `AGENT_STATE` | — |
| `MemoryEntry` | `user_id`, `content`, `memory_type`, `memory_id`, `relevance_score` [0–1], `metadata` | blank user_id/content raise; relevance outside [0,1] raises |
| `EmbeddingVector` | `values: list[float]`, `model_name`, `source_text` | empty values raises |
| `DocumentContent` | `document_id`, `text`, `page_count`, `mime_type`, `filename`, `extraction_metadata` | blank document_id raises |
| `DocumentChunk` | `chunk_id`, `document_id`, `document_name`, `user_id`, `chunk_index`, `text`, `page_number?`, `char_start`, `char_end` | blank chunk_id/text raise; frozen dataclass |
| `StoredChunk` | `chunk: DocumentChunk`, `embedding: EmbeddingVector` | — |
| `RetrievedChunk` | `chunk: DocumentChunk`, `similarity` [0–1], `retrieval_path` | similarity outside [0,1] raises |
| `RetrievalResult` | `query`, `chunks`, `answer`, `citations` | `has_results` and `has_answer` computed properties |
| `ToolExecutionRequest` | `tool_name`, `args`, `user_id`, `timeout_ms ≥ 0` | blank tool_name/user_id, negative timeout raise |
| `ToolExecutionResult` | `tool_name`, `success`, `output`, `error`, `duration_ms` | `is_empty` computed; `failure()` factory |

### Android (`domain/agent/`)

| Class | File | Key fields | Validation |
|---|---|---|---|
| `EmbeddingVector` | `EmbeddingProvider.kt` | `values: List<Float>`, `modelName`, `sourceText` | empty values raise; `cosineSimilarity(other)` method |
| `LoadedDocument` | `DocumentLoader.kt` | `documentId`, `text`, `pageCount`, `mimeType`, `filename`, `extractionMetadata` | blank documentId raises; `isEmpty` computed |
| `VectorChunk` | `VectorStore.kt` | `chunkId`, `documentId`, `documentName`, `userId`, `chunkIndex`, `text`, `pageNumber?` | blank chunkId/text/userId raise |
| `VectorSearchResult` | `VectorStore.kt` | `chunk`, `similarity` [0–1], `retrievalPath` | similarity outside [0,1] raises |
| `RetrievalResult` | `Retriever.kt` | `query`, `chunks`, `answer`, `citations` | `hasResults`, `hasAnswer` computed |
| `RetrievalCitation` | `Retriever.kt` | `documentId`, `documentName`, `pageNumber?`, `excerpt`, `similarity`, `retrievalPath` | — |
| `MCPInvocationResult` | `MCPClient.kt` | `toolName`, `success`, `output`, `error`, `requiresConfirmation`, `metadata` | `isEmpty` computed |
| `ToolExecutionRequest` | `ToolExecutor.kt` | `toolName`, `args`, `userId`, `callerPermissions`, `timeoutMs ≥ 0` | blank toolName/userId, negative timeoutMs raise |
| `ToolExecutionResponse` | `ToolExecutor.kt` | `toolName`, `success`, `output`, `error`, `durationMs`, `metadata` | `isEmpty` computed; `failure()` factory |

---

## 7. Test Coverage

### Backend — `backend/tests/unit/test_interfaces_core.py`

**70 tests, all passing.** Test groups:

| Group | Tests | What is verified |
|---|---|---|
| `TestMemoryType` | 2 | Enum values and string subtype |
| `TestMemoryEntry` | 7 | Valid construction, blank field rejection, boundary relevance scores |
| `TestEmbeddingVector` | 4 | Valid construction, empty values rejection, dimensions property |
| `TestDocumentContent` | 3 | Valid construction, blank document_id rejection, all fields |
| `TestDocumentChunk` | 4 | Valid construction, blank field rejection, frozen (immutable) |
| `TestStoredChunk` | 1 | Valid construction with nested types |
| `TestRetrievedChunk` | 4 | Valid construction, similarity bounds enforcement |
| `TestRetrievalResult` | 2 | `has_results` computed property |
| `TestToolExecutionRequest` | 3 | Blank field validation |
| `TestToolExecutionResult` | 2 | Success/failure state, `is_empty` |
| `TestErrorTypes` | 3 | `DocumentLoadError`, `UnsupportedFormatError`, `EmbeddingError` |
| `TestAbstractnessEnforced` | 9 | Each ABC raises `TypeError` on direct instantiation |
| `TestConcreteStubsAreValid` | 21 | Each ABC satisfied by a minimal concrete stub |
| `TestInitReExports` | 5 | All re-exported symbols importable from `app.interfaces` |
| `TestNoInfrastructureImports` | 1 | No infra library name appears in `core.py` import statements |

### Android — `domain/src/test/kotlin/com/aiassistant/domain/agent/`

**130 tests across 7 files, all passing.**

| File | Tests | What is verified |
|---|---|---|
| `MemoryStoreTest` | 14 | Store/retrieve CRUD, topK, type filter, observe flow, deleteAll isolation, privacy no-op, exception attributes |
| `DocumentLoaderTest` | 17 | `LoadedDocument` construction, `isEmpty`, `DocumentLoadException` message formatting, `UnsupportedDocumentFormatException` inheritance, `supports()`, `load()` happy/error/unsupported paths |
| `EmbeddingProviderTest` | 21 | `EmbeddingVector` construction, `dimensions`, `cosineSimilarity` (identical/orthogonal/opposite/zero/known-value/mismatch), `EmbeddingException` attributes, `embed`/`embedBatch` happy/error paths, `isReady` state |
| `VectorStoreTest` | 21 | `VectorChunk`/`VectorSearchResult` construction and validation, `upsert`, upsert idempotency, `search` ordering/topK/minSimilarity/user-isolation, `deleteByDocument`/`deleteAll` isolation, `count` |
| `RetrieverTest` | 18 | `RetrievalCitation` fields, `RetrievalResult` computed properties, `retrieve` user-scope/topK/documentId-filter/citations, `retrieveAndGenerate` answer population |
| `MCPClientTest` | 19 | `MCPInvocationResult` states, `MCPClientException` attributes, `discoverTools`, `isRegistered`, `invoke` for unknown/confirmation/confirmed/normal tool, `MCPTool` model |
| `ToolExecutorTest` | 20 | `ToolExecutionRequest` validation, `ToolExecutionResponse` states/`failure` factory, full pipeline: unknown tool/validation failure/missing permission/success/no-required-permission; `isAvailable`, `listTools` sorted |

---

## 8. Integration Guidance

### Adding a new agent (backend)

```python
from app.interfaces import Agent, IMemoryStore, IRetriever, IToolExecutor
from app.agents.models import AgentCapability, AgentRequest, AgentExecution, AgentEvent

class MyAgent(Agent):
    def __init__(
        self,
        memory: IMemoryStore,
        retriever: IRetriever,
        executor: IToolExecutor,
    ) -> None:
        self._memory = memory
        self._retriever = retriever
        self._executor = executor

    @property
    def name(self) -> str: return "my_agent"
    @property
    def description(self) -> str: return "Does X."
    @property
    def capabilities(self) -> frozenset[AgentCapability]:
        return frozenset({AgentCapability.TEXT_GENERATION, AgentCapability.RAG_RETRIEVAL})

    async def execute(self, request, execution):
        memories = await self._memory.retrieve(request.user_id, request.input)
        rag_result = await self._retriever.retrieve_and_generate(request.user_id, request.input)
        # yield AgentEvent subclasses ...
```

### Adding a new agent (Android)

```kotlin
class MyAgent @Inject constructor(
    private val llmClient: LlmClient,
    private val retriever: Retriever,
    private val toolExecutor: ToolExecutor,
) : Agent {
    override val name get() = "my_agent"
    override val description get() = "Does X."
    override val capabilities get() = setOf(AgentCapability.TEXT_GENERATION, AgentCapability.RAG_RETRIEVAL)

    override fun execute(request: AgentRequest, execution: AgentExecution): Flow<AgentEvent> = flow {
        val ragResult = retriever.retrieve(request.userId, request.input)
        val llmResponse = llmClient.generate(LlmRequest(prompt = ragResult.answer))
        // emit AgentEvent subclasses ...
    }
}
```

### Adding a new MCP tool connector (backend)

```python
from app.interfaces import MCPToolConnector  # convenience re-export
from app.schemas.mcp import MCPToolSchema, MCPToolResult

class NotionConnector(MCPToolConnector):
    @property
    def tool_name(self) -> str: return "notion"
    def get_schema(self) -> MCPToolSchema: ...
    async def invoke(self, params: dict, user_id: str) -> MCPToolResult: ...
    @property
    def requires_confirmation(self) -> bool: return False
```

### Adding a new MCP tool connector (Android)

Implement `MCPClient` in the data layer using Retrofit:

```kotlin
class RemoteMCPClientAdapter @Inject constructor(
    private val mcpApiService: MCPApiService,
) : MCPClient {
    override suspend fun discoverTools(): List<MCPTool> = mcpApiService.getTools().map { it.toDomain() }
    override suspend fun invoke(toolName: String, params: Map<String, String>, userId: String, confirmed: Boolean): MCPInvocationResult =
        mcpApiService.invoke(MCPInvokeRequest(toolName, params, confirmed)).toDomain()
    override fun isRegistered(toolName: String): Boolean = cachedTools.any { it.name == toolName }
}
```

### Implementing `IVectorStore` for production (backend)

The existing `RAGService` uses ChromaDB directly. To migrate:

```python
from app.interfaces.core import IVectorStore, StoredChunk, EmbeddingVector, RetrievedChunk

class ChromaVectorStore(IVectorStore):
    """Wraps the existing RAGService ChromaDB operations behind IVectorStore."""

    def __init__(self, rag_service: RAGService) -> None:
        self._rag = rag_service

    async def upsert(self, chunk: StoredChunk) -> None:
        await self._rag.embed_and_store(...)  # map StoredChunk fields

    async def search(self, user_id, query_embedding, top_k=5, min_similarity=0.0):
        results = await self._rag.query_documents(user_id=user_id, ...)
        return [RetrievedChunk(...) for r in results.chunks]
    # ... etc
```

This is the **adapter pattern** — RAGService is not modified; ChromaVectorStore wraps it.

---

*This document was generated during the core-interfaces implementation task. Update when new interfaces are added or existing interfaces change their signatures.*
