# Requirements Document

## Introduction

The Android AI Assistant (Enterprise Edition) is a production-ready, full-stack AI platform consisting of an Android application, a FastAPI backend, an AI orchestration layer, a RAG pipeline, and a supporting infrastructure of authentication, vector storage (ChromaDB), object storage (MinIO), MCP integrations, an admin dashboard, and CI/CD pipelines. The system enables enterprise users to interact with multiple AI language models through natural language chat, voice, document analysis, image understanding, and code assistance — all with offline-first support, role-based access control, and comprehensive observability.

The Android application is organized into 24 Gradle modules following Clean Architecture with strict unidirectional dependency rules. The backend is a Python FastAPI service containerized with Docker Compose, including PostgreSQL, Redis, ChromaDB, MinIO, Celery, Nginx, Prometheus, Grafana, and Loki.

This document defines all functional and non-functional requirements using EARS patterns and INCOSE quality standards.

---

## Glossary

- **AI_Assistant**: The Android mobile application that is the primary user-facing client.
- **Backend**: The Python FastAPI server that orchestrates AI, data, and business logic.
- **Auth_Service**: The backend module responsible for authentication and authorization.
- **AI_Orchestrator**: The backend module that routes requests to LLM providers and manages prompt lifecycle.
- **RAG_Pipeline**: The Retrieval-Augmented Generation pipeline for document-based question answering.
- **Vector_Store**: The ChromaDB instance that stores and retrieves document embeddings.
- **Memory_Service**: The backend service that persists and retrieves long-term user memory using vector embeddings.
- **MCP_Broker**: The Model Context Protocol broker that manages external tool integrations.
- **Admin_Dashboard**: The web interface for platform administration and monitoring.
- **LLM_Provider**: Any supported large language model service (OpenAI, Gemini, Claude, Ollama, Llama, Mistral).
- **User**: An authenticated human interacting with the AI_Assistant.
- **Admin**: A privileged User with access to the Admin_Dashboard and platform management APIs.
- **Conversation**: A named, persisted sequence of messages between a User and the AI_Orchestrator.
- **Message**: A single turn within a Conversation, attributed to either the User or the AI_Orchestrator.
- **Document**: A user-uploaded file (PDF, DOCX, TXT, Markdown, or image) ingested by the RAG_Pipeline.
- **Embedding**: A fixed-dimension floating-point vector representation of a text chunk.
- **Chunk**: A segment of a Document produced during RAG ingestion.
- **MCP_Tool**: An external service integration exposed through the MCP_Broker.
- **JWT**: JSON Web Token used for stateless authentication.
- **RBAC**: Role-Based Access Control governing feature access per user role.
- **EARS**: Easy Approach to Requirements Syntax — the pattern system used in this document.
- **Streaming_Response**: An AI response delivered incrementally via WebSocket or Server-Sent Events.
- **Meeting_Assistant**: The feature module responsible for recording, transcribing, summarizing, and exporting meeting audio.
- **Transcription_Service**: The backend service that converts meeting audio to timestamped text.
- **Action_Item**: A discrete task extracted from a meeting transcript by the AI_Orchestrator and assigned to a named participant.
- **Translator**: The feature module that converts text or speech between human languages using the AI_Orchestrator.
- **Language_Pair**: A directed combination of a source language and a target language used by the Translator.
- **Offline_Translation_Model**: A bundled on-device language model enabling translation without network connectivity.
- **Productivity_Suite**: The collection of To-Do, Calendar, Reminder, and Habit_Tracker features integrated with the AI_Orchestrator.
- **Habit_Tracker**: The feature module that records recurring User behaviors and surfaces AI-generated insights about completion patterns.
- **Onboarding_Flow**: The sequence of introductory screens shown to a User on first launch before the Home Dashboard.
- **Splash_Screen**: The initial branded screen displayed while the application initializes.
- **Docs_Directory**: The `/docs` folder in the project root containing all Markdown documentation files required by the Documentation-First architecture.
- **Educational_Header**: A structured documentation block placed at the top of every generated source file, conforming to the Educational Mode standards.
- **Firebase_Remote_Config**: The Firebase service used to deliver runtime configuration values to the AI_Assistant without requiring an app update.
- **MinIO**: The self-hosted S3-compatible object storage service used to persist raw uploaded documents.
- **Celery**: The distributed task queue used to process long-running background jobs (document ingestion, push notification dispatch) with Redis as the message broker.
- **SentenceTransformer**: The embedding model (`all-MiniLM-L6-v2`) used to generate vector embeddings for RAG chunks and memories.
- **Token_Family**: A group of related refresh tokens sharing the same `family_id`, used for replay detection and cascaded revocation.

---

## Requirements

### Requirement 1: User Authentication

**User Story:** As a User, I want to securely register, log in, and manage my session, so that my data and conversations remain private and protected.

#### Acceptance Criteria

1. THE Auth_Service SHALL accept user registration with a valid email address (unique within the system) and a password between 12 and 128 characters.
2. WHEN a User submits valid credentials, THE Auth_Service SHALL issue a signed JWT with a 15-minute expiry and a refresh token with a 30-day expiry.
3. WHEN a JWT expires, THE Auth_Service SHALL accept a valid refresh token and issue a new JWT without requiring the User to re-enter credentials.
4. WHEN a refresh token is used, THE Auth_Service SHALL invalidate the previous refresh token and issue a replacement (token rotation). IF the same refresh token is submitted a second time after already being used, THE Auth_Service SHALL revoke all tokens in the same Token_Family and return HTTP 401.
5. IF a User submits invalid credentials 5 consecutive times within 10 minutes, THEN THE Auth_Service SHALL lock the account for 15 minutes and send one email notification per failed attempt throughout the lockout window.
6. THE Auth_Service SHALL support Google OAuth2 sign-in, mapping the Google account to a local user record on first sign-in.
7. WHEN a User enables biometric authentication on their device, THE AI_Assistant SHALL use the device biometric prompt to unlock the local session without transmitting biometric data to the Backend. The biometric session SHALL remain valid for 15 minutes, after which the AI_Assistant SHALL re-prompt.
8. THE Auth_Service SHALL enforce RBAC with at least three roles: `user`, `premium`, and `admin`.
9. WHERE the `admin` role is assigned, THE Auth_Service SHALL grant access to all Admin_Dashboard endpoints.
10. WHEN a User logs out, THE Auth_Service SHALL invalidate all active refresh tokens for that User's session (single-device logout).
11. IF a User with a role other than `admin` attempts to access an Admin_Dashboard endpoint, THEN THE Auth_Service SHALL return HTTP 403.

---

### Requirement 2: AI Chat

**User Story:** As a User, I want to have multi-turn conversations with an AI model, so that I can get intelligent answers, summaries, and assistance in real time.

#### Acceptance Criteria

1. THE AI_Orchestrator SHALL accept a Conversation request containing a User message (maximum 32,000 characters), a Conversation ID, and a selected LLM_Provider. IF the message exceeds 32,000 characters, THE AI_Orchestrator SHALL reject the request with HTTP 422.
2. WHEN a Conversation request is received, THE AI_Orchestrator SHALL deliver a Streaming_Response by emitting tokens incrementally within 500 ms of the first token being generated.
3. THE AI_Orchestrator SHALL maintain the full message history within the context window for each Conversation, up to the selected LLM_Provider's token limit.
4. IF the Conversation history exceeds 80% of the LLM_Provider's context window, THEN THE AI_Orchestrator SHALL summarize the messages outside the most recent turn set that fits within 40% of the context window and retain the summary in place of those raw messages.
5. THE AI_Assistant SHALL render AI responses with Markdown formatting, including headers, bold, italic, code blocks with syntax highlighting, tables, and bullet lists.
6. WHEN a User selects regenerate on a Message, THE AI_Orchestrator SHALL produce a new response using the same input context and append it as an alternative to the existing Message, up to a maximum of 5 alternatives per Message.
7. WHEN a User selects copy, share, or export on a Message, THE AI_Assistant SHALL copy the content to the clipboard, invoke the device's native share mechanism, or export the content as plain text or Markdown respectively.
8. IF a Streaming_Response connection is interrupted, THEN THE AI_Assistant SHALL display a retry option and SHALL wait for the User to initiate reconnection. IF the User does not reconnect within 5 minutes, THE AI_Assistant SHALL discard the partial response and display an error.
9. THE AI_Orchestrator SHALL count and record input and output token usage for every Message and associate the count with the User's usage record.
10. WHEN a User sends a Message, THE AI_Assistant SHALL display a typing indicator until the first token of the Streaming_Response is received.

---

### Requirement 3: Multi-LLM Provider Support

**User Story:** As a User, I want to choose which AI model powers my conversations, so that I can balance capability, cost, and privacy according to my needs.

#### Acceptance Criteria

1. THE AI_Orchestrator SHALL support the following LLM_Providers: OpenAI GPT-4o, Google Gemini 1.5 Pro, Anthropic Claude 3.5 Sonnet, Ollama (self-hosted), Llama 3.x, and Mistral.
2. WHEN a User selects a new LLM_Provider in the Settings screen, THE AI_Assistant SHALL apply the selection immediately for subsequent Messages without requiring an app restart.
3. WHEN the active LLM_Provider returns no response within 10 seconds or returns a connection error, THE AI_Orchestrator SHALL automatically retry with a configured fallback LLM_Provider and notify the User in-app which provider was substituted. IF no fallback LLM_Provider is configured, THEN THE AI_Orchestrator SHALL return a structured error response without notifying the User of a substitution.
4. IF Admin-configured per-provider rate limits are exceeded, THEN THE AI_Orchestrator SHALL return a structured error response identifying the provider and the rate limit.
5. WHERE Ollama is selected, THE AI_Orchestrator SHALL route requests to the locally configured Ollama endpoint and SHALL NOT transmit data to external services.
6. THE AI_Orchestrator SHALL track per-provider token cost using configurable per-token pricing and surface accumulated cost broken down by provider and calendar day in the Admin_Dashboard, refreshed within 60 seconds.
7. IF switching to a new LLM_Provider fails because the provider's API key is absent or rejected, THEN THE AI_Assistant SHALL retain the previously active provider and display a structured error identifying the failed provider.

---

### Requirement 4: RAG Pipeline — Document Ingestion

**User Story:** As a User, I want to upload documents and ask questions about their content, so that I can extract insights without reading entire files manually.

#### Acceptance Criteria

1. THE RAG_Pipeline SHALL accept document uploads in PDF, DOCX, TXT, and Markdown formats with a maximum file size of 50 MB per document. THE RAG_Pipeline SHALL reject documents in unsupported formats or exceeding the size limit with HTTP 422 before storing any bytes in MinIO or ChromaDB.
2. WHEN a Document is uploaded, THE RAG_Pipeline SHALL store the raw file in MinIO, enqueue a Celery background job, and return a job ID immediately. THE RAG_Pipeline SHALL then extract text using OCR for scanned PDFs and direct text extraction for native PDFs, DOCX, TXT, and Markdown files.
3. WHEN text extraction is complete, THE RAG_Pipeline SHALL split the text into overlapping Chunks of a configurable size (default 512 tokens, overlap 64 tokens, minimum chunk size 64 tokens, maximum 2048 tokens, maximum overlap 50% of chunk size) such that the union of all Chunks covers the full extracted text with no gaps.
4. WHEN Chunks are produced, THE RAG_Pipeline SHALL generate an Embedding for each Chunk using the SentenceTransformer model and store the Embedding in the Vector_Store under the user-scoped collection `documents_{user_id}`.
5. THE RAG_Pipeline SHALL associate every Chunk and Embedding with the originating Document and User, and SHALL NOT allow cross-user retrieval.
6. WHEN a User submits a question about a Document, THE RAG_Pipeline SHALL retrieve the top-K semantically relevant Chunks (default K=5) from the Vector_Store and assemble them into a context window for the LLM_Provider.
7. THE AI_Orchestrator SHALL include citations in every RAG response, referencing the source Document name and page number for each retrieved Chunk. For formats without page numbers (TXT, Markdown), THE AI_Orchestrator SHALL use the character offset range as the citation reference.
8. IF document text extraction fails, THEN THE RAG_Pipeline SHALL return a structured error message identifying the failure stage (extraction, chunking, or embedding) and the file name.
9. THE RAG_Pipeline SHALL support a round-trip property: for any valid Document, ingesting the Document and then querying a verbatim phrase present in the Document SHALL return a response referencing that Document and containing the phrase in at least one retrieved Chunk.
10. WHEN a User deletes a Document, THE RAG_Pipeline SHALL remove all associated Chunks and Embeddings from the Vector_Store and all associated PostgreSQL records within 60 seconds.
11. WHEN a User polls `/jobs/{job_id}`, THE Backend SHALL return the job's current status (`queued`, `processing`, `completed`, or `failed`) and, upon `failed`, a structured error message.

---

### Requirement 5: Voice Assistant

**User Story:** As a User, I want to speak to the AI Assistant and hear spoken responses, so that I can interact hands-free while multitasking.

#### Acceptance Criteria

1. WHEN the User activates voice input and speaks, THE AI_Assistant SHALL detect end-of-speech after 1,500 ms of silence and submit the transcript to the AI_Orchestrator. IF speech recognition produces a low-confidence transcript (below the device engine's confidence threshold), THE AI_Assistant SHALL display the transcript with a visual low-confidence indicator.
2. WHEN speech-to-text conversion produces a transcript, THE AI_Assistant SHALL submit the transcript as a Message to the AI_Orchestrator and display it in the Voice Assistant screen.
3. WHEN the device text-to-speech engine is available, THE AI_Assistant SHALL convert the AI_Orchestrator's text response to speech using the User's selected voice profile. IF no voice profile has been selected, THE AI_Assistant SHALL use the device's default voice.
4. WHEN the AI_Assistant is speaking a response and the User taps the interrupt control, THE AI_Assistant SHALL stop playback within 300 ms and activate the microphone for the next User input.
5. WHERE the device supports wake-word detection and the AI_Assistant is in the foreground, THE AI_Assistant SHALL activate voice input mode without requiring a screen tap.
6. IF the microphone permission is not granted, THEN THE AI_Assistant SHALL display an explanation dialog and a button navigating the User to the system permissions settings.
7. IF the speech recognition engine fails to produce any transcript within 10 seconds of activation, THEN THE AI_Assistant SHALL stop listening, display an error message, and return to the idle voice state.
8. IF the text-to-speech engine fails to synthesize the AI response, THEN THE AI_Assistant SHALL display the response text on screen, show an error indicator, and remain ready for the next User input.

---

### Requirement 6: Image Understanding

**User Story:** As a User, I want to capture or select images and have the AI describe, analyze, or extract text from them, so that I can process visual information quickly.

#### Acceptance Criteria

1. THE AI_Assistant SHALL accept images in JPEG, PNG, and WebP formats from the device camera or gallery with a maximum resolution of 4096 × 4096 pixels and a maximum file size of 10 MB. IF an image exceeds either limit, THE AI_Assistant SHALL display an error and reject the submission.
2. WHEN an image is submitted, THE AI_Assistant SHALL display an analysis progress indicator and transmit the image to the Backend within 3 seconds of submission.
3. WHEN an image is submitted, THE Backend SHALL perform OCR and return extracted text along with bounding box coordinates. IF no text is detected, THE Backend SHALL return an empty text result with a `no_text_found` indicator rather than an error.
4. WHEN a vision-capable LLM_Provider is active, THE AI_Orchestrator SHALL pass the image and a User-provided prompt to the LLM_Provider and return a structured analysis response. IF no vision-capable LLM_Provider is currently active, THE AI_Orchestrator SHALL return an error response indicating that no active vision-capable provider is available.
5. WHEN a User submits an image containing a barcode or QR code, THE AI_Assistant SHALL return the decoded payload as a Message in the active Conversation. IF the barcode or QR code cannot be decoded, THE AI_Assistant SHALL display an error message indicating the scan failed.
6. IF the selected LLM_Provider does not support vision input, THEN THE AI_Orchestrator SHALL return an error message identifying the capability gap. IF no compatible vision provider is configured at all, THE AI_Orchestrator SHALL state that no compatible provider is available.
7. IF camera or gallery permission is not granted, THEN THE AI_Assistant SHALL display an explanation dialog and a button navigating the User to the system permissions settings.

---

### Requirement 7: AI Memory

**User Story:** As a User, I want the assistant to remember my preferences and context across sessions, so that I receive personalized responses without repeating myself.

#### Acceptance Criteria

1. THE Memory_Service SHALL store user-specific facts, preferences, and writing style observations as Embeddings in the Vector_Store under the user-scoped collection `memories_{user_id}`, tagged with the User's identifier.
2. WHEN the AI_Orchestrator prepares a prompt, THE Memory_Service SHALL retrieve the top-3 most semantically relevant memories for the current Message and inject them into the system prompt context. IF memory retrieval fails or returns no results, THE AI_Orchestrator SHALL proceed with prompt construction without memory injection.
3. THE AI_Assistant SHALL display a Memory screen allowing the User to view all stored memories, edit the text content of individual memories, and delete individual memories.
4. WHEN a User deletes a memory, THE Memory_Service SHALL remove the corresponding Embedding from the Vector_Store within 10 seconds. IF removal is not confirmed within 10 seconds, THE Memory_Service SHALL return an error and leave the memory intact.
5. THE Memory_Service SHALL NOT share memories between Users; each User's memories are stored exclusively in that User's scoped ChromaDB collection.
6. WHERE privacy mode is enabled by the User, THE Memory_Service SHALL disable memory capture for the duration of the session without deleting existing memories.
7. WHEN the AI_Orchestrator receives a completed Message, THE Memory_Service SHALL extract and store any new user facts, preferences, or writing style observations identified in that Message.

---

### Requirement 8: MCP Tool Integration

**User Story:** As a User, I want the assistant to interact with external tools like GitHub, Gmail, and Google Calendar on my behalf, so that I can accomplish cross-platform tasks through a single interface.

#### Acceptance Criteria

1. THE MCP_Broker SHALL implement the Model Context Protocol specification and support tool registration, discovery, invocation, and result handling.
2. THE MCP_Broker SHALL provide out-of-the-box MCP_Tool connectors for: GitHub, Gmail, Google Drive, Google Calendar, Slack, Jira, Notion, and Figma.
3. WHEN the AI_Orchestrator determines that a User request requires a MCP_Tool, THE MCP_Broker SHALL invoke the appropriate tool within 30 seconds, capture the result, and inject it into the LLM_Provider context. IF the invocation does not complete within 30 seconds, THE MCP_Broker SHALL cancel the request and return a timeout error.
4. WHEN the AI_Orchestrator determines that a write-action MCP_Tool is needed, THE AI_Assistant SHALL display a confirmation dialog. IF the User confirms, THE MCP_Broker SHALL invoke the tool. IF the User dismisses the dialog, THE MCP_Broker SHALL NOT invoke the tool and shall inform the AI_Orchestrator that the action was cancelled.
5. IF a MCP_Tool invocation fails, THEN THE MCP_Broker SHALL return a structured error response containing an error code and a user-safe message, and THE AI_Orchestrator SHALL display the user-safe message without exposing internal error details.
6. THE MCP_Broker SHALL be extensible such that a new MCP_Tool can be registered by adding a single connector class without modifying existing connector code.
7. THE MCP_Broker SHALL log every MCP_Tool invocation with the User identifier, tool name, timestamp, and result status to the audit log, retaining records for a minimum of 90 days.
8. IF the User dismisses the MCP_Tool confirmation dialog, THE AI_Assistant SHALL display a message indicating the action was not performed and continue the Conversation.

---

### Requirement 9: Security

**User Story:** As an Admin, I want the platform to enforce robust security controls, so that User data, API keys, and AI interactions are protected from unauthorized access and injection attacks.

#### Acceptance Criteria

1. IF an API request contains an expired, malformed, or revoked JWT, THEN THE Backend SHALL reject the request with HTTP 401.
2. IF an authenticated User's role lacks the required permission for a protected endpoint, THEN THE Backend SHALL return HTTP 403.
3. THE Auth_Service SHALL store all passwords using a bcrypt hash with a minimum work factor of 12.
4. THE AI_Assistant SHALL use Android EncryptedSharedPreferences for all locally stored credentials and tokens.
5. THE AI_Assistant SHALL implement certificate pinning for all Backend API connections and reject connections where the server certificate does not match the pinned certificate.
6. WHEN a prompt injection pattern is detected in a LLM_Provider input, THE Backend SHALL reject the entire request with HTTP 400 and error code `PROMPT_INJECTION_DETECTED`, write an audit log entry containing the user ID and a SHA-256 hash of the sanitized input, and SHALL NOT forward any portion of the input to the LLM_Provider.
7. THE Backend SHALL use parameterized queries for all database operations and apply context-aware output encoding before returning user-supplied content in API responses.
8. THE Auth_Service SHALL maintain an audit log of all authentication events, including login, logout, token refresh, and failed attempts, retaining records for a minimum of 90 days.
9. IF an authenticated User exceeds 60 requests per minute, THEN THE Backend SHALL return HTTP 429 with a `Retry-After` header indicating the number of seconds until the limit resets.
10. WHERE API keys for LLM_Providers are stored, THE Backend SHALL encrypt them at rest using AES-256 and never return plaintext key values in API responses or logs.
11. IF an unauthenticated request to a public endpoint exceeds 20 requests per minute from the same IP address, THEN THE Backend SHALL return HTTP 429.

---

### Requirement 10: Offline-First Architecture

**User Story:** As a User, I want the app to remain functional when I have no internet connection, so that I can view past conversations and draft messages without interruption.

#### Acceptance Criteria

1. THE AI_Assistant SHALL cache up to 500 Conversations and their most recent 10,000 Messages in a local Room database and display them immediately on launch without waiting for a network response.
2. WHILE the device is offline, THE AI_Assistant SHALL queue outgoing Messages and submit them to the Backend when connectivity is restored using WorkManager.
3. WHEN connectivity is restored, THE AI_Assistant SHALL initiate synchronization of the local Room database with the Backend within 30 seconds, resolving conflicts by preferring the server state for Messages and the local state for User preferences.
4. WHEN the device has no network connectivity, THE AI_Assistant SHALL display a persistent offline banner until connectivity is restored.
5. WHILE the device is offline, THE AI_Assistant SHALL allow the User to read, search, and navigate all previously cached Conversations.
6. IF a queued Message fails to deliver after 3 retry attempts with exponential backoff (initial interval 5 seconds, multiplier 2, maximum interval 60 seconds), THEN THE AI_Assistant SHALL mark the Message with a failed status and display a persistent in-app notification.

---

### Requirement 11: Conversation History and Search

**User Story:** As a User, I want to search and manage my conversation history, so that I can quickly find past interactions and keep my workspace organized.

#### Acceptance Criteria

1. THE AI_Assistant SHALL display a paginated list of all Conversations sorted by last-modified date, loading 20 items per page using Paging 3.
2. WHEN a User enters a search query, THE AI_Assistant SHALL filter Conversations by matching the query against Conversation titles and Message content within 300 ms using the local Room FTS index.
3. THE AI_Assistant SHALL allow the User to pin, rename, and delete individual Conversations. Pinned Conversations SHALL appear at the top of the list regardless of sort order.
4. WHEN a User deletes a Conversation, THE Backend SHALL mark the Conversation as soft-deleted and THE AI_Assistant SHALL remove it from the local cache within 5 seconds. IF cache removal cannot be completed within 5 seconds, THE deletion operation SHALL fail and the Conversation SHALL remain visible.
5. THE AI_Assistant SHALL group Conversations by date category: Today, Yesterday, Last 7 Days, and Older.
6. WHEN a User requests export of a Conversation, THE AI_Assistant SHALL generate and save a Markdown or PDF file to the device's Downloads folder within 10 seconds.

---

### Requirement 12: Code Assistant

**User Story:** As a developer User, I want AI-powered code generation, explanation, and debugging, so that I can accelerate development and learn from AI suggestions.

#### Acceptance Criteria

1. THE AI_Assistant SHALL provide a code editor composable supporting syntax highlighting for Kotlin, Java, Python, JavaScript, C++, and SQL, with a maximum input size of 500 lines or 50,000 characters.
2. WHEN a User submits a code block for explanation, THE AI_Orchestrator SHALL return a structured response with three labeled sections: "What it does", "Why it is written this way", and "Potential improvements".
3. WHEN a User submits a code block for bug fixing, THE AI_Orchestrator SHALL return the corrected code with inline comments identifying each change. IF no bugs are found, THE AI_Orchestrator SHALL return the original code with a statement that no issues were detected.
4. WHEN a User requests unit test generation for a code block, THE AI_Orchestrator SHALL return at least one test case per distinct function or method in the submitted code, following the Arrange-Act-Assert pattern.
5. WHEN a User taps the copy button on a generated code block, THE AI_Assistant SHALL copy the full code content to the clipboard.
6. THE AI_Orchestrator SHALL include a language identifier in every code response, using one of: `kotlin`, `java`, `python`, `javascript`, `cpp`, `sql`, or `plaintext` as the fallback.
7. IF a submitted code block is empty or exceeds 500 lines or 50,000 characters, THEN THE AI_Orchestrator SHALL reject the request with a structured error before processing.

---

### Requirement 13: Notes and Productivity

**User Story:** As a User, I want AI-enhanced notes with summarization and rewriting, so that I can capture and refine information efficiently.

#### Acceptance Criteria

1. THE AI_Assistant SHALL provide a notes editor supporting plain text and Markdown input up to 50,000 characters, with a live preview that renders within 500 ms of each input change.
2. WHEN a User requests an AI summary of a note, THE AI_Orchestrator SHALL return a concise summary of no more than 150 words preserving all key facts. IF the generated summary exceeds 150 words, THE AI_Orchestrator SHALL truncate it to exactly 150 words before delivering it to the User.
3. WHEN a User requests an AI rewrite of a note, THE AI_Orchestrator SHALL return a rewritten version in the User's previously learned writing style if Memory_Service records are available, or in a neutral professional style (clear language, active voice, standard grammar) otherwise. IF the rewrite fails, THE AI_Orchestrator SHALL return an error message and leave the original note unchanged.
4. THE AI_Assistant SHALL persist notes in the local Room database and synchronize them with the Backend when connectivity is available, using last-write-wins conflict resolution based on the `updated_at` timestamp.
5. THE AI_Assistant SHALL support tagging notes with user-defined labels (maximum 50 characters per label, maximum 50 tags per note) and filtering the notes list by tag.

---

### Requirement 14: Resume and Email Generation

**User Story:** As a User, I want AI to generate professional resumes, cover letters, and emails, so that I can produce high-quality written materials quickly.

#### Acceptance Criteria

1. WHEN a User provides at least one work experience entry, contact information, and a target job description, THE AI_Orchestrator SHALL generate an ATS-optimized resume in Markdown format containing Summary, Experience, Education, and Skills sections with keywords from the job description, within 30 seconds.
2. WHEN a User provides both a job description and resume data for a cover letter request, THE AI_Orchestrator SHALL generate a tailored cover letter not exceeding 400 words. IF either input is missing, THE AI_Orchestrator SHALL return HTTP 422 specifying which input is absent.
3. WHEN a User taps the export button on a generated resume or cover letter, THE AI_Assistant SHALL generate and save the file in PDF or DOCX format to the device's Downloads folder without requiring prior review or approval.
4. WHEN a User provides context and intent for an email, THE AI_Orchestrator SHALL generate a professional email with subject line, greeting, body (maximum 300 words), and closing.
5. WHEN a User requests grammar correction on a draft email, THE AI_Orchestrator SHALL return the corrected text with an inline diff marking each removed and inserted segment. IF no changes are needed, THE AI_Orchestrator SHALL return the original text with a `no_changes_needed` indicator.
6. IF the AI_Orchestrator fails to generate any content in criteria 1, 2, or 4, THEN THE AI_Orchestrator SHALL return a structured error message identifying which operation failed.
7. IF export format conversion fails in criterion 3, THEN THE AI_Assistant SHALL display an error message identifying the format and the failure reason.

---

### Requirement 15: Admin Dashboard

**User Story:** As an Admin, I want a web dashboard to monitor platform health, user activity, and AI costs, so that I can manage the platform effectively.

#### Acceptance Criteria

1. THE Admin_Dashboard SHALL display metrics (refreshed within 30 seconds) for: active users, messages per hour, total token consumption, per-provider token cost, and error rates.
2. THE Admin_Dashboard SHALL provide a user management interface allowing Admins to view, search, promote, demote, deactivate, and reactivate Users.
3. THE Admin_Dashboard SHALL display aggregated analytics for AI feature usage broken down by feature (chat, voice, RAG, code, etc.) and LLM_Provider.
4. WHEN a User is deactivated by an Admin, THE Auth_Service SHALL invalidate all active JWTs and all active refresh tokens for that User within 5 seconds of the deactivation request.
5. THE Admin_Dashboard SHALL surface a paginated audit log (maximum 100 entries per page, maximum date range 90 days) of all authentication and MCP_Tool invocation events, filterable by User, event type, and date range.
6. THE Admin_Dashboard SHALL display error monitoring including the top 10 most frequent error types in the last 24 hours, each with error type name, count, and up to 500 characters of stack trace excerpt.
7. THE Admin_Dashboard SHALL provide a feedback management interface that aggregates User-submitted feedback, allows Admins to tag feedback items by category, and export them as CSV with at minimum: feedback ID, user ID, timestamp, content, and assigned category.
8. WHERE Firebase_Remote_Config is enabled, THE Admin_Dashboard SHALL provide a UI for Admins to read, update, and publish remote configuration values without redeploying the Backend.
9. THE Admin_Dashboard SHALL display a session monitor (refreshed within 30 seconds) showing active sessions with device type, country-level geographic region, current feature in use, and session duration.

---

### Requirement 16: Notifications

**User Story:** As a User, I want to receive push notifications for relevant AI assistant events, so that I stay informed even when the app is in the background.

#### Acceptance Criteria

1. WHEN a RAG ingestion job completes (successfully or with failure), THE Backend SHALL send a push notification to the User's device containing the document name and final status.
2. WHEN a queued Message that previously failed delivery is successfully delivered on retry, THE Backend SHALL send a push notification to the User's device.
3. WHEN the AI_Assistant first launches on a new device, THE AI_Assistant SHALL request notification permission during onboarding. IF the User denies permission, THE AI_Assistant SHALL suppress all push notifications and respect the preference for the lifetime of the installation.
4. THE AI_Assistant SHALL allow the User to configure which notification categories are enabled in the Settings screen. Categories SHALL include at minimum: RAG ingestion, message delivery, and system alerts.
5. IF the device's push notification token changes, THEN THE AI_Assistant SHALL update the Backend with the new token within the next successful API request. IF the token update fails, THE AI_Assistant SHALL retry on each of the next 10 subsequent successful API requests before stopping.
6. IF the Backend fails to dispatch a push notification, THEN THE Backend SHALL retry with exponential backoff up to 3 times and, if all retries fail, surface the failure in the Admin_Dashboard.
7. WHEN the User grants notification permission after having previously denied it, THE AI_Assistant SHALL register the device token with the Backend within the same session.

---

### Requirement 17: Performance and Scalability

**User Story:** As a User, I want the app and backend to respond quickly under normal and peak load, so that my workflow is never blocked by latency.

#### Acceptance Criteria

1. WHEN the AI_Assistant is launched from a cold start on a mid-range Android device (Snapdragon 700 series or equivalent), THE AI_Assistant SHALL display a fully interactive Home Dashboard within 2 seconds under normal operating conditions.
2. WHEN non-AI REST API endpoints (authentication, history, settings) are called under a load of 1,000 concurrent users, THE Backend SHALL respond within 200 ms at the 95th percentile.
3. WHEN a Chat request is received and the LLM_Provider's p95 response latency is ≤ 2,000 ms, THE Backend SHALL deliver the first Streaming_Response token within 500 ms of receiving the request.
4. WHEN a well-formed PDF of 10 pages and ≤ 5 MB is submitted for ingestion, THE RAG_Pipeline SHALL complete ingestion within 30 seconds on the production server configuration.
5. WHEN additional Backend instances are added behind a load balancer, THE Backend SHALL achieve at least 80% proportional increase in request throughput per added instance with no lost writes or duplicate records.
6. THE AI_Assistant SHALL use Paging 3 for all list screens (Conversations, History, Documents) and SHALL NOT load more than 20 items at a time into memory.
7. WHILE a list screen is loading the next page, THE AI_Assistant SHALL display a loading indicator and SHALL NOT block the User from scrolling or interacting with already-loaded items.

---

### Requirement 18: Analytics and Observability

**User Story:** As an Admin, I want comprehensive logging, metrics, and tracing, so that I can diagnose issues and understand system behavior in production.

#### Acceptance Criteria

1. THE Backend SHALL emit a structured JSON log entry for every API request (excluding `/metrics` and `/health`), containing: a UUID correlation ID assigned at request ingress, user ID, endpoint path, HTTP status code, and response time in milliseconds.
2. THE Backend SHALL expose a Prometheus metrics endpoint at `/metrics` providing counters and histograms for request rates, error rates, and response times per endpoint, with histogram buckets at 50, 100, 200, 500, 1000, 2000, and 5000 ms.
3. THE Backend SHALL integrate with Grafana for dashboarding, with pre-built dashboards accessible without manual configuration that include at minimum: AI cost by provider, request volume over time, and error rate over time.
4. THE Backend SHALL integrate with Loki for log aggregation such that a query by correlation ID or user ID returns results within 10 seconds.
5. WHEN an unhandled exception occurs in the Backend, THE Backend SHALL log the full stack trace with a correlation ID.
6. WHEN an unhandled exception is logged, THE Backend SHALL increment the error counter metric. THE Backend SHALL only increment error counters when an actual unhandled exception has occurred.
7. THE AI_Assistant SHALL integrate Firebase Crashlytics for crash reporting and Firebase Analytics for screen view and feature usage events, with a minimum event set of: `screen_view`, `feature_used`, `message_sent`, and `error_occurred`.

---

### Requirement 19: Android Architecture and Code Quality

**User Story:** As a developer, I want the Android codebase to follow Clean Architecture with modular separation, so that features can be developed, tested, and maintained independently.

#### Acceptance Criteria

1. THE AI_Assistant SHALL be organized into the following Gradle modules, each declared in `settings.gradle.kts` such that project sync succeeds: `app`, `core-ui`, `core-network`, `core-database`, `core-ai`, `core-security`, `core-common`, `feature-auth`, `feature-chat`, `feature-rag`, `feature-camera`, `feature-code`, `feature-voice`, `feature-settings`, `feature-profile`, `feature-history`, `feature-notes`, `feature-meeting`, `feature-resume`, `feature-email`, `feature-translator`, `feature-productivity`, `domain`, and `data`.
2. THE CI/CD pipeline SHALL include a dependency-lint step that fails the build when any `feature` module declares a dependency on another `feature` module, any `domain` module declares a dependency on a `data` or `feature` module, or any `data` module declares a dependency on a `feature` module.
3. THE AI_Assistant SHALL use Hilt for all dependency injection. No direct constructor calls, factory methods, or service locators SHALL be used for cross-module dependencies outside of Hilt-managed entry points.
4. All Kotlin source files in the AI_Assistant SHALL use Kotlin Coroutines and StateFlow/SharedFlow for asynchronous operations and SHALL NOT declare or return LiveData types.
5. WHEN the CI/CD pipeline measures JaCoCo line coverage for the `domain` and `data` modules (excluding auto-generated files), the combined coverage SHALL be at least 70%. The pipeline SHALL fail the build if coverage falls below this threshold.
6. WHEN a pull request targets the main branch, THE CI/CD pipeline SHALL run ktlint and Detekt against all changed Kotlin source files and SHALL block the merge if either tool reports one or more errors.

---

### Requirement 20: CI/CD and DevOps

**User Story:** As a developer, I want automated build, test, and deployment pipelines, so that code quality is enforced and releases are reproducible.

#### Acceptance Criteria

1. THE Backend SHALL be containerized using Docker with a multi-stage Dockerfile where the final production stage contains no build tools or development packages.
2. THE Backend deployment environment SHALL be defined in a Docker Compose file including services for: Backend API, PostgreSQL, Redis, ChromaDB, MinIO, Celery worker, Nginx reverse proxy, Prometheus, Grafana, and Loki.
3. WHEN a pull request is opened or updated, THE GitHub Actions workflow SHALL run Android lint and unit tests and Backend unit and integration tests, and SHALL block merge if any check fails via GitHub branch protection rules.
4. WHEN a commit is merged to `main`, THE GitHub Actions workflow SHALL produce a signed Android release APK artifact and build and push a Docker image.
5. WHEN `/health` is called, THE Backend SHALL return HTTP 200 with a JSON body containing `{"status": "ok"}` if all dependencies are reachable, or HTTP 503 with `{"status": "unavailable", "dependencies": {...}}` listing any unreachable dependencies.
6. WHEN `/ready` is called, THE Backend SHALL return HTTP 200 with `{"status": "ready"}` if the service can accept traffic, or HTTP 503 with `{"status": "unavailable"}` if it cannot.
7. WHEN the service starts and all dependencies are healthy, THE Backend SHALL return HTTP 200 on `/ready` indicating readiness to accept traffic.
8. THE project SHALL include `.env.example` files for all required environment variables with descriptions. All `.env` files containing actual secret values SHALL be listed in `.gitignore` and SHALL NOT be present in the repository.

---

### Requirement 21: Testing Strategy

**User Story:** As a developer, I want comprehensive test coverage across unit, integration, and UI layers, so that regressions are caught before reaching production.

#### Acceptance Criteria

1. THE project SHALL include unit tests for all Android domain Use Cases and Repository implementations using JUnit and MockK, and for all ViewModel state logic using JUnit and MockK. Backend service functions SHALL have unit tests using Pytest. The Backend unit test suite SHALL achieve at least 70% line coverage as measured by the Pytest coverage tool.
2. THE project SHALL include integration tests for all Backend API endpoints using Pytest. Each test SHALL create a fresh database schema before execution and tear it down after, ensuring test isolation. Tests SHALL use mock LLM_Provider responses.
3. THE project SHALL include Compose UI tests for the following flows: login, AI chat (send message and receive response), document upload, voice activation, and settings navigation (open Settings, modify one setting, return to previous screen).
4. THE project SHALL provide mock LLM_Provider implementations that return identical output for identical input and make no external network requests, for use in automated tests.
5. IF a valid text Document in PDF, DOCX, TXT, or Markdown format is ingested by the RAG_Pipeline, THEN querying a verbatim phrase of at least 3 words present in the Document SHALL return at least one Chunk containing that phrase.
6. IF a valid Message input is processed by the AI_Orchestrator, THEN the output token count recorded SHALL be greater than zero and SHALL not exceed the maximum context window declared by the mock LLM_Provider.

---

### Requirement 22: Documentation

**User Story:** As a developer joining the project, I want comprehensive technical documentation, so that I can understand the system and contribute without requiring extensive onboarding.

#### Acceptance Criteria

1. THE project SHALL include a `/docs` directory containing separate Markdown documents for: Project Vision, PRD, System Architecture, Android Architecture, Backend Architecture, AI Architecture, RAG Architecture, Database Design, API Specification, Security Guide, Performance Guide, Testing Strategy, DevOps Guide, MCP Integration, Coding Standards, and Deployment Guide. Each document SHALL contain at minimum a title heading, a purpose section, and at least one substantive content section.
2. THE System Architecture document SHALL include a Mermaid component diagram showing the relationships between all major system components.
3. THE API Specification SHALL document every REST endpoint and WebSocket event with request schema, response schema, authentication requirements, and at least one valid example request and one valid example response per endpoint or event.
4. EVERY source code file SHALL include a header block containing all four of the following fields: purpose, architectural placement, dependencies, and design decision or pattern. A file is non-conformant if any field is missing.
5. THE Database Design document SHALL include an entity-relationship diagram covering all database tables with column types, constraints, and foreign key relationships.
6. THE project README SHALL include setup instructions that assume only a clean OS installation and internet access, list every required tool with its required version, and enable a developer to complete the full local development setup (Android Studio and Backend Docker Compose) in under 15 minutes.

---

### Requirement 23: Accessibility

**User Story:** As a User with accessibility needs, I want the app to be usable with assistive technologies, so that I am not excluded from any feature.

#### Acceptance Criteria

1. THE AI_Assistant SHALL achieve WCAG 2.1 AA compliance for all screens, including minimum contrast ratios of 4.5:1 for normal text and 3:1 for large text.
2. THE AI_Assistant SHALL provide content descriptions for all interactive UI elements without visible text labels, such that a screen reader can announce the element's purpose to the User.
3. THE AI_Assistant SHALL support dynamic text scaling up to 200% on any screen in the app without content truncation or layout overflow.
4. THE AI_Assistant SHALL not rely solely on color to convey information; every color-coded status indicator SHALL also display an icon or text label conveying the same information.
5. THE AI_Assistant SHALL support keyboard navigation on devices with physical keyboards, with focus moving in top-to-bottom, left-to-right visual order, every interactive element reachable via the Tab key, and no pointer device required to activate any element.

---

### Requirement 24: Theming and UI Design System

**User Story:** As a User, I want a polished, consistent visual experience with light and dark themes, so that the app feels professional and comfortable to use.

#### Acceptance Criteria

1. THE AI_Assistant SHALL implement Material Design 3's color system, type scale, and shape scale, with all spacing values using 8 dp increments.
2. THE AI_Assistant SHALL support Light, Dark, and System-default themes. WHEN a User selects a theme, THE AI_Assistant SHALL apply that theme on all subsequent launches without requiring re-selection.
3. WHERE Android 12 or later is detected, THE AI_Assistant SHALL support Material You dynamic color theming derived from the User's wallpaper.
4. IF the device screen width is 600 dp or greater, THEN THE AI_Assistant SHALL display a two-pane layout on the Chat and History screens showing the Conversation list in the primary pane and the active Conversation or detail in the secondary pane.
5. WHEN the device transitions from folded to unfolded posture, THE AI_Assistant SHALL expand to a two-pane layout. WHEN the device transitions from unfolded to folded posture, THE AI_Assistant SHALL collapse to a single-pane layout.

---

### Requirement 25: Prompt Engineering and AI Safety

**User Story:** As an Admin, I want prompt templates, versioning, and safety guardrails, so that AI responses are consistent, controlled, and free from harmful content.

#### Acceptance Criteria

1. THE AI_Orchestrator SHALL manage prompt templates in a versioned store, associating each template with a version number, creation timestamp, and author.
2. WHEN a prompt template is updated, THE AI_Orchestrator SHALL preserve the previous version and allow rollback to any prior version by an Admin.
3. THE AI_Orchestrator SHALL apply safety filters to every LLM_Provider response and redact content classified as harmful before delivering it to the User.
4. IF the safety filter fails to properly redact classified harmful content, THEN THE AI_Orchestrator SHALL block the entire response and return an error to the User rather than deliver it unredacted.
5. THE AI_Orchestrator SHALL detect and block prompt injection patterns (including role-override and instruction-override attempts) in User input before forwarding to the LLM_Provider, logging each blocked attempt with user ID and a hash of the blocked input.
6. THE AI_Orchestrator SHALL enforce a configurable maximum response length in tokens per LLM_Provider. WHEN a response reaches the configured maximum, THE AI_Orchestrator SHALL truncate the response and append a truncation notice before delivering it to the User.
7. THE AI_Orchestrator SHALL include a system prompt in every LLM_Provider request that enforces the assistant's persona, scope, and safety rules, and SHALL NOT allow the User to override the system prompt directly.

---

### Requirement 26: WebSocket Streaming Infrastructure

**User Story:** As a developer, I want a reliable WebSocket infrastructure for streaming AI responses, so that Users receive real-time token output with resilient connection handling.

#### Acceptance Criteria

1. THE Backend SHALL implement a WebSocket endpoint at `/ws/chat/{conversation_id}` that authenticates connections using a JWT query parameter. IF the JWT is absent, expired, or invalid, THE Backend SHALL close the connection with WebSocket close code 4001 before streaming begins.
2. WHEN a WebSocket connection is established, THE Backend SHALL send a heartbeat ping every 30 seconds and close the connection with close code 1001 if no pong is received within 10 seconds.
3. WHEN a Streaming_Response is in progress and the WebSocket connection drops, THE Backend SHALL buffer up to 1,000 tokens for 60 seconds. WHEN the client reconnects within 60 seconds, THE Backend SHALL deliver all buffered tokens before resuming the live stream.
4. THE AI_Assistant SHALL implement automatic WebSocket reconnection with exponential backoff starting at 1 second, doubling each attempt, capping at 30 seconds, for up to 5 attempts. IF all 5 attempts fail, THE AI_Assistant SHALL display a connection-failed error and stop retrying.
5. THE Backend SHALL emit structured WebSocket events distinguishing: `token` (incremental text), `done` (stream complete), `error` (failure with error code and message), and `tool_call` (MCP_Tool invocation notification with tool name).

---

### Requirement 27: Background Jobs and Async Processing

**User Story:** As a developer, I want background job processing for long-running operations, so that API responses remain fast and operations complete reliably.

#### Acceptance Criteria

1. THE Backend SHALL use Celery with Redis as the message broker for all asynchronous jobs, including document ingestion, embedding generation, and push notification dispatch.
2. WHEN a document ingestion job is enqueued, THE Backend SHALL return a job ID immediately. THE User MAY poll job status at `/jobs/{job_id}`, which SHALL return one of the states: `queued`, `processing`, `completed`, or `failed`.
3. IF a Celery job fails, THEN THE Backend SHALL retry it up to 3 times with exponential backoff of `2^n` seconds (n=0: 1 s, n=1: 2 s, n=2: 4 s). IF all retries fail, THE Backend SHALL mark the job as `failed` and surface the failure in the Admin_Dashboard.
4. THE Backend SHALL expose Celery worker metrics (queue depth, active tasks, failed tasks) to the Prometheus metrics endpoint at `/metrics`.
5. WHILE a document ingestion job has status `queued` or `processing`, THE AI_Assistant SHALL display an in-progress status indicator for the Document in the RAG document list.

---

### Requirement 28: Data Privacy and Compliance

**User Story:** As a User, I want control over my data and assurance that privacy regulations are respected, so that I can trust the platform with sensitive information.

#### Acceptance Criteria

1. WHEN a User requests a data export, THE Backend SHALL return a structured JSON archive of all that User's data (conversations, messages, documents, memories, notes) within 24 hours. IF the export is not ready within 24 hours, THE Backend SHALL notify the User and provide a reason.
2. WHEN a User confirms account deletion, THE Backend SHALL permanently remove all of that User's data, including all Embeddings in the Vector_Store, within 72 hours. IF deletion is not completed within 72 hours, THE Backend SHALL notify the User and an Admin.
3. WHEN a User reaches the onboarding consent screen, THE AI_Assistant SHALL display a clear privacy policy and terms of service and SHALL require an affirmative tap to accept before enabling any optional data collection.
4. THE AI_Orchestrator SHALL NOT store raw User messages in any third-party service beyond the configured LLM_Provider API call. User message content SHALL NOT be logged in plaintext in any Backend log or database outside the Conversation record.
5. THE Backend SHALL enforce data residency configuration, allowing deployments to restrict all data storage to a specified geographic region. IF a data write would violate the configured residency constraint, THE Backend SHALL reject the operation with a structured error.

---

### Requirement 29: Productivity Suite

**User Story:** As a User, I want AI-powered to-do lists, calendar, reminders, and habit tracking, so that I can manage my work and personal goals in one place with intelligent assistance.

#### Acceptance Criteria

1. THE AI_Assistant SHALL provide a To-Do feature allowing the User to create, view, update, and delete TodoItem entries with title, description, due date, priority, and tags, persisted locally in Room and synchronized to the Backend.
2. WHEN a User provides a natural language prompt, THE AI_Orchestrator SHALL generate a structured list of up to 20 TodoItem entries relevant to the prompt and present them to the User for confirmation before saving.
3. THE AI_Assistant SHALL provide a Calendar feature displaying CalendarEvent objects in a monthly and weekly grid view. WHEN the Google Calendar MCP connector is configured and the device is online, THE AI_Assistant SHALL merge Google Calendar events into the local display, with local events taking precedence on title conflicts.
4. WHEN a User requests AI-suggested meeting times, THE AI_Orchestrator SHALL return a list of available time slots (minimum 3, maximum 10) based on the User's existing CalendarEvent entries, each slot showing start time, end time, and duration.
5. THE AI_Assistant SHALL provide a Reminders feature allowing the User to create Reminder entries with a title, trigger time, iCal RRULE recurrence rule, and an optional linked TodoItem. THE AI_Assistant SHALL deliver local notifications at the scheduled trigger time using exact alarms. IF notification permission is denied, THE AI_Assistant SHALL display an in-app reminder instead.
6. WHEN a User provides a natural language description of a task, THE AI_Orchestrator SHALL generate a suggested Reminder with a title and trigger time and present it to the User for confirmation before saving.
7. THE AI_Assistant SHALL provide a Habit_Tracker feature allowing the User to define habits with a name, description, and recurrence (daily or weekly), log daily completion, and view AI-generated insights about completion patterns, best and worst days, and streak predictions. AI insights SHALL only be generated after at least 7 days of logged data.
8. IF a User attempts to access another User's Productivity_Suite data, THEN THE Backend SHALL return HTTP 403.
9. THE AI_Assistant SHALL store all Productivity_Suite data locally in Room and synchronize it to the Backend when the device has network connectivity. Conflict resolution SHALL use the record with the latest device `updated_at` timestamp as the authoritative version, tracked via a `syncStatus` field with values `pending`, `processing`, `ready`, and `failed`.

---

### Requirement 30: AI Model Comparison Mode

**User Story:** As a User, I want to send the same prompt to multiple AI models simultaneously and compare their responses side-by-side, so that I can choose the best answer and understand the trade-offs between providers.

#### Acceptance Criteria

1. WHEN a User activates Comparison Mode and submits a Message, THE AI_Orchestrator SHALL dispatch the same prompt concurrently to all selected LLM_Providers (minimum 2, maximum 4) and return each response in a separate panel within the AI_Assistant.
2. WHEN all LLM_Provider responses for a Comparison Mode Message are received, THE AI_Assistant SHALL display each response in a side-scrollable panel showing the provider name, the full response text rendered with Markdown formatting, token count, latency from dispatch to first token (ms), and estimated token cost in USD calculated using the configured per-token pricing.
3. THE AI_Orchestrator SHALL dispatch all selected LLM_Provider requests within 100 ms of each other, so that latency differences reflect provider performance rather than dispatch scheduling.
4. WHEN one LLM_Provider in a Comparison Mode request fails or times out after 30 seconds, THE AI_Orchestrator SHALL display an error panel for that provider and continue displaying results from the remaining providers.
5. THE AI_Assistant SHALL display a quality score for each Comparison Mode response, computed as a normalized composite of response length (0–40 points), coherence as assessed by a secondary lightweight LLM evaluation call (0–40 points), and latency (0–20 points, with lower latency scoring higher). The quality score SHALL be displayed as a value between 0 and 100.
6. WHEN a User selects "Use This Response" on one panel in Comparison Mode, THE AI_Assistant SHALL adopt that response as the canonical Message in the active Conversation and dismiss the other panels.
7. THE AI_Orchestrator SHALL record per-provider token usage and cost for every Comparison Mode request and associate all entries with the User's usage record.
8. IF fewer than 2 LLM_Providers are configured and available, THEN THE AI_Assistant SHALL disable the Comparison Mode control and display a tooltip explaining that at least 2 active providers are required.

---

### Requirement 31: On-Device AI Inference

**User Story:** As a User, I want the app to run AI models directly on my device when supported, so that I can use AI features without a network connection and with full data privacy.

#### Acceptance Criteria

1. WHERE the device reports a Neural Processing Unit (NPU) or GPU with at least 4 GB of available dedicated memory, THE AI_Assistant SHALL offer on-device inference as a selectable LLM_Provider using a quantized (INT4 or INT8) Llama or Mistral model bundled or downloaded to local storage.
2. WHEN on-device inference is selected and a Message is submitted, THE AI_Assistant SHALL route the request entirely to the on-device model, making no network calls to the Backend or any external LLM_Provider endpoint for that request.
3. WHEN an on-device inference request is initiated, THE AI_Assistant SHALL display a "Running on device" indicator so the User knows no data is transmitted externally.
4. WHILE an on-device inference request is executing and available device RAM falls below 512 MB, THE AI_Assistant SHALL cancel the on-device request, display an "Insufficient resources — switching to cloud" message, and automatically retry the same prompt against the configured fallback LLM_Provider.
5. WHEN on-device inference produces a response, THE AI_Assistant SHALL begin displaying tokens within 2,000 ms of request submission on a device meeting the NPU/GPU threshold defined in criterion 1.
6. IF the on-device model files are absent or corrupt, THEN THE AI_Assistant SHALL detect the condition at startup, display a download prompt, and fall back to the configured cloud LLM_Provider until the model files are successfully downloaded and verified.
7. THE AI_Assistant SHALL verify the integrity of on-device model files using a SHA-256 checksum against the bundled manifest before loading the model. IF the checksum does not match, THE AI_Assistant SHALL treat the files as corrupt per criterion 6.
8. WHEN the device is offline and on-device inference is available, THE AI_Assistant SHALL allow full AI chat using the on-device model without queuing Messages for later delivery.

---

### Requirement 32: AI Persona and System Prompt Customization

**User Story:** As a User, I want to create and switch between custom AI personas with distinct system prompts, so that I can tailor the assistant's tone and scope to different tasks.

#### Acceptance Criteria

1. THE AI_Assistant SHALL allow each User to create, name, save, and delete custom Persona objects, where each Persona contains a unique name (1–80 characters), a system prompt (1–4,000 characters), a tone descriptor (one of: `professional`, `casual`, `concise`, `detailed`, `creative`), and a scope description (0–500 characters).
2. WHEN a User selects a Persona, THE AI_Orchestrator SHALL inject that Persona's system prompt, tone, and scope into the LLM_Provider system message for all subsequent Messages in the active Conversation, replacing any previously active Persona.
3. THE AI_Assistant SHALL allow each User to store up to 20 Personas. IF a User attempts to create a 21st Persona, THE AI_Assistant SHALL return an error indicating the limit has been reached and prompt the User to delete an existing Persona.
4. WHERE a User's role does not include `admin`, THE AI_Orchestrator SHALL append the platform-level safety and scope rules defined in Requirement 25 criterion 7 to the User's selected Persona system prompt, so that User-defined Personas cannot override platform safety guardrails.
5. WHEN an Admin locks a Persona by setting its `admin_locked` flag, THE AI_Assistant SHALL prevent Users from editing or deleting that Persona and SHALL display a lock indicator on the Persona entry.
6. WHERE an Admin configures a role-restricted Persona list for a given RBAC role, THE AI_Assistant SHALL display only the Personas permitted for the User's role plus the User's own Personas, and SHALL NOT expose Personas restricted to other roles.
7. WHEN a User switches Personas within an active Conversation, THE AI_Assistant SHALL display a system message in the Conversation timeline indicating the Persona change with the new Persona name and timestamp.
8. IF a Persona's system prompt contains a prompt injection pattern as defined in Requirement 25 criterion 5, THEN THE AI_Orchestrator SHALL reject the Persona save operation with HTTP 422 and a `PROMPT_INJECTION_DETECTED` error code.

---

### Requirement 33: Context-Aware AI Suggestions

**User Story:** As a User, I want the assistant to proactively surface relevant suggestions based on what I am currently doing in the app, so that I can take helpful next steps without having to ask.

#### Acceptance Criteria

1. WHEN the User is viewing or editing a Note and has not interacted for 5 seconds, THE AI_Orchestrator SHALL generate a set of 1–3 contextual suggestions for the active Note (such as summarize, expand, or add action items) and display them as dismissible chips above the keyboard without blocking the editor.
2. WHEN the User is viewing a CalendarEvent, THE AI_Orchestrator SHALL generate a set of 1–3 pre-meeting suggestions (such as draft agenda, prep questions, or lookup attendee profiles) and display them as a non-blocking card below the event details.
3. WHEN the User is viewing a Conversation and the last Message is more than 24 hours old, THE AI_Orchestrator SHALL offer a "Continue this conversation" suggestion that pre-populates the input field with a contextual continuation prompt based on the last Message content.
4. THE AI_Assistant SHALL limit context-aware suggestion generation to at most one generation request per screen per 5-second idle window, so that multiple rapid idle events do not cause redundant requests.
5. WHEN the User dismisses a suggestion chip or card, THE AI_Assistant SHALL suppress suggestions of the same type for that screen instance for the remainder of the session.
6. IF the AI_Orchestrator does not return a suggestion within 3 seconds of the trigger event, THE AI_Assistant SHALL silently suppress the suggestion for that trigger and NOT display a loading indicator.
7. THE AI_Orchestrator SHALL NOT generate context-aware suggestions when Privacy Mode is enabled per Requirement 7 criterion 6.
8. THE AI_Assistant SHALL allow the User to disable context-aware suggestions globally from the Settings screen. WHEN disabled, THE AI_Orchestrator SHALL not be invoked for any proactive suggestion on any screen.

---

### Requirement 34: AI Cost Dashboard for Users

**User Story:** As a User, I want to see my own token usage and estimated costs broken down by feature and provider, so that I can manage my spending and receive alerts before reaching my limit.

#### Acceptance Criteria

1. THE AI_Assistant SHALL provide a personal Cost Dashboard screen accessible from the Settings screen, displaying the User's own token usage and estimated cost in USD broken down by: feature (chat, RAG, code, voice, comparison, suggestions), LLM_Provider, and calendar day, covering the most recent 90 days.
2. WHEN the Cost Dashboard screen is opened, THE Backend SHALL return aggregated usage data for the authenticated User within 2 seconds. IF the data is not available within 2 seconds, THE AI_Assistant SHALL display a loading state and continue waiting up to 10 seconds before displaying an error.
3. THE AI_Assistant SHALL display a monthly usage summary showing total tokens consumed, total estimated cost in USD, and a bar chart of daily cost for the current calendar month.
4. WHEN a User sets a spending alert threshold (minimum $0.01, maximum $999.99, in USD), THE Backend SHALL monitor that User's accumulated estimated daily cost and, WHEN the threshold is reached, send an in-app notification within 60 seconds.
5. WHEN a spending alert notification is delivered, THE AI_Assistant SHALL display a persistent banner on the Cost Dashboard screen indicating the threshold that was reached, the current accumulated cost, and the date the threshold was crossed. The banner SHALL remain visible until the User explicitly dismisses it.
6. THE AI_Assistant SHALL allow the User to configure up to 3 distinct spending alert thresholds. IF a User attempts to add a 4th threshold, THE AI_Assistant SHALL display an error and prompt deletion of an existing threshold.
7. IF a User's role does not include `admin`, THEN THE Backend SHALL return only that User's own usage data from the cost endpoint and SHALL return HTTP 403 if the request includes another User's identifier.
8. THE Backend SHALL store per-User usage records with granularity of one record per Message per LLM_Provider, retaining records for a minimum of 90 days before archival.

---

### Requirement 35: Federated Multi-Backend Support

**User Story:** As an enterprise Admin, I want to configure multiple backend instances across regions or departments and have requests automatically routed to the correct backend, so that data residency rules are enforced and service is uninterrupted during backend failures.

#### Acceptance Criteria

1. THE AI_Assistant SHALL support configuration of multiple Backend endpoints, each identified by a name, base URL, geographic region tag, and an ordered set of RBAC roles whose data it is authorized to process.
2. WHEN the AI_Assistant initiates a request for an authenticated User, THE AI_Assistant SHALL select the Backend endpoint whose region tag and role authorization match the User's data residency requirement and RBAC role. IF multiple eligible backends exist, THE AI_Assistant SHALL select the one with the lowest measured round-trip latency from the most recent health check.
3. WHEN the selected Backend returns a connection error or a 5xx HTTP response on any request, THE AI_Assistant SHALL automatically retry the same request against the next eligible Backend in the configured list within 2 seconds, without user intervention.
4. IF no eligible Backend is reachable after exhausting all configured endpoints, THEN THE AI_Assistant SHALL display a structured error message identifying the outage and SHALL NOT route the request to any non-eligible Backend regardless of availability.
5. THE AI_Assistant SHALL perform health checks against all configured Backend endpoints every 30 seconds and update the endpoint selection ranking without requiring an app restart.
6. WHEN failover to a secondary Backend occurs, THE AI_Assistant SHALL display a non-blocking informational banner indicating which backend is currently active and the reason for failover.
7. THE AI_Assistant SHALL ensure that data submitted to one Backend is NOT replicated or forwarded to any other Backend instance by the client. Cross-backend data synchronization, if required, is the sole responsibility of the server-side infrastructure.
8. WHERE an Admin publishes a Backend federation configuration via Firebase_Remote_Config, THE AI_Assistant SHALL apply the updated configuration within 60 seconds of publication without requiring an app update or restart.

---

### Requirement 36: AI-Powered Semantic Search Across All Content

**User Story:** As a User, I want to search across all my conversations, notes, documents, and memories using natural language, so that I can find relevant content quickly regardless of how it was stored.

#### Acceptance Criteria

1. THE AI_Assistant SHALL provide a unified Semantic Search screen that accepts a natural language query and searches across Conversations, Notes, Documents (RAG Chunks), and Memories belonging to the authenticated User.
2. WHEN a semantic search query is submitted, THE Backend SHALL generate an Embedding for the query using the SentenceTransformer model, perform a vector similarity search across all four content-type collections scoped to the User, and return ranked results within 3 seconds for a corpus of up to 100,000 stored Embeddings.
3. THE Backend SHALL return search results with a minimum relevance score threshold of 0.5 (cosine similarity), sorted descending by score. Each result SHALL include: source type (Conversation, Note, Document, Memory), source name or title, a text excerpt of up to 300 characters surrounding the most relevant passage, the relevance score (0.0–1.0, two decimal places), and a deep-link navigating the AI_Assistant to the source item.
4. THE AI_Assistant SHALL group search results by source type and display a result count per group. WHEN the User taps a result, THE AI_Assistant SHALL navigate to the source item and highlight the matched excerpt.
5. IF the semantic search corpus contains no Embeddings for a content type, THE Backend SHALL omit that content type's group from the results rather than returning an empty group.
6. THE Backend SHALL maintain a round-trip property for semantic search: for any text excerpt of at least 10 words stored in any content type, submitting that exact excerpt as a search query SHALL return a result referencing the originating item with a relevance score of at least 0.90.
7. THE Backend SHALL NOT perform keyword full-text search as the primary retrieval mechanism for semantic search; vector similarity SHALL be the sole ranking signal for result ordering.
8. IF the semantic search request does not return any results above the 0.5 threshold, THE AI_Assistant SHALL display a "No results found" state with a suggestion to try rephrasing the query, rather than an error.

---

### Requirement 37: Differential Privacy for Memory

**User Story:** As a User, I want my stored memories to be protected by privacy-preserving techniques, so that even direct access to the database cannot reconstruct my personal information from the stored vectors.

#### Acceptance Criteria

1. THE Memory_Service SHALL apply calibrated Laplace noise to every Embedding before storing it in the Vector_Store, where the noise scale (epsilon) is configurable by an Admin in the range 0.1 to 10.0, with a default value of 1.0. Smaller epsilon values correspond to stronger privacy protection.
2. WHEN an Admin updates the differential privacy epsilon value, THE Memory_Service SHALL apply the new epsilon to all subsequently stored Embeddings within 5 seconds of the configuration change being published, without requiring a service restart.
3. THE Memory_Service SHALL record a `privacy_budget_spent` counter per User, incrementing by the epsilon value each time a new memory Embedding is stored, and expose the per-User accumulated budget to the Admin_Dashboard.
4. WHEN the Memory_Service retrieves memories for prompt injection per Requirement 7 criterion 2, THE Memory_Service SHALL use the noised Embeddings stored in the Vector_Store, ensuring that retrieval quality is evaluated against the same noise-perturbed vectors that are exposed externally.
5. THE Memory_Service SHALL ensure that for any stored noised Embedding, a query using the original unnoised Embedding vector SHALL still return the noised Embedding as a top-3 result with a cosine similarity of at least 0.70, preserving sufficient retrieval utility under the default epsilon of 1.0.
6. IF the Admin sets epsilon below 0.1 or above 10.0, THEN THE Backend SHALL reject the configuration update with HTTP 422 and an error identifying the valid range.
7. WHEN differential privacy is enabled, THE Admin_Dashboard SHALL display the current epsilon value, the noise mechanism (Laplace), and a plain-language explanation of the privacy guarantee, alongside the per-User privacy budget consumed.
8. THE Memory_Service SHALL apply differential privacy noise independently to each Embedding dimension so that no single noise sample is correlated across dimensions, consistent with the standard Laplace mechanism for vector data.

---

### Requirement 26: Secret Management and Environment Configuration

**User Story:** As an Admin, I want all runtime secrets and environment variables to be securely managed and documented, so that the system can be deployed reproducibly without exposing credentials.

#### Acceptance Criteria

1. THE Backend SHALL read all secrets (JWT secret key, AES encryption key, LLM provider API keys, database passwords, MinIO credentials, SMTP credentials, Firebase service account path, Google OAuth client credentials) exclusively from environment variables or a mounted secrets file. No secret value SHALL be hardcoded in source code or committed to version control.
2. THE project SHALL provide a `.env.example` file at the repository root and at `backend/` that documents every required environment variable with a description and placeholder value. The `.env` file containing real values SHALL be listed in `.gitignore` and SHALL NOT be committed.
3. THE Backend SHALL validate at startup that all required environment variables are present and non-empty. IF any required variable is absent, THE Backend SHALL log a structured error identifying the missing variable and exit with a non-zero code before accepting any requests.
4. THE Backend SHALL expose a `GET /ready` endpoint that returns HTTP 200 only when all required services (PostgreSQL, Redis) are reachable and all required environment variables are present. IF any dependency is unreachable, THE endpoint SHALL return HTTP 503 with a structured body identifying the failing dependency.
5. WHERE AES-256 encryption is applied to LLM provider API keys stored in the database, THE Backend SHALL use a key sourced from the `AES_ENCRYPTION_KEY` environment variable. IF this variable is absent at startup, THE Backend SHALL refuse to start.
6. THE `LLM_FALLBACK_PROVIDER` environment variable SHALL be documented in `.env.example`. WHEN this variable is set and the primary LLM_Provider fails, THE AI_Orchestrator SHALL fall back to the configured provider. WHEN the variable is absent or empty, THE AI_Orchestrator SHALL return a structured error without attempting fallback.

---

### Requirement 27: CI/CD Pipeline and Release Process

**User Story:** As a developer, I want automated CI/CD pipelines that enforce quality gates and produce reproducible signed releases, so that every merge to main is safe to ship and every release is traceable.

#### Acceptance Criteria

1. THE project SHALL provide GitHub Actions workflows that run on every pull request targeting any branch: Android lint, unit tests, Detekt static analysis, ktlint style check, JaCoCo coverage check (≥70% on `domain` + `data` combined), and instrumented Compose UI tests on an Android API 34 emulator.
2. WHEN a pull request workflow job fails, the merge to the target branch SHALL be blocked. All required status checks SHALL be configured as branch-protection rules on `main`.
3. THE project SHALL provide a backend CI workflow that runs on every pull request touching `backend/`: ruff lint, mypy type check, pytest unit and property-based tests with coverage ≥70%, and pytest integration tests against real PostgreSQL 16 and Redis 7 service containers.
4. WHEN a version tag matching `v<major>.<minor>.<patch>` is pushed, THE release workflow SHALL build a signed Android App Bundle (AAB) and APK, publish the AAB to the configured Google Play Store track, build and push a multi-arch Docker image to GitHub Container Registry tagged with the semver version, and create a GitHub Release with auto-generated changelog.
5. THE release workflow SHALL sign the Android release artifacts using a keystore stored as a base64-encoded GitHub Actions secret. The keystore file SHALL NOT be committed to the repository.
6. THE backend Docker image SHALL be built as a multi-stage image (builder + production stages) producing a minimal runtime image that runs as a non-root user. The image SHALL pass a Trivy CRITICAL-severity vulnerability scan before being pushed.
7. THE release workflow SHALL deploy the new backend image to the staging environment automatically, run a `/health` smoke test, and require manual approval from a configured reviewer before deploying to production.
8. ALL GitHub Actions workflow files SHALL use pinned action versions (e.g. `actions/checkout@v4`) and SHALL NOT use `latest` or floating version references.

---

### Requirement 28: Security Scanning and Vulnerability Management

**User Story:** As an Admin, I want automated security scanning integrated into the development workflow, so that vulnerabilities and credential leaks are detected before they reach production.

#### Acceptance Criteria

1. THE project SHALL run CodeQL semantic analysis for both Kotlin/Java (Android) and Python (Backend) on every pull request and on every push to `main`. Findings SHALL be surfaced in the GitHub Security tab via SARIF upload.
2. THE project SHALL run Gitleaks secret-scanning on the full Git history on every push to `main` and on every pull request. IF a secret pattern is detected, the workflow job SHALL fail and block the merge.
3. THE project SHALL run Bandit Python SAST on the `backend/app` directory on every pull request. Findings at HIGH severity and HIGH confidence SHALL fail the workflow job.
4. THE project SHALL run a Trivy image vulnerability scan on the backend Docker image on every push to `main`. IF any CRITICAL-severity unfixed vulnerability is found, the workflow job SHALL fail.
5. THE project SHALL run an OWASP Dependency-Check scan against all Python and Gradle dependencies on a weekly schedule. Findings with CVSS score ≥7.0 SHALL fail the workflow job unless suppressed in `.github/dependency-check-suppression.xml` with a documented justification and expiry date.
6. THE project SHALL run a Python Safety check against `backend/requirements.txt` on every pull request. Any critical CVE SHALL fail the workflow job.
7. THE `network_security_config.xml` Android resource SHALL pin the backend TLS certificate. THE Backend SHALL present a certificate matching the pinned fingerprint. IF the certificate is rotated, the `network_security_config.xml` SHALL be updated in the same deployment.

---

### Requirement 29: Infrastructure Validation and Deployment Readiness

**User Story:** As a DevOps engineer, I want infrastructure configuration validated automatically, so that misconfigured Nginx, Prometheus, or database migrations never reach production.

#### Acceptance Criteria

1. THE project SHALL validate `docker-compose.yml` syntax and service image tag format on every pull request that touches infrastructure files. IF any service image uses a floating `:latest` tag without a build context, THE validation job SHALL emit a warning.
2. THE Nginx configuration SHALL be validated with `nginx -t` inside a container on every pull request that modifies `infrastructure/nginx/`. IF the syntax check fails, the pull request SHALL be blocked.
3. THE Prometheus configuration SHALL be validated with `promtool check config` and all alert rule files with `promtool check rules` on every pull request that modifies `infrastructure/prometheus/`. IF either check fails, the pull request SHALL be blocked.
4. THE Alembic migration chain SHALL be validated on every pull request that modifies `backend/alembic/` by applying all migrations (`upgrade head`) and then rolling back all migrations (`downgrade base`) against a temporary PostgreSQL service container. IF either direction fails, the pull request SHALL be blocked.
5. THE Alembic migration chain SHALL have exactly one head at all times. IF a pull request creates a second migration head (a branch), the validation job SHALL detect this via `alembic heads` and fail.
6. ALL Grafana dashboard JSON files in `infrastructure/grafana/provisioning/` SHALL be valid JSON. IF any file fails JSON parsing, the validation job SHALL fail.
7. THE `docker-compose.yml` `chromadb` service SHALL use a port that does not conflict with the `backend` service port (8000). ChromaDB SHALL be configured on port 8001 or higher.

---

### Requirement 30: Dependency Injection Completeness

**User Story:** As an Android developer, I want every class in the dependency graph to be properly annotated for Hilt injection, so that the app never crashes due to a missing binding at runtime.

#### Acceptance Criteria

1. EVERY use case class in the `domain` module SHALL declare an `@Inject` constructor. The `domain` module's `build.gradle.kts` SHALL declare a `javax.inject:javax.inject:1` dependency to provide the `@Inject` annotation without introducing Android framework dependencies.
2. EVERY ViewModel in every feature module SHALL be annotated with `@HiltViewModel` and declare an `@Inject` constructor. No ViewModel SHALL be instantiated manually.
3. THE `core-security` module SHALL provide all `String` resources referenced by `BiometricAuthManager` in `core-security/src/main/res/values/strings.xml`, including `biometric_prompt_title`, `biometric_prompt_subtitle`, `biometric_prompt_negative_button`, `biometric_not_available`, and `biometric_not_enrolled`. IF any required string resource is absent, the app SHALL fail to compile rather than crash at runtime.
4. WHEN the Hilt component graph is generated at build time, there SHALL be zero unresolved binding errors. The `:app:kaptDebugKotlin` (or KSP equivalent) build step SHALL succeed with no Hilt-related errors.

---

### Requirement 31: Android Test Coverage

**User Story:** As a developer, I want sufficient Android test coverage on the domain and data layers, so that regressions are caught before they reach users.

#### Acceptance Criteria

1. THE combined JaCoCo instruction coverage for the `domain` and `data` modules SHALL be at least 70% and SHALL be enforced by the Android CI workflow on every pull request.
2. EVERY use case in the `domain` module SHALL have at least one unit test that verifies the happy path, and at least one test that verifies the primary error path (e.g. repository error propagation).
3. EVERY `*RepositoryImpl` in the `data` module SHALL have unit tests that mock the local Room DAO and remote Retrofit service, verifying local-first emission, remote sync trigger, and conflict resolution.
4. EVERY ViewModel in the feature modules SHALL have unit tests using Turbine (or equivalent) that verify `StateFlow` emissions for at least the primary success state and the primary error state.
5. THE `core-ui` module SHALL have Compose UI tests verifying `MarkdownText` renders all six node types (header, bold, italic, inline code, fenced code, table, bullet list), `CodeBlock` displays the language identifier, and adaptive layout switches to two-pane at 600 dp width.

