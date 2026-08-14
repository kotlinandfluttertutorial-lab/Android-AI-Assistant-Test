# Running the Backend — Android AI Assistant

This document covers every way to run, test, and operate the Python backend.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Environment Configuration](#2-environment-configuration)
3. [Option A — Docker Compose (Recommended)](#3-option-a--docker-compose-recommended)
4. [Option B — Local Development (No Docker)](#4-option-b--local-development-no-docker)
5. [Database Migrations](#5-database-migrations)
6. [Running the Celery Worker](#6-running-the-celery-worker)
7. [Running Tests](#7-running-tests)
8. [Service URLs (Development)](#8-service-urls-development)
9. [Common Commands Reference](#9-common-commands-reference)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Prerequisites

| Tool | Minimum Version | Install |
|---|---|---|
| Python | 3.12 | https://www.python.org/downloads/ |
| Docker Desktop | 24.x | https://www.docker.com/products/docker-desktop/ |
| Docker Compose | v2 (bundled with Docker Desktop) | — |
| Git | any | https://git-scm.com/ |

> **Windows note:** All commands below use the Windows `cmd` shell.  
> If you use PowerShell, replace `&` with `;` and `copy` with `Copy-Item`.

---

## 2. Environment Configuration

The backend reads all secrets and settings from a `.env` file.

```cmd
cd backend
copy .env.example .env
```

Open `backend\.env` and fill in at minimum the three required values:

```env
# Required — PostgreSQL connection
DATABASE_URL=postgresql+asyncpg://aiassistant:changeme@localhost:5432/aiassistant

# Required — Redis connection
REDIS_URL=redis://localhost:6379/0

# Required — JWT signing secret (generate a strong random value)
SECRET_KEY=<run: python -c "import secrets; print(secrets.token_hex(32))">
```

Everything else is optional and has sensible defaults. See `.env.example` for the full reference with inline documentation.

**LLM provider keys** — leave blank to disable a provider; the app starts without any:

```env
OPENAI_API_KEY=sk-...
GEMINI_API_KEY=AQ.Ab8RN6KsOFzwbID62Wo-1mSdLst3P9EbKI7EuK_wlUY3Vcc9Ig
ANTHROPIC_API_KEY=sk-ant-...
```

---

## 3. Option A — Docker Compose (Recommended)

Starts the entire stack in containers: FastAPI · Celery worker · PostgreSQL · Redis · ChromaDB · MinIO · Nginx · Prometheus · Grafana · Loki.

### 3.1 First-time setup

```cmd
rem From the project root (where docker-compose.yml lives)

docker compose up -d
docker compose build --no-cache backend
docker compose up -d backend
docker compose exec backend alembic upgrade head
docker compose exec backend alembic upgrade head


```

Wait for all containers to become healthy (about 30–60 s), then run migrations:

```cmd
docker compose exec backend alembic upgrade head
```

### 3.2 Verify it is running

```cmd
curl http://localhost/health
```

Expected response: `{"status": "ok", ...}`

Interactive API docs: **http://localhost/docs**

### 3.3 Development mode (hot-reload)

`docker-compose.override.yml` is automatically applied when running locally.  
It mounts the source code into the container and enables `--reload`, so code changes take effect without rebuilding.

```cmd
docker compose up -d
```

The override also exposes all internal ports on the host — see [Service URLs](#8-service-urls-development).

### 3.4 Rebuilding after dependency changes

```cmd
docker compose build backend
docker compose up -d backend
```

### 3.5 Viewing logs

```cmd
rem All services
docker compose logs -f

rem Backend only
docker compose logs -f backend

rem Celery worker only
docker compose logs -f celery_worker
```

### 3.6 Stopping

```cmd
rem Stop containers, keep data volumes
docker compose down

rem Stop containers AND delete all data (destructive)
docker compose down -v
```

---

## 4. Option B — Local Development (No Docker)

Use this when you want faster iteration without rebuilding containers.  
You still need the infrastructure services — the easiest way is to run just those via Docker:

```cmd
rem Start only PostgreSQL, Redis, ChromaDB, and MinIO
docker compose up -d postgres redis chromadb minio
```

### 4.1 Create and activate a virtual environment

```cmd
cd backend
python -m venv venv
venv\Scripts\activate
```

### 4.2 Install dependencies

```cmd
pip install -r requirements.txt
```

### 4.3 Configure environment

```cmd
copy .env.example .env
rem Edit .env and set DATABASE_URL, REDIS_URL, SECRET_KEY
```

### 4.4 Run database migrations

```cmd
alembic upgrade head
```

### 4.5 Start the API server

```cmd
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

J:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend>
venv311\Scripts\uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

netsh advfirewall firewall add rule name="FastAPI Dev" dir=in action=allow protocol=TCP localport=8000

```
J: cd J:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend
````

https://chatgpt.com/c/6a74293b-8154-83ee-a9a0-312a18135e80

cd j:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend
venv311\Scripts\uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

cd J:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend
venv311\Scripts\activate
celery -A app.workers.celery_app worker --loglevel=info --concurrency=4

cloudflared tunnel --config "C:\Users\admin\.cloudflared\config.yml" run mybackend
````

ngrok http 8000

For your Android app, since the URL changes, the easiest workaround is to put the base URL in a config you can update without rebuilding — like SharedPreferences or a settings screen. Then just paste the new ngrok URL whenever you restart.

http://192.168.0.158:8000/docs
The API is now available at **http://localhost:8000**  
Interactive docs: **http://localhost:8000/docs**

---

## 5. Database Migrations

Alembic manages the PostgreSQL schema.

```cmd
rem Apply all pending migrations (run after every pull)
alembic upgrade head

rem Check current migration state
alembic current

rem Show migration history
alembic history

rem Roll back the most recent migration
alembic downgrade -1

rem Generate a new migration after changing a model
alembic revision --autogenerate -m "describe your change here"
```

> **Docker:** Prefix each command with `docker compose exec backend` instead of running locally.  
> Example: `docker compose exec backend alembic upgrade head`

---

## 6. Running the Celery Worker

The Celery worker handles background tasks — primarily RAG document ingestion. Redis must be running before starting the worker.

**Local:**

```cmd

rem Open a second terminal in the backend directory
venv311\Scripts\activate
celery -A app.workers.celery_app worker --loglevel=info --concurrency=4

cd J:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend
```

**Docker Compose:**  
The `celery_worker` service starts automatically with `docker compose up -d`. No extra steps needed.

**Monitoring tasks (optional):**

```cmd
rem Install Flower (Celery monitoring UI) — not in requirements.txt
pip install flower==2.0.1
celery -A app.workers.celery_app flower --port=5555
rem Open http://localhost:5555
```

---

## 7. Running Tests

```cmd
cd backend
venv\Scripts\activate

rem Run all tests
pytest

rem Run with coverage report
pytest --cov=app --cov-report=term-missing

rem Run a specific test file
pytest tests/test_rag_service.py

rem Run a specific test function
pytest tests/test_rag_service.py::test_chunk_text_basic

rem Run only fast tests (exclude slow property-based tests)
pytest -m "not slow"

rem Run in verbose mode
pytest -v
```

Test configuration is in `pytest.ini`. All tests run in `asyncio_mode = auto`.

---

## 8. Service URLs (Development)

When using Docker Compose with `docker-compose.override.yml` applied (the default when running locally), all ports are exposed on the host:

| Service | URL | Notes |
|---|---|---|
| FastAPI (via Nginx) | http://localhost | Production routing through Nginx |
| FastAPI (direct) | http://localhost:8000 | Direct access, bypasses Nginx |
| API docs (Swagger) | http://localhost:8000/docs | Interactive API explorer |
| API docs (ReDoc) | http://localhost:8000/redoc | Alternative docs UI |
| Health check | http://localhost/health | Returns `{"status": "ok"}` |
| PostgreSQL | localhost:5432 | User/pass from `.env` |
| Redis | localhost:6379 | No auth by default |
| ChromaDB | http://localhost:8001 | Vector store HTTP API |
| MinIO API | http://localhost:9000 | S3-compatible endpoint |
| MinIO Console | http://localhost:9001 | Web UI for browsing buckets |
| Prometheus | http://localhost:9090 | Metrics browser |
| Grafana | http://localhost:3000 | Dashboards (admin / changeme) |

---

## 9. Common Commands Reference

```cmd
rem ── Docker Compose ────────────────────────────────────────────────────────

rem Start all services
docker compose up -d

rem Start specific services only
docker compose up -d postgres redis chromadb minio

rem Stop all services
docker compose down

rem Rebuild and restart backend
docker compose build backend & docker compose up -d backend

rem Open a shell inside the backend container
docker compose exec backend bash

rem Run a one-off command in the backend container
docker compose exec backend python -c "from app.config.settings import get_settings; print(get_settings())"

rem ── Local (venv activated) ────────────────────────────────────────────────

rem Start API server with hot reload
uvicorn app.main:app --host 0.0.0.0 --port 8000 --reload

rem Start Celery worker
celery -A app.workers.celery_app worker --loglevel=info --concurrency=4

rem Run migrations
alembic upgrade head

rem Generate a new migration
alembic revision --autogenerate -m "add new table"

rem Run tests
pytest

rem Run tests with coverage
pytest --cov=app --cov-report=html
rem Then open htmlcov/index.html in a browser
```

---

## 10. Troubleshooting

**`alembic upgrade head` fails with "relation already exists"**  
The database already has tables from a previous run. Reset it or stamp the migration:
```cmd
alembic stamp head
```

**`uvicorn` exits with `Address already in use`**  
Another process is on port 8000. Kill it or change the port:
```cmd
uvicorn app.main:app --port 8001 --reload
```

**`ModuleNotFoundError` when running locally**  
The virtual environment is not activated, or dependencies are missing:
```cmd
venv\Scripts\activate
pip install -r requirements.txt
```

**ChromaDB connection refused**  
ChromaDB is not running. Start it with:
```cmd
docker compose up -d chromadb
```
Then verify: `curl http://localhost:8001/api/v1/heartbeat`

**Celery tasks are not executing**  
Redis must be running and `REDIS_URL` must be correct in `.env`.  
Check the worker is running and connected:
```cmd
celery -A app.workers.celery_app inspect ping
```

**`OPENAI_API_KEY not configured` error**  
This is expected if you have not set the key. The app starts without it; only endpoints that call OpenAI will fail. Set the key in `.env` to enable OpenAI.

**Docker containers stuck in "starting" state**  
Check for port conflicts with other local services (especially if you have a local PostgreSQL on 5432 or Redis on 6379). Use `docker compose ps` to see container states and `docker compose logs <service>` for details.


cd J:\Android\AndroidStudioProjects\Kiro\Android-AI-Assistant\backend
