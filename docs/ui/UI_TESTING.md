# UI Testing Strategy

> **Last updated**: Phase 14 — Production UI/UX Upgrade

---

## Test Tiers

| Tier | Type | Location | Tool |
|---|---|---|---|
| 1 | Unit (ViewModel/logic) | `src/test/` | JUnit 5 + MockK |
| 2 | Component UI | `core-ui/src/androidTest/` | Compose UI Test |
| 3 | Screen UI | `feature-*/src/androidTest/` | Compose UI Test |
| 4 | End-to-End flows | `app/src/androidTest/` | Compose UI Test |
| 5 | BDD scenarios | `app/src/androidTest/assets/features/` | Gherkin (Cucumber) |

---

## Screen Coverage Matrix

| Screen | Loading | Success | Error | Empty | Offline | Accessibility |
|---|---|---|---|---|---|---|
| `ChatDetailScreen` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `ChatListScreen` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `HistoryListScreen` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `SettingsScreen` | ✅ | ✅ | ✅ | — | — | ✅ |
| `DocumentListScreen` | ✅ | ✅ | ✅ | ✅ | — | ✅ |
| `HomeDashboard` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| `SplashScreen` | — | ✅ | — | — | — | ✅ |
| `LoginScreen` | ✅ | ✅ | ✅ | — | — | ✅ |

---

## Component Test Files

| File | Phase | Components Tested |
|---|---|---|
| `AiModeIndicatorTest.kt` | 14.6 | AiModeIndicator (all 4 modes, icon, a11y) |
| `ChatBubbleTest.kt` | 14.1 | UserMessageBubble, AssistantMessageBubble, streaming, long-press |
| `MessageInputBarTest.kt` | 14.2 | send/stop states, disabled, generating, text input |
| `ConnectivityStatusBarTest.kt` | 14.7 | OFFLINE/SYNCING/ONLINE, visibility |
| `ModelStatusCardTest.kt` | 14.5 | Ready/Downloading/Loading/Error/Unavailable |
| `AccessibilitySemanticsTest.kt` | 14.10 | contentDescriptions, 48dp targets, roles |
| `ContrastRatioNewTokensTest.kt` | 14.10/12.7 | WCAG AA contrast for all new AI-mode tokens |
| `ThemeSwitchingTest.kt` | existing | Light/Dark/System theme switching |
| `AnimationSystemTest.kt` | existing | Animation spec factories |
| `ContrastRatioTest.kt` | existing | Base M3 scheme contrast |
| `AdaptiveLayoutTest.kt` | existing | WindowSizeClass breakpoints |

---

## BDD Scenario Index

| File | Scenarios |
|---|---|
| `offline_ai.feature` | 5 scenarios covering offline UX, Gemma fallback, cache display |
| `rag_sources.feature` | 6 scenarios covering source chip, bottom sheet, document upload |
| `gemma_model_switching.feature` | 7 scenarios covering install, download, mode switching, loading |

---

## Running Tests

### Component / Screen Tests (instrumented)

```bash
./gradlew :core-ui:connectedAndroidTest
./gradlew :feature-chat:connectedAndroidTest
```

### Unit Tests

```bash
./gradlew :core-ui:test
./gradlew :feature-chat:test
./gradlew :feature-settings:test
```

### All Tests

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest
```

---

## Test Naming Convention

```
[Component]_[state/action]_[expected behavior]

Examples:
  sendButton_disabledWhenEmpty
  gemmaMode_displaysGemmaLabel
  offlineState_visible_displaysOfflineMessage
  readyState_displaysReadyBadge
```

---

## Known Limitations

1. BDD scenarios require Cucumber Android runner setup — step implementations are scaffolded but not all steps are wired to real app actions yet.
2. Snapshot / screenshot tests are not included — the design system uses dynamic Material You colors which differ per device.
3. Performance tests (`Macrobenchmark`) are separate from the main test suite.
