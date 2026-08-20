$gcloud       = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT      = "android-ai-assistant-89cec"
$PROJECT_NUM  = "106071012091"
$SA           = "ai-assistant-backend@$PROJECT.iam.gserviceaccount.com"

# ── IMPORTANT ────────────────────────────────────────────────────────────────
# Replace YOUR_GITHUB_USERNAME with your actual GitHub username
# Example: if your repo is github.com/kotlinandfluttertutorial-lab/Android-AI-Assistant-Test
# then set: $GITHUB_USER = "kotlinandfluttertutorial-lab"
$GITHUB_USER  = "YOUR_GITHUB_USERNAME"
$REPO_NAME    = "Android-AI-Assistant-Test"
# ─────────────────────────────────────────────────────────────────────────────

Write-Host "=== Step 1: Create Workload Identity Pool ===" -ForegroundColor Cyan
& $gcloud iam workload-identity-pools create github-actions `
    --location=global `
    --display-name="GitHub Actions" `
    --project=$PROJECT `
    2>&1 | Where-Object { $_ -notmatch "WARNING" }

Write-Host ""
Write-Host "=== Step 2: Create OIDC Provider ===" -ForegroundColor Cyan
& $gcloud iam workload-identity-pools providers create-oidc github `
    --location=global `
    --workload-identity-pool=github-actions `
    --issuer-uri="https://token.actions.githubusercontent.com" `
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository" `
    --project=$PROJECT `
    2>&1 | Where-Object { $_ -notmatch "WARNING" }

Write-Host ""
Write-Host "=== Step 3: Bind GitHub repo to service account ===" -ForegroundColor Cyan
& $gcloud iam service-accounts add-iam-policy-binding $SA `
    --role="roles/iam.workloadIdentityUser" `
    --member="principalSet://iam.googleapis.com/projects/$PROJECT_NUM/locations/global/workloadIdentityPools/github-actions/attribute.repository/$GITHUB_USER/$REPO_NAME" `
    --project=$PROJECT `
    2>&1 | Select-String "Updated|Error|already"

Write-Host ""
Write-Host "=== Step 4: Grant Cloud Run deploy permissions ===" -ForegroundColor Cyan
& $gcloud projects add-iam-policy-binding $PROJECT `
    --member="serviceAccount:$SA" `
    --role="roles/run.developer" `
    2>&1 | Select-String "Updated|Error"

& $gcloud projects add-iam-policy-binding $PROJECT `
    --member="serviceAccount:$SA" `
    --role="roles/run.jobsExecuter" `
    2>&1 | Select-String "Updated|Error"

Write-Host ""
Write-Host "=== Done! ===" -ForegroundColor Green
Write-Host ""
Write-Host "Your GCP_WIF_PROVIDER value for GitHub Secrets:" -ForegroundColor Yellow
Write-Host "projects/$PROJECT_NUM/locations/global/workloadIdentityPools/github-actions/providers/github"
