# Cloud Run Deployment Guide — Android AI Assistant
## Budget-First: ₹0–₹1,000/month

> **One rule:** Don't pay for idle infrastructure.
> Cloud Run bills only when requests are in flight.
> That's the core reason this architecture stays cheap.

---

## Target Architecture

```
Android App (Kotlin + Compose)
  │  HTTPS
  ▼
Cloud Run — FastAPI backend
  │  (scales to zero when unused)
  ├── Neon / Supabase PostgreSQL   ← free tier, no always-on VM
  ├── ChromaDB on Cloud Run        ← same pattern, no managed Redis needed yet
  ├── Cloud Storage                ← PDFs, audio, uploads
  └── OpenAI / Gemini API          ← pay per token, not per hour
```

**What we are NOT deploying yet** (add these only when traffic demands it):

| Skip for now | Reason |
|---|---|
| Cloud SQL | ~₹800/month minimum even idle; use Neon free tier instead |
| Memorystore (Redis) | ~₹2,500/month; skip until Celery is needed |
| Cloud Load Balancer | Cloud Run has HTTPS built-in |
| GKE | Way too complex for a portfolio project |
| Vertex AI | Direct OpenAI/Gemini API calls are cheaper at this scale |
| min-instances = 1 | Keeps an instance warm 24/7; not needed yet |

---

## Budget Breakdown

| Component | Service | Cost |
|---|---|---|
| FastAPI backend | Cloud Run (min=0) | ₹0–₹150 |
| PostgreSQL | Neon free tier | ₹0 |
| Vector store | ChromaDB on Cloud Run | ₹0 (shares compute) |
| File storage | Cloud Storage | ₹0–₹80 |
| Secrets | Secret Manager | ₹0–₹5 |
| Container images | Artifact Registry | ₹0–₹40 |
| CI/CD | GitHub Actions | ₹0 |
| Logs | Cloud Logging | ₹0 (first 50 GB free) |
| LLM | OpenAI / Gemini | ₹200–₹500 (usage) |
| **Total** | | **₹200–₹775/month** |

*LLM cost is the real variable. Set token limits in `.env` — they are already wired to
`LLM_MAX_OUTPUT_TOKENS_OPENAI`, `LLM_MAX_OUTPUT_TOKENS_GEMINI`, etc.*

---

## Prerequisites

```bash
# Install Google Cloud SDK
# Windows: https://cloud.google.com/sdk/docs/install-sdk
gcloud --version   # needs 460+
docker --version   # needs 24+
```

One-time account setup: Google Cloud account with billing enabled.
New accounts get $300 free credits — enough to build the whole portfolio.

---

## Step 1 — GCP Project

```bash
export PROJECT_ID="android-ai-assistant"   # must be globally unique
export REGION="asia-south1"                # Mumbai — lowest latency from India

gcloud projects create $PROJECT_ID --name="Android AI Assistant"
gcloud config set project $PROJECT_ID
gcloud config set run/region $REGION

# Link billing (get your account ID from: gcloud billing accounts list)
gcloud billing projects link $PROJECT_ID --billing-account=YOUR_BILLING_ID
```

---

## Step 2 — Enable APIs (one-time, ~60 seconds)

```bash
gcloud services enable \
  run.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  storage.googleapis.com \
  secretmanager.googleapis.com \
  iam.googleapis.com
```

That's the minimum. No VPC, no networking extras, no SQL admin API yet.

---

## Step 3 — PostgreSQL (Neon Free Tier)

Cloud SQL costs ~₹800/month even on the smallest instance with no traffic.
Use **Neon** instead — it's a serverless PostgreSQL that scales to zero.

1. Go to [neon.tech](https://neon.tech) and create a free account
2. Create a new project → name it `android-ai-assistant`
3. Copy the connection string — it looks like:
   ```
   postgresql+asyncpg://user:password@ep-xxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
   ```
4. This is your `DATABASE_URL` — store it in Secret Manager (Step 5)

**Why Neon:** Free tier gives 0.5 GB storage, 191 compute hours/month, auto-suspends
when idle. Your `app/main.py` already handles async connections via `asyncpg` and
SQLAlchemy — no code changes needed. Alembic migrations run the same way.

**Upgrade path:** When you outgrow the free tier, Neon Pro is ~₹750/month and still
cheaper than Cloud SQL. Cloud SQL is the right call at sustained high traffic.

---

## Step 4 — ChromaDB on Cloud Run

ChromaDB runs as a second Cloud Run service on the same internal network.
It costs ₹0 at zero traffic and shares the same free-tier allocation.

```bash
# Deploy ChromaDB as a Cloud Run service (internal only — not public)
gcloud run deploy chromadb \
  --image=chromadb/chroma:1.5.9 \
  --region=$REGION \
  --port=8001 \
  --memory=512Mi \
  --cpu=1 \
  --min-instances=0 \
  --max-instances=1 \
  --no-allow-unauthenticated \
  --ingress=internal
```

Note the service URL — you'll set it as `CHROMA_HOST` in the backend deploy.
The backend connects to ChromaDB on **port 8001** (`CHROMA_PORT=8001` — the default
in `backend/app/config/settings.py` and `backend/.env.example`).
Cloud Run internal services talk to each other without going to the public internet.

> **Important:** ChromaDB has CVE-2026-45829 (pre-auth RCE). The `--ingress=internal`
> flag means it is only reachable from other Cloud Run services in the same project —
> not from the public internet. This matches the mitigation already in your
> `docker-compose.yml` (port bound to `127.0.0.1` only).
>
> **ChromaDB persistence:** Cloud Run's filesystem is ephemeral. For a portfolio
> project, rebuild the vector index from source documents on each deploy.
> When you need persistence, mount a Cloud Storage FUSE volume or move to a
> managed vector DB. See "Upgrade Path" at the bottom of this guide.

---

## Step 5 — Cloud Storage (replaces MinIO)

```bash
# Create a bucket for documents, audio, and generated files
gsutil mb -l $REGION gs://$PROJECT_ID-files

# Create a dedicated service account for the backend
gcloud iam service-accounts create ai-assistant-backend \
  --display-name="AI Assistant Backend SA"

# Grant it Object Admin on the bucket only (not the whole project)
gsutil iam ch \
  serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com:roles/storage.objectAdmin \
  gs://$PROJECT_ID-files
```

Generate HMAC keys so the existing `minio==7.2.9` SDK works against GCS
without any code changes:

```bash
gsutil hmac create ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com
# Save the Access ID and Secret — you'll add them to Secret Manager next
```

Your `backend/.env.example` uses `MINIO_ENDPOINT`, `MINIO_ACCESS_KEY`,
`MINIO_SECRET_KEY`, and `MINIO_BUCKET_NAME`. On GCS those become:

```
MINIO_ENDPOINT    = storage.googleapis.com
MINIO_ACCESS_KEY  = <HMAC Access ID>
MINIO_SECRET_KEY  = <HMAC Secret>
MINIO_BUCKET_NAME = android-ai-assistant-files
```

---

## Step 6 — Secret Manager

Every value that was in `backend/.env` goes here.
Cloud Run pulls them at startup — no `.env` file on the container, no secrets in the image.

```bash
# Helper — creates a secret and adds the first version
secret() { 
  gcloud secrets create "$1" --replication-policy=automatic 2>/dev/null || true
  printf '%s' "$2" | gcloud secrets versions add "$1" --data-file=-
}

# Required — from app/main.py startup_validation()
secret SECRET_KEY           "$(python -c 'import secrets; print(secrets.token_hex(32))')"
secret AES_ENCRYPTION_KEY   "$(python -c 'import base64,os; print(base64.b64encode(os.urandom(32)).decode())')"
secret DATABASE_URL         "postgresql+asyncpg://user:pass@ep-xxx.neon.tech/neondb?sslmode=require"
secret REDIS_URL            "redis://localhost:6379/0"   # placeholder — Redis skipped for now

# Storage (HMAC keys from Step 5)
secret MINIO_ACCESS_KEY     "your-hmac-access-id"
secret MINIO_SECRET_KEY     "your-hmac-secret"

# LLM (set at least one)
secret OPENAI_API_KEY       "sk-..."
secret GEMINI_API_KEY       "AIza..."
secret ANTHROPIC_API_KEY    "sk-ant-..."   # optional

# Grant the backend service account access to read all secrets
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor"
```

> **About REDIS_URL:** `app/main.py` requires `REDIS_URL` at startup (it's in
> `REQUIRED_ENV_VARS`). Set a placeholder value so the service starts.
> The `RateLimitMiddleware` and `CeleryBroker` will log a connection warning
> but the API routes will still work. Add real Redis only when you enable Celery.

---

## Step 7 — Build and Push the Container

The existing `backend/Dockerfile` works without any modification:
- Multi-stage build → lean production image
- Non-root `appuser` → Cloud Run security requirement satisfied
- Exposes port 8000 → matches Cloud Run default
- `/health` endpoint → liveness probe ready

```bash
# One-time: create the image repository
gcloud artifacts repositories create backend \
  --repository-format=docker \
  --location=$REGION

# Authenticate Docker to push
gcloud auth configure-docker $REGION-docker.pkg.dev

# Build and push (run from project root)
export IMAGE="$REGION-docker.pkg.dev/$PROJECT_ID/backend/api"
export TAG=$(git rev-parse --short HEAD)

docker build \
  --target production \
  --tag $IMAGE:$TAG \
  --tag $IMAGE:latest \
  backend/

docker push $IMAGE:$TAG
docker push $IMAGE:latest

echo "Image: $IMAGE:$TAG"
```

---

## Step 8 — Deploy to Cloud Run

```bash
# Get the ChromaDB internal URL from Step 4
CHROMA_URL=$(gcloud run services describe chromadb \
  --region=$REGION \
  --format="value(status.url)")
# Strip https:// and port for CHROMA_HOST
CHROMA_HOST=$(echo $CHROMA_URL | sed 's|https://||')

gcloud run deploy ai-assistant-backend \
  --image=$IMAGE:$TAG \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com \
  \
  # Cost-control: scale to zero, cap at 2 instances
  --min-instances=0 \
  --max-instances=2 \
  --cpu=1 \
  --memory=1Gi \
  --concurrency=40 \
  \
  # Allow public traffic (Firebase JWT is verified inside the app)
  --allow-unauthenticated \
  \
  # Secrets → env vars
  --set-secrets=\
SECRET_KEY=SECRET_KEY:latest,\
AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:latest,\
DATABASE_URL=DATABASE_URL:latest,\
REDIS_URL=REDIS_URL:latest,\
MINIO_ACCESS_KEY=MINIO_ACCESS_KEY:latest,\
MINIO_SECRET_KEY=MINIO_SECRET_KEY:latest,\
OPENAI_API_KEY=OPENAI_API_KEY:latest,\
GEMINI_API_KEY=GEMINI_API_KEY:latest,\
ANTHROPIC_API_KEY=ANTHROPIC_API_KEY:latest \
  \
  # Non-secret env vars (from backend/.env.example defaults)
  --set-env-vars=\
ENVIRONMENT=production,\
LOG_LEVEL=INFO,\
MINIO_ENDPOINT=storage.googleapis.com,\
MINIO_BUCKET_NAME=$PROJECT_ID-files,\
CHROMA_HOST=$CHROMA_HOST,\
CHROMA_PORT=8001,\
DEFAULT_LLM_PROVIDER=gemini,\
LLM_FALLBACK_PROVIDER=openai,\
LLM_MAX_OUTPUT_TOKENS_OPENAI=2048,\
LLM_MAX_OUTPUT_TOKENS_GEMINI=4096,\
LLM_MAX_OUTPUT_TOKENS_CLAUDE=2048,\
PROMETHEUS_ENABLED=true,\
LOKI_URL=,\
DP_EPSILON=1.0 \
  --port=8000
```

Cloud Run prints the service URL when done:
```
Service [ai-assistant-backend] revision [...] has been deployed
Service URL: https://ai-assistant-backend-xxxx-el.a.run.app
```

Verify immediately:
```bash
export BASE_URL="https://ai-assistant-backend-xxxx-el.a.run.app"

curl $BASE_URL/health
# {"status":"ok"}

curl $BASE_URL/ready
# {"status":"ready","dependencies":{"database":"ok","redis":"..."}}

curl $BASE_URL/docs
# Swagger UI — your full API surface
```

---

## Step 9 — Run Alembic Migrations

```bash
# Run as a one-off Cloud Run Job (exits when done, billed only for execution time)
gcloud run jobs create alembic-migrate \
  --image=$IMAGE:$TAG \
  --region=$REGION \
  --service-account=ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com \
  --set-secrets=DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest \
  --set-env-vars=ENVIRONMENT=production \
  --command="python" \
  --args="-m,alembic,upgrade,head" \
  --max-retries=1

gcloud run jobs execute alembic-migrate --region=$REGION --wait
```

On every schema change (new migration file), update the job and re-run:
```bash
gcloud run jobs update alembic-migrate --image=$IMAGE:$TAG --region=$REGION
gcloud run jobs execute alembic-migrate --region=$REGION --wait
```

---

## Step 10 — Connect the Android App

Find the base URL constant in `core-network`. Search for it:
```bash
# From the project root (Windows PowerShell)
Select-String -Path "core-network\src\**\*.kt" -Pattern "BASE_URL|run\.app|api\."
```

Update it to your Cloud Run URL:
```kotlin
// core-network/src/main/kotlin/.../NetworkConstants.kt
const val BASE_URL = "https://ai-assistant-backend-xxxx-el.a.run.app/api/v1/"
```

**Custom domain (optional but recommended):** Avoids rebuilding the app when
Cloud Run generates a new URL hash.

```bash
gcloud run domain-mappings create \
  --service=ai-assistant-backend \
  --domain=api.handsonandroid.com \
  --region=$REGION
# Follow the CNAME instructions it prints
```

After that, `BASE_URL` stays `https://api.handsonandroid.com/api/v1/` forever.

### Certificate Pin

Your `core-security` module pins the TLS cert SHA-256. Cloud Run's `*.run.app`
certificate rotates. Two options:

**Option A (simpler) — pin the root CA instead of the leaf cert:**
This is more stable because the root CA rarely changes.

**Option B — pin your custom domain cert:**
Use Cloudflare in front of Cloud Run; pin Cloudflare's root CA.
The pin value stays stable across Cloud Run re-deployments.

Update the `BACKEND_TLS_PIN_SHA256` secret in Secret Manager and the
corresponding constant in `core-security` whenever you change the pin.

---

## Step 11 — GitHub Actions CI/CD

Replace the SSH-based `deploy-production` job in `.github/workflows/backend-ci.yml`
with this. Uses Workload Identity Federation — no service account key files stored anywhere.

### 11.1 — Set up Workload Identity (one-time)

```bash
export PROJECT_NUMBER=$(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")

# Create the pool
gcloud iam workload-identity-pools create github-actions \
  --location=global \
  --display-name="GitHub Actions"

# Create the OIDC provider
gcloud iam workload-identity-pools providers create-oidc github \
  --location=global \
  --workload-identity-pool=github-actions \
  --issuer-uri="https://token.actions.githubusercontent.com" \
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository"

# Allow GitHub Actions from your repo to impersonate the backend SA
gcloud iam service-accounts add-iam-policy-binding \
  ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com \
  --role="roles/iam.workloadIdentityUser" \
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/attribute.repository/YOUR_GITHUB_ORG/Android-AI-Assistant-Test"

# Grant deploy permissions to the SA
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/run.developer"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/artifactregistry.writer"

gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser"
```

### 11.2 — GitHub Repository Secrets

Add these four secrets in GitHub → Settings → Secrets → Actions:

| Secret name | Value |
|---|---|
| `GCP_PROJECT_ID` | `android-ai-assistant` |
| `GCP_REGION` | `asia-south1` |
| `GCP_WIF_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/providers/github` |
| `GCP_SERVICE_ACCOUNT` | `ai-assistant-backend@android-ai-assistant.iam.gserviceaccount.com` |

### 11.3 — Deploy job (add to backend-ci.yml)

Add this job after `build-and-push`. It replaces `deploy-staging`, `smoke-test-staging`,
and `deploy-production`:

```yaml
# ──────────────────────────────────────────────────────────────────────────
# Cloud Run Deploy — replaces SSH deploy-staging + deploy-production
# Runs on every push to main or manual workflow_dispatch.
# No SSH secrets, no VM, no docker compose pull on a server.
# ──────────────────────────────────────────────────────────────────────────
deploy-cloud-run:
  name: Deploy to Cloud Run
  runs-on: ubuntu-latest
  needs: build-and-push
  if: >-
    (github.event_name == 'push' && github.ref == 'refs/heads/main') ||
     github.event_name == 'workflow_dispatch'
  environment:
    name: production
    url: https://api.handsonandroid.com

  permissions:
    contents: read
    id-token: write   # required for Workload Identity Federation

  steps:
    - name: Authenticate to Google Cloud
      uses: google-github-actions/auth@v2
      with:
        workload_identity_provider: ${{ secrets.GCP_WIF_PROVIDER }}
        service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}

    - name: Set up gcloud
      uses: google-github-actions/setup-gcloud@v2

    - name: Run Alembic migrations
      run: |
        IMAGE="${{ secrets.GCP_REGION }}-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/backend/api"
        gcloud run jobs update alembic-migrate \
          --image="$IMAGE:sha-${{ github.sha }}" \
          --region=${{ secrets.GCP_REGION }} || \
        gcloud run jobs create alembic-migrate \
          --image="$IMAGE:sha-${{ github.sha }}" \
          --region=${{ secrets.GCP_REGION }} \
          --service-account=${{ secrets.GCP_SERVICE_ACCOUNT }} \
          --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" \
          --set-env-vars="ENVIRONMENT=production" \
          --command="python" \
          --args="-m,alembic,upgrade,head" \
          --max-retries=1
        gcloud run jobs execute alembic-migrate \
          --region=${{ secrets.GCP_REGION }} \
          --wait

    - name: Deploy to Cloud Run
      id: deploy
      uses: google-github-actions/deploy-cloudrun@v2
      with:
        service: ai-assistant-backend
        region: ${{ secrets.GCP_REGION }}
        image: ${{ secrets.GCP_REGION }}-docker.pkg.dev/${{ secrets.GCP_PROJECT_ID }}/backend/api:sha-${{ github.sha }}

    - name: Smoke test
      run: |
        HTTP=$(curl -sSo /dev/null -w "%{http_code}" \
          --retry 5 --retry-delay 8 \
          "${{ steps.deploy.outputs.url }}/health")
        echo "/health → $HTTP  (${{ steps.deploy.outputs.url }})"
        [[ "$HTTP" == "200" ]] || exit 1
        echo "✅ Deployed: ${{ steps.deploy.outputs.url }}"
```

---

## Step 12 — Cost Controls

Set these now to avoid surprise bills.

### Budget alert in GCP console
```bash
# Creates an alert when spend exceeds ₹800 in a month
gcloud billing budgets create \
  --billing-account=YOUR_BILLING_ID \
  --display-name="AI Assistant Budget Alert" \
  --budget-amount=800INR \
  --threshold-rule=percent=0.5 \
  --threshold-rule=percent=0.9 \
  --threshold-rule=percent=1.0
```

### Token limits (already in backend/.env.example)
These cap LLM spend per request — wired to the AI Orchestrator in your code:
```bash
# Update in Secret Manager or as Cloud Run env vars
LLM_MAX_OUTPUT_TOKENS_OPENAI=2048    # was 4096 — halved to cut cost
LLM_MAX_OUTPUT_TOKENS_GEMINI=4096    # Gemini is cheaper, keep higher
LLM_MAX_OUTPUT_TOKENS_CLAUDE=2048    # most expensive — keep low
```

### Cloud Run concurrency
`--concurrency=40` means one instance handles 40 parallel requests before
Cloud Run spins a second instance. For a personal project, 40 is plenty and
prevents over-scaling.

---

## Troubleshooting

### Service starts but /ready returns 503

```bash
curl https://YOUR_URL/ready
# {"status":"unavailable","dependencies":{"database":"unreachable","redis":"..."}}
```

The `/ready` endpoint in `app/main.py` checks both `database` and `redis`.
- `database unreachable` → check `DATABASE_URL` in Secret Manager; confirm
  Neon allows connections from `0.0.0.0/0` (it does by default)
- `redis unreachable` → expected until you add Redis; the API still works

### startup_validation() fails at boot

Check Cloud Run logs:
```bash
gcloud run services logs read ai-assistant-backend --region=$REGION --limit=50
```

Look for `STARTUP_VALIDATION_FAILED` — it logs exactly which env var is missing.
The four required vars are: `SECRET_KEY`, `AES_ENCRYPTION_KEY`, `DATABASE_URL`, `REDIS_URL`.

### ChromaDB returns empty results after redeploy

Cloud Run's filesystem is wiped on each new revision. Trigger a re-ingestion:
```bash
curl -X POST https://YOUR_URL/api/v1/rag/reindex \
  -H "Authorization: Bearer YOUR_JWT"
```

Or add a startup script that re-indexes from Cloud Storage on boot.
This is the right time to think about ChromaDB persistence (see Upgrade Path below).

### Image push fails

```bash
gcloud auth configure-docker $REGION-docker.pkg.dev
gcloud artifacts repositories list --location=$REGION
```

---

## Upgrade Path

Once the project has real users, add these in order:

```
Current (₹0–₹775):
  Cloud Run + Neon + ChromaDB-ephemeral + Cloud Storage + Secret Manager

↓ When you need persistent vectors:
  Add Cloud Run volume mount (Cloud Storage FUSE) for ChromaDB data
  Or migrate to Weaviate Cloud free tier

↓ When you need background jobs (document ingestion, notifications):
  Add Redis — use Upstash (serverless Redis, free 10k requests/day)
  Enable Celery worker as Cloud Run Job (Step 12 of the full guide)

↓ When traffic is sustained (> 100 req/min consistently):
  Migrate from Neon to Cloud SQL (db-f1-micro → db-g1-small)
  Set min-instances=1 on Cloud Run
  Add Memorystore Redis

↓ When you need SLA / HA:
  Cloud SQL HA + read replica
  Global load balancer
  Multi-region Cloud Run
```

---

## Interview Talking Points

This deployment gives you a story that covers the full stack:

- **Android** — Kotlin + Jetpack Compose, Clean Architecture, Hilt, Retrofit, Room
- **Backend** — FastAPI, async SQLAlchemy, Alembic migrations, JWT auth, RAG pipeline
- **AI/GenAI** — Multi-LLM orchestration (OpenAI, Gemini, Claude), ChromaDB vector store,
  sentence-transformers embeddings, RAG with cited answers
- **Cloud** — Google Cloud Run (containerized, auto-scaling), Cloud Storage, Secret Manager,
  Artifact Registry, Workload Identity Federation (no long-lived keys)
- **DevOps** — GitHub Actions CI/CD, Docker multi-stage build, Alembic as Cloud Run Job,
  smoke tests after every deploy
- **Security** — JWT + bcrypt, AES-256 encryption at rest, certificate pinning, Secret Manager,
  no credentials in code or images, WIF instead of service account keys

The ₹1,000/month budget constraint is itself an interview answer:
*"I deliberately chose Neon over Cloud SQL and avoided Memorystore until the project
needed it — the infrastructure scales with the product, not ahead of it."*
