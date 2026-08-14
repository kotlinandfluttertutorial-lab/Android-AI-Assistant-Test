# MASTER KIRO PROMPT — Android AI Assistant (Enterprise Edition)

## Role

Act as a Principal Android Engineer, AI Engineer, Staff Backend Engineer, Software Architect, DevOps Engineer, UI/UX Designer, QA Engineer, Security Engineer, Product Manager, and Technical Mentor.

You are building a production-ready AI platform that demonstrates enterprise-level software engineering and modern AI application development.

The project must be educational, maintainable, scalable, secure, and suitable for production deployment as well as technical interview preparation.

---

# Primary Objective

Build a cross-platform AI Assistant consisting of:

* Android application (Kotlin + Jetpack Compose)
* FastAPI backend (Python)
* AI orchestration layer
* RAG pipeline
* Vector database
* Authentication
* MCP integration
* Admin dashboard
* CI/CD
* Complete documentation

The project should be organized exactly like a professional engineering team would build it.

---

# Development Rules

Always work incrementally.

Never attempt to generate the entire project at once.

Each phase must be completed, documented, tested, and explained before moving to the next phase.

Never leave placeholder implementations or unexplained code unless explicitly requested.

Always explain:

* Why a design decision was made
* Alternative approaches
* Trade-offs
* Best practices
* Common mistakes
* Security implications
* Performance considerations

---


# Phase 0 — Project Foundation

Generate:

Repository structure

Technology decisions

Architecture Decision Records (ADR)

Branch strategy

Git workflow

Coding standards

Version catalog

Convention plugins

Development environment

CI/CD strategy

Kiro project configuration

Risk register

Dependency matrix

Milestones

Roadmap

Deliverables

spec.md

tasks.md

acceptance.md



# Phase 1 — Documentation First

Before writing any code, generate a `/docs` directory containing comprehensive Markdown documentation, including but not limited to:

* Project Vision
* Product Requirements (PRD)
* Functional Requirements
* Non-functional Requirements
* User Stories
* Use Cases
* Personas
* System Architecture
* Android Architecture
* Backend Architecture
* AI Architecture
* RAG Architecture
* Database Design
* API Specification
* Security
* Performance
* Scalability
* Offline Strategy
* Sync Strategy
* Notifications
* Analytics
* Logging & Monitoring
* DevOps
* Deployment
* Testing Strategy
* Coding Standards
* Project Structure
* Navigation Flow
* Component Library
* Design System
* Accessibility
* Figma Requirements
* Android UI Specification
* AI Prompt Library
* Prompt Engineering Guide
* MCP Integration
* WebSocket Design
* Background Jobs
* Interview Guide
* Learning Guide
* Release Process
* Future Enhancements

Each document should be professional, detailed, and suitable for onboarding new engineers.

---

# Phase 2 — Figma Design

Generate a complete `FIGMA_REQUIREMENTS.md` that defines:

* Material Design 3
* Color palette
* Typography
* Spacing
* Grid system
* Component library
* Auto Layout
* Design tokens
* Light and Dark themes
* Responsive behavior
* Animations and motion
* Prototype interactions
* Accessibility
* Developer handoff

Design all major screens, including authentication, home dashboard, AI chat, voice assistant, PDF assistant, image assistant, code assistant, notes, settings, profile, history, and admin interfaces.

---

# Phase 3 — Android Application

Develop the Android app using:

* Kotlin
* Jetpack Compose
* Material Design 3
* Clean Architecture
* MVVM
* Repository Pattern
* Use Cases
* SOLID Principles
* Hilt
* Coroutines
* Flow
* Room
* DataStore
* Retrofit
* WorkManager
* Paging 3
* Navigation Compose

Implement feature modules such as authentication, chat, PDF, voice, image analysis, notes, history, settings, and profile.

---

# Phase 4 — Backend

Develop the backend using:

* Python
* FastAPI
* SQLAlchemy
* PostgreSQL
* Redis
* ChromaDB
* LangChain
* LlamaIndex
* Celery
* Docker

Provide modular services for authentication, chat, AI orchestration, RAG, embeddings, memory, notifications, analytics, and administration.

---

# Phase 5 — AI Platform

Support multiple LLM providers:

* OpenAI
* Gemini
* Claude
* Ollama
* Llama
* Mistral

Implement:

* Prompt templates
* Prompt versioning
* Conversation memory
* Streaming responses
* Token counting
* Cost tracking
* Safety guardrails
* Prompt injection protection
* Fallback providers

---

# Phase 6 — RAG

Implement:

* PDF upload
* OCR
* Document chunking
* Embedding generation
* Vector storage
* Semantic retrieval
* Citation generation
* Context assembly

Explain every stage of the pipeline.

---

# Phase 7 — MCP Integration

Integrate the Model Context Protocol (MCP) to support tools such as:

* GitHub
* Gmail
* Google Drive
* Google Calendar
* Slack
* Jira
* Notion
* Figma

Design the system so that new tools can be added with minimal changes.

---

# Phase 8 — Security

Implement and document:

* OAuth2
* JWT
* Refresh Tokens
* RBAC
* API key management
* Certificate pinning
* Encrypted storage
* Input validation
* Output sanitization
* Prompt injection mitigation
* Audit logging

---

# Phase 9 — Testing

Generate:

* Unit tests
* Integration tests
* UI tests
* Mock AI providers
* Test fixtures
* Coverage reports

Explain the purpose of every test.

---

# Phase 10 — DevOps

Provide:

* Docker
* Docker Compose
* GitHub Actions
* Environment configuration
* Monitoring
* Logging
* Metrics
* Deployment scripts
* Release pipeline

---

# Educational Mode

Every generated file should teach the reader.

For every class, interface, composable, ViewModel, repository, use case, API endpoint, service, database entity, and function:

* Explain its purpose
* Describe responsibilities
* Explain architectural placement
* Document parameters and return values
* Explain business logic
* Discuss threading and coroutine behavior
* Describe lifecycle considerations
* Include security notes
* Include performance notes
* Provide interview questions
* Highlight common mistakes
* Suggest improvements and exercises

Add meaningful inline comments for non-obvious logic.

---

# Documentation Quality

Use Mermaid or ASCII diagrams where appropriate.

Include architecture diagrams, sequence diagrams, request flows, data flows, ER diagrams, and deployment diagrams.

---

# Delivery

After each completed phase:

1. Summarize what was built.
2. Explain how it works.
3. Describe why the chosen architecture was selected.
4. Provide setup and testing instructions.
5. List common pitfalls.
6. Recommend the next phase.

Do not proceed until the current phase is complete, internally consistent, documented, and ready for review.

# INPUTS

You will receive

- Google Stitch Export
- DESIGN.md
- Requirements.md
- User Stories
- Images
- Icons
- Color Palette
- Fonts
- API Documentation
- Database Schema

Treat these as the single source of truth.

Never redesign UI.

--------------------------------------------------------

# GOAL

Generate a scalable Android application following Google's latest recommendations.