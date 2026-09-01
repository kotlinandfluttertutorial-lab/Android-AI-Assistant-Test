# Cloud Run Deployment Guide — Android AI Assistant
## Budget-First: ₹0–₹1,000/month

> **Audience:** Anyone deploying this project for the first time, or anyone
> debugging a production issue.  Every step is explained — no assumed Cloud Run
> knowledge.
>
> **Project:** `android-ai-assistant-89cec` · Region: `asia-south1`

---

## Table of Contents

1. [Architecture Overview](#1-architecture-overview)
2. [Cost Breakdown](#2-cost-breakdown)
3. [Prerequisites](#3-prerequisites)
4. [One-Time GCP Setup](#4-one-time-gcp-setup)
   - 4.1 Enable APIs
   - 4.2 Create the service account
   - 4.3 Create the GCS bucket
   - 4.4 Configure Secret Manager
   - 4.5 Set up Artifact Registry
   - 4.6 Workload Identity Federation (WIF)
5. [Redis — Upstash Free Tier](#5-redis--upstash-free-tier)
6. [Database — Neon PostgreSQL](#6-database--neon-postgresql)
7. [ChromaDB on Cloud Run](#7-chromadb-on-cloud-run)
8. [Deploy the Backend](#8-deploy-the-backend)
9. [Verify the Deployment](#9-verify-the-deployment)
10. [GitHub Actions Secrets Reference](#10-github-actions-secrets-reference)
11. [Environment Variables Reference](#11-environment-variables-reference)
12. [Troubleshooting](#12-troubleshooting)
    - MinIO / Storage errors
    - Redis connection refused
    - Cold-start timeouts
    - JWT / auth errors
13. [Incident Log — 2026-09-01](#13-incident-log--2026-09-01)

---

## 1. Architecture Overview

```
Android App (OkHttp)
        │
        ▼
Cloud Run — FastAPI backend
  ├── Auth          ← JWT + Google OAuth2
  ├── RAG           ← Document upload → GCS → ChromaDB
  ├── AI Orchestrator ← Gemini (primary) + OpenAI (fallback)
  └── DevOps        ← Error analysis, anomaly detection
        │
        ├── Neon PostgreSQL (serverless, free tier)
        ├── Upstash Redis   (serverless, free tier)
        ├── Google Cloud Storage (file uploads, ~₹2/GB/month)
        └── ChromaDB on Cloud Run (internal, scales to zero)
```

**Why this stack costs ≈ ₹0 at low traffic:**

| Service | Free allowance | Cost above free tier |
|---------|---------------|----------------------|
| Cloud Run | 2M req/month, 360k vCPU-s, 180k GB-s | ~₹0.07/100k req |
| Neon PostgreSQL | 0.5 GB storage, 1 branch | ₹0 on free plan |
| Upstash Redis | 10k commands/day, 256 MB | ₹0 on free plan |
| GCS | 5 GB storage, 1 GB egress/month | ₹2–₹8/GB |
| Artifact Registry | 0.5 GB storage | ₹8/GB above free |
| Cloud Logging | 50 GB/month | ₹0 for this project |

---

## 2. Cost Breakdown

The deploy workflow enforces hard cost caps:

```yaml
--min-instances=0   # scale to zero when idle — no idle billing
--max-instances=2   # hard cap — can't accidentally run 50 instances
--concurrency=40    # one instance handles 40 parallel requests
--cpu=1             # 1 vCPU per instance
--memory=1Gi        # 1 GB RAM per instance
```

At typical student / portfolio usage (< 10k requests/month) the total bill is
**< ₹200/month** — almost entirely GCS storage for uploaded documents.

> **Tip:** Delete documents you no longer need via `DELETE /documents/{id}`.
> The handler removes the file from GCS automatically.

---

## 3. Prerequisites

Install these tools once on your machine:

```powershell
# Google Cloud CLI
winget install Google.CloudSDK

# Authenticate
gcloud auth login
gcloud auth application-default login   # needed for local GCS testing
gcloud config set project android-ai-assistant-89cec
gcloud config set run/region asia-south1
```

Confirm your project:
```powershell
gcloud projects describe android-ai-assistant-89cec
```

---

## 4. One-Time GCP Setup

Run these commands once. Skip any step already done.

### 4.1 Enable Required APIs

```powershell
gcloud services enable `
  run.googleapis.com `
  artifactregistry.googleapis.com `
  storage.googleapis.com `
  secretmanager.googleapis.com `
  iam.googleapis.com `
  iamcredentials.googleapis.com `
  cloudresourcemanager.googleapis.com `
  logging.googleapis.com `
  cloudbuild.googleapis.com
```

### 4.2 Create the Service Account

The Cloud Run service runs as this service account. It needs least-privilege
access to GCS, Secret Manager, and Cloud Logging.

```powershell
$PROJECT = "android-ai-assistant-89cec"
$SA      = "ai-assistant-backend"
$SA_EMAIL = "$SA@$PROJECT.iam.gserviceaccount.com"

# Create
gcloud iam service-accounts create $SA `
  --display-name="AI Assistant Backend"

# Grant roles
gcloud projects add-iam-policy-binding $PROJECT `
  --member="serviceAccount:$SA_EMAIL" `
  --role="roles/storage.objectAdmin"          # GCS read/write

gcloud projects add-iam-policy-binding $PROJECT `
  --member="serviceAccount:$SA_EMAIL" `
  --role="roles/secretmanager.secretAccessor" # read secrets

gcloud projects add-iam-policy-binding $PROJECT `
  --member="serviceAccount:$SA_EMAIL" `
  --role="roles/logging.logWriter"            # structured logs

gcloud projects add-iam-policy-binding $PROJECT `
  --member="serviceAccount:$SA_EMAIL" `
  --role="roles/run.invoker"                  # call internal Cloud Run services
```

### 4.3 Create the GCS Bucket

Documents uploaded by users are stored here instead of MinIO.

```powershell
$BUCKET = "android-ai-assistant-89cec-files"

gcloud storage buckets create gs://$BUCKET `
  --location=asia-south1 `
  --uniform-bucket-level-access `
  --no-public-access-prevention

# Lifecycle rule: auto-delete objects after 365 days (optional cost guard)
gcloud storage buckets update gs://$BUCKET `
  --lifecycle-file=- << '{"rule":[{"action":{"type":"Delete"},"condition":{"age":365}}]}'
```

> **Security note:** The bucket is private. Only the service account above
> (via ADC on Cloud Run) can read/write objects. Users access files only
> through the authenticated API — never with direct bucket URLs.

### 4.4 Configure Secret Manager

All secrets live in Secret Manager — never in env vars or `.env` files on
the server.

```powershell
# Helper function
function Set-GcpSecret {
  param($Name, $Value)
  $existing = gcloud secrets describe $Name 2>$null
  if (-not $existing) {
    Write-Host "Creating secret: $Name"
    echo $Value | gcloud secrets create $Name --data-file=-
  } else {
    Write-Host "Updating secret: $Name"
    echo $Value | gcloud secrets versions add $Name --data-file=-
  }
}

# Required secrets — fill in real values
Set-GcpSecret "DATABASE_URL"       "postgresql+asyncpg://USER:PASS@HOST/DB"
Set-GcpSecret "SECRET_KEY"         (python -c "import secrets; print(secrets.token_hex(32))")
Set-GcpSecret "AES_ENCRYPTION_KEY" (python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())")
Set-GcpSecret "REDIS_URL"          "rediss://default:PASSWORD@HOST:PORT"   # Upstash — see Step 5
Set-GcpSecret "GEMINI_API_KEY"     "your-gemini-api-key"
Set-GcpSecret "OPENAI_API_KEY"     "sk-..."                                 # optional fallback
Set-GcpSecret "ANTHROPIC_API_KEY"  ""                                       # optional
```

> **Upstash Redis URL format:** Always use `rediss://` (double-s = TLS).
> Upstash does not accept `redis://` connections. See [Step 5](#5-redis--upstash-free-tier).

### 4.5 Create Artifact Registry Repository

Docker images are pushed here by the CI pipeline.

```powershell
gcloud artifacts repositories create backend `
  --repository-format=docker `
  --location=asia-south1 `
  --description="AI Assistant backend images"
```

### 4.6 Workload Identity Federation (WIF)

WIF lets GitHub Actions authenticate to GCP without storing a service account
key file anywhere. This is a one-time setup.

```powershell
$PROJECT     = "android-ai-assistant-89cec"
$PROJECT_NUM = (gcloud projects describe $PROJECT --format="value(projectNumber)")
$POOL        = "github-pool"
$PROVIDER    = "github-provider"
$REPO        = "YOUR_GITHUB_USERNAME/YOUR_REPO_NAME"   # ← change this

# Create pool
gcloud iam workload-identity-pools create $POOL `
  --location=global `
  --display-name="GitHub Actions Pool"

# Create provider
gcloud iam workload-identity-pools providers create-oidc $PROVIDER `
  --location=global `
  --workload-identity-pool=$POOL `
  --display-name="GitHub Actions Provider" `
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" `
  --issuer-uri="https://token.actions.githubusercontent.com"

# Bind to service account (allow GitHub repo to impersonate the SA)
gcloud iam service-accounts add-iam-policy-binding $SA_EMAIL `
  --role="roles/iam.workloadIdentityUser" `
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUM/locations/global/workloadIdentityPools/$POOL/attribute.repository/$REPO"

# Print the provider resource name — you need this for GitHub secrets
$PROVIDER_NAME = "projects/$PROJECT_NUM/locations/global/workloadIdentityPools/$POOL/providers/$PROVIDER"
Write-Host "WIF Provider: $PROVIDER_NAME"
```

Add `$PROVIDER_NAME` as the `GCP_WIF_PROVIDER` GitHub secret.

---

## 5. Redis — Upstash Free Tier

Cloud Run containers are stateless and do not have network access to
`localhost`. The `REDIS_URL=redis://localhost:6379` that works in Docker
Compose **does not work on Cloud Run** — it causes the errors you saw in
the logs:

```
Rate limit Redis check failed (fail-open): Error 111 connecting to localhost:6379
Redis revocation check unavailable: Connection refused — skipping
```

**Fix: use Upstash Redis** — serverless, zero infrastructure, free tier covers
this project's usage.

### 5.1 Create a free Upstash Redis instance

1. Go to [console.upstash.com](https://console.upstash.com) → **Create Database**
2. Name: `ai-assistant-prod`
3. Region: **ap-south-1** (Mumbai — closest to `asia-south1`)
4. Type: **Regional** (free tier supports this)
5. Click **Create**

### 5.2 Get the connection URL

In the Upstash dashboard → your database → **Details** tab:

```
UPSTASH_REDIS_REST_URL  → ignore this (REST API, not needed)
```

Click **Connect** → select **redis-cli** tab:

```
rediss://default:<PASSWORD>@<HOST>.upstash.io:6379
```

> **Important:** Copy the `rediss://` URL (with two s's — TLS).  
> Plain `redis://` connections are **rejected** by Upstash.

### 5.3 Update the Secret Manager secret

```powershell
$REDIS_URL = "rediss://default:YOUR_PASSWORD@YOUR_HOST.upstash.io:6379"
echo $REDIS_URL | gcloud secrets versions add REDIS_URL --data-file=-
```

### 5.4 Verify the connection locally

```powershell
# Install redis-cli if needed: winget install Redis.Redis
redis-cli -u "rediss://default:YOUR_PASSWORD@YOUR_HOST.upstash.io:6379" PING
# Expected: PONG
```

### What Redis is used for in this project

| Feature | Key prefix | Impact if Redis down |
|---------|-----------|----------------------|
| Rate limiting | `rate:` / `rate:ip:` | Fail-open — request allowed through |
| JWT revocation | `revoked_jti:` | Fail-open — revoked tokens may work until expiry |
| Account lockout | `auth:attempts:` / `auth:locked:` | Lockout not enforced |
| Celery broker | *(Celery not active on Cloud Run)* | Not affected |

The current behaviour is **fail-open** — Redis being unreachable degrades
gracefully instead of denying all traffic. This is correct for a portfolio
project. For production systems with strict security requirements, change the
fail-open behaviour to fail-closed in `rate_limit.py` and `dependencies.py`.

---

## 6. Database — Neon PostgreSQL

The project uses [Neon](https://neon.tech) serverless PostgreSQL on the free
tier. The connection string format is:

```
postgresql+asyncpg://USER:PASS@HOST.neon.tech/DB?ssl=require
```

> **Neon quirk:** Neon requires `?ssl=require` in the connection string for
> asyncpg. Without it you'll see `SSL connection required` errors.

The `DATABASE_URL` secret in Secret Manager must use this exact format.

### Run migrations after schema changes

Migrations run automatically via the `alembic-migrate` Cloud Run Job in the
deploy workflow. To run them manually:

```powershell
# Locally (against the Neon database)
cd backend
$env:DATABASE_URL = "postgresql+asyncpg://..."
python -m alembic upgrade head
```

---

## 7. ChromaDB on Cloud Run

ChromaDB runs as a separate internal Cloud Run service.

### Deploy ChromaDB

```powershell
gcloud run deploy chromadb `
  --image=chromadb/chroma:1.5.9 `
  --region=asia-south1 `
  --service-account=$SA_EMAIL `
  --min-instances=0 `
  --max-instances=1 `
  --memory=1Gi `
  --cpu=1 `
  --port=8000 `
  --no-allow-unauthenticated `    # internal only — not public
  --ingress=internal
```

The deploy workflow reads the ChromaDB URL automatically:

```yaml
HOST=$(gcloud run services describe chromadb \
  --region=asia-south1 \
  --format="value(status.url)" | sed 's|https://||')
```

This URL is passed to the backend as `CHROMA_HOST`. The backend connects to
ChromaDB using the service account identity (Cloud Run service-to-service auth).

> **Note:** ChromaDB CVEs (CVE-2026-45829 through 45833) affect the HTTP
> server when exposed publicly. This deployment keeps ChromaDB on the internal
> network only — the ingress restriction is the primary mitigation.

---

## 8. Deploy the Backend

The `cloud-run-deploy.yml` workflow deploys automatically on every push to
`main`. To trigger a manual deployment:

1. GitHub → Actions → **cloud-run-deploy** → **Run workflow** → **Run workflow**

Or use gcloud directly for a one-off deploy:

```powershell
$IMAGE = "asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest"

gcloud run deploy ai-assistant-backend `
  --image=$IMAGE `
  --region=asia-south1 `
  --service-account=$SA_EMAIL `
  --min-instances=0 `
  --max-instances=2 `
  --cpu=1 `
  --memory=1Gi `
  --concurrency=40 `
  --port=8000 `
  --allow-unauthenticated `
  --set-secrets="SECRET_KEY=SECRET_KEY:latest,AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:latest,DATABASE_URL=DATABASE_URL:latest,REDIS_URL=REDIS_URL:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest,OPENAI_API_KEY=OPENAI_API_KEY:latest" `
  --set-env-vars="ENVIRONMENT=production,STORAGE_BACKEND=gcs,GCS_BUCKET_NAME=android-ai-assistant-89cec-files,DEFAULT_LLM_PROVIDER=gemini,LLM_FALLBACK_PROVIDER=openai"
```

### Rollback a bad deployment

```powershell
# List recent revisions
gcloud run revisions list --service=ai-assistant-backend --region=asia-south1

# Route 100% traffic to a previous revision
gcloud run services update-traffic ai-assistant-backend `
  --region=asia-south1 `
  --to-revisions=ai-assistant-backend-00004-xyz=100
```

---

## 9. Verify the Deployment

### Health checks

```powershell
$BASE = "https://ai-assistant-backend-106071012091.asia-south1.run.app"

# Liveness — is the process alive?
curl "$BASE/health"
# Expected: {"status":"ok"}

# Readiness — are dependencies reachable?
curl "$BASE/ready"
# Expected: {"status":"ready","database":"ok","redis":"ok",...}
```

### Test document upload (the endpoint that was failing)

```powershell
# 1. Get a token
$TOKEN = (curl -s -X POST "$BASE/auth/login" `
  -H "Content-Type: application/json" `
  -d '{"email":"your@email.com","password":"yourpass"}' | `
  ConvertFrom-Json).access_token

# 2. Upload a test document
curl -X POST "$BASE/documents/upload" `
  -H "Authorization: Bearer $TOKEN" `
  -F "file=@C:\path\to\test.pdf"

# Expected: HTTP 202
# {"document_id":"...","job_id":"...","status":"pending"}
```

If you see HTTP 202 instead of 500, the GCS fix is working.

### Check logs in Cloud Logging

```powershell
# Last 50 error logs for the backend
gcloud logging read `
  'resource.type="cloud_run_revision" AND resource.labels.service_name="ai-assistant-backend" AND severity>=ERROR' `
  --limit=50 `
  --format="table(timestamp,textPayload)" `
  --project=android-ai-assistant-89cec
```

Or open the GCP Console:  
**Logging → Log Explorer → Filter: `resource.type="cloud_run_revision" AND resource.labels.service_name="ai-assistant-backend"`**

---

## 10. GitHub Actions Secrets Reference

Set these at **GitHub → Settings → Secrets and Variables → Actions → Secrets**.

| Secret name | Value | Where to get it |
|-------------|-------|-----------------|
| `GCP_PROJECT_ID` | `android-ai-assistant-89cec` | GCP Console |
| `GCP_REGION` | `asia-south1` | Fixed |
| `GCP_WIF_PROVIDER` | `projects/.../providers/github-provider` | Step 4.6 output |
| `GCP_SERVICE_ACCOUNT` | `ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com` | Step 4.2 |
| `CLOUD_RUN_SERVICE` | `ai-assistant-backend` | Fixed |
| `CLOUD_RUN_SERVICE_URL` | `https://ai-assistant-backend-106071012091.asia-south1.run.app` | After first deploy |
| `CHROMA_SERVICE_NAME` | `chromadb` | Step 7 |

Set these at **GitHub → Settings → Secrets and Variables → Actions → Variables**:

| Variable name | Value |
|---------------|-------|
| `GCP_ARTIFACT_REPO` | `backend` |

---

## 11. Environment Variables Reference

The backend reads configuration from environment variables injected by Cloud
Run from Secret Manager and inline env vars. **Never put secrets in inline
env vars** — use Secret Manager for anything sensitive.

### Secrets (via Secret Manager → `--set-secrets`)

| Variable | Description |
|----------|-------------|
| `DATABASE_URL` | Neon PostgreSQL async URL — `postgresql+asyncpg://...?ssl=require` |
| `REDIS_URL` | Upstash Redis TLS URL — `rediss://default:PASSWORD@HOST:PORT` |
| `SECRET_KEY` | JWT signing key — 64 hex chars minimum |
| `AES_ENCRYPTION_KEY` | AES-256 key for stored secrets — base64 encoded 32 bytes |
| `GEMINI_API_KEY` | Google Gemini API key (primary LLM) |
| `OPENAI_API_KEY` | OpenAI API key (fallback LLM — optional) |
| `ANTHROPIC_API_KEY` | Anthropic API key (optional) |

### Non-secret env vars (via `--set-env-vars`)

| Variable | Production value | Description |
|----------|-----------------|-------------|
| `ENVIRONMENT` | `production` | Enables security-hardened defaults |
| `STORAGE_BACKEND` | `gcs` | Use GCS for file uploads (not MinIO) |
| `GCS_BUCKET_NAME` | `android-ai-assistant-89cec-files` | GCS bucket for documents |
| `CHROMA_HOST` | *(auto from deploy workflow)* | ChromaDB Cloud Run hostname |
| `CHROMA_PORT` | `8001` | ChromaDB HTTP port |
| `DEFAULT_LLM_PROVIDER` | `gemini` | Primary LLM provider |
| `LLM_FALLBACK_PROVIDER` | `openai` | Fallback LLM provider |
| `PROMETHEUS_ENABLED` | `true` | Expose `/metrics` |

### Local development `.env` values

For local Docker Compose, keep your `.env` as:

```ini
STORAGE_BACKEND=minio
MINIO_ENDPOINT=localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET_NAME=documents
REDIS_URL=redis://localhost:6379/0
```

The `STORAGE_BACKEND` switch is what separates local and production — you
never need to change application code when deploying.

---

## 12. Troubleshooting

### MinIO / Storage errors (the root cause of the 2026-09-01 incident)

**Symptom:**
```
MinIO bucket check/create failed: S3 operation failed; code: AccessDenied
MinIO upload failed: S3 operation failed; code: AccessDenied
POST /documents/upload → 500
```

**Root cause:** `STORAGE_BACKEND` was not set, so the code tried to connect to
`MINIO_ENDPOINT=storage.googleapis.com` using the MinIO S3 client with
`secure=False`. GCS rejects S3 requests from an unauthenticated client over
HTTP.

**Fix applied:**
- Added `STORAGE_BACKEND=gcs` env var to Cloud Run.
- Created `storage_service.py` — a proper GCS adapter using ADC (no keys needed).
- `rag_service.py` now delegates to `storage_service` instead of calling MinIO directly.

**Verify fix:**
```powershell
curl -X POST "$BASE/documents/upload" -H "Authorization: Bearer $TOKEN" -F "file=@test.pdf"
# Must return HTTP 202, not 500
```

---

### Redis connection refused

**Symptom:**
```
Rate limit Redis check failed (fail-open): Error 111 connecting to localhost:6379
Redis revocation check unavailable: Connection refused — skipping
```

**Root cause:** `REDIS_URL` secret in Secret Manager still contains
`redis://localhost:6379/0` — a value that works in Docker Compose but is
unreachable on Cloud Run.

**Fix:**
1. Create a free Upstash Redis database (see [Step 5](#5-redis--upstash-free-tier)).
2. Update the secret:
   ```powershell
   echo "rediss://default:PASSWORD@HOST.upstash.io:6379" | `
     gcloud secrets versions add REDIS_URL --data-file=-
   ```
3. Redeploy (the workflow picks up the new secret version automatically on
   the next push, or trigger a manual redeploy from GitHub Actions).

**Impact while unfixed:** Rate limiting and JWT revocation checks are
fail-open — the app continues to serve requests. Account lockout does not
work. This is a **security degradation**, not a service outage.

---

### Cold-start timeouts

**Symptom:** First request after idle period takes 30–60 seconds or returns
a `503 Service Unavailable`.

**Cause:** `min-instances=0` means Cloud Run terminates the container when
idle. The next request waits for a new container to start, download the ML
embedding model (~90 MB), and connect to dependencies.

**Mitigations:**
1. **Increase start-period in health check** — already set to 15s in Dockerfile.
2. **Smoke test retries** — the deploy workflow retries `/health` 8 times with
   15s delay, so cold-starts during CI are handled.
3. **Warm-up endpoint** — the Android app can call `GET /health` before the
   user reaches a screen that needs the backend, hiding the latency.
4. **min-instances=1** — eliminates cold starts but adds ~₹200/month.
   Only consider this when the project has regular real users.

---

### JWT / auth errors

**Symptom:** `401 Unauthorized` or `403 Forbidden` on all requests after a
redeploy.

**Cause A:** `SECRET_KEY` rotated in Secret Manager — existing JWTs signed
with the old key are now invalid. Users need to log in again.

**Cause B:** Clock skew — the `exp` claim is validated against server time.
Cloud Run uses UTC. If the Android device clock is significantly wrong, tokens
may appear expired.

**Fix for Cause A:** Deploy a new revision with the new `SECRET_KEY`. Inform
users that they need to log in again (this is expected behaviour after a key
rotation).

---

### 413 Request Entity Too Large on document upload

**Symptom:** `413` when uploading a large PDF.

**Cause:** `MAX_REQUEST_BODY_SIZE` default is 1 MiB for JSON endpoints.
The upload endpoint is exempt from this limit and uses `MAX_FILE_SIZE_MB`
(default 50 MB) instead. If you see 413 on uploads, check:

1. Is there a load balancer / reverse proxy in front of Cloud Run with its own
   body size limit?
2. Is the client's `Content-Length` correct?

Cloud Run itself has a 32 MB request body limit. Documents larger than 32 MB
must use signed GCS upload URLs — not yet implemented.

---

## 13. Incident Log — 2026-09-01

**Time:** 14:01:06 UTC  
**Endpoint:** `POST /documents/upload`  
**HTTP status:** 500  
**Duration:** ~4 seconds latency before failure

### Timeline

| Time (UTC) | Event |
|-----------|-------|
| 14:01:02 | Request received |
| 14:01:06 | Redis rate limit check fails (fail-open, request continues) |
| 14:01:06 | Redis JWT revocation check fails (fail-open, request continues) |
| 14:01:09 | MinIO bucket check fails: `AccessDenied` |
| 14:01:09 | MinIO upload fails: `AccessDenied` |
| 14:01:10 | Handler returns HTTP 500 |

### Root Causes

1. **Primary (caused 500):** `STORAGE_BACKEND` was not set. The backend
   attempted to connect to `storage.googleapis.com:9000` using the MinIO Python
   client with `secure=False`. GCS rejected the request with `AccessDenied`
   because S3 unsigned HTTP requests are not accepted.

2. **Secondary (degraded security, not 500):** `REDIS_URL` in Secret Manager
   contained `redis://localhost:6379/0`. No Redis is running at localhost in a
   Cloud Run container. Rate limiting and JWT revocation were fail-open and
   silently skipped.

### Fixes Applied

| Fix | File changed | Effect |
|-----|-------------|--------|
| Added `STORAGE_BACKEND`, `GCS_BUCKET_NAME` settings | `settings.py` | Backend now knows which storage to use |
| Created `storage_service.py` | new file | GCS adapter using ADC — no credentials needed on Cloud Run |
| Updated `rag_service.py` | existing file | Delegates storage to `storage_service` instead of raw MinIO client |
| Removed MinIO secrets from deploy workflow | `cloud-run-deploy.yml` | Stops deploying unused/broken MinIO credentials |
| Added `google-cloud-storage==2.18.2` | `requirements.txt` | GCS client library available in the container |
| Update `REDIS_URL` secret | Secret Manager (manual) | Points to Upstash Redis — see Step 5 |

### Prevention

- The `/ready` endpoint now catches GCS connectivity issues and reports them
  before traffic is routed to a new revision.
- The smoke test in CI checks `/ready` after every deploy — a failing GCS
  connection will surface as a warning in the deploy summary.
- Local development uses MinIO via Docker Compose (`STORAGE_BACKEND=minio`),
  which is unchanged. The `STORAGE_BACKEND` setting is the single toggle that
  separates local and production behaviour.
