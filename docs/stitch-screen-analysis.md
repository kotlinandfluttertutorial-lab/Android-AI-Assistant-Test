# Stitch Screen Analysis
## Phase 1 — Design Reference → Android Compose Mapping

> **Status:** Analysis only. No Android production code modified.  
> **Source:** `design/stitch/src/screens/` (13 TSX screens + 1 shared component)  
> **Target:** Jetpack Compose screens inside the existing modular Android project  
> **Date:** September 2026

---

## Table of Contents

1. [Complete Screen Inventory](#1-complete-screen-inventory)
2. [Screen-to-Compose Mapping](#2-screen-to-compose-mapping)
3. [Shared Component Mapping](#3-shared-component-mapping)
4. [Navigation Flow](#4-navigation-flow)
5. [Design-Token Analysis](#5-design-token-analysis)
6. [Required Assets](#6-required-assets)
7. [Existing Android Code That Can Be Reused](#7-existing-android-code-that-can-be-reused)
8. [Missing Android Components](#8-missing-android-components)
9. [AI Architecture Integration Points](#9-ai-architecture-integration-points)
10. [Recommended Migration Order](#10-recommended-migration-order)

---

## 1. Complete Screen Inventory

### 1.1 SplashScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Full-screen brand intro; auto-navigates to `onboarding` after 2800 ms |
| **Background** | `linear-gradient(160deg, #1a0533 → #381E72 → #6750A4 → #9C89C4)` |
| **Components** | Ambient orb blobs (2× absolute `div`), logo container (glass-morphism rounded rect 88×88 r28), custom `AILogoMark` SVG, app name h1, tagline p, 3-dot loading animation, "Powered by Gemini" footer label |
| **Typography** | App name: 32sp w700, tagline: 14sp w400 60% white, footer: 12sp uppercase letter-spacing 1.5 |
| **Animations** | `fadeIn 0.8s` on logo group; `fadeIn 1s delay 0.5s` on dots; `typing-dot` keyframe per dot (staggered 0/0.2s/0.4s) |
| **Navigation** | Auto `navigate('onboarding')` after timeout — no user action |
| **Loading state** | Pulsing loading dots are the primary loading indicator |
| **Input fields** | None |
| **Bottom nav** | None (pre-auth flow) |
| **Dark mode** | Always dark (gradient background) |

---

### 1.2 OnboardingScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | 3-slide pager introducing app capabilities; ends at `login` |
| **Slides** | `🤖 Meet Your AI Assistant`, `⚡ Limitless Capabilities`, `🔒 Private & Secure` |
| **Slide gradients** | Purple `#6750A4→#9C89C4`, Rose `#7D5260→#B26978`, Green `#386A20→#5A9E3C` |
| **Background** | `#FFFBFE` (light) |
| **Components** | Skip button (top-right, text), illustration area (gradient rounded-rect r28, 340px), large emoji hero (96sp), progress dot row (active dot expands to 24×8, inactive 8×8 r4), Next FAB (56×56 r28 `#6750A4`), "Get Started" full-width button (visible on last slide only) |
| **Typography** | Slide title: 30sp w700 `#1C1B1F` letter-spacing -0.5; description: 16sp w400 `#49454F` lh1.6 |
| **Animations** | Slide content: `animate-fade-in` + `animate-slide-up` CSS classes on key change; dot width transition 0.3s; Next arrow SVG inside FAB |
| **Navigation** | Skip → `login`; Next (slides 0/1) → next slide; Next (slide 2) → `login`; Get Started → `login` |
| **Selected state** | Active dot: 24×8 `#6750A4`; inactive: 8×8 `#CAC4D0` |
| **Bottom nav** | None |

---

### 1.3 LoginScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Authentication entry; 3 modes: login / signup / forgot-password |
| **Background** | `bg` (dark: `#1C1B1F`, light: `#FFFBFE`) |
| **Components** | Logo icon (64×64 r20 gradient `#6750A4→#9C89C4`), custom AI SVG, h1 title (dynamic per mode), subtitle p, Google Sign-In button (card bg + Google SVG), divider "or", email `<Input>`, password `<Input>` with show/hide toggle, forgot-password text button, primary CTA full-width button (h52 r26), sign-up / sign-in toggle footer link |
| **Input fields** | Email (type=email), Password (type=password/text toggle), Full name (signup mode only) |
| **Typography** | Title: 26sp w700; subtitle: 14sp; label: 13sp w500; input placeholder: 15sp; CTA: 16sp w600 |
| **Shapes** | Inputs: r14; Google button: r26; CTA: r26; logo: r20 |
| **States** | login / signup / forgot — title, subtitle, fields, CTA label all change dynamically |
| **Error state** | Not shown in design (field-level validation not implemented in reference) |
| **Animations** | `animate-fade-in` (0.6s) on main container; logo hover scale; floating image `animate-float` 6s on desktop sidebar |
| **Navigation** | Google button → `home`; Sign In CTA → `home`; Forgot → mode=`forgot`; Back to sign in → mode=`login`; Sign up link ↔ toggle modes |
| **Dark mode** | Full support — all colours token-switched |
| **Bottom nav** | None |

---

### 1.4 HomeScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Main dashboard / hub screen; entry point after login |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Top bar (greeting h2 + dark-mode toggle + avatar circle), search bar button (navigates to `chat`, r26, shadow), featured banner (gradient card `#6750A4→#9C89C4`, overflow circles, "Try now" ghost button), Quick Actions 4-column grid (8 items), Recent Chats list (4 items), BottomNav |
| **Quick Actions** | Chat 💬, Voice 🎙️, PDF 📄, Image 🖼️, Code ⌨️, Translate ✨, Notes 📝, Tools 🔧 — each is a card (r16) with icon container (40×40 r14 coloured bg) + label 11sp |
| **Recent Chats** | Row: avatar (40×40 r20 `#E8DEF8`), title 14sp w600, time 11sp, preview 12sp truncated; tap → `chat` |
| **Section headers** | "Quick Actions", "Recent Chats" — 16sp w600; "See all" TextButton → `history` tab |
| **Scrolling** | Main content scrollable vertically; search bar sticky at top |
| **FAB** | 56×56 r16 `#E8DEF8` add-icon, position `bottom-24 right-6` (above nav) |
| **Typography** | Greeting sub: 13sp; name: 22sp w700; banner headline: 18sp w700; banner body: 13sp |
| **Navigation** | Search bar → `chat`; Quick action cards → respective screens; "See all" → history tab; Featured "Try now" → `chat` |
| **Bottom nav** | BottomNav with `home` tab active |

---

### 1.5 ChatScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Conversational AI chat screen; supports multimodal input |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back arrow, AI avatar circle gradient, "AI Assistant" title, online indicator dot, 3-dot menu), message list (`flex-1 overflow-y-auto`), typing indicator (3 pulsing dots), suggestion chips (horizontal scroll, visible when ≤3 messages), input bar |
| **Message bubbles** | User: `#6750A4` bg, rounded `20/20/4/20`px, white text; AI: card bg + border, rounded `20/20/20/4`px, themed text |
| **AI avatar** | 32×32 r16, gradient `#6750A4→#9C89C4`, 🤖 emoji |
| **Input bar** | Attach 📎 / Camera 📷 / Voice 🎙️ icon buttons (32×32), textarea (auto-grow, max-height 100), send button (40×40 r20, active=`#6750A4`, inactive=`#CAC4D0`) |
| **Input field** | Textarea: `background=inputBg`, `border=1.5px`, r24 container |
| **Markdown rendering** | Bold `**text**`, fenced code blocks (\`\`\`) with dark `#1e1e2e` bg monospace pre block |
| **Message actions** | Copy 📋, Regenerate 🔄, Like 👍, Dislike 👎 — below AI bubbles only |
| **Suggestion chips** | Horizontal scroll; 4 pre-built prompts; tap fills input |
| **Typing indicator** | 3× 7px dots, `#6750A4`, staggered `typing-dot` animation |
| **Auto-scroll** | `scrollIntoView` on messages change and typing state |
| **Loading state** | Typing indicator replaces response |
| **Send button state** | `#6750A4` when input non-empty, `#CAC4D0` when empty |
| **Navigation** | Back arrow → `home`; no bottom nav (feature screen) |

---

### 1.6 CodeScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Code editor + AI analysis assistant |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back, title "Code Assistant", language picker pill), language picker dropdown (wrap, shown/hidden toggle), code editor block (dark `#1e1e2e`, macOS traffic-light dots, filename label, textarea, monospace Fira Code-style), action button grid (4 items), AI result card, copy/share row |
| **Code editor** | Dark `#1e1e2e` background, `color: #cdd6f4`, monospace 12.5sp lh1.7, resizable textarea, traffic-light dots (#ff5f57, #ffbd2e, #28ca41) |
| **Action buttons** | Explain 💡, Fix Bugs 🐛, Optimize ⚡, Gen Tests 🧪 — 4-column grid, active state fills button with action color |
| **Action active colors** | Explain: `#6750A4`, Fix: `#B3261E`, Optimize: `#0061A4`, Tests: `#386A20` |
| **AI result card** | Card bg + border r16, ✨ icon + "AI Analysis" header, loading dots or `CodeFormattedText` (code blocks with dark pre bg) |
| **Language picker** | Pill `#E8DEF8` border `#D0BCFF`, dropdown: flex-wrap row of language chips |
| **Languages** | Python, JavaScript, TypeScript, Kotlin, Swift, Go, Rust, Java |
| **Copy/Share row** | Two equal-width buttons: Copy (card bg), Share (`#6750A4`) |
| **Loading state** | 3-dot typing animation inside result card |
| **Navigation** | Back → `home`; no bottom nav |

---

### 1.7 HistoryScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Browsable, searchable chat history with pin/group |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (h2 "Chat History" + new-chat FAB 40×40 r20 `#6750A4`), search bar (r24 card bg), filter chips ("All Chats" / "📌 Pinned"), grouped chat list (Today / Yesterday / Earlier), empty state, BottomNav, FAB |
| **Chat row** | Icon (40×40 r20 `#E8DEF8` emoji), title 14sp w600 (pinned shows 📌), preview 12sp truncated, time 11sp, 3-dot action button |
| **Context menu** | Absolute positioned card (r14, shadow), items: Pin 📌, Rename ✏️, Delete 🗑️ (danger color `#B3261E`) |
| **Filter chips** | Active: `#6750A4` bg white text; inactive: card bg + border |
| **Group headers** | 12sp w600 uppercase sub-color letter-spacing 0.8 |
| **Empty state** | 💬 48sp icon, "No chats found" 16sp w600, "Try a different search term" 14sp |
| **Search** | Controlled input in card; clear button appears on focus |
| **Context menu trigger** | onContextMenu (long-press) or 3-dot button click |
| **Navigation** | Chat row → `chat`; New-chat FAB → `chat`; BottomNav chats tab active |

---

### 1.8 ImageScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | AI image analysis — 3-state flow: picker → analyzing → result |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back, title "Image Assistant", subtitle), action cards (Take Photo / Gallery, 2-col grid), sample image grid (3×2 Unsplash thumbnails), analyzing state (spinner ring + 4-step progress list), result state (image preview, description card, detected objects card, OCR card, action buttons) |
| **State: picker** | 2 large action cards (r20), 6 thumbnail samples (r12 grid) |
| **State: analyzing** | Spinner (120×120 border-only ring + spinning top-border), 🔍 inside circle, step list (4 items): Object detection ✓, OCR ✓, Scene analysis ✓, Description (loading dots) |
| **State: result** | Image preview (r20, "AI Analysis ✓" badge overlay), 3 `ResultCard` components (tinted header + white body), "Ask about this" → `chat` button, "New Image" button |
| **ResultCard** | Coloured header bar (bg + icon + title), white body with description text |
| **Action cards** | Take Photo: `#E8DEF8` bg `#6750A4` text; Gallery: card bg |
| **Step progress icons** | Complete: green ✓ circle; In progress: loading dots; Pending: empty circle |
| **Navigation** | Back → `home`; "Ask about this" → `chat`; "New Image" → picker state |

---

### 1.9 PDFScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | PDF upload, browse, view summary, Q&A assistant |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back, "PDF Assistant", subtitle), upload drop zone (dashed border `#6750A4`, r20, 📤 icon), document list (3 items), selected-PDF view: file header bar (red `#B3261E`), page thumbnail strip (3 pages), AI summary card, Q&A input + answer |
| **Upload zone** | Full-width, dashed `2px` border `#6750A4`, `#F3EDF7` bg, tap to upload |
| **Document row** | PDF icon (44×52 r8 `#FFD7D4`), name 14sp w600 truncated, meta 12sp (pages • size • date), chevron |
| **PDF header** | `#B3261E` bg, white text, Close button |
| **Page thumbnails** | Horizontal scroll, 80×110 white cards with skeleton lines |
| **AI Summary** | Card (r16), ✨ icon, `FormattedText` renderer (bold lines, bullet lines) |
| **Q&A** | Input r22 + send button r22 `#6750A4`; answer shown in `#E8DEF8` tinted block r12 |
| **Loading state** | 3-dot typing animation in Q&A area |
| **Navigation** | Back → `home`; document tap → selected state; Close → list state |

---

### 1.10 VoiceScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Voice-to-AI conversation; 4 states: idle / listening / processing / speaking |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back, "Voice Assistant"), status label, mic button (96×96 r48), pulse rings (3 animated rings, visible in `listening` state), sound wave bars (15 bars, animated), transcript card, AI response card, footer action buttons |
| **Mic button colors** | idle: `#6750A4`; listening: `#B3261E`; processing: `#386A20`; speaking: `#0061A4` |
| **Mic icon** | idle/speaking: microphone SVG; listening/processing: stop square |
| **Pulse rings** | 3 expanding rings, `rgba(103,80,164,0.15)`, `pulse-ring 1.8s stagger 0.3s` — visible only in `listening` |
| **Wave bars** | 15 bars, 4px wide r2, color: listening=`#6750A4`, speaking=`#0061A4`, idle=`#CAC4D0`; height animated via `wave` keyframe |
| **Transcript card** | White card r20, "You said" label 11sp uppercase, transcript 16sp |
| **AI response card** | `#E8DEF8` bg, `#D0BCFF` border r20, "AI Response" label `#6750A4` 11sp uppercase, response 15sp `#21005D` |
| **Footer buttons** | "Switch to Chat" (card bg, r24); "New Session" (`#6750A4`, visible when response shown) |
| **Navigation** | Back → `home`; "Switch to Chat" → `chat` |

---

### 1.11 ToolsScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | Tools library / explorer; 12 specialized AI tools |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (h2 "AI Tools", subtitle), search bar (read-only placeholder), featured banner (gradient `#7D5260→#B26978`, "Resume Builder AI", "Try now" ghost button → `pdf`), 2-column tools grid (12 tools), BottomNav |
| **Tool card** | r18 card bg + border, icon (44×44 r14 coloured bg emoji), tool name 14sp w700, description 12sp sub-color; tap navigates to target screen |
| **Tools & targets** | Summarizer→chat, Translator→chat, Grammar Check→chat, Resume Builder→pdf, Email Writer→chat, Meeting Notes→voice, To-Do Generator→chat, Code Generator→code, Image Creator→image, Data Analyzer→pdf, Research Helper→chat, Finance Advisor→chat |
| **Featured banner** | `#7D5260→#B26978` gradient, overflow circle decoration, ghost button `rgba(255,255,255,0.2)` |
| **Search bar** | Non-interactive in design (placeholder only); no filter chips |
| **Navigation** | Tool cards → respective screens; BottomNav `tools` tab active |

---

### 1.12 ProfileScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | User profile, usage stats, account menu |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header gradient band (purple `#6750A4→#9C89C4`, avatar circle 72×72, name, email, plan badge), floating stats card (4-column grid, overlaps header by -16px), menu list card (7 items), Sign Out button, version footer, BottomNav |
| **Avatar** | 72×72 r36, gradient `#D0BCFF→#9C89C4`, initial "F", white border 3px; online dot (16×16 r8 `#386A20`) |
| **Plan badge** | Inline chip `rgba(255,255,255,0.2)` r12 "⚡ Free Plan" |
| **Stats card** | Floats (-16px margin-top) white card r20 shadow; 4 stats: Total Chats 247, Words 84.2K, PDFs 18, Days Active 63; divided by hairlines |
| **Menu item** | Icon (40×40 r14 tinted bg emoji), title 15sp w600, description 12sp, chevron; "Upgrade to Pro" item has `#F3EDF7` row bg, purple text, "PRO" badge |
| **Sign Out button** | Full-width r26 `#FFD7D4` bg `#F2B8B5` border `#B3261E` text |
| **Navigation** | Menu items → `settings`; Sign Out → `login`; BottomNav `profile` tab active |

---

### 1.13 SettingsScreen.tsx

| Property | Detail |
|---|---|
| **Purpose** | App configuration — appearance, notifications, voice, privacy, API key, about |
| **Background** | `#FFFBFE` / `#1C1B1F` |
| **Components** | Header (back → `profile`, "Settings"), grouped sections (Appearance / Notifications / Voice / Privacy / API Key / About), each with icon + UPPERCASE title + card of `SettingRow` items |
| **Sections** | Appearance: Dark Mode toggle, Language select; Notifications: Push toggle; Voice: Voice toggle, Voice Speed row; Privacy: Data Sharing toggle, Clear History danger row; API Key: password input + Save button; About: Version, Terms, Privacy Policy |
| **Toggle** | Custom 48×28 pill: active=`#6750A4`, inactive=`#CAC4D0`; thumb 22×22 white, animated left position |
| **Input field** | API key: r12 monospace password input, `#F3EDF7` bg light / `#1C1B1F` dark |
| **Danger row** | "Clear History": `#B3261E` label text |
| **Save API Key** | Full-width r22 `#6750A4` button |
| **Language** | Native `<select>` (7 options) — maps to Spinner/DropdownMenu in Compose |
| **Navigation** | Back → `profile` |

---

## 2. Screen-to-Compose Mapping

| TSX File | Compose Target | Module | Existing? |
|---|---|---|---|
| `SplashScreen.tsx` | `SplashScreen.kt` | `feature-auth` | ✅ Exists (`feature-auth/SplashScreen.kt`) |
| `OnboardingScreen.tsx` | `OnboardingScreen.kt` | `feature-auth` | ✅ Exists (`feature-auth/OnboardingScreen.kt`) |
| `LoginScreen.tsx` | `LoginScreen.kt` | `feature-auth` | ✅ Exists (`feature-auth/LoginScreen.kt`) |
| `HomeScreen.tsx` | `HomeDashboard.kt` | `app` | ✅ Exists (`app/HomeDashboard.kt`) |
| `ChatScreen.tsx` | `ChatDetailScreen.kt` | `feature-chat` | ✅ Exists (`feature-chat/ChatDetailScreen.kt`) |
| `CodeScreen.tsx` | `CodeEditorScreen.kt` + `CodeAnalysisScreen.kt` | `feature-code` | ✅ Exists (2 screens) |
| `HistoryScreen.tsx` | `HistoryListScreen.kt` + `SearchHistoryScreen.kt` | `feature-history` | ✅ Exists (2 screens) |
| `ImageScreen.tsx` | `ImageAnalysisScreen.kt` + `CameraCaptureScreen.kt` | `feature-camera` | ✅ Exists |
| `PDFScreen.tsx` | `RAGViewModel` + `DocumentChatViewModel` + screens | `feature-rag` | ✅ Exists (via RAG module) |
| `VoiceScreen.tsx` | `VoiceScreen.kt` | `feature-voice` | ✅ Exists |
| `ToolsScreen.tsx` | `HomeDashboard.kt` (quick actions section) | `app` | ⚠️ Partial — no dedicated ToolsScreen |
| `ProfileScreen.tsx` | `ProfileScreen.kt` | `feature-profile` | ✅ Exists (ProfileViewModel confirmed) |
| `SettingsScreen.tsx` | `SettingsScreen.kt` | `feature-settings` | ✅ Exists |

### Screen state mapping notes

**CodeScreen** maps to **two** Android screens (editor + analysis) — the Stitch design shows both in one scrollable view. The Compose implementation correctly splits them.

**HistoryScreen** maps to **two** Android screens (list + search) — the Stitch design collapses both into one screen with inline search.

**ImageScreen** maps to **three** Android screens (capture + analysis + OCR result) — the Stitch design is a 3-state single screen.

**PDFScreen** maps to the **feature-rag** module (document upload, list, chat) rather than a dedicated PDF module.

**ToolsScreen** has **no dedicated Android screen** — quick actions are embedded in `HomeDashboard`. A standalone `ToolsScreen.kt` is needed in a new `feature-tools` module or inside `feature-dashboard`.

---

## 3. Shared Component Mapping

### BottomNav.tsx → AppNavigationBar.kt

| TSX Property | Compose Equivalent |
|---|---|
| 4 tabs: Home, Chats, AI Tools, Profile | `AppNavigationBar` in `app/navigation/AppNavigation.kt` |
| Active tab: `#E8DEF8` pill (64×32) behind icon | `NavigationBarItem` indicator color from `MaterialTheme.colorScheme.secondaryContainer` |
| Active icon: filled SVG; inactive: outlined SVG | `AppIcons` object — filled vs outlined variants |
| Active label: 11sp w600; inactive: 11sp w400 | `NavigationBarItem` label style |
| Tab IDs: `home` / `chats` / `tools` / `profile` | Routes: `HOME_ROUTE`, `ChatRoute`, `ToolsRoute`, `ProfileRoute` |
| Tab navigation: `handleTabChange` in `App.tsx` | `AppNavigationShell` onNavigate callback + `NavHostController.navigate()` |
| Adaptive behavior | `AppNavigationShell` handles compact=BottomBar, medium=Rail, expanded=Drawer |

### Other shared design elements

| TSX Element | Compose Equivalent | Location |
|---|---|---|
| `typing-dot` animation (3-dot pulse) | `TypingIndicator` composable | `core-ui/motion/TypingIndicator.kt` |
| AI chat bubble (left-aligned, rounded asymmetric) | `ChatBubble` composable | `core-ui/components/ChatBubble.kt` |
| Markdown rendering (`**bold**`, code blocks) | `MarkdownText` composable | `core-ui/components/MarkdownText.kt` |
| Monospace code block (`#1e1e2e` dark bg) | `CodeBlock` composable | `core-ui/components/CodeBlock.kt` |
| Send button + input field composite | `MessageInputBar` composable | `core-ui/components/MessageInputBar.kt` |
| Shimmer skeleton loading | `ShimmerSkeleton` composable | `core-ui/components/ShimmerSkeleton.kt` |
| Loading spinner / dots | `LoadingIndicator` composable | `core-ui/components/LoadingIndicator.kt` |
| Swipe-to-reveal actions | `SwipeRevealLayout` composable | `core-ui/components/SwipeRevealLayout.kt` |
| Offline banner | `OfflineBanner` composable | `core-ui/components/OfflineBanner.kt` |
| AI mode indicator (Cloud / On-device badge) | `AiModeIndicator` composable | `core-ui/components/AiModeIndicator.kt` |
| Model status card | `ModelStatusCard` composable | `core-ui/components/ModelStatusCard.kt` |
| Toggle switch | Material3 `Switch` (custom styled via `SwitchDefaults.colors`) | — |
| `SurfaceFillTextField` | `SurfaceFillTextField` composable | `core-ui/components/SurfaceFillTextField.kt` |
| Error display inside chat | `InlineChatError` composable | `core-ui/components/InlineChatError.kt` |
| StreamingMessage (token-by-token) | `StreamingMessage` composable | `core-ui/components/StreamingMessage.kt` |

---

## 4. Navigation Flow

### 4.1 Stitch navigation graph (TSX)

```
splash (2800ms)
  └→ onboarding
       └→ login (Skip / Get Started)
            └→ home
                 ├→ chat          (search bar, quick action, recent chats)
                 ├→ voice         (quick action)
                 ├→ pdf           (quick action)
                 ├→ image         (quick action)
                 ├→ code          (quick action)
                 ├→ tools         (quick action / BottomNav AI Tools tab)
                 ├→ history       (BottomNav Chats tab / "See all")
                 └→ profile       (BottomNav Profile tab)
                      └→ settings
                           └→ profile (back)

BottomNav tabs (from any main screen):
  home ←→ history ←→ tools ←→ profile

Feature-screen back actions:
  chat/voice/pdf/image/code → home
  settings → profile
```

### 4.2 Android navigation graph (Compose)

```
AuthRoute.GRAPH (startDestination)
  ├── SplashScreen      (auto → onboarding or home)
  ├── OnboardingScreen  (→ login)
  ├── LoginScreen       (→ HomeDashboard)
  └── RegisterScreen    (→ login)

HOME_ROUTE
  └── HomeDashboard     (AppNavigationShell wraps all main screens)

ChatRoute.GRAPH
  ├── ChatListScreen
  └── ChatDetailScreen

CodeRoute.GRAPH
  ├── CodeEditorScreen
  └── CodeAnalysisScreen

RAGRoute.GRAPH
  ├── DocumentListScreen / upload
  └── DocumentChatScreen

VoiceRoute
  └── VoiceScreen

TranslatorRoute
  └── TranslatorScreen

CameraRoute.GRAPH
  ├── CameraCaptureScreen
  ├── ImageAnalysisScreen
  └── OcrResultScreen

HistoryRoute
  ├── HistoryListScreen
  └── SearchHistoryScreen

ProfileRoute
  └── ProfileScreen

SettingsRoute
  ├── SettingsScreen
  └── CostDashboardScreen

ResumeRoute.GRAPH
  ├── ResumeBuilderScreen
  └── CoverLetterEditorScreen

EmailRoute / TranslatorRoute / ProductivityRoute / SearchRoute (additional feature modules)
```

### 4.3 Navigation gap analysis

| Stitch Screen | Android Route | Gap |
|---|---|---|
| `tools` (ToolsScreen) | No dedicated route | ❌ Missing — embedded in HomeDashboard |
| BottomNav "AI Tools" | No `ToolsRoute` composable | ❌ BottomNav item navigates to `HomeDashboard` (wrong) |
| PDF upload list | `RAGRoute.DOCUMENT_LIST` | ✅ Exists but branded differently |
| Image picker state | `CameraRoute.CAPTURE` | ✅ Equivalent |

---

## 5. Design-Token Analysis

### 5.1 Color palette (Stitch TSX → Android AppColors / Material3)

| Role | Stitch value | Android mapping | Location |
|---|---|---|---|
| Primary | `#6750A4` | `MaterialTheme.colorScheme.primary` | `Color.kt` LightColorScheme |
| Primary light variant | `#9C89C4` | `MaterialTheme.colorScheme.primaryContainer` (approx) | `Color.kt` |
| On-Primary | `#FFFFFF` | `MaterialTheme.colorScheme.onPrimary` | `Color.kt` |
| Background light | `#FFFBFE` | `MaterialTheme.colorScheme.background` | `Color.kt` |
| Background dark | `#1C1B1F` | `MaterialTheme.colorScheme.background` (dark) | `Color.kt` |
| Surface light | `#FFFFFF` (card) | `MaterialTheme.colorScheme.surface` | `Color.kt` |
| Surface dark | `#2B2930` (card) | `AppColors.surfaceTonal1Dark` (`#1E2030`) | `Color.kt` AppColors |
| Surface-container-low | `#F3EDF7` | `MaterialTheme.colorScheme.surfaceContainerLow` | `Color.kt` |
| Border light | `#E7E0EC` | `MaterialTheme.colorScheme.outlineVariant` | `Color.kt` |
| Border dark | `#49454F` | `MaterialTheme.colorScheme.outlineVariant` (dark) | `Color.kt` |
| On-surface text | `#1C1B1F` | `MaterialTheme.colorScheme.onSurface` | `Color.kt` |
| Sub-text light | `#49454F` | `MaterialTheme.colorScheme.onSurfaceVariant` | `Color.kt` |
| Sub-text dark | `#CAC4D0` | `MaterialTheme.colorScheme.onSurfaceVariant` (dark) | `Color.kt` |
| Error / destructive | `#B3261E` | `MaterialTheme.colorScheme.error` | `Color.kt` |
| AI response bg | `#E8DEF8` | `MaterialTheme.colorScheme.secondaryContainer` | `Color.kt` |
| AI response text | `#21005D` | `MaterialTheme.colorScheme.onSecondaryContainer` | `Color.kt` |
| Voice listening | `#B3261E` | `MaterialTheme.colorScheme.error` | `Color.kt` |
| Voice processing | `#386A20` | `MaterialTheme.colorScheme.tertiary` (approx) | `Color.kt` |
| Voice speaking | `#0061A4` | `MaterialTheme.colorScheme.secondary` (approx) | `Color.kt` |
| Code editor bg | `#1e1e2e` | `AppColors` — no exact token yet | Needs addition |
| Code editor text | `#cdd6f4` | `AppColors` — no exact token yet | Needs addition |
| Splash gradient start | `#1a0533` | `AppColors` — dark purple gradient | Needs addition |
| Input background (light) | `#F3EDF7` | `MaterialTheme.colorScheme.surfaceContainerLow` | `Color.kt` |
| Input background (dark) | `#2B2930` | `AppColors.surfaceTonal1Dark` | `Color.kt` |

### 5.2 Typography (Stitch TSX → Android Type.kt)

| Stitch usage | Size / Weight | Android mapping |
|---|---|---|
| Screen hero title (Splash) | 32sp w700 | `MaterialTheme.typography.headlineLarge` |
| Section h2 / modal title | 22–26sp w700 | `MaterialTheme.typography.headlineMedium` |
| Slide title (Onboarding) | 30sp w700 letter-spacing -0.5 | `MaterialTheme.typography.headlineLarge` |
| Card / list title | 15–17sp w600–700 | `MaterialTheme.typography.titleMedium` |
| Body text / description | 13–16sp w400 lh1.5–1.6 | `MaterialTheme.typography.bodyLarge` / `bodyMedium` |
| Label / button text | 14–16sp w500–600 | `MaterialTheme.typography.labelLarge` |
| Metadata / timestamps | 11–12sp w400–500 | `MaterialTheme.typography.labelSmall` |
| Code monospace | 12.5sp monospace | `AppType.codeBlock` or `AppTypeExtended` token |
| Section subheader | 12sp w600 uppercase ls0.8 | `MaterialTheme.typography.labelSmall` CAPS |
| Stat value | 16sp w700 | `MaterialTheme.typography.titleMedium` |

### 5.3 Shape tokens (Stitch TSX → Android Shape.kt)

| Stitch usage | Radius | Android mapping |
|---|---|---|
| Main CTA buttons | r26 (pill) | `CircleShape` / `50%` |
| Cards / list items | r14–r20 | `MaterialTheme.shapes.large` (16dp) / `ExtraLarge` (28dp) |
| Featured banners | r20–r32 | `MaterialTheme.shapes.extraLarge` |
| Icon containers | r12–r16 | `MaterialTheme.shapes.medium` (12dp) |
| Input fields | r12–r26 | `MaterialTheme.shapes.large` → `CircleShape` |
| Code editor | r16 | `MaterialTheme.shapes.large` |
| Logo/splash container | r28–r40 | Custom `RoundedCornerShape(28.dp)` |
| Mic button | r48 (full circle) | `CircleShape` |
| Toggle | r14 (pill) | Custom pill shape |

### 5.4 Spacing tokens

| Stitch usage | Value | Android `Spacing` equivalent |
|---|---|---|
| Screen horizontal margin | 20px | `MaterialTheme.spacing.md` (16dp) or `20.dp` |
| Section gap | 16px | `MaterialTheme.spacing.md` |
| Card internal padding | 14–20px | `MaterialTheme.spacing.md` |
| Item gap in grids | 8–12px | `MaterialTheme.spacing.sm` |
| Icon size (actions) | 40–48px | 40dp / 48dp |
| BottomNav height | 80px | 80dp |
| FAB bottom offset | 24px above nav | `MaterialTheme.spacing.lg` |

### 5.5 Animation tokens

| Stitch animation | Duration | Android equivalent |
|---|---|---|
| `fadeIn` screen enter | 600–800ms | `enterSpec<Float>()` (300ms) — compress for mobile |
| `typing-dot` bounce | 1.4s per cycle | `TypingIndicator` (`core-ui/motion/TypingIndicator.kt`) |
| `pulse-ring` expanding | 1.8s infinite | `infiniteTransition` + `animateFloat` |
| Dot width (onboarding) | 300ms ease | `animateIntAsState(tween(300))` |
| Slide content swap | CSS class-based | `AnimatedContent` with `slideInHorizontally + fadeIn` |
| Send button color | 200ms | `animateColorAsState(tween(200))` |
| Wave bars (voice) | 1.2s staggered | `infiniteTransition` + `animateFloat` per bar |
| Theme crossfade | 400ms | Already implemented in `AppTheme.kt` |

---

## 6. Required Assets

### 6.1 Vector assets needed

| Asset | Source in TSX | Android target | Notes |
|---|---|---|---|
| AI logo mark (spark/robot face) | Inline SVG `AILogoMark()` in SplashScreen | `ic_ai_logo.xml` in `core-ui/res/drawable` | Convert SVG path to VectorDrawable |
| Google sign-in logo | Inline 4-path SVG `GoogleIcon()` | `ic_google.xml` | Standard Google icon — use official asset |
| Home icon (filled/outlined) | Inline SVG `HomeIcon()` in BottomNav | `AppIcons.Home` / `AppIcons.HomeFilled` | Already in `AppIcons` object |
| Chats icon | Inline SVG `ChatsIcon()` | `AppIcons.Chat` / `AppIcons.ChatFilled` | Already in `AppIcons` object |
| Tools/grid icon | Inline SVG `ToolsIcon()` | `AppIcons.Tools` / `AppIcons.ToolsFilled` | Check `AppIcons` — may need addition |
| Profile/person icon | Inline SVG `ProfileIcon()` | `AppIcons.Person` / `AppIcons.PersonFilled` | Already in `AppIcons` object |
| Send arrow icon | Inline SVG (right arrow) | `AppIcons.Send` or `Icons.AutoMirrored.Filled.Send` | Already in Material Icons |
| Back arrow | Inline SVG (chevron left) | `Icons.AutoMirrored.Filled.ArrowBack` | Material Icons |
| 3-dot menu icon | Inline SVG (3 circles) | `Icons.Default.MoreVert` | Material Icons |

### 6.2 Image assets

| Asset | TSX usage | Android approach |
|---|---|---|
| Sample images (ImageScreen) | Unsplash URLs | Use Coil with placeholder + shimmer; no bundled bitmaps |
| Profile avatar placeholder | Initial letter circle | Compose `Canvas`-drawn initial circle — no image needed |
| Onboarding illustrations | Large emoji (96sp) | Use emoji `Text()` composable or custom illustration VectorDrawable |
| Featured banner background | Gradient `#6750A4→#9C89C4` | `Brush.linearGradient()` — no image needed |
| Splash background | `linear-gradient(160deg, ...)` | `Brush.linearGradient()` with 4 color stops |
| PDF page thumbnails | Skeleton divs | `ShimmerSkeleton` composable |

### 6.3 Font assets

| Stitch font | Usage | Android equivalent |
|---|---|---|
| Inter (400/500/600/700/800) | All body / UI text | Already bundled in `core-ui` as `MaterialTypography` |
| Fira Code (400/500) | Code editor monospace | Add `font-firacode` to `core-ui` fonts if not already present |

> **Action required:** Verify `Fira Code` is included in `core-ui/res/font/`. If missing, add as a downloadable font via `core-ui/build.gradle.kts` or bundle the `.ttf` file.

---

## 7. Existing Android Code That Can Be Reused

### 7.1 Screens — direct reuse

| Android screen | Stitch equivalent | Reuse assessment |
|---|---|---|
| `feature-auth/SplashScreen.kt` | `SplashScreen.tsx` | ✅ **High** — exists; needs gradient bg + AI logo animation |
| `feature-auth/OnboardingScreen.kt` | `OnboardingScreen.tsx` | ✅ **High** — exists; needs slide content, gradient cards, emoji heroes |
| `feature-auth/LoginScreen.kt` | `LoginScreen.tsx` | ✅ **High** — exists; needs Google button, modal-style card, password toggle |
| `app/HomeDashboard.kt` | `HomeScreen.tsx` | ✅ **High** — exists; needs quick-actions grid, featured banner, recent-chats list |
| `feature-chat/ChatDetailScreen.kt` | `ChatScreen.tsx` | ✅ **High** — exists; needs attachment buttons, suggestion chips |
| `feature-voice/VoiceScreen.kt` | `VoiceScreen.tsx` | ✅ **High** — exists; needs pulse rings, wave bars, state-color mic button |
| `feature-code/CodeEditorScreen.kt` | `CodeScreen.tsx` (editor portion) | ✅ **High** — exists; needs language picker, action chips |
| `feature-code/CodeAnalysisScreen.kt` | `CodeScreen.tsx` (result portion) | ✅ **High** — exists; needs styled result card |
| `feature-history/HistoryListScreen.kt` | `HistoryScreen.tsx` | ✅ **High** — exists; needs date groups, search, filter chips |
| `feature-settings/SettingsScreen.kt` | `SettingsScreen.tsx` | ✅ **High** — exists; needs API key section, voice settings |
| `feature-profile/ProfileScreen.kt` (via ViewModel) | `ProfileScreen.tsx` | ✅ **High** — ViewModel exists; screen may need UI update |
| `feature-rag/*` | `PDFScreen.tsx` | ✅ **Medium** — RAG screens handle documents/chat, not pixel-identical |
| `feature-camera/ImageAnalysisScreen.kt` | `ImageScreen.tsx` (result) | ✅ **Medium** — analysis screen exists; picker/analyzing states need work |
| `feature-camera/CameraCaptureScreen.kt` | `ImageScreen.tsx` (picker) | ✅ **Medium** — capture exists; gallery/sample grid missing |

### 7.2 Components — direct reuse

| Component | File | Covers Stitch element |
|---|---|---|
| `ChatBubble` | `core-ui/components/ChatBubble.kt` | AI + User message bubbles (asymmetric corners) |
| `MarkdownText` | `core-ui/components/MarkdownText.kt` | Bold/italic/code rendering in AI responses |
| `CodeBlock` | `core-ui/components/CodeBlock.kt` | Fenced code blocks in chat and code screen |
| `StreamingMessage` | `core-ui/components/StreamingMessage.kt` | Token-by-token AI response streaming |
| `TypingIndicator` | `core-ui/motion/TypingIndicator.kt` | 3-dot bouncing animation |
| `ShimmerSkeleton` | `core-ui/components/ShimmerSkeleton.kt` | Loading states (PDF thumbnails, image picker) |
| `LoadingIndicator` | `core-ui/components/LoadingIndicator.kt` | Processing spinner (ImageScreen analyzing state) |
| `SwipeRevealLayout` | `core-ui/components/SwipeRevealLayout.kt` | History screen swipe-to-delete/pin |
| `OfflineBanner` | `core-ui/components/OfflineBanner.kt` | Network error state |
| `InlineChatError` | `core-ui/components/InlineChatError.kt` | Error display in chat flow |
| `AiModeIndicator` | `core-ui/components/AiModeIndicator.kt` | Cloud/On-device mode badge |
| `MessageInputBar` | `core-ui/components/MessageInputBar.kt` | Input area (attach, camera, mic, send) |
| `SurfaceFillTextField` | `core-ui/components/SurfaceFillTextField.kt` | Search bars, Q&A input fields |
| `RagComponents` | `core-ui/components/RagComponents.kt` | Document cards in PDF screen |

### 7.3 Architecture — full reuse

| Layer | Existing class | Covers |
|---|---|---|
| AI streaming | `AIStreamClient` / `AIStreamClientImpl` | Chat token streaming |
| On-device inference | `OnDeviceInferenceClient` | On-device AI responses |
| On-device init | `OnDeviceAiInitializer` | Model download + capability check |
| Hardware capability | `HardwareCapabilityDetector` | Device capability gating |
| Embedding | `MiniLmEmbeddingModel` | RAG (PDF) semantic search |
| Inference engine | `MediaPipeInferenceEngine` | On-device LLM execution |
| Query routing | `QueryRouterImpl` | Cloud vs on-device routing |
| Stream events | `StreamEvent` sealed class | Token / Done / Error / ToolCall events |
| Navigation | `AppNavigationShell` | Adaptive nav (Bottom/Rail/Drawer) |
| Theme | `AppTheme` / `AppColors` / `AppIcons` | All color, icon, elevation tokens |
| Adaptive layouts | `AdaptiveScaffold` / `TwoPaneLayout` | Tablet/foldable layouts |
| Reduced motion | `ReducedMotion` / `LocalReducedMotionEnabled` | Accessibility animation gating |
| DataStore theme | `ThemePreferences` | Dark mode persistence (SettingsScreen toggle) |

---

## 8. Missing Android Components

### 8.1 Missing screens

| Missing | Target module | Priority | Rationale |
|---|---|---|---|
| `ToolsScreen.kt` | `feature-dashboard` or new `feature-tools` | **High** | BottomNav "AI Tools" tab has no target screen |

### 8.2 Missing UI components (need to be built in `core-ui`)

| Component | Needed for | Description |
|---|---|---|
| `QuickActionGrid` | HomeScreen, ToolsScreen | 2–4 column grid of action cards with coloured icon bg + label |
| `FeaturedBannerCard` | HomeScreen, ToolsScreen | Gradient card with overlay circles, title, body, CTA button |
| `RecentChatRow` | HomeScreen | Icon + title + preview + time row for chat previews |
| `SplashGradientBackground` | SplashScreen | 4-stop diagonal gradient with ambient blobs |
| `OnboardingSlide` | OnboardingScreen | Gradient illustration card + content area layout |
| `OnboardingProgressDots` | OnboardingScreen | Animated expanding/contracting dot row |
| `VoicePulseRing` | VoiceScreen | 3 expanding rings, `#6750A4`, staggered animation |
| `VoiceWaveVisualizer` | VoiceScreen | N-bar animated sound wave canvas |
| `VoiceTranscriptCard` | VoiceScreen | "You said" card + "AI Response" tinted card |
| `ProfileHeaderBand` | ProfileScreen | Gradient header with avatar, name, email, plan badge |
| `ProfileStatCard` | ProfileScreen | 4-column stats grid with hairline dividers |
| `ProfileMenuItem` | ProfileScreen | Row with icon container, title, desc, chevron, optional badge |
| `CodeEditorDark` | CodeScreen | Dark `#1e1e2e` container with traffic-light dots + monospace textarea |
| `LanguagePickerChipRow` | CodeScreen | Horizontal wrap of language selection chips |
| `CodeActionChipGrid` | CodeScreen | 4-column action button grid with active color state |
| `DocumentUploadZone` | PDFScreen | Dashed-border drop zone with icon and label |
| `DocumentListRow` | PDFScreen | PDF icon + name + meta + chevron |
| `PdfPageThumbnailStrip` | PDFScreen | Horizontal scroll of skeleton page previews |
| `ImageAnalysisProgressList` | ImageScreen | 4-step analysis progress with check/loading/empty indicators |
| `ResultCard` | ImageScreen | Tinted header + white body card for analysis results |
| `HistoryFilterChips` | HistoryScreen | All / Pinned filter chips |
| `HistoryGroupHeader` | HistoryScreen | Date-group section label (Today / Yesterday / Earlier) |
| `HistoryContextMenu` | HistoryScreen | Floating action menu (Pin / Rename / Delete) |
| `SearchInputBar` | HistoryScreen, ToolsScreen | Standalone search input bar (not MessageInputBar) |
| `SettingsSection` | SettingsScreen | Group card with icon + UPPERCASE title + child rows |
| `SettingsToggleRow` | SettingsScreen | Label + sub + custom Toggle aligned right |
| `SettingsDangerRow` | SettingsScreen | Destructive-colored settings row |
| `ApiKeyInputRow` | SettingsScreen | Password input + save button within a settings section |
| `GradientAvatarCircle` | ProfileScreen, HomeScreen | User initial inside gradient circle |

### 8.3 Missing navigation

| Gap | Action needed |
|---|---|
| No `ToolsRoute` / `ToolsNavigation.kt` | Create dedicated ToolsScreen + register in rootNavHost |
| BottomNav "AI Tools" tab navigates to `HomeDashboard` | Update `AppNavigationBar` destination to `ToolsRoute` |
| `HistoryScreen` search is inline (Stitch) vs separate screen (Android) | Consider merging into `HistoryListScreen` with collapsible search |

### 8.4 Missing design tokens

| Token | Value needed | Location |
|---|---|---|
| Code editor background | `#1e1e2e` | `AppColors` — add `codeEditorBg` |
| Code editor text | `#cdd6f4` | `AppColors` — add `codeEditorFg` |
| Code editor syntax colors | pink, blue, yellow, green, gray, purple | `AppColors` — add syntax color group |
| Splash dark gradient stops | `#1a0533`, `#381E72` | `AppColors` — add `splashGradientStart` / `Mid` |
| Voice listening ring | `rgba(103,80,164,0.15)` | `AppColors` — add `voicePulseRing` |
| Sign-out destructive bg | `#FFD7D4` | Use `MaterialTheme.colorScheme.errorContainer` |
| AI response bubble bg | `#E8DEF8` | `MaterialTheme.colorScheme.secondaryContainer` ✅ already exists |

---

## 9. AI Architecture Integration Points

### 9.1 ChatScreen → AIStreamClient

```
ChatDetailScreen.kt
  └─ ChatDetailViewModel
       └─ SendMessageUseCase
            └─ AIStreamClient (interface)
                 ├─ AIStreamClientImpl  (Cloud — WebSocket)
                 └─ OnDeviceInferenceClient (On-device — MediaPipe)
                      └─ StreamEvent sealed class
                           ├─ Token(text)    → StreamingMessage composable
                           ├─ Done(usage)    → token count display
                           └─ Error(message) → InlineChatError composable
```

Design integration: `ChatScreen.tsx` shows streaming text (FormattedText component). Maps directly to `StreamingMessage` + `MarkdownText` composables driven by `StateFlow<ChatDetailUiState>`.

### 9.2 VoiceScreen → VoiceViewModel

```
VoiceScreen.kt
  └─ VoiceViewModel
       └─ SendMessageUseCase  (audio → text → AI → response)
            ├─ idle → listening → processing → speaking states
            └─ Transcript + AI response displayed in state cards
```

Design integration: 4-state machine in Stitch maps directly to `VoiceUiState` sealed class in `VoiceViewModel`. Pulse ring + wave bar animations driven by `state == listening/speaking`.

### 9.3 PDFScreen → RAG pipeline

```
DocumentChatScreen.kt (feature-rag)
  └─ DocumentChatViewModel
       ├─ QueryDocumentUseCase
       │    └─ MiniLmEmbeddingModel (on-device embeddings)
       │         └─ LocalVectorIndexImpl (cosine similarity search)
       └─ UploadDocumentUseCase
            └─ RAGViewModel
```

Design integration: PDF upload zone → `UploadDocumentUseCase`. Document list → `RAGViewModel.documents`. Q&A → `QueryDocumentUseCase` → streaming answer via `AIStreamClient`.

### 9.4 ImageScreen → CameraViewModel

```
ImageAnalysisScreen.kt (feature-camera)
  └─ CameraViewModel
       └─ Analyze image (Cloud Vision / on-device OCR)
            ├─ Object detection results → ResultCard (Description)
            ├─ OCR results            → ResultCard (Extracted Text)
            └─ "Ask about this" → navigate to ChatDetailScreen with image context
```

Design integration: 3-state flow (picker / analyzing / result) in Stitch maps to `CameraUiState`. Progress steps (4 items) are UI feedback during cloud analysis.

### 9.5 CodeScreen → CodeViewModel

```
CodeEditorScreen.kt + CodeAnalysisScreen.kt (feature-code)
  └─ CodeViewModel
       └─ AnalyzeCodeUseCase
            └─ AIStreamClient
                 └─ StreamEvent.Token → CodeBlock / MarkdownText rendering
```

Design integration: 4 action buttons (Explain / Fix / Optimize / Gen Tests) map to 4 distinct prompts passed to `AnalyzeCodeUseCase`. Active action state drives button highlight color.

### 9.6 AiModeIndicator integration points

The `AiModeIndicator` composable (showing Cloud / On-device badge) should appear in:
- `HomeDashboard.kt` top bar (currently implemented per code comments)
- `ChatDetailScreen.kt` header
- `CodeEditorScreen.kt` header (currently shows "GPT-4.5 Ultra" in Stitch — map to `AiModeIndicator`)
- `VoiceScreen.kt` header

The `OnDeviceCapabilityChecker` + `OnDeviceAiInitializer` gate whether on-device inference is available. `ModelStatusCard` composable surfaces download/ready/error state in SettingsScreen.

### 9.7 QueryRouter integration

`QueryRouterImpl.evaluate(capabilityBitmask, userPreference)` routes each request:
- `RoutingDecision.CLOUD` → `AIStreamClientImpl` (WebSocket to backend)
- `RoutingDecision.ON_DEVICE` → `OnDeviceInferenceClient` (MediaPipe/llama.cpp)
- `RoutingDecision.HYBRID` → on-device with cloud fallback

The Stitch design does not expose this routing — it is transparent to the UI. The `AiModeIndicator` is the only design surface for this.

---

## 10. Recommended Migration Order

Migrations are ordered by: (1) foundational → feature screens, (2) risk (auth first so app is runnable), (3) complexity ascending.

### Phase A — Foundation (Do first, unblocks everything)

| # | Task | Rationale |
|---|---|---|
| A1 | Add missing `AppColors` tokens (code editor, splash gradient, voice pulse) | All screens need correct tokens before implementing |
| A2 | Verify / add `Fira Code` font in `core-ui` | `CodeScreen` requires monospace font |
| A3 | Build `QuickActionGrid` composable in `core-ui` | Used by HomeScreen and ToolsScreen |
| A4 | Build `FeaturedBannerCard` composable in `core-ui` | Used by HomeScreen and ToolsScreen |
| A5 | Build `SearchInputBar` composable in `core-ui` | Used by History and Tools |
| A6 | Build `GradientAvatarCircle` composable in `core-ui` | Used by Profile and Home |

### Phase B — Auth flow (SplashScreen → OnboardingScreen → LoginScreen)

| # | Task | Module | Key components needed |
|---|---|---|---|
| B1 | Update `SplashScreen.kt` | `feature-auth` | `SplashGradientBackground`, AI logo SVG, `typing-dot` animation |
| B2 | Update `OnboardingScreen.kt` | `feature-auth` | `OnboardingSlide`, `OnboardingProgressDots`, gradient illustration card |
| B3 | Update `LoginScreen.kt` | `feature-auth` | Google button, `SurfaceFillTextField`, show/hide password, mode toggle |

### Phase C — Home Dashboard

| # | Task | Module | Key components needed |
|---|---|---|---|
| C1 | Update `HomeDashboard.kt` | `app` | `QuickActionGrid`, `FeaturedBannerCard`, `RecentChatRow`, greeting top bar |
| C2 | Create `ToolsScreen.kt` + `ToolsNavigation.kt` | `feature-dashboard` | Tool grid 2-col, `FeaturedBannerCard`, `SearchInputBar` |
| C3 | Update `AppNavigationBar` AI Tools destination | `app` | Point to `ToolsRoute` instead of `HomeDashboard` |

### Phase D — Chat & History (Core feature screens)

| # | Task | Module | Key components needed |
|---|---|---|---|
| D1 | Update `ChatDetailScreen.kt` | `feature-chat` | Attachment buttons, suggestion chip row, send button state, `MarkdownText` |
| D2 | Update `HistoryListScreen.kt` | `feature-history` | `HistoryFilterChips`, `HistoryGroupHeader`, `HistoryContextMenu`, `SearchInputBar` |

### Phase E — AI Tool screens

| # | Task | Module | Key components needed |
|---|---|---|---|
| E1 | Update `VoiceScreen.kt` | `feature-voice` | `VoicePulseRing`, `VoiceWaveVisualizer`, `VoiceTranscriptCard`, state-color mic |
| E2 | Update `CodeEditorScreen.kt` | `feature-code` | `CodeEditorDark`, `LanguagePickerChipRow`, `CodeActionChipGrid` |
| E3 | Update `CodeAnalysisScreen.kt` | `feature-code` | Styled result card, `CodeBlock`, copy/share buttons |
| E4 | Update `ImageAnalysisScreen.kt` + `CameraCaptureScreen.kt` | `feature-camera` | `ImageAnalysisProgressList`, `ResultCard`, sample image grid |
| E5 | Update RAG/PDF screens | `feature-rag` | `DocumentUploadZone`, `DocumentListRow`, `PdfPageThumbnailStrip`, Q&A input |

### Phase F — Profile & Settings

| # | Task | Module | Key components needed |
|---|---|---|---|
| F1 | Build `ProfileScreen.kt` UI | `feature-profile` | `ProfileHeaderBand`, `ProfileStatCard`, `ProfileMenuItem`, sign-out button |
| F2 | Update `SettingsScreen.kt` | `feature-settings` | `SettingsSection`, `SettingsToggleRow`, `ApiKeyInputRow`, language picker |

### Phase G — Polish

| # | Task | Notes |
|---|---|---|
| G1 | Dark mode audit across all screens | All Stitch screens support dark — verify all tokens swap correctly via `AppTheme` |
| G2 | Reduced-motion pass | Gate all animations behind `LocalReducedMotionEnabled` |
| G3 | Accessibility pass | `contentDescription` on all icons; screen reader order; contrast audit |
| G4 | Adaptive layout validation | Tablet two-pane for History+Chat, Settings+Profile (already `TwoPaneLayout` exists) |

---

## Summary

| Category | Count | Status |
|---|---|---|
| Stitch screens analyzed | 13 + 1 shared | ✅ Complete |
| Existing Android screens matched | 13 / 14 | ✅ (ToolsScreen missing) |
| core-ui components available for reuse | 18 | ✅ |
| New components to build | 28 | ❌ Not yet created |
| Missing navigation destination | 1 (ToolsRoute) | ❌ |
| Missing AppColors tokens | 7 | ❌ |
| Font verification needed | 1 (Fira Code) | ⚠️ Verify |
| Migration phases | 7 (A–G) | Ordered by dependency |

> **Next step:** Say `NEXT` to begin Phase A implementation — adding missing design tokens and building foundation components in `core-ui`.
