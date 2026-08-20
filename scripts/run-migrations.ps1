$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"
$SA      = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$IMAGE   = "$REGION-docker.pkg.dev/$PROJECT/backend/api:latest"

Write-Host "=== Updating Alembic job image ===" -ForegroundColor Cyan
& $gcloud run jobs update alembic-migrate `
    --image="$IMAGE" `
    --region="$REGION" `
    --project="$PROJECT" `
    --update-secrets="DATABASE_URL=DATABASE_URL:7" `
    2>&1

Write-Host ""
Write-Host "=== Running migration ===" -ForegroundColor Cyan
& $gcloud run jobs execute alembic-migrate `
    --region="$REGION" `
    --project="$PROJECT" `
    --wait `
    2>&1

Write-Host ""
Write-Host "=== Done ===" -ForegroundColor Green
