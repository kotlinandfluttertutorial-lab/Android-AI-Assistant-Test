$gcloud = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

function Store-Secret($name, $value) {
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $value, [System.Text.Encoding]::UTF8)
    # Create if not exists (ignore error if already exists)
    & $gcloud secrets create $name --replication-policy=automatic --project=$PROJECT 2>&1 | Out-Null
    # Add new version
    $result = & $gcloud secrets versions add $name --data-file="$tmp" --project=$PROJECT 2>&1
    Remove-Item $tmp -Force
    if ($result -match "Created version") {
        Write-Host "✅ $name stored"
    } else {
        Write-Host "⚠️  $name : $($result -join ' ')"
    }
}

# Store GEMINI_API_KEY — pass the value via the GEMINI_API_KEY environment variable
# Usage: $env:GEMINI_API_KEY = "your-key-here" ; .\store-secrets.ps1
# Never hardcode the key value in this file.
if (-not $env:GEMINI_API_KEY) {
    Write-Host "❌ GEMINI_API_KEY environment variable is not set. Aborting." -ForegroundColor Red
    exit 1
}
Store-Secret "GEMINI_API_KEY" $env:GEMINI_API_KEY

Write-Host ""
Write-Host "=== All secrets in Secret Manager ==="
& $gcloud secrets list --project=$PROJECT 2>&1 | Where-Object { $_ -notmatch "WARNING" }
