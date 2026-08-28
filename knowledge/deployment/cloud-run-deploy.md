# Deployment Guide — Cloud Run

**Project:** android-ai-assistant-89cec
**Region:** asia-south1 (Mumbai)
**Last updated:** 2026-08-26

---

## Services deployed

| Service | Image | Ingress | Min | Max |
|---------|-------|---------|-----|-----|
| ai-assistant-backend | asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:latest | public | 0 | 2 |
| chromadb | chromadb/chroma:1.5.9 | internal | 0 | 1 |

---

## Deploying via CI/CD (recommended)

Every push to `main` triggers the `cloud-run-deploy.yml` GitHub Actions workflow:

1. Build Docker image (multi-stage, production target)
2. Push to Artifact Registry: `asia-south1-docker.pkg.dev/android-ai-assistant-89cec/backend/api:sha-{git_sha}`
3. Run Alembic migrations via Cloud Run Job
4. Deploy new Cloud Run revision
5. Smoke test: `GET /health` must return 200

The workflow uses Workload Identity Federation — no service account key files stored anywhere.

---

## Manual deployment

```powershell
# From project root on Windows
.\scripts\deploy-cloud-run.ps1
```

Or step by step:

```bash
PROJECT=android-ai-assistant-89cec
REGION=asia-south1
IMAGE=asia-south1-docker.pkg.dev/$PROJECT/backend/api

# 1. Authenticate Docker
gcloud auth configure-docker $REGION-docker.pkg.dev

# 2. Build and push
TAG=$(git rev-parse --short HEAD)
docker build --target production -t $IMAGE:$TAG backend/
docker push $IMAGE:$TAG

# 3. Run migrations
gcloud run jobs execute alembic-migrate --region=$REGION --wait

# 4. Deploy
gcloud run services update ai-assistant-backend \
  --image=$IMAGE:$TAG \
  --region=$REGION

# 5. Verify
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/health
curl https://ai-assistant-backend-106071012091.asia-south1.run.app/ready
```

---

## Post-deployment checklist

After every deployment, verify these in order:

1. `GET /health` returns `{"status":"ok"}`
2. `GET /ready` returns `{"status":"ready","dependencies":{"database":"ok"}}`
3. `POST /auth/login` with test credentials returns a JWT
4. `POST /documents/query` with a known query returns `total_chunks > 0`
5. Check Grafana error rate dashboard — no spike in 5xx errors
6. Check Cloud Logging for `STARTUP_VALIDATION_PASSED`

If step 4 fails (empty RAG results): ChromaDB is cold-starting. Wait 4 minutes and retry.
If still empty after 4 minutes: trigger a re-index via `POST /api/v1/admin/rag/reindex`.

---

## First-time deployment (new environment)

Run these scripts in order:

```powershell
# 1. Create bucket, IAM, and HMAC keys for GCS
.\scripts\setup-gcs.ps1

# 2. Update HMAC secrets in Secret Manager
$env:HMAC_ACCESS_ID = "GOOG1E..."
$env:HMAC_SECRET = "..."
.\scripts\update-minio-secrets.ps1

# 3. Grant IAM permissions to service account
.\scripts\setup-iam.ps1

# 4. Configure Workload Identity Federation for GitHub Actions
.\scripts\setup-wif.ps1
.\scripts\setup-wif-step2.ps1

# 5. Deploy
.\scripts\deploy-cloud-run.ps1

# 6. Run migrations
.\scripts\run-migrations.ps1

# 7. Seed the knowledge base
cd backend
python scripts/seed_knowledge.py
```

---

## Environment variables (Cloud Run)

Non-secret values are set as env vars directly on the Cloud Run service.
Secret values are pulled from Secret Manager at container startup.

| Variable | Where set | Value |
|----------|-----------|-------|
| ENVIRONMENT | env var | production |
| LOG_LEVEL | env var | INFO |
| MINIO_ENDPOINT | env var | storage.googleapis.com |
| MINIO_BUCKET_NAME | env var | android-ai-assistant-89cec-files |
| CHROMA_HOST | env var | (ChromaDB Cloud Run URL, no https://) |
| CHROMA_PORT | env var | 8001 |
| DEFAULT_LLM_PROVIDER | env var | gemini |
| SECRET_KEY | Secret Manager | auto-pulled at startup |
| DATABASE_URL | Secret Manager | auto-pulled at startup |
| GEMINI_API_KEY | Secret Manager | auto-pulled at startup |
| MINIO_ACCESS_KEY | Secret Manager | auto-pulled at startup |

---

## Rollback

See `runbooks/rollback.md` for step-by-step rollback instructions.

Quick version:
```bash
GOOD_REVISION="ai-assistant-backend-XXXXX-xxx"
gcloud run services update-traffic ai-assistant-backend \
  --region=asia-south1 \
  --to-revisions=$GOOD_REVISION=100
```
