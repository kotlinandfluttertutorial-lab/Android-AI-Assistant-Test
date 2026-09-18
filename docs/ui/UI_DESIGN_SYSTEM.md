# UI Design System

> **Module**: `core-ui`  
> **Last updated**: Phase 2 — Production UI/UX Upgrade

---

## Overview

All design tokens live in `core-ui/src/main/kotlin/com/aiassistant/core/ui/`.  
Feature modules **must never** define their own colors, typography, spacing, or shape values — they import from `core-ui`.

---

## Color Tokens

### Material 3 Color Scheme

| Token | Light | Dark | Use Case |
|---|---|---|---|
| `primary` | `#1B6EF5` | `#ADC6FF` | Primary actions, active nav items |
| `primaryContainer` | `#D8E2FF` | `#0047CA` | Chips, selected state backgrounds |
| `background` | `#FEFBFF` | `#111318` (OLED) | Screen background |
| `surface` | `#FEFBFF` | `#111318` | Card base |
| `surfaceVariant` | `#E3E2EC` | `#44474F` | Chip background, input fields |

### AI-Mode Semantic Tokens (`AppColors`)

| Mode | Container Light | onContainer Light | Container Dark | onContainer Dark | Use |
|---|---|---|---|---|---|
| **Gemma** | `#D1FAE5` | `#065F46` | `#064E3B` | `#A7F3D0` | On-device indicator |
| **Cloud AI** | `#DBEAFE` | `#1E40AF` | `#1E3A5F` | `#BFDBFE` | Cloud indicator |
| **RAG** | `#EDE9FE` | `#4C1D95` | `#2E1065` | `#DDD6FE` | Document RAG indicator |

### Code Block Tokens

| Token | Value | Use |
|---|---|---|
| `codeBlockBackground` | `#0F172A` | Always-dark code block background |
| `codeBlockSurface` | `#1E293B` | Code block header bar |
| `codeBlockOnSurface` | `#CBD5E1` | Code text color |
| `codeBlockLineNumber` | `#64748B` | Language label, line numbers |

---

## Typography Scale

All values use `sp` units to respect system font scaling.

| Role | Size | Weight | Usage |
|---|---|---|---|
| `displayAI` | 48sp SemiBold | Hero AI displays |
| `headlineLarge` | 32sp Normal | Screen headings |
| `titleMedium` | 16sp Medium | TopAppBar, card headers |
| `bodyLarge` | 16sp Normal | Primary chat text, list items |
| `bodyMedium` | 14sp Normal | Message bubble content |
| `bodySmall` | 12sp Normal | Timestamps, metadata |
| `labelLarge` | 14sp Medium | Buttons, tabs |
| `labelSmall` | 11sp Medium | Chips, badges |

### Extended Tokens (`AppTypeExtended`)

| Token | Size | Font | Usage |
|---|---|---|---|
| `codeContent` | 12sp Mono Normal | Code body text |
| `codeLabel` | 11sp Mono Medium | Code block language header |
| `aiModeLabel` | 11sp Medium | AI mode chip labels |
| `cacheTimestamp` | 10sp Normal | "Cached · 3:42 PM" labels |
| `documentTitle` | 14sp SemiBold | Document list titles |

---

## Spacing Tokens (`Spacing`)

All values are 8dp-grid multiples.

| Token | Value | Use Case |
|---|---|---|
| `none` | 0dp | Explicit zero |
| `xs` | 4dp | Chip inner padding, icon inset |
| `sm` | 8dp | List item vertical padding |
| `md` / `screenEdge` | 16dp | Standard screen edge, card padding |
| `lg` | 24dp | Section spacing |
| `xl` | 32dp | Hero section vertical spacing |
| `xxl` | 48dp | Minimum touch target, coarse rhythm |
| `xxxl` | 64dp | Full-screen intro screens |

---

## Shape Tokens

### Material 3 Standard (`MaterialShapes`)

| Token | Radius | Use Case |
|---|---|---|
| `extraSmall` | 4dp | Chips, tooltips |
| `small` | 8dp | Buttons, snackbars |
| `medium` | 12dp | Cards, dialogs |
| `large` | 16dp | Navigation drawers |
| `extraLarge` | 28dp | Full-screen bottom sheets |

### Extended Tokens (`AppShapes`)

| Token | Value | Use Case |
|---|---|---|
| `pill` | 50% | AiModeIndicator, chips, input bar |
| `bubble` | 18dp | Chat bubbles |
| `bubbleTail` | 4dp | Asymmetric bubble "tail" corner |
| `sheet` | top-28dp | Bottom sheets |
| `codeBlock` | 8dp | Code block containers |
| `avatar` | CircleShape | AI avatar, user avatar |
| `inputBar` | 28dp | MessageInputBar container |

---

## Elevation Tokens (`Elevation`)

| Token | Value | Use Case |
|---|---|---|
| `none` | 0dp | List rows, flat surfaces |
| `low` | 1dp | Cards at rest |
| `mid` | 3dp | Raised cards, FAB |
| `high` | 6dp | Pressed cards, drawers |
| `modal` | 12dp | Dialogs, bottom sheets |
| `toast` | 24dp | Snackbars |

---

## Animation Specs (`Animation.kt`)

| Constant | Value | Use |
|---|---|---|
| `DURATION_MICRO` | 100ms | Icon taps, immediate feedback |
| `DURATION_QUICK` | 150ms | Button state, chip select |
| `DURATION_SHORT` | 200ms | Tab switches, fade-through |
| `DURATION_MEDIUM` | 300ms | Screen navigation |
| `DURATION_LONG` | 400ms | Theme crossfade |
| `SHIMMER_DURATION_MS` | 1200ms | Skeleton shimmer cycle |
| `CURSOR_BLINK_MS` | 800ms | Streaming cursor blink |
| `COPY_CONFIRM_DURATION_MS` | 1500ms | Copy → checkmark duration |
| `ONLINE_BANNER_AUTO_DISMISS_MS` | 3000ms | "Back online" banner auto-dismiss |

### Easing

| Alias | M3 Easing | Use |
|---|---|---|
| `EasingEnter` | LinearOutSlowIn | Elements entering |
| `EasingExit` | FastOutLinearIn | Elements exiting |
| `EasingEmphasized` | FastOutSlowIn | Emphasis, state changes |

---

## Icon Registry (`AppIcons`)

All icons are sourced from `androidx.compose.material.icons`. See `AppIcons.kt` for the complete registry.

| Group | Key Icons |
|---|---|
| `Navigation` | Back, Forward, Menu, Close, MoreVert |
| `Destinations` | HomeFilled/Outlined, ChatFilled/Outlined, HistoryFilled/Outlined |
| `Chat` | Send, Stop, Attach, Mic, Copy, CopyDone, Share, Regenerate, ThumbUp, ThumbDown |
| `Ai` | Gemma (OfflineBolt), Cloud, Rag (Source), Assistant (SmartToy) |
| `Documents` | Document, Upload, Indexed (CheckCircle), Failed (ErrorOutline) |
| `Status` | Offline (WifiOff), Syncing (SyncAlt), Success (CheckCircle), Error |

---

## Usage Rules

1. **Never** use raw hex colors in composables — always use `MaterialTheme.colorScheme.*` or `AppColors.*`.
2. **Never** use raw `dp` values for spacing — always use `MaterialTheme.spacing.*`.
3. **Never** use `Icons.*` directly — always use `AppIcons.*`.
4. **Never** create a `Typography` or `Shapes` instance in a feature module.
5. All text must use `sp` units, never `dp`.
