$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

# ── Usage ─────────────────────────────────────────────────────────────────────
# $env:HMAC_ACCESS_ID = "GOOG1E..."
# $env:HMAC_SECRET    = "your-hmac-secret"
# .\scripts\update-minio-secrets.ps1
# ─────────────────────────────────────────────────────────────────────────────

if (-not $env:HMAC_ACCESS_ID) {
    Write-Host "❌ HMAC_ACCESS_ID environment variable is not set." -ForegroundColor Red
    Write-Host "   Run: `$env:HMAC_ACCESS_ID = `"<your Access ID>`""
    exit 1
}

if (-not $env:HMAC_SECRET) {
    Write-Host "❌ HMAC_SECRET environment variable is not set." -ForegroundColor Red
    Write-Host "   Run: `$env:HMAC_SECRET = `"<your Secret>`""
    exit 1
}

function Update-Secret($name, $value) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $value, [System.Text.Encoding]::UTF8)
    $result = & $gcloud secrets versions add $name --data-file="$tmp" --project=$PROJECT 2>&1
    Remove-Item $tmp -Force
    if ($result -match "Created version") {
        Write-Host "✅ $name updated in Secret Manager"
    } else {
        Write-Host "⚠️  $name : $($result -join ' ')"
    }
}

Write-Host "=== Updating MINIO secrets with real HMAC values ===" -ForegroundColor Cyan
Write-Host ""

Update-Secret "MINIO_ACCESS_KEY" $env:HMAC_ACCESS_ID
Update-Secret "MINIO_SECRET_KEY" $env:HMAC_SECRET

Write-Host ""
Write-Host "✅ Done. Now redeploy Cloud Run to pick up the new versions:"
Write-Host "   .\scripts\deploy-cloud-run.ps1"
Write-Host ""
Write-Host "After redeploy, verify file upload works:"
Write-Host "   curl -X POST https://ai-assistant-backend-106071012091.asia-south1.run.app/api/v1/rag/upload \"
Write-Host "     -H `"Authorization: Bearer YOUR_JWT`" \"
Write-Host "     -F `"file=@your-document.pdf`""
