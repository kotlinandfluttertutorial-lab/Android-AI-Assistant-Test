# ============================================================================
# store-secrets.ps1 — Store backend secrets in GCP Secret Manager
# ============================================================================
#
# Each environment (stage / production) uses SEPARATE secrets so a Stage
# deployment can never accidentally use a Production key and vice versa.
#
# Naming convention:
#   aiassistant-stage-<name>   → Stage environment
#   aiassistant-prod-<name>    → Production environment
#
# Usage examples:
#
#   Store Stage Gemini key:
#     $env:GEMINI_API_KEY = "AIza..."
#     .\scripts\store-secrets.ps1 -Environment stage -Secret gemini-api-key
#
#   Store Production Gemini key:
#     $env:GEMINI_API_KEY = "AIza..."
#     .\scripts\store-secrets.ps1 -Environment production -Secret gemini-api-key
#
#   Store all secrets for an environment:
#     $env:GEMINI_API_KEY = "..."
#     $env:SECRET_KEY = "..."
#     $env:AES_ENCRYPTION_KEY = "..."
#     $env:DATABASE_URL = "..."
#     $env:REDIS_URL = "..."
#     .\scripts\store-secrets.ps1 -Environment stage -All
#
# SECURITY:
#   - Never hardcode key values in this file.
#   - Pass values via environment variables only.
#   - Each environment MUST have its own independent Gemini API key so that:
#       • Stage quota exhaustion does not affect Production.
#       • A compromised Stage key cannot be used against Production.
#       • API usage can be monitored and billed separately per environment.
# ============================================================================

param(
    [ValidateSet("stage", "production")]
    [string]$Environment = "stage",

    [ValidateSet(
        "gemini-api-key",
        "openai-api-key",
        "secret-key",
        "aes-encryption-key",
        "database-url",
        "redis-url",
        "minio-access-key",
        "minio-secret-key"
    )]
    [string]$Secret = "",

    [switch]$All
)

$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

# Map environment name to the secret name prefix
$prefix = if ($Environment -eq "production") { "aiassistant-prod" } else { "aiassistant-stage" }

function Store-Secret([string]$secretName, [string]$envVar) {
    $value = [System.Environment]::GetEnvironmentVariable($envVar)
    if (-not $value) {
        Write-Host "  SKIP $secretName — env var '$envVar' is not set" -ForegroundColor Yellow
        return
    }
    $fullName = "$prefix-$secretName"
    $tmp = [System.IO.Path]::GetTempFileName()
    [System.IO.File]::WriteAllText($tmp, $value, [System.Text.Encoding]::UTF8)

    # Create secret if it doesn't exist yet
    & $gcloud secrets describe $fullName --project=$PROJECT 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        & $gcloud secrets create $fullName --replication-policy=automatic --project=$PROJECT 2>&1 | Out-Null
        Write-Host "  Created secret $fullName" -ForegroundColor Gray
    }

    # Add a new version
    $result = & $gcloud secrets versions add $fullName --data-file="$tmp" --project=$PROJECT 2>&1
    Remove-Item $tmp -Force

    if ($result -match "Created version") {
        Write-Host "  OK  $fullName stored" -ForegroundColor Green
    } else {
        Write-Host "  WARN $fullName : $($result -join ' ')" -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "=== Storing secrets for environment: $Environment ===" -ForegroundColor Cyan
Write-Host "    Secret prefix: $prefix" -ForegroundColor Gray
Write-Host ""

if ($All) {
    # ── Gemini API key (REQUIRED — each environment must have its own key) ──
    Store-Secret "gemini-api-key"      "GEMINI_API_KEY"
    Store-Secret "openai-api-key"      "OPENAI_API_KEY"

    # ── Auth & encryption ──────────────────────────────────────────────────
    Store-Secret "secret-key"          "SECRET_KEY"
    Store-Secret "aes-encryption-key"  "AES_ENCRYPTION_KEY"

    # ── Database & cache ───────────────────────────────────────────────────
    Store-Secret "database-url"        "DATABASE_URL"
    Store-Secret "redis-url"           "REDIS_URL"

    # ── Storage ────────────────────────────────────────────────────────────
    Store-Secret "minio-access-key"    "MINIO_ACCESS_KEY"
    Store-Secret "minio-secret-key"    "MINIO_SECRET_KEY"

} elseif ($Secret) {
    $envVarMap = @{
        "gemini-api-key"      = "GEMINI_API_KEY"
        "openai-api-key"      = "OPENAI_API_KEY"
        "secret-key"          = "SECRET_KEY"
        "aes-encryption-key"  = "AES_ENCRYPTION_KEY"
        "database-url"        = "DATABASE_URL"
        "redis-url"           = "REDIS_URL"
        "minio-access-key"    = "MINIO_ACCESS_KEY"
        "minio-secret-key"    = "MINIO_SECRET_KEY"
    }
    Store-Secret $Secret $envVarMap[$Secret]
} else {
    Write-Host "Specify -Secret <name> or -All" -ForegroundColor Red
    Write-Host ""
    Write-Host "Example — store Stage Gemini key:"
    Write-Host '  $env:GEMINI_API_KEY = "AIza..."'
    Write-Host '  .\scripts\store-secrets.ps1 -Environment stage -Secret gemini-api-key'
    Write-Host ""
    Write-Host "Example — store Production Gemini key:"
    Write-Host '  $env:GEMINI_API_KEY = "AIzaSy..."   # different key from stage!'
    Write-Host '  .\scripts\store-secrets.ps1 -Environment production -Secret gemini-api-key'
    exit 1
}

Write-Host ""
Write-Host "=== Secrets for $prefix in Secret Manager ===" -ForegroundColor Cyan
& $gcloud secrets list --project=$PROJECT --filter="name:$prefix" 2>&1 |
    Where-Object { $_ -notmatch "WARNING" }
