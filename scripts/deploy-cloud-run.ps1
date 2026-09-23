# ============================================================================
# deploy-cloud-run.ps1 — Deploy the backend to GCP Cloud Run
# ============================================================================
#
# Each environment uses SEPARATE GCP Secret Manager secrets so Stage and
# Production NEVER share credentials — including the Gemini API key.
#
# Secret naming:
#   aiassistant-stage-gemini-api-key   → Stage Gemini key
#   aiassistant-prod-gemini-api-key    → Production Gemini key
#   (same pattern for all other secrets)
#
# Usage:
#   .\scripts\deploy-cloud-run.ps1 -Environment stage
#   .\scripts\deploy-cloud-run.ps1 -Environment production
#
# IMPORTANT: Production deployments require manual execution.
#            Never add this script to an automatic CI trigger on main.
# ============================================================================

param(
    [Parameter(Mandatory)]
    [ValidateSet("stage", "production")]
    [string]$Environment
)

$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"
$SA      = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"

# ── Environment-specific configuration ───────────────────────────────────────

if ($Environment -eq "production") {
    $SERVICE    = "ai-assistant-backend"
    $IMAGE      = "$REGION-docker.pkg.dev/$PROJECT/backend/api:latest"
    $PREFIX     = "aiassistant-prod"
    $ENV_NAME   = "production"
    $LOG_LEVEL  = "INFO"
    $MIN_INST   = 0
    $MAX_INST   = 10
    $MEMORY     = "1Gi"
} else {
    $SERVICE    = "ai-assistant-backend-stage"
    $IMAGE      = "$REGION-docker.pkg.dev/$PROJECT/backend/api:latest"
    $PREFIX     = "aiassistant-stage"
    $ENV_NAME   = "staging"
    $LOG_LEVEL  = "DEBUG"
    $MIN_INST   = 0
    $MAX_INST   = 2
    $MEMORY     = "512Mi"
}

# ── Secret mapping ────────────────────────────────────────────────────────────
# Format: ENV_VAR=SECRET_MANAGER_NAME:version
# Each environment reads from its own prefixed secret.
# Both map to the same env var name inside the container (e.g. GEMINI_API_KEY),
# but the Secret Manager secret holding the value is different per environment.
#
# This means:
#   Stage   container → reads aiassistant-stage-gemini-api-key → GEMINI_API_KEY
#   Prod    container → reads aiassistant-prod-gemini-api-key  → GEMINI_API_KEY

$SECRETS = (
    "GEMINI_API_KEY=$PREFIX-gemini-api-key:latest",
    "OPENAI_API_KEY=$PREFIX-openai-api-key:latest",
    "SECRET_KEY=$PREFIX-secret-key:latest",
    "AES_ENCRYPTION_KEY=$PREFIX-aes-encryption-key:latest",
    "DATABASE_URL=$PREFIX-database-url:latest",
    "REDIS_URL=$PREFIX-redis-url:latest",
    "MINIO_ACCESS_KEY=$PREFIX-minio-access-key:latest",
    "MINIO_SECRET_KEY=$PREFIX-minio-secret-key:latest"
) -join ","

Write-Host ""
Write-Host "=== Deploying backend to Cloud Run ===" -ForegroundColor Cyan
Write-Host "    Environment : $Environment" -ForegroundColor Gray
Write-Host "    Service     : $SERVICE" -ForegroundColor Gray
Write-Host "    Image       : $IMAGE" -ForegroundColor Gray
Write-Host "    Secret prefix: $PREFIX" -ForegroundColor Gray
Write-Host ""

if ($Environment -eq "production") {
    Write-Host "  PRODUCTION deployment — confirm to proceed." -ForegroundColor Red
    $confirm = Read-Host "Type 'deploy-production' to confirm"
    if ($confirm -ne "deploy-production") {
        Write-Host "  Aborted." -ForegroundColor Yellow
        exit 1
    }
}

& $gcloud run deploy $SERVICE `
    --image="$IMAGE" `
    --region="$REGION" `
    --project="$PROJECT" `
    --service-account="$SA" `
    --min-instances=$MIN_INST `
    --max-instances=$MAX_INST `
    --cpu=1 `
    --memory=$MEMORY `
    --concurrency=40 `
    --allow-unauthenticated `
    --port=8000 `
    --set-secrets="$SECRETS" `
    --set-env-vars="ENVIRONMENT=$ENV_NAME,LOG_LEVEL=$LOG_LEVEL,MINIO_ENDPOINT=storage.googleapis.com,MINIO_BUCKET_NAME=$PROJECT-$Environment-files,CHROMA_PORT=8001,DEFAULT_LLM_PROVIDER=gemini,GOOGLE_CLIENT_ID=106071012091-d4brm5cng1gaor0al51veafjd0fa239v.apps.googleusercontent.com,GOOGLE_ANDROID_CLIENT_ID=106071012091-0cu6q2e6b4tsa1qrauutqt70ms13otn6.apps.googleusercontent.com" `
    2>&1

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
