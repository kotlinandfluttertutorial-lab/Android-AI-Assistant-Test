# Kiro Prompt – Enterprise Android AI Assistant

You are an Expert Android Architect, AI Engineer, Backend Engineer, UI/UX Engineer, DevOps Engineer, and Security Engineer.

Build a **production-ready Android AI Assistant** that can compete with ChatGPT, Gemini, Claude, and Microsoft Copilot while following Google's latest Android development guidelines.

## Primary Goal

Create a scalable, enterprise-grade AI Assistant application with:

* Modern Android architecture
* AI Chat
* Voice Assistant
* PDF Chat (RAG)
* Image Understanding
* AI Code Assistant
* AI Resume Builder
* AI Email Writer
* AI Translator
* AI Notes
* AI Meeting Assistant
* AI Memory
* Multi-LLM Support
* Offline AI
* MCP Integration

---

# Technology Stack

## Android

* Kotlin
* Jetpack Compose
* Material 3
* MVVM
* Clean Architecture
* Repository Pattern
* Use Cases
* SOLID Principles
* Modular Architecture
* Hilt
* Coroutines
* Flow
* StateFlow
* SharedFlow
* Room
* DataStore
* Retrofit
* OkHttp
* Coil
* Timber
* WorkManager

---

## Backend

Generate a FastAPI backend using:

* Python
* FastAPI
* LangChain
* LlamaIndex
* PostgreSQL
* ChromaDB
* Redis
* Celery
* Docker

---

## AI Providers

Support multiple providers with an abstraction layer:

* OpenAI
* Google Gemini
* Anthropic Claude
* Ollama
* Llama 3.x
* Mistral

Allow switching AI providers from the Settings screen.

---

# MCP (Model Context Protocol)

Implement MCP support for external tools.

Create connectors for:

* GitHub
* Gmail
* Google Drive
* Google Calendar
* Notion
* Slack
* Jira
* Confluence
* Figma
* Linear

The assistant should automatically detect available tools and invoke them through MCP when appropriate.

---

# AI Features

## Chat

* Streaming responses
* Markdown
* Tables
* Code highlighting
* Images
* Citations
* Conversation history
* Regenerate
* Continue generation
* Edit prompt
* Share
* Copy
* Export conversation

---

## AI Memory

Implement long-term memory.

Remember:

* User preferences
* Writing style
* Frequently used prompts
* Favorite programming languages
* Frequently accessed documents

Store memories using vector embeddings.

---

## RAG

Allow user to upload:

* PDF
* DOCX
* TXT
* Markdown
* Images

Create embeddings.

Store in ChromaDB.

Allow semantic search.

Answer questions using uploaded files.

---

## Voice Assistant

Implement

* Speech-to-text
* Text-to-speech
* Voice wake word
* Continuous conversation
* Interrupt AI while speaking
* Multiple voice options

---

## Vision

Allow

* Camera
* Gallery
* OCR
* Image captioning
* Receipt scanner
* Document scanner
* Barcode scanning

---

## AI Code Assistant

Generate

* Kotlin
* Java
* Python
* C++
* JavaScript

Features

* Explain code
* Debug
* Refactor
* Unit tests
* Documentation
* Architecture review

---

## Resume Builder

Generate

* ATS Resume
* Cover Letter
* LinkedIn Summary

Export

* PDF
* DOCX

---

## AI Email

Generate

* Professional emails
* Replies
* Grammar correction
* Summaries

---

## AI Meeting Assistant

* Record meetings
* Transcribe
* Summarize
* Generate action items
* Export notes

---

## Productivity

Include

* Notes
* To-Do
* Calendar
* Reminder
* Habit tracker

---

## Android Features

Implement

* Offline-first architecture
* Paging 3
* WorkManager sync
* Background downloads
* Push notifications
* Deep links
* Dynamic themes
* Material You
* Tablet layouts
* Foldable support
* Wear OS companion (optional)

---

## Security

Implement

* OAuth
* JWT
* Refresh token
* Biometric authentication
* Certificate pinning
* SQL injection protection
* API key encryption
* EncryptedSharedPreferences
* Root detection
* Screenshot protection for sensitive screens

---

## Admin Dashboard

Generate a web dashboard with:

* Active users
* AI usage
* Token consumption
* Error monitoring
* Analytics
* Feedback management

---

## CI/CD

Generate

* GitHub Actions
* Unit testing
* UI testing
* ktlint
* Detekt
* JaCoCo
* Release pipeline
* Fastlane

---

## Monitoring

Integrate

* Firebase Analytics
* Crashlytics
* Performance Monitoring
* Logging
* Remote Config

---

## Project Structure

Generate a complete multi-module project with:

* app
* core-ui
* core-network
* core-database
* core-ai
* core-security
* core-common
* feature-auth
* feature-chat
* feature-rag
* feature-pdf
* feature-camera
* feature-code
* feature-voice
* feature-settings
* feature-profile
* feature-history
* feature-notes
* feature-meeting
* feature-resume
* feature-email
* domain
* data

---

## Deliverables

Generate:

* Complete Android Studio project
* FastAPI backend
* Database schema
* REST API documentation
* Architecture diagrams
* Sequence diagrams
* ER diagrams
* Complete UI in Jetpack Compose
* Unit tests
* UI tests
* README
* API documentation
* Docker setup
* Deployment guide
* Sample data
* Postman collection

Follow Kotlin coding conventions, Android best practices, and enterprise software design patterns. The project should be production-ready, scalable, maintainable, secure, and suitable for deployment to the Google Play Store and as a flagship GitHub portfolio project.


# Educational Mode (Highly Detailed)

Generate the entire project in **Educational Mode**.

Assume I am learning AI Engineering and advanced Android development. Every generated file should teach me why it exists, how it works, and when to use it.

## File Header

At the beginning of **every file**, include a documentation block with:

* Purpose of the file
* Responsibilities
* Where it fits in Clean Architecture
* Dependencies
* Who calls this file
* Which files it depends on
* Real-world use case
* Common interview questions
* Best practices
* Common mistakes
* Performance considerations
* Security considerations (where applicable)

---

## Class Documentation

Before every class, explain:

* Why this class exists
* Why it is needed
* Responsibilities
* Design Pattern used
* SOLID principles applied
* Thread safety considerations
* Memory considerations
* Lifecycle considerations (Android)
* Real-world banking/app example

---

## Function Documentation

For **every function**, include detailed comments explaining:

* Purpose
* Input parameters
* Return value
* Business logic
* Why this implementation was chosen
* Time complexity (Big O)
* Space complexity
* Coroutine behavior (if applicable)
* Thread on which it runs
* Error handling
* Edge cases
* Example input
* Example output
* Interview explanation

Example:

```kotlin
/**
 * Purpose:
 * Retrieves all chat messages from the repository.
 *
 * Why:
 * Separates business logic from the ViewModel.
 *
 * Parameters:
 * None
 *
 * Returns:
 * Flow<List<ChatMessage>>
 *
 * Complexity:
 * Time: O(n)
 * Space: O(n)
 *
 * Thread:
 * Runs on Dispatchers.IO.
 *
 * Example:
 * Input:
 * User opens chat history.
 *
 * Output:
 * List of previous conversations.
 *
 * Interview:
 * Why is Flow preferred over LiveData?
 */
```

---

## Line-by-Line Comments

For complex functions, explain nearly every significant line:

```kotlin
// Launch a coroutine tied to the ViewModel lifecycle
viewModelScope.launch {

    // Emit loading state before making the API call
    _uiState.value = UiState.Loading

    // Execute the use case on the appropriate dispatcher
    val result = getMessagesUseCase()

    // Update the UI with the latest data
    _uiState.value = UiState.Success(result)
}
```

Avoid commenting trivial syntax like `val x = 1`; focus on intent and reasoning.

---

## Explain Design Decisions

After every major implementation, explain:

* Why this approach was chosen
* Alternative approaches
* Pros and cons
* Scalability impact
* Maintainability impact
* Testability impact

---

## Dependency Injection

For every Hilt module, explain:

* Why dependency injection is used
* Singleton vs Factory vs Scoped objects
* Why the chosen scope is appropriate
* Lifecycle of injected objects

---

## Architecture Explanations

For each layer (UI, Domain, Data):

* Responsibilities
* Allowed dependencies
* Forbidden dependencies
* Data flow
* Why Clean Architecture is beneficial

---

## Coroutines and Flow

For each coroutine/Flow example, explain:

* Why Coroutine instead of Thread
* Why Flow instead of LiveData
* Cold vs Hot Flow
* StateFlow vs SharedFlow
* Dispatcher selection
* Structured concurrency
* Cancellation behavior
* Common pitfalls

---

## Jetpack Compose

For every composable, explain:

* Recomposition
* State management
* remember vs rememberSaveable
* Hoisting state
* Side effects (LaunchedEffect, DisposableEffect, SideEffect)
* Performance optimization
* Accessibility

---

## Backend (FastAPI)

For each endpoint:

* Purpose
* Request schema
* Response schema
* Authentication
* Validation
* Error handling
* Database interaction
* AI model interaction

---

## AI Explanations

For every AI feature, explain:

* Prompt construction
* Token usage
* Context window
* Streaming responses
* Temperature
* Top-P
* Embeddings
* Vector search
* RAG pipeline
* Hallucination prevention

---

## Security

Explain every security implementation:

* Why API keys are protected
* JWT authentication flow
* OAuth flow
* Certificate pinning
* Encrypted storage
* SQL injection prevention
* Prompt injection mitigation
* Secure coding practices

---

## Testing

For every test:

* What is being tested
* Why it is important
* Arrange–Act–Assert pattern
* Mocking strategy
* Expected result
* Edge cases

---

## Learning Notes

At the end of every file, include:

### Key Concepts Learned

* Topic 1
* Topic 2
* Topic 3

### Interview Questions

* 5–10 questions related to this file

### Common Mistakes

* Frequent implementation errors
* How to avoid them

### Further Reading

* Official Android documentation
* Kotlin documentation
* Google architecture guides
* AI/LLM documentation

### Exercises

* Small coding tasks to reinforce learning
* Suggested enhancements
* Refactoring ideas

---

## Code Quality Rules

* Write production-ready code.
* Follow Kotlin coding conventions.
* Keep functions small and focused.
* Prefer immutable data.
* Use meaningful names.
* Add comprehensive documentation without cluttering simple code.
* Include diagrams (ASCII or Mermaid) where useful to explain architecture or data flow.


Act as a Staff Android Engineer, AI Engineer, and Software Architect.

Build a production-ready Android application named "AI Assistant" using modern Android development best practices.

## Tech Stack

Language:
- Kotlin

UI:
- Jetpack Compose
- Material Design 3
- Navigation Compose

Architecture:
- Clean Architecture
- MVVM
- Repository Pattern
- Use Cases
- SOLID Principles
- Multi-module architecture

Dependency Injection:
- Hilt

Asynchronous:
- Kotlin Coroutines
- StateFlow
- SharedFlow

Networking:
- Retrofit
- OkHttp
- Kotlin Serialization

Database:
- Room
- DataStore

Image Loading:
- Coil

Logging:
- Timber

Testing:
- JUnit
- Mockito
- MockK
- Turbine
- Espresso
- Compose UI Tests

CI/CD:
- GitHub Actions
- ktlint
- Detekt

## Modules

app
core
core-ui
core-network
core-database
core-common
feature-auth
feature-chat
feature-pdf
feature-voice
feature-camera
feature-settings
feature-profile
feature-history
domain
data

## Features

Authentication
- Google Sign In
- Email Login

AI Chat
- Streaming responses
- Markdown rendering
- Code blocks
- Chat history
- Regenerate response
- Copy response
- Share response

Voice Assistant
- Speech to Text
- Text to Speech
- Voice conversation

PDF Assistant
- Upload PDF
- Summarize PDF
- Ask questions using RAG

Image Assistant
- Capture image
- OCR
- AI image analysis

Notes
- AI summary
- AI rewrite
- Save locally

History
- Search
- Delete
- Pin chats

Settings
- Dark Mode
- Notifications
- Language
- AI Provider

## Backend

Generate a FastAPI backend.

Include

/chat
/upload
/summarize
/ask
/history

Use

LangChain
ChromaDB
OpenAI or Gemini

## Android Requirements

Implement

Repository Pattern
UseCases
UI State
Error Handling
Offline Cache
Loading States
Retry
Pagination
Search

## Security

EncryptedSharedPreferences
Certificate Pinning
API Key Management
Biometric Login

## Folder Structure

Follow feature-based Clean Architecture.

Generate

Gradle files
Version Catalog
Hilt modules
Navigation
Room
Retrofit
ViewModels
Compose Screens
Repositories
UseCases
Tests

## Deliverables

- Complete Android Studio project
- Production-ready architecture
- Documentation
- README
- Architecture diagram
- Sequence diagrams
- Unit tests
- UI tests
- GitHub Actions workflow


Android AI Assistant – Figma AI Prompt

Prompt:

Design a modern Android mobile application called AI Assistant using Material Design 3 with a clean, premium, and minimal interface.

The app should feel similar to ChatGPT, Gemini, and Claude, with smooth animations, rounded cards, soft shadows, and excellent spacing.

Create the UI in 375 × 812 px (Android) with Auto Layout and reusable components.

Design System
Material Design 3
Light and Dark themes
8pt spacing system
Rounded corners (16–24dp)
Modern typography
Consistent iconography
Accessibility-compliant contrast
Reusable components and variants
Screens to Design
1. Splash Screen
AI Assistant logo
Animated gradient background
Loading indicator
2. Onboarding (3 screens)
Welcome
AI capabilities
Privacy & security
Get Started button
Skip option
3. Login / Sign Up
Google Sign-In
Email & Password
Forgot Password
Create Account
4. Home Dashboard
Greeting ("Hello, Firoj 👋")
Search bar
Recent conversations
Quick action cards:
Chat
Voice
PDF
Camera
Image
Translate
Code Assistant
Notes
Bottom navigation:
Home
Chats
AI Tools
Profile
5. AI Chat Screen
Streaming AI responses
User and AI message bubbles
Markdown support
Code blocks
Copy, regenerate, share, like/dislike actions
Typing indicator
Suggested prompts
Text input with:
Attachment
Camera
Voice
Send
6. Voice Assistant
Large animated microphone
Sound wave visualization
Voice transcript
AI speaking animation
Start/Stop controls
7. PDF Assistant
Upload PDF
List of uploaded documents
PDF preview
AI-generated summary
Ask questions about the document
Highlight relevant pages
8. Image Assistant
Camera capture
Gallery picker
Image preview
OCR text extraction
AI description
Analyze button
9. Code Assistant
Syntax-highlighted editor
Language selector
Explain code
Fix bugs
Optimize
Generate tests
Copy and share actions
10. AI Tools

Display cards for:

Summarizer
Translator
Grammar Checker
Resume Builder
Email Writer
Meeting Notes
To-Do Generator
Code Generator
11. Chat History
Search conversations
Filters
Pin/Delete/Rename chats
Date grouping
12. Profile
Avatar
Usage statistics
Subscription status
Settings
Help & Support
Logout
13. Settings
Theme (Light/Dark/System)
Language
Notifications
Privacy
API Key
Voice settings
About
Components

Create reusable components for:

Buttons
Text fields
Cards
Bottom navigation
Top app bar
Chat bubbles
AI response cards
Prompt chips
Floating Action Button
Progress indicators
Loading states
Empty states
Error states
Prototype

Include interactions for:

Smooth page transitions
Chat message animation
Voice waveform animation
Loading skeletons
Bottom sheet actions
Floating action button
Micro-interactions for taps and long presses
Deliverables
25–30 high-fidelity screens
Component library
Color styles
Text styles
Icon library
Auto Layout
Design tokens
Light & Dark themes
Clickable prototype
Developer-ready specifications for Android (Jetpack Compose)

This prompt should generate a complete, production-quality UI that you can directly implement using Jetpack Compose + Material 3.


# Kiro Command – Enterprise AI Backend

Build the complete backend for the **Android AI Assistant** project.

Act as a **Principal Backend Architect**, **Senior Python Engineer**, **AI Engineer**, **DevOps Engineer**, **Cloud Architect**, and **Technical Mentor**.

## Primary Objective

Design and implement a **production-ready**, **enterprise-scale**, **cloud-native AI backend** that supports Android, Web, and future clients.

The backend must be modular, secure, scalable, observable, and educational.

Do not generate placeholders or incomplete implementations unless explicitly requested.

---

# Architecture

Use **Modular Monolith** architecture first, with clear module boundaries so each module can later be extracted into its own microservice without major refactoring.

Document all architectural decisions and explain trade-offs.

---

# Technology Stack

Language:

* Python 3.13

Framework:

* FastAPI

Validation:

* Pydantic v2

ORM:

* SQLAlchemy 2.0

Database:

* PostgreSQL

Vector Database:

* ChromaDB

Cache:

* Redis

Background Jobs:

* Celery + Redis

Storage:

* MinIO (S3 compatible)

Authentication:

* OAuth2
* JWT
* Refresh Tokens
* Google Login

AI Framework:

* LangChain
* LlamaIndex

LLM Providers:

* OpenAI
* Gemini
* Claude
* Ollama
* Llama
* Mistral

Monitoring:

* Prometheus
* Grafana
* Loki

Logging:

* Loguru

Deployment:

* Docker
* Docker Compose

Reverse Proxy:

* Nginx

Testing:

* Pytest
* Coverage
* Integration Tests

Documentation:

* OpenAPI
* Swagger
* ReDoc

---

# Folder Structure

Generate the following project structure:

backend/
app/
api/
auth/
chat/
ai/
rag/
embeddings/
vision/
speech/
email/
resume/
memory/
analytics/
notifications/
websocket/
middleware/
security/
repositories/
services/
models/
schemas/
database/
config/
prompts/
workers/
utils/
tests/

---

# Features

Implement:

* Authentication
* User Profile
* AI Chat
* Streaming Responses
* Conversation History
* AI Memory
* PDF Upload
* RAG
* OCR
* Image Understanding
* Voice Processing
* Resume Builder
* Email Generator
* Meeting Summarizer
* Notes
* Search
* Analytics
* Admin APIs
* Notification APIs

---

# AI Layer

Create an AI abstraction layer.

Support multiple providers.

Allow runtime provider switching.

Implement:

* Prompt templates
* Prompt versioning
* Context management
* Conversation memory
* Token counting
* Cost tracking
* Retry strategies
* Fallback models
* Rate limiting
* Safety checks
* Prompt injection protection

---

# RAG Pipeline

Implement complete RAG:

Document Upload

↓

OCR

↓

Chunking

↓

Embeddings

↓

Vector Storage

↓

Semantic Search

↓

Context Assembly

↓

LLM

↓

Citations

Explain each stage and why it exists.

---

# WebSocket

Implement streaming responses using WebSockets.

Support:

* Live token streaming
* Typing indicators
* Reconnect logic
* Heartbeats
* Error recovery

---

# Security

Implement:

* JWT
* OAuth2
* Refresh Tokens
* RBAC
* API Keys
* Secret Management
* Certificate Validation
* SQL Injection Protection
* XSS Protection
* CSRF Protection (where applicable)
* Prompt Injection Protection
* Audit Logging

Explain every security decision.

---

# Database

Design production-grade schemas.

Include:

Users

Chats

Messages

Documents

Embeddings

Memories

Settings

Notifications

Analytics

API Keys

Generate ER diagrams.

---

# API Standards

Follow REST best practices.

Implement:

* Versioning
* Pagination
* Filtering
* Sorting
* Search
* Validation
* Standard error responses
* Correlation IDs
* Idempotency where appropriate

---

# DevOps

Generate:

Docker

Docker Compose

GitHub Actions

Environment files

Health checks

Readiness probes

Logging

Metrics

Tracing

---

# Testing

Generate:

Unit Tests

Integration Tests

API Tests

Mock AI Providers

Test Fixtures

Coverage Reports

Explain each test.

---

# Educational Mode

Every file must teach the reader.

Include:

Purpose

Responsibilities

Architecture

Business logic

Performance

Security

Interview questions

Common mistakes

Exercises

Best practices

Alternative approaches

Use detailed comments for non-obvious code and architectural decisions.

---

# Documentation

Generate:

README

Architecture Guide

API Documentation

Deployment Guide

Security Guide

Troubleshooting Guide

Performance Guide

Sequence Diagrams

Mermaid Architecture Diagrams

ER Diagrams

Request Flow Diagrams

---

# Code Quality

Follow:

PEP 8

SOLID

DRY

KISS

YAGNI

Clean Code

Dependency Injection

Repository Pattern

Service Layer Pattern

Domain-Driven Design concepts where appropriate

Use async/await correctly and avoid blocking operations.

---

# Delivery Strategy

Generate the project incrementally.

Start with:

1. Project structure
2. Configuration
3. Database
4. Authentication
5. AI Chat
6. RAG
7. AI Memory
8. Voice
9. Vision
10. Admin
11. Testing
12. Deployment

After each phase:

* Explain what was built.
* Explain how it works.
* Explain why it was designed this way.
* Show how to test it.
* Identify common pitfalls.
* Suggest next improvements.

Do not proceed to the next phase until the current phase is complete, documented, and verified.


AI Assistant App — Complete
13 fully interactive screens inside a pixel-perfect 375×812 Android phone frame:

Screen	Features
Splash	Animated gradient, logo, pulsing dots → auto-advances
Onboarding	3 slides with illustrations, progress dots, skip/next/get started
Login / Sign Up	Google Sign-In, email/password, forgot password toggle
Home Dashboard	Greeting, search bar, promo banner, 8 quick action cards, recent chats
Chat	Message bubbles, markdown + code block rendering, typing indicator, action buttons (copy/regenerate/like), suggestion chips
Voice	Animated mic with pulse rings, sound wave bars, transcript + AI response states
PDF	Upload button, document list, PDF preview, AI summary, Q&A input
Image	Gallery picker, sample images, animated analysis steps, object/text/description results
Code	Syntax editor with dark theme, language picker, 4 AI actions (Explain/Fix/Optimize/Tests)
AI Tools	12-tool grid with featured card, search bar
Chat History	Search, pin filter, date groups, context menu (pin/rename/delete)
Profile	Avatar header, 4 usage stats, upgrade CTA, full menu
Settings	Dark mode toggle, language, notifications, voice, privacy, API key input