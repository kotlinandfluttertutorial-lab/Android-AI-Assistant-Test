# UI Architecture

> **Last updated**: Phase 3–5 — Production UI/UX Upgrade

---

## Layer Diagram

```
┌─────────────────────────────────────────────────────────────┐
│  app module                                                 │
│  MainActivity → AppNavigationShell → rootNavHost           │
└─────────────────────────┬───────────────────────────────────┘
                          │
          ┌───────────────┼───────────────┐
          ▼               ▼               ▼
   feature-chat    feature-history  feature-settings  ...
   ChatDetailScreen HistoryListScreen SettingsScreen
   ChatListScreen   SearchHistoryScreen
          │               │
          └───────────────┘
                  │
                  ▼
           feature ViewModel
                  │
                  ▼
           domain UseCase
                  │
                  ▼
           domain Repository (interface)
                  │
          ┌───────┴────────┐
          ▼                ▼
      Room (local)    Retrofit (remote)
```

---

## Component Hierarchy

### core-ui Components

```
core-ui/components/
├── AiModeIndicator       — pill chip (Gemma/Cloud/RAG/OnDeviceRAG)
├── ChatBubble            — UserMessageBubble + AssistantMessageBubble
├── CodeBlock             — always-dark, monospace, copy button
├── ConnectivityStatusBar — offline/syncing/online banner
├── CacheStatusIndicator  — "Cached · Last synced: X" label
├── DataStatus            — @Immutable model (isFromCache, isSyncing, lastUpdated)
├── ErrorBanner           — full-width error with retry
├── InlineChatError       — inline chat list error with retry
├── LoadingIndicator      — CIRCULAR / DOTS / LINEAR variants
├── MarkdownText          — Markdown rendered text
├── MessageActionRow      — Copy/Share/Regenerate/ThumbsUp/ThumbsDown
├── MessageInputBar       — pill input, send/stop, voice, attach
├── ModelStatusCard       — Gemma model status with all states
├── OfflineBanner         — persistent offline banner (always visible)
├── RagComponents         — CitationCard, SourceChip, SourcesBottomSheet, DocumentReferenceCard
├── ShimmerSkeleton       — ShimmerBox, ConversationListSkeleton
├── StreamingMessage      — blinking cursor for in-progress responses
├── SurfaceFillTextField  — styled text input
├── SwipeRevealLayout     — swipe-to-reveal action container
├── SystemMessage         — divider-style system event label
└── TypingIndicator       — three-dot bounce (in motion/)
```

---

## State Management

### UI State Pattern

Every screen uses a `sealed class` UiState:

```kotlin
sealed interface ChatUiState {
    data object Loading : ChatUiState
    data class Success(val messages: List<MessageUiModel>, val aiMode: AiMode) : ChatUiState
    data class Error(val message: String) : ChatUiState
}
```

### ViewModel → UI Contract

```kotlin
// ViewModel exposes:
val uiState: StateFlow<ScreenUiState>

// Screen reads with lifecycle awareness:
val state by viewModel.uiState.collectAsStateWithLifecycle()
```

### Performance

- **@Immutable** on `MessageUiModel` — prevents unnecessary list recompositions.
- **@Stable** on `StreamingState` — isolates streaming recomposition to the cursor composable.
- **derivedStateOf** used for expensive derived values (e.g., message grouping).
- **stable keys** in all `LazyColumn` calls: `items(key = { it.id })`.

---

## Navigation

- Single-activity, NavHost-based.
- 15 feature NavGraphs registered flat in `rootNavHost`.
- Adaptive navigation chrome in `AppNavigationShell`:
  - **Compact** (phone): `NavigationBar` + optional `ModalNavigationDrawer`.
  - **Medium** (tablet portrait / large phone landscape): `NavigationRail`.
  - **Expanded** (tablet landscape): `PermanentNavigationDrawer`.

### Deep Links

| Pattern | Destination |
|---|---|
| `aiassistant://open/chat` | Chat list |
| `aiassistant://open/rag` | Document list |
| `aiassistant://open/voice` | Voice screen |
| `aiassistant://open/meeting` | Meeting screen |
| `aiassistant://open/productivity` | Productivity |

---

## UI Rules

1. No business logic in composables — ViewModels own all state.
2. No direct `Retrofit`, `Room`, or `Gemma` imports in UI files.
3. All navigation is delegated via callbacks, never direct `navController` refs inside composables below the screen level.
4. Use `collectAsStateWithLifecycle()`, not `collectAsState()`, to respect lifecycle.
