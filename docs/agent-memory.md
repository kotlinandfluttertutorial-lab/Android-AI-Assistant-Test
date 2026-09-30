# Agent Memory

## Memory Scope Hierarchy

The agent memory system (Phase 8) uses three scopes with distinct lifetimes
and privacy guarantees:

```
┌─────────────────────────────────────────────────────────┐
│  LongTermMemory   (user-approved, persists across sessions) │
│    └─ LongTermMemoryEntry(approvedByUser=true required)  │
├─────────────────────────────────────────────────────────┤
│  ConversationMemory (session-scoped, cleared on end)    │
│    └─ recentMessages: List<ContextMessage>              │
│    └─ retrievedMemories: List<MemoryEntry>              │
├─────────────────────────────────────────────────────────┤
│  ShortTermMemory  (request-scoped, ephemeral)           │
│    └─ inputPrompt, currentTask, agentResults            │
│    └─ tokenCount, maxTokens                             │
└─────────────────────────────────────────────────────────┘
```

## Key Types

### MemoryEntry
```kotlin
data class MemoryEntry(
    val content: String,
    val relevanceScore: Float,  // 0.0–1.0 cosine similarity
    val source: String = "",    // e.g. "user_statement", "task_result"
)
```

### MemorySnapshot (aggregate)
```kotlin
data class MemorySnapshot(
    val shortTerm: ShortTermMemory,
    val conversation: ConversationMemory? = null,
    val longTerm: LongTermMemory? = null,
    val maxContextTokens: Int = 0,   // 0 = unlimited
)
```

`MemorySnapshot.toContextMemories()` assembles all entries ranked by
`relevanceScore` descending, trimmed to the token budget (4 chars ≈ 1 token).

## Context Assembly

`AgentContextAssembler.assemble(request, snapshot)` produces an `AgentContext`
from a `MemorySnapshot` before calling `Agent.execute()`.

Agents receive a fully assembled context — they NEVER query memory stores
directly. This keeps agents stateless and testable.

**Privacy mode:** When `AgentContext.isPrivacyMode = true`, long-term
memories are excluded from the assembled context:

```kotlin
val effectiveSnapshot = if (privacyMode) snapshot.copy(longTerm = null) else snapshot
```

## Long-Term Memory Safety

Every `LongTermMemoryEntry` requires `approvedByUser = true` at construction:

```kotlin
LongTermMemoryEntry(entry = MemoryEntry("I prefer dark mode"), approvedByUser = true)
// LongTermMemoryEntry(entry = ..., approvedByUser = false)  ← throws IllegalArgumentException
```

`LongTermMemory.withEntry(entry)` also re-validates `approvedByUser = true`.

**Sensitive information MUST NOT be stored automatically.** Callers must:
1. Screen content for PII before creating a `LongTermMemoryEntry`.
2. Only create entries after explicit user approval (e.g. a "Remember this" action).
3. Never auto-store conversation content from `isPrivacyMode = true` sessions.

## SafetyLimits for Memory

`SafetyLimits.maxContextTokens` controls how many tokens of memory are
injected into the context window:

```kotlin
val limits = SafetyLimits(maxContextTokens = 4_096)
val context = AgentContextAssembler.assemble(request, snapshot, limits)
```

When the budget is exceeded, the lowest-relevance entries are dropped first.

## Conversation History

`ConversationMemory.withMessage(message)` maintains a sliding window:

```kotlin
val cm = ConversationMemory(conversationId = "c1", maxMessages = 20)
cm.withMessage(ContextMessage("user", "Hello"))
// Oldest messages are automatically dropped when maxMessages is exceeded
```

## Database Persistence

Memory retrieval (semantic search over stored memories) is handled by the
`:data` layer via `MemoryRepository`. The domain layer only defines the
`ContextMemory` / `MemoryEntry` value objects — it has no I/O dependencies.
