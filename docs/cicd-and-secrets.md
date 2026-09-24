# CI/CD Pipeline & Secret Management

Android AI Assistant — How code moves from your machine to Stage and Production.

---

## CI/CD Flow

### Complete picture

```
Your machine
    │
    ▼ git push feature/my-change
GitHub
    │
    ▼ Pull Request → develop  or  main
    │
    ├─── android-ci.yml  (runs on every PR)
    │         │
    │    ┌────┴─────────────────────────────────────────────┐
    │    │  All jobs run in parallel after validate passes  │
    │    │                                                  │
    │    │  validate              ← Gradle wrapper check    │
    │    │  dependency-lint       ← Clean Arch rules        │
    │    │  hilt-ksp-gate         ← Hilt binding compile    │
    │    │  hilt-di-gate          ← Hilt DI verification    │
    │    │  android-lint          ← Lint all modules        │
    │    │  android-unit-tests    ← All module unit tests   │
    │    │  ktlint + Detekt       ← Code style / analysis  │
    │    │  jacoco-gate           ← Coverage ≥ 70%          │
    │    │  hilt-audit            ← DI graph audit          │
    │    │  backend-unit-tests    ← pytest tests/unit/      │
    │    │  backend-integration   ← pytest tests/integ/     │
    │    └────────────────────────────────────────────────┘
    │         │
    │    All 11 checks must pass → PR can merge
    │
    ├─── Merge to develop
    │         │ (no automatic deploy — manual only for now)
    │         ▼
    │    Stage deploy (manual):
    │    .\scripts\deploy-cloud-run.ps1 -Environment stage
    │
    ├─── Merge to main  (PR: develop → main)
    │         │
    │         ▼ android-ci.yml → build-signed-apk job
    │              Builds productionRelease APK
    │              Signs with keystore
    │              Distributes via Firebase App Distribution
    │
    │         ▼ cloud-run-deploy.yml  (triggers on push to main)
    │              Job 1: build  — Docker image → Artifact Registry
    │              Job 2: migrate — Alembic upgrade head (Cloud Run Job)
    │              Job 3: deploy — New revision to Cloud Run (production)
    │              Job 3b: deploy-worker — Celery worker Cloud Run service
    │              Job 4: smoke-test — /health + /ready checks
    │
    └─── Production is live
```

### What triggers what

| Event | Workflows triggered |
|---|---|
| PR opened/updated against `develop` or `main` | `android-ci.yml` — all 11 check jobs |
| Push to `main` | `android-ci.yml` — `build-signed-apk` job only |
| Push to `main` with changes in `backend/**` | `cloud-run-deploy.yml` — full build→migrate→deploy pipeline |
| Manual `workflow_dispatch` | Both workflows support it |

### The 11 required PR checks

Every PR must pass all of these before merging:

| Check | What it does | Blocks merge on |
|---|---|---|
| `validate` | Gradle wrapper integrity | Tampered wrapper |
| `dependency-lint` | Module dependency rules (no feature→feature, etc.) | Architecture violations |
| `hilt-ksp-gate` | KSP generates Hilt code for stage + production debug | Hilt binding errors |
| `hilt-di-gate` | Duplicate Hilt DI verification | Binding errors |
| `android-lint` | Lint all modules, uploads HTML/XML reports | New lint errors |
| `android-unit-tests` | `testDebugUnitTest` all modules | Test failures |
| `ktlint + Detekt` | Style + static analysis on **changed `.kt` files** only | Violations in your changes |
| `jacoco-gate` | Combined `domain` + `data` coverage ≥ 70% | Coverage drops below threshold |
| `hilt-audit` | Additional Hilt DI graph check | Binding errors |
| `backend-unit-tests` | `pytest tests/unit/` (mocked — no DB/Redis needed) | Test failures |
| `backend-integration-tests` | `pytest tests/integration/` with 30s timeout per test | Test failures |

### The production deploy pipeline (cloud-run-deploy.yml)

Runs on every push to `main` that touches `backend/**`.

```
Job 1 — build
    Docker build (linux/amd64, no-cache)
    Push to Artifact Registry:
      asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-<commit>
      asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest
    Output: image reference for downstream jobs

Job 2 — migrate  (needs: build)
    Creates/updates Cloud Run Job: alembic-migrate
    Executes: python -m alembic upgrade head
    Reads secrets: DATABASE_URL, SECRET_KEY, REDIS_URL, AES_ENCRYPTION_KEY
    On failure: streams last 50 log lines from Cloud Logging

Job 3 — deploy  (needs: build, migrate)
    Deploys new revision to Cloud Run service: ai-assistant-backend
    Secrets injected: SECRET_KEY, AES_ENCRYPTION_KEY, DATABASE_URL,
                      REDIS_URL, OPENAI_API_KEY, GEMINI_API_KEY
    Env vars: ENVIRONMENT=production, STORAGE_BACKEND=gcs, etc.
    Cost guards: min-instances=0, max-instances=2, concurrency=40

Job 3b — deploy-worker  (needs: build, migrate, parallel with deploy)
    Deploys Celery worker: ai-assistant-worker
    Same image, APP_MODE=worker, --no-cpu-throttling
    min-instances=1 (always warm), max-instances=1

Job 4 — smoke-test  (needs: deploy, deploy-worker)
    Waits 30s for Cloud Run cold start
    GET /health → must return 200
    GET /ready  → checks DB + Redis + env vars (warning if non-200)
    Retries: 8 attempts × 15s delay = up to 150s patience
```

### The signed APK build (android-ci.yml — build-signed-apk)

Runs on push to `main` or `workflow_dispatch`.

```
Builds: productionRelease APK
Signs:  KEYSTORE_BASE64 + KEY_ALIAS + KEY_PASSWORD + STORE_PASSWORD
Writes: GOOGLE_SERVICES_JSON from secret (real Firebase config)
Output: Signed APK artifact
Distributes: Firebase App Distribution → FIREBASE_TESTER_GROUPS
Notifies: Slack via SLACK_WEBHOOK_URL (optional)
```

Uses GitHub Environment: `production` — can add approval gate here.

---

## How to Update Secrets

### Environment file structure

```
.env.local            ← Local Docker stack. Real secrets. Git-ignored.
.env.stage            ← Stage non-secret config only. Git-ignored when real values added.
.env.production       ← Production non-secret config only. Git-ignored when real values added.

.env.local.example      ← Template for .env.local. Committed.
.env.stage.example      ← Template for .env.stage. Committed.
.env.production.example ← Template for .env.production. Committed.
```

**Local** — real secrets go directly in `.env.local`:
```
DATABASE_URL=postgresql+asyncpg://aiassistant:local_dev_password@localhost:5432/aiassistant_local
SECRET_KEY=<generated>
GEMINI_API_KEY=AIza<your-local-key>
...
```

**Stage / Production** — `.env.stage` and `.env.production` contain **only non-secret config**
(ENVIRONMENT, LOG_LEVEL, CORS_ORIGINS, LLM model names, etc.).
Real secrets like `GEMINI_API_KEY`, `DATABASE_URL`, `SECRET_KEY` live exclusively
in GCP Secret Manager and are injected by `deploy-cloud-run.ps1` at deploy time
via `--set-secrets`.

### Setup workflow

```powershell
# Local — copy template and fill real values
Copy-Item .env.local.example .env.local
# Edit .env.local: set SECRET_KEY, AES_ENCRYPTION_KEY, GEMINI_API_KEY, etc.

# Stage — copy non-secret template
Copy-Item .env.stage.example .env.stage
# Edit .env.stage: only non-secret values (ENVIRONMENT, LOG_LEVEL, CORS_ORIGINS...)
# Store actual Stage secrets in GCP Secret Manager:
$env:GEMINI_API_KEY = "AIza<stage-key>"
.\scripts\store-secrets.ps1 -Environment stage -Secret gemini-api-key
$env:SECRET_KEY = (python -c "import secrets; print(secrets.token_hex(32))")
.\scripts\store-secrets.ps1 -Environment stage -Secret secret-key
# ... repeat for database-url, redis-url, aes-encryption-key

# Production — same pattern
Copy-Item .env.production.example .env.production
# Edit .env.production: only non-secret values
$env:GEMINI_API_KEY = "AIza<prod-key>"
.\scripts\store-secrets.ps1 -Environment production -Secret gemini-api-key
```

### Method A — Zero-downtime secret update (no code change needed)

Cloud Run can be configured to pick up `latest` secret versions on startup.
Since the deploy workflow uses `:latest` for all secrets, deploying a new
revision reads the new value automatically. But you can also force a new
revision with the exact same image:

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

# Force a new revision (picks up new :latest secret versions, same image)
& $gcloud run deploy ai-assistant-backend `
    --image="asia-south1-docker.pkg.dev/$PROJECT/backend/api:latest" `
    --region=asia-south1 `
    --project=$PROJECT `
    --quiet
```

This takes about 60 seconds and causes a brief cold-start window during
traffic switchover. Existing in-flight requests complete normally.

### Method B — Update secret + trigger CI/CD

Push any commit to `main` that touches `backend/**`. The
`cloud-run-deploy.yml` workflow rebuilds, migrates, and deploys — picking
up the new secret version automatically.

---

### Step-by-step: rotating the Gemini API key

**Stage:**

```powershell
# 1. Store the new key
$env:GEMINI_API_KEY = "AIza<new-stage-key>"
.\scripts\store-secrets.ps1 -Environment stage -Secret gemini-api-key

# 2. Force a new revision to pick it up
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run deploy ai-assistant-backend-stage `
    --image="asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest" `
    --region=asia-south1 `
    --quiet

# 3. Verify
curl https://ai-assistant-backend-stage-XXXXXXXXXX.asia-south1.run.app/health
```

**Production:**

```powershell
# 1. Store the new key
$env:GEMINI_API_KEY = "AIza<new-prod-key>"
.\scripts\store-secrets.ps1 -Environment production -Secret gemini-api-key

# 2. Force a new revision (or just push any backend change to main)
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run deploy ai-assistant-backend `
    --image="asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest" `
    --region=asia-south1 `
    --quiet

# 3. Verify
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/health
```

### Step-by-step: rotating the JWT SECRET_KEY

Rotating `SECRET_KEY` invalidates **all existing JWTs** — every logged-in
user gets a 401 and must log in again.

```powershell
# 1. Generate a new key
$newKey = python -c "import secrets; print(secrets.token_hex(32))"
Write-Host "New key: $newKey"  # save this somewhere safe

# 2. Store for the target environment
$env:SECRET_KEY = $newKey
.\scripts\store-secrets.ps1 -Environment production -Secret secret-key

# 3. Deploy immediately (old key is invalid from this point)
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run deploy ai-assistant-backend `
    --image="asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest" `
    --region=asia-south1 --quiet
```

Inform users that they need to log in again.

### Step-by-step: updating the database URL

Used when you rotate Neon credentials or migrate to a new database.

```powershell
# 1. Test the new URL locally first
python -c "
import asyncio, asyncpg
async def test():
    conn = await asyncpg.connect('$env:DATABASE_URL')
    print('OK:', await conn.fetchval('SELECT version()'))
    await conn.close()
asyncio.run(test())
"

# 2. Store the new URL
$env:DATABASE_URL = "postgresql+asyncpg://user:newpass@host.neon.tech/db?ssl=require"
.\scripts\store-secrets.ps1 -Environment production -Secret database-url

# 3. Redeploy (run migrations first if the schema changed)
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run jobs execute alembic-migrate --region=asia-south1 --wait --quiet
& $gcloud run deploy ai-assistant-backend `
    --image="asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest" `
    --region=asia-south1 --quiet
```

---

### Quick reference — all secrets per environment

| Secret | Update command (stage) | Update command (production) |
|---|---|---|
| Gemini API key | `store-secrets.ps1 -Environment stage -Secret gemini-api-key` | `store-secrets.ps1 -Environment production -Secret gemini-api-key` |
| OpenAI API key | `-Secret openai-api-key` | `-Secret openai-api-key` |
| JWT SECRET_KEY | `-Secret secret-key` | `-Secret secret-key` |
| AES key | `-Secret aes-encryption-key` | `-Secret aes-encryption-key` |
| Database URL | `-Secret database-url` | `-Secret database-url` |
| Redis URL | `-Secret redis-url` | `-Secret redis-url` |
| MinIO access key | `-Secret minio-access-key` | `-Secret minio-access-key` |
| MinIO secret key | `-Secret minio-secret-key` | `-Secret minio-secret-key` |

After any secret update, **always force a new revision** to make it take effect.

---

### GitHub Actions secrets required

Set at **GitHub → Settings → Secrets and Variables → Actions**.

| Secret name | Used by | Description |
|---|---|---|
| `GCP_WIF_PROVIDER` | cloud-run-deploy | Workload Identity Federation provider resource name |
| `GCP_SERVICE_ACCOUNT` | cloud-run-deploy | SA email: `ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com` |
| `CLOUD_RUN_SERVICE` | cloud-run-deploy | `ai-assistant-backend` |
| `CLOUD_RUN_SERVICE_URL` | cloud-run-deploy smoke test | `https://ai-assistant-backend-106071012091.asia-south1.run.app` |
| `CHROMA_SERVICE_NAME` | cloud-run-deploy | `chromadb` |
| `KEYSTORE_BASE64` | android-ci build-signed-apk | Base64-encoded release keystore |
| `KEY_ALIAS` | android-ci | Keystore key alias |
| `KEY_PASSWORD` | android-ci | Keystore key password |
| `STORE_PASSWORD` | android-ci | Keystore store password |
| `GOOGLE_SERVICES_JSON` | android-ci | Base64-encoded `google-services.json` |
| `FIREBASE_APP_ID` | android-ci | Firebase App ID for App Distribution |
| `FIREBASE_SERVICE_ACCOUNT` | android-ci | Firebase service account JSON (base64) |
| `FIREBASE_TESTER_GROUPS` | android-ci | Comma-separated tester group aliases |
| `AES_ENCRYPTION_KEY_CI` | android-ci backend tests | AES key for CI test runs |
| `SLACK_WEBHOOK_URL` | android-ci | Slack notifications (optional) |

| Variable name | Used by | Value |
|---|---|---|
| `GCP_PROJECT_ID` | cloud-run-deploy | `android-ai-assistant-89cec` |
| `GCP_REGION` | cloud-run-deploy | `asia-south1` |
| `GCP_ARTIFACT_REPO` | cloud-run-deploy | `backend` |

---

## Local: no CI/CD involved

The local environment bypasses all of the above completely.

```
Your code change
    ↓
Ctrl+S  (hot-reload via --reload in Docker)
    ↓
FastAPI picks up the change in ~1 second
    ↓
Test in Android app (localDebug variant)
```

No push, no workflow, no deploy needed.
Only when you're satisfied do you push and open a PR.
