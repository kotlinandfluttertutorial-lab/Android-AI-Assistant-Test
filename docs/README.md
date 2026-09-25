# Documentation — Android AI Assistant (Enterprise Edition)

All documentation is organized into six numbered sections. Browse the section that matches
your goal, or use the full index below.

---

## Sections

| # | Folder | Contents |
|---|---|---|
| 1 | [01-project](#01-project) | Vision, PRD, requirements, developer TODO |
| 2 | [02-architecture](#02-architecture) | System, Android, backend, AI, RAG, database design |
| 3 | [03-guides](#03-guides) | Setup, coding standards, security, testing, deployment, performance, DevOps |
| 4 | [04-ai-features](#04-ai-features) | On-device RAG, AIOps, RAG pipeline, observability, dashboards |
| 5 | [05-operations](#05-operations) | Environments, CI/CD, API spec, UI, caching, accessibility, Cloud Run, MCP |
| 6 | [06-learning](#06-learning) | Learning guides, backend deep-dives, phase walkthroughs (16–20) |

---

## 01-project

Product definition and planning documents.

| File | Description |
|---|---|
| [PROJECT_VISION.md](01-project/PROJECT_VISION.md) | Vision statement and strategic goals |
| [PRD.md](01-project/PRD.md) | Product Requirements Document (v1.0) |
| [REQUIREMENTS.md](01-project/REQUIREMENTS.md) | Functional and non-functional requirements |
| [DEVELOPER_TODO.md](01-project/DEVELOPER_TODO.md) | Active developer task list |

---

## 02-architecture

Architecture decision records and system design documents.

| File | Description |
|---|---|
| [SYSTEM_ARCHITECTURE.md](02-architecture/SYSTEM_ARCHITECTURE.md) | Five-layer system overview |
| [ANDROID_ARCHITECTURE.md](02-architecture/ANDROID_ARCHITECTURE.md) | Clean Architecture, MVVM, module graph |
| [BACKEND_ARCHITECTURE.md](02-architecture/BACKEND_ARCHITECTURE.md) | FastAPI modular monolith design |
| [AI_ARCHITECTURE.md](02-architecture/AI_ARCHITECTURE.md) | Provider-agnostic AIOrchestrator layer |
| [RAG_ARCHITECTURE.md](02-architecture/RAG_ARCHITECTURE.md) | Cloud RAG pipeline (ChromaDB + backend) |
| [DATABASE_DESIGN.md](02-architecture/DATABASE_DESIGN.md) | PostgreSQL + Room schema design |

---

## 03-guides

Hands-on developer guides for building, testing, and shipping.

| File | Description |
|---|---|
| [SETUP_GUIDE.md](03-guides/SETUP_GUIDE.md) | Local dev environment setup |
| [CODING_STANDARDS.md](03-guides/CODING_STANDARDS.md) | Kotlin/Python style, Educational Headers, lint rules |
| [SECURITY_GUIDE.md](03-guides/SECURITY_GUIDE.md) | Security controls across every layer |
| [TESTING_STRATEGY.md](03-guides/TESTING_STRATEGY.md) | Coverage requirements, unit/property/E2E strategy |
| [DEPLOYMENT_GUIDE.md](03-guides/DEPLOYMENT_GUIDE.md) | Production deployment checklist |
| [PERFORMANCE_GUIDE.md](03-guides/PERFORMANCE_GUIDE.md) | Performance targets and measurement conditions |
| [DEVOPS_GUIDE.md](03-guides/DEVOPS_GUIDE.md) | Docker Compose, scripts, local stack |
| [NOTEBOOKLM_PROMPTS.md](03-guides/NOTEBOOKLM_PROMPTS.md) | Curated NotebookLM study prompts |

---

## 04-ai-features

Feature-level documentation for all AI capabilities.

| File | Description |
|---|---|
| [ON_DEVICE_RAG.md](04-ai-features/ON_DEVICE_RAG.md) | **On-device RAG** — portfolio doc with architecture, pipeline, demo walkthrough |
| [RAG_GUIDE.md](04-ai-features/RAG_GUIDE.md) | Cloud RAG pipeline guide |
| [AI_DEVOPS_ASSISTANT.md](04-ai-features/AI_DEVOPS_ASSISTANT.md) | AI DevOps Assistant overview |
| [DEVOPS_ASSISTANT_GUIDE.md](04-ai-features/DEVOPS_ASSISTANT_GUIDE.md) | DevOps assistant usage guide |
| [AIOPS_GUIDE.md](04-ai-features/AIOPS_GUIDE.md) | AIOps feature guide |
| [ANOMALY_DETECTION_GUIDE.md](04-ai-features/ANOMALY_DETECTION_GUIDE.md) | Anomaly detection system |
| [ERROR_ANALYSIS_GUIDE.md](04-ai-features/ERROR_ANALYSIS_GUIDE.md) | Error analysis and RCA |
| [RCA_GUIDE.md](04-ai-features/RCA_GUIDE.md) | Root cause analysis guide |
| [DASHBOARD_GUIDE.md](04-ai-features/DASHBOARD_GUIDE.md) | Observability dashboard guide |
| [OBSERVABILITY_GUIDE.md](04-ai-features/OBSERVABILITY_GUIDE.md) | Full observability setup |

---

## 05-operations

Environment setup, API reference, UI documentation, and deployment infrastructure.

**Environments & CI/CD**

| File | Description |
|---|---|
| [ENVIRONMENTS.md](05-operations/ENVIRONMENTS.md) | Local / Stage / Production architecture |
| [ENVIRONMENT_CONFIGURATION.md](05-operations/ENVIRONMENT_CONFIGURATION.md) | Stage and Production env vars |
| [RUNNING_ENVIRONMENTS.md](05-operations/RUNNING_ENVIRONMENTS.md) | How to run each environment |
| [CICD_AND_SECRETS.md](05-operations/CICD_AND_SECRETS.md) | CI/CD pipeline and secret management |
| [CLOUD_RUN_DEPLOYMENT.md](05-operations/CLOUD_RUN_DEPLOYMENT.md) | Google Cloud Run deployment |
| [MCP_INTEGRATION.md](05-operations/MCP_INTEGRATION.md) | Model Context Protocol integration |

**API**

| File | Description |
|---|---|
| [API_SPECIFICATION.md](05-operations/API_SPECIFICATION.md) | Full REST API spec (base URL, auth, endpoints) |

**UI & Frontend**

| File | Description |
|---|---|
| [UI_ARCHITECTURE.md](05-operations/UI_ARCHITECTURE.md) | Compose UI architecture and navigation |
| [UI_DESIGN_SYSTEM.md](05-operations/UI_DESIGN_SYSTEM.md) | Design tokens, typography, color |
| [UI_TESTING.md](05-operations/UI_TESTING.md) | UI and screenshot testing strategy |
| [ACCESSIBILITY.md](05-operations/ACCESSIBILITY.md) | WCAG compliance and a11y implementation |
| [GEMMA_UI.md](05-operations/GEMMA_UI.md) | Gemma on-device UI patterns |
| [RAG_UI.md](05-operations/RAG_UI.md) | RAG chat and document UI patterns |
| [ON_DEVICE_OFFLINE_NAVIGATION.md](05-operations/ON_DEVICE_OFFLINE_NAVIGATION.md) | Offline navigation architecture |
| [CACHING_STRATEGY.md](05-operations/CACHING_STRATEGY.md) | Client-side caching strategy |

---

## 06-learning

Study guides, code deep-dives, and phase-by-phase walkthroughs.

**Guides**

| File | Description |
|---|---|
| [LEARNING_GUIDE.md](06-learning/LEARNING_GUIDE.md) | Master guide covering all 20 DevOps phases |
| [GENAI_LEARNING_GUIDE.md](06-learning/GENAI_LEARNING_GUIDE.md) | Generative AI concepts and patterns |
| [BACKEND_LEARNING_GUIDE.md](06-learning/BACKEND_LEARNING_GUIDE.md) | Backend architecture study guide |
| [BACKEND_CODE_EXPLAINED.md](06-learning/BACKEND_CODE_EXPLAINED.md) | Line-by-line backend code walkthroughs |
| [BACKEND_7DAY_PLAN.md](06-learning/BACKEND_7DAY_PLAN.md) | 7-day backend onboarding plan |

**Phase Walkthroughs (16–20)**

| File | Description |
|---|---|
| [PHASE16_SECURITY_GUIDE.md](06-learning/PHASE16_SECURITY_GUIDE.md) | Phase 16: Security hardening |
| [PHASE17_TESTING_GUIDE.md](06-learning/PHASE17_TESTING_GUIDE.md) | Phase 17: Testing strategy |
| [PHASE18_CICD_GUIDE.md](06-learning/PHASE18_CICD_GUIDE.md) | Phase 18: CI/CD pipeline |
| [PHASE19_JENKINS_GUIDE.md](06-learning/PHASE19_JENKINS_GUIDE.md) | Phase 19: Jenkins setup |
| [PHASE20_KUBERNETES_GUIDE.md](06-learning/PHASE20_KUBERNETES_GUIDE.md) | Phase 20: Kubernetes deployment |
