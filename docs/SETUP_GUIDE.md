# Setup Guide — Firebase, GCP & GitHub Secrets
## For First-Time Setup (Step-by-Step)

> **Your project details (already confirmed):**
> - Firebase project: `android-ai-assistant-89cec`
> - Firebase account: `kotlinfiroj@gmail.com`
> - Android package name: `com.aiassistant`
> - GCP region: `asia-south1` (Mumbai — lowest latency from India)
> - Monthly budget target: ₹0–₹1,000

---

## Overview — What You Are Setting Up

```
Your Phone (Android App)
       │ HTTPS
       ▼
Cloud Run (FastAPI backend)   ← runs only when requests arrive, ₹0 when idle
       │
       ├── Neon PostgreSQL    ← free tier database
       ├── ChromaDB           ← vector store (also Cloud Run)
       ├── Cloud Storage      ← files and audio uploads
       └── Secret Manager     ← API keys, never in code
```

**GitHub Actions** automates building and deploying everything on every `git push`.

---

## Suggested Schedule (first-time)

| Day | Tasks | Time |
|-----|-------|------|
| 1 | Install tools, create GCP project, enable APIs | 2–3 hrs |
| 2 | Neon DB + ChromaDB + Storage + Secret Manager | 2–3 hrs |
| 3 | Build Docker image + first Cloud Run deploy + migrations | 2–3 hrs |
| 4 | Workload Identity + GitHub Secrets | 1 hr |
| 5 | Push to main, watch the pipeline pass | 30 min |

---

## Part 1 — Firebase (already mostly done)

Your `google-services.json` is already committed and your Firebase project exists.
Three small tasks remain.

---

### 1a. Web Client ID — already set ✅

The Web Client ID for Google Sign-In has been filled in from your `google-services.json`:

```
106071012091-d4brm5cng1gaor0al51veafjd0fa239v.apps.googleusercontent.com
```

This value is in `feature-auth/src/main/res/values/strings.xml` as `google_web_client_id`.
No action needed.

---

### 1b. Create Firebase App Distribution Service Account

This lets GitHub Actions send APKs directly to your phone after every build.

**Steps:**
1. Go to [console.firebase.google.com](https://console.firebase.google.com)
2. Sign in with **kotlinfiroj@gmail.com**
3. Select project **Android AI Assistant** (`android-ai-assistant-89cec`)
4. Click the ⚙️ gear icon → **Project Settings** → **Service accounts** tab
5. Click **Generate new private key** → **Generate key**
6. A JSON file downloads — **save it somewhere safe** (e.g. `firebase-service-account.json`)
   > ⚠️ Never commit this file to Git

**Set up App Distribution:**
1. Left sidebar → **Release & Monitor** → **App Distribution**
2. Click **Testers & Groups** → **Add group**
3. Name: `qa-team` → Save
4. Click **Invite testers** → add your own email

**Your Firebase App IDs** (from `google-services.json`):
```
Release: 1:106071012091:android:af250ff9587e8c33df765e
Debug:   1:106071012091:android:44515e4f30fe7b6cdf765e
```
Use the **release** ID for the `FIREBASE_APP_ID` GitHub secret.

---

### 1c. Get a Firebase Cloud Messaging Server Key (optional)

Only needed for push notifications. Skip for now, add later if required.

---

## Part 2 — GCP Setup

### 2a. Install Required Tools

**Google Cloud SDK (Windows):**
1. Download from [cloud.google.com/sdk/docs/install-sdk](https://cloud.google.com/sdk/docs/install-sdk)
2. Run the `.exe` installer
3. Check ✅ **Add gcloud to PATH** during installation
4. Restart PowerShell after installation

**Verify:**
```powershell
gcloud --version   # needs 460+
docker --version   # needs 24+ (install Docker Desktop if missing)
```

**Authenticate:**
```powershell
gcloud auth login
# Opens your browser — sign in with kotlinfiroj@gmail.com
```

---

### 2b. Create GCP Project

```powershell
# Set these variables — use them in every command below
$PROJECT_ID = "android-ai-assistant"
$REGION     = "asia-south1"

# Create the project
gcloud projects create $PROJECT_ID --name="Android AI Assistant"

# Set as default so you don't have to type it every time
gcloud config set project $PROJECT_ID
gcloud config set run/region $REGION

# Link billing account
gcloud billing accounts list
# Look at the output — copy the ACCOUNT_ID (format: XXXXXX-XXXXXX-XXXXXX)

gcloud billing projects link $PROJECT_ID --billing-account=YOUR_ACCOUNT_ID
```

> 💡 New GCP accounts get **$300 free credits** — enough to run this project for months.

---

### 2c. Enable APIs (one command)

```powershell
gcloud services enable `
  run.googleapis.com `
  artifactregistry.googleapis.com `
  cloudbuild.googleapis.com `
  storage.googleapis.com `
  secretmanager.googleapis.com `
  iam.googleapis.com
```

Wait about 60 seconds for all APIs to activate.

---

### 2d. Create Neon PostgreSQL (free, no GCP cost)

GCP's own Cloud SQL costs ~₹800/month even with zero traffic. Neon is free.

**Steps:**
1. Go to [neon.tech](https://neon.tech) → **Sign up** (use GitHub or Google)
2. Click **New project**
3. Name: `android-ai-assistant`
4. Region: **AWS ap-south-1** (Mumbai — closest to India)
5. Click **Create project**
6. On the dashboard, copy the **Connection string** — it looks like:
   ```
   postgresql+asyncpg://user:password@ep-xxxx-xxxx.ap-southeast-1.aws.neon.tech/neondb?sslmode=require
   ```
7. **Save this string** — it becomes your `DATABASE_URL` secret

---

### 2e. Deploy ChromaDB (vector store)

ChromaDB is the AI memory/search database. It runs on Cloud Run just like the backend.

```powershell
gcloud run deploy chromadb `
  --image=chromadb/chroma:1.5.9 `
  --region=$REGION `
  --port=8000 `
  --memory=512Mi `
  --cpu=1 `
  --min-instances=0 `
  --max-instances=1 `
  --no-allow-unauthenticated `
  --ingress=internal
```

> The `--ingress=internal` flag means only your backend can talk to it — not the public internet.
> Note the URL it prints (looks like `https://chromadb-xxxx-el.a.run.app`) — you will need it.

---

### 2f. Create Cloud Storage Bucket

This is where uploaded files (PDFs, audio recordings) are stored.

```powershell
# Create the storage bucket
gsutil mb -l $REGION gs://$PROJECT_ID-files

# Create a service account for the backend to use
gcloud iam service-accounts create ai-assistant-backend `
  --display-name="AI Assistant Backend SA"

# Give it permission to read/write files in the bucket
gsutil iam ch `
  serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com:roles/storage.objectAdmin `
  gs://$PROJECT_ID-files

# Create HMAC keys (the backend uses these like MinIO/S3 access keys)
gsutil hmac create ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com
```

The last command prints two values — **save both**:
```
Access ID: GOOGXXXXXXXXXXXXXX
Secret:    base64stringhere
```

---

### 2g. Store Secrets in Secret Manager

First, generate the values you need:

```powershell
# Install Python if you don't have it, then run:
python -c "import secrets; print(secrets.token_hex(32))"
# Copy the output — this is your SECRET_KEY

python -c "import base64,os; print(base64.b64encode(os.urandom(32)).decode())"
# Copy the output — this is your AES_ENCRYPTION_KEY
```

Now store everything in Secret Manager:

```powershell
# Helper function — creates a secret and sets its value
function Set-GCPSecret {
    param($Name, $Value)
    gcloud secrets create $Name --replication-policy=automatic 2>$null
    $Value | gcloud secrets versions add $Name --data-file=-
    Write-Host "✅ Set secret: $Name"
}

# --- Required secrets ---
Set-GCPSecret "SECRET_KEY"          "PASTE_YOUR_GENERATED_SECRET_KEY_HERE"
Set-GCPSecret "AES_ENCRYPTION_KEY"  "PASTE_YOUR_GENERATED_AES_KEY_HERE"
Set-GCPSecret "DATABASE_URL"        "postgresql+asyncpg://user:pass@ep-xxxx.neon.tech/neondb?sslmode=require"
Set-GCPSecret "REDIS_URL"           "redis://localhost:6379/0"

# --- Storage (from step 2f) ---
Set-GCPSecret "MINIO_ACCESS_KEY"    "PASTE_HMAC_ACCESS_ID_HERE"
Set-GCPSecret "MINIO_SECRET_KEY"    "PASTE_HMAC_SECRET_HERE"

# --- LLM API keys (set at least one) ---
Set-GCPSecret "OPENAI_API_KEY"      "sk-..."
Set-GCPSecret "GEMINI_API_KEY"      "AIza..."

# Grant the backend service account permission to read all secrets
gcloud projects add-iam-policy-binding $PROJECT_ID `
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/secretmanager.secretAccessor"
```

> **REDIS_URL placeholder:** The app requires this env var to start. Set any valid redis:// URL
> for now — Redis isn't actually used yet. The API still works without a real Redis server.

---

### 2h. Build and Push Docker Image

Run these from your **project root** (where `backend/` folder is):

```powershell
# Create the Artifact Registry repository
gcloud artifacts repositories create backend `
  --repository-format=docker `
  --location=$REGION

# Authenticate Docker to push to GCP
gcloud auth configure-docker $REGION-docker.pkg.dev

# Build the image
$IMAGE = "$REGION-docker.pkg.dev/$PROJECT_ID/backend/api"
docker build --target production --tag "${IMAGE}:latest" backend/

# Push to GCP
docker push "${IMAGE}:latest"

Write-Host "✅ Image pushed: ${IMAGE}:latest"
```

> The first build takes 5–10 minutes. Subsequent builds are faster (cached).

---

### 2i. First Deploy to Cloud Run

```powershell
gcloud run deploy ai-assistant-backend `
  --image="${IMAGE}:latest" `
  --region=$REGION `
  --service-account="ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --min-instances=0 `
  --max-instances=2 `
  --cpu=1 `
  --memory=1Gi `
  --concurrency=40 `
  --allow-unauthenticated `
  --set-secrets="SECRET_KEY=SECRET_KEY:latest,AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:latest,DATABASE_URL=DATABASE_URL:latest,REDIS_URL=REDIS_URL:latest,MINIO_ACCESS_KEY=MINIO_ACCESS_KEY:latest,MINIO_SECRET_KEY=MINIO_SECRET_KEY:latest,OPENAI_API_KEY=OPENAI_API_KEY:latest,GEMINI_API_KEY=GEMINI_API_KEY:latest" `
  --set-env-vars="ENVIRONMENT=production,LOG_LEVEL=INFO,MINIO_ENDPOINT=storage.googleapis.com,MINIO_BUCKET_NAME=$PROJECT_ID-files,CHROMA_PORT=8001,LLM_FALLBACK_PROVIDER=" `
  --port=8000
```

When it finishes it prints your service URL:
```
Service URL: https://ai-assistant-backend-xxxx-el.a.run.app
```

**Test it immediately:**
```powershell
$SERVICE_URL = "https://ai-assistant-backend-xxxx-el.a.run.app"   # paste your actual URL

# Should return: {"status":"ok"}
Invoke-WebRequest "$SERVICE_URL/health" | Select-Object -ExpandProperty Content

# Should return 200 with database status
Invoke-WebRequest "$SERVICE_URL/ready" | Select-Object -ExpandProperty Content
```

**Save the SERVICE_URL — you need it for step 3b (GitHub secrets).**

---

### 2j. Run Database Migrations

This creates all the database tables in Neon.

```powershell
# Create the migration job
gcloud run jobs create alembic-migrate `
  --image="${IMAGE}:latest" `
  --region=$REGION `
  --service-account="ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --set-secrets="DATABASE_URL=DATABASE_URL:latest,SECRET_KEY=SECRET_KEY:latest,REDIS_URL=REDIS_URL:latest" `
  --set-env-vars="ENVIRONMENT=production" `
  --command="python" `
  --args="-m,alembic,upgrade,head" `
  --max-retries=1

# Run it
gcloud run jobs execute alembic-migrate --region=$REGION --wait
```

If it says `Succeeded` — your database tables are created. ✅

---

### 2k. Set Up Workload Identity Federation (Passwordless CI/CD)

This lets GitHub Actions deploy to GCP **without storing any passwords** — the most secure way.

```powershell
# Get your project number
$PROJECT_NUMBER = $(gcloud projects describe $PROJECT_ID --format="value(projectNumber)")
Write-Host "Project number: $PROJECT_NUMBER"

# Create the identity pool
gcloud iam workload-identity-pools create github-actions `
  --location=global `
  --display-name="GitHub Actions"

# Add the GitHub OIDC provider
gcloud iam workload-identity-pools providers create-oidc github `
  --location=global `
  --workload-identity-pool=github-actions `
  --issuer-uri="https://token.actions.githubusercontent.com" `
  --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository"

# ⚠️ Replace YOUR_GITHUB_USERNAME with your actual GitHub username
# Example: if your repo URL is github.com/johndoe/Android-AI-Assistant-Test
# then YOUR_GITHUB_USERNAME = johndoe
gcloud iam service-accounts add-iam-policy-binding `
  "ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/iam.workloadIdentityUser" `
  --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/attribute.repository/YOUR_GITHUB_USERNAME/Android-AI-Assistant-Test"

# Grant deploy permissions to the service account
gcloud projects add-iam-policy-binding $PROJECT_ID `
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/run.developer"

gcloud projects add-iam-policy-binding $PROJECT_ID `
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/artifactregistry.writer"

gcloud projects add-iam-policy-binding $PROJECT_ID `
  --member="serviceAccount:ai-assistant-backend@$PROJECT_ID.iam.gserviceaccount.com" `
  --role="roles/iam.serviceAccountUser"
```

**Print the WIF provider name — save it for GitHub secrets:**
```powershell
Write-Host "GCP_WIF_PROVIDER value:"
Write-Host "projects/$PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/providers/github"
```

---

### 2l. Set a Budget Alert (prevents surprise bills)

```powershell
gcloud billing budgets create `
  --billing-account=YOUR_BILLING_ACCOUNT_ID `
  --display-name="AI Assistant Budget Alert" `
  --budget-amount=800INR `
  --threshold-rule=percent=0.5 `
  --threshold-rule=percent=0.9 `
  --threshold-rule=percent=1.0
```

---

## Part 3 — GitHub Secrets

Go to your repo on GitHub:
**Settings** → **Secrets and variables** → **Actions** → **New repository secret**

Add each row below as a separate secret.

---

### 3a. Prepare Values Not Yet Available

**Android Release Keystore** (if you don't have one):
```powershell
# Run this ONCE — save the passwords you enter
keytool -genkey -v `
  -keystore android-release.jks `
  -alias release `
  -keyalg RSA -keysize 2048 -validity 10000
# Prompts: enter a keystore password, answer name/org questions

# Base64-encode it for GitHub
[Convert]::ToBase64String([IO.File]::ReadAllBytes("android-release.jks")) | Set-Clipboard
# Now KEYSTORE_BASE64 is in your clipboard — paste it into GitHub
```

**Firebase service account** (from step 1b):
```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("firebase-service-account.json")) | Set-Clipboard
# FIREBASE_SERVICE_ACCOUNT is in clipboard
```

**google-services.json**:
```powershell
$gsJsonPath = "j:\Android\AndroidStudioProjects\Kiro\TestBranch\Develop_Feature\Android-AI-Assistant-Test\app\google-services.json"
[Convert]::ToBase64String([IO.File]::ReadAllBytes($gsJsonPath)) | Set-Clipboard
# GOOGLE_SERVICES_JSON is in clipboard
```

**NVD API Key** (for dependency scanning — free):
1. Go to [nvd.nist.gov/developers/request-an-api-key](https://nvd.nist.gov/developers/request-an-api-key)
2. Enter your email → key arrives in minutes

---

### 3b. Add All Secrets to GitHub

| Secret Name | Value | Where to get it |
|---|---|---|
| `GCP_PROJECT_ID` | `android-ai-assistant` | Step 2b |
| `GCP_REGION` | `asia-south1` | Step 2b |
| `GCP_WIF_PROVIDER` | Long string printed in step 2k | Step 2k |
| `GCP_SERVICE_ACCOUNT` | `ai-assistant-backend@android-ai-assistant.iam.gserviceaccount.com` | Step 2f |
| `CLOUD_RUN_SERVICE` | `ai-assistant-backend` | Step 2i |
| `CLOUD_RUN_SERVICE_URL` | `https://ai-assistant-backend-xxxx-el.a.run.app` | Step 2i output |
| `CHROMA_SERVICE_NAME` | `chromadb` | Step 2e |
| `KEYSTORE_BASE64` | Base64 of `.jks` file | Step 3a |
| `KEY_ALIAS` | `release` | Step 3a (the alias you chose) |
| `KEY_PASSWORD` | Password you set in keytool | Step 3a |
| `KEYSTORE_PASSWORD` | Keystore password from keytool | Step 3a |
| `GOOGLE_SERVICES_JSON` | Base64 of `app/google-services.json` | Step 3a |
| `FIREBASE_APP_ID` | `1:106071012091:android:af250ff9587e8c33df765e` | Step 1c |
| `FIREBASE_SERVICE_ACCOUNT` | Base64 of Firebase service account JSON | Step 3a |
| `FIREBASE_TESTER_GROUPS` | `qa-team` | Step 1b |
| `NVD_API_KEY` | Key from NVD website | Step 3a |
| `AES_ENCRYPTION_KEY_CI` | Same AES key stored in Secret Manager | Step 2g |

---

### 3c. Add the GitHub Variable (not a secret)

**Settings → Secrets and variables → Actions → Variables tab → New repository variable:**

| Variable | Value |
|---|---|
| `GCP_ARTIFACT_REPO` | `backend` |

---

### 3d. Test the Full Pipeline

```bash
git add .
git commit -m "chore: complete deployment setup"
git push origin main
```

Then go to **GitHub → Actions tab** and watch the `cloud-run-deploy` workflow run.

**Expected result:**
1. ✅ Build & Push Image
2. ✅ Run Alembic Migrations
3. ✅ Deploy to Cloud Run
4. ✅ Smoke test `/health` returns 200

If any step fails — go to the failed step in GitHub Actions, expand the logs, and share the error message. Common issues and fixes are in the Troubleshooting section below.

---

## Part 4 — Connect Android App to Cloud Run

Once the backend is live:

1. Open `core-network/src/main/kotlin/com/aiassistant/core/network/di/NetworkModule.kt`
2. The `BASE_URL` is already set to `https://api.handsonandroid.com/` for release builds
3. If you're using the raw Cloud Run URL (without a custom domain), override at build time:
   ```powershell
   ./gradlew assembleRelease -Pbase_url="https://ai-assistant-backend-xxxx-el.a.run.app/"
   ```
4. Or set up a custom domain (optional):
   ```powershell
   gcloud run domain-mappings create `
     --service=ai-assistant-backend `
     --domain=api.handsonandroid.com `
     --region=$REGION
   ```

---

## Troubleshooting

### "Permission denied" during gcloud commands
```powershell
gcloud auth login
gcloud config set project android-ai-assistant
```

### Docker build fails with "cannot connect to Docker daemon"
Open **Docker Desktop** first, wait for it to say "Running", then retry.

### Cloud Run deploy fails with "IMAGE not found"
```powershell
# Re-authenticate Docker and re-push
gcloud auth configure-docker asia-south1-docker.pkg.dev
docker push "${IMAGE}:latest"
```

### `/health` returns 503 (service crashed at startup)
```powershell
# Check startup logs
gcloud run services logs read ai-assistant-backend --region=asia-south1 --limit=50
# Look for STARTUP_VALIDATION_FAILED — it logs exactly which env var is missing
```

### Alembic migration fails
```powershell
# Check migration logs
gcloud logging read 'resource.type="cloud_run_job"' --limit=20 --format="value(textPayload)"
# Usually means DATABASE_URL is wrong — verify the Neon connection string
```

### GitHub Actions fails at "Authenticate to Google Cloud"
- Double-check `GCP_WIF_PROVIDER` secret — it must be the full string from step 2k
- Double-check `GCP_SERVICE_ACCOUNT` — must end in `.iam.gserviceaccount.com`
- Make sure you replaced `YOUR_GITHUB_USERNAME` with your actual GitHub username in step 2k

---

## Quick Reference — Key Values for This Project

| Item | Value |
|------|-------|
| Firebase project ID | `android-ai-assistant-89cec` |
| Firebase account | `kotlinfiroj@gmail.com` |
| Firebase release app ID | `1:106071012091:android:af250ff9587e8c33df765e` |
| Web OAuth client ID | `106071012091-d4brm5cng1gaor0al51veafjd0fa239v.apps.googleusercontent.com` |
| GCP project ID | `android-ai-assistant` |
| GCP region | `asia-south1` |
| GCP service account | `ai-assistant-backend@android-ai-assistant.iam.gserviceaccount.com` |
| Android package name | `com.aiassistant` |
| Cloud Run service name | `ai-assistant-backend` |
| ChromaDB service name | `chromadb` |
| Storage bucket | `android-ai-assistant-files` |

---

*Created: August 19, 2026 · Maintained alongside `docs/CLOUD_RUN_DEPLOYMENT.md`*
