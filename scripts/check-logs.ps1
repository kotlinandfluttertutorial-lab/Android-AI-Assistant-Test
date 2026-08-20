$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
& $gcloud run services logs read ai-assistant-backend `
    --region=asia-south1 `
    --project=android-ai-assistant-89cec `
    --limit=50 `
    2>&1
