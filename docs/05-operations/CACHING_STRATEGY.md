# Caching Strategy

> **Last updated**: Phase 7 — Production UI/UX Upgrade

---

## Overview

The app follows a **cache-first** strategy. Room is the single source of truth for all displayed data. Network calls update Room; composables observe Room via Flow.

---

## Flow Diagram

```
User opens screen
        │
        ▼
  ViewModel collects
  Repository Flow
        │
        ├──────────────────────────────────┐
        ▼                                  ▼
  Room emits                         Network fetch
  cached data                        (if online)
  immediately                             │
        │                                 ▼
        ▼                           Update Room
  UI shows                                │
  cached data                             ▼
  (isFromCache=true)               Room emits fresh
                                   data (isFromCache=false)
```

---

## DataStatus Model

```kotlin
@Immutable
data class DataStatus(
    val isFromCache: Boolean = false,   // data came from Room before network sync
    val isSyncing: Boolean = false,     // network fetch in-flight
    val lastUpdated: Instant? = null    // timestamp of last successful sync
)
```

### UiState Integration

```kotlin
data class ChatListUiState(
    val conversations: GroupedConversations = ...,
    val dataStatus: DataStatus = DataStatus.Live,
    val isOffline: Boolean = false
)
```

---

## UI Representation

| State | Component | Behavior |
|---|---|---|
| `isFromCache=true` | `CacheStatusIndicator` | "Cached · Last synced: Today 3:42 PM" |
| `isSyncing=true` | `CacheStatusIndicator` (spinning icon) | "Syncing…" |
| `isOffline=true` | `ConnectivityStatusBar` | Amber "You're offline" bar |
| Back online | `ConnectivityStatusBar` | Green "Back online" → auto-dismiss 3s |

---

## Repository Implementation

`ConversationRepositoryImpl` (data module) follows the pattern:

```kotlin
fun getConversations(): Flow<List<Conversation>> = flow {
    // 1. Emit cached data immediately
    emit(localDataSource.getAllConversations())

    // 2. Refresh from network (if online)
    if (connectivityObserver.isConnected()) {
        val fresh = remoteDataSource.getConversations()
        localDataSource.upsertAll(fresh)
        emit(localDataSource.getAllConversations())
    }
}
```

---

## What Gets Cached

| Data | Cache | TTL |
|---|---|---|
| Conversation list | Room `ConversationEntity` | Until explicit delete |
| Messages | Room `MessageEntity` | Until conversation deleted |
| Documents | Room `DocumentEntity` | Until document deleted |
| User profile | Room `UserEntity` | Session |

## What Does NOT Get Cached

- Live AI streaming responses (ephemeral)
- Cost dashboard data (always fresh)
- Notification payloads

---

## Offline Fallback Decision Tree

```
User wants to send message
        │
        ├── Online? → Send via Cloud AI or RAG
        │
        └── Offline?
               │
               ├── Gemma installed & Ready?
               │        └── Yes → Use on-device Gemma
               │
               └── No → Disable input bar
                        Show "Switch to Gemma to continue offline"
```
