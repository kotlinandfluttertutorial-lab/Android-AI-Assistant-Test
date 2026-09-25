# Accessibility Guide

> **Last updated**: Phase 12 — Production UI/UX Upgrade

---

## Touch Target Policy

All interactive elements must meet the 48dp minimum:

```kotlin
// Use minimumInteractiveComponentSize for custom click targets
Modifier
    .minimumInteractiveComponentSize()  // ensures 48dp min
    .clickable { ... }

// IconButtons use it automatically via Material 3 defaults
IconButton(onClick = { ... }) { ... }  // 48dp guaranteed
```

---

## Content Descriptions

### Rules

1. Every `Icon` that conveys information must have `contentDescription`.
2. Purely decorative icons must use `contentDescription = null`.
3. Container composables with merged semantics describe the whole unit.

### Examples

```kotlin
// Decorative — icon is explained by surrounding text
Icon(
    imageVector = AppIcons.Ai.Gemma,
    contentDescription = null  // "On-device Gemma" chip describes it
)

// Informative — standalone icon button
IconButton(
    modifier = Modifier.semantics { contentDescription = "Send message" }
) { ... }

// Merged container
Row(
    modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = "Conversation: ${title}, last updated ${time}"
    }
) { ... }
```

---

## Role Annotations

Use `Role` for custom interactive containers that aren't standard M3 components:

```kotlin
Modifier.semantics { role = Role.Button }
Modifier.semantics { role = Role.Tab }
Modifier.semantics { role = Role.Checkbox }
```

---

## Error State Announcements

Error states that appear dynamically must use `liveRegion` so TalkBack announces them without focus change:

```kotlin
Modifier.semantics {
    liveRegion = LiveRegionMode.Polite
    contentDescription = "Error: $message"
}
```

---

## TalkBack Test Checklist

Before releasing any screen:

- [ ] All meaningful icons have `contentDescription`
- [ ] Decorative icons have `contentDescription = null`
- [ ] All tap targets are ≥ 48dp × 48dp
- [ ] Custom clickable containers have `Role` semantics
- [ ] Error banners use `liveRegion`
- [ ] Screen titles are announced on navigation
- [ ] Modal bottom sheets announce their title
- [ ] Progress indicators have `contentDescription`
- [ ] Images have alt text or `contentDescription = null` if decorative

---

## Contrast Requirements (WCAG AA)

| Text Type | Minimum Ratio |
|---|---|
| Normal text (< 18sp) | 4.5 : 1 |
| Large text (≥ 18sp or ≥ 14sp bold) | 3.0 : 1 |
| UI components & icons | 3.0 : 1 |

All new `AppColors` AI-mode tokens are verified in `ContrastRatioNewTokensTest.kt`.

---

## Font Scaling

All text uses `sp` units. Test at 200% font scale:

1. Open device Settings → Accessibility → Font size → Set to largest.
2. Launch the app.
3. Verify no text truncates without `overflow = TextOverflow.Ellipsis`.
4. Verify no layouts overflow screen width.
5. Verify all interactive elements remain tappable.

---

## Reduced Motion

All animations check `LocalReducedMotionEnabled.current`:

```kotlin
val reducedMotion = LocalReducedMotionEnabled.current
val duration = if (reducedMotion) 0 else DURATION_MEDIUM
```

Screens that use `HapticHelper` suppress haptic feedback when reduced motion is active.
