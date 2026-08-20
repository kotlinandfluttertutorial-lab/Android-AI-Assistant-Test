$gcloud      = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT     = "android-ai-assistant-89cec"
$PROJECT_NUM = "106071012091"
$SA          = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"
$GITHUB_USER = "kotlinandfluttertutorial-lab"
$REPO_NAME   = "Android-AI-Assistant-Test"

Write-Host "=== Creating OIDC provider (fixed) ===" -ForegroundColor Cyan
& $gcloud iam workload-identity-pools providers create-oidc github `
    --location=global `
    --workload-identity-pool=github-actions `
    --issuer-uri="https://token.actions.githubusercontent.com" `
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.repository_owner=assertion.repository_owner" `
    --attribute-condition="assertion.repository_owner == '$GITHUB_USER'" `
    --project=$PROJECT `
    2>&1 | Where-Object { $_ -notmatch "^$" }

Write-Host ""
Write-Host "=== Fixing run.jobsExecuter role (correct name) ===" -ForegroundColor Cyan
& $gcloud projects add-iam-policy-binding $PROJECT `
    --member="serviceAccount:$SA" `
    --role="roles/run.developer" `
    2>&1 | Select-String "Updated|already|Error"

# The correct role for executing Cloud Run Jobs
& $gcloud projects add-iam-policy-binding $PROJECT `
    --member="serviceAccount:$SA" `
    --role="roles/cloudbuild.builds.editor" `
    2>&1 | Select-String "Updated|already|Error"

Write-Host ""
Write-Host "=== Your GitHub Secret values ===" -ForegroundColor Green
Write-Host ""
Write-Host "GCP_WIF_PROVIDER ="
Write-Host "projects/$PROJECT_NUM/locations/global/workloadIdentityPools/github-actions/providers/github"
Write-Host ""
Write-Host "GCP_SERVICE_ACCOUNT ="
Write-Host $SA
Write-Host ""
Write-Host "GCP_PROJECT_ID  = $PROJECT"
Write-Host "GCP_REGION      = asia-south1"
Write-Host "CLOUD_RUN_SERVICE       = ai-assistant-backend"
Write-Host "CLOUD_RUN_SERVICE_URL   = https://ai-assistant-backend-106071012091.asia-south1.run.app"
Write-Host "CHROMA_SERVICE_NAME     = chromadb"
Write-Host "GCP_ARTIFACT_REPO       = backend (Variable, not Secret)"
