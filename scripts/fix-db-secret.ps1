$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

# First disable version 1 of DATABASE_URL (the corrupt one with leading ?)
Write-Host "Disabling corrupt version 1..."
& $gcloud secrets versions disable 1 --secret=DATABASE_URL --project=$PROJECT 2>&1

# Confirm version 2 is enabled
Write-Host "Confirming version 2 is active..."
& $gcloud secrets versions list DATABASE_URL --project=$PROJECT 2>&1

# Now update the Cloud Run service to force a new revision
# This ensures Cloud Run picks up the new secret version
Write-Host ""
Write-Host "Updating Cloud Run to force new revision..."
& $gcloud run services update ai-assistant-backend `
    --region=asia-south1 `
    --project=$PROJECT `
    --update-secrets="DATABASE_URL=DATABASE_URL:2" `
    2>&1

Write-Host "Done."
