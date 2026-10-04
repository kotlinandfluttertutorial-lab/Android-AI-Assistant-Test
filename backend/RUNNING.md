# Running the Backend — Android AI Assistant

Everything you need to develop and test MCP tools, the Agent layer, and the
RAG pipeline locally using Docker.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [First-Time Setup (5 minutes)](#2-first-time-setup-5-minutes)
3. [Start the Stack](#3-start-the-stack)
4. [Verify All Services](#4-verify-all-services)
5. [Run the Smoke Test](#5-run-the-smoke-test)
6. [MCP Demo Tool](#6-mcp-demo-tool)
7. [Option B — Local venv (No Docker)](#7-option-b--local-venv-no-docker)
8. [Database Migrations](#8-database-migrations)
9. [Running the Celery Worker](#9-running-the-celery-worker)
10. [Running Tests](#10-running-tests)
11. [Service URLs](#11-service-urls)
12. [Environment Variables Reference](#12-environment-variables-reference)
13. [Common Commands](#13-common-commands)
14. [Troubleshooting](#14-troubleshooting)

---

## 1. Prerequisites

| Tool | Minimum Version | Install |
|---|---|---|
| Docker Desktop | 24.x | https://www.docker.com/products/docker-desktop/ |
| Docker Compose | v2 (bundled with Docker Desktop) | — |
| Python | 3.11 | Only needed for Option B |
| Git | any | https://git-scm.com/ |

> **Windows:** Use PowerShell 7 or the commands shown with `docker compose` (no hyphen, v2 syntax).

---

## 2. First-Time Setup (5 minutes)

```powershell
# 1. Copy the Docker env template
Copy-Item backend\.env.docker.example backend\.env.docker

# 2. Generate the two REQUIRED values and paste them into backend\.env.docker
python -c "import secrets; print('SECRET_KEY=' + secrets.token_hex(32))"
python -c "import base64, os; print('AES_ENCRYPTION_KEY=' + base64.b64encode(os.urandom(32)).decode())"

# 3. (Optional) Add a Gemini API key for live LLM responses
# Obtain from: https://aistudio.google.com/app/apikey
# All non-LLM features work without a key.
```

Open `backend/.env.docker` and replace the two `REPLACE_ME_…` placeholder values.
Everything else has safe local-only defaults — no production secrets are required.

---

## 3. Start the Stack

```powershell
# Start all 5 services (postgres, redis, chromadb, minio, backend, celery_worker)
docker compose up -d

# Watch logs until all services are healthy (~60 s on first boot)
docker compose logs -f

# Run database migrations (once after first `up -d`, and after every schema change)
docker compose exec backend alembic upgrade head
```

### Dev mode (with Flower + Mailhog)

```powershell
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d
```

This adds:
- **Flower** — Celery task monitoring at http://localhost:5555
- **Mailhog** — local SMTP catcher at http://localhost:8025

---

## 4. Verify All Services

```powershell
# All services should show "healthy"
docker compose ps

# FastAPI
curl http://localhost:8000/health   # {"status":"ok"}
curl http://localhost:8000/ready    # {"status":"ready","dependencies":{...}}

# PostgreSQL
docker compose exec postgres pg_isready -U aiassistant

# Redis
docker compose exec redis redis-cli ping   # PONG

# ChromaDB
curl http://localhost:8001/api/v1/heartbeat   # {"nanosecond heartbeat": ...}

# Celery worker
docker compose exec celery_worker python -m celery -A app.workers.celery_app inspect ping
```

---

## 5. Run the Smoke Test

The smoke test verifies every acceptance criterion automatically:

```powershell
docker compose exec backend python scripts/local_smoke_test.py
```

Expected output (all checks pass):

```
Android AI Assistant — Local Smoke Test
Base URL: http://localhost:8000

──────────────────────────────────────────────────────────
  1. FastAPI
──────────────────────────────────────────────────────────
  ✓ PASS  GET /health  (status=ok)
  ✓ PASS  GET /ready   (status=ready  deps={...})

──────────────────────────────────────────────────────────
  2. PostgreSQL
──────────────────────────────────────────────────────────
  ✓ PASS  SELECT 1  (connected to postgres:5432/aiassistant_local)

  ...

════════════════════════════════════════════════════════════
Summary  10 passed  0 skipped  0 failed  / 10 total
════════════════════════════════════════════════════════════
```

The script:
- Registers a throw-away test user and obtains a JWT automatically
- Uploads a small text document and polls ingestion status
- Submits a RAG question via `POST /api/v1/rag/query`
- Executes an agent run via `POST /api/v1/agent/execute`
- Calls the MCP demo echo tool via the agent API
- Verifies required environment variables are set
- Cleans up the test document at the end

You can also run it from the host (requires `requests` in your venv):

```powershell
$env:BASE_URL = "http://localhost:8000"
python backend/scripts/local_smoke_test.py
```

---

## 6. MCP Demo Tool

`backend/app/mcp/connectors/demo.py` provides four zero-credential MCP tools
that work entirely in-process — no API keys, no network calls to external services.

| Tool name | What it does |
|---|---|
| `demo_echo` | Returns the input message unchanged — liveness test for the MCP dispatch pipeline |
| `demo_add` | Adds two numbers — verifies parameter parsing |
| `demo_env_info` | Returns safe runtime info (Python version, feature flags, agent limits) |
| `demo_rag_ping` | Checks ChromaDB is reachable from within an agent execution context |

### Register the demo connector

The `AgentServiceFactory` in `backend/app/agent/factory.py` registers
`DemoMCPConnector` automatically when `ENVIRONMENT=development`.  No code
changes are needed for local development.

To register it manually in any code or test:

```python
from app.mcp.server import MCPServer
from app.mcp.connectors.demo import DemoMCPConnector

server = MCPServer.create(db=db)
server.register(DemoMCPConnector())
result = await server.execute("demo_echo", {"message": "hello"}, user_id="dev")
assert result.success
assert result.result["echo"] == "hello"
```

### Call demo tools via the API

```bash
# Register + login to get a JWT (replace with your credentials)
TOKEN=$(curl -s -X POST http://localhost:8000/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"you@example.com","password":"your_password"}' | python -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# List available MCP tools (includes demo_*)
curl -s http://localhost:8000/api/v1/agent/tools \
  -H "Authorization: Bearer $TOKEN" | python -m json.tool

# Call demo_echo via the Agent execute endpoint
curl -s -X POST http://localhost:8000/api/v1/agent/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Call demo_echo with message ping","enable_mcp":true,"max_steps":3}' \
  | python -m json.tool

# Call demo_env_info to verify backend configuration
curl -s -X POST http://localhost:8000/api/v1/agent/execute \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message":"Call demo_env_info and summarise the result","enable_mcp":true,"max_steps":3}' \
  | python -m json.tool
```

---

## 7. Option B — Local venv (No Docker)

Use this when you want faster iteration without container rebuild times.
Start only the infrastructure services via Docker:

```powershell
# Infrastructure only (PostgreSQL, Redis, ChromaDB, MinIO)
docker compose up -d postgres redis chromadb minio

# Create and activate virtual environment
cd backend
python -m venv venv311
venv311\Scripts\Activate.ps1

# Install dependencies
pip install -r requirements.txt

# Configure environment
Copy-Item .env.docker.example .env.docker
# Edit .env.docker — change CHROMA_HOST=localhost, REDIS_URL=redis://localhost:6379/0
# (docker-compose.yml overrides these inside containers; for venv use localhost)

# Apply migrations
alembic upgrade head

# Start the API server (hot-reload)
python -m uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload
```

---

## 8. Database Migrations

```powershell
# Apply all pending migrations (run after every pull and after first start)
docker compose exec backend alembic upgrade head

# Check current revision
docker compose exec backend alembic current

# Roll back one step
docker compose exec backend alembic downgrade -1

# Generate a new migration after changing a model
docker compose exec backend alembic revision --autogenerate -m "describe change"
```

---

## 9. Running the Celery Worker

The `celery_worker` service starts automatically with `docker compose up -d`.

```powershell
# View Celery logs
docker compose logs -f celery_worker

# Check worker is alive
docker compose exec celery_worker python -m celery \
  -A app.workers.celery_app inspect ping

# Flower UI (only in dev mode)
# http://localhost:5555
```

**Local venv (second terminal):**

```powershell
cd backend
venv311\Scripts\Activate.ps1
python -m celery -A app.workers.celery_app worker --loglevel=debug --pool=solo
```

---

## 10. Running Tests

```powershell
cd backend
venv311\Scripts\Activate.ps1

# Run all unit tests
pytest

# With coverage report
pytest --cov=app --cov-report=term-missing

# Specific file
pytest tests/unit/test_agent_integration.py -v

# Exclude slow / integration tests
pytest -m "not slow"

# Run the local smoke test (requires running Docker stack)
docker compose exec backend python scripts/local_smoke_test.py
```

---

## 11. Service URLs

| Service | URL | Notes |
|---|---|---|
| FastAPI (direct) | http://localhost:8000 | API server |
| Swagger UI | http://localhost:8000/docs | Interactive API explorer |
| ReDoc | http://localhost:8000/redoc | Alternative docs |
| Health check | http://localhost:8000/health | `{"status":"ok"}` |
| Readiness probe | http://localhost:8000/ready | Full dependency check |
| PostgreSQL | `localhost:5432` | `aiassistant / local_dev_password` |
| Redis | `localhost:6379` | No auth |
| ChromaDB | http://localhost:8001 | Vector store HTTP API (127.0.0.1 only) |
| MinIO API | http://localhost:9000 | S3-compatible endpoint |
| MinIO Console | http://localhost:9001 | Web UI (minioadmin / minioadmin123) |
| Flower | http://localhost:5555 | Celery monitoring (dev mode only) |
| Mailhog | http://localhost:8025 | SMTP catcher (dev mode only) |
| Android Emulator | http://10.0.2.2:8000 | Maps to host port 8000 |

---

## 12. Environment Variables Reference

All variables are documented in `backend/.env.docker.example`.

| Variable | Required | Default | Notes |
|---|---|---|---|
| `SECRET_KEY` | **Yes** | — | JWT signing key. Generate with `python -c "import secrets; print(secrets.token_hex(32))"` |
| `AES_ENCRYPTION_KEY` | **Yes** | — | AES-256 key. Generate with `python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())"` |
| `DATABASE_URL` | Auto | set by compose | Overridden to use Docker service name `postgres` |
| `REDIS_URL` | Auto | set by compose | Overridden to `redis://redis:6379/0` |
| `CHROMA_HOST` | Auto | `chromadb` | Set by compose; use `localhost` in venv |
| `CHROMA_PORT` | Auto | `8000` | Internal container port (external: 8001) |
| `GEMINI_API_KEY` | No | blank | Required only for live LLM responses |
| `OPENAI_API_KEY` | No | blank | Optional — alternative LLM provider |
| `MAX_AGENT_STEPS` | No | `10` | Max agent reasoning steps per run |
| `MAX_AGENT_TOOL_CALLS` | No | `20` | Max MCP tool calls per agent run |
| `AGENT_TIMEOUT_SECONDS` | No | `120.0` | Agent run wall-clock timeout |
| `ENVIRONMENT` | No | `development` | Controls security defaults |
| `LOG_LEVEL` | No | `DEBUG` | Python logging level |
| `OTEL_ENABLED` | No | `false` | Disabled locally to reduce log noise |

---

## 13. Common Commands

```powershell
# ── Start / Stop ──────────────────────────────────────────────────────────────

# Start standard stack
docker compose up -d

# Start dev stack (+ Flower + Mailhog)
docker compose -f docker-compose.yml -f docker-compose.dev.yml up -d

# Stop (keep data)
docker compose down

# Stop and delete all data volumes  ⚠ DESTRUCTIVE
docker compose down -v

# ── Logs ──────────────────────────────────────────────────────────────────────

docker compose logs -f                  # all services
docker compose logs -f backend          # FastAPI only
docker compose logs -f celery_worker    # Celery only

# ── Exec ──────────────────────────────────────────────────────────────────────

docker compose exec backend bash                        # open shell
docker compose exec backend alembic upgrade head        # run migrations
docker compose exec backend python scripts/local_smoke_test.py   # smoke test
docker compose exec backend pytest tests/unit/ -v       # unit tests

# ── Build ─────────────────────────────────────────────────────────────────────

docker compose build backend          # rebuild backend image
docker compose build --no-cache backend   # force full rebuild

# ── Status ────────────────────────────────────────────────────────────────────

docker compose ps                     # show container states + health
docker compose top                    # show running processes
```

---

## 14. Troubleshooting

### FastAPI does not start

```powershell
docker compose logs backend
```

Common causes:
- `SECRET_KEY` or `AES_ENCRYPTION_KEY` is still the placeholder value — regenerate both.
- Port 8000 already in use — stop the other process or change the host port mapping.
- `backend/.env.docker` file not found — copy from `.env.docker.example`.

### PostgreSQL connection refused

```powershell
docker compose logs postgres
docker compose ps postgres   # health should be "healthy"
```

- Wait for the healthcheck to pass (up to 30 s on first boot).
- If port 5432 conflicts with a local PostgreSQL: change `"5432:5432"` to `"5433:5432"` in `docker-compose.yml` and update `DATABASE_URL` accordingly.

### Redis connection error

```powershell
docker compose exec redis redis-cli ping   # should return PONG
docker compose logs redis
```

- Check `REDIS_URL` — inside containers it must be `redis://redis:6379/0`, not `localhost`.

### ChromaDB heartbeat fails

```powershell
docker compose logs chromadb
curl http://localhost:8001/api/v1/heartbeat
```

- The first start can take up to 30 s.
- ChromaDB is bound to `127.0.0.1:8001` on the host — it is not reachable from another machine.
- Inside containers it is reachable as `chromadb:8000` (set by `CHROMA_HOST` env var).

### Celery worker not processing tasks

```powershell
docker compose exec celery_worker python -m celery \
  -A app.workers.celery_app inspect ping
docker compose logs celery_worker
```

- Verify `CELERY_BROKER_URL=redis://redis:6379/0` in the container.
- Check Redis is healthy (`docker compose ps redis`).
- If Celery logs show `kombu.exceptions.OperationalError` — Redis is not reachable.

### Document ingestion stuck in "pending"

- Check `celery_worker` logs for errors.
- Confirm `CHROMA_HOST=chromadb` is set (not `localhost`) in the worker container.
- Confirm MinIO is healthy: `docker compose ps minio`.
- Run `docker compose exec celery_worker python -m celery -A app.workers.celery_app inspect active` to see currently running tasks.

### "REPLACE_ME" AES key error on startup

```
STARTUP_VALIDATION_FAILED: required environment variable 'AES_ENCRYPTION_KEY' is missing or empty
```

Generate a real key:

```powershell
python -c "import base64, os; print(base64.b64encode(os.urandom(32)).decode())"
```

Paste the output into `backend/.env.docker` as the value of `AES_ENCRYPTION_KEY`.

### Hot-reload not working

The backend mounts `./backend/app` into the container.  Changes to `.py` files
in `backend/app/` trigger an automatic reload.  Changes to `requirements.txt`
or `Dockerfile` require a rebuild:

```powershell
docker compose build backend
docker compose up -d backend
```

### `alembic upgrade head` fails with "relation already exists"

The database has tables from a previous run.  Stamp Alembic to the latest revision:

```powershell
docker compose exec backend alembic stamp head
```

### Smoke test reports FAIL for agent/RAG checks

- Ensure `alembic upgrade head` has been run.
- Ensure a Gemini API key is set in `backend/.env.docker` (LLM generation requires it).
- If the key is intentionally blank, these checks return a graceful error — that is expected.
- Re-run after ingestion completes: document processing is async and may take 10–30 s.
