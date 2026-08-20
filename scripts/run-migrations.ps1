$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"
$SA      = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$IMAGE   = "$REGION-docker.pkg.dev/$PROJECT/backend/api:latest"

Write-Host "=== Creating Alembic migration job ===" -ForegroundColor Cyan

# Create the job (ignore error if already exists)
& $gcloud run jobs create alembic-migrate `
    --image="$IMAGE" `
    --region="$REGION" `
    --project="$PROJECT" `
    --service-account="$SA" `
    --set-secrets="DATABASE_URL=DATABASE_URL:5,SECRET_KEY=SECRET_KEY:3,REDIS_URL=REDIS_URL:3,AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:3" `
    --set-env-vars="ENVIRONMENT=production" `
    --command="python" `
    --args="-m,alembic,upgrade,head" `
    --max-retries=1 `
    --memory=512Mi `
    --cpu=1 `
    2>&1

Write-Host ""
Write-Host "=== Running migration ===" -ForegroundColor Cyan
& $gcloud run jobs execute alembic-migrate `
    --region="$REGION" `
    --project="$PROJECT" `
    --wait `
    2>&1

Write-Host ""
Write-Host "=== Migration complete ===" -ForegroundColor Green
