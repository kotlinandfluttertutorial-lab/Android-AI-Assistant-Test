# On-Device Offline Navigation — Screen by Screen Flow

**Android AI Assistant (Enterprise Edition)**

> **Last updated**: September 2026
>
> This document describes the complete navigation flow for the on-device AI feature when the
> device is offline. Every screen, state, and transition described here runs entirely on the
> Android device — no network connection is required to navigate, ingest documents, or run
> inference once the GGUF model is downloaded.

---

## Table of Contents

1. [Overview](#overview)
2. [Navigation Architecture](#navigation-architecture)
3. [Startup — Hardware Capability Check](#1-startup--hardware-capability-check)
4. [Home Dashboard](#2-home-dashboard)
5. [On-Device Documents Screen](#3-on-device-documents-screen)
6. [On-Device RAG Chat Screen](#4-on-device-rag-chat-screen)
7. [Manage Models Screen](#5-manage-models-screen)
8. [Benchmark Screen](#6-benchmark-screen)
9. [Full Navigation Graph](#full-navigation-graph)
10. [Offline Decision Matrix](#offline-decision-matrix)
11. [Key Files Reference](#key-files-reference)

---

## Overview

The on-device AI path is designed so that once the GGUF model is downloaded and verified,
**the device can be fully air-gapped** and all features continue to work. Navigation is
handled by Jetpack Compose Navigation with all routes resolved locally. No API call is
issued at any navigation event.

**What works fully offline:**
- All navigation transitions
- Local document list (SQLite-backed)
- Document ingestion (parsing, chunking, embedding)
- Vector similarity search against local index
- Token-by-token inference via the on-device GGUF engine
- Inference benchmarks

**What requires connectivity:**
- Initial model download (one-time)
- Cloud AI fallback (only triggered on explicit user action)
- Auth / profile syncing

---

## Navigation Architecture

The app uses an **adaptive navigation shell** (`AppNavigationShell`) that selects the
appropriate chrome based on window size — no business logic lives inside the shell.

The root `NavHost` is hosted in `MainActivity` (single-activity architecture) and starts
on `AuthRoute.GRAPH`. After successful authentication, the back stack is cleared and
navigation transfers to `HOME_ROUTE`. All 15 feature graphs are then navigable from
that root without re-authentication.

```
┌─────────────────────────────────────────────────────────────┐
│                   AppNavigationShell                        │
│                                                             │
│  Compact (phone)   → NavigationBar (bottom)                 │
│  Medium (tablet P) → NavigationRail (start)                 │
│  Expanded (tablet) → PermanentNavigationDrawer (start)      │
│                                                             │
│  All route resolution: NavHostController (local, in-process)│
└─────────────────────────────────────────────────────────────┘
```

Navigation between destinations uses `saveState = true` / `restoreState = true` so the
back stack is preserved across tab switches without any network round-trip.

```kotlin
navController.navigate(route) {
    popUpTo(HOME_ROUTE) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
```

---

## 1. Startup — Hardware Capability Check

**Classes:** `OnDeviceCapabilityChecker` · `DeviceCapabilityDetector` · `OnDeviceModelManager`  
**Module:** `core-on-device-ai`

Before the first screen renders, `OnDeviceCapabilityChecker.evaluate()` runs on the IO
dispatcher. It gates whether the on-device AI option is surfaced to the user at all.

### Check sequence

```
App starts
    │
    ▼
DeviceCapabilityDetector.isOnDeviceInferenceSupported()
    │
    ├─ hasNpuOrDedicatedGpu()
    │      Queries EGL renderer/vendor string for keywords:
    │      Adreno · Mali · Hexagon · NeuroPilot · MLPE · Turing · PowerVR
    │
    └─ hasEnoughAvailableMemory()
           ActivityManager.MemoryInfo.availMem ≥ 4 GB
```

### Resulting capability states

| State | Condition | UI behaviour |
|---|---|---|
| `NotSupported` | GPU/NPU not detected **or** < 4 GB available RAM | On-device AI option hidden entirely |
| `SupportedButModelNotReady` | Hardware OK, but model absent / corrupt / downloading | Download prompt shown; cloud provider used as fallback |
| `SupportedAndReady` | Hardware OK + GGUF file present + SHA-256 verified | On-device AI available for selection |

> **Design note:** `availMem` is used rather than `totalMem`. A device may advertise 8 GB
> total but have < 4 GB free due to system and app usage. The check is intentionally
> conservative — if EGL initialisation fails for any reason, the result is `false`, not
> an exception.

---

## 2. Home Dashboard

**Route:** `HOME_ROUTE`  
**File:** `app/src/main/kotlin/com/aiassistant/HomeDashboard.kt`  
**Navigation shell:** `AppNavigation.kt`

The dashboard is the root of the back stack. The adaptive navigation chrome renders
immediately from local state — no network call.

### Bottom navigation items (compact / phone)

| Tab | Route | Offline? |
|---|---|---|
| Chat | `chat/list` | ⚠️ Cloud-dependent; shows error state offline |
| History | `history/` | ✅ Reads local DB |
| Docs | `ondevicerag/documents` | ✅ Fully offline |
| Voice | `voice/` | ⚠️ Depends on connectivity |
| Settings | `settings/screen` | ✅ Fully offline |

### Drawer sections (expanded / tablet)

The `PermanentNavigationDrawer` groups items into three sections:

```
── (no header) ──────────────────────────
  New Chat · Conversations · History · Documents · Favorites

── AI ───────────────────────────────────
  On-device Gemma   →  VoiceRoute.GRAPH
  Cloud AI          →  ChatRoute.LIST
  RAG Documents     →  RAGRoute.DOCUMENT_LIST

── Settings ─────────────────────────────
  Settings · Profile · Notes
```

The "On-device Gemma" drawer item is shown based on `OnDeviceCapabilityState` — it is
hidden when the state is `NotSupported`.

---

## 3. On-Device Documents Screen

**Route:** `ondevicerag/documents`  
**Deep link:** `aiassistant://open/ondevicerag/documents`  
**File:** `feature-on-device-rag/…/OnDeviceDocumentsScreen.kt`  
**ViewModel:** `OnDeviceDocumentViewModel`

This is the **entry point** for all offline RAG work. Documents are stored in a local
SQLite database and never leave the device.

### UI states

```
OnDeviceDocumentUiState
    │
    ├── Loading              → CircularProgressIndicator (centred)
    ├── DocumentList         → Scrollable list  +  optional warning banners
    ├── IngestionRunning     → Progress banner above list
    ├── FileSizeRejection    → Snackbar "File is too large. Max 50 MB."
    └── Error                → Red error message centred on screen
```

### Document list item anatomy

```
┌──────────────────────────────────────────────────────┐
│  invoice-q3.pdf                              [Delete] │
│  Ready · 142 chunks                                   │
└──────────────────────────────────────────────────────┘
```

Status badges and their meanings:

| Badge | Colour | Meaning |
|---|---|---|
| Ready | Primary | Chunked, embedded, and indexed. Tappable → navigates to RAG Chat |
| Processing | Tertiary | Ingestion pipeline running |
| Pending | Outline | Queued, waiting for processing slot |
| Failed (stage) | Error | Ingestion failed at the named stage (parse / chunk / embed) |

Only **Ready** documents are tappable. Tapping navigates to Screen 4.

### Ingestion progress banner (shown during `IngestionRunning`)

```
Parsing report.pdf…                         [indeterminate bar]
Splitting report.pdf into chunks…           [indeterminate bar]
Generating embeddings 48/200…              [determinate bar  ████░░░░]
```

### Warning banners

**Low storage warning** — shown when internal storage is below threshold:
> ⚠️ Storage is almost full. Free up space to continue ingesting documents.

**File size rejection snackbar** — shown when a file > 50 MB is picked:
> "report-2024.pdf" is too large. Maximum file size is 50 MB.

### Navigation from this screen

| Action | Destination |
|---|---|
| Tap a Ready document | `ondevicerag/documents/{documentId}/chat` |
| Tap + FAB | System file picker (PDF · TXT · Markdown) |

---

## 4. On-Device RAG Chat Screen

**Route:** `ondevicerag/documents/{documentId}/chat`  
**Deep link:** `aiassistant://open/ondevicerag/documents/{documentId}/chat`  
**File:** `feature-on-device-rag/…/OnDeviceRagChatScreen.kt`  
**ViewModel:** `OnDeviceRagViewModel`

This is the **core offline inference screen**. The full pipeline — vector search, context
assembly, and token generation — runs on-device.

### Inference pipeline (state machine)

```
User submits query
        │
        ▼
   ┌─────────┐
   │ Routing │  "Checking capabilities…"
   └────┬────┘
        │  capability confirmed
        ▼
  ┌──────────┐
  │ Searching│  "Searching local documents…"
  └─────┬────┘   (cosine similarity against local vector index)
        │  chunks retrieved
        ▼
  ┌──────────┐
  │ Streaming│  tokens arrive from on-device GGUF engine
  └─────┬────┘  TopAppBar badge: "Running on device" (green)
        │  generation complete
        ▼
   ┌──────┐
   │ Done │  full response + expandable citations
   └──────┘
```

### All UI states

| State | Screen content | Input enabled? |
|---|---|---|
| `Idle` | "Type a question below to search your on-device documents." | ✅ |
| `Routing` | Spinner + "Checking capabilities…" | ❌ |
| `Searching` | Spinner + "Searching local documents…" | ❌ |
| `Streaming` | Accumulating text + spinner bottom-right | ❌ |
| `Done` | Full response + "Show sources (N)" button | ✅ |
| `NoRelevantContent` | "No relevant content found in local documents." | ✅ |
| `Error` | Red message + optional "Retry via cloud" + "New query". Includes a `stage` field indicating where the pipeline failed: `embedding` · `search` · `generation` · `router` | ✅ |

### TopAppBar inference path badge

The badge is visible in `Searching`, `Streaming`, and `Done` states:

| Badge text | Background colour | Meaning |
|---|---|---|
| Running on device | `primaryContainer` | Inference is fully offline |
| Using cloud AI | `secondaryContainer` | Fallback to cloud (requires connectivity) |

### Cloud fallback banner

When inference falls back from on-device to cloud, an animated teal banner slides in
at the top of the content area:

> On-device inference unavailable — using cloud AI.

**When offline:** the fallback itself fails → the screen transitions to `Error` state
with `canRetry = true`. The user can tap "Retry via cloud" (will also fail without
connectivity) or "New query" to reset.

### Citations section (expandable, `Done` state only)

Tapping "Show sources (N)" expands a list of citation cards:

```
┌──────────────────────────────────────────────────────────┐
│  invoice-q3.pdf · page 4 · chunk 12                      │
│  Similarity: 0.87                                         │
│                                                           │
│  "The total amount due is $14,250, payable by October…"  │
└──────────────────────────────────────────────────────────┘
```

Each citation shows: document name · page number (if available) · chunk index ·
cosine similarity score · excerpt text.

### Navigation from this screen

| Action | Destination |
|---|---|
| Back arrow / system back | `ondevicerag/documents` (popBackStack) |

---

## 5. Manage Models Screen

**Route:** `ondevicerag/models`  
**Deep link:** `aiassistant://open/ondevicerag/models`  
**File:** `feature-on-device-rag/…/ManageModelsScreen.kt`  
**ViewModel:** `ManageModelsViewModel`

Accessible from Settings. Lists all GGUF model entries from the manifest, showing
download status, disk usage, and last-used date.

### Model card anatomy

```
┌──────────────────────────────────────────────────────────┐
│  Gemma 3 1B INT4                        [Download] [🗑️]  │
│  v1.2.0 · 1.8 GB                                         │
│  Last used: Sep 20, 2026                                  │
│                                                           │
│  42% · 756 MB / 1.8 GB                                    │
│  ████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░                    │
└──────────────────────────────────────────────────────────┘
```

### Download states

| `DownloadState` | Label shown | Progress bar? |
|---|---|---|
| `Downloading` | `N% · X MB / Y MB` | ✅ determinate `LinearProgressIndicator` |
| `Verifying` | `Verifying checksum…` | ❌ |
| `Error` | `Download failed: [reason]` (error colour) | ❌ |

### SHA-256 verification gate

Every model file is verified with a SHA-256 checksum before inference is allowed:

```
File present on disk
        │
        ▼
  Compute SHA-256
        │
   ┌────┴────┐
 match?      no match
   │              │
   ▼              ▼
 Ready        Delete file → Corrupt state → re-download required
```

If the device goes offline **during** a download, the temp file is deleted and
`DownloadState.Error` is shown. The completed file is never exposed to inference
until verification passes.

### Status banners

**Battery Saver active:**
> Battery saver active — on-device AI uses CPU only.

**Model update available:**
> Update available for Gemma 3 1B INT4. Download to use the latest version.

The update notice is in-app only (no push notification). Both banners animate in/out
with `AnimatedVisibility`.

### Empty state

When no models have been downloaded yet:
> No models downloaded yet.

### Navigation from this screen

| Action | Destination |
|---|---|
| Back arrow / system back | Previous screen (popBackStack) |

---

## 6. Benchmark Screen

**Route:** `ondevicerag/benchmark`  
**Deep link:** `aiassistant://open/ondevicerag/benchmark`  
**File:** `feature-on-device-rag/…/BenchmarkScreen.kt`  
**ViewModel:** `BenchmarkViewModel`

Accessible from Settings. Runs 10 inference iterations with a 200-token fixed prompt
against the on-device GGUF engine. Works fully offline — no network dependency.
Results are displayed locally and are never sent to any backend.

### UI states

| State | Screen content |
|---|---|
| `Idle` | Description text + "Run Benchmark" button |
| `Running` | Spinner + "Running N / 10 iterations…" |
| `Done` | Results table + "Run Again" button |
| `Error` | Red error message + "Retry" button |

### Results table (Done state)

| Metric | p50 | p95 |
|---|---|---|
| TTFT (ms) | — | — |
| Tokens/sec | — | — |

Additional rows: **Accelerator** (GPU/CPU/NPU name) and **Peak RAM (MB)**.

### Navigation from this screen

| Action | Destination |
|---|---|
| Back arrow / system back | Previous screen (popBackStack) |

---

## Full Navigation Graph

```
AppNavigationShell (adaptive chrome — no network required)
│
├── chat/list                           Cloud chat (offline → error state)
│
├── history/                            History (local DB — offline ✅)
│
├── ondevicerag/documents               Screen 3 — local document list
│       └── ondevicerag/documents/
│               {documentId}/chat       Screen 4 — on-device RAG chat
│
├── voice/                              Voice (connectivity-dependent)
│
└── settings/screen
        ├── ondevicerag/models          Screen 5 — model management
        └── ondevicerag/benchmark       Screen 6 — local benchmark
```

---

## Offline Decision Matrix

| Scenario | What happens |
|---|---|
| Device offline, model downloaded + verified | Full inference available — no change in behaviour |
| Device offline, model not downloaded | Hardware check passes, `SupportedButModelNotReady`. Download button shown but fails — user informed via `DownloadState.Error` |
| Device offline, model file corrupt (checksum mismatch) | File deleted automatically, `VerificationFailed` state, re-download required |
| Device offline, user taps "Retry via cloud" | Cloud call fails, `Error` state shown again with same retry option |
| Battery Saver active | Inference continues on CPU only; banner shown in Manage Models |
| < 4 GB available RAM at startup | `NotSupported` — on-device AI option hidden entirely |
| GPU/NPU not detected via EGL | `NotSupported` — on-device AI option hidden entirely |
| Storage below threshold during ingestion | `LowStorageWarning` banner shown; in-progress ingestion paused |

---

## Key Files Reference

| File | Module | Purpose |
|---|---|---|
| `AppNavigation.kt` | `app` | Adaptive navigation shell (Bar / Rail / Drawer) |
| `OnDeviceCapabilityChecker.kt` | `core-on-device-ai` | Orchestrates hardware + model status at startup |
| `DeviceCapabilityDetector.kt` | `core-on-device-ai` | EGL GPU/NPU check + available memory check |
| `OnDeviceModelManager.kt` | `core-on-device-ai` | Model download, SHA-256 verification, lifecycle |
| `OnDeviceCapabilityState.kt` | `core-on-device-ai` | Sealed class for startup capability result |
| `OnDeviceEngine.kt` | `core-on-device-ai` | Interface for the GGUF inference engine (JNI bridge) |
| `OnDeviceRagNavigation.kt` | `feature-on-device-rag` | Route constants + `NavGraphBuilder` extension |
| `OnDeviceDocumentsScreen.kt` | `feature-on-device-rag` | Screen 3 — local document list |
| `OnDeviceRagChatScreen.kt` | `feature-on-device-rag` | Screen 4 — on-device RAG chat |
| `ManageModelsScreen.kt` | `feature-on-device-rag` | Screen 5 — model file management |
| `BenchmarkScreen.kt` | `feature-on-device-rag` | Screen 6 — local inference benchmark |

### Related documentation

- [`docs/on-device-rag.md`](../on-device-rag.md) — six-layer architecture and privacy model
- [`docs/ui/GEMMA_UI.md`](GEMMA_UI.md) — Gemma model state machine and UI components
- [`docs/ui/RAG_UI.md`](RAG_UI.md) — RAG UI components and design patterns
- [`docs/architecture/AI_ARCHITECTURE.md`](../architecture/AI_ARCHITECTURE.md) — full AI system architecture
