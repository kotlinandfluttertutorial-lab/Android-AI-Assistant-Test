$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run jobs executions describe alembic-migrate-jr9ld `
    --region=asia-south1 `
    --project=android-ai-assistant-89cec `
    2>&1 | Select-Object -Last 5

Write-Host ""
Write-Host "=== Logs ==="
& $gcloud logging read `
    'resource.type="cloud_run_job" AND resource.labels.job_name="alembic-migrate"' `
    --limit=30 `
    --format="value(textPayload)" `
    --project=android-ai-assistant-89cec `
    2>&1 | Where-Object { $_ -notmatch "^$|WARNING" }
