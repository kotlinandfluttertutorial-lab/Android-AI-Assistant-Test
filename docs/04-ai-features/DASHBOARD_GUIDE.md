# Phase 14 — Android AI DevOps Dashboard Guide

> **Learning goal:** Understand how to build a production-quality Android feature
> module from scratch — data layer through UI — and how Clean Architecture keeps
> each layer independently testable.
>
> **Career connection:** Android engineers who can wire a complete vertical slice
> (API → domain → ViewModel → Compose) end-to-end are the most valuable on any
> team. This phase demonstrates exactly that.

---

## 1. What Was Built

```
┌─────────────────────────────────┐
│  AI DevOps Dashboard            │
├─────────────────────────────────┤
│  🔴 Critical  2   🟠 High  5   │
│  🟡 Medium    1   🔵 Open  8   │
├─────────────────────────────────┤
│  AI Error Analysis    ▾         │
│  Summary: DB pool exhausted     │
│  Confidence: ████████░░  87%   │
│  Root cause: LLM calls hold…    │
│  Fix: Add asyncio.wait_for…     │
├─────────────────────────────────┤
│  Recent Incidents (8)           │
│  INC-xxx  DB timeout   HIGH●    │
│  INC-yyy  OOM error    MED●     │
├─────────────────────────────────┤
│  DevOps Assistant               │
│  [Why did the API fail?]   [→]  │
│  "At 14:32 the API returned…"   │
│  Sources: INC-001, runbook.md   │
└─────────────────────────────────┘
```

**Entry point:** Home Dashboard → "DevOps Dashboard" feature card.

---

## 2. Architecture — Full Vertical Slice

Phase 14 follows Clean Architecture strictly. Data only flows inward
(outer layers depend on inner, never the reverse):

```
┌───────────────────────────────────────────────────────────┐
│  feature-dashboard (UI layer)                             │
│                                                           │
│  DashboardScreen.kt      — Compose UI                     │
│  DashboardViewModel.kt   — @HiltViewModel, StateFlow      │
│  DashboardUiState.kt     — sealed class: Loading/Content  │
│  components/             — StatusBadge, IncidentListItem  │
│                            AiAnalysisCard, DevOpsChatCard │
│  DashboardNavigation.kt  — DashboardRoute, navGraph ext   │
└──────────────────────┬────────────────────────────────────┘
                       │ uses domain use cases
┌──────────────────────▼────────────────────────────────────┐
│  domain (business logic layer)                            │
│                                                           │
│  model/Incident.kt              — pure Kotlin data class  │
│  model/AiAnalysis.kt            — Phase 10 result shape   │
│  model/DevOpsChatResult.kt      — Phase 13 answer shape   │
│  repository/IncidentRepository  — interface               │
│  repository/DevOpsRepository    — interface               │
│  usecase/GetIncidentsUseCase    — single-responsibility   │
│  usecase/AskDevOpsAssistantUseCase                        │
│  usecase/AnalyseErrorsUseCase                             │
└──────────────────────┬────────────────────────────────────┘
                       │ implements interfaces
┌──────────────────────▼────────────────────────────────────┐
│  data (infrastructure layer)                              │
│                                                           │
│  remote/devops/IncidentApiService.kt   — Retrofit iface   │
│  remote/devops/DevOpsApiService.kt     — Retrofit iface   │
│  remote/devops/IncidentRemoteDataSource.kt                │
│  remote/devops/DevOpsRemoteDataSource.kt                  │
│  repository/IncidentRepositoryImpl.kt                     │
│  repository/DevOpsRepositoryImpl.kt                       │
│  di/DevOpsDataModule.kt               — Hilt bindings     │
└───────────────────────────────────────────────────────────┘
```

**Dependency rule:** `feature-dashboard` depends only on `:domain`. It has a
Gradle rule that throws an error if any `:data` class is imported directly.

---

## 3. Key Implementation Details

### DashboardViewModel — parallel loading

```kotlin
// Fetch incidents and AI analysis in parallel using coroutine async
val incidentsDeferred = async(dispatchers.io) { getIncidents(limit = 20) }
val analysisDeferred  = async(dispatchers.io) { analyseErrors(lookbackMinutes = 30) }

val incidentsResult = incidentsDeferred.await()
val analysisResult  = analysisDeferred.await()
```

`async` + `await` launches both network calls simultaneously. Without this,
they would run sequentially (~4s total vs ~2s in parallel). This is the
standard pattern for independent concurrent data fetching in Coroutines.

### DashboardUiState — explicit state modeling

```kotlin
sealed class DashboardUiState {
    data object Loading : DashboardUiState()
    data class  Content(
        val counts:       IncidentCounts,
        val incidents:    List<Incident>,
        val aiAnalysis:   AiAnalysis?,      // null = not yet loaded
        val isRefreshing: Boolean = false,  // separate from Loading
        val isOffline:    Boolean = false,
    ) : DashboardUiState()
    data class Error(val message: String, val isOffline: Boolean) : DashboardUiState()
}
```

`isRefreshing` is separate from `Loading` so a pull-to-refresh doesn't blank the
screen — the existing content stays visible while new data loads.

`aiAnalysis: AiAnalysis?` is nullable because: it's optional (errors for the last
30 min may not always be interesting), and it loads in parallel with incidents so
it might succeed after incidents fail (or vice versa).

### AiAnalysisCard — confidence gate on the Android side

```kotlin
val isLowConfidence = analysis.confidence < 0.6

// The backend already overrides likely_root_cause when confidence < 0.6
// The UI additionally:
// - Shows a red progress bar instead of blue
// - Renders the low_confidence_warning text
// - Tints the confidence label red
```

This mirrors the backend safety gate (Phase 10/12). Both layers enforce the
0.6 rule independently — the backend generates the right response, and the
Android UI makes it visually prominent when confidence is low.

### StatusBadge — custom warning colour

Material 3 has no built-in "warning" role. We added amber tokens to `Color.kt`:

```kotlin
// In core-ui/Color.kt — Phase 14 addition
internal val Warning40  = Color(0xFFB25B00)  // text on light surface
internal val Warning90  = Color(0xFFFFDDB3)  // container (amber tint)
internal val Warning20  = Color(0xFF5B2D00)  // text on amber container
```

Used directly in `StatusBadge.kt` and `AiAnalysisCard.kt` for MEDIUM severity
and INVESTIGATING status. This pattern keeps the amber usage consistent without
needing a full M3 custom colour role.

### DevOpsChatCard — quick question chips

```kotlin
private val QUICK_QUESTIONS = listOf(
    "Why did the API fail?",
    "Show open incidents",
    "How do I restart the service?",
    "Summarize today's errors",
)
```

These chips populate the input field and immediately submit when tapped. They
serve two purposes: discoverability (users see what the assistant can do) and
accessibility (one tap vs typing on mobile).

---

## 4. Data Flow for "Refresh"

```
User swings down → PullToRefreshBox.onRefresh fires
     │
     ▼
DashboardViewModel.refresh()
     │
     ├── Current state is Content? → set isRefreshing = true
     │   (keeps existing data visible)
     │
     ▼
async {
  getIncidents(limit=20)     ← GET /api/v1/incidents
  analyseErrors(minutes=30)  ← POST /api/v1/analysis/errors
}
     │
     ▼
DashboardUiState.Content(
  counts       = computeCounts(incidents),
  incidents    = incidents,
  aiAnalysis   = analysis,   // null if analysis call failed
  isRefreshing = false,
  isOffline    = isOffline.value,
)
     │
     ▼
DashboardScreen collects StateFlow → recomposes
```

---

## 5. Data Flow for "Ask a DevOps Question"

```
User types question + taps Send
     │
     ▼
DashboardViewModel.askQuestion("Why did the API fail?")
     │
     ▼
ChatUiState.Loading → input shows spinner
     │
     ▼
AskDevOpsAssistantUseCase("Why did the API fail?")
     │
     ▼
DevOpsRepositoryImpl.chat(question)
     │
     ▼
DevOpsRemoteDataSource.chat()
     │
     ▼
POST /api/v1/devops/chat          ← Phase 13 backend
     │
     ▼ (ReAct loop on server: search_logs → search_incidents → answer)
     │
     ▼
DevOpsChatResponse → mapped to DevOpsChatResult domain model
     │
     ▼
ChatUiState.Success(result)
     │
     ▼
DevOpsChatCard shows answer + citations + tools used
```

---

## 6. Accessibility Considerations

Every interactive element has a `contentDescription`:

```kotlin
// Count chips announce their value
.semantics { contentDescription = "$count $label incidents" }

// AI analysis card announces expand/collapse
.semantics { contentDescription = "AI Analysis, tap to ${if (expanded) "collapse" else "expand"}" }

// Confidence bar announces the percentage
.semantics { contentDescription = "Confidence $confidencePct percent" }

// Refresh button
.semantics { contentDescription = "Refresh dashboard" }

// Chat input
.semantics { contentDescription = "Ask a DevOps question" }
```

Color is never the only distinguishing signal — severity is shown with both
colour AND a text label ("CRITICAL", "HIGH", etc.), following WCAG 1.4.1.

---

## 7. How to Navigate to the Dashboard

From anywhere in the app:
```kotlin
navController.navigate(DashboardRoute.SCREEN)
```

Or tap the "DevOps Dashboard" card on the Home screen (added to `featureCards`
list in `HomeDashboard.kt`).

---

## 8. Running the Backend for Dashboard Data

The dashboard calls three backend endpoints:

```bash
# 1. Get recent incidents
GET /api/v1/incidents?limit=20

# 2. AI error analysis (last 30 min)
POST /api/v1/analysis/errors
{"lookback_minutes": 30}

# 3. DevOps assistant chat
POST /api/v1/devops/chat
{"question": "Why did the API fail?"}
```

Start the backend locally:
```bash
docker-compose up -d
```

Or point to the Cloud Run URL in your build variant (`cloud` flavour in `app/build.gradle.kts`).

---

## 9. Interview Questions

**Q1: What is Clean Architecture and how is it applied here?**

Clean Architecture organises code into concentric layers where inner layers (domain,
models) have no knowledge of outer layers (data, UI). Dependencies point inward only.

In Phase 14:
- `domain/model/Incident.kt` is a plain Kotlin `data class` with zero Android imports
- `domain/repository/IncidentRepository.kt` is an interface — no Retrofit, no Room
- `feature-dashboard` depends on `:domain` but never on `:data`
- `data/repository/IncidentRepositoryImpl.kt` implements the interface using Retrofit

This means: you can swap Retrofit for GraphQL without touching the UI. You can
test `DashboardViewModel` with a fake `IncidentRepository` without a network.
You can move from REST to gRPC without any feature module changes.

---

**Q2: Why are the use cases so small? Is `GetIncidentsUseCase` even necessary?**

Use cases are small by design — each does one thing. `GetIncidentsUseCase` is
a thin wrapper around `IncidentRepository.getIncidents()` but it provides:

1. **A stable injection target** — ViewModels inject the use case, not the
   repository. If the business rule changes (e.g. "filter out dismissed incidents
   before returning"), you change the use case, not the ViewModel or the repository.

2. **Testability** — you can mock `GetIncidentsUseCase` in a ViewModel unit test
   without instantiating a repository or a data source.

3. **Composability** — use cases can call each other. A future `GetDashboardDataUseCase`
   could call `GetIncidentsUseCase`, `AnalyseErrorsUseCase`, and `GetMetricsUseCase`
   internally, giving the ViewModel a single call point.

Small use cases scale; combined use cases don't.

---

**Q3: What is `sealed class` and why use it for `DashboardUiState`?**

A `sealed class` restricts which classes can extend it to those defined in the
same file. The `when` expression on a sealed class is exhaustive — the compiler
forces you to handle every case.

```kotlin
when (state) {
    is DashboardUiState.Loading -> { /* spinner */ }
    is DashboardUiState.Content -> { /* data */ }
    is DashboardUiState.Error   -> { /* error */ }
    // No `else` needed — sealed class guarantees these are the only 3 cases
}
```

Without `sealed`, if you added a new state (e.g. `Empty`) and forgot to handle it
in the UI, it would silently fall through. With `sealed`, it's a compile error.
This eliminates an entire category of runtime bugs.

---

**Q4: What is the difference between `StateFlow` and `LiveData`?**

Both hold observable state. Key differences:

| Dimension | StateFlow | LiveData |
|-----------|-----------|----------|
| Origin | Kotlin Coroutines | AndroidX lifecycle |
| Lifecycle awareness | Manual (use `collectAsStateWithLifecycle`) | Built-in |
| Initial value | Required | Optional |
| Null safety | Non-null by type system | Nullable |
| Cold/hot | Hot (always has a value) | Hot |
| Testing | Works in pure JVM tests | Requires Android runner |

In Compose, `StateFlow` is preferred because it pairs naturally with coroutines
and works in pure JVM unit tests without Android instrumentation. `LiveData` is
legacy and being phased out on new projects.

---

**Q5: Why does `async` + `await` outperform sequential coroutine calls?**

```kotlin
// Sequential — total time = time_A + time_B
val result1 = withContext(IO) { callA() }
val result2 = withContext(IO) { callB() }

// Parallel — total time = max(time_A, time_B)
val deferred1 = async(IO) { callA() }
val deferred2 = async(IO) { callB() }
val result1 = deferred1.await()
val result2 = deferred2.await()
```

`getIncidents()` takes ~200ms and `analyseErrors()` takes ~2000ms (LLM call).
Sequentially: ~2200ms. In parallel: ~2000ms — an 8% improvement for this case,
but on slow networks the win is larger.

`async` starts a coroutine immediately; `await` suspends until it completes.
Both coroutines run concurrently on the IO dispatcher thread pool.

---

**Q6: How would you add an "Incident Detail" screen to this module?**

Following the exact same pattern already established:

1. **Route** — add to `DashboardNavigation.kt`:
   ```kotlin
   const val INCIDENT_DETAIL = "devops/incident/{incidentId}"
   fun incidentDetail(id: String) = "devops/incident/$id"
   ```

2. **NavGraph** — add a `composable` entry in `dashboardNavGraph()`:
   ```kotlin
   composable(
       route = DashboardRoute.INCIDENT_DETAIL,
       arguments = listOf(navArgument("incidentId") { type = NavType.StringType })
   ) {
       IncidentDetailScreen(onNavigateUp = { navController.popBackStack() })
   }
   ```

3. **ViewModel** — `IncidentDetailViewModel(savedStateHandle)` reads `incidentId`
   from `SavedStateHandle`, calls `GetIncidentUseCase(id)`, exposes a `StateFlow`.

4. **Screen** — `IncidentDetailScreen` collects the StateFlow and renders the
   full incident with Phase 10 error analysis and Phase 12 RCA results.

5. **Navigate** — in `DashboardScreen`:
   ```kotlin
   onIncidentClick = { id -> navController.navigate(DashboardRoute.incidentDetail(id)) }
   ```

No changes needed to `domain/`, `data/`, or any other module.

---

## 10. Exercise

1. **Run the dashboard** — start the backend, open the Android app, navigate to
   "DevOps Dashboard". If the backend has incidents, they should appear within 2s.

2. **Test pull-to-refresh** — verify the spinner appears and data reloads without
   blanking the screen (because `isRefreshing = true` keeps the content visible).

3. **Ask a quick question** — tap one of the pre-set chips ("Show open incidents").
   Verify the answer appears with citations and the tools used are listed.

4. **Test low-confidence display** — using the backend `curl` commands from the
   Phase 10 guide, insert a single ambiguous event. Trigger analysis. The confidence
   bar should be red and the warning text should appear.

5. **Add an offline state** — turn on Airplane Mode. Refresh the dashboard. Verify
   the offline banner appears and the error message says "No network connection."

6. **Write a ViewModel unit test** — mock `GetIncidentsUseCase` to return a list of
   5 incidents. Call `viewModel.refresh()`. Assert `uiState.value` transitions from
   `Loading` → `Content` with `counts.open = 5`.

---

## Phase 14 Summary

**What was built:**

```
core-ui/Color.kt
  Warning amber tokens (Warning40/80/90/20/WarningDark)

data layer
  IncidentApiService + DevOpsApiService (Retrofit)
  IncidentRemoteDataSource + DevOpsRemoteDataSource
  IncidentRepositoryImpl + DevOpsRepositoryImpl
  DevOpsDataModule (Hilt)

domain layer
  Incident + IncidentSeverity + IncidentStatus
  AiAnalysis, DevOpsChatResult domain models
  IncidentRepository + DevOpsRepository interfaces
  GetIncidentsUseCase + AskDevOpsAssistantUseCase + AnalyseErrorsUseCase

feature-dashboard module
  build.gradle.kts + AndroidManifest.xml
  Registered in settings.gradle.kts + app/build.gradle.kts

  DashboardUiState (Loading/Content/Error + ChatUiState)
  DashboardViewModel (parallel load, refresh, askQuestion)
  DashboardScreen (PullToRefreshBox, incident counts, AI card, chat)
  components/
    StatusBadge + SeverityBadge
    IncidentListItem
    AiAnalysisCard (expandable, confidence bar, facts/fix)
    DevOpsChatCard (quick chips, input, answer, citations)
  DashboardNavigation + DashboardRoute

app/HomeDashboard.kt
  "DevOps Dashboard" feature card added to home grid
app/MainActivity.kt
  dashboardNavGraph registered in rootNavHost
```

**Connection to Phase 15 (AIOps):**
The dashboard becomes the notification target — when the anomaly detector (Phase 11)
creates an incident, a push notification routes the developer here. The human reviews
the AI analysis, approves or rejects the recommended fix, and the AIOps loop closes.

Say `NEXT` to continue to **Phase 15 — AIOps**.
