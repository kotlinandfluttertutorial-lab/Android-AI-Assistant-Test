# Implementation Plan: Android AI Assistant (Enterprise Edition)

## Overview

Implementation follows Clean Architecture from the ground up: core modules first, then domain, then data, then feature modules, then the FastAPI backend, infrastructure, and observability layers. Each task builds on the previous so no component is left unconnected at any integration point.

## Tasks

- [x] 1. Bootstrap Android project structure and Gradle multi-module setup
  - Create all Gradle modules: `app`, `core-ui`, `core-network`, `core-database`, `core-ai`, `core-security`, `core-common`, `feature-auth`, `feature-chat`, `feature-rag`, `feature-camera`, `feature-code`, `feature-voice`, `feature-settings`, `feature-profile`, `feature-history`, `feature-notes`, `feature-meeting`, `feature-resume`, `feature-email`, `feature-translator`, `feature-productivity`, `domain`, `data`
  - Configure `settings.gradle.kts` with version catalog (`libs.versions.toml`)
  - Enforce unidirectional dependency rule in each module's `build.gradle.kts`: `feature` → `domain`/`core`; `domain` ← never `data`/`feature`; `data` → never `feature`
  - Set up Hilt in `app` module and all feature/data modules; no manual cross-module instantiation
  - Configure ktlint and Detekt with zero-error CI rules
  - Configure JaCoCo for `domain` and `data` modules (combined minimum 70% coverage)
  - _Requirements: 19.1, 19.2, 19.3, 19.4, 19.5, 19.6_

- [x] 2. Implement `core-common` module
  - [x] 2.1 Create shared utilities, extension functions, coroutine dispatchers, and base sealed classes
    - `DispatcherProvider` interface + `DefaultDispatcherProvider` for testability
    - `DomainError` sealed class covering all error cases: `NetworkError`, `NetworkUnavailable`, `Unauthorized`, `Forbidden`, `ValidationError`, `ServerError`, `StreamingInterrupted`, `BiometricFailed`, `OfflineQueueFull`
    - `ApiResult<T>` sealed class (`Success`, `Error`, `Loading`, `NetworkUnavailable`) with `map`, `flatMap`, and `fold` operators
    - _Requirements: 19.4_
  - [x] 2.2 Write unit tests for extension functions and `ApiResult` transformations
    - Test `map`, `flatMap`, `fold` operators on every `ApiResult` variant
    - _Requirements: 19.4, 21.1_

- [x] 3. Implement `core-ui` design system
  - [x] 3.1 Create Material Design 3 theme, color tokens, typography scale, and shape system
    - `AppTheme` composable supporting Light, Dark, and System-default modes; theme persisted in DataStore
    - 8 dp spacing system constants throughout
    - Material You dynamic color support for Android 12+ (wallpaper-derived palette)
    - _Requirements: 24.1, 24.2, 24.3_
  - [x] 3.2 Build shared reusable composables
    - `ChatBubble`, `LoadingIndicator`, `MarkdownText` (headers/bold/italic/inline code/fenced code/tables/bullet lists), `CodeBlock` with syntax highlighting, `ErrorBanner`, `OfflineBanner`
    - Every composable includes a `contentDescription` parameter enabling TalkBack navigation
    - Minimum contrast ratio 4.5:1 for normal text, 3:1 for large text; no color-only status indicators — icons or text accompany all color-coded states
    - _Requirements: 2.5, 23.1, 23.2, 23.4_
  - [x] 3.3 Implement adaptive and responsive layouts
    - Two-pane layout for Chat and History screens on tablets (≥600 dp width)
    - Foldable device posture handling: fold/unfold transitions do not drop state
    - Dynamic text scaling support up to 200% without truncation or overflow on any primary screen
    - Keyboard navigation with logical focus order on all screens
    - _Requirements: 23.3, 23.5, 24.4, 24.5_
  - [x] 3.4 Write Compose UI tests for design system composables
    - Test theme switching (light/dark), `MarkdownText` rendering all six node types, `CodeBlock` language display, adaptive layout breakpoints at 600 dp, contrast ratio compliance
    - _Requirements: 24.2, 21.3, 31.5_

- [x] 4. Implement `core-security` module
  - [x] 4.1 Implement `SecureStorage` (EncryptedSharedPreferences wrapper) and `BiometricManager`
    - `SecureStorage` wraps `EncryptedSharedPreferences` for JWT and refresh token storage; never exposes raw key values
    - `BiometricManager` uses `BiometricPrompt` to unlock local session without transmitting biometric data to any server
    - Root detection utility to warn user on compromised device
    - Add `core-security/src/main/res/values/strings.xml` with all required string resources: `biometric_prompt_title`, `biometric_prompt_subtitle`, `biometric_prompt_negative_button`, `biometric_not_available`, `biometric_not_enrolled`
    - _Requirements: 9.4, 1.7, 30.3_
  - [x] 4.2 Write unit tests for `SecureStorage` read/write/clear operations
    - Use Robolectric for EncryptedSharedPreferences; test that written tokens survive read and that clear removes them
    - _Requirements: 9.4, 21.1_


- [x] 5. Implement `core-network` module
  - [x] 5.1 Configure Retrofit + OkHttp with certificate pinning, JWT auth interceptor, and refresh token interceptor
    - `CertificatePinningInterceptor` — rejects connections where server certificate does not match pinned certificate
    - `AuthInterceptor` — attaches `Bearer` JWT to every authenticated request
    - `RefreshTokenInterceptor` — catches HTTP 401, calls `POST /auth/refresh`, retries original request; on refresh failure clears credentials and navigates to Login
    - `NetworkModule` Hilt module exposing configured `OkHttpClient` and `Retrofit`
    - _Requirements: 9.5, 1.3_
  - [x] 5.2 Write unit tests for interceptors using MockWebServer
    - Test cert pinning rejection, token refresh retry on 401, and propagation of 401 when refresh fails
    - _Requirements: 9.5, 21.1_

- [x] 6. Implement `core-database` Room module
  - [x] 6.1 Define all Room entities, DAOs, type converters, and migrations
    - Entities: `UserEntity`, `ConversationEntity`, `MessageEntity`, `DocumentEntity`, `MemoryEntity`, `NoteEntity`, `TodoItemEntity`, `CalendarEventEntity`, `ReminderEntity`, `HabitDefinitionEntity`, `HabitEntryEntity`
    - DAOs: `ConversationDao` and `MessageDao` with FTS4 virtual table for full-text search; `TodoItemDao`, `CalendarEventDao`, `ReminderDao`, `HabitDefinitionDao`, `HabitEntryDao`
    - Type converters for `List<String>` ↔ JSON and `Long` ↔ `Instant`
    - `DatabaseModule` Hilt module exposing `AppDatabase`
    - _Requirements: 10.1, 11.2, 19.1_
  - [x] 6.2 Write unit tests for DAO operations using an in-memory Room database
    - Test FTS search returns matches; test cascade delete on conversation removes messages
    - _Requirements: 11.2, 21.1_

- [x] 7. Implement `core-ai` WebSocket streaming module
  - [x] 7.1 Implement `AIStreamClient` interface with OkHttp WebSocket, token event parsing, and exponential backoff reconnection
    - Parse structured events: `{"type":"token","data":"..."}`, `{"type":"done","usage":{...}}`, `{"type":"error","message":"..."}`, `{"type":"tool_call","toolName":"...","toolInput":{...}}`
    - `StreamEvent` sealed class: `Token(text)`, `Done(usage)`, `Error(message)`, `ToolCall(toolName, toolInput)`
    - Reconnect with exponential backoff: start 1 s, double each attempt, cap at 30 s, maximum 5 total attempts
    - _Requirements: 26.4, 26.5, 2.8_
  - [x] 7.2 Write property test for WebSocket exponential backoff intervals (Property 27)
    - **Property 27: WebSocket Reconnection Backoff** — **Validates: Requirements 26.4**
    - Use Kotest PropTest; generate N ∈ [1, 5] disconnect events; assert intervals are 1 s → 2 s → 4 s → 8 s → 16 s capped at 30 s; assert ≤5 attempts total
  - [x] 7.3 Write property test for WebSocket event schema conformance (Property 28)
    - **Property 28: WebSocket Event Schema Conformance** — **Validates: Requirements 26.5**
    - Use Kotest PropTest; generate arbitrary JSON payloads; assert every valid-format message parses to exactly one `StreamEvent` subtype; assert malformed messages surface as `StreamEvent.Error`

- [x] 8. Implement `domain` module — entities, repository interfaces, and use cases
  - [x] 8.1 Define domain entities and repository interfaces
    - Entities: `User`, `Conversation`, `Message`, `Document`, `Memory`, `Note`, `MCPTool`, `TodoItem`, `CalendarEvent`, `Reminder`, `HabitDefinition`, `HabitEntry` — pure Kotlin, zero Android/third-party dependencies
    - Repository interfaces: `AuthRepository`, `ConversationRepository`, `MessageRepository`, `DocumentRepository`, `MemoryRepository`, `NoteRepository`, `UserRepository`, `ProductivityRepository`
    - Add `javax.inject:javax.inject:1` dependency to `domain/build.gradle.kts` so use cases can use `@Inject` without pulling in Android framework
    - _Requirements: 19.2, 30.1_
  - [x] 8.2 Implement authentication use cases
    - `LoginUseCase`, `RegisterUseCase`, `RefreshTokenUseCase`
    - `RegisterUseCase` validates email format (RFC 5321) and password length ≥ 12 characters before calling `AuthRepository`
    - _Requirements: 1.1, 1.2, 1.3_
  - [x] 8.3 Write unit tests for authentication use cases
    - Mock `AuthRepository`; verify correct token outputs, email validation errors, password length rejection; verify happy path and primary error path
    - _Requirements: 21.1, 31.2_
  - [x] 8.4 Implement conversation and message use cases
    - `GetConversationsUseCase` (paginated, sorted by `updatedAt`, grouped by date), `CreateConversationUseCase`, `DeleteConversationUseCase` (soft-delete), `SearchConversationsUseCase`
    - `SendMessageUseCase`, `RegenerateMessageUseCase`, `ExportConversationUseCase` (Markdown + PDF), `SyncOfflineQueueUseCase`
    - _Requirements: 11.1, 11.3, 11.4, 11.6, 2.6, 2.7, 10.2_
  - [x] 8.5 Write unit tests for conversation and message use cases
    - Verify FTS search filtering, soft-delete behavior, export format output, and offline queue ordering; verify happy path and primary error path per use case
    - _Requirements: 21.1, 31.2_
  - [x] 8.6 Implement document, memory, notes, meeting, translation, and productivity use cases
    - `UploadDocumentUseCase`, `QueryDocumentUseCase`, `DeleteDocumentUseCase`
    - `GetMemoriesUseCase`, `DeleteMemoryUseCase`
    - `SaveNoteUseCase`, `SummarizeNoteUseCase`, `RewriteNoteUseCase`
    - `GenerateResumeUseCase`, `GenerateEmailUseCase`
    - `StartMeetingRecordingUseCase`, `StopMeetingRecordingUseCase`, `GetMeetingSummaryUseCase`
    - `TranslateTextUseCase`
    - `CreateTodoUseCase`, `UpdateTodoUseCase`, `DeleteTodoUseCase`, `GetTodosUseCase`, `GenerateTodosFromPromptUseCase`
    - `CreateCalendarEventUseCase`, `GetCalendarEventsUseCase`, `DeleteCalendarEventUseCase`
    - `CreateReminderUseCase`, `UpdateReminderUseCase`, `DeleteReminderUseCase`, `GetRemindersUseCase`, `SuggestReminderUseCase`
    - `CreateHabitUseCase`, `LogHabitEntryUseCase`, `GetHabitInsightsUseCase` (enforce minimum 7 days of logged entries), `DeleteHabitUseCase`
    - _Requirements: 4.1, 4.6, 7.3, 7.4, 13.1, 14.1, 14.4, 10.5, 19.1, 29.1, 29.7_
  - [x] 8.7 Write unit tests for document, memory, notes, meeting, translation, and productivity use cases
    - Mock all repositories; verify pre-conditions and output shapes for each use case; verify happy path and primary error path
    - Verify `GetHabitInsightsUseCase` returns an error when fewer than 7 days of entries exist
    - _Requirements: 21.1, 29.7, 31.2_

- [x] 9. Implement `data` module — repositories and data sources
  - [x] 9.1 Implement `AuthRepositoryImpl` with local and remote data sources
    - Local: `SecureStorage` for JWT and refresh token persistence
    - Remote: Retrofit service for `/auth/*` endpoints
    - On logout, call `POST /auth/logout` to invalidate all session refresh tokens
    - _Requirements: 1.1, 1.2, 1.3, 1.10_
  - [x] 9.2 Write unit tests for `AuthRepositoryImpl`
    - Mock `SecureStorage` and Retrofit service; verify token storage, rotation, and logout invalidation; verify local-first emission, remote sync trigger, conflict resolution
    - _Requirements: 21.1, 31.3_
  - [x] 9.3 Implement `ConversationRepositoryImpl` and `MessageRepositoryImpl`
    - Local Room DAOs as single source of truth; always emit local data first
    - Remote Retrofit services for background sync; conflict resolution: server-wins for `Message` content; local-wins for User preferences
    - `ConnectivityObserver` gates all remote calls
    - _Requirements: 10.1, 10.3, 11.1_
  - [x] 9.4 Write property test for offline sync conflict resolution (Property 17)
    - **Property 17: Conflict-Free Offline Sync** — **Validates: Requirements 10.3**
    - Use Kotest PropTest; generate random sequences of offline messages; after sync assert server state contains all queued messages in original order; assert local Room reflects server authoritative state
  - [x] 9.5 Implement `DocumentRepositoryImpl`, `MemoryRepositoryImpl`, `NoteRepositoryImpl`, and `ProductivityRepositoryImpl`
    - `DocumentRepositoryImpl`: multipart upload to `POST /documents`; poll `GET /jobs/{id}` for ingestion status; `syncStatus` transitions: `pending` → `processing` → `ready` / `failed`
    - `MemoryRepositoryImpl`: remote CRUD backed by `/memory/*` endpoints; no local cache (sensitive data)
    - `NoteRepositoryImpl`: local Room + remote sync with `syncStatus` field
    - `ProductivityRepositoryImpl`: local-first with server sync for all four sub-types; conflict resolution uses last-write-wins (`updated_at`); `CalendarEvent` additionally sourced from Google Calendar MCP connector when connected
    - _Requirements: 4.1, 4.10, 7.4, 13.4, 29.9_
  - [x] 9.6 Write unit tests for `DocumentRepositoryImpl` and `NoteRepositoryImpl`
    - Verify all `syncStatus` state transitions; verify delete clears both local cache and remote entries; verify local-first emission, remote sync trigger, conflict resolution
    - _Requirements: 21.1, 31.3_

- [x] 10. Checkpoint — Android core, domain, and data layers
  - Ensure all unit tests pass. Verify JaCoCo combined coverage ≥70% across `domain` and `data`. Ask the user if questions arise.


- [x] 11. Implement `feature-auth` module
  - [x] 11.1 Build Splash, Onboarding, Login, Register, and Biometric unlock screens
    - `AuthViewModel` with `StateFlow<AuthUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Splash screen displayed while application initializes (cold-start ≤2 s on Snapdragon 700 series)
    - Onboarding: display privacy policy and terms of service; require affirmative tap before enabling optional data collection; request notification permission; deny → suppress all push notifications for lifetime of installation
    - Login with email/password inline validation (valid email format, ≥12 and ≤128 character password)
    - Google OAuth2 sign-in button mapping Google account to local user record on first sign-in
    - Biometric prompt unlocking local session via `BiometricManager`; session valid for 15 minutes, re-prompt on expiry
    - _Requirements: 1.1, 1.6, 1.7, 16.3, 17.1, 28.3_
  - [x] 11.2 Write Compose UI tests for auth flows
    - Email validation error display; biometric prompt trigger; navigation to Home on success; privacy policy display; notification permission request dialog; consent gate blocks access until confirmed
    - _Requirements: 21.3_

- [x] 12. Implement `feature-chat` module
  - [x] 12.1 Build ChatList screen with conversation date grouping and Paging 3
    - `ChatViewModel` with `StateFlow<ChatListUiState>`; annotated `@HiltViewModel` with `@Inject` constructor; conversations sorted by `updatedAt`
    - Group conversations into: Today / Yesterday / Last 7 Days / Older
    - Pin, rename, and soft-delete actions per conversation
    - Load 20 conversations per page using Paging 3
    - Persistent `OfflineBanner` when `ConnectivityObserver` reports no network
    - _Requirements: 11.1, 11.3, 11.5, 10.4, 17.6_
  - [x] 12.2 Write property test for conversation date grouping invariant (Property 18)
    - **Property 18: Conversation Date Grouping Invariant** — **Validates: Requirements 11.5**
    - Use Kotest PropTest; generate `Conversation` lists with random `updatedAt` timestamps; assert each conversation lands in exactly one correct group
  - [x] 12.3 Build ChatDetail screen with WebSocket streaming and Markdown rendering
    - Connect `AIStreamClient`; display tokens incrementally as they arrive
    - Reject outgoing messages exceeding 32,000 characters with inline error before sending
    - Show typing indicator until first token of `Streaming_Response` is received
    - Render assistant messages via `MarkdownText` composable (headers, bold, italic, code blocks, tables, bullet lists)
    - Regenerate action produces new response appended as alternative (maximum 5 alternatives per message)
    - Copy / share / export individual message as plain text or Markdown; "share" invokes device-native share mechanism
    - Display retry option when `StreamingInterrupted` error occurs; discard partial response if User does not reconnect within 5 minutes
    - _Requirements: 2.1, 2.2, 2.5, 2.6, 2.7, 2.8, 2.10_
  - [x] 12.4 Write Compose UI tests for chat send, streaming display, and copy action
    - Mock `AIStreamClient`; assert tokens render incrementally; assert typing indicator disappears on first token; assert copy action writes to clipboard
    - _Requirements: 21.3_

- [x] 13. Implement `feature-voice` module
  - [x] 13.1 Build VoiceAssistant screen with STT, TTS, and wake word integration
    - `VoiceViewModel` state machine: `Idle` → `Listening` → `Transcribing` → `Speaking` → `Idle`; annotated `@HiltViewModel` with `@Inject` constructor
    - On-device `SpeechRecognizer`; detect end-of-speech after 1,500 ms of silence; display transcript with low-confidence visual indicator
    - STT timeout: stop listening and show error if no transcript within 10 seconds
    - Submit transcript as `Message` to `SendMessageUseCase`; display in Voice screen
    - Convert AI response to speech via `TextToSpeech` with User-selected voice profile; fall back to device default
    - TTS failure: display response text on screen with error indicator
    - Interrupt control: stop TTS playback within 300 ms and re-activate microphone
    - Wake word activation when device supports it
    - Show permission rationale dialog and settings deep-link if microphone permission not granted
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 5.8_
  - [x] 13.2 Write unit tests for `VoiceViewModel` state transitions
    - Test full idle→listening→transcribing→speaking→idle cycle; verify permission denied branches to rationale state; verify primary success and primary error `StateFlow` emissions using Turbine
    - _Requirements: 21.1, 31.4_

- [x] 14. Implement `feature-rag` module
  - [x] 14.1 Build DocumentList screen with upload status and Paging 3
    - `RAGViewModel` with `StateFlow<RAGUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - File picker bottom sheet accepting PDF, DOCX, TXT, Markdown; reject files > 50 MB with inline error
    - Display ingestion status badge per document: `pending` / `processing` / `ready` / `failed`
    - Poll `GET /jobs/{job_id}` periodically while status is `pending` or `processing`
    - _Requirements: 4.1, 27.2, 27.5_
  - [x] 14.2 Build DocumentChat screen for RAG-augmented queries
    - Submit query via `QueryDocumentUseCase`; display cited response showing source document name and page number for every retrieved chunk
    - _Requirements: 4.6, 4.7_
  - [x] 14.3 Write unit tests for `RAGViewModel` upload and status polling
    - Verify status state transitions (`pending`→`processing`→`ready`/`failed`); verify files >50 MB show rejection error; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_

- [x] 15. Implement `feature-camera` module
  - [x] 15.1 Build CameraCapture, ImageAnalysis, and OCRResult screens
    - `CameraViewModel` with `StateFlow<CameraUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - CameraX capture or gallery picker; accept JPEG, PNG, WebP; enforce max 4096×4096 pixels and 10 MB; reject oversized/wrong-format with error
    - Show camera/gallery permission rationale dialog and settings deep-link if permission not granted
    - Show analysis progress indicator; transmit image to Backend within 3 seconds
    - Display OCR extracted text with bounding box overlays; show `no_text_found` indicator when no text detected
    - Barcode and QR code scanning returning decoded payload as a `Message` in the active Conversation
    - Show structured capability-gap error if active LLM provider does not support vision input
    - Pass image + User prompt to AI Orchestrator when vision-capable provider is active
    - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5, 6.6, 6.7_
  - [x] 15.2 Write unit tests for `CameraViewModel` image submission and error states
    - Test resolution rejection, progress indicator trigger, vision-incapable provider error, QR decode result; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_

- [x] 16. Implement `feature-code` module
  - [x] 16.1 Build CodeEditor and CodeAnalysis screens
    - `CodeViewModel` with `StateFlow<CodeUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Syntax-highlighted editor composable: Kotlin, Java, Python, JavaScript, C++, SQL; max 500 lines or 50,000 characters
    - Reject empty code blocks or inputs exceeding limits with a structured error before sending
    - Submit code block for: explanation (three labeled sections), bug fix (corrected code + inline change comments), unit test generation (AAA pattern, min 1 test per function/method)
    - Render correct syntax highlighting using language identifier from AI response
    - Single-tap copy to clipboard
    - _Requirements: 12.1, 12.2, 12.3, 12.4, 12.5, 12.6, 12.7_
  - [x] 16.2 Write property test for code response language identifier (Property 30)
    - **Property 30: Code Response Language Identifier** — **Validates: Requirements 12.6**
    - Use Kotest PropTest; generate code analysis requests across all six supported languages; assert every response contains a non-empty `languageId` matching one of the supported identifiers

- [x] 17. Implement `feature-notes` module
  - [x] 17.1 Build NotesList and NoteEditor screens
    - `NotesViewModel` with `StateFlow<NotesUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Plain text and Markdown editor with live split-pane preview; max 50,000 characters; preview renders within 500 ms
    - User-defined tag labels (max 50 chars/label, max 50 tags/note); filter notes list by selected tag
    - Persist notes in local Room database; sync to Backend when connected with `syncStatus` field
    - AI summarize action (≤150 words, preserving all key facts; truncate to exactly 150 words if exceeded)
    - AI rewrite action (learned writing style from Memory_Service if available; neutral professional style as fallback)
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_
  - [x] 17.2 Write property test for AI summary word limit (Property 19)
    - **Property 19: AI Summary Word Limit** — **Validates: Requirements 13.2**
    - Use Kotest PropTest; generate notes with random content; mock `SummarizeNoteUseCase`; assert returned summary word count ≤ 150 for all inputs
  - [x] 17.3 Write property test for notes tag filter invariant (Property 20)
    - **Property 20: Notes Tag Filter Invariant** — **Validates: Requirements 13.5**
    - Use Kotest PropTest; generate note lists with random tag assignments; apply random single-tag filter; assert every note in result contains the filter tag; assert no note without the filter tag appears

- [x] 18. Implement `feature-resume` and `feature-email` modules
  - [x] 18.1 Build ResumeBuilder and CoverLetterEditor screens
    - `ResumeViewModel` with `StateFlow<ResumeUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Input: at least one work experience + contact info + target job description; output: ATS-optimized resume in Markdown within 30 seconds
    - Require both job description and resume data for cover letter; return HTTP 422 with missing-field identification if either is absent
    - Cover letter generation tailored to provided job description (≤400 words)
    - Export generated resume or cover letter as PDF or DOCX to Downloads folder; display error if conversion fails
    - _Requirements: 14.1, 14.2, 14.3, 14.7_
  - [x] 18.2 Write property test for cover letter word limit (Property 21)
    - **Property 21: Cover Letter Word Limit** — **Validates: Requirements 14.2**
    - Use Kotest PropTest; generate random resume and job description pairs; assert returned cover letter word count ≤ 400
  - [x] 18.3 Build EmailComposer and GrammarCorrection screens
    - `EmailViewModel` with `StateFlow<EmailUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Generate professional email: subject line, greeting, body (max 300 words), closing — from User-provided context and intent
    - Grammar correction with inline diff view highlighting all changes; return `no_changes_needed` indicator if no changes required
    - _Requirements: 14.4, 14.5, 14.6_
  - [x] 18.4 Write property test for email generation structure (Property 22)
    - **Property 22: Email Generation Structure** — **Validates: Requirements 14.4**
    - Use Kotest PropTest; generate random email context inputs; assert every response contains all four required components: subject line, greeting, body, and closing

- [x] 19. Implement `feature-meeting` module
  - [x] 19.1 Build MeetingRecorder and MeetingSummary screens
    - `MeetingViewModel` state machine: `Idle` → `Recording` → `Processing` → `Complete`; annotated `@HiltViewModel` with `@Inject` constructor
    - Record meeting audio using Android `MediaRecorder`; stream or submit to `Transcription_Service`
    - Display timestamped transcript with speaker attribution
    - AI summarization of transcript via `AI_Orchestrator`
    - Action item extraction: discrete tasks extracted from transcript assigned to named participants
    - Export meeting summary as PDF or Markdown
    - Show microphone permission rationale dialog and settings deep-link if not granted
    - _Requirements: 5.6, 19.1_
  - [x] 19.2 Write unit tests for `MeetingViewModel` state transitions
    - Test full `Idle`→`Recording`→`Processing`→`Complete` cycle; test permission denied branches to rationale state; test summary contains extracted action items; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_

- [x] 20. Implement `feature-translator` module
  - [x] 20.1 Build TranslatorScreen with online and offline translation routing
    - `TranslatorViewModel` with `StateFlow<TranslatorUiState>`; annotated `@HiltViewModel` with `@Inject` constructor; language pair selector for source/target; persist selection in DataStore
    - Accept text input and speech input via `SpeechRecognizer`; display translated output
    - When online: route to `AI_Orchestrator` (supports all language pairs)
    - When offline: fall back to bundled `Offline_Translation_Model`
    - _Requirements: 10.5, 19.1_
  - [x] 20.2 Write unit tests for `TranslatorViewModel` online/offline routing logic
    - Mock `ConnectivityObserver`; verify `AI_Orchestrator` route when connected; verify `Offline_Translation_Model` when disconnected; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_


- [x] 20.5 Implement `feature-productivity` module (Productivity Suite)
  - [x] 20.5.1 Build TodoList and TodoEditor screens
    - `ProductivityViewModel` with `StateFlow<ProductivityUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Paginated `TodoItem` list filterable by completion status and due date
    - `TodoEditor` with title, description, due date, priority, and tags
    - AI-assisted todo generation: user provides natural language prompt; AI returns up to 20 `TodoItem` candidates for user confirmation before saving; does NOT persist until user confirms
    - Local Room storage + backend sync via `ProductivityRepository` (local-first, last-write-wins, `syncStatus` field)
    - _Requirements: 29.1, 29.2_
  - [x] 20.5.2 Build CalendarView screen
    - Monthly/weekly calendar grid displaying `CalendarEvent` objects from local Room
    - When Google Calendar MCP connector configured and device online: merge Google Calendar events; local events take precedence on title conflicts
    - AI-suggested optimal meeting times: return minimum 3 and maximum 10 available time slots based on existing `CalendarEvent` entries
    - _Requirements: 29.3, 29.4_
  - [x] 20.5.3 Build ReminderList and ReminderEditor screens
    - `ReminderList`: list of upcoming `Reminder` objects sorted by trigger time
    - `ReminderEditor`: title, trigger time, iCal RRULE recurrence rule, optional linked `TodoItem`
    - Deliver local notifications via `NotificationManager` + `AlarmManager` with exact alarms; request `SCHEDULE_EXACT_ALARM` permission on Android 12+
    - If notification permission denied: display in-app reminder instead
    - AI-suggested reminders from natural language via `SuggestReminderUseCase`; return for user confirmation before saving
    - _Requirements: 29.5, 29.6_
  - [x] 20.5.4 Build HabitTracker screen
    - `HabitList` and `HabitDetail` views: define habits (name, description, daily/weekly recurrence), log daily completion, view AI-generated insights (completion patterns, best/worst days, streak predictions)
    - AI insights only generated after at least 7 days of logged data
    - _Requirements: 29.7_
  - [x] 20.5.5 Write property test for productivity sync conflict resolution (Property 29)
    - **Property 29: Productivity Sync Conflict Resolution** — **Validates: Requirements 29.9**
    - Use Kotest PropTest; generate random sequences of local and remote updates with `updated_at` timestamps; assert authoritative version is always the one with the latest `updated_at` timestamp

- [x] 21. Implement `feature-settings` and `feature-profile` modules
  - [x] 21.1 Build Settings screen
    - `SettingsViewModel` with `StateFlow<SettingsUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - LLM_Provider selector: apply selection immediately for subsequent Messages without app restart
    - Theme selector (Light / Dark / System-default); persisted in DataStore
    - Notification category toggles: RAG ingestion, message delivery, system alerts
    - Privacy mode toggle: disables memory capture for current session without deleting existing memories
    - Account management: change password, Google OAuth2 link/unlink, logout (calls `POST /auth/logout`)
    - Firebase Remote Config: display configurable remote values if Admin-published
    - Context-aware suggestions global toggle: `contextSuggestionsEnabled` persisted to DataStore
    - _Requirements: 3.2, 3.7, 7.6, 16.4, 24.2, 28.3, 33.8_
  - [x] 21.2 Build Profile and Memory Management screen
    - Display and edit user profile (name, avatar); show stored memories list with edit and delete per-item
    - Memory delete triggers `DeleteMemoryUseCase`; remove from Vector_Store within 10 seconds
    - Data export request: calls Backend export endpoint; show status while export is prepared (up to 24 hours)
    - Account deletion flow with confirmation dialog: call Backend deletion endpoint; display status
    - _Requirements: 7.3, 7.4, 28.1, 28.2_
  - [x] 21.3 Write unit tests for `SettingsViewModel` and `ProfileViewModel`
    - Verify LLM_Provider switch applies immediately; verify privacy mode toggle gates memory capture; verify logout clears tokens; verify context suggestions toggle gates suggestion calls; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_

- [x] 22. Implement `feature-history` module
  - [x] 22.1 Build ConversationHistory screen with search and export
    - `HistoryViewModel` with `StateFlow<HistoryUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Paginated conversation list (20 per page, Paging 3) sorted by `updatedAt`; two-pane layout on tablets ≥600 dp
    - FTS search across conversation titles and message content within 300 ms
    - Group conversations: Today / Yesterday / Last 7 Days / Older
    - Pin, rename, and soft-delete actions per conversation
    - Export conversation as Markdown or PDF to Downloads folder within 10 seconds
    - _Requirements: 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 17.6, 24.4_
  - [x] 22.2 Write Compose UI tests for history search and export
    - Assert FTS search returns correct results within 300 ms; assert export file is created in Downloads; assert pinned conversations appear at top
    - _Requirements: 21.3_

- [x] 23. Implement Model Comparison Mode in `feature-chat`
  - [x] 23.1 Build ComparisonMode screen
    - `ComparisonModeViewModel` with `StateFlow<ComparisonModeUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Dispatch same prompt concurrently to 2–4 selected LLM_Providers within 100 ms of each other
    - Display each response in a side-scrollable panel: provider name, Markdown-rendered response, token count, latency (ms to first token), estimated cost (USD)
    - Quality score per panel: composite of response length (0–40 pts), coherence via lightweight LLM eval (0–40 pts), latency (0–20 pts), displayed as 0–100
    - "Use This Response" adopts response as canonical Message and dismisses other panels
    - Error panel for any provider that fails or times out after 30 seconds; other panels continue
    - Disable Comparison Mode control if fewer than 2 providers are configured; display tooltip explaining requirement
    - _Requirements: 30.1, 30.2, 30.3, 30.4, 30.5, 30.6, 30.7, 30.8_
  - [x] 23.2 Write unit tests for `ComparisonModeViewModel`
    - Verify concurrent dispatch within 100 ms; verify error panel shown on provider timeout; verify "Use This Response" sets canonical message; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_

- [x] 24. Implement FastAPI backend — Auth Service
  - [x] 24.1 Implement `/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout` endpoints
    - Password hashing with bcrypt (work factor ≥12); JWT 15-minute expiry; refresh token 30-day expiry
    - Token rotation on each refresh; detect replay via `family_id` and revoke entire Token_Family on second use
    - Account lockout: 5 consecutive failed attempts within 10 minutes → lock for 15 minutes, send 1 email notification per attempt during lockout
    - Google OAuth2 sign-in: map Google account to local user record on first sign-in
    - RBAC enforcement: `user`, `premium`, `admin` roles; HTTP 403 on insufficient role
    - Audit log for all auth events (login, logout, refresh, failed attempts); retained ≥90 days
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 1.9, 1.10, 1.11, 9.3, 9.8_
  - [x] 24.2 Write Pytest unit and integration tests for Auth Service
    - Test registration validation (email format + password length), JWT issuance, token rotation, replay detection and Token_Family revocation, account lockout, Google OAuth2 flow, RBAC enforcement
    - Use fresh database schema per test; mock external services
    - _Requirements: 21.1, 21.2_

- [x] 25. Implement FastAPI backend — AI Orchestrator
  - [x] 25.1 Implement multi-LLM routing, streaming, context management, and safety filters
    - Route requests to: OpenAI GPT-4o, Google Gemini 1.5 Pro, Anthropic Claude 3.5 Sonnet, Ollama, Llama 3.x, Mistral
    - WebSocket endpoint at `/ws/chat/{conversation_id}`: JWT auth via query param; close with code 4001 on invalid JWT
    - Heartbeat ping every 30 seconds; close with code 1001 if pong not received within 10 seconds
    - Token buffering: buffer up to 1,000 tokens for 60 seconds on disconnect; deliver on reconnect
    - Context window management: summarize messages when history exceeds 80% of context window
    - Fallback LLM_Provider on timeout (10 s) or connection error; notify User of substitution; structured error if no fallback configured (when `LLM_FALLBACK_PROVIDER` env var is absent or empty)
    - Prompt injection detection: reject with HTTP 400, `PROMPT_INJECTION_DETECTED`, log SHA-256 hash of sanitized input
    - Safety filters: redact harmful content; block entire response if redaction fails
    - System prompt enforcement: persona, scope, safety rules; User cannot override system prompt
    - Prompt template versioning: associate version number, creation timestamp, and author; preserve prior versions; allow Admin rollback
    - Configurable max response length per provider; truncate and append notice on limit hit
    - Token usage tracking per Message; per-provider cost tracking using configurable per-token pricing
    - Comparison Mode: dispatch same prompt concurrently to 2–4 providers within 100 ms; record per-provider token usage and cost
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.8, 2.9, 3.1, 3.3, 3.4, 3.5, 3.6, 3.7, 9.6, 25.1, 25.2, 25.3, 25.4, 25.5, 25.6, 25.7, 26.1, 26.2, 26.3, 26.4, 26.5, 30.1, 30.3, 30.7_
  - [x] 25.2 Write Pytest unit and integration tests for AI Orchestrator
    - Use mock LLM_Provider returning deterministic output; test streaming, context summarization, fallback routing, prompt injection rejection, safety filter, token counting
    - _Requirements: 21.1, 21.2, 21.4, 21.6_

- [x] 26. Implement FastAPI backend — RAG Pipeline
  - [x] 26.1 Implement document ingestion, chunking, embedding, and retrieval endpoints
    - `POST /documents`: validate format (PDF, DOCX, TXT, MD) and size (≤50 MB) before writing any bytes; store raw file in MinIO; enqueue Celery job; return job ID immediately
    - Celery job: OCR for scanned PDFs, direct extraction for native PDF/DOCX/TXT/MD; chunk (default 512 tokens, overlap 64, min 64, max 2048, max overlap 50%); embed via SentenceTransformer `all-MiniLM-L6-v2`; store in ChromaDB under `documents_{user_id}`
    - `GET /jobs/{job_id}`: return `queued` / `processing` / `completed` / `failed` + error message on failure
    - `POST /documents/{id}/query`: retrieve top-K=5 semantically relevant chunks; include citations (document name + page number, or character offset for TXT/MD)
    - `DELETE /documents/{id}`: remove all chunks/embeddings from ChromaDB and PostgreSQL records within 60 seconds
    - Celery retry: up to 3 times with `2^n` second backoff; mark as `failed` and surface in Admin_Dashboard on all retries exhausted
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 27.1, 27.2, 27.3_
  - [x] 26.2 Write property test for RAG round-trip retrieval (Property 4)
    - **Property 4: RAG Round-Trip Retrieval** — **Validates: Requirements 4.9, 21.5**
    - Use Hypothesis; generate valid text documents; ingest and query a verbatim phrase of ≥3 words present in the document; assert at least one retrieved chunk contains that phrase
  - [x] 26.3 Write Pytest unit and integration tests for RAG Pipeline
    - Test format/size validation, MinIO upload, Celery job status transitions, chunking coverage (no gaps), embedding storage, cross-user isolation, delete cleanup
    - _Requirements: 21.1, 21.2_

- [x] 27. Implement FastAPI backend — Memory Service and MCP Broker
  - [x] 27.1 Implement Memory Service
    - Store user facts, preferences, writing style observations as embeddings in ChromaDB under `memories_{user_id}`
    - Retrieve top-3 most relevant memories per Message; inject into system prompt context; proceed without injection if retrieval fails
    - CRUD endpoints (`GET /memory`, `DELETE /memory/{id}`); delete removes embedding from ChromaDB within 10 seconds
    - Privacy mode: disable memory capture for session without deleting existing memories
    - Extract and store new facts from every completed Message
    - Strict per-user scoping: no cross-user memory access
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5, 7.6, 7.7_
  - [x] 27.2 Implement MCP Broker
    - Implement Model Context Protocol: tool registration, discovery, invocation, and result handling
    - Out-of-the-box connectors: GitHub, Gmail, Google Drive, Google Calendar, Slack, Jira, Notion, Figma
    - Invocation timeout 30 seconds; cancel and return timeout error on breach
    - Write-action confirmation: surface confirmation to AI_Assistant; invoke only on user confirmation; cancel and inform AI_Orchestrator on dismissal
    - Structured error on failure: error code + user-safe message; never expose internal details
    - Extensibility: register new tool by adding single connector class without modifying existing connectors
    - Audit log: User ID, tool name, timestamp, result status; retained ≥90 days
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.8_
  - [x] 27.3 Write Pytest unit and integration tests for Memory Service and MCP Broker
    - Test memory injection on prompt construction, delete TTL enforcement, privacy mode gate, cross-user isolation; MCP invocation, timeout handling, write-action confirmation flow, audit log entry creation
    - _Requirements: 21.1, 21.2_


- [x] 28. Implement FastAPI backend — Admin Dashboard API and observability
  - [x] 28.1 Implement Admin Dashboard API endpoints
    - Metrics endpoint: active users, messages/hour, total token consumption, per-provider cost, error rates; refreshed within 30 seconds
    - User management: view, search, promote, demote, deactivate (invalidate all JWTs and refresh tokens within 5 seconds), reactivate
    - Usage analytics: AI feature usage broken down by feature and LLM_Provider
    - Audit log: paginated (max 100/page, max 90-day range); filterable by User, event type, date range
    - Error monitoring: top 10 most frequent error types in last 24 hours; error type name, count, ≤500 chars stack trace excerpt
    - Feedback management: aggregate User feedback, tag by category, export as CSV (feedback ID, user ID, timestamp, content, category)
    - Firebase Remote Config UI: read, update, and publish remote configuration values without redeploying Backend
    - Session monitor: active sessions (device type, country-level region, current feature, session duration); refreshed within 30 seconds
    - Celery worker metrics: queue depth, active tasks, failed tasks; exposed to Prometheus `/metrics`
    - Differential privacy panel: display current epsilon value, noise mechanism ("Laplace"), plain-language privacy guarantee, per-user privacy budget consumed
    - _Requirements: 15.1, 15.2, 15.3, 15.4, 15.5, 15.6, 15.7, 15.8, 15.9, 27.4, 37.7_
  - [x] 28.2 Implement structured logging, Prometheus metrics, Grafana dashboards, and Loki integration
    - Structured JSON log per API request (excluding `/metrics`, `/health`): UUID correlation ID, user ID, endpoint path, HTTP status, response time ms
    - Prometheus endpoint at `/metrics`: counters and histograms (buckets: 50, 100, 200, 500, 1000, 2000, 5000 ms) for request rates, error rates, response times per endpoint
    - Pre-built Grafana dashboards: AI cost by provider, request volume over time, error rate over time
    - Loki log aggregation: query by correlation ID or user ID returns results within 10 seconds
    - Unhandled exception: log full stack trace with correlation ID; increment error counter metric (only on actual unhandled exceptions)
    - _Requirements: 18.1, 18.2, 18.3, 18.4, 18.5, 18.6_
  - [x] 28.3 Write Pytest unit and integration tests for Admin endpoints and observability
    - Test RBAC enforcement on all admin endpoints; test user deactivation token invalidation; test metrics endpoint schema; test correlation ID propagation
    - _Requirements: 21.1, 21.2_

- [x] 29. Implement FastAPI backend — Security, rate limiting, and data privacy
  - [x] 29.1 Implement security middleware and data privacy endpoints
    - Rate limiting: authenticated users ≥60 req/min → HTTP 429 with `Retry-After`; unauthenticated public endpoints ≥20 req/min per IP → HTTP 429
    - Parameterized queries for all database operations; context-aware output encoding on all API responses
    - AES-256 encryption at rest for all LLM_Provider API keys; never return plaintext key in response or log
    - `POST /data/export`: return structured JSON archive of all User data within 24 hours; notify User and provide reason if delayed
    - `DELETE /data/account`: permanently remove all User data including ChromaDB embeddings within 72 hours; notify User and Admin if delayed
    - Data residency enforcement: reject write operations that would violate configured geographic constraint
    - Push notification dispatch via Celery: retry up to 3 times with exponential backoff; surface persistent failures in Admin_Dashboard
    - Device token refresh: update Backend token on each successful API request if changed; retry on next 10 requests if update fails
    - _Requirements: 9.1, 9.2, 9.7, 9.9, 9.10, 9.11, 16.1, 16.2, 16.5, 16.6, 16.7, 28.1, 28.2, 28.4, 28.5_
  - [x] 29.2 Write Pytest unit and integration tests for security middleware and data privacy
    - Test rate limiting (authenticated and unauthenticated), parameterized query enforcement, AES-256 key encryption, data export/deletion workflows, data residency rejection
    - _Requirements: 21.1, 21.2_

- [x] 30. Implement FastAPI backend — health checks and remaining API endpoints
  - [x] 30.1 Implement health, readiness, and image understanding endpoints
    - `GET /health`: return HTTP 200 `{"status":"ok"}` if all dependencies reachable; HTTP 503 with unreachable dependencies listed
    - `GET /ready`: return HTTP 200 `{"status":"ready"}` if service can accept traffic and all required env vars present; HTTP 503 if not
    - `POST /images/analyze`: validate JPEG/PNG/WebP, max 4096×4096 px, max 10 MB; perform OCR and return extracted text with bounding box coordinates; `no_text_found` indicator if no text detected; pass image + prompt to vision-capable LLM_Provider; return structured error if no vision-capable provider configured
    - `POST /transcription`: accept meeting audio; return timestamped transcript with speaker attribution
    - `POST /translate`: route to AI_Orchestrator or offline model based on connectivity
    - `POST /resumes/generate`, `POST /covers/generate`, `POST /emails/generate`, `POST /emails/grammar`: implement per requirements 14.1–14.6
    - _Requirements: 6.3, 6.4, 14.1, 14.2, 14.4, 14.5, 20.1, 20.2, 20.3, 20.5, 20.6, 20.7_
  - [x] 30.2 Write Pytest integration tests for health, image analysis, and generation endpoints
    - Test health/readiness with dependency mocks (including missing env var returns 503); test image OCR, vision routing, audio transcription, resume/cover/email generation
    - _Requirements: 21.1, 21.2_

- [x] 31. Implement Firebase integration and Android notifications
  - [x] 31.1 Integrate Firebase Crashlytics, Firebase Analytics, and Firebase Remote Config
    - Crashlytics: crash reporting on all uncaught exceptions
    - Analytics: minimum event set: `screen_view`, `feature_used`, `message_sent`, `error_occurred`
    - Remote Config: fetch configurable runtime values published via Admin_Dashboard UI; apply on next app launch without app update
    - _Requirements: 18.7, 15.8_
  - [x] 31.2 Implement WorkManager offline queue and push notification handling
    - WorkManager `SyncOfflineQueueWorker`: submit queued messages when connectivity restored; exponential backoff (initial 5 s, multiplier 2, max 60 s); mark Message `failed` with in-app notification after 3 attempts
    - FCM push notification receiver: handle RAG ingestion complete, queued message delivered, system alert categories; respect per-category notification settings from SettingsViewModel
    - Device token refresh on token change: call Backend update endpoint; retry on next 10 successful requests if update fails
    - _Requirements: 10.2, 10.3, 10.6, 16.1, 16.2, 16.4, 16.5, 16.7_
  - [x] 31.3 Write unit tests for `SyncOfflineQueueWorker` and notification routing
    - Verify retry count and backoff intervals; verify `failed` status after 3 attempts; verify notification category filtering
    - _Requirements: 21.1_

- [x] 32. CI/CD, Docker Compose infrastructure, and documentation
  - [x] 32.1 Implement GitHub Actions CI/CD workflows
    - PR workflow: run Android lint + unit tests + Backend unit + integration tests; block merge on failure via branch protection rules
    - Merge-to-main workflow: produce signed Android release APK artifact; build and push Docker image
    - Dependency-lint step: fail build when `feature` → `feature`, `domain` → `data`/`feature`, or `data` → `feature` dependency detected
    - JaCoCo gate: fail build if combined `domain` + `data` coverage < 70%
    - ktlint + Detekt: block merge on any error in changed Kotlin source files
    - _Requirements: 19.2, 19.5, 19.6, 20.3, 20.4_
  - [x] 32.2 Implement Docker Compose infrastructure
    - Multi-stage Dockerfile: final production stage contains no build tools or dev packages; runs as non-root user
    - `docker-compose.yml` with services: Backend API, PostgreSQL, Redis, ChromaDB, MinIO, Celery worker, Nginx reverse proxy, Prometheus, Grafana, Loki; ChromaDB on port ≥8001 (not 8000)
    - `.env.example` files for all required environment variables with descriptions; all `.env` files with actual secrets listed in `.gitignore`
    - _Requirements: 20.1, 20.2, 20.8_
  - [x] 32.3 Write and verify all required documentation
    - `/docs` directory with: Project Vision, PRD, System Architecture (Mermaid component diagram), Android Architecture, Backend Architecture, AI Architecture, RAG Architecture, Database Design (ER diagram), API Specification (every endpoint with request/response schema, auth requirements, one valid example each), Security Guide, Performance Guide, Testing Strategy, DevOps Guide, MCP Integration, Coding Standards, Deployment Guide
    - Educational header block in every source file: purpose, architectural placement, dependencies, design decision/pattern
    - README: setup instructions for clean OS install, every required tool with version, full local dev setup completable in under 15 minutes
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.6_

- [x] 33. Implement On-Device AI Inference (`feature-on-device-ai` / `core-ai` extension)
  - [x] 33.1 Add NPU/GPU capability detection and on-device model lifecycle management
    - Query `ActivityManager.MemoryInfo` and `EGL` / vendor extensions to detect NPU or dedicated GPU with ≥4 GB available memory at startup
    - `OnDeviceModelManager`: download quantized INT4/INT8 Llama or Mistral GGUF model to internal storage; verify SHA-256 checksum against bundled manifest before loading; detect absent or corrupt files and display download prompt, falling back to configured cloud LLM_Provider until verified
    - Expose `OnDeviceInferenceClient` implementing `AIStreamClient`; route requests entirely on-device with zero network calls to Backend or any external endpoint
    - Display "Running on device" persistent indicator while on-device inference is active
    - Monitor available RAM during inference; when RAM < 512 MB cancel request, show "Insufficient resources — switching to cloud" message, and retry prompt against fallback LLM_Provider automatically
    - Time-to-first-token ≤2,000 ms on a device meeting the NPU/GPU threshold; when device is offline and on-device model available allow full AI chat without queuing messages
    - Register `OnDeviceInferenceClient` as a selectable `LLM_Provider` option in `SettingsViewModel` (only shown when NPU/GPU threshold is met)
    - _Requirements: 31.1, 31.2, 31.3, 31.4, 31.5, 31.6, 31.7, 31.8_
  - [x] 33.2 Write unit tests for `OnDeviceModelManager` lifecycle and fallback routing
    - Verify checksum mismatch triggers corrupt-files branch; verify RAM threshold cancels request and retries fallback; verify device without NPU/GPU does not expose on-device provider in settings
    - _Requirements: 21.1, 31.6, 31.7_
  - [x] 33.3 Write property test for on-device inference provider isolation (Property 31)
    - **Property 31: On-Device Inference Zero Network Calls** — **Validates: Requirements 31.2**
    - Use Kotest PropTest; generate arbitrary message payloads routed to `OnDeviceInferenceClient`; mock `OkHttpClient` to capture outbound calls; assert zero HTTP requests are made to any external host for any input

- [x] 34. Implement AI Persona and System Prompt Customization (Android + Backend)
  - [x] 34.1 Add `Persona` domain entity, repository interface, and use cases
    - `Persona` entity: `id`, `userId`, `name` (1–80 chars), `systemPrompt` (1–4,000 chars), `tone` (enum: `professional`/`casual`/`concise`/`detailed`/`creative`), `scopeDescription` (0–500 chars), `adminLocked` (Boolean), `allowedRoles` (List<String>)
    - `PersonaRepository` interface: `getPersonas()`, `createPersona()`, `updatePersona()`, `deletePersona()`
    - `CreatePersonaUseCase`: validate field lengths; reject if User already has 20 personas; call `PersonaRepository.createPersona()`
    - `DeletePersonaUseCase`: reject if `adminLocked == true` for non-admin callers
    - `SelectPersonaUseCase`: persists selected persona ID to DataStore; informs AI Orchestrator for subsequent messages
    - All use cases annotated with `@Inject` constructor
    - _Requirements: 32.1, 32.3, 32.4, 32.5, 32.6_
  - [x] 34.2 Build PersonaList and PersonaEditor screens
    - `PersonaViewModel` with `StateFlow<PersonaUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - `PersonaList`: list of User's own personas + Admin-shared personas permitted for the User's role; lock icon on `adminLocked` entries (no edit/delete controls)
    - `PersonaEditor`: form for name, system prompt (character counter up to 4,000), tone selector, scope description
    - Enforce 20-persona limit with inline error; display system message in Conversation timeline on persona switch (persona name + timestamp)
    - Filter persona list to only show personas permitted for the User's role per RBAC configuration
    - _Requirements: 32.1, 32.3, 32.5, 32.6, 32.7_
  - [x] 34.3 Implement Backend Persona endpoints and AI Orchestrator injection
    - `POST /personas`, `GET /personas`, `PUT /personas/{id}`, `DELETE /personas/{id}`
    - Validate persona `systemPrompt` for prompt injection patterns (Requirement 25.5); reject with HTTP 422 + `PROMPT_INJECTION_DETECTED` on match
    - Enforce 20-persona limit per user at the Backend; return HTTP 422 with limit-reached message on 21st
    - `admin_locked` flag: prevent non-admin edits/deletes; role-restricted persona list per RBAC role configuration
    - AI Orchestrator: inject selected Persona's system prompt + tone + scope into LLM system message, appending platform safety rules for non-admin users
    - _Requirements: 32.2, 32.3, 32.4, 32.5, 32.6, 32.8_
  - [x] 34.4 Write unit tests for persona use cases and `PersonaViewModel`
    - Verify 20-persona limit error; verify `adminLocked` delete rejection; verify role-filtered persona list; verify persona switch inserts timeline system message; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4_
  - [x] 34.5 Write Pytest unit and integration tests for Persona Backend endpoints
    - Test field validation (name/prompt length), prompt injection rejection, 20-persona limit, admin_locked enforcement, RBAC role filtering
    - _Requirements: 21.1, 21.2_


- [x] 35. Implement Context-Aware AI Suggestions (Android + Backend)
  - [x] 35.1 Add context suggestion use cases and domain model
    - `ContextSuggestion` entity: `id`, `type` (enum: `summarize`/`expand`/`add_action_items`/`draft_agenda`/`prep_questions`/`lookup_attendees`/`continue_conversation`), `displayText`, `preFillText`, `targetScreenType`
    - `GetContextSuggestionsUseCase`: takes current screen context (note content / calendar event / last conversation message + age); returns 1–3 `ContextSuggestion` objects; no-op when privacy mode enabled or suggestions globally disabled
    - `DismissSuggestionUseCase`: records dismissed suggestion type per screen instance in memory (session-scoped, not persisted)
    - Rate-gate: at most one suggestion generation request per screen per 5-second idle window (enforced in use case layer via timestamp tracking)
    - _Requirements: 33.1, 33.4, 33.5, 33.7, 33.8_
  - [x] 35.2 Integrate suggestion chips/cards into `feature-notes`, `feature-productivity`, and `feature-chat`
    - `feature-notes` / `NoteEditor`: detect 5-second idle via `LaunchedEffect` + debounce; call `GetContextSuggestionsUseCase`; display result as dismissible chips above keyboard; suppress suggestion type for remainder of session on dismiss; no loading indicator if response not received within 3 seconds
    - `feature-productivity` / `CalendarEventDetail`: on event view, call `GetContextSuggestionsUseCase` with event context; display as non-blocking card below event details
    - `feature-chat` / `ChatDetail`: when last message is >24 hours old offer "Continue this conversation" chip pre-populating input field with continuation prompt
    - Settings toggle: `contextSuggestionsEnabled` persisted to DataStore; when false `GetContextSuggestionsUseCase` returns empty list immediately
    - _Requirements: 33.1, 33.2, 33.3, 33.5, 33.6, 33.8_
  - [x] 35.3 Implement Backend `/suggestions/context` endpoint
    - `POST /suggestions/context`: accept screen context payload; call AI Orchestrator `complete()` with context-specific prompt template; return 1–3 `ContextSuggestion` objects; respond within 3 seconds or let client time out silently
    - Respect privacy mode: return empty suggestions list when User's privacy mode is enabled
    - _Requirements: 33.1, 33.2, 33.6, 33.7_
  - [x] 35.4 Write unit tests for `GetContextSuggestionsUseCase` and rate-gate logic
    - Verify privacy mode returns empty suggestions; verify global disable returns empty suggestions; verify 5-second rate gate blocks duplicate requests; verify dismissed type is suppressed for session
    - _Requirements: 21.1, 33.4, 33.5, 33.7, 33.8_
  - [x] 35.5 Write property test for context suggestion rate-gate invariant (Property 32)
    - **Property 32: Context Suggestion Rate-Gate** — **Validates: Requirements 33.4**
    - Use Kotest PropTest; generate sequences of idle events with random inter-event intervals (0–10 s); assert the number of suggestion generation calls equals exactly the number of idle events that are ≥5 seconds after the previous call; assert rapid consecutive events (< 5 s apart) produce at most one call

- [x] 36. Implement AI Cost Dashboard for Users (Android + Backend)
  - [x] 36.1 Add per-user usage record model and Backend cost aggregation endpoint
    - Extend `UsageRecord` PostgreSQL model with `feature` field (enum: `chat`/`rag`/`code`/`voice`/`comparison`/`suggestions`); store one record per Message per LLM_Provider; retain ≥90 days
    - `GET /usage/cost`: return aggregated token usage + estimated cost broken down by feature, LLM_Provider, and calendar day for the last 90 days for the authenticated User only; respond within 2 seconds; return HTTP 403 if request includes another user's identifier
    - `POST /usage/alerts`: create spending alert threshold (min $0.01, max $999.99); max 3 per user; return HTTP 422 on 4th attempt
    - `DELETE /usage/alerts/{id}`: remove alert threshold
    - Alert monitor: background task checks accumulated daily cost per User every 60 seconds; sends in-app notification when threshold crossed
    - _Requirements: 34.1, 34.2, 34.4, 34.7, 34.8_
  - [x] 36.2 Build CostDashboard screen in `feature-settings`
    - `CostDashboardViewModel` with `StateFlow<CostDashboardUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Accessible from Settings screen; display token usage + estimated cost (USD) broken down by feature, LLM_Provider, and calendar day for last 90 days
    - Monthly summary: total tokens, total estimated cost, bar chart of daily cost for current month
    - Loading state up to 10 seconds before showing error if Backend does not respond in time
    - Spending alert thresholds: add/remove up to 3; inline error on 4th attempt
    - Persistent banner when threshold crossed: threshold amount, current accumulated cost, date crossed; remains until User explicitly dismisses it
    - _Requirements: 34.1, 34.2, 34.3, 34.4, 34.5, 34.6_
  - [x] 36.3 Write unit tests for `CostDashboardViewModel` and alert threshold logic
    - Verify loading state → error after 10 s timeout; verify 3-alert limit error; verify persistent banner displayed and dismissed correctly; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4, 34.5, 34.6_
  - [x] 36.4 Write Pytest unit and integration tests for cost aggregation and alert endpoints
    - Test per-user scoping (HTTP 403 on other user's data); test alert threshold CRUD including limit enforcement; test alert monitor triggers notification within 60 seconds of threshold crossing
    - _Requirements: 21.1, 21.2, 34.7, 34.8_
  - [x] 36.5 Write property test for per-user cost data isolation (Property 33)
    - **Property 33: Per-User Cost Data Isolation** — **Validates: Requirements 34.7**
    - Use Hypothesis; generate pairs of distinct user IDs and usage records; assert that a cost query for user A never returns any record attributed to user B; assert any request including a foreign user ID returns HTTP 403

- [x] 37. Implement Federated Multi-Backend Support (Android)
  - [x] 37.1 Add `BackendEndpoint` domain model and federation configuration
    - `BackendEndpoint` data class: `name`, `baseUrl`, `regionTag`, `allowedRoles` (List<String>), `latencyMs` (Long, updated by health checks)
    - `FederationConfig` data class: ordered list of `BackendEndpoint` objects; read from Firebase Remote Config JSON published by Admin; applied within 60 seconds of publication without app restart
    - `BackendEndpointSelector`: selects endpoint whose `regionTag` and `allowedRoles` match the User's data residency requirement and RBAC role; on tie selects lowest `latencyMs`
    - `FederationHealthCheckWorker` (WorkManager periodic, every 30 s): ping each endpoint's `/health`; update `latencyMs`; refresh endpoint selection ranking without app restart
    - _Requirements: 35.1, 35.2, 35.5, 35.8_
  - [x] 37.2 Implement failover routing and informational banner in `core-network`
    - Extend `core-network` `NetworkModule` to route all API calls through `BackendEndpointSelector`
    - On connection error or 5xx response: retry same request against next eligible endpoint within 2 seconds; no user intervention required
    - If all eligible endpoints exhausted: display structured error identifying outage; do NOT route to non-eligible endpoint
    - On failover: display non-blocking informational banner showing active backend name and failover reason; banner auto-dismisses when primary endpoint recovers
    - Data isolation: the client MUST NOT replicate or forward data to a second endpoint; cross-backend sync is server-side only
    - _Requirements: 35.3, 35.4, 35.6, 35.7_
  - [x] 37.3 Write unit tests for `BackendEndpointSelector` and failover logic
    - Verify correct endpoint selected by region+role; verify latency-based tiebreaking; verify failover skips non-eligible endpoints; verify structured error when all endpoints exhausted
    - _Requirements: 21.1, 35.2, 35.4_
  - [x] 37.4 Write property test for federated endpoint selection invariant (Property 34)
    - **Property 34: Federated Endpoint Eligibility** — **Validates: Requirements 35.2, 35.4**
    - Use Kotest PropTest; generate random lists of `BackendEndpoint` objects with varying regions/roles and a User with specific region + role constraints; assert selected endpoint always satisfies both region and role constraints; assert when no endpoint satisfies constraints the result is an error state, never a non-eligible endpoint

- [x] 38. Implement AI-Powered Semantic Search (Android + Backend)
  - [x] 38.1 Add semantic search use case and domain model
    - `SemanticSearchResult` entity: `sourceType` (enum: `CONVERSATION`/`NOTE`/`DOCUMENT`/`MEMORY`), `sourceName`, `excerpt` (≤300 chars), `relevanceScore` (0.0–1.0), `deepLinkUri`
    - `SemanticSearchUseCase`: submit natural language query to Backend `/search/semantic`; return ranked `SemanticSearchResult` list filtered to score ≥0.5; no-op return if no results above threshold
    - `domain` module: add `SemanticSearchRepository` interface
    - `data` module: implement `SemanticSearchRepositoryImpl` calling Retrofit `/search/semantic` endpoint
    - _Requirements: 36.1, 36.3, 36.8_
  - [x] 38.2 Build UnifiedSemanticSearch screen
    - `SemanticSearchViewModel` with `StateFlow<SemanticSearchUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Query input field; submit triggers `SemanticSearchUseCase`; loading state while awaiting results
    - Group results by `sourceType`; display count per group; result card shows source name, excerpt (matched text highlighted), relevance score (two decimal places)
    - Tap result: navigate to source item via `deepLinkUri` and highlight matched excerpt
    - "No results found" state with rephrase suggestion when zero results above 0.5 threshold; no error state for empty results
    - Content-type group omitted entirely when that type has no embeddings
    - _Requirements: 36.1, 36.3, 36.4, 36.5, 36.8_
  - [x] 38.3 Implement Backend `/search/semantic` endpoint
    - `POST /search/semantic`: generate query embedding via SentenceTransformer; perform cosine similarity search across `conversations_{user_id}`, `notes_{user_id}`, `documents_{user_id}`, `memories_{user_id}` ChromaDB collections; return results with score ≥0.5 sorted descending; respond within 3 seconds for corpus ≤100,000 embeddings
    - Each result: `source_type`, `source_name`, `excerpt` (≤300 chars), `relevance_score` (2 dp), `deep_link`
    - Omit content-type group if no embeddings exist for that type; do NOT return empty groups
    - Primary retrieval signal is vector similarity only (no keyword FTS fallback)
    - Strict user scoping: only query collections scoped to `user_id` from JWT
    - _Requirements: 36.2, 36.3, 36.5, 36.7_
  - [x] 38.4 Write unit tests for `SemanticSearchViewModel` and result grouping
    - Verify results grouped by source type with correct counts; verify "No results" state on empty response; verify navigation deep-link triggered on tap; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4, 36.4, 36.8_
  - [x] 38.5 Write property test for semantic search round-trip retrieval (Property 35)
    - **Property 35: Semantic Search Round-Trip** — **Validates: Requirements 36.6**
    - Use Hypothesis; generate text excerpts of ≥10 words; store each excerpt as a content embedding in one of the four collections; submit the exact excerpt as a search query; assert the result referencing the originating item has a relevance score ≥0.90
  - [x] 38.6 Write Pytest unit and integration tests for semantic search endpoint
    - Test user scoping (no cross-user results); test threshold filtering (no results below 0.5 returned); test empty-group omission; test 3-second response SLA with mock ChromaDB returning 100,000 entries
    - _Requirements: 21.1, 21.2, 36.2, 36.5, 36.7_

- [x] 39. Implement Differential Privacy for Memory (Backend)
  - [x] 39.1 Implement Laplace noise injection in `Memory_Service`
    - Add `DifferentialPrivacyConfig` Pydantic settings model: `epsilon` (float, default 1.0, valid range 0.1–10.0); read from environment variable `DP_EPSILON`; validate at startup
    - `LaplaceNoiseInjector`: applies calibrated Laplace noise independently to each embedding dimension using `numpy`; noise scale = `sensitivity / epsilon` (sensitivity = 1.0 for unit-normalized embeddings); no single noise sample correlated across dimensions
    - In `memory_service.py`: call `LaplaceNoiseInjector.add_noise(embedding)` before every ChromaDB write; store the noised embedding
    - Retrieval: use noised embeddings in ChromaDB for all memory retrieval operations (Requirement 7.2 prompt injection)
    - `privacy_budget_spent` counter: per-user Redis counter incremented by `epsilon` on each new memory store; exposed to Admin Dashboard
    - _Requirements: 37.1, 37.3, 37.4, 37.8_
  - [x] 39.2 Implement Admin epsilon configuration endpoint and Admin Dashboard display
    - `PUT /admin/privacy/epsilon`: accept new epsilon value; validate 0.1 ≤ epsilon ≤ 10.0 (HTTP 422 with range error if outside bounds); persist to Redis config key; apply to all subsequent embeddings within 5 seconds without service restart
    - Admin Dashboard panel: display current epsilon, noise mechanism label ("Laplace"), plain-language privacy guarantee explanation, per-user privacy budget consumed
    - _Requirements: 37.2, 37.6, 37.7_
  - [x] 39.3 Write Pytest unit tests for `LaplaceNoiseInjector` and epsilon configuration
    - Verify noise is applied independently per dimension (no inter-dimension correlation); verify epsilon=1.0 default; verify epsilon <0.1 or >10.0 returns HTTP 422; verify config change applies within 5 seconds
    - _Requirements: 21.1, 37.1, 37.2, 37.6, 37.8_
  - [x] 39.4 Write property test for differential privacy retrieval utility (Property 36)
    - **Property 36: Differential Privacy Retrieval Utility** — **Validates: Requirements 37.5**
    - Use Hypothesis; generate random unit-normalized embedding vectors; apply Laplace noise at epsilon=1.0; query ChromaDB with original unnoised vector; assert the noised embedding is returned as a top-3 result with cosine similarity ≥0.70

- [x] 40. Implement Secret Management and Environment Configuration (Backend)
  - [x] 40.1 Audit and enforce env-var-only secret loading
    - Audit all `backend/app/config/` settings: confirm every secret (JWT secret key, AES key, LLM provider API keys, DB password, MinIO credentials, SMTP credentials, Firebase service account path, Google OAuth credentials) is loaded exclusively from environment variables or mounted secrets file — zero hardcoded values
    - Implement startup validation in `backend/app/main.py` `lifespan` handler: iterate required env var list; log structured error identifying each missing variable; exit with code 1 before binding to any port if any required variable is absent
    - Enforce `AES_ENCRYPTION_KEY` presence check specifically: refuse to start if absent
    - `LLM_FALLBACK_PROVIDER` env var: document in `.env.example`; wire to AI Orchestrator fallback logic — return structured error (no fallback attempt) when absent or empty
    - _Requirements: 26.1, 26.3, 26.5, 26.6_
  - [x] 40.2 Update `.env.example` files with all required variables and descriptions
    - Ensure root `.env.example` and `backend/.env.example` document every required variable with description and placeholder; include `DP_EPSILON`, `LLM_FALLBACK_PROVIDER`, `AES_ENCRYPTION_KEY`, `BACKEND_TLS_PIN_SHA256`, and all secrets from Requirement 26.1
    - Verify all `.env` files with real values are present in `.gitignore`; add any missing entries
    - _Requirements: 26.2_
  - [x] 40.3 Verify `/ready` endpoint reflects env-var validation
    - `GET /ready` must return HTTP 503 with structured body identifying missing env vars if any required variable is absent at the time of the call, in addition to checking PostgreSQL/Redis reachability
    - Write Pytest integration test: start backend with a missing required env var; assert `/ready` returns HTTP 503 with the missing variable name in the body
    - _Requirements: 26.3, 26.4_
  - [x] 40.4 Write Pytest unit tests for startup env-var validation
    - Mock `os.environ` to remove each required variable in turn; assert `startup_validation()` logs the missing variable name and raises `SystemExit` with code 1
    - _Requirements: 21.1, 26.3, 26.5_

- [x] 41. Implement CI/CD Pipeline and Release Process (GitHub Actions)
  - [x] 41.1 Update Android CI workflow for new requirements
    - Extend `android-ci.yml`: add instrumented Compose UI test job running on Android API 34 emulator; add Detekt job scoped to changed Kotlin files; confirm JaCoCo gate (≥70% `domain` + `data`) is enforced
    - Verify all required status checks are registered as branch-protection rules on `main` so that any single failure blocks merge
    - Pin all `uses:` action references to exact SHA or semver tag (e.g. `actions/checkout@v4`); replace any `@latest` or floating version references
    - _Requirements: 27.1, 27.2, 27.8_
  - [x] 41.2 Update Backend CI workflow for new requirements
    - Extend `backend-ci.yml`: add `ruff` lint step, `mypy` type check step, and Pytest coverage gate (≥70%); add Pytest integration test job using PostgreSQL 16 and Redis 7 service containers
    - _Requirements: 27.3_
  - [x] 41.3 Implement release workflow
    - Create/update `release.yml`: trigger on version tag `v<major>.<minor>.<patch>`; build signed Android AAB + APK using keystore from base64-encoded GitHub Actions secret (keystore NOT committed to repo); publish AAB to configured Google Play Store track; build multi-arch Docker image tagged with semver; run Trivy CRITICAL scan before push; push to GitHub Container Registry; create GitHub Release with auto-generated changelog
    - Auto-deploy to staging; run `/health` smoke test; require manual approval from configured reviewer before production deploy
    - _Requirements: 27.4, 27.5, 27.6, 27.7_

- [x] 42. Implement Security Scanning and Vulnerability Management (CI/CD)  vx 
  - [x] 42.1 Add CodeQL analysis workflow
    - Create/extend `security-scan.yml`: run CodeQL for Kotlin/Java (Android) and Python (Backend) on every PR and every push to `main`; upload SARIF results to GitHub Security tab
    - _Requirements: 28.1_
  - [x] 42.2 Add Gitleaks, Bandit, Trivy, OWASP Dependency-Check, and Safety scans
    - Gitleaks: scan full Git history on every push to `main` and every PR; fail and block merge on any detected secret pattern
    - Bandit: scan `backend/app/` on every PR; fail on HIGH severity + HIGH confidence findings
    - Trivy: scan backend Docker image on every push to `main`; fail on CRITICAL-severity unfixed vulnerabilities
    - OWASP Dependency-Check: run on weekly schedule against Python + Gradle dependencies; fail on CVSS ≥7.0 unless suppressed in `.github/dependency-check-suppression.xml` with documented justification and expiry date
    - Safety: check `backend/requirements.txt` on every PR; fail on any critical CVE
    - _Requirements: 28.2, 28.3, 28.4, 28.5, 28.6_
  - [x] 42.3 Verify `network_security_config.xml` certificate pinning matches Backend TLS certificate
    - Confirm `app/src/main/res/xml/network_security_config.xml` contains a `<pin-set>` entry with the backend's current TLS certificate SHA-256 fingerprint
    - Document certificate rotation procedure: both `network_security_config.xml` update and Backend certificate rotation must occur in the same deployment
    - Write a CI check that extracts the pinned fingerprint from `network_security_config.xml` and compares it against the fingerprint recorded in `.env.example` (placeholder field `BACKEND_TLS_PIN_SHA256`)
    - _Requirements: 28.7_

- [x] 43. Infrastructure Validation, Hilt DI Completeness, and Android Test Coverage
  - [x] 43.1 Implement infrastructure validation CI jobs
    - Extend `infrastructure-validation.yml`: docker-compose lint on every PR touching infrastructure files; warn if any service uses a floating `:latest` tag without a build context
    - Nginx validation: run `nginx -t` inside container on every PR modifying `infrastructure/nginx/`; block PR on failure
    - Prometheus validation: run `promtool check config` and `promtool check rules` on every PR modifying `infrastructure/prometheus/`; block PR on failure
    - Alembic migration chain validation on every PR modifying `backend/alembic/`: run `upgrade head` then `downgrade base` against temporary PostgreSQL service container; run `alembic heads` and fail if more than one head detected
    - Grafana dashboard JSON validation: parse all JSON files in `infrastructure/grafana/provisioning/`; fail job on any parse error
    - Verify ChromaDB service in `docker-compose.yml` uses port ≥8001 and does not conflict with backend port 8000
    - _Requirements: 29.1, 29.2, 29.3, 29.4, 29.5, 29.6, 29.7_
  - [x] 43.2 Enforce Hilt DI completeness across Android modules
    - Audit every use case class in `domain`: confirm `@Inject` constructor present; add `javax.inject:javax.inject:1` dependency to `domain/build.gradle.kts` if not already declared
    - Audit every ViewModel in all feature modules: confirm `@HiltViewModel` annotation and `@Inject` constructor; replace any manual ViewModel instantiation
    - Add `core-security/src/main/res/values/strings.xml` with all required string resources: `biometric_prompt_title`, `biometric_prompt_subtitle`, `biometric_prompt_negative_button`, `biometric_not_available`, `biometric_not_enrolled`
    - Run `:app:kaptDebugKotlin` (or KSP equivalent) and confirm zero Hilt binding errors; add this as a CI gate step in `android-ci.yml`
    - _Requirements: 30.1, 30.2, 30.3, 30.4_
  - [x] 43.3 Complete Android test coverage to meet JaCoCo 70% gate
    - For every use case in `domain` lacking a test: add unit test covering happy path and primary error path (repository error propagation)
    - For every `*RepositoryImpl` in `data` lacking tests: add unit tests mocking Room DAO and Retrofit service verifying local-first emission, remote sync trigger, and conflict resolution
    - For every ViewModel lacking Turbine tests: add `StateFlow` emission tests for primary success state and primary error state
    - Add `core-ui` Compose tests: verify `MarkdownText` renders all six node types (header, bold, italic, inline code, fenced code, table, bullet list); verify `CodeBlock` displays language identifier; verify adaptive layout switches to two-pane at 600 dp width
    - Run JaCoCo report; confirm combined `domain` + `data` instruction coverage ≥70%; fix coverage gaps if below threshold
    - _Requirements: 31.1, 31.2, 31.3, 31.4, 31.5_

## Task Dependency Graph

```json
{
  "waves": [
    {
      "wave": 1,
      "description": "Android project bootstrap — no dependencies",
      "tasks": ["1"]
    },
    {
      "wave": 2,
      "description": "Core Android modules (parallel) and Backend Auth Service (independent)",
      "tasks": ["2", "3", "4", "5", "6", "24"],
      "dependsOn": ["1"]
    },
    {
      "wave": 3,
      "description": "Core AI streaming, Domain module, and Backend AI Orchestrator",
      "tasks": ["7", "8", "25"],
      "dependsOn": ["2", "5", "24"]
    },
    {
      "wave": 4,
      "description": "Data module and Backend RAG Pipeline, Memory Service, MCP Broker",
      "tasks": ["9", "26", "27"],
      "dependsOn": ["4", "5", "6", "7", "8", "25"]
    },
    {
      "wave": 5,
      "description": "Android core/domain/data checkpoint and Backend Admin Dashboard, Security, health endpoints",
      "tasks": ["10", "28", "29", "30"],
      "dependsOn": ["9", "26", "27"]
    },
    {
      "wave": 6,
      "description": "Android auth feature module; independent Android feature modules; Backend CI/CD infrastructure",
      "tasks": ["11", "13", "14", "15", "16", "18", "20", "32"],
      "dependsOn": ["3", "4", "8", "9", "10", "28", "29", "30"]
    },
    {
      "wave": 7,
      "description": "Android chat, notes, productivity features; Firebase integration; Backend secret management",
      "tasks": ["12", "17", "20.5", "31", "40"],
      "dependsOn": ["6", "11", "24", "25", "26", "27", "28", "29", "30"]
    },
    {
      "wave": 8,
      "description": "Android meeting, settings/profile, history, on-device AI; AI Persona customization; CI/CD release workflow",
      "tasks": ["19", "21", "22", "33", "34", "41"],
      "dependsOn": ["12", "13", "17", "20.5", "31", "32"]
    },
    {
      "wave": 9,
      "description": "Android comparison mode, context suggestions, cost dashboard, federated backends, semantic search; Differential Privacy; Security scanning",
      "tasks": ["23", "35", "36", "37", "38", "39", "42"],
      "dependsOn": ["21", "22", "25", "26", "27", "28", "33", "34", "41"]
    },
    {
      "wave": 10,
      "description": "Final validation — infrastructure validation, Hilt DI completeness, Android test coverage",
      "tasks": ["43"],
      "dependsOn": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "20.5", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42"]
    }
  ]
}
```
## Notes

- Tasks 1–20.5 cover the Android client layer and are marked complete.
- Tasks 21–23 cover remaining Android feature modules (Settings, History, Comparison Mode).
- Tasks 24–30 cover the FastAPI backend services.
- Tasks 31–32 cover infrastructure, Firebase, CI/CD, and documentation.
- Tasks 33–43 cover the extended requirements added after the initial implementation plan:
  - Task 33: On-Device AI Inference (Req 31 — Android `core-ai` / new `feature-on-device-ai`)
  - Task 34: AI Persona and System Prompt Customization (Req 32 — Android + Backend)
  - Task 35: Context-Aware AI Suggestions (Req 33 — Android + Backend)
  - Task 36: AI Cost Dashboard for Users (Req 34 — Android + Backend)
  - Task 37: Federated Multi-Backend Support (Req 35 — Android)
  - Task 38: AI-Powered Semantic Search (Req 36 — Android + Backend)
  - Task 39: Differential Privacy for Memory (Req 37 — Backend)
  - Task 40: Secret Management and Environment Configuration (NFR Req 26 — Backend)
  - Task 41: CI/CD Pipeline and Release Process (NFR Req 27 — GitHub Actions)
  - Task 42: Security Scanning and Vulnerability Management (NFR Req 28 — CI/CD)
  - Task 43: Infrastructure Validation, Hilt DI Completeness, and Android Test Coverage (NFR Reqs 29, 30, 31)
- All property-based tests use Kotest PropTest (Android) or Hypothesis (Backend) and are marked with their property number and the requirement they validate.
- Properties 31–36 are defined in tasks 33–39 respectively, continuing the numbering from Property 30 in task 16.
- JaCoCo line coverage for `domain` and `data` modules must reach 70% combined before merging any PR.
- Backend Pytest line coverage must reach 70% before merging any PR.
- The `feature-on-device-ai` module (task 33) may be integrated into `core-ai` or introduced as a standalone Gradle module depending on model size and build-time constraints; the decision should be made before starting task 33.1.
