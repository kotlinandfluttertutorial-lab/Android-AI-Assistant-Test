# RAG UI Guide

> **Last updated**: Phase 9 — Production UI/UX Upgrade

---

## Document Ingestion Pipeline

```
Upload ──► Processing ──► Chunking ──► Embedding ──► Indexed
  │                                                     │
  └──────────────────── Failed ◄───────────────────────┘
                           │
                       (retry)
                           ▼
                        Upload
```

---

## `DocumentIndexStatus` Enum

| Value | Badge Color | Progress Bar |
|---|---|---|
| `UPLOADING` | Blue (cloud) | Yes |
| `PROCESSING` | Violet (RAG) | Yes |
| `CHUNKING` | Violet (RAG) | Yes |
| `EMBEDDING` | Violet (RAG) | Yes |
| `INDEXED` | Green (gemma) | No |
| `FAILED` | Red | No (retry button) |

---

## Citation Components

### `CitationCard`

Full citation card used inside `SourcesBottomSheet`.

```kotlin
CitationCard(
    documentName = "Android Architecture.pdf",
    pageOrChunk = "Page 12",
    relevanceLabel = "High",
    onTap = { /* open document */ }
)
```

### `SourceChip`

Compact inline badge below RAG responses. Tapping opens `SourcesBottomSheet`.

```kotlin
SourceChip(
    sourceCount = 3,
    onTap = { showSourcesSheet = true }
)
```

### `SourcesBottomSheet`

```kotlin
if (showSourcesSheet) {
    SourcesBottomSheet(
        sources = listOf(
            "Android Architecture.pdf" to "Page 12",
            "API Guide.md" to "Section 3.2",
            "Meeting Notes.docx" to null
        ),
        onDismiss = { showSourcesSheet = false }
    )
}
```

### `DocumentReferenceCard`

Compact reference card for the DocumentChatScreen sidebar.

```kotlin
DocumentReferenceCard(
    documentName = "API Specification.pdf",
    fileSizeLabel = "1.2 MB",
    status = DocumentIndexStatus.INDEXED,
    onTap = { /* select document */ }
)
```

---

## Integration with `AssistantMessageBubble`

When a RAG response includes citations, pass them to the bubble:

1. Show `AiModeIndicator(mode = AiMode.RAG)` above the first turn message.
2. Render `SourceChip(sourceCount = citations.size)` below the message text.
3. Tapping the chip shows `SourcesBottomSheet` with full `CitationCard` list.

---

## Color Semantics

All RAG UI uses the `RAG` token set from `AppColors`:

| Token (light) | Value | Description |
|---|---|---|
| `ragContainerLight` | `#EDE9FE` | Chip/badge background |
| `ragOnContainerLight` | `#4C1D95` | Text |
| `ragIndicatorLight` | `#8B5CF6` | Icon tint |
