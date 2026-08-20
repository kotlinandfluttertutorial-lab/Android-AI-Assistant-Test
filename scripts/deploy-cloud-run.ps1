$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"
$REGION  = "asia-south1"
$SA      = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$IMAGE   = "$REGION-docker.pkg.dev/$PROJECT/backend/api:latest"

Write-Host "=== Deploying to Cloud Run (pinned clean secret versions) ===" -ForegroundColor Cyan

# All version numbers are pinned to the clean REST-API-stored versions (no BOM)
# DATABASE_URL:5, SECRET_KEY:3, AES:3, REDIS:3, MINIO keys:3, GEMINI:6, OPENAI:3
& $gcloud run deploy ai-assistant-backend `
    --image="$IMAGE" `
    --region="$REGION" `
    --project="$PROJECT" `
    --service-account="$SA" `
    --min-instances=0 `
    --max-instances=2 `
    --cpu=1 `
    --memory=1Gi `
    --concurrency=40 `
    --allow-unauthenticated `
    --port=8000 `
    --set-secrets="SECRET_KEY=SECRET_KEY:3,AES_ENCRYPTION_KEY=AES_ENCRYPTION_KEY:3,DATABASE_URL=DATABASE_URL:7,REDIS_URL=REDIS_URL:3,MINIO_ACCESS_KEY=MINIO_ACCESS_KEY:3,MINIO_SECRET_KEY=MINIO_SECRET_KEY:3,OPENAI_API_KEY=OPENAI_API_KEY:3,GEMINI_API_KEY=GEMINI_API_KEY:6" `
    --set-env-vars="ENVIRONMENT=production,LOG_LEVEL=INFO,MINIO_ENDPOINT=storage.googleapis.com,MINIO_BUCKET_NAME=$PROJECT-files,CHROMA_PORT=8001,LLM_FALLBACK_PROVIDER=,DEFAULT_LLM_PROVIDER=gemini" `
    2>&1

Write-Host "=== Done ===" -ForegroundColor Green
