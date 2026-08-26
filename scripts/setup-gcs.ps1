$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$gsutil  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gsutil.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"
$SA      = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$BUCKET  = "gs://$PROJECT-files"

Write-Host "=== Step 5: Cloud Storage Setup ===" -ForegroundColor Cyan
Write-Host ""

# ── 1. Create bucket (idempotent — skips if already exists) ──────────────────
Write-Host "--- Creating bucket $BUCKET ---" -ForegroundColor Yellow
$bucketCheck = & $gsutil ls -p $PROJECT 2>&1 | Select-String "$PROJECT-files"
if ($bucketCheck) {
    Write-Host "✅ Bucket already exists — skipping"
} else {
    & $gsutil mb -l $REGION $BUCKET 2>&1
    Write-Host "✅ Bucket created: $BUCKET"
}

Write-Host ""

# ── 2. Grant service account Object Admin on the bucket only ─────────────────
Write-Host "--- Granting storage.objectAdmin to $SA ---" -ForegroundColor Yellow
& $gsutil iam ch `
    "serviceAccount:${SA}:roles/storage.objectAdmin" `
    $BUCKET 2>&1
Write-Host "✅ IAM binding applied"

Write-Host ""

# ── 3. Generate HMAC keys ─────────────────────────────────────────────────────
Write-Host "--- Generating HMAC keys for $SA ---" -ForegroundColor Yellow
Write-Host ""
Write-Host "⚠️  COPY THE ACCESS KEY ID AND SECRET BELOW — they are shown only once!" -ForegroundColor Red
Write-Host ""

$hmacOutput = & $gsutil hmac create $SA 2>&1
Write-Host $hmacOutput

Write-Host ""
Write-Host "==================================================" -ForegroundColor Green
Write-Host "NEXT STEPS:" -ForegroundColor Green
Write-Host ""
Write-Host "1. Copy the Access ID and Secret printed above"
Write-Host ""
Write-Host "2. Run these two commands to update Secret Manager:"
Write-Host '   $env:HMAC_ACCESS_ID = "<paste Access ID here>"'
Write-Host '   $env:HMAC_SECRET    = "<paste Secret here>"'
Write-Host '   .\scripts\update-minio-secrets.ps1'
Write-Host ""
Write-Host "3. Redeploy Cloud Run to pick up the new secret versions:"
Write-Host "   .\scripts\deploy-cloud-run.ps1"
Write-Host "==================================================" -ForegroundColor Green
