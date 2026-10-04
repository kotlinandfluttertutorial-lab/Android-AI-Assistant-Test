# Local Setup Guide

> **For:** Developers who want to run and develop MCP, Agent, and RAG locally  
> **Updated:** 2026-10-03  
> **Time to working stack:** ~10 minutes on a fast machine

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [First-time setup](#2-first-time-setup)
3. [Start the stack](#3-start-the-stack)
4. [Verify everything works](#4-verify-everything-works)
5. [Run the automated smoke test](#5-run-the-automated-smoke-test)
6. [Development workflow](#6-development-workflow)
7. [Testing MCP tools locally](#7-testing-mcp-tools-locally)
8. [Testing RAG locally](#8-testing-rag-locally)
9. [Testing Agent execution locally](#9-testing-agent-execution-locally)
10. [Environment variable reference](#10-environment-variable-reference)
11. [Troubleshooting](#11-troubleshooting)

---

## 1. Prerequisites

| Tool | Version | Install |
|---|---|---|
| Docker Desktop | 24.x | https://www.docker.com/products/docker-desktop/ |
| Docker Compose v2 | bundled | — |
| Python | 3.11+ | Only needed to run tests / scripts outside Docker |
| Git | any | — |

> **Windows note:** Use PowerShell 7. Docker Desktop must be running before `docker compose up`.

---

## 2. First-time setup

```powershell
# Clone / navigate to the repository root
cd Android-AI-Assistant-Test

# Copy the Docker env template
Copy-Item backend\.env.docker.example backend\.env.docker

# Generate the two REQUIRED secrets — run each line and paste the output into .env.docker
python -c "import secrets; print('SECRET_KEY=' + secrets.token_hex(32))"
python -c "import base64, os; print('AES_ENCRYPTION_KEY=' + base64.b64encode(os.urandom(32)).decode())"
```

Open `backend\.env.docker` and replace the two `REPLACE_ME_…` placeholders with the generated values.

**Optional** — add a Gemini API key for live LLM responses:
```dotenv
GEMINI_API_KEY=your_key_here   # https://aistudio.google.com/app/apikey
```

All non-LLM features (document upload, RAG retrieval, MCP tool dispatch, Agent routing) work without a key. Only the final answer generation step requires an LLM.

---

## 3. Start the stack

```powershell
# Start all 6 services
docker compose up -d

# Watch logs until all services are healthy (~60 s on first boot)
docker compose logs -f

# Run database migrations (once after first `up -d`, and after every schema change)
docker compose exec backend alembic upgrade head
```

Services started:
- **postgres** — PostgreSQL 16 + pgvector
- **redis** — Redis 7
- **chromadb** — ChromaDB 0.5.20 vector store
- **minio** — S3-compatible object storage
- **backend** — FastAPI (hot-reload, port 8000)
- **celery_worker** — Celery worker (ingestion queue)

### Dev mode (adds Flower + Mailhog)

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

Additional services:
- **Flower** — Celery task monitoring at http://localhost:5555
- **Mailhog** — SMTP catcher at http://localhost:8025

---

## 4. Verify everything works

```powershell
# All containers should show "healthy"
docker compose ps

# FastAPI
curl http://localhost:8000/health    # → {"status":"ok"}
curl http://localhost:8000/ready     # → {"status":"ready","dependencies":{...}}

# PostgreSQL
docker compose exec postgres pg_isready -U aiassistant

# Redis
docker compose exec redis redis-cli ping    # → PONG

# ChromaDB
curl http://localhost:8001/api/v1/heartbeat   # → {"nanosecond heartbeat": N}

# Celery worker
docker compose exec celery_worker python -m celery -A app.workers.celery_app inspect ping
```

---

## 5. Run the automated smoke test

The smoke test verifies every acceptance criterion in one command:

```powershell
docker compose exec backend python scripts/local_smoke_test.py
```

Expected output:
```
Android AI Assistant — Local Smoke Test
Base URL: http://localhost:8000

──────────────────────────────────────────────────────────
  1. FastAPI
──────────────────────────────────────────────────────────
  ✓ PASS  GET /health  (status=ok)
  ✓ PASS  GET /ready   (status=ready  deps={...})
...
════════════════════════════════════════════════════════════
Summary  10 passed  0 skipped  0 failed  / 10 total
════════════════════════════════════════════════════════════
```

The smoke test automatically:
- Registers a throw-away test user and logs in
- Uploads a small document and polls ingestion status
- Submits a RAG question
- Executes an agent run
- Calls the MCP demo echo tool via the agent API
- Verifies required environment variables are set
- Cleans up the test document

---

## 6. Development workflow

### Code hot-reload

`./backend/app` is bind-mounted into the container. Changes to `.py` files trigger automatic uvicorn reload. No rebuild needed.

Changes to `requirements.txt` or `Dockerfile` require a rebuild:
```powershell
docker compose build backend
docker compose up -d backend
```

### Running unit tests

```powershell
# In a local venv (faster)
cd backend
venv311\Scripts\Activate.ps1
pytest tests/unit/ -q

# Or inside the container
docker compose exec backend pytest tests/unit/ -q
```

### Running security tests only

```powershell
docker compose exec backend pytest tests/security/ -v
```

### Applying a new database migration

```powershell
# Generate (after changing a SQLAlchemy model)
docker compose exec backend alembic revision --autogenerate -m "describe change"

# Apply
docker compose exec backend alembic upgrade head

# Roll back one step
docker compose exec backend alembic downgrade -1
```

---

## 7. Testing MCP tools locally

### Using the demo tool (zero credentials)

The `DemoMCPConnector` is registered automatically when `ENVIRONMENT=development`. Call it via the agent API:

```powershell
# Get a token first
$TOKEN = (curl -s -X POST http://localhost:8000/auth/register `
  -H "Content-Type: application/json" `
  -d '{"email":"dev@test.local","password":"DevTest123!","full_name":"Dev"}' | ConvertFrom-Json).access_token

# List available tools
curl -s http://localhost:8000/api/v1/agent/tools `
  -H "Authorization: Bearer $TOKEN" | ConvertFrom-Json | Select -ExpandProperty tools

# Call demo_echo
curl -s -X POST http://localhost:8000/api/v1/agent/execute `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"message":"Call demo_echo with message hello","enable_mcp":true,"max_steps":3}' `
  | ConvertFrom-Json | Select status, output, tool_calls
```

### Using Atlassian (Jira / Confluence)

1. Create a free Atlassian sandbox site at https://www.atlassian.com
2. Create an OAuth 2.0 app at https://developer.atlassian.com/console/myapps/
3. Add the following variables to `backend/.env.docker`:
   ```dotenv
   ATLASSIAN_MCP_SERVER_URL=https://mcp.atlassian.com/v1
   ATLASSIAN_CLIENT_ID=your-client-id
   ATLASSIAN_CLIENT_SECRET=your-client-secret
   ```
4. Restart the backend: `docker compose restart backend`
5. Test:
   ```powershell
   curl -s -X POST http://localhost:8000/api/v1/agent/execute `
     -H "Authorization: Bearer $TOKEN" `
     -H "Content-Type: application/json" `
     -d '{"message":"Get Jira issue PROJ-1","enable_mcp":true}' `
     | ConvertFrom-Json
   ```

---

## 8. Testing RAG locally

```powershell
# Upload a test document
curl -s -X POST http://localhost:8000/api/v1/documents/upload `
  -H "Authorization: Bearer $TOKEN" `
  -F "file=@path/to/document.pdf" `
  | ConvertFrom-Json

# Save the document_id from the response, then poll status
$DOC_ID = "..."  # from upload response
curl -s "http://localhost:8000/api/v1/documents/$DOC_ID/status" `
  -H "Authorization: Bearer $TOKEN" | ConvertFrom-Json

# Once status="ready", query the document
curl -s -X POST http://localhost:8000/api/v1/rag/query `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d "{\"question\":\"What is the main topic?\",\"document_ids\":[\"$DOC_ID\"]}" `
  | ConvertFrom-Json
```

The response includes `answer`, `sources` (with `excerpt` and `score`), and `request_id`.

---

## 9. Testing Agent execution locally

```powershell
# DIRECT_LLM mode — just call the LLM
curl -s -X POST http://localhost:8000/api/v1/agent/execute `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"message":"What is 2+2?","enable_rag":false,"enable_mcp":false}' `
  | ConvertFrom-Json

# RAG mode — query uploaded documents
curl -s -X POST http://localhost:8000/api/v1/agent/execute `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"message":"Summarise my architecture document","enable_rag":true,"enable_mcp":false}' `
  | ConvertFrom-Json

# AGENT mode — full orchestration
curl -s -X POST http://localhost:8000/api/v1/agent/execute `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -d '{"message":"Call demo_env_info and tell me about the backend config","enable_mcp":true,"max_steps":5}' `
  | ConvertFrom-Json

# Streaming (SSE)
curl -s -X POST http://localhost:8000/api/v1/agent/stream `
  -H "Authorization: Bearer $TOKEN" `
  -H "Content-Type: application/json" `
  -H "Accept: text/event-stream" `
  -d '{"message":"What is RAG?","enable_rag":true}' `
  --no-buffer
```

---

## 10. Environment variable reference

Full reference in `backend/.env.docker.example`. Key variables:

| Variable | Required | Default | Notes |
|---|---|---|---|
| `SECRET_KEY` | **Yes** | — | JWT signing key — generate with `python -c "import secrets; print(secrets.token_hex(32))"` |
| `AES_ENCRYPTION_KEY` | **Yes** | — | AES-256 key — generate with `python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())"` |
| `GEMINI_API_KEY` | No | blank | Required only for live LLM answer generation |
| `DATABASE_URL` | Auto | set by compose | Overridden to `postgres:5432` inside containers |
| `REDIS_URL` | Auto | set by compose | Overridden to `redis:6379/0` inside containers |
| `CHROMA_HOST` | Auto | `chromadb` | Set by compose; use `localhost` in venv |
| `MAX_AGENT_STEPS` | No | 10 | Max agent reasoning steps |
| `MAX_AGENT_TOOL_CALLS` | No | 20 | Max MCP tool calls per agent run |
| `AGENT_TIMEOUT_SECONDS` | No | 120.0 | Agent run timeout |
| `ATLASSIAN_CLIENT_ID` | No | blank | Required for Jira/Confluence MCP tools |
| `ATLASSIAN_CLIENT_SECRET` | No | blank | Required for Jira/Confluence MCP tools |
| `ENVIRONMENT` | No | `development` | Controls security defaults and demo tool registration |
| `LOG_LEVEL` | No | `DEBUG` | Set `INFO` for quieter output |

---

## 11. Troubleshooting

### FastAPI won't start

```powershell
docker compose logs backend
```

Common causes:
- `SECRET_KEY` or `AES_ENCRYPTION_KEY` still contains `REPLACE_ME_…` — regenerate both.
- Port 8000 already in use — change the host port mapping.
- `backend/.env.docker` not found — copy from `.env.docker.example`.

### ChromaDB not reachable

```powershell
docker compose logs chromadb
curl http://localhost:8001/api/v1/heartbeat
```

- Wait up to 30 s for ChromaDB to start.
- ChromaDB port is bound to `127.0.0.1:8001` — not accessible from another machine.
- Inside containers use `chromadb:8000` (set by `CHROMA_HOST` env var).

### Document stuck in "processing"

```powershell
docker compose logs celery_worker
docker compose exec celery_worker python -m celery -A app.workers.celery_app inspect active
```

- Ensure ChromaDB and MinIO are healthy.
- Check `CHROMA_HOST=chromadb` in the worker container.
- Restart the worker if it crashed: `docker compose restart celery_worker`.

### No LLM responses (only tool/RAG work)

This is expected when `GEMINI_API_KEY` is blank. The agent will fail at the `respond` step with an LLM error. Set the key in `backend/.env.docker` and restart:
```powershell
docker compose restart backend celery_worker
```

### "AES key error" on startup

```
STARTUP_VALIDATION_FAILED: required environment variable 'AES_ENCRYPTION_KEY' is missing
```

Generate and set the key:
```powershell
python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"
# Paste output into backend/.env.docker as AES_ENCRYPTION_KEY=<value>
docker compose restart backend
```

### Hot-reload not triggering

The backend mounts `./backend/app` into the container. Changes to `.py` files trigger reload. Changes to `requirements.txt` require a rebuild:
```powershell
docker compose build backend
docker compose up -d backend
```

### Port conflicts

If port 8000, 5432, 6379, or 8001 is already in use, edit `docker-compose.yml` and change the host port:
```yaml
ports:
  - "18000:8000"   # use 18000 on the host instead
```

Then access the API at `http://localhost:18000`.
