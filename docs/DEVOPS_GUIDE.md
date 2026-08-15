# DevOps Guide
## Android AI Assistant — Enterprise Edition

---

## Docker Compose Services

### Full Stack (`docker-compose.yml`)

```yaml
services:
  postgres:
    image: postgres:15-alpine
    environment:
      POSTGRES_DB: aiassistant
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - postgres_data:/var/lib/postgresql/data
    ports: ["5432:5432"]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    ports: ["6379:6379"]
    healthcheck:
      test: ["CMD", "redis-cli", "--no-auth-warning", "-a", "${REDIS_PASSWORD}", "ping"]
      interval: 10s

  chromadb:
    image: chromadb/chroma:latest
    volumes:
      - chroma_data:/chroma/chroma
    ports: ["8000:8000"]

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: ${MINIO_USER}
      MINIO_ROOT_PASSWORD: ${MINIO_PASSWORD}
    volumes:
      - minio_data:/data
    ports: ["9000:9000", "9001:9001"]

  backend:
    build: ./backend
    depends_on:
      postgres: { condition: service_healthy }
      redis: { condition: service_healthy }
      chromadb: { condition: service_started }
      minio: { condition: service_started }
    env_file: ./backend/.env
    ports: ["8080:8080"]
    command: uvicorn app.main:app --host 0.0.0.0 --port 8080 --workers 4

  celery:
    build: ./backend
    depends_on:
      redis: { condition: service_healthy }
      postgres: { condition: service_healthy }
    env_file: ./backend/.env
    command: celery -A app.workers.celery_app worker --loglevel=info --concurrency=4

  nginx:
    image: nginx:alpine
    volumes:
      - ./infrastructure/nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./infrastructure/certs:/etc/nginx/certs:ro
    ports: ["80:80", "443:443"]
    depends_on: [backend]

  prometheus:
    image: prom/prometheus:latest
    volumes:
      - ./infrastructure/prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    ports: ["9090:9090"]

  grafana:
    image: grafana/grafana:latest
    environment:
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_PASSWORD}
    volumes:
      - grafana_data:/var/lib/grafana
      - ./infrastructure/grafana/provisioning:/etc/grafana/provisioning:ro
    ports: ["3000:3000"]
    depends_on: [prometheus]

  loki:
    image: grafana/loki:latest
    ports: ["3100:3100"]
    volumes:
      - loki_data:/loki

volumes:
  postgres_data:
  chroma_data:
  minio_data:
  grafana_data:
  loki_data:
```

---

## Service Summary

| Service | Image | Port(s) | Purpose |
|---------|-------|---------|---------|
| `postgres` | postgres:15-alpine | 5432 | Primary relational database |
| `redis` | redis:7-alpine | 6379 | Cache + Celery message broker |
| `chromadb` | chromadb/chroma | 8000 | Vector store for RAG + memory |
| `minio` | minio/minio | 9000, 9001 | Document object storage |
| `backend` | (build) | 8080 | FastAPI application |
| `celery` | (build) | — | Background task workers |
| `nginx` | nginx:alpine | 80, 443 | Reverse proxy + TLS termination |
| `prometheus` | prom/prometheus | 9090 | Metrics scraping |
| `grafana` | grafana/grafana | 3000 | Metrics dashboards |
| `loki` | grafana/loki | 3100 | Log aggregation |

---

## GitHub Actions CI/CD Pipelines

### Android CI (`android-ci.yml`)

Triggers: push to `main`/`develop`, pull requests to `main`.

```yaml
name: Android CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  build-and-test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }

      - name: Cache Gradle
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: gradle-${{ hashFiles('**/*.gradle.kts') }}

      - run: ./gradlew ktlintCheck       # zero-error tolerance
      - run: ./gradlew detekt            # zero-error tolerance
      - run: ./gradlew testDebugUnitTest # unit + property tests
      - run: ./gradlew koverVerify       # coverage gate ≥ 70%

      - name: Upload test reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: android-test-reports
          path: '**/build/reports/tests/'
```

### Backend CI (`backend-ci.yml`)

Triggers: push to `main`/`develop`, pull requests to `main`.

```yaml
name: Backend CI
on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  test:
    runs-on: ubuntu-latest
    services:
      postgres:
        image: postgres:15-alpine
        env: { POSTGRES_PASSWORD: test, POSTGRES_DB: test_db }
        options: --health-cmd pg_isready --health-interval 10s
      redis:
        image: redis:7-alpine

    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-python@v5
        with: { python-version: "3.11" }

      - name: Install dependencies
        run: pip install -r requirements.txt
        working-directory: backend

      - run: ruff check .                           # zero-error tolerance
        working-directory: backend
      - run: mypy app/                              # zero-error tolerance
        working-directory: backend
      - run: pytest --cov=app --cov-fail-under=70  # coverage gate ≥ 70%
        working-directory: backend
        env:
          DATABASE_URL: postgresql+asyncpg://postgres:test@localhost:5432/test_db
          REDIS_URL: redis://localhost:6379/0
          SECRET_KEY: ci-test-secret-key-minimum-32-characters!!
          ENCRYPTION_KEY: ci-test-encryption-key-exactly32chars
```

### Branch Protection Rules

Configure on the `main` branch in GitHub Settings → Branches:
- Require status checks to pass: `build-and-test` (Android), `test` (Backend)
- Require at least 1 approving review
- Require linear history (no merge commits)
- Restrict force pushes

---

## Environment Variable Management

```env
# backend/.env.example — copy to .env and fill in values

# Database
DATABASE_URL=postgresql+asyncpg://user:pass@localhost:5432/aiassistant
REDIS_URL=redis://:password@localhost:6379/0

# Security (never commit actual values)
SECRET_KEY=your-256-bit-secret-key-minimum-32-characters
ENCRYPTION_KEY=your-256-bit-aes-key-exactly-32bytes!

# LLM Providers (all optional — configure what you use)
OPENAI_API_KEY=sk-...
GEMINI_API_KEY=AIza...
ANTHROPIC_API_KEY=sk-ant-...
OLLAMA_BASE_URL=http://localhost:11434

# Storage
MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=documents

# Firebase
FIREBASE_CREDENTIALS_JSON=/path/to/serviceAccountKey.json
GOOGLE_CLIENT_ID=...
GOOGLE_CLIENT_SECRET=...

# Observability
GRAFANA_PASSWORD=secure-grafana-password
PROMETHEUS_ENABLED=true
LOKI_URL=http://loki:3100

# Celery
CELERY_BROKER_URL=redis://:password@localhost:6379/1
CELERY_RESULT_BACKEND=redis://:password@localhost:6379/2

# Postgres Docker Compose only
POSTGRES_USER=postgres
POSTGRES_PASSWORD=secure-password
```

---

## Database Migrations (Alembic)

```bash
# Apply all pending migrations
cd backend
alembic upgrade head

# Create a new migration (auto-generated from model changes)
alembic revision --autogenerate -m "add_habit_entries_table"

# Check current migration state
alembic current

# Roll back one step (requires explicit approval in production)
alembic downgrade -1
```

Alembic migration files live in `backend/alembic/versions/`. All migrations are reviewed for
reversibility before merging. CI runs migrations against a clean test database.

---

## Local Development Quickstart

```bash
# 1. Start infrastructure (no backend or celery)
docker compose up postgres redis chromadb minio prometheus grafana loki -d

# 2. Set up backend
cd backend
cp .env.example .env   # fill in values
pip install -r requirements.txt
alembic upgrade head

# 3. Start backend (hot-reload)
uvicorn app.main:app --reload --host 0.0.0.0 --port 8080

# 4. Start Celery worker (separate terminal)
celery -A app.workers.celery_app worker --loglevel=debug
```

---

## Production Checklist (pre-deploy)

- [ ] All CI checks green on release branch
- [ ] Coverage ≥ 70% confirmed
- [ ] `alembic upgrade head` tested on staging
- [ ] All env vars set and validated
- [ ] TLS certificates valid (not expiring within 60 days)
- [ ] Certificate fingerprints updated in Android app if cert renewed
- [ ] Firebase `google-services.json` is production file
- [ ] MinIO bucket `documents` created
- [ ] Grafana dashboards imported
- [ ] Observability services (Prometheus, Grafana, Loki) on internal network only
