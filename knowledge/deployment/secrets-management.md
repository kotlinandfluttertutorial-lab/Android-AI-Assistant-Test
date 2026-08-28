# Secrets Management

**Last updated:** 2026-08-26
**Tool:** Google Cloud Secret Manager
**Project:** android-ai-assistant-89cec

---

## Principle

No secrets ever live in code, `.env` files, Docker images, or GitHub Actions secrets.
All sensitive values are stored in GCP Secret Manager and pulled by Cloud Run at
container startup using the service account identity.

---

## Secrets inventory

| Secret name | What it contains | Rotation frequency |
|-------------|-----------------|-------------------|
| `SECRET_KEY` | JWT signing key (64 hex chars) | Annually or on compromise |
| `AES_ENCRYPTION_KEY` | AES-256 key for LLM key encryption (base64) | Annually or on compromise |
| `DATABASE_URL` | Neon PostgreSQL connection string | On password rotation |
| `REDIS_URL` | Redis connection string | On password rotation |
| `MINIO_ACCESS_KEY` | GCS HMAC Access ID | On HMAC key rotation |
| `MINIO_SECRET_KEY` | GCS HMAC Secret | On HMAC key rotation |
| `OPENAI_API_KEY` | OpenAI API key | On compromise or quarterly |
| `GEMINI_API_KEY` | Google Gemini API key | On compromise or quarterly |
| `ANTHROPIC_API_KEY` | Anthropic Claude API key | On compromise |

---

## Adding a new secret

```bash
PROJECT=android-ai-assistant-89cec

# Create the secret
gcloud secrets create MY_NEW_SECRET \
  --replication-policy=automatic \
  --project=$PROJECT

# Add the first version
echo -n "my-secret-value" | \
  gcloud secrets versions add MY_NEW_SECRET \
    --data-file=- \
    --project=$PROJECT

# Verify
gcloud secrets versions access latest \
  --secret=MY_NEW_SECRET \
  --project=$PROJECT
```

Then add it to the Cloud Run service:
```bash
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --update-secrets="MY_NEW_SECRET=MY_NEW_SECRET:latest"
```

---

## Rotating a secret (e.g. after compromise)

### Step 1 — Add a new version

```bash
# New value — never hardcode in scripts; use env var
echo -n "$NEW_VALUE" | \
  gcloud secrets versions add GEMINI_API_KEY \
    --data-file=- \
    --project=android-ai-assistant-89cec
```

### Step 2 — Verify the new version

```bash
gcloud secrets versions list GEMINI_API_KEY \
  --project=android-ai-assistant-89cec
# Should show: newest version as ENABLED, previous as ENABLED

# Test the new value works before disabling the old one
# (deploy Cloud Run with the new version and verify /ready)
```

### Step 3 — Force Cloud Run to use the new version

Cloud Run only pulls secrets at container startup. To pick up the new version:

```bash
# Deploy a new revision (same image, forces secret refresh)
gcloud run services update ai-assistant-backend \
  --region=asia-south1 \
  --project=android-ai-assistant-89cec
```

### Step 4 — Disable the old version

After confirming the service is healthy with the new secret:

```bash
# Disable old version (don't delete — keep for audit trail)
gcloud secrets versions disable 5 \
  --secret=GEMINI_API_KEY \
  --project=android-ai-assistant-89cec
```

---

## Rotating HMAC keys (GCS access)

HMAC keys cannot be updated — you must create a new pair and delete the old one.

```bash
PROJECT=android-ai-assistant-89cec
SA=ai-assistant-backend@$PROJECT.iam.gserviceaccount.com
gsutil=$env:USERPROFILE + "\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gsutil.cmd"

# 1. Create new HMAC key pair (shows Access ID and Secret — copy immediately)
gsutil hmac create $SA

# 2. Update Secret Manager
$env:HMAC_ACCESS_ID = "GOOG1E..."  # paste from above
$env:HMAC_SECRET    = "..."        # paste from above
.\scripts\update-minio-secrets.ps1

# 3. Force Cloud Run to pick up new keys
.\scripts\deploy-cloud-run.ps1

# 4. Verify file upload/download works
curl -X POST https://ai-assistant-backend-106071012091.asia-south1.run.app/api/v1/documents \
  -H "Authorization: Bearer YOUR_JWT" \
  -F "file=@test.txt"

# 5. Delete the old HMAC key (list first to get the access ID)
gsutil hmac list -p $PROJECT -u $SA
gsutil hmac delete OLD_ACCESS_ID
```

---

## Secret access control

The backend service account `ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com`
has `roles/secretmanager.secretAccessor` at the project level. This means it can
read ANY secret in the project.

For tighter control in production, consider granting access at the per-secret level:
```bash
gcloud secrets add-iam-policy-binding GEMINI_API_KEY \
  --member="serviceAccount:ai-assistant-backend@android-ai-assistant-89cec.iam.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor" \
  --project=android-ai-assistant-89cec
```

---

## Emergency: compromised SECRET_KEY or AES_ENCRYPTION_KEY

If `SECRET_KEY` is compromised, all existing JWTs are invalid after rotation
(users must log in again — this is expected).

If `AES_ENCRYPTION_KEY` is compromised, all stored LLM API keys (encrypted at rest
in PostgreSQL) must be re-encrypted with the new key. Contact the database admin.
