# Google Cloud — Phase 6 Learning Guide

A thorough walkthrough of the GCP services used by this project: what each
service does, why it was chosen, how the pieces connect, and how to operate
them safely.

---

## Table of Contents

1. [Why GCP?](#1-why-gcp)
2. [Architecture Overview](#2-architecture-overview)
3. [Cloud Run — Serverless Containers](#3-cloud-run--serverless-containers)
4. [Artifact Registry — Docker Image Store](#4-artifact-registry--docker-image-store)
5. [IAM & Service Accounts](#5-iam--service-accounts)
6. [Secret Manager](#6-secret-manager)
7. [Cloud Storage (GCS)](#7-cloud-storage-gcs)
8. [Cloud Logging & Monitoring](#8-cloud-logging--monitoring)
9. [Workload Identity Federation](#9-workload-identity-federation)
10. [Cost Architecture](#10-cost-architecture)
11. [Deployment Pipeline End-to-End](#11-deployment-pipeline-end-to-end)
12. [Key Design Decisions & Trade-offs](#12-key-design-decisions--trade-offs)
13. [Interview Questions](#13-interview-questions)
14. [Exercises](#14-exercises)

---

## 1. Why GCP?

This project uses Google Cloud Platform for several reasons that map directly
to your Android background:

| Reason | Details |
|---|---|
| **Firebase integration** | The Android app already uses Firebase (FCM, Crashlytics). Firebase is a GCP product — the same service account can access both. |
| **Gemini API** | The primary LLM provider is Google Gemini. Keeping the backend on GCP reduces cross-provider latency. |
| **Free tier generosity** | Cloud Run gives 2 million free requests/month. Neon PostgreSQL and Upstash Redis cover zero-cost database + cache. |
| **Android developer familiarity** | You already authenticate with `gcloud` for Android Studio's Play Services. The same toolchain applies. |
| **Cloud Run simplicity** | No Kubernetes, no node pools, no ingress controllers. Deploy a container and it runs. |

---

## 2. Architecture Overview

```
GitHub Push → main
        │
        ▼
GitHub Actions (cloud-run-deploy.yml)
        │
        ├── 1. Authenticate via WIF (no key file)
        │
        ├── 2. docker build → Artifact Registry
        │          asia-south1-docker.pkg.dev/PROJECT/backend/api:sha-ABC
        │
        ├── 3. Cloud Run Job: alembic upgrade head (Neon PostgreSQL)
        │
        ├── 4. Cloud Run Service: ai-assistant-backend (min=0, max=2)
        │         Secrets from Secret Manager
        │         Files in GCS (documents, audio)
        │         Vector data in ChromaDB Cloud Run (internal)
        │
        ├── 5. Cloud Run Service: ai-assistant-worker (min=1, max=1, APP_MODE=worker)
        │         Celery tasks: ingestion, notifications, GDPR
        │         Broker: Upstash Redis (TLS)
        │
        └── 6. Smoke tests: /health + /ready
```

### Services used

| GCP Service | Purpose |
|---|---|
| **Cloud Run** | Run the FastAPI container and Celery worker without managing VMs |
| **Artifact Registry** | Store and version Docker images |
| **Secret Manager** | Store API keys and credentials — never in env vars or code |
| **Cloud Storage** | Store uploaded documents, audio, and generated files |
| **Cloud Logging** | Centralised structured log storage and search |
| **Cloud Monitoring** | Metrics, dashboards, and uptime alerting |
| **IAM** | Fine-grained access control — who can do what |
| **Workload Identity Federation** | Let GitHub Actions authenticate to GCP without a key file |

### External services (not GCP but essential)

| Service | Why not a GCP service? |
|---|---|
| **Neon PostgreSQL** | GCP's Cloud SQL is ~$30/month minimum. Neon free tier is $0. |
| **Upstash Redis** | GCP's Memorystore is ~$40/month minimum. Upstash free tier is $0. |

---

## 3. Cloud Run — Serverless Containers

Cloud Run is the core of this project. It runs Docker containers without any
server management — no VMs, no Kubernetes, no load balancers to configure.

### How Cloud Run works

```
1. You push a Docker image to Artifact Registry
2. You deploy that image to Cloud Run
3. Cloud Run creates a Cloud Run Service (ai-assistant-backend)
4. Each HTTP request starts a container instance (if none are running)
5. Cloud Run scales instances up/down automatically
6. When idle (no requests), instances scale to zero
```

### Revision model

Every `gcloud run deploy` creates a new **revision** — an immutable snapshot
of the service at that point in time:

```
ai-assistant-backend-00001-abc  ← old revision (0% traffic)
ai-assistant-backend-00002-xyz  ← old revision (0% traffic)
ai-assistant-backend-00003-def  ← current revision (100% traffic)
```

Traffic routing between revisions is explicit — you control the percentages.
This enables **canary deployments**:

```bash
# Route 10% of traffic to the new revision for a canary test
gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=ai-assistant-backend-00004-new=10,LATEST=90
```

### Rollback in 30 seconds

```bash
# List revisions
gcloud run revisions list \
  --service=ai-assistant-backend \
  --region=asia-south1

# Route all traffic back to a previous revision
gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=ai-assistant-backend-00003-def=100
```

This is one of Cloud Run's biggest advantages over traditional VMs — rollback
is instant and doesn't require rebuilding anything.

### Concurrency model

```
# This project's settings:
--concurrency=40    # one container handles 40 parallel requests
--max-instances=2   # at most 2 containers run simultaneously
```

Maximum simultaneous requests = `concurrency × max_instances` = 40 × 2 = 80.

If request 81 arrives while all instances are at capacity, Cloud Run queues
it briefly. If demand sustains above capacity, a new instance starts (cold start).

For this project, 80 concurrent users is more than sufficient. For a production
system with thousands of users, increase both values.

### `startup_cpu_boost`

```hcl
# terraform/modules/cloud_run/main.tf
resources {
  startup_cpu_boost = true
}
```

During a cold start, Cloud Run normally gives the container its allocated CPU.
`startup_cpu_boost = true` gives the container **extra CPU during startup only**,
reducing cold-start time from ~30 seconds to ~15 seconds. After the instance
is handling requests, CPU drops back to the allocated amount.

### `cpu_idle = true` vs `cpu_idle = false`

| Setting | Behaviour | Cost |
|---|---|---|
| `cpu_idle = true` (default) | CPU throttled when not handling a request | Cheaper; CPU only counted during request handling |
| `cpu_idle = false` | CPU always allocated, even between requests | More expensive; needed for background processing |

The Celery worker uses `cpu_idle = false` (via `--no-cpu-throttling` in the deploy
workflow) because it processes background tasks between requests and needs the CPU
available at all times.

### Cloud Run Jobs (Alembic migrations)

The deploy pipeline runs Alembic as a **Cloud Run Job**, not a Service:

```yaml
# cloud-run-deploy.yml
gcloud run jobs create alembic-migrate \
  --image="$IMAGE" \
  --command="python" \
  --args="-m,alembic,upgrade,head" \
  --max-retries=1
```

A **Job** runs a container to completion and exits — perfect for one-off tasks
like database migrations. You're only billed for the actual execution time
(typically 5-30 seconds for Alembic).

A **Service** runs continuously and handles HTTP requests.

---

## 4. Artifact Registry — Docker Image Store

Artifact Registry stores versioned Docker images. Every CI build pushes a new
image tagged with the git commit SHA:

```
asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-abc123
asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-def456
asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest
```

### Why SHA tags instead of `:latest`

```bash
# Risky — :latest can be overwritten; you don't know which code is running
docker pull myimage:latest

# Safe — sha-abc123 always refers to exactly this code snapshot
docker pull myimage:sha-abc123
```

The deploy workflow pins to a SHA tag so rollbacks are deterministic:

```yaml
# cloud-run-deploy.yml
export BACKEND_IMAGE="$REPO/api:sha-${{ github.sha }}"
```

If a bad release is deployed, the rollback command re-deploys the previous
SHA tag — no rebuild needed.

### Image signing with cosign

```yaml
# .github/workflows/backend-ci.yml
- name: Sign image with cosign (keyless OIDC)
  run: cosign sign --yes "$IMAGE@$DIGEST"
```

The image signature is published to the Sigstore Rekor transparency log.
Anyone can verify the image was built by this repository's CI pipeline:

```bash
cosign verify \
  --certificate-identity-regexp "https://github.com/your-org/your-repo" \
  --certificate-oidc-issuer "https://token.actions.githubusercontent.com" \
  asia-south1-docker.pkg.dev/PROJECT/backend/api:latest
```

This is a supply chain security control — it proves the image in production
is exactly what CI built from a known commit.

### Cleanup policy

Without a cleanup policy, old image layers accumulate and you pay for storage.
The `terraform/modules/artifact_registry/main.tf` sets
`immutable_tags = false` for development. In production, switch to SHA-only tags
and add a lifecycle cleanup policy to delete images older than 30 days.

---

## 5. IAM & Service Accounts

IAM (Identity and Access Management) controls who can do what in GCP.

### The principle of least privilege

```
❌ Dangerous: roles/owner
   → Can do everything including delete the project

❌ Still too broad: roles/editor
   → Can create/modify almost all resources

✅ Correct: specific roles only
   → roles/run.developer           (deploy Cloud Run services)
   → roles/secretmanager.secretAccessor (read secrets at runtime)
   → roles/logging.logWriter       (write logs)
   → roles/storage.objectAdmin     (on the specific bucket only)
```

The backend service account has exactly the roles it needs — nothing more.
This limits the blast radius if the account is ever compromised.

### Service accounts vs user accounts

| Account type | Use case |
|---|---|
| **User account** (`you@gmail.com`) | You authenticating with `gcloud auth login` |
| **Service account** (`name@PROJECT.iam.gserviceaccount.com`) | Applications and CI pipelines authenticating programmatically |

Cloud Run services run as a service account. The service account identity is
what grants the container permission to read secrets, write to GCS, and call
internal Cloud Run services.

### Role binding scope matters

```bash
# Project-level binding — applies to ALL resources in the project
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin"    # can access ALL buckets

# Bucket-level binding — applies to ONE bucket only
gcloud storage buckets add-iam-policy-binding gs://$BUCKET \
  --member="serviceAccount:$SA_EMAIL" \
  --role="roles/storage.objectAdmin"    # can only access this bucket
```

The storage Terraform module uses **bucket-level** IAM, not project-level.
This is stricter — the backend SA can only access the documents bucket, not
every bucket in the project.

---

## 6. Secret Manager

Secret Manager is GCP's encrypted key-value store for sensitive configuration.

### Why not environment variables directly?

```yaml
# ❌ Anti-pattern — secret in plain text env var:
env_vars: "DATABASE_URL=postgresql://user:REAL_PASSWORD@host/db"

# ✅ Correct — reference to a secret:
set-secrets: "DATABASE_URL=DATABASE_URL:latest"
```

Storing secrets as plain env vars means:
- They appear in deployment configs (Cloud Run YAML, Terraform state)
- They may be logged if the application accidentally logs its environment
- Rotating a secret requires a new deployment

With Secret Manager:
- Secrets are encrypted at rest (AES-256) and in transit
- Access is controlled by IAM — the service account needs `roles/secretmanager.secretAccessor`
- Rotating a secret creates a new version; Cloud Run can be configured to use `latest` automatically
- Every secret access is logged in Cloud Audit Logs

### Secret versioning

```bash
# Current versions of a secret
gcloud secrets versions list DATABASE_URL

# Rotate a secret (creates version 2, 3, etc.)
echo "postgresql://user:NEW_PASSWORD@host/db" | \
  gcloud secrets versions add DATABASE_URL --data-file=-

# Cloud Run services reference "latest" so they always use the newest version
# --set-secrets="DATABASE_URL=DATABASE_URL:latest"

# To pin to a specific version (for controlled rollout):
# --set-secrets="DATABASE_URL=DATABASE_URL:3"
```

### How Cloud Run accesses secrets

```hcl
# terraform/modules/cloud_run/main.tf
env {
  name = "DATABASE_URL"
  value_source {
    secret_key_ref {
      secret  = "DATABASE_URL"
      version = "latest"
    }
  }
}
```

At container startup, Cloud Run calls the Secret Manager API using the service
account identity to fetch the secret value. The container sees it as a regular
environment variable. If the service account lacks `secretAccessor` permission,
the container fails to start.

### Never put secrets in `.tfvars` files

```hcl
# ❌ terraform.tfvars — NEVER do this
database_password = "realpassword123"

# ✅ Correct — secrets in Secret Manager, non-secrets in tfvars
# terraform.tfvars only contains:
project_id = "android-ai-assistant-89cec"
region     = "asia-south1"
environment = "prod"
```

The `terraform/environments/dev/terraform.tfvars` and `prod/terraform.tfvars`
files only contain non-sensitive values (project IDs, regions, instance counts).
Secrets are pre-created in Secret Manager before Terraform runs.

---

## 7. Cloud Storage (GCS)

Cloud Storage stores user-uploaded documents, audio recordings, and generated
files. The backend uses GCS instead of MinIO in production for several reasons:

| Aspect | MinIO (local) | GCS (production) |
|---|---|---|
| Durability | Single disk in Docker | 11 nines (10x failover) |
| Cost | Docker volume | ~$0.02/GB/month |
| Scaling | Limited by one machine | Unlimited |
| Access control | MinIO credentials | GCP IAM |
| Managed | You manage the process | Google manages everything |

### How the backend switches between MinIO and GCS

```python
# backend/app/config/settings.py
STORAGE_BACKEND: str = Field(default="minio")

# backend/app/services/storage_service.py
if settings.STORAGE_BACKEND == "gcs":
    client = storage.Client()          # uses Application Default Credentials
    bucket = client.bucket(bucket_name)
    blob = bucket.blob(object_path)
    blob.upload_from_file(file_obj)
else:
    minio_client = Minio(endpoint, access_key, secret_key)
    minio_client.put_object(bucket, path, file_obj, length)
```

The `STORAGE_BACKEND=gcs` environment variable in Cloud Run switches the
backend to GCS without any code changes. Local development still uses MinIO
via Docker Compose.

### Application Default Credentials (ADC)

On Cloud Run, the service account identity is automatically available as ADC.
The GCS client library reads it without any explicit credentials:

```python
# No credentials needed in production code!
from google.cloud import storage
client = storage.Client()  # reads from ADC (Cloud Run service account)
```

This is why there is no `GOOGLE_APPLICATION_CREDENTIALS` environment variable
in the Cloud Run config — it's not needed when running on GCP infrastructure.

### Bucket security

```hcl
# terraform/modules/storage/main.tf
public_access_prevention    = "enforced"  # no public objects ever
uniform_bucket_level_access = true        # IAM only, no per-object ACLs
```

`public_access_prevention = "enforced"` is a hard guarantee — even if code
accidentally tries to make an object public, GCP blocks it. There is no way
to expose a document without explicitly removing this setting.

Users access their uploaded files only through the authenticated API — the
backend fetches the file from GCS and streams it to the user. There are no
direct bucket URLs in the responses.

### HMAC keys for S3 compatibility

```hcl
# terraform/modules/storage/main.tf
resource "google_storage_hmac_key" "backend" {
  project               = var.project_id
  service_account_email = var.backend_sa_email
}
```

GCS supports the S3-compatible XML API when you authenticate with HMAC keys.
The HMAC access ID and secret are stored in Secret Manager as `MINIO_ACCESS_KEY`
and `MINIO_SECRET_KEY`. The backend's existing MinIO SDK code can then talk to
GCS without any changes:

```
MINIO_ENDPOINT=storage.googleapis.com
MINIO_ACCESS_KEY=<HMAC access ID from Secret Manager>
MINIO_SECRET_KEY=<HMAC secret from Secret Manager>
```

This compatibility layer is what allowed the storage migration from MinIO to
GCS without rewriting the upload/download code.

---

## 8. Cloud Logging & Monitoring

### Structured logging

The backend emits JSON-structured logs (configured in `app/observability/logging_setup.py`).
Every log line contains:

```json
{
  "severity": "ERROR",
  "message": "JWT verification failed",
  "correlation_id": "abc-123-def",
  "user_id": "uuid-here",
  "path": "/auth/login",
  "method": "POST",
  "status_code": 401,
  "response_time_ms": 45
}
```

Cloud Logging automatically indexes these fields — you can search by any field:

```bash
# Find all 500 errors in the last hour
gcloud logging read \
  'resource.type="cloud_run_revision" AND httpRequest.status=500' \
  --limit=50 \
  --format="table(timestamp,jsonPayload.correlation_id,jsonPayload.message)"
```

### Correlation IDs

Every request gets a UUID `correlation_id` from `RequestLoggingMiddleware`.
This ID also sets the `X-Correlation-ID` response header. If a user reports
a problem, you can give them the header value and search for it in Cloud Logging
to find every log line from that specific request.

### Cloud Monitoring alerts

```bash
# Example: create an uptime check for the /health endpoint
gcloud monitoring uptime-checks create http \
  --display-name="Backend Health" \
  --uri="https://ai-assistant-backend-PROJECT.run.app/health" \
  --period=60

# If /health returns non-200 twice in a row, Cloud Monitoring fires an alert
# (configure via Console: Monitoring → Alerting → Create Policy)
```

### Prometheus metrics at `/metrics`

The backend exposes Prometheus-format metrics at `/metrics`:

```
http_requests_total{method="POST",handler="/chat/message",status="200"} 1234
http_request_duration_seconds_bucket{le="0.5"} 1150
http_request_duration_seconds_bucket{le="2.0"} 1230
```

These can be scraped by a Prometheus instance (local development) or by
Cloud Monitoring's managed Prometheus service.

---

## 9. Workload Identity Federation

WIF is one of the most important security controls in this project. It lets
GitHub Actions authenticate to GCP without storing a service account key file.

### The old way (dangerous)

```
1. Create service account key → downloads a JSON file
2. Base64-encode the JSON file
3. Store as a GitHub secret: GCP_SA_KEY
4. In CI: echo "$GCP_SA_KEY" | base64 -d > /tmp/key.json
5. gcloud auth activate-service-account --key-file=/tmp/key.json
```

**Problems:**
- The JSON key is valid until manually revoked — it never expires
- If the key leaks (accidental commit, compromised runner), attackers have
  persistent GCP access
- Key rotation is a manual, error-prone process

### The WIF way (safe)

```
1. GitHub generates a short-lived OIDC token for the workflow run
   → "I am workflow run #12345 on branch main of repo org/repo"
2. GCP verifies the token against GitHub's OIDC endpoint
3. GCP issues a short-lived access token (expires in 1 hour)
4. The workflow uses that access token to push Docker images, deploy, etc.
```

**Benefits:**
- No credentials stored anywhere — nothing to leak
- Tokens expire in 1 hour — a compromised token becomes useless quickly
- The WIF binding is scoped to a specific repository — other repos cannot
  impersonate your service account

### How it works in `cloud-run-deploy.yml`

```yaml
# .github/workflows/cloud-run-deploy.yml
- uses: google-github-actions/auth@v2
  with:
    workload_identity_provider: ${{ secrets.GCP_WIF_PROVIDER }}
    service_account: ${{ secrets.GCP_SERVICE_ACCOUNT }}
```

The `GCP_WIF_PROVIDER` is not a secret in the traditional sense — it's a
resource path like `projects/106071012091/locations/global/workloadIdentityPools/github-actions/providers/github`.
It's marked as a GitHub secret to keep configuration centralised, not for
security reasons.

### The attribute condition

```hcl
# terraform/modules/iam/main.tf
attribute_condition = "assertion.repository_owner == '${var.github_org}'"
```

This condition ensures only workflows from your GitHub organisation can exchange
tokens. Without it, any public GitHub repository could try to impersonate your
service account (they'd be blocked by GCP's OIDC verification, but the condition
adds defence-in-depth).

---

## 10. Cost Architecture

The project is designed to be free at low traffic and cheap at medium traffic.

### The zero-cost baseline

```
Monthly requests: < 2,000,000
Monthly CPU:      < 360,000 vCPU-seconds
Monthly memory:   < 180,000 GB-seconds
```

At student/portfolio usage (< 10,000 requests/month), this project's Cloud Run
bill is **exactly ₹0**.

### What costs money

| Service | When you pay | Approximate cost |
|---|---|---|
| Cloud Run | Above the free tier | ~₹0.07 per 100k requests |
| GCS | Storage + egress | ~₹1.60/GB stored/month |
| Artifact Registry | Above 0.5 GB stored | ~₹8/GB |
| Neon PostgreSQL | Above 0.5 GB storage | Free tier is generous |
| Upstash Redis | Above 10k commands/day | Free tier covers portfolio usage |

### Cost controls enforced by Terraform

```hcl
# terraform/modules/cloud_run/main.tf
scaling {
  min_instance_count = var.backend_min   # 0 = scale to zero when idle
  max_instance_count = var.backend_max   # 2 = hard cap, can't accidentally run 50
}
```

`min_instances = 0` means no idle billing. `max_instances = 2` is a hard guard
against runaway scaling from a traffic spike or a DoS attack.

### Setting up a budget alert

```bash
# Create a budget alert at $10/month — email when 80% is reached
gcloud billing budgets create \
  --billing-account=BILLING_ACCOUNT_ID \
  --display-name="AI Assistant Monthly Budget" \
  --budget-amount=10 \
  --threshold-rule=percent=0.8
```

This sends an email warning before any significant charges accumulate.

---

## 11. Deployment Pipeline End-to-End

Tracing a single push to `main` through the entire GCP deployment:

```
Developer: git push origin main
        │
        ▼
GitHub Actions triggers cloud-run-deploy.yml
        │
        ▼
Step 1: Authenticate (WIF)
  github-actions/auth → exchanges OIDC token → short-lived GCP token
        │
        ▼
Step 2: Build & Push image
  docker build → linux/amd64 → NO CACHE (ensures chromadb installed fresh)
  docker push → asia-south1-docker.pkg.dev/PROJECT/backend/api:sha-ABC123
  Stored in Artifact Registry with immutable SHA tag
        │
        ▼
Step 3: Alembic migrations (Cloud Run Job)
  gcloud run jobs update alembic-migrate --image=sha-ABC123
  gcloud run jobs execute alembic-migrate --wait
  → python -m alembic upgrade head
  → connects to Neon PostgreSQL via DATABASE_URL from Secret Manager
  → applies any pending migrations (e.g. migration 0015)
  → exits with code 0 on success
        │
        ▼
Step 4a: Deploy API (Cloud Run Service)
  gcloud run deploy ai-assistant-backend
    --image=sha-ABC123
    --set-secrets=SECRET_KEY=SECRET_KEY:latest,...  (7 secrets from Secret Manager)
    --set-env-vars=ENVIRONMENT=production,...        (non-sensitive config)
  Cloud Run:
    → creates new revision: ai-assistant-backend-00007-xyz
    → waits for startup probe /health to return 200
    → routes 100% traffic to new revision
    → old revision (00006-abc) receives 0% traffic
        │
        ▼
Step 4b: Deploy Celery Worker (Cloud Run Service, parallel with 4a)
  gcloud run deploy ai-assistant-worker
    --env-vars APP_MODE=worker
    --min-instances=1    (always warm — no cold start delay for background tasks)
    --no-cpu-throttling  (CPU available between tasks)
        │
        ▼
Step 5: Smoke test
  Resolve service URL from deploy output or CLOUD_RUN_SERVICE_URL secret
  Wait 30s for revision to warm up
  curl /health (retry 8 times, 15s delay) → must return 200
  curl /ready  → checks DB + Redis + env vars (warning if Redis absent)
        │
        ▼
Deployment complete → summary written to GitHub Actions step summary
```

### What happens if a migration fails

```
Step 3 exits with non-zero code
    ↓
Cloud Run Job marks execution as FAILED
    ↓
GitHub Actions marks step as failed
    ↓
Workflow fails before Step 4 (deploy)
    ↓
Old revision continues to serve 100% of traffic
```

The migration job failing is a safe guard — the deploy step never runs if
Alembic fails. The old code continues to work with the old schema. No partial
state.

---

## 12. Key Design Decisions & Trade-offs

### 12.1 Cloud Run over GKE (Kubernetes)

**Cloud Run benefits:**
- Zero cluster management (no node pools, no upgrades, no etcd)
- Scales to zero — no idle cost
- Revisions and traffic splitting built-in
- Managed TLS, load balancing, and autoscaling

**Cloud Run limitations:**
- Max 60-minute request timeout
- Stateless — no persistent filesystem (ChromaDB data is ephemeral)
- Single process per container (no sidecars without Cloud Run jobs)

**The right trade-off:** For a portfolio project or early-stage startup, Cloud
Run is the correct choice. GKE makes sense when you need stateful workloads,
complex service mesh, or multi-container pods with shared state.

### 12.2 Neon + Upstash instead of Cloud SQL + Memorystore

| Service | Cloud SQL | Neon |
|---|---|---|
| Minimum cost | ~$30/month | $0/month |
| Serverless | No (always-on) | Yes (scales to zero) |
| Connection pooling | You configure pgBouncer | Built-in |
| Branching | No | Yes (instant read replicas) |

For a project where cost discipline is explicitly stated in the requirements
("₹0–₹1,000/month budget"), Neon + Upstash are the correct choices.

### 12.3 ChromaDB on Cloud Run over managed vector database

Managed vector databases (Pinecone, Weaviate Cloud, AlloyDB pgvector) cost
$20-100+/month. ChromaDB on Cloud Run costs ~₹50/month at zero traffic
(min-instances=0).

The limitation: ChromaDB's container filesystem is ephemeral — the vector
index is wiped on each new revision. The `lifespan()` in `main.py` re-seeds
from the `knowledge/` directory on every startup (120-second cap). This adds
cold-start time but costs nothing.

### 12.4 GCS with HMAC keys instead of ADC for MinIO compatibility

The backend's document storage code uses the MinIO Python SDK. Replacing it with
the native GCS client library would have required changing ~200 lines of service
code, adding risk and delay.

The HMAC key approach (GCS HMAC keys + MinIO SDK pointing at `storage.googleapis.com`)
let the existing code work on GCS unchanged. The MinIO SDK doesn't know or care
whether the endpoint is MinIO or GCS — it just speaks S3.

Trade-off: HMAC keys are less secure than ADC (they don't expire automatically
and require manual rotation). A future improvement is to replace the MinIO SDK
with the native GCS client and remove HMAC keys entirely.

### 12.5 `min-instances=1` for the Celery worker

The API uses `min-instances=0` (scale to zero). The Celery worker uses
`min-instances=1` (always one instance running).

The reason: Celery workers pull tasks from Redis. A cold-start delay on the
worker means background tasks queue up and aren't processed until the worker
wakes up. For document ingestion (which users are waiting for), a 30-60 second
delay would be very noticeable. Keeping one worker instance always warm costs
~₹150/month on Cloud Run but delivers instant task processing.

---

## 13. Interview Questions

**1. What is Cloud Run and when would you choose it over GKE (Kubernetes)?**

Cloud Run is a fully managed serverless container platform — you push a Docker
image and Cloud Run handles scaling, load balancing, TLS, and health monitoring.
You choose Cloud Run when your workloads are stateless HTTP services or background
jobs, when cost matters (scale to zero means no idle billing), and when you want
to avoid cluster management overhead. You choose GKE when you need stateful
workloads, complex networking between services, multi-container pods, or when you
need more control over the runtime environment.

**2. What is the principle of least privilege and how is it applied here?**

Least privilege means granting exactly the permissions required — nothing more.
The backend service account has `roles/secretmanager.secretAccessor` to read
secrets, `roles/run.invoker` to call internal Cloud Run services, and
`roles/storage.objectAdmin` scoped to a specific bucket — not the whole project.
It does not have `roles/owner` or `roles/editor`. If the service account is
compromised, an attacker can only read secrets, access that one bucket, and call
internal services — they cannot delete resources, access other buckets, or modify
IAM policies.

**3. Explain the Cloud Run revision model. How does it enable zero-downtime deployments?**

Every `gcloud run deploy` creates a new revision — an immutable snapshot of the
service. Traffic routing between revisions is explicit. During a deploy, Cloud Run
starts the new revision, waits for the startup probe to succeed, then atomically
shifts traffic from old to new revision. The old revision stays available until
explicitly deleted. If something goes wrong after a deploy, you can instantly route
100% traffic back to the previous revision in under a minute.

**4. Why use Secret Manager instead of environment variables for API keys?**

Secrets in environment variables are stored as plain text in deployment configs
(Cloud Run YAML, CI logs, Terraform state). Anyone with access to these configs
can read the secrets. Secret Manager encrypts secrets at rest with AES-256, logs
every access in Cloud Audit Logs, supports versioning (rotate without redeploying),
and has fine-grained IAM (you can grant read access to specific service accounts).
The container still sees the secret as a regular env var — no code changes needed.

**5. What is Workload Identity Federation and why is it more secure than a service account key?**

A service account key is a JSON file that grants permanent GCP access until manually
revoked. If it leaks (accidental commit, compromised CI machine), the attacker has
permanent access. WIF replaces it with short-lived OIDC tokens — GitHub generates
a token proving "this is workflow run #X from this repo", GCP exchanges it for a
1-hour access token. After 1 hour, the token is worthless. Nothing is stored that
could be leaked — there's no JSON key file anywhere.

---

## 14. Exercises

**Exercise 1 — Check the Cloud Run revision history**

```bash
# List the last 10 revisions of the backend service
gcloud run revisions list \
  --service=ai-assistant-backend \
  --region=asia-south1 \
  --limit=10 \
  --format="table(name,createTime,containers.image.slice(-1),traffic)"
```

What fraction of traffic does each revision receive? Which revision is currently
serving 100% of traffic?

**Exercise 2 — Simulate a bad deploy and rollback**

1. Find the name of the revision before the current one (from Exercise 1)
2. Route 10% of traffic to the old revision:
   ```bash
   gcloud run services update-traffic ai-assistant-backend \
     --region=asia-south1 \
     --to-revisions=OLD_REVISION_NAME=10,LATEST=90
   ```
3. Check the traffic split: `gcloud run services describe ai-assistant-backend --region=asia-south1`
4. Route all traffic back: `--to-revisions=LATEST=100`

**Exercise 3 — Rotate a secret without downtime**

1. Generate a new `SECRET_KEY`:
   ```bash
   python -c "import secrets; print(secrets.token_hex(32))"
   ```
2. Add it as a new version in Secret Manager:
   ```bash
   echo "new-secret-value" | gcloud secrets versions add SECRET_KEY --data-file=-
   ```
3. Trigger a new deploy (the deploy workflow picks up `latest` automatically)
4. Verify the new revision starts successfully using the new key
5. Note: all existing JWT tokens signed with the old key are now invalid —
   users must log in again. Explain why this is expected behaviour.

**Exercise 4 — Query your application logs**

```bash
# Find the 10 most recent ERROR-level logs from the backend
gcloud logging read \
  'resource.type="cloud_run_revision" AND resource.labels.service_name="ai-assistant-backend" AND severity=ERROR' \
  --limit=10 \
  --format="table(timestamp,jsonPayload.message,jsonPayload.correlation_id)" \
  --project=android-ai-assistant-89cec
```

Pick one log entry's `correlation_id` and find all log entries with that ID:
```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND jsonPayload.correlation_id="YOUR_ID_HERE"' \
  --format="table(timestamp,severity,jsonPayload.message)"
```

This traces a single request through all its log entries.

**Exercise 5 — Understand Secret Manager versioning**

```bash
# See all versions of a secret
gcloud secrets versions list SECRET_KEY

# Access the value of a specific version (not recommended for real secrets)
gcloud secrets versions access 1 --secret=SECRET_KEY

# Disable an old version (prevents it from being used, but doesn't delete it)
gcloud secrets versions disable 1 --secret=SECRET_KEY

# Cloud Run still uses version "latest" — but "latest" now points to version 2
```

This exercise teaches why pinning to `version: "latest"` in Cloud Run config
is a trade-off: you get automatic secret rotation without redeploying, but you
also mean that a new secret version takes effect immediately on the next
container start — which is exactly what you want for credential rotation.
