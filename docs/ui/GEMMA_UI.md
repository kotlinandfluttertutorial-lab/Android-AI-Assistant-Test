# Gemma UI Guide

> **Last updated**: Phase 6 — Production UI/UX Upgrade

---

## Model State Machine

```
NotDownloaded ──► Downloading ──► Installing ──► Loading ──► Ready
       │                │                                      │
       │           (cancel)                               (delete)
       │                ▼                                      ▼
       └─────────► Error ◄──────────────────────────── NotDownloaded
                                            
       Hardware check                                Outdated
            ▼                                           │
       Unavailable                               (update) ▼
                                                  Downloading
```

---

## Component Reference

### `ModelStatusCard`

Full card showing model state. Use on the Model Settings screen.

```kotlin
ModelStatusCard(
    modelDisplayName = "Gemma 3 (1B INT4)",
    status = ModelStatus.Ready,
    storageSizeLabel = "1.8 GB",
    onManage = { /* navigate to manage */ },
    onDelete = { /* confirm + delete */ }
)
```

### `ModelDownloadProgress`

Inline progress bar within `ModelStatusCard` during download.

```kotlin
ModelDownloadProgress(
    status = ModelStatus.Downloading(
        progressPercent = 47,
        speedMbps = 3.2f,
        etaSeconds = 65
    ),
    onCancel = { /* cancel download */ }
)
```

### `ModelLoadingIndicator`

Compact spinning chip shown in the chat TopAppBar while Gemma loads into memory.

```kotlin
ModelLoadingIndicator(visible = state.isModelLoading)
```

### `OfflineAiIndicator`

Persistent pill chip shown in the HomeDashboard AI status row and chat header when Gemma is active.

```kotlin
OfflineAiIndicator(
    modelName = "Gemma 3",
    onTap = { navController.navigate(ModelSettingsRoute) }
)
```

---

## State → UI Mapping

| `ModelStatus` | Badge Text | Action Button |
|---|---|---|
| `NotDownloaded` | "Not installed" | "Download" |
| `Downloading` | "Downloading" | "Cancel" (in progress bar) |
| `Installing` | "Installing" | — |
| `Loading` | "Loading" | — |
| `Ready` | "Ready" (green) | "Manage" + "Delete" |
| `Error` | "Error" (red) | "Retry" |
| `Unavailable` | "Unavailable" | — |
| `Outdated(v)` | "Update available" | "Update" |

---

## RAM Guard

`HardwareCapabilityDetector` (feature-on-device-ai) checks device RAM before allowing download. The UI never exposes raw RAM values — only "Supported" or "Not supported on this device".

---

## Color Semantics

All Gemma UI uses the `Gemma` token set from `AppColors`:

| Token (light) | Value | Description |
|---|---|---|
| `gemmaContainerLight` | `#D1FAE5` | Chip/badge background |
| `gemmaOnContainerLight` | `#065F46` | Chip text |
| `gemmaIndicatorLight` | `#10B981` | Icon tint, dot indicator |
