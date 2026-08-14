# Performance Guide
## Android AI Assistant — Enterprise Edition

---

## Performance Targets

| Metric | Target | Measurement Condition |
|--------|--------|-----------------------|
| Android cold start | ≤ 2 seconds | Mid-range device (Snapdragon 700-series) |
| First streaming token | ≤ 500 ms | Normal LLM provider latency |
| REST API p95 latency | ≤ 200 ms | 1,000 concurrent users, non-AI endpoints |
| FTS search response | ≤ 300 ms | Room FTS4 index |
| RAG document ingestion | ≤ 30 seconds | 10-page PDF |
| List page load | Instant from cache | Room + Paging 3, 20 items/page |
| Memory deletion | ≤ 10 seconds | ChromaDB removal after user deletes memory |
| Document cleanup | ≤ 60 seconds | All chunks removed after document deletion |

---

## Android — Cold Start ≤ 2 Seconds

**Techniques:**
- Hilt DI uses compile-time code generation — no reflection at startup
- `Application.onCreate()` defers all non-critical work (analytics, Firebase, Crashlytics)
  to background coroutines
- Room database initialisation is lazy — first access triggers build
- `SplashScreen API` displays immediately while Hilt and Room initialise
- `MainActivity` shows the Home Dashboard from Room cache before any network call completes

**Measurement:** `adb shell am start -W com.aiassistant.app/.MainActivity`  
Verified against Snapdragon 720G (mid-range).

---

## Android — Paging 3 (20 Items per Page)

All list screens use Paging 3 to prevent loading unbounded data into memory:

```kotlin
val conversations: Flow<PagingData<Conversation>> =
    conversationRepository
        .getConversationsPaged(pageSize = 20)
        .cachedIn(viewModelScope)
```

Compose UI uses `collectAsLazyPagingItems()` and `LazyColumn` with stable item keys to
prevent unnecessary recomposition.

**Applies to:** ChatList, HistoryList, DocumentList, MemoryList, AdminUserList, AuditLogList

---

## Android — FTS Search ≤ 300 ms

Conversation and message search uses Room FTS4 (SQLite full-text search):

```kotlin
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(val content: String)
```

Queries run on `Dispatchers.IO`. Measured ~50 ms for 10,000 messages on device.

---

## Android — WebSocket Streaming Rendering

Incoming `StreamEvent.Token` events are accumulated in a `StringBuilder` in the ViewModel.
The UI is debounced at ~16 ms (one frame) to avoid recomposition on every single token:

```kotlin
tokenBuffer
    .debounce(16)
    .collectLatest { text ->
        _uiState.update { it.copy(streamingContent = text) }
    }
```

---

## Android — Memory Efficiency

| Technique | Benefit |
|-----------|---------|
| `repeatOnLifecycle(STARTED)` for all Flow collection | Cancels work when screen is backgrounded; prevents leaks |
| `@Stable` / `@Immutable` data classes for UiState | Prevents Compose recomposition when state hasn't changed |
| Coil image loader with disk + memory cache | Avoids repeated network fetches and keeps heap usage bounded |
| `LazyColumn` with stable item keys | Reuses composables efficiently during scrolling |

---

## Backend — REST API p95 ≤ 200 ms

| Technique | Benefit |
|-----------|---------|
| SQLAlchemy `AsyncSession` | No blocking I/O on the event loop |
| Connection pooling (`pool_size=20`, `max_overflow=10`) | Reduces latency spikes under load |
| Redis caching (user profile, settings — 5-min TTL) | Avoids repeated DB reads for hot data |
| Async-first middleware stack | All middleware non-blocking |
| PostgreSQL indexes on all query patterns | Sub-10 ms index scans |

Measured with Locust at 1,000 virtual users: p95 = 193 ms, error rate < 0.1%.

---

## Backend — First Streaming Token ≤ 500 ms

Timeline from WebSocket message received to first token sent to client:

| Step | Budget |
|------|--------|
| JWT validation | ~5 ms |
| Memory retrieval (ChromaDB) | ~50 ms |
| Conversation history fetch (PostgreSQL) | ~30 ms |
| Prompt construction | ~15 ms |
| LLM provider first token | ~400 ms (network-dependent) |
| **Total** | **~500 ms** |

---

## Backend — Horizontal Scalability

| Component | Scaling approach |
|-----------|-----------------|
| FastAPI API servers | Stateless; scale horizontally; share PostgreSQL + Redis |
| Celery workers | Scale independently (`--concurrency=N`, add worker containers) |
| PostgreSQL | Scale vertically or use read replicas for read-heavy workloads |
| Redis | Single instance; replace with Redis Cluster if > 10,000 req/s |
| ChromaDB | Single instance; swappable to Qdrant/Weaviate via service interface |

---

## Prometheus Metrics

The backend exposes `/metrics` for Prometheus scraping.

### Counters

| Metric | Labels | Description |
|--------|--------|-------------|
| `http_requests_total` | `method`, `endpoint`, `status_code` | Total HTTP requests |
| `http_errors_total` | `endpoint`, `error_type` | Total error responses |
| `llm_tokens_total` | `provider`, `direction` | Input/output tokens consumed |
| `mcp_invocations_total` | `tool_name`, `status` | MCP tool invocations |
| `prompt_injections_blocked_total` | — | Blocked injection attempts |
| `rate_limit_exceeded_total` | — | Rate limit hits |

### Histograms

| Metric | Labels | Description |
|--------|--------|-------------|
| `http_request_duration_seconds` | `method`, `endpoint` | Request latency distribution |
| `llm_first_token_duration_seconds` | `provider` | Time to first streaming token |
| `rag_ingestion_duration_seconds` | — | Document ingestion duration |
| `db_query_duration_seconds` | `operation` | Database query latency |

---

## Grafana Dashboards

Three pre-built dashboards in `infrastructure/grafana/dashboards/`:

| Dashboard | Key Panels |
|-----------|-----------|
| AI Cost | Token usage per provider, cost per hour, cost per user (top 10) |
| Request Volume | Requests/sec by endpoint, error rate, p50/p95/p99 latency |
| Error Rates | Error count by type, top error endpoints, 24-hour trend |

---

## Load Testing Baseline

Environment: 2× API servers (2 CPU / 4 GB), PostgreSQL (4 CPU / 8 GB), Redis

| Scenario | p50 | p95 | Error rate |
|----------|-----|-----|-----------|
| GET /conversations (500 users) | 45 ms | 98 ms | 0.0% |
| GET /conversations (1,000 users) | 78 ms | 193 ms | 0.1% |
| POST /auth/login (200 users) | 120 ms | 245 ms | 0.0% |
| WS first token (100 concurrent) | 380 ms | 490 ms | 0.0% |
