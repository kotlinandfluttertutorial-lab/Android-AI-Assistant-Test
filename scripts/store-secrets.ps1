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

# Update GEMINI_API_KEY to the new working key
Store-Secret "GEMINI_API_KEY" "AIzaSyAEW_TTf-lfB-Tq07utHnBaEeWtGjzDH24"

Write-Host ""
Write-Host "=== All secrets in Secret Manager ==="
& $gcloud secrets list --project=$PROJECT 2>&1 | Where-Object { $_ -notmatch "WARNING" }
