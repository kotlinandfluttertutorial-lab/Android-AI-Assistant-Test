# Running the Three Environments

Android AI Assistant — Local · Stage · Production

---

## Quick Reference

| | Local | Stage | Production |
|---|---|---|---|
| **Android variant** | `localDebug` | `stageDebug` | `productionRelease` |
| **App label** | AI Assistant Local | AI Assistant Stage | AI Assistant |
| **UI badge** | Blue `LOCAL` | Amber `STAGE` | *(none)* |
| **Application ID** | `com.aiassistant.local.debug` | `com.aiassistant.stage.debug` | `com.aiassistant` |
| **API URL** | `https://api.handsonandroid.com/` | `https://ai-assistant-backend-stage-*.run.app/` | `https://ai-assistant-backend-106071012091.asia-south1.run.app/` |
| **Backend** | Docker on your machine + Cloudflare tunnel | GCP Cloud Run | GCP Cloud Run |
| **Database** | Docker PostgreSQL (`localhost:5432`) | Neon PostgreSQL (stage branch) | Neon PostgreSQL (main branch) |
| **Redis** | Docker Redis (`localhost:6379`) | Upstash Redis (stage instance) | Upstash Redis (prod instance) |
| **Storage** | MinIO (`localhost:9000`) | GCS `android-ai-assistant-89cec-stage-files` | GCS `android-ai-assistant-89cec-files` |
| **Secrets** | `backend/.env` + `.env.local` | GCP Secret Manager `aiassistant-stage-*` | GCP Secret Manager `aiassistant-prod-*` |
| **Gemini key** | Your local dev key in `.env.local` | `aiassistant-stage-gemini-api-key` | `aiassistant-prod-gemini-api-key` |
| **Cost** | ₹0 | ~₹0 (scales to zero) | ~₹200/month |

---

## Environment 1 — Local

### What it is

Your developer machine running the backend via Docker Compose, exposed publicly
through a **Cloudflare tunnel** at `https://api.handsonandroid.com`.
This replaced the old hardcoded `192.168.0.158:8000` address.

```
Android (localDebug)
    ↓ HTTPS
api.handsonandroid.com
    ↓ Cloudflare tunnel
Your machine :8000
    ↓
Docker Compose
    ├── FastAPI      :8000
    ├── PostgreSQL   :5432
    ├── Redis        :6379
    ├── ChromaDB     :8001
    └── MinIO        :9000 / :9001
```

### Prerequisites

- Docker Desktop installed and running
- `cloudflared` installed and tunnel `mybackend` configured
- `backend/.env` file exists (copy from `backend/.env.example`)
- `.env.local` file exists at the repo root (copy from `.env.local.example`)

### First-time setup

```powershell
# 1. Copy and fill environment files
Copy-Item backend\.env.example backend\.env
# Edit backend\.env — set at minimum:
#   DATABASE_URL=postgresql+asyncpg://aiassistant:aiassistant@postgres:5432/aiassistant
#   REDIS_URL=redis://redis:6379/0
#   SECRET_KEY=<python -c "import secrets; print(secrets.token_hex(32))">
#   AES_ENCRYPTION_KEY=<python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())">
#   GEMINI_API_KEY=<your local dev key from aistudio.google.com>

Copy-Item .env.local.example .env.local
# Edit .env.local — set GEMINI_API_KEY (can be same as above)
```

### Start the server

```powershell
# From the repo root — starts Docker + tunnel in one command
.\start-dev.ps1
```

What this does:
1. Starts Docker Compose (postgres, redis, minio, chromadb, backend, celery_worker)
2. Waits for all services to be healthy
3. Runs Alembic migrations
4. Starts the Cloudflare tunnel → `https://api.handsonandroid.com` goes live

### Stop the server

```powershell
.\stop-dev.ps1
# or
docker compose down
```

### Run the Android app

1. Open **Build Variants** panel → select `localDebug`
2. Press **Run ▶**

The app shows a blue `LOCAL` badge and connects to `https://api.handsonandroid.com`.

### Verify it is working

```powershell
# Health check
curl https://api.handsonandroid.com/health
# Expected: {"status":"ok",...}

# API docs
start https://api.handsonandroid.com/docs
```

### Useful local commands

```powershell
# View logs
docker compose logs -f backend
docker compose logs -f celery_worker

# Rebuild after code changes
docker compose build backend
docker compose up -d backend

# Open a shell in the container
docker compose exec backend bash

# Run migrations manually
docker compose exec backend alembic upgrade head

# Reset everything (DELETES all local data)
docker compose down -v
```

---

## Environment 2 — Stage

### What it is

A complete copy of the production backend running on GCP Cloud Run, connected
to its own isolated Neon database, Upstash Redis, and GCS bucket.
Used for QA, UAT, and testing before production deployments.

```
Android (stageDebug)
    ↓ HTTPS
GCP Cloud Run — ai-assistant-backend-stage
    ├── Neon PostgreSQL (stage branch)
    ├── Upstash Redis   (stage instance)
    └── GCS             android-ai-assistant-89cec-stage-files
         └── Secrets from GCP Secret Manager  aiassistant-stage-*
```

### One-time setup (run once, then it stays running)

**Step 1 — Create Stage database**

1. Go to [neon.tech](https://neon.tech) → your project
2. **Branches** → **New Branch** → name: `stage`
3. Copy the connection string:
   ```
   postgresql+asyncpg://user:pass@ep-xxx-stage.neon.tech/neondb?ssl=require
   ```

**Step 2 — Create Stage Redis**

1. Go to [console.upstash.com](https://console.upstash.com)
2. **Create Database** → name: `ai-assistant-stage`, region: `ap-south-1`
3. Copy the `rediss://` URL from the Connect tab

**Step 3 — Store secrets**

```powershell
# Gemini API key (get a separate key for stage from aistudio.google.com)
$env:GEMINI_API_KEY = "AIza..."
.\scripts\store-secrets.ps1 -Environment stage -Secret gemini-api-key

# Database (Neon stage branch URL from Step 1)
$env:DATABASE_URL = "postgresql+asyncpg://user:pass@ep-xxx.neon.tech/neondb?ssl=require"
.\scripts\store-secrets.ps1 -Environment stage -Secret database-url

# Redis (Upstash URL from Step 2 — must start with rediss://)
$env:REDIS_URL = "rediss://default:pass@host.upstash.io:6379"
.\scripts\store-secrets.ps1 -Environment stage -Secret redis-url

# JWT signing key (generate fresh — never reuse production key)
$env:SECRET_KEY = (python -c "import secrets; print(secrets.token_hex(32))")
.\scripts\store-secrets.ps1 -Environment stage -Secret secret-key

# Encryption key (generate fresh)
$env:AES_ENCRYPTION_KEY = (python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())")
.\scripts\store-secrets.ps1 -Environment stage -Secret aes-encryption-key
```

**Step 4 — Deploy Stage backend**

```powershell
.\scripts\deploy-cloud-run.ps1 -Environment stage
```

This creates Cloud Run service `ai-assistant-backend-stage`.

**Step 5 — Run migrations**

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

& $gcloud run jobs create alembic-migrate-stage `
    --image="asia-south1-docker.pkg.dev/$PROJECT/backend/api:latest" `
    --region=asia-south1 `
    --service-account="ai-assistant-backend@$PROJECT.iam.gserviceaccount.com" `
    --set-secrets="DATABASE_URL=aiassistant-stage-database-url:latest" `
    --command="python","-m","alembic","upgrade","head"

& $gcloud run jobs execute alembic-migrate-stage --region=asia-south1 --wait
```

**Step 6 — Get the Stage URL and update Android**

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run services describe ai-assistant-backend-stage `
    --region=asia-south1 `
    --format="value(status.url)"
# Prints something like:
# https://ai-assistant-backend-stage-XXXXXXXXXX.asia-south1.run.app
```

Update both `app/build.gradle.kts` and `core-network/build.gradle.kts` — replace the
placeholder with the real URL in the `stage` flavor block:

```kotlin
buildConfigField("String", "API_BASE_URL",
    "\"https://ai-assistant-backend-stage-XXXXXXXXXX.asia-south1.run.app/\"")
buildConfigField("String", "WS_BASE_URL",
    "\"wss://ai-assistant-backend-stage-XXXXXXXXXX.asia-south1.run.app\"")
```

### Deploy updates

Stage deploys automatically when code merges to the `develop` branch via CI.

To deploy manually:

```powershell
.\scripts\deploy-cloud-run.ps1 -Environment stage
```

### Run the Android app

1. Open **Build Variants** panel → select `stageDebug`
2. Press **Run ▶**

The app shows an amber `STAGE` badge and connects to the Cloud Run stage URL.

### Verify it is working

```powershell
$STAGE_URL = "https://ai-assistant-backend-stage-XXXXXXXXXX.asia-south1.run.app"
curl "$STAGE_URL/health"
curl "$STAGE_URL/ready"
# Expected: {"status":"ok"} and {"status":"ready","database":"ok","redis":"ok",...}
```

---

## Environment 3 — Production

### What it is

The live backend serving real users. Already running in GCP project
`android-ai-assistant-89cec`.

```
Android (productionRelease)
    ↓ HTTPS
GCP Cloud Run — ai-assistant-backend
https://ai-assistant-backend-106071012091.asia-south1.run.app
    ├── Neon PostgreSQL (main branch)
    ├── Upstash Redis   (prod instance)
    └── GCS             android-ai-assistant-89cec-files
         └── Secrets from GCP Secret Manager  aiassistant-prod-*
```

### Deploy production

Production **never deploys automatically**. Always requires manual confirmation.

```powershell
# Builds the signed APK for production (CI handles this on merge to main)
.\gradlew.bat assembleProductionRelease

# Deploy backend to production Cloud Run
.\scripts\deploy-cloud-run.ps1 -Environment production
# Prompts: Type 'deploy-production' to confirm
```

### Run the Android app

1. Open **Build Variants** panel → select `productionRelease`
2. Press **Run ▶**

The app shows no badge and connects to the production Cloud Run URL.

### Verify it is working

```powershell
$PROD_URL = "https://ai-assistant-backend-106071012091.asia-south1.run.app"
curl "$PROD_URL/health"
curl "$PROD_URL/ready"
```

### Check production logs

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud logging read `
    'resource.type="cloud_run_revision" AND resource.labels.service_name="ai-assistant-backend" AND severity>=ERROR' `
    --limit=50 `
    --project=android-ai-assistant-89cec `
    --format="table(timestamp,textPayload)"
```

Or open the GCP Console → **Logging → Log Explorer**.

### Rollback

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"

# List recent revisions
& $gcloud run revisions list --service=ai-assistant-backend --region=asia-south1

# Route 100% traffic to a previous revision
& $gcloud run services update-traffic ai-assistant-backend `
    --region=asia-south1 `
    --to-revisions=ai-assistant-backend-00004-xyz=100
```

---

## How all three run side by side

All three Android apps can be installed on the same device simultaneously
because each has a different application ID:

```
com.aiassistant.local.debug    ← Local (blue badge)
com.aiassistant.stage.debug    ← Stage (amber badge)
com.aiassistant                ← Production (no badge)
```

---

## CI/CD Flow

```
Your code change
      ↓
git push feature/my-change
      ↓
Pull Request → develop
      ↓ GitHub Actions checks:
        • Android KSP / Hilt gate
        • Unit tests
        • Lint, ktlint, Detekt
        • Backend tests
      ↓ Merged to develop
Auto deploy → Stage (ai-assistant-backend-stage)
      ↓
QA tests on stageDebug
      ↓
Pull Request: develop → main
      ↓ Reviewer approves
Manual deploy → Production
  .\scripts\deploy-cloud-run.ps1 -Environment production
```

---

## Switching environments in Android Studio

1. `View → Tool Windows → Build Variants`
2. Change the `:app` module dropdown:

| Want to test | Select variant |
|---|---|
| Local backend (`api.handsonandroid.com`) | `localDebug` |
| Stage GCP backend | `stageDebug` |
| Production GCP backend | `productionDebug` or `productionRelease` |

3. Press **Run ▶** — the correct APK installs automatically.

---

## Updating a secret without redeploying

Secrets are read at container startup. After updating a secret version in
Secret Manager, trigger a new revision by redeploying:

```powershell
# Stage
.\scripts\deploy-cloud-run.ps1 -Environment stage

# Production
.\scripts\deploy-cloud-run.ps1 -Environment production
```

Or force a new revision with no code change:

```powershell
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run deploy ai-assistant-backend-stage `
    --image="asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest" `
    --region=asia-south1 `
    --project=android-ai-assistant-89cec
```

---

## Troubleshooting

### Local: tunnel not reachable

```powershell
# Check if cloudflared is running
Get-Process cloudflared -ErrorAction SilentlyContinue

# Restart just the tunnel
cloudflared tunnel --config "C:\Users\admin\.cloudflared\config.yml" run mybackend

# Check Docker stack is up first
docker compose ps
```

### Local: backend container keeps restarting

```powershell
docker compose logs backend --tail=50
# Common causes: wrong DATABASE_URL in backend/.env, or missing AES_ENCRYPTION_KEY
```

### Stage/Production: 500 errors on upload

```powershell
# Check GCS storage backend is set
$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run services describe ai-assistant-backend-stage `
    --region=asia-south1 --format="yaml(spec.template.spec.containers[0].env)"
# Must contain: STORAGE_BACKEND=gcs
```

### Stage/Production: Redis connection errors in logs

Means `REDIS_URL` still contains `redis://localhost`. Update the secret with
the Upstash `rediss://` URL and redeploy:

```powershell
$env:REDIS_URL = "rediss://default:PASSWORD@HOST.upstash.io:6379"
.\scripts\store-secrets.ps1 -Environment stage -Secret redis-url
.\scripts\deploy-cloud-run.ps1 -Environment stage
```

### Stage/Production: cold start timeouts (first request is slow)

Normal — Cloud Run scales to zero when idle. The first request after idle
takes 30–60 seconds to start the container. Subsequent requests are fast.
Set `--min-instances=1` in `deploy-cloud-run.ps1` to eliminate cold starts
(adds ~₹200/month).

### Android: wrong server being called

Check the Build Variants panel. The currently selected variant determines the
URL baked into the APK. Look at the badge:
- Blue LOCAL → `api.handsonandroid.com`
- Amber STAGE → Stage Cloud Run
- No badge → Production Cloud Run
