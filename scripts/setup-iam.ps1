$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"
$SA = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$REGION = "asia-south1"
$IMAGE = "$REGION-docker.pkg.dev/$PROJECT/backend/api"

Write-Host "=== Step 1: Grant deploy permissions to service account ==="
& $gcloud projects add-iam-policy-binding $PROJECT --member="serviceAccount:$SA" --role="roles/run.developer" --project=$PROJECT 2>&1 | Select-String "Updated|Error"
& $gcloud projects add-iam-policy-binding $PROJECT --member="serviceAccount:$SA" --role="roles/artifactregistry.writer" --project=$PROJECT 2>&1 | Select-String "Updated|Error"
& $gcloud projects add-iam-policy-binding $PROJECT --member="serviceAccount:$SA" --role="roles/iam.serviceAccountUser" --project=$PROJECT 2>&1 | Select-String "Updated|Error"
Write-Host "✅ IAM permissions granted"

Write-Host ""
Write-Host "=== Step 2: Create Artifact Registry repository ==="
& $gcloud artifacts repositories create backend --repository-format=docker --location=$REGION --project=$PROJECT 2>&1 | Where-Object { $_ -notmatch "WARNING" }
Write-Host "✅ Artifact Registry ready"

Write-Host ""
Write-Host "=== Step 3: Authenticate Docker to push images ==="
& $gcloud auth configure-docker "$REGION-docker.pkg.dev" --quiet 2>&1 | Where-Object { $_ -notmatch "WARNING" }
Write-Host "✅ Docker authenticated"

Write-Host ""
Write-Host "Image will be: $IMAGE`:latest"
Write-Host ""
Write-Host "Next: run the Docker build from project root"
