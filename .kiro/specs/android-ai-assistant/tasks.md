# Implementation Plan: Android AI Assistant (Enterprise Edition)

## Overview

Implementation follows Clean Architecture from the ground up: core modules first, then domain, then data, then feature modules, then the FastAPI backend, infrastructure, and observability layers. Each task builds on the previous so no component is left unconnected at any integration point.

## Tasks

- [x] 1. Bootstrap Android project structure and Gradle multi-module setup
  - Create all Gradle modules: `app`, `core-ui`, `core-network`, `core-database`, `core-ai`, `core-security`, `core-common`, `feature-auth`, `feature-chat`, `feature-rag`, `feature-camera`, `feature-code`, `feature-voice`, `feature-settings`, `feature-profile`, `feature-history`, `feature-notes`, `feature-meeting`, `feature-resume`, `feature-email`, `feature-translator`, `feature-productivity`, `domain`, `data`
  - Configure `settings.gradle.kts` with version catalog (`libs.versions.toml`)
  - Enforce unidirectional dependency rule in each module's `build.gradle.kts`: `feature` ? `domain`/`core`; `domain` ? never `data`/`feature`; `data` ? never `feature`
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
    - Two-pane layout for Chat and History screens on tablets (=600 dp width)
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
    - Type converters for `List<String>` ? JSON and `Long` ? `Instant`
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
    - Use Kotest PropTest; generate N ? [1, 5] disconnect events; assert intervals are 1 s ? 2 s ? 4 s ? 8 s ? 16 s capped at 30 s; assert =5 attempts total
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
    - `RegisterUseCase` validates email format (RFC 5321) and password length = 12 characters before calling `AuthRepository`
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
    - `DocumentRepositoryImpl`: multipart upload to `POST /documents`; poll `GET /jobs/{id}` for ingestion status; `syncStatus` transitions: `pending` ? `processing` ? `ready` / `failed`
    - `MemoryRepositoryImpl`: remote CRUD backed by `/memory/*` endpoints; no local cache (sensitive data)
    - `NoteRepositoryImpl`: local Room + remote sync with `syncStatus` field
    - `ProductivityRepositoryImpl`: local-first with server sync for all four sub-types; conflict resolution uses last-write-wins (`updated_at`); `CalendarEvent` additionally sourced from Google Calendar MCP connector when connected
    - _Requirements: 4.1, 4.10, 7.4, 13.4, 29.9_
  - [x] 9.6 Write unit tests for `DocumentRepositoryImpl` and `NoteRepositoryImpl`
    - Verify all `syncStatus` state transitions; verify delete clears both local cache and remote entries; verify local-first emission, remote sync trigger, conflict resolution
    - _Requirements: 21.1, 31.3_

- [x] 10. Checkpoint — Android core, domain, and data layers
  - Ensure all unit tests pass. Verify JaCoCo combined coverage =70% across `domain` and `data`. Ask the user if questions arise.


- [x] 11. Implement `feature-auth` module
  - [x] 11.1 Build Splash, Onboarding, Login, Register, and Biometric unlock screens
    - `AuthViewModel` with `StateFlow<AuthUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Splash screen displayed while application initializes (cold-start =2 s on Snapdragon 700 series)
    - Onboarding: display privacy policy and terms of service; require affirmative tap before enabling optional data collection; request notification permission; deny ? suppress all push notifications for lifetime of installation
    - Login with email/password inline validation (valid email format, =12 and =128 character password)
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
    - `VoiceViewModel` state machine: `Idle` ? `Listening` ? `Transcribing` ? `Speaking` ? `Idle`; annotated `@HiltViewModel` with `@Inject` constructor
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
    - Test full idle?listening?transcribing?speaking?idle cycle; verify permission denied branches to rationale state; verify primary success and primary error `StateFlow` emissions using Turbine
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
    - Verify status state transitions (`pending`?`processing`?`ready`/`failed`); verify files >50 MB show rejection error; verify primary success and error `StateFlow` emissions
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
    - AI summarize action (=150 words, preserving all key facts; truncate to exactly 150 words if exceeded)
    - AI rewrite action (learned writing style from Memory_Service if available; neutral professional style as fallback)
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_
  - [x] 17.2 Write property test for AI summary word limit (Property 19)
    - **Property 19: AI Summary Word Limit** — **Validates: Requirements 13.2**
    - Use Kotest PropTest; generate notes with random content; mock `SummarizeNoteUseCase`; assert returned summary word count = 150 for all inputs
  - [x] 17.3 Write property test for notes tag filter invariant (Property 20)
    - **Property 20: Notes Tag Filter Invariant** — **Validates: Requirements 13.5**
    - Use Kotest PropTest; generate note lists with random tag assignments; apply random single-tag filter; assert every note in result contains the filter tag; assert no note without the filter tag appears

- [x] 18. Implement `feature-resume` and `feature-email` modules
  - [x] 18.1 Build ResumeBuilder and CoverLetterEditor screens
    - `ResumeViewModel` with `StateFlow<ResumeUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - Input: at least one work experience + contact info + target job description; output: ATS-optimized resume in Markdown within 30 seconds
    - Require both job description and resume data for cover letter; return HTTP 422 with missing-field identification if either is absent
    - Cover letter generation tailored to provided job description (=400 words)
    - Export generated resume or cover letter as PDF or DOCX to Downloads folder; display error if conversion fails
    - _Requirements: 14.1, 14.2, 14.3, 14.7_
  - [x] 18.2 Write property test for cover letter word limit (Property 21)
    - **Property 21: Cover Letter Word Limit** — **Validates: Requirements 14.2**
    - Use Kotest PropTest; generate random resume and job description pairs; assert returned cover letter word count = 400
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
    - `MeetingViewModel` state machine: `Idle` ? `Recording` ? `Processing` ? `Complete`; annotated `@HiltViewModel` with `@Inject` constructor
    - Record meeting audio using Android `MediaRecorder`; stream or submit to `Transcription_Service`
    - Display timestamped transcript with speaker attribution
    - AI summarization of transcript via `AI_Orchestrator`
    - Action item extraction: discrete tasks extracted from transcript assigned to named participants
    - Export meeting summary as PDF or Markdown
    - Show microphone permission rationale dialog and settings deep-link if not granted
    - _Requirements: 5.6, 19.1_
  - [x] 19.2 Write unit tests for `MeetingViewModel` state transitions
    - Test full `Idle`?`Recording`?`Processing`?`Complete` cycle; test permission denied branches to rationale state; test summary contains extracted action items; verify primary success and error `StateFlow` emissions
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
    - Paginated conversation list (20 per page, Paging 3) sorted by `updatedAt`; two-pane layout on tablets =600 dp
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
    - Password hashing with bcrypt (work factor =12); JWT 15-minute expiry; refresh token 30-day expiry
    - Token rotation on each refresh; detect replay via `family_id` and revoke entire Token_Family on second use
    - Account lockout: 5 consecutive failed attempts within 10 minutes ? lock for 15 minutes, send 1 email notification per attempt during lockout
    - Google OAuth2 sign-in: map Google account to local user record on first sign-in
    - RBAC enforcement: `user`, `premium`, `admin` roles; HTTP 403 on insufficient role
    - Audit log for all auth events (login, logout, refresh, failed attempts); retained =90 days
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
    - `POST /documents`: validate format (PDF, DOCX, TXT, MD) and size (=50 MB) before writing any bytes; store raw file in MinIO; enqueue Celery job; return job ID immediately
    - Celery job: OCR for scanned PDFs, direct extraction for native PDF/DOCX/TXT/MD; chunk (default 512 tokens, overlap 64, min 64, max 2048, max overlap 50%); embed via SentenceTransformer `all-MiniLM-L6-v2`; store in ChromaDB under `documents_{user_id}`
    - `GET /jobs/{job_id}`: return `queued` / `processing` / `completed` / `failed` + error message on failure
    - `POST /documents/{id}/query`: retrieve top-K=5 semantically relevant chunks; include citations (document name + page number, or character offset for TXT/MD)
    - `DELETE /documents/{id}`: remove all chunks/embeddings from ChromaDB and PostgreSQL records within 60 seconds
    - Celery retry: up to 3 times with `2^n` second backoff; mark as `failed` and surface in Admin_Dashboard on all retries exhausted
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6, 4.7, 4.8, 4.9, 4.10, 4.11, 27.1, 27.2, 27.3_
  - [x] 26.2 Write property test for RAG round-trip retrieval (Property 4)
    - **Property 4: RAG Round-Trip Retrieval** — **Validates: Requirements 4.9, 21.5**
    - Use Hypothesis; generate valid text documents; ingest and query a verbatim phrase of =3 words present in the document; assert at least one retrieved chunk contains that phrase
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
    - Audit log: User ID, tool name, timestamp, result status; retained =90 days
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
    - Error monitoring: top 10 most frequent error types in last 24 hours; error type name, count, =500 chars stack trace excerpt
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
    - Rate limiting: authenticated users =60 req/min ? HTTP 429 with `Retry-After`; unauthenticated public endpoints =20 req/min per IP ? HTTP 429
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
    - Dependency-lint step: fail build when `feature` ? `feature`, `domain` ? `data`/`feature`, or `data` ? `feature` dependency detected
    - JaCoCo gate: fail build if combined `domain` + `data` coverage < 70%
    - ktlint + Detekt: block merge on any error in changed Kotlin source files
    - _Requirements: 19.2, 19.5, 19.6, 20.3, 20.4_
  - [x] 32.2 Implement Docker Compose infrastructure
    - Multi-stage Dockerfile: final production stage contains no build tools or dev packages; runs as non-root user
    - `docker-compose.yml` with services: Backend API, PostgreSQL, Redis, ChromaDB, MinIO, Celery worker, Nginx reverse proxy, Prometheus, Grafana, Loki; ChromaDB on port =8001 (not 8000)
    - `.env.example` files for all required environment variables with descriptions; all `.env` files with actual secrets listed in `.gitignore`
    - _Requirements: 20.1, 20.2, 20.8_
  - [x] 32.3 Write and verify all required documentation
    - `/docs` directory with: Project Vision, PRD, System Architecture (Mermaid component diagram), Android Architecture, Backend Architecture, AI Architecture, RAG Architecture, Database Design (ER diagram), API Specification (every endpoint with request/response schema, auth requirements, one valid example each), Security Guide, Performance Guide, Testing Strategy, DevOps Guide, MCP Integration, Coding Standards, Deployment Guide
    - Educational header block in every source file: purpose, architectural placement, dependencies, design decision/pattern
    - README: setup instructions for clean OS install, every required tool with version, full local dev setup completable in under 15 minutes
    - _Requirements: 22.1, 22.2, 22.3, 22.4, 22.5, 22.6_

- [x] 33. Implement On-Device AI Inference (`feature-on-device-ai` / `core-ai` extension)
  - [x] 33.1 Add NPU/GPU capability detection and on-device model lifecycle management
    - Query `ActivityManager.MemoryInfo` and `EGL` / vendor extensions to detect NPU or dedicated GPU with =4 GB available memory at startup
    - `OnDeviceModelManager`: download quantized INT4/INT8 Llama or Mistral GGUF model to internal storage; verify SHA-256 checksum against bundled manifest before loading; detect absent or corrupt files and display download prompt, falling back to configured cloud LLM_Provider until verified
    - Expose `OnDeviceInferenceClient` implementing `AIStreamClient`; route requests entirely on-device with zero network calls to Backend or any external endpoint
    - Display "Running on device" persistent indicator while on-device inference is active
    - Monitor available RAM during inference; when RAM < 512 MB cancel request, show "Insufficient resources — switching to cloud" message, and retry prompt against fallback LLM_Provider automatically
    - Time-to-first-token =2,000 ms on a device meeting the NPU/GPU threshold; when device is offline and on-device model available allow full AI chat without queuing messages
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
    - Use Kotest PropTest; generate sequences of idle events with random inter-event intervals (0–10 s); assert the number of suggestion generation calls equals exactly the number of idle events that are =5 seconds after the previous call; assert rapid consecutive events (< 5 s apart) produce at most one call

- [x] 36. Implement AI Cost Dashboard for Users (Android + Backend)
  - [x] 36.1 Add per-user usage record model and Backend cost aggregation endpoint
    - Extend `UsageRecord` PostgreSQL model with `feature` field (enum: `chat`/`rag`/`code`/`voice`/`comparison`/`suggestions`); store one record per Message per LLM_Provider; retain =90 days
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
    - Verify loading state ? error after 10 s timeout; verify 3-alert limit error; verify persistent banner displayed and dismissed correctly; verify primary success and error `StateFlow` emissions
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
    - `SemanticSearchResult` entity: `sourceType` (enum: `CONVERSATION`/`NOTE`/`DOCUMENT`/`MEMORY`), `sourceName`, `excerpt` (=300 chars), `relevanceScore` (0.0–1.0), `deepLinkUri`
    - `SemanticSearchUseCase`: submit natural language query to Backend `/search/semantic`; return ranked `SemanticSearchResult` list filtered to score =0.5; no-op return if no results above threshold
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
    - `POST /search/semantic`: generate query embedding via SentenceTransformer; perform cosine similarity search across `conversations_{user_id}`, `notes_{user_id}`, `documents_{user_id}`, `memories_{user_id}` ChromaDB collections; return results with score =0.5 sorted descending; respond within 3 seconds for corpus =100,000 embeddings
    - Each result: `source_type`, `source_name`, `excerpt` (=300 chars), `relevance_score` (2 dp), `deep_link`
    - Omit content-type group if no embeddings exist for that type; do NOT return empty groups
    - Primary retrieval signal is vector similarity only (no keyword FTS fallback)
    - Strict user scoping: only query collections scoped to `user_id` from JWT
    - _Requirements: 36.2, 36.3, 36.5, 36.7_
  - [x] 38.4 Write unit tests for `SemanticSearchViewModel` and result grouping
    - Verify results grouped by source type with correct counts; verify "No results" state on empty response; verify navigation deep-link triggered on tap; verify primary success and error `StateFlow` emissions
    - _Requirements: 21.1, 31.4, 36.4, 36.8_
  - [x] 38.5 Write property test for semantic search round-trip retrieval (Property 35)
    - **Property 35: Semantic Search Round-Trip** — **Validates: Requirements 36.6**
    - Use Hypothesis; generate text excerpts of =10 words; store each excerpt as a content embedding in one of the four collections; submit the exact excerpt as a search query; assert the result referencing the originating item has a relevance score =0.90
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
    - `PUT /admin/privacy/epsilon`: accept new epsilon value; validate 0.1 = epsilon = 10.0 (HTTP 422 with range error if outside bounds); persist to Redis config key; apply to all subsequent embeddings within 5 seconds without service restart
    - Admin Dashboard panel: display current epsilon, noise mechanism label ("Laplace"), plain-language privacy guarantee explanation, per-user privacy budget consumed
    - _Requirements: 37.2, 37.6, 37.7_
  - [x] 39.3 Write Pytest unit tests for `LaplaceNoiseInjector` and epsilon configuration
    - Verify noise is applied independently per dimension (no inter-dimension correlation); verify epsilon=1.0 default; verify epsilon <0.1 or >10.0 returns HTTP 422; verify config change applies within 5 seconds
    - _Requirements: 21.1, 37.1, 37.2, 37.6, 37.8_
  - [x] 39.4 Write property test for differential privacy retrieval utility (Property 36)
    - **Property 36: Differential Privacy Retrieval Utility** — **Validates: Requirements 37.5**
    - Use Hypothesis; generate random unit-normalized embedding vectors; apply Laplace noise at epsilon=1.0; query ChromaDB with original unnoised vector; assert the noised embedding is returned as a top-3 result with cosine similarity =0.70

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
    - Extend `android-ci.yml`: add instrumented Compose UI test job running on Android API 34 emulator; add Detekt job scoped to changed Kotlin files; confirm JaCoCo gate (=70% `domain` + `data`) is enforced
    - Verify all required status checks are registered as branch-protection rules on `main` so that any single failure blocks merge
    - Pin all `uses:` action references to exact SHA or semver tag (e.g. `actions/checkout@v4`); replace any `@latest` or floating version references
    - _Requirements: 27.1, 27.2, 27.8_
  - [x] 41.2 Update Backend CI workflow for new requirements
    - Extend `backend-ci.yml`: add `ruff` lint step, `mypy` type check step, and Pytest coverage gate (=70%); add Pytest integration test job using PostgreSQL 16 and Redis 7 service containers
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
    - OWASP Dependency-Check: run on weekly schedule against Python + Gradle dependencies; fail on CVSS =7.0 unless suppressed in `.github/dependency-check-suppression.xml` with documented justification and expiry date
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
    - Verify ChromaDB service in `docker-compose.yml` uses port =8001 and does not conflict with backend port 8000
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
    - Run JaCoCo report; confirm combined `domain` + `data` instruction coverage =70%; fix coverage gaps if below threshold
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
    },
    {
      "wave": 11,
      "description": "On-Device RAG — database entities, engine components, domain layer, feature module, property tests, documentation",
      "tasks": ["44", "45", "46", "47", "48", "49"],
      "dependsOn": ["1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "43"]
    }
  ]
}
```
- [x] 44. Extend `core-database` module with On-Device RAG entities
  - Add `OnDeviceChunkEntity` (tableName: "on_device_chunks"): `id`, `userId`, `documentId`, `documentName`, `chunkIndex`, `pageNumber?`, `startCharOffset`, `endCharOffset`, `content`, `embeddingBlob` (ByteArray — serialized FloatArray little-endian IEEE 754 float32), `createdAt`
  - Add `OnDeviceDocumentEntity` (tableName: "on_device_documents"): `id`, `userId`, `fileName`, `mimeType`, `sizeBytes`, `totalChunks`, `ingestionStatus` ("pending"|"processing"|"ready"|"failed"), `failureStage?`, `createdAt`
  - Add `QueryRoutingLogEntity` (tableName: "query_routing_log"): `id`, `userId`, `timestamp`, `selectedPath`, `capabilityBitmask` (Int), `userOverride?`, `fallbackOccurred`, `reason`
  - Implement `OnDeviceChunkDao`: `insert()`, `getChunksForDocument(userId, documentId)`, `getAllChunks(userId)`, `deleteByDocument(userId, documentId)`, `countChunks(userId)`, `totalEmbeddingBytes(userId)`
  - Implement `OnDeviceDocumentDao`: `insert()`, `getDocuments(userId)`, `updateStatus(id, status, failureStage, totalChunks)`, `delete(id, userId)`
  - Implement `QueryRoutingLogDao`: `insert()`, `getRecentLogs(userId, limit)`, `deleteOlderThan(cutoffMs)` — 30-day retention
  - Add TypeConverter pair for `FloatArray ? ByteArray` (little-endian IEEE 754 float32): `FloatArray.size * 4` bytes per entry
  - Update `AppDatabase` to include the three new entities and expose new DAOs
  - _Requirements: 33.4, 33.5, 35.6, 36.10_

- [x] 45. Implement `core-ai` On-Device RAG engine components
  - [x] 45.1 Implement `OnDeviceEmbeddingModel` interface and MiniLM-L6-v2 implementation
    - Interface: `initialize(modelPath, expectedChecksum): ModelLoadEvent`, `generateEmbedding(text): FloatArray`, `embeddingDimension: Int`, `isReady: Boolean`
    - SHA-256 checksum verification at `initialize()` — mismatch ? `ModelLoadEvent.Failed`, triggers re-download prompt via `ManageOnDeviceModelsUseCase`
    - Deterministic: identical input ? identical output on the same device (same model weights); text truncated to 512 tokens when input exceeds limit
    - Minimum embedding dimension: 384; expose as `embeddingDimension` property
    - `EmbeddingModelModule` Hilt binding in `core-ai`
    - _Requirements: 34.1, 34.5, 34.6, 34.7_

  - [x] 45.2 Implement `LocalVectorIndex` backed by Room `OnDeviceChunkDao`
    - Interface: `addChunk(userId, chunk, embedding)`, `search(userId, queryEmbedding, k, minSimilarity)`, `deleteByDocument(userId, documentId)`, `getStats(userId): VectorIndexStats`
    - Cosine similarity: L2-normalize stored and query vectors, then compute dot product (pure in-process Kotlin)
    - User isolation enforced at SQL level (`WHERE userId = ?`) — no in-memory filter relied upon
    - `search()` returns results sorted descending by similarity, filtered by `minSimilarity = 0.40f`; returns empty list when no results meet threshold
    - `VectorIndexStats(totalChunks: Int, totalDocuments: Int, indexSizeBytes: Long)`
    - Overwrite existing entry with same `chunk.id` on `addChunk()`
    - _Requirements: 34.2, 34.3, 34.4, 34.8, 34.9, 34.10_

  - [x] 45.3 Implement `Chunker` class
    - Constructor params: `chunkSizeTokens` (default 512), `overlapTokens` (default 64), `minChunkSizeTokens` (64), `maxChunkSizeTokens` (2048)
    - Enforce `overlapTokens = chunkSizeTokens / 2` at construction with `require()`
    - `chunk(text, documentId, documentName, pageOffsets): List<TextChunk>` — union of all chunk content covers full input text with no gaps
    - Uses whitespace-based token approximation (4 chars ˜ 1 token) for on-device use
    - `PageOffset(pageNumber, startCharOffset, endCharOffset)` for page-number attribution in PDF documents
    - _Requirements: 33.4, 35.2_

  - [x] 45.4 Implement `OnDeviceInferenceEngine` interface and MediaPipe LLM Inference API implementation
    - Interface: `loadModel(modelPath, expectedChecksum): ModelLoadEvent`, `generateStream(prompt): Flow<OnDeviceStreamEvent>`, `cancelGeneration()`, `benchmarkMode(): BenchmarkResult`, `activeAccelerator(): HardwareAccelerator`, `releaseMemory()`
    - Sealed `OnDeviceStreamEvent`: `Token(text)`, `Done(tokensGenerated, generationTimeMs)`, `Error(message, stage)`, `Cancelled`
    - SHA-256 checksum verification before model load; mismatch ? `ModelLoadEvent.Failed`
    - RAM monitoring: poll `ActivityManager.MemoryInfo` every 2 seconds during generation — emit `Error(stage="ram_exceeded")` if available RAM < 512 MB; cancel generation and allow caller to fallback
    - Thermal monitoring at generation start: if `PowerManager.thermalStatus == THERMAL_STATUS_CRITICAL` defer and emit `Error(stage="thermal_critical")`; re-check every 30 seconds
    - Battery Saver mode: restrict accelerator to CPU-only when `PowerManager` reports battery saver active
    - Graceful cancellation: halt token emission within 500 ms on `cancelGeneration()` call
    - `releaseMemory()` called by lifecycle observer within 5 seconds of app going to background; model can be reloaded on next foreground request
    - `benchmarkMode()`: run 200-token fixed prompt 10 times consecutively; return mean and p95 TTFT and tokens/sec per accelerator; expose `peakRamMb`
    - `InferenceEngineModule` Hilt binding in `core-ai`
    - _Requirements: 32.1, 32.2, 32.5, 32.6, 32.7, 32.8, 32.9, 32.10, 37.1, 37.2, 37.6, 37.7_

  - [x] 45.5 Implement `QueryRouter` interface
    - Interface: `evaluate(userId, userPreference): RoutingDecision` — pure function of signals + preference; no side effects beyond repository log write
    - 4-bit bitmask: bit 0 = Gemma model files present + checksum valid, bit 1 = `EmbeddingModel.isReady`, bit 2 = `LocalVectorIndex` has =1 chunk for `userId`, bit 3 = network reachable to Backend
    - Default routing rule: bitmask == 0b1111 AND preference != PREFER_CLOUD ? `ON_DEVICE`; all other combinations ? `CLOUD`
    - Offline + on-device capable (bits 0–2 set, bit 3 unset): always `ON_DEVICE`, never queue for later
    - Records routing decision to `QueryRoutingLogEntity` via `QueryRoutingLogRepository`
    - `RoutingDecision(path: InferencePath, capabilityBitmask: Int, reason: String, fallbackOccurred: Boolean = false)`
    - _Requirements: 36.1, 36.2, 36.3, 36.4, 36.5, 36.8, 36.9, 36.10_

  - [x] 45.6 Write unit tests for all `core-ai` On-Device RAG components
    - `OnDeviceEmbeddingModel`: checksum mismatch ? `ModelLoadEvent.Failed`; identical input ? identical `FloatArray` output; truncation at 512 tokens
    - `LocalVectorIndex`: insert then search returns correct results; user A query never returns user B chunks; empty result when no chunks above threshold
    - `Chunker`: union of all chunks equals full source text (no gaps); `overlapTokens > chunkSizeTokens / 2` throws at construction; min/max chunk size respected
    - `OnDeviceInferenceEngine`: RAM < 512 MB ? `Error(stage="ram_exceeded")`; thermal critical ? `Error(stage="thermal_critical")`; Battery Saver restricts to CPU; `cancelGeneration()` halts within 500 ms; checksum mismatch ? `ModelLoadEvent.Failed`
    - `QueryRouter`: verify all 16 bitmask combinations × 3 preference options produce correct `InferencePath`
    - _Requirements: 34.5, 34.6, 34.8, 34.9, 35.2, 36.1, 36.2_

- [x] 46. Implement On-Device RAG domain and data layers
  - [x] 46.1 Add On-Device RAG domain entities, repository interfaces, and use cases to `domain` module
    - Domain entities: `OnDeviceDocument(id, userId, fileName, mimeType, sizeBytes, totalChunks, ingestionStatus, failureStage?)`, `OnDeviceModelInfo(name, version, sizeBytes, lastUsed, checksum)`, `BenchmarkResult(accelerator, ttftMeanMs, ttftP95Ms, tokensPerSecMean, tokensPerSecP95, peakRamMb)`, `RoutingDecision`, `PathPreference`, `OnDeviceQueryEvent` (sealed), `IngestionProgress` (sealed)
    - Repository interfaces: `OnDeviceDocumentRepository`, `ModelFileRepository`, `QueryRoutingLogRepository`, `QueryMetricsRepository`
    - `OnDeviceIngestDocumentUseCase` — `invoke(uri, userId): Flow<IngestionProgress>`: Parse ? Chunk ? Embed ? Index ? persist to Room; record `failureStage` on error at any stage
    - `OnDeviceQueryUseCase` — `invoke(query, userId, topK): Flow<OnDeviceQueryEvent>`: Embed query ? Search `LocalVectorIndex` ? Build context ? Stream Gemma; emit `NoRelevantContent` if no chunks above 0.40 similarity; record query metrics on `Done`
    - `RouteQueryUseCase` — `invoke(userId, preference): RoutingDecision`: delegate to `QueryRouter`, persist log entry via `QueryRoutingLogRepository`
    - `BenchmarkOnDeviceUseCase` — `invoke(): BenchmarkResult`: delegate to `OnDeviceInferenceEngine.benchmarkMode()`
    - `ManageOnDeviceModelsUseCase` — `listModels()`, `downloadModel(model, allowMetered)`, `verifyModel(model)`, `deleteModel(model)`
    - `DeleteOnDeviceDocumentUseCase` — remove all chunks from `LocalVectorIndex` and Room `OnDeviceChunkDao` within 10 seconds; update document status to deleted
    - All use cases annotated with `@Inject` constructor; add `javax.inject:javax.inject:1` to `domain/build.gradle.kts` if not already present
    - _Requirements: 32.6, 33.1, 33.7, 34.1, 35.1, 36.1_

  - [x] 46.2 Implement `OnDeviceDocumentRepositoryImpl` and `ModelFileRepositoryImpl` in `data` module
    - `OnDeviceDocumentRepositoryImpl`: wraps `OnDeviceDocumentDao` + `OnDeviceChunkDao`; no remote sync (on-device only, offline-first); exposes `Flow<List<OnDeviceDocument>>` from Room as single source of truth
    - `ModelFileRepositoryImpl`: manages Gemma and embedding model files in `getFilesDir()`; SHA-256 checksum verification on read; WorkManager download job with `NetworkType.UNMETERED` constraint; resume-from-byte on interruption
    - Wire Hilt bindings in `data/di/OnDeviceRagModule.kt`: bind repository interfaces to implementations with `@Binds` in `@InstallIn(SingletonComponent::class)` module
    - _Requirements: 33.5, 33.6, 33.7, 33.10, 37.3, 37.4, 37.5, 37.9, 37.10_

  - [x] 46.3 Write unit tests for On-Device RAG use cases
    - `OnDeviceIngestDocumentUseCase`: verify `IngestionProgress` event sequence (Parsing ? Chunking ? Embedding(n/N) ? Complete); verify failure at extraction, chunking, and embedding stages each record the correct `failureStage`; verify round-trip: TXT doc ingested and queryable by verbatim phrase
    - `OnDeviceQueryUseCase`: verify `NoRelevantContent` event when all similarity scores < 0.40; verify `ChunkCitation` list populated in `Done` event; verify Gemma engine never receives embedding/search method calls
    - `RouteQueryUseCase`: verify log entry created for every invocation; verify `ON_DEVICE` path when `bitmask == 0b1111`; verify `CLOUD` fallback recorded when any signal unset
    - `DeleteOnDeviceDocumentUseCase`: verify all chunks removed from `LocalVectorIndex` and `OnDeviceChunkDao` within 10 seconds (use `advanceTimeBy` in test coroutine scope)
    - _Requirements: 21.1, 31.2, 33.8, 35.5, 35.6, 35.7_

- [x] 47. Implement `feature-on-device-rag` module
  - [x] 47.1 Create `feature-on-device-rag` Gradle module with correct dependencies
    - Module dependencies: `feature-on-device-rag` ? `domain`, `core-ui`, `core-ai`, `core-database`
    - Explicitly exclude dependencies on other `feature-*` modules, the `data` module direct DAOs, or Backend network layer
    - Register module in `settings.gradle.kts`; wire Hilt entry point via `app` module `@HiltAndroidApp`
    - Add `feature-on-device-rag` ? `data` dependency only through `domain` repository interfaces (Hilt binding resolves at app module level)
    - _Requirements: 19.1, 19.2, 30.2_

  - [x] 47.2 Build `OnDeviceDocumentsScreen` and `OnDeviceDocumentViewModel`
    - `OnDeviceDocumentViewModel` with `StateFlow<OnDeviceDocumentUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - File picker accepting PDF, TXT, Markdown from device file picker or share intent; reject files > 50 MB with inline structured error before calling any use case
    - Document list with ingestion status badge (pending / processing / ready / failed) and chunk count for `ready` docs; use `IngestionProgress` events for live updates
    - In-progress indicator during ingestion; allow normal app interaction during background ingestion
    - Low-storage warning state: pause ingestion and show warning banner when available storage < 100 MB
    - Failed status shows `failureStage` description (extraction / chunking / embedding)
    - Delete document action: calls `DeleteOnDeviceDocumentUseCase`; removes entry from list
    - _Requirements: 33.1, 33.2, 33.3, 33.6, 33.7, 33.9, 33.10_

  - [x] 47.3 Build `OnDeviceRagChatScreen` and `OnDeviceRagViewModel`
    - `OnDeviceRagViewModel` with `StateFlow<OnDeviceRagChatUiState>`; annotated `@HiltViewModel` with `@Inject` constructor
    - On query submit: call `RouteQueryUseCase` first; display active path indicator in chat toolbar ("Running on device" / "Using cloud AI")
    - On-device path: call `OnDeviceQueryUseCase`, stream tokens to chat view incrementally; display citations via "Show sources" expandable control showing chunk text + doc name + chunk index + cosine similarity score
    - Cloud path: route through existing `SendMessageUseCase` / `AIStreamClient` infrastructure
    - Display "No relevant content found in local documents" message on `NoRelevantContent` event
    - Display non-blocking fallback notification banner when cloud-to-on-device fallback occurs (`RoutingDecision.fallbackOccurred == true`)
    - Display structured error state and "Retry via cloud" action button on `Error` event from query pipeline
    - _Requirements: 35.1, 35.4, 35.5, 35.8, 35.9, 36.5, 36.6, 36.7, 36.8_

  - [x] 47.4 Build `BenchmarkScreen` and `ManageModelsScreen`
    - `BenchmarkScreen`: accessible from Settings; trigger `BenchmarkOnDeviceUseCase`; display results table with columns: Accelerator, TTFT p50 ms, TTFT p95 ms, Tokens/sec p50, RAM Peak MB
    - `ManageModelsScreen`: list downloaded models with name, version, disk size, last-used date; delete button per model; trigger download for missing/corrupt model with progress bar (percentage + ETA)
    - Model download: WorkManager job with `NetworkType.UNMETERED`; resume from last byte on interruption; show mobile data warning dialog if on metered network
    - "Running on device" persistent indicator in chat toolbar while on-device inference is active
    - Battery Saver notice: display inline notice "Battery saver active — on-device AI uses CPU only" when applicable
    - Update model prompt: in-app notification (not push) when new model version available via `ManageOnDeviceModelsUseCase`; continue using existing model until user approves update
    - _Requirements: 32.3, 32.4, 32.5, 37.3, 37.4, 37.5, 37.8, 37.10_

  - [x] 47.5 Write unit tests for `OnDeviceRagViewModel` and `OnDeviceDocumentViewModel`
    - `OnDeviceRagViewModel`: verify "Running on device" indicator in `UiState` on `ON_DEVICE` routing decision; verify `ChunkCitation` list populated in `UiState` on `Done` event; verify error state and retry option on `Error` event; verify `NoRelevantContent` state; verify primary success and error `StateFlow` emissions using Turbine
    - `OnDeviceDocumentViewModel`: verify `UiState` status transitions (pending ? processing ? ready / failed); verify files > 50 MB trigger rejection error state; verify low-storage warning state; verify delete action removes document from list in `UiState`
    - _Requirements: 21.1, 31.4_

- [x] 48. Property-based tests for On-Device RAG correctness properties
  - [x] 48.1 Write property test for On-Device RAG Round-Trip (Property 37)
    - **Property 37: On-Device RAG Round-Trip** — **Validates: Requirements 35.10, 33.8**
    - Use Kotest PropTest; generate random TXT documents (100–2000 chars); ingest via `OnDeviceIngestDocumentUseCase`; pick verbatim 5-word phrase from document; query via `OnDeviceQueryUseCase` using mocked `OnDeviceInferenceEngine` that echoes retrieved context; assert response includes citation referencing source document name when cosine similarity threshold is met

  - [x] 48.2 Write property test for Embedding Determinism (Property 38)
    - **Property 38: Embedding Determinism** — **Validates: Requirements 34.5**
    - Use Kotest PropTest; generate random strings (1–512 chars); call `embeddingModel.generateEmbedding(text)` twice on same instance; assert `assertContentEquals(result1, result2)` for all inputs

  - [x] 48.3 Write property test for Local Vector Index User Isolation (Property 39)
    - **Property 39: Local Vector Index User Isolation** — **Validates: Requirements 34.8**
    - Use Kotest PropTest; generate two distinct user IDs (A and B); generate random chunks for user B; insert all under B's scope in an in-memory Room test database; search under user A with random query embedding (minSimilarity = 0f); assert zero results contain chunk IDs belonging to user B

  - [x] 48.4 Write property test for Query Router Path Selection Correctness (Property 40)
    - **Property 40: Query Router Path Selection Correctness** — **Validates: Requirements 36.1, 36.2**
    - Use Kotest PropTest; generate all 16 bitmask values (0–15) × 3 preference options (null, PREFER_ON_DEVICE, PREFER_CLOUD); assert `decision.path == ON_DEVICE` iff `bitmask == 15 AND preference != PREFER_CLOUD`; assert `CLOUD` in all other cases; assert no other factor influences the decision

  - [x] 48.5 Write property test for Gemma Generation-Only Isolation (Property 41)
    - **Property 41: Gemma Generation-Only Isolation** — **Validates: Requirements 35.7**
    - Use Kotest PropTest; generate random query strings and document content; use spy `OnDeviceInferenceEngine` that records all method invocations; run full `OnDeviceQueryUseCase` pipeline; assert no "generateEmbedding", "search", or "parse" method calls appear in spy recording; assert only "generateStream", "cancelGeneration", "releaseMemory" (or empty) calls are present on the engine spy

- [x] 49. On-Device RAG portfolio documentation
  - [x] 49.1 Create `docs/on-device-rag.md` portfolio documentation
    - Six implementation phases (purpose, component list, design decision + rationale per phase)
    - Mermaid architecture diagram: 6-layer stack + two query paths (on-device: Query_Router ? Embedding ? LocalVectorIndex ? Gemma; cloud: Query_Router ? Backend ? LLM_Provider)
    - Benchmark results table: columns Device Model, Chipset, Accelerator (CPU/GPU/NPU), Gemma Model Variant, TTFT p50 ms, Tokens/sec p50, RAM Peak MB; minimum 2 placeholder rows
    - Offline demo section: step-by-step (enable airplane mode, add sample docs, run sample queries, expected outputs with citations)
    - Privacy & Security section: (a) data that never leaves device, (b) model file storage + SHA-256 integrity verification, (c) user document isolation enforced at SQL layer, (d) `QueryRouter` prevents inadvertent cloud forwarding when offline
    - Reference to `benchmarks/on_device_rag_benchmark.sh` script
    - _Requirements: 38.1, 38.2, 38.5, 38.6_

  - [x] 49.2 Add `benchmarks/` directory with reproducible benchmark script
    - `benchmarks/on_device_rag_benchmark.sh`: installs app on connected device via ADB, triggers `BenchmarkOnDeviceUseCase` via ADB shell intent, captures logcat output, saves structured results to `benchmarks/results/benchmark_<timestamp>.json`
    - Document script prerequisites and usage in `docs/on-device-rag.md`
    - _Requirements: 38.8_

  - [x] 49.3 Update README with "On-Device RAG" section
    - Reference `docs/on-device-rag.md`
    - List on-device models used with version numbers (Gemma 2B/7B INT4/INT8, MiniLM-L6-v2)
    - Minimum hardware requirements (NPU/GPU =4 GB dedicated memory; CPU fallback supported)
    - Supported document formats (PDF, TXT, Markdown)
    - One-paragraph plain-language explanation: Gemma is the generation component only; a separate embedding model handles retrieval; the two never share inference calls
    - _Requirements: 38.3_

  - [x] 49.4 Add `Educational_Header` blocks to all new On-Device RAG source files
    - Every source file in `core-ai` (`OnDeviceInferenceEngine`, `OnDeviceEmbeddingModel`, `LocalVectorIndex`, `Chunker`, `QueryRouter` and their implementations), `feature-on-device-rag` (all ViewModels, Screen composables, DI modules), and `domain` (new use cases and entities) must include the 4-field educational header: purpose, architectural placement (referencing the specific layer in the 6-layer on-device stack), dependencies, design decision/pattern
    - _Requirements: 38.7, 22.4_

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
- Tasks 44–49 cover the On-Device RAG Architecture (Android-side only, no backend changes):
  - Task 44: Extend `core-database` with On-Device RAG Room entities and DAOs
  - Task 45: Implement `core-ai` On-Device RAG engine components (EmbeddingModel, LocalVectorIndex, Chunker, InferenceEngine, QueryRouter)
  - Task 46: On-Device RAG domain entities, repository interfaces, use cases, and `data` module repository implementations
  - Task 47: `feature-on-device-rag` module (DocumentsScreen, RagChatScreen, BenchmarkScreen, ManageModelsScreen)
  - Task 48: Property-based tests for Properties 37–41 (On-Device RAG Round-Trip, Embedding Determinism, User Isolation, Router Correctness, Gemma-Only Isolation)
  - Task 49: Portfolio documentation (`docs/on-device-rag.md`), benchmark script, README update, Educational_Header blocks
- All property-based tests use Kotest PropTest (Android) or Hypothesis (Backend) and are marked with their property number and the requirement they validate.
- Properties 31–36 are defined in tasks 33–39 respectively, continuing the numbering from Property 30 in task 16.
- Properties 37–41 are defined in task 48 for the On-Device RAG correctness properties.
- JaCoCo line coverage for `domain` and `data` modules must reach 70% combined before merging any PR.
- Backend Pytest line coverage must reach 70% before merging any PR.
- The `feature-on-device-ai` module (task 33) may be integrated into `core-ai` or introduced as a standalone Gradle module depending on model size and build-time constraints; the decision should be made before starting task 33.1.
- The `feature-on-device-rag` module (tasks 44–49) is a standalone Gradle module with no dependency on the Backend network layer; all inference and retrieval is performed entirely on-device.

---

## Task 50 — Screen-by-Screen UI Redesign Specification

> **Purpose:** Define a cohesive, pixel-intentional visual redesign for eight key screens, the app-wide dark-mode implementation, and the motion/animation system. All redesign work is additive to the existing Clean Architecture and Compose infrastructure; it changes only `core-ui` design tokens and per-screen composables — no ViewModel, domain, or data layer changes are required.

### Design Philosophy

The redesign elevates the app from a functional utility to a premium AI product. The guiding principles are:

- **Depth without clutter** — layered surfaces (elevation tiers), generous whitespace, and intentional color zoning replace the current flat card grid.
- **Conversational warmth** — rounded corners, soft shadows, and gradient accents signal intelligence and approachability.
- **Zero surprise motion** — every transition follows the M3 motion spec (shared element, container transform, fade-through); nothing moves purely for decoration.
- **Dark-first** — dark mode is the primary design target; light mode is a clean inversion, not an afterthought.
- **Type as hierarchy** — three weights of the brand typeface (Regular, Medium, SemiBold) drive all visual hierarchy; color is used for role, not decoration.

---

### 50.1 Design Token Additions (`core-ui`)

Before tackling individual screens, extend the existing token set in `core-ui`:

#### 50.1.1 Extended Color Tokens

Add the following to `Color.kt` and both `LightColorScheme` / `DarkColorScheme`:

| New role | Light value | Dark value | Purpose |
|---|---|---|---|
| `surfaceTonal1` | `#F4F6FF` | `#1E2030` | Cards floating above background |
| `surfaceTonal2` | `#EEF0FC` | `#252740` | Nested card surfaces |
| `surfaceTonal3` | `#E5E8F9` | `#2C2F4A` | Input field backgrounds |
| `accentGlow` | `#1B6EF5` at 12% opacity | `#ADC6FF` at 16% opacity | Ambient AI-active glow behind avatar/orb |
| `gradientStart` | `#1B6EF5` | `#5C8FFF` | Gradient primary start |
| `gradientEnd` | `#705572` | `#DDB9DF` | Gradient primary end (blue?purple brand gradient) |
| `ragAmber` | `#F59E0B` | `#FCD34D` | RAG document status: processing |
| `ragGreen` | `#10B981` | `#34D399` | RAG document status: ready |
| `ragRed` | `#EF4444` | `#F87171` | RAG document status: failed |
| `ticketOpen` | `#3B82F6` | `#60A5FA` | Ticket status: open |
| `ticketInProgress` | `#8B5CF6` | `#A78BFA` | Ticket status: in-progress |
| `ticketClosed` | `#6B7280` | `#9CA3AF` | Ticket status: closed (neutral) |
| `ticketUrgent` | `#EF4444` | `#F87171` | Ticket priority: urgent |

#### 50.1.2 Extended Elevation Tokens

Add `Elevation.kt` to `core-ui`:

```kotlin
object Elevation {
    val none   = 0.dp   // Background plane
    val low    = 1.dp   // Subtle lift: list items, dividers
    val mid    = 3.dp   // Cards, bottom-bar
    val high   = 6.dp   // Floating action elements, drawers
    val modal  = 12.dp  // Dialogs, bottom sheets
    val toast  = 24.dp  // Snackbars, toasts
}
```

#### 50.1.3 Extended Spacing Tokens

Add one token to the existing `Spacing` data class:

```kotlin
val screenEdge: Dp = 20.dp  // Replaces the 16dp screen-edge padding on redesigned screens
```

#### 50.1.4 Typography Additions

Add three `TextStyle` extensions to `Type.kt`:

| Token | Size | Weight | Use |
|---|---|---|---|
| `displayAI` | 48sp, `SemiBold` | SemiBold | Full-screen greeting or splash hero text |
| `sectionLabel` | 11sp, `Medium`, 0.8sp tracking, ALL CAPS | Medium | Section dividers, group labels |
| `chatTimestamp` | 11sp, `Normal`, 0.2sp tracking | Normal | Message timestamps, metadata |

---

### 50.2 Login Screen Redesign

**Route:** `auth/login` · **File:** `feature-auth/LoginScreen.kt`

#### Visual Layout

```
+---------------------------------+
¦  ¦¦¦¦¦¦ Blurred-mesh gradient   ¦  ? Full-bleed animated gradient background (see §50.9)
¦                                 ¦
¦   +-------------------------+   ¦
¦   ¦  ?  AI Assistant logo   ¦   ¦  ? 72dp circular logo container, accentGlow ring
¦   ¦     (brand gradient)    ¦   ¦
¦   +-------------------------+   ¦
¦                                 ¦
¦   Welcome back                  ¦  ? headlineLarge, onBackground
¦   Sign in to continue           ¦  ? bodyMedium, onSurfaceVariant
¦                                 ¦
¦  +---------------------------+  ¦
¦  ¦  ??  Email address        ¦  ¦  ? surfaceTonal3 fill, no border in idle state
¦  +---------------------------+  ¦  ? focused: 2dp primary border + label floats up
¦                                 ¦
¦  +---------------------------+  ¦
¦  ¦  ??  Password         ??  ¦  ¦  ? same treatment, trailing show/hide icon
¦  +---------------------------+  ¦
¦                                 ¦
¦  [ Forgot password? ]           ¦  ? right-aligned TextButton, labelMedium
¦                                 ¦
¦  +---------------------------+  ¦
¦  ¦       Sign In             ¦  ¦  ? full-width, gradient fill Button (gradientStart?End)
¦  +---------------------------+  ¦  ? loading state: replaces text with 20dp CircularProgress
¦                                 ¦
¦  ----------- or -----------     ¦  ? divider with centered label
¦                                 ¦
¦  +--------------------------+   ¦
¦  ¦  G  Continue with Google ¦   ¦  ? outlined, white bg / surface bg in dark mode
¦  +--------------------------+   ¦
¦                                 ¦
¦  [ ??  Use biometrics ]         ¦  ? conditional, centered, iconButton + bodySmall label
¦                                 ¦
¦  Don't have an account?         ¦
¦  [ Create Account ]             ¦  ? inline TextButton, primary color
+---------------------------------+
```

#### Interaction Details

- **Background:** Animated mesh gradient that slowly shifts hue between `gradientStart` and `gradientEnd` using a 12-second `InfiniteTransition` (see §50.9.1). In dark mode the gradient is dark-desaturated so it reads as subtle depth rather than vibrant color.
- **Logo container:** 72dp `Box` with circular `background(brush = Brush.radialGradient(...))` using brand colors. A `pulseScale` animation (0.97?1.03, 3s, `RepeatMode.Reverse`, `EaseInOutSine`) signals the AI is "alive" on the splash/login entry.
- **Text fields:** Replace `OutlinedTextField` with a custom `SurfaceFillTextField` composable (`surfaceTonal3` background, `extraSmall` shape, no visible border in idle state, `primary`-colored 2dp border on focus). The label floats to the top-left inside the field on focus/non-empty (standard M3 behavior, but styled to the new surface).
- **Sign In button:** `Button` using `Brush.linearGradient(gradientStart, gradientEnd)` as the container color via `ButtonDefaults.buttonColors(containerColor = Color.Transparent)` + `background(brush)` modifier. Includes a `CrossfadeAnimatedContent` between the label text and a `CircularProgressIndicator` during loading.
- **Error banner:** Slide-down `AnimatedVisibility` from the top of the form (see §50.9.4).
- **Biometric icon:** Only rendered when `isBiometricAvailable == true`. Uses `Icons.Filled.Fingerprint` with a scale-in `AnimatedVisibility`.

#### Accessibility

- `contentDescription = "Sign in to AI Assistant"` on the root `Column`.
- Both text fields retain all existing `semantics` content descriptions.
- The gradient button announces "Sign In button, double-tap to activate" (standard Button semantics are unchanged).

---

### 50.3 Home Dashboard Redesign

**Route:** `home` · **File:** `app/HomeDashboard.kt`

#### Visual Layout

```
+---------------------------------+
¦  Good morning, [Name]   ??  ?  ¦  ? headlineMedium greeting; trailing icon row
¦  Tuesday, August 25             ¦  ? bodySmall, onSurfaceVariant, date
¦---------------------------------¦
¦                                 ¦
¦  +--------------------------+   ¦  ? "Ask AI" hero card, full-width
¦  ¦  ?  What can I help      ¦   ¦     surfaceTonal1, shape.large, Elevation.mid
¦  ¦     you with today?      ¦   ¦     gradient left stripe (4dp, gradientStart)
¦  ¦  [ Type a message... ]   ¦   ¦     tapping navigates directly to ChatDetail (new)
¦  +--------------------------+   ¦
¦                                 ¦
¦  Quick actions -------------    ¦  ? sectionLabel
¦                                 ¦
¦  +----+ +----+ +----+ +----+   ¦  ? horizontal LazyRow of QuickActionChip
¦  ¦ ?? ¦ ¦ ?? ¦ ¦ ?? ¦ ¦ ?? ¦   ¦     (Chat / Voice / Camera / Search)
¦  ¦Chat¦ ¦Voice¦ ¦Cam ¦ ¦RAG ¦   ¦     shape.small, surfaceTonal2, 48dp height
¦  +----+ +----+ +----+ +----+   ¦
¦                                 ¦
¦  Recent conversations ------    ¦  ? sectionLabel; "See all" right-aligned TextButton
¦                                 ¦
¦  +--------------------------+   ¦  ? ConversationPreviewCard × 3 max
¦  ¦ ?? Project planning...   ¦   ¦     surfaceTonal1, shape.medium, Elevation.low
¦  ¦    2 hours ago           ¦   ¦     leading avatar with AI provider color dot
¦  +--------------------------+   ¦
¦  +--------------------------+   ¦
¦  ¦ ?? Debug this function.. ¦   ¦
¦  ¦    Yesterday             ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  Feature areas -------------    ¦  ? sectionLabel
¦                                 ¦
¦  +----------+  +----------+    ¦  ? 2-column LazyVerticalGrid, FeatureCard (redesigned)
¦  ¦ ?? Docs  ¦  ¦ ?? Code  ¦    ¦     130dp height, shape.large, surfaceTonal1
¦  ¦    & RAG ¦  ¦ Assistant¦    ¦     icon 32dp primary tint, labelMedium, Elevation.mid
¦  +----------+  +----------+    ¦     hover/press: scale 0.97 with spring animation
¦  +----------+  +----------+    ¦
¦  ¦ ? Email  ¦  ¦ ?? Notes ¦    ¦
¦  +----------+  +----------+    ¦
¦   …(remaining feature cards)…  ¦
¦                                 ¦
¦---------------------------------¦
¦  Chat ¦ History ¦ Voice ¦ Notes ¦  ? redesigned NavigationBar (see §50.3.1)
+---------------------------------+
```

#### Sub-component: NavigationBar Redesign (§50.3.1)

- Replace the plain `NavigationBar` with one using `surfaceTonal1` container color and `Elevation.mid` shadow.
- Selected indicator: pill-shaped `NavigationBarItem` indicator using `primaryContainer` fill (M3 default, already correct) but with a `scaleIn` entry animation (see §50.9.3).
- Icon size: 24dp ? 22dp to tighten the visual weight.
- Label: `labelSmall` (11sp) — unchanged size but now `Medium` weight.
- The **Tasks** tab is renamed to **Tickets** (see §50.7) and its icon changes to `Icons.Outlined.ConfirmationNumber`.

#### Sub-component: Hero "Ask AI" Card

- `Card(shape = MaterialTheme.shapes.large, elevation = Elevation.mid)` with a 4dp left-side accent stripe painted as a `Box` overlay using `gradientStart` color.
- Tapping the card navigates to `ChatRoute.detail(newConversationId)` by calling `CreateConversationUseCase` first (wired via a new `HomeDashboardViewModel` — the only ViewModel addition for this task).
- `HomeDashboardViewModel` exposes `StateFlow<HomeDashboardUiState>` containing: `userName: String`, `recentConversations: List<Conversation>` (max 3), `todayDate: String`.

#### Sub-component: QuickActionChip

```kotlin
@Composable
fun QuickActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
)
```

- `FilterChip` styled with `surfaceTonal2` background, `primary` icon tint, `labelSmall` text.
- Press animation: `scale(0.94f)` with `spring(stiffness = Spring.StiffnessHigh)`.

#### Sub-component: ConversationPreviewCard

- `ListItem` wrapped in `Card(elevation = Elevation.low, shape = MaterialTheme.shapes.medium)`.
- Leading: 40dp circular avatar showing the first letter of the conversation title on `primaryContainer` background.
- Trailing: `chatTimestamp` style text for relative time.
- Swipe-to-dismiss (right swipe) triggers `DeleteConversationUseCase` with an `undoSnackbar`.

---

### 50.4 Chat Screen Redesign

**Routes:** `chat/list` · `chat/detail/{conversationId}` · **Files:** `feature-chat/ChatListScreen.kt`, `feature-chat/ChatDetailScreen.kt`

#### 50.4.1 Chat List Screen

```
+---------------------------------+
¦  Conversations          [ + ]   ¦  ? titleLarge; trailing FAB-style icon (not floating)
¦---------------------------------¦
¦  +---------------------------+  ¦  ? SearchBar (M3 SearchBar, not OutlinedTextField)
¦  ¦ ??  Search conversations  ¦  ¦     surfaceTonal3 fill, shape.large, no border
¦  +---------------------------+  ¦     expands full-screen on focus (M3 SearchBar behavior)
¦                                 ¦
¦  Pinned --------------------    ¦  ? sectionLabel (only shown when pins exist)
¦  +--------------------------+   ¦
¦  ¦ ?? Project Kickoff       ¦   ¦  ? surfaceTonal2 bg (tinted) to distinguish pinned
¦  ¦    3 conversations       ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  Today ----------------------   ¦  ? sectionLabel
¦  +--------------------------+   ¦  ? ConversationRow (redesigned):
¦  ¦ ?  Project planning      ¦   ¦     40dp AI avatar (provider-colored dot badge)
¦  ¦    "Can you break down…" ¦   ¦     bodySmall preview text, 1-line truncated
¦  ¦                  2h ago  ¦   ¦     chatTimestamp, right-aligned
¦  +--------------------------+   ¦
¦  +--------------------------+   ¦
¦  ¦ ?  Refactor auth flow    ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  Yesterday ------------------   ¦
¦  …                              ¦
+---------------------------------+
```

**Interaction details:**
- Replace `OutlinedTextField` search with `SearchBar` (M3 `SearchBar` composable) that expands to full-screen overlay with `AnimatedContent` slide-up (see §50.9.4).
- `ConversationRow` uses `SwipeRevealLayout` showing Pin, Rename, Delete actions on trailing swipe (colored action tiles: `primaryContainer`, `secondaryContainer`, `errorContainer`).
- Long-press opens a `ModalBottomSheet` (M3) with the same three actions plus "Export".
- Empty state: centered illustration (AI assistant waving, 120dp `Image` composable using vector drawable) + `headlineSmall` "No conversations yet" + `bodyMedium` suggestion.

#### 50.4.2 Chat Detail Screen

```
+---------------------------------+
¦  ?  Project planning    ···  ?  ¦  ? TopAppBar: back, title (truncated), overflow, provider
¦---------------------------------¦
¦  +- Running on device ------+   ¦  ? conditional on-device indicator (M3 banner, tonal)
¦  +--------------------------+   ¦
¦                                 ¦
¦             [8:42 AM]           ¦  ? chatTimestamp, centered, onSurfaceVariant
¦                                 ¦
¦  +--------------------------+   ¦  ? user MessageBubble
¦  ¦ Can you break down this  ¦   ¦     primaryContainer bg, right-aligned
¦  ¦ project into milestones? ¦   ¦     shape.medium with 4dp top-right corner = 2dp
¦  +--------------------------+   ¦     (M3 "tail" effect via asymmetric RoundedCornerShape)
¦                                 ¦
¦  ?  +------------------------+  ¦  ? assistant MessageBubble
¦     ¦ Sure! Here's a plan:   ¦  ¦     surfaceTonal1 bg, left-aligned, AI avatar 32dp
¦     ¦                        ¦  ¦     leading the bubble row
¦     ¦ **Phase 1 — Discovery**¦  ¦
¦     ¦ - Stakeholder mapping  ¦  ¦     MarkdownText rendering
¦     ¦ - Requirements doc     ¦  ¦
¦     ¦ - Timeline draft       ¦  ¦
¦     ¦                        ¦  ¦
¦     ¦ ?? 3 sources  •  14:2s ¦  ¦  ? citation count + latency chip (if RAG)
¦     ¦  [Copy] [Share] [?]    ¦  ¦  ? action row, bodySmall icons + labels
¦     +------------------------+  ¦
¦                                 ¦
¦  ?  ¦                           ¦  ? streaming: blinking cursor after last token
¦                                 ¦
¦  …(typing indicator: 3-dot      ¦  ? animated 3-dot pulse (see §50.9.5)
¦     pulse before first token)   ¦
¦                                 ¦
¦---------------------------------¦
¦  +---------------------------+  ¦  ? MessageInputBar (redesigned)
¦  ¦ Message AI Assistant… ?? ¦  ¦     surfaceTonal3 bg, shape.extraLarge (pill)
¦  ¦                        ? ¦  ¦     trailing: mic icon (voice input) + send icon
¦  +---------------------------+  ¦     send icon: primary tint when non-empty, disabled tint when empty
¦  [??] [??] [?Compare] [+More]  ¦  ? accessory row above keyboard
+---------------------------------+
```

**Sub-component: MessageInputBar**

```kotlin
@Composable
fun MessageInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceInput: () -> Unit,
    onAttach: () -> Unit,
    isEnabled: Boolean,
    characterLimit: Int = 32_000,
    modifier: Modifier = Modifier
)
```

- Character counter appears at `28,000 / 32,000` threshold: `labelSmall`, `error` color when over `30,000`, hides when under threshold.
- Send button: `FilledIconButton` (M3) with `gradientStart` container when non-empty, `surfaceVariant` when empty, animated via `animateColorAsState`.
- The outer pill shape uses `shape.extraLarge` (28dp radius).

**Sub-component: ChatBubble redesign**

- Extend the existing `ChatBubble` composable in `core-ui/components/ChatBubble.kt`:
  - Add `bubbleTailPosition: BubbleTailPosition` enum (`NONE`, `USER`, `ASSISTANT`).
  - User bubble: `RoundedCornerShape(topStart=16dp, topEnd=4dp, bottomEnd=16dp, bottomStart=16dp)`.
  - Assistant bubble: `RoundedCornerShape(topStart=4dp, topEnd=16dp, bottomEnd=16dp, bottomStart=16dp)`.
  - Background: user = `primaryContainer`, assistant = `surfaceTonal1`.
  - Add `onLongPress` callback opening the message action `ModalBottomSheet`.

---

### 50.5 RAG Search Screen Redesign

**Routes:** `rag/documents` · `rag/documents/{id}/chat` · **Files:** `feature-rag/DocumentListScreen.kt`, `feature-rag/DocumentChatScreen.kt`

#### 50.5.1 Document List Screen

```
+---------------------------------+
¦  ?  Documents              [+]  ¦  ? TopAppBar; [+] = upload FAB inlined in bar
¦---------------------------------¦
¦  +---------------------------+  ¦  ? StorageSummaryCard
¦  ¦  ??  3 documents  •  8 MB ¦  ¦     surfaceTonal1, shape.large
¦  ¦  ¦¦¦¦¦¦¦¦¦¦¦¦¦ 8/50 MB   ¦  ¦     LinearProgressIndicator showing storage used
¦  +---------------------------+  ¦
¦                                 ¦
¦  +---------------------------+  ¦  ? SearchBar (same as Chat list)
¦  ¦ ??  Search documents      ¦  ¦
¦  +---------------------------+  ¦
¦                                 ¦
¦  Your documents -------------   ¦  ? sectionLabel
¦                                 ¦
¦  +--------------------------+   ¦  ? DocumentCard
¦  ¦ ??  Q3_Report.pdf        ¦   ¦     shape.medium, surfaceTonal1, Elevation.low
¦  ¦     PDF  •  2.4 MB       ¦   ¦     leading file-type icon with type-colored tint
¦  ¦     ? Ready              ¦   ¦     status badge: ragGreen dot + "Ready"
¦  ¦     Uploaded 2 days ago  ¦   ¦     chatTimestamp for upload date
¦  ¦  [Ask AI ›]  [?]         ¦   ¦     primary TextButton + overflow DropdownMenu
¦  +--------------------------+   ¦
¦                                 ¦
¦  +--------------------------+   ¦
¦  ¦ ??  Meeting_Notes.docx   ¦   ¦
¦  ¦     DOCX  •  180 KB      ¦   ¦
¦  ¦     ? Processing…   [??] ¦   ¦     ragAmber dot; indeterminate LinearProgress
¦  ¦     Uploaded just now    ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  +--------------------------+   ¦
¦  ¦ ??  spec_draft.txt       ¦   ¦
¦  ¦     TXT  •  42 KB        ¦   ¦
¦  ¦     ? Failed             ¦   ¦     ragRed dot; "Retry" TextButton
¦  ¦  [Retry]  [Delete]       ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦       [ Upload Document ]       ¦  ? secondary gradient-outlined Button, centered
¦       PDF, DOCX, TXT, MD =50MB  ¦  ? bodySmall hint below button
+---------------------------------+
```

**Interaction details:**
- FAB becomes a `SmallFloatingActionButton` docked inside the TopAppBar trailing slot (saves vertical space).
- `DocumentCard` uses a `SwipeRevealLayout` trailing action for Delete (single action, `errorContainer` tile).
- Status badge uses `AnimatedContent` to cross-fade between states as the polling result updates.
- "Processing" state shows an indeterminate `LinearProgressIndicator` in `ragAmber` color via `ProgressIndicatorDefaults.colors(trackColor = ragAmber)`.
- File picker launches a `ModalBottomSheet` (`FilePickerBottomSheet`, already implemented) with a redesigned header using the new tokens.

#### 50.5.2 Document Chat Screen (RAG Query)

```
+---------------------------------+
¦  ?  Q3_Report.pdf  ? Ready      ¦  ? TopAppBar; status dot inline with title
¦---------------------------------¦
¦                                 ¦
¦  +--------------------------+   ¦  ? DocumentContextBanner
¦  ¦ ?? Searching across      ¦   ¦     surfaceTonal2, shape.small, collapsible
¦  ¦    Q3_Report.pdf         ¦   ¦     "Collapse" toggle on right
¦  +--------------------------+   ¦
¦                                 ¦
¦  …chat messages identical to    ¦
¦    Chat Detail layout (§50.4.2) ¦
¦    but assistant bubbles show:  ¦
¦                                 ¦
¦  ?  +------------------------+  ¦
¦     ¦ According to page 14:  ¦  ¦
¦     ¦ "Revenue grew 23%..."  ¦  ¦     blockquote style: 4dp left border, indented
¦     ¦                        ¦  ¦     using `surfaceTonal2` left-border Box overlay
¦     ¦ ? Sources (2)          ¦  ¦  ? collapsible `SourcesPanel`:
¦     ¦   • Page 14, para 2    ¦  ¦     ChunkCitation chip per source, ragGreen tint
¦     ¦   • Page 22, fig. 3    ¦  ¦     Tapping a citation shows full chunk text in
¦     +------------------------+  ¦     a `ModalBottomSheet`
¦                                 ¦
¦---------------------------------¦
¦  [  Ask about this document…  ] ¦  ? same MessageInputBar (§50.4.2)
+---------------------------------+
```

**Sub-component: SourcesPanel**

```kotlin
@Composable
fun SourcesPanel(
    citations: List<ChunkCitation>,
    onCitationTap: (ChunkCitation) -> Unit,
    modifier: Modifier = Modifier
)
```

- Collapsed by default; animated height via `animateFloatAsState` on `heightFraction`.
- Each citation is an `AssistChip` (M3) with `ragGreen` border and document-page label.

---

### 50.6 Profile Screen Redesign

**Route:** `profile` · **File:** `feature-profile/ProfileScreen.kt`

#### Visual Layout

```
+---------------------------------+
¦  ?  Profile                     ¦  ? TopAppBar (no trailing actions)
¦---------------------------------¦
¦                                 ¦
¦         +----------+            ¦  ? 88dp avatar circle
¦         ¦  [Photo] ¦            ¦     ElevatedCard shape=CircleShape Elevation.high
¦         ¦  or init ¦            ¦     gradient fill (gradientStart?End) when no photo
¦         +----------+            ¦     "Edit" badge: 28dp circle bottom-right, surfaceTonal3
¦         [?]                     ¦
¦                                 ¦
¦         Alex Johnson            ¦  ? headlineMedium, center-aligned
¦         alex@example.com        ¦  ? bodyMedium, onSurfaceVariant, center
¦         Premium  •  Joined 2024 ¦  ? labelMedium, secondaryContainer chip + bodySmall date
¦                                 ¦
¦---------------------------------¦
¦                                 ¦
¦  AI Memory -----------------    ¦  ? sectionLabel
¦                                 ¦
¦  +--------------------------+   ¦  ? MemorySummaryCard
¦  ¦ ??  23 memories stored   ¦   ¦     surfaceTonal1, shape.large, Elevation.mid
¦  ¦  Prefers concise answers ¦   ¦     top-3 memories shown inline as chips
¦  ¦  [ Python developer ]    ¦   ¦
¦  ¦  [ Uses dark mode ]      ¦   ¦
¦  ¦             [Manage all] ¦   ¦     right-aligned TextButton ? MemoryListScreen
¦  +--------------------------+   ¦
¦                                 ¦
¦  Account --------------------   ¦  ? sectionLabel
¦                                 ¦
¦  +--------------------------+   ¦  ? SettingsGroup card, surfaceTonal1, shape.large
¦  ¦  ?? Change password    › ¦   ¦     each row = ListItem with trailing Icon(chevron)
¦  ¦-------------------------¦   ¦
¦  ¦  G  Google account     › ¦   ¦     Google linked: green check badge; unlinked: "Link"
¦  ¦-------------------------¦   ¦
¦  ¦  ?? Export my data     › ¦   ¦
¦  ¦-------------------------¦   ¦
¦  ¦  ??  Delete account       ¦   ¦     error color icon + label; opens ConfirmationDialog
¦  +--------------------------+   ¦
¦                                 ¦
¦  +--------------------------+   ¦  ? sign-out row, separate card
¦  ¦  Sign Out                ¦   ¦     errorContainer subtle tint, centered label
¦  +--------------------------+   ¦
+---------------------------------+
```

**Sub-component: MemorySummaryCard**

- Each memory fact is an `InputChip` (M3) with a trailing `close` icon to delete inline.
- Chip list uses `FlowRow` layout (Compose Foundation) to wrap chips naturally.
- Tapping "Manage all" navigates to `MemoryListScreen` using a `SharedTransitionLayout` hero animation on the card (see §50.9.2).

**Sub-component: SettingsGroup**

```kotlin
@Composable
fun SettingsGroup(
    items: List<SettingsGroupItem>,
    modifier: Modifier = Modifier
)
```

A `Column` inside a `Card`. Each `SettingsGroupItem` is a `ListItem` with optional `icon`, `title`, `subtitle`, `trailing` slot, and `onClick`. Dividers between rows use `HorizontalDivider` at 1dp / `outlineVariant`.

---

### 50.7 Tickets Screen — New Feature

> **Note:** The "Tasks / Productivity" navigation tab (currently labelled **Tasks**) is repurposed and renamed to **Tickets**. This is the existing `feature-productivity` module's `TodoList` screen, elevated into a full ticket-management UI. No new module is created; the screen composables in `feature-productivity` are redesigned in-place.

**Route:** `productivity/list` · **File:** `feature-productivity/TodoListScreen.kt` (rename to `TicketsScreen.kt`)

#### Visual Layout

```
+---------------------------------+
¦  Tickets                 [?] [+]¦  ? titleLarge; [?] = AI Generate; [+] = New Ticket
¦---------------------------------¦
¦  +-----+ +---------+ +-------+ ¦  ? FilterChipRow (horizontal scroll)
¦  ¦ All ¦ ¦ Open    ¦ ¦ In    ¦ ¦     "All" selected by default
¦  ¦  12 ¦ ¦   7     ¦ ¦Progress¦ ¦     count badge inside each chip using labelSmall
¦  +-----+ +---------+ +-------+ ¦
¦  +------+ +--------+            ¦
¦  ¦ Done ¦ ¦ Urgent ¦            ¦
¦  ¦   3  ¦ ¦   2    ¦            ¦
¦  +------+ +--------+            ¦
¦                                 ¦
¦  [ ??  Search tickets…    ]     ¦  ? SearchBar
¦                                 ¦
¦  Urgent ---------------------   ¦  ? sectionLabel (only when Urgent tickets exist)
¦                                 ¦
¦  +--------------------------+   ¦  ? TicketCard (urgent)
¦  ¦ ?? Fix auth token expiry ¦   ¦     left-border 4dp ticketUrgent color
¦  ¦    #T-042               ¦   ¦     ticket ID in labelSmall, onSurfaceVariant
¦  ¦    Open  •  Due Today    ¦   ¦     status chip + due date
¦  ¦    Assigned: You         ¦   ¦     assignee row
¦  ¦    [ ? In Progress ]     ¦   ¦     quick-action button (moves to next status)
¦  +--------------------------+   ¦
¦                                 ¦
¦  Open ------------------------  ¦
¦                                 ¦
¦  +--------------------------+   ¦  ? TicketCard (open)
¦  ¦ ?? Add dark mode support ¦   ¦     left-border ticketOpen color
¦  ¦    #T-041  •  Open       ¦   ¦
¦  ¦    Due Aug 30            ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  +--------------------------+   ¦  ? TicketCard (in-progress)
¦  ¦ ?? Redesign profile page ¦   ¦     left-border ticketInProgress color
¦  ¦    #T-039  •  In Progress¦   ¦
¦  +--------------------------+   ¦
+---------------------------------+
```

#### Sub-component: TicketCard

```kotlin
@Composable
fun TicketCard(
    ticket: TodoItem,
    onStatusChange: (TodoStatus) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
)
```

- `Card(shape = MaterialTheme.shapes.medium, elevation = Elevation.low)` with a 4dp left accent `Box` overlay colored per `ticket.priority`:
  - `URGENT` ? `ticketUrgent`
  - `HIGH` ? `ticketOpen`
  - `MEDIUM` ? `ticketInProgress`
  - `LOW` ? `NeutralVariant60`
- Status `FilterChip` (non-interactive display) shows current status with matching `ticketOpen` / `ticketInProgress` / `ticketClosed` color.
- "Quick move" `Button` text: Open ? "Start" (moves to In Progress), In Progress ? "Resolve" (moves to Done).
- Long-press opens a `ModalBottomSheet` with full ticket actions: Edit, Duplicate, Move to status, Delete.
- Swipe-left reveals a single Delete action tile (`errorContainer`).

#### Ticket Detail Screen

When a `TicketCard` is tapped, navigate to a `TicketDetailScreen` (new composable):

```
+---------------------------------+
¦  ?  #T-042              ?? Urg  ¦  ? TopAppBar; priority badge chip
¦---------------------------------¦
¦                                 ¦
¦  Fix auth token expiry          ¦  ? headlineMedium
¦                                 ¦
¦  Status   [ Open ? ]            ¦  ? inline status selector (DropdownMenu)
¦  Due       Aug 30, 2026         ¦  ? bodyMedium
¦  Assigned  You                  ¦
¦  Tags      [ auth ] [ backend ] ¦  ? InputChip tags (FlowRow)
¦                                 ¦
¦  Description ----------------   ¦  ? sectionLabel
¦  The JWT access token does not  ¦
¦  refresh correctly after 15 min ¦  ? bodyMedium
¦                                 ¦
¦  ? AI Actions ---------------  ¦  ? sectionLabel
¦  +--------------------------+   ¦  ? AI suggestion chips
¦  ¦ Draft a fix plan         ¦   ¦     AssistChip row
¦  ¦ Break into subtasks      ¦   ¦
¦  ¦ Estimate effort          ¦   ¦
¦  +--------------------------+   ¦
¦                                 ¦
¦  [  Save Changes  ]             ¦  ? full-width gradient Button
+---------------------------------+
```

---

### 50.8 Dark Mode Specification

**Files:** `core-ui/Color.kt`, `core-ui/AppTheme.kt`, `feature-settings/SettingsScreen.kt`

#### Color Scheme Audit

The existing `DarkColorScheme` is largely correct. Apply the following targeted corrections for the redesign:

| Role | Current dark value | Corrected dark value | Reason |
|---|---|---|---|
| `background` | `Neutral10 = #1A1B1F` | `#111318` | Deeper true-dark base; OLED-friendly |
| `surface` | `#1A1B1F` | `#111318` | Matches background for seamless base layer |
| `surfaceTonal1` | (new token) | `#1E2030` | First elevation above background; blue-shifted dark |
| `surfaceTonal2` | (new token) | `#252740` | Second elevation |
| `surfaceTonal3` | (new token) | `#2C2F4A` | Input field backgrounds (higher saturation so fields are visible) |
| `onBackground` | `Neutral90 = #E3E2E6` | `#E2E2E9` | Slightly cooler for dark-mode readability |
| `outlineVariant` | `NeutralVariant30 = #44474F` | `#3A3D4A` | Less prominent dividers on dark surfaces |

#### Dark Mode Surface Layering Model

The redesigned app uses a **5-tier surface model** to create depth on OLED screens:

| Tier | Token | Dark Value | Used For |
|---|---|---|---|
| 0 | `background` | `#111318` | Root scaffold background |
| 1 | `surface` | `#111318` | Same as background (M3 spec) |
| 2 | `surfaceTonal1` | `#1E2030` | Cards, bottom bar, side rail |
| 3 | `surfaceTonal2` | `#252740` | Nested card surfaces, modal sheets |
| 4 | `surfaceTonal3` | `#2C2F4A` | Input fields, code block backgrounds |

All existing composables that currently use `surfaceVariant` as card background are migrated to `surfaceTonal1` during the redesign.

#### Theme Toggle UX

The theme toggle in `SettingsScreen` is redesigned from a plain `RadioButton` group to an `icon-button trio`:

```
  ?  Light    ?  System    ?  Dark
  [ ] --------[?]----------[ ]
```

Each option is a `SegmentedButton` (M3 `SingleChoiceSegmentedButtonRow`) with icon + label. The selected state uses `primaryContainer` fill with an animated `colorAnimation` (see §50.9.6).

#### Gradient and Glow Behavior in Dark Mode

- The login background gradient uses desaturated variants: `#1A2040` ? `#2A1A30` (subtle blue-to-purple).
- The `accentGlow` ring around the logo orb is more visible in dark mode (16% opacity vs 12% in light).
- The "Ask AI" hero card gradient stripe uses `gradientStart` at full opacity in both modes.
- `ChatBubble` user bubbles use `primary` at 90% opacity for the background in dark mode (avoid pure `primaryContainer` which can be too saturated).

#### Contrast Validation Checklist

All text/icon-on-background combinations must satisfy WCAG 2.1 AA (4.5:1 normal, 3:1 large):

| Pair | Dark mode ratio | Result |
|---|---|---|
| `onBackground` (#E2E2E9) on `background` (#111318) | ~14.5:1 | ? AAA |
| `primary` (#ADC6FF) on `background` (#111318) | ~9.3:1 | ? AAA |
| `onSurfaceVariant` on `surfaceTonal1` (#1E2030) | ~8.1:1 | ? AAA |
| `chatTimestamp` `onSurfaceVariant` on `surfaceTonal1` | ~6.2:1 | ? AA |
| `ragAmber` on `surfaceTonal1` | ~4.8:1 | ? AA |

---

### 50.9 Animation and Motion Specification

**Files:** `core-ui/motion/` (new package), individual screen composables

#### 50.9.1 Animated Mesh Gradient Background (Login, Splash)

```kotlin
// core-ui/motion/MeshGradientBackground.kt

@Composable
fun MeshGradientBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "meshGradient")

    val color1 by infiniteTransition.animateColor(
        initialValue = gradientStart,
        targetValue = gradientEnd,
        animationSpec = infiniteRepeatable(
            animation = tween(12_000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "color1"
    )
    // Rendered as Brush.sweepGradient or radialGradient over a full-screen Canvas
    // In dark mode: colors are pre-multiplied by 0.35f alpha to prevent harshness
}
```

- Duration: 12 seconds per cycle, `RepeatMode.Reverse`.
- Performance: `Canvas` draw, no Compose recomposition on each frame (uses `drawBehind` with `rememberUpdatedState`).
- Reduced motion: when `LocalReducedMotionEnabled.current == true`, render static gradient, no animation.

#### 50.9.2 Shared Element Transition (Home ? Chat, Home ? Profile)

Implement `SharedTransitionLayout` (available in `androidx.compose.animation:animation 1.7+`) for:

- **Home ? Chat Detail:** The "Ask AI" hero card morphs into the `ChatDetailScreen` TopAppBar via container transform.
- **Profile card ? Memory List:** The `MemorySummaryCard` morphs into the `MemoryListScreen` background card.

```kotlin
// Usage pattern:
SharedTransitionLayout {
    AnimatedContent(targetState = currentDestination) { destination ->
        when (destination) {
            Destination.Home -> {
                HeroCard(
                    modifier = Modifier.sharedElement(
                        state = rememberSharedContentState("hero-card"),
                        animatedVisibilityScope = this
                    )
                )
            }
            Destination.ChatDetail -> {
                ChatDetailTopBar(
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState("hero-card"),
                        animatedVisibilityScope = this
                    )
                )
            }
        }
    }
}
```

- Duration: 350ms, `FastOutSlowInEasing`.
- Fallback for Compose versions < 1.7: use `Crossfade` with 250ms duration.

#### 50.9.3 Navigation Indicator Animation (Bottom Nav)

The `NavigationBarItem` selected indicator pill uses:

- **Enter:** `scaleIn(initialScale = 0.6f, animationSpec = spring(stiffness = Spring.StiffnessMedium))` + `fadeIn(tween(150))`
- **Exit:** `scaleOut(targetScale = 0.6f)` + `fadeOut(tween(100))`

Implemented by wrapping the indicator composable slot with `AnimatedContent`.

#### 50.9.4 Screen Entry / Exit Transitions

Define a sealed class `AppTransition` in `core-ui/motion/AppTransition.kt`:

```kotlin
sealed class AppTransition {

    /** Standard forward push: new screen slides in from right */
    data object Push : AppTransition()

    /** Back navigation: current screen slides out to right */
    data object Pop : AppTransition()

    /** Modal entry: sheet/dialog slides up from bottom */
    data object Modal : AppTransition()

    /** Fade-through for tab switches (M3 spec) */
    data object FadeThrough : AppTransition()
}
```

Apply to `NavHost` via the `enterTransition` / `exitTransition` parameters:

| Navigation event | Enter | Exit |
|---|---|---|
| Forward push (new screen) | `slideInHorizontally { fullWidth } + fadeIn(tween(300))` | `slideOutHorizontally { -fullWidth/3 } + fadeOut(tween(200))` |
| Back pop | `slideInHorizontally { -fullWidth/3 } + fadeIn(tween(200))` | `slideOutHorizontally { fullWidth } + fadeOut(tween(300))` |
| Tab switch | `fadeIn(tween(200)) + scaleIn(0.92f, tween(200))` | `fadeOut(tween(150)) + scaleOut(0.92f, tween(150))` |
| Modal bottom sheet | (M3 `ModalBottomSheet` handles internally) | — |

Duration values are chosen to be fast enough not to feel sluggish on mid-range devices (300ms push, 200ms pop).

#### 50.9.5 Typing Indicator Animation (Chat)

```kotlin
// core-ui/components/TypingIndicator.kt

@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    // Three dots, each animating vertically -6dp?0dp with 200ms stagger
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(500, delayMillis = index * 150, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot$index"
            )
            Box(
                Modifier
                    .size(8.dp)
                    .offset(y = offsetY.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            )
        }
    }
}
```

- Three dots, 8dp each, 4dp gap.
- Stagger: 0ms, 150ms, 300ms — creates a wave effect.
- Reduced motion: render three static dots, no animation.

#### 50.9.6 Theme Switch Animation

When `ThemeMode` changes (via Settings segmented button), apply a `Crossfade` at the root `AppTheme` level:

```kotlin
@Composable
fun AppTheme(themeMode: ThemeMode = ThemeMode.SYSTEM, ...) {
    Crossfade(
        targetState = isDark,
        animationSpec = tween(400, easing = FastOutSlowInEasing),
        label = "themeSwitch"
    ) { dark ->
        val scheme = if (dark) DarkColorScheme else LightColorScheme
        CompositionLocalProvider(...) {
            MaterialTheme(colorScheme = scheme, ...) {
                content()
            }
        }
    }
}
```

Duration: 400ms — long enough to be visible but not disruptive.

#### 50.9.7 Card Press Feedback (FeatureCard, TicketCard, DocumentCard)

Apply a uniform press-scale effect using `Modifier.graphicsLayer`:

```kotlin
fun Modifier.pressScale(
    targetScale: Float = 0.96f,
    stiffness: Float = Spring.StiffnessHigh
): Modifier = composed {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) targetScale else 1f,
        animationSpec = spring(stiffness = stiffness),
        label = "pressScale"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    pressed = true
                    tryAwaitRelease()
                    pressed = false
                }
            )
        }
}
```

Use `targetScale = 0.96f` for large cards, `0.94f` for small chips.

#### 50.9.8 Reduced Motion Support

All animation composables check `LocalReducedMotionEnabled`:

```kotlin
// core-ui/motion/ReducedMotion.kt
val LocalReducedMotionEnabled = compositionLocalOf { false }

// In AppTheme, provide based on system setting:
val reduceMotion = LocalContext.current.resources
    .configuration
    .isAccessibilityFeatureEnabled(ACCESSIBILITY_SERVICE_ANIMATIONS_DISABLED)
    // Or use the Accompanist / official API when available
CompositionLocalProvider(LocalReducedMotionEnabled provides reduceMotion) { ... }
```

When `LocalReducedMotionEnabled.current == true`:
- `MeshGradientBackground`: static gradient.
- `TypingIndicator`: three static dots.
- `SharedTransitionLayout`: instant switch (0ms `tween`).
- Navigation transitions: `fadeIn/fadeOut` only (no slide/scale).
- Theme switch: instant (0ms `tween`).

---

### 50.10 Implementation Tasks

> These are the actionable sub-tasks for the redesign. Each maps to one or more of the screen specs above.

- [x] 50.1 Extend `core-ui` design tokens
  - Add `surfaceTonal1/2/3`, `accentGlow`, `gradientStart/End`, `ragAmber/Green/Red`, `ticketOpen/InProgress/Closed/Urgent` color tokens to `Color.kt` and both color schemes
  - Add `Elevation.kt` with 6-tier elevation constants
  - Add `screenEdge` spacing token to `Spacing.kt`
  - Add `displayAI`, `sectionLabel`, `chatTimestamp` text styles to `Type.kt`
  - Create `core-ui/motion/` package with `MeshGradientBackground.kt`, `AppTransition.kt`, `ReducedMotion.kt`, `TypingIndicator.kt`
  - Add `pressScale` modifier extension to a new `core-ui/motion/Modifiers.kt`
  - _Requirements: 24.1, 24.2, 24.3_

- [x] 50.2 Redesign Login Screen
  - Replace static background with `MeshGradientBackground` composable
  - Replace `OutlinedTextField` instances with new `SurfaceFillTextField` composable (add to `core-ui/components/`)
  - Replace plain `Button` with gradient-fill button (using `Brush.linearGradient` modifier pattern)
  - Add `CrossfadeAnimatedContent` for loading state inside Sign In button
  - Replace static Google sign-in button with outlined variant following new design tokens
  - Add `AnimatedVisibility` slide-down for `ErrorBanner`
  - Add brand logo `pulseScale` animation
  - _Requirements: 1.1, 1.6, 1.7, 24.1, 24.3_

- [x] 50.3 Redesign Home Dashboard with new `HomeDashboardViewModel`
  - Create `HomeDashboardViewModel` (Hilt) exposing `userName`, `recentConversations` (max 3), `todayDate`
  - Add hero "Ask AI" card with gradient accent stripe and navigation to new `ChatDetail`
  - Replace `LazyVerticalGrid` feature cards with redesigned `FeatureCard` composable using new tokens and `pressScale` modifier
  - Add `QuickActionChip` horizontal `LazyRow`
  - Add `ConversationPreviewCard` list (max 3) with swipe-to-dismiss
  - Apply `pressScale` to all tappable cards
  - Redesign `NavigationBar` with new surface tokens and animated selected indicator
  - Rename "Tasks" tab to "Tickets" with `Icons.Outlined.ConfirmationNumber` icon
  - Apply navigation transitions via `AppTransition` in root `NavHost`
  - _Requirements: 19.1, 24.1, 24.2_

- [x] 50.4 Redesign Chat Screens
  - Replace `OutlinedTextField` search with M3 `SearchBar` in `ChatListScreen`
  - Add `SwipeRevealLayout` composable (new, in `core-ui/components/`) for swipe-to-action on list rows
  - Extend `ChatBubble` in `core-ui` with asymmetric corner radii, provider avatar, long-press action callback
  - Build redesigned `MessageInputBar` composable in `feature-chat` with pill shape, character counter, gradient send button
  - Add accessory row (camera, attach, compare, more) above keyboard in `ChatDetailScreen`
  - Add `TypingIndicator` to `ChatDetailScreen` before first streaming token
  - Integrate `SharedTransitionLayout` hero transition from Home hero card to `ChatDetailScreen`
  - _Requirements: 2.1, 2.2, 2.5, 24.1, 24.3_

- [x] 50.5 Redesign RAG Search Screens
  - Add `StorageSummaryCard` to `DocumentListScreen` showing document count + storage `LinearProgressIndicator`
  - Replace `DocumentCard` styling with new token-based design (status badge colors, `SwipeRevealLayout` delete)
  - Animate status badge transitions with `AnimatedContent`
  - Build `SourcesPanel` composable for collapsible RAG citations in `DocumentChatScreen`
  - Add blockquote styling to assistant bubbles in `DocumentChatScreen` (4dp left-border Box overlay)
  - _Requirements: 4.1, 4.6, 4.7, 24.1_

- [ ] 50.6 Redesign Profile Screen
  - Redesign avatar section with gradient fill, `ElevatedCard` shape, edit badge
  - Add account tier chip (`Premium` / `Free` `SuggestionChip`)
  - Build `MemorySummaryCard` with `FlowRow` chip layout and `SharedTransitionLayout` hero to `MemoryListScreen`
  - Build `SettingsGroup` composable for grouped action rows
  - Separate sign-out into its own card with `errorContainer` styling
  - _Requirements: 7.3, 7.4, 28.1, 28.2, 24.1_

- [ ] 50.7 Redesign Tickets Screen (repurposed from Productivity Tasks)
  - Rename `TodoListScreen.kt` ? `TicketsScreen.kt`; update navigation route references
  - Build `FilterChipRow` for status/priority filters with count badges
  - Build `TicketCard` composable with priority-colored left border accent, `pressScale` modifier, quick-move `Button`
  - Build `TicketDetailScreen` composable with inline status selector, `FlowRow` tags, AI action chips
  - Apply `SwipeRevealLayout` for delete action on `TicketCard`
  - Update `NavigationBar` tab to use `Icons.Outlined.ConfirmationNumber` and label "Tickets"
  - _Requirements: 29.1, 29.2, 24.1_

- [ ] 50.8 Implement Dark Mode refinements
  - Update `DarkColorScheme` in `Color.kt` with corrected `background` (#111318), `surface` (#111318), and new `surfaceTonal1/2/3` tokens
  - Update all screen composables that use `surfaceVariant` as a card background to use `surfaceTonal1` instead
  - Update `ChatBubble` user bubble to use `primary.copy(alpha = 0.9f)` in dark mode
  - Update login gradient to use dark-desaturated color variants in dark mode
  - Wrap `AppTheme` content in `Crossfade` (400ms) for animated theme switching
  - Verify all contrast ratios in the checklist table (§50.8) by running Compose UI tests
  - _Requirements: 24.1, 24.2, 24.3_

- [ ] 50.9 Implement animation system
  - Implement `MeshGradientBackground` (§50.9.1) with reduced-motion fallback
  - Wire `SharedTransitionLayout` for Home?Chat and Profile?Memory transitions (§50.9.2)
  - Apply `AppTransition` specs to `NavHost` `enterTransition`/`exitTransition`/`popEnterTransition`/`popExitTransition` (§50.9.4)
  - Implement `TypingIndicator` composable with 3-dot stagger animation (§50.9.5)
  - Implement `pressScale` modifier (§50.9.7)
  - Provide `LocalReducedMotionEnabled` in `AppTheme`; gate all animations (§50.9.8)
  - Write Compose UI tests asserting: TypingIndicator is hidden after first streaming token; navigation indicator animates on tab switch; theme `Crossfade` is applied when `ThemeMode` changes; all animations are disabled when `LocalReducedMotionEnabled == true`
  - _Requirements: 23.1, 23.2, 24.1, 24.3_

- [ ] 50.10 Update Task Dependency Graph wave entry for Task 50
  - Wave 12: Task 50 (UI Redesign) — depends on Tasks 3, 11, 12, 14, 21, 20.5; can run in parallel with Tasks 44–49 as it touches only `core-ui` and feature-level composable files
  - _Requirements: 24.1, 24.2, 24.3_
