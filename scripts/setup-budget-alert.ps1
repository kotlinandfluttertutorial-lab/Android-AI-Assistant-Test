$gcloud  = "C:\Users\admin\AppData\Local\Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd"
$PROJECT = "android-ai-assistant-89cec"

# ── Usage ─────────────────────────────────────────────────────────────────────
# 1. Find your billing account ID:
#      gcloud billing accounts list
# 2. Set it below and run this script:
#      .\scripts\setup-budget-alert.ps1
# ─────────────────────────────────────────────────────────────────────────────

# Get billing account linked to the project
Write-Host "=== Finding billing account for project $PROJECT ===" -ForegroundColor Cyan
$billingInfo = & $gcloud billing projects describe $PROJECT --format="value(billingAccountName)" 2>&1
$BILLING_ACCOUNT = $billingInfo -replace "billingAccounts/", ""
Write-Host "Billing account: $BILLING_ACCOUNT"
Write-Host ""

if (-not $BILLING_ACCOUNT -or $BILLING_ACCOUNT -match "Error") {
    Write-Host "❌ Could not auto-detect billing account." -ForegroundColor Red
    Write-Host "   Run: gcloud billing accounts list"
    Write-Host "   Then set: `$BILLING_ACCOUNT = `"XXXXXX-XXXXXX-XXXXXX`""
    exit 1
}

# ── Budget: ₹800/month with alerts at 50%, 90%, 100% ────────────────────────
Write-Host "=== Creating budget alert (800 INR/month) ===" -ForegroundColor Cyan

& $gcloud billing budgets create `
    --billing-account="$BILLING_ACCOUNT" `
    --display-name="AI Assistant Monthly Budget" `
    --budget-amount="800INR" `
    --threshold-rule="percent=0.5,basis=CURRENT_SPEND" `
    --threshold-rule="percent=0.9,basis=CURRENT_SPEND" `
    --threshold-rule="percent=1.0,basis=CURRENT_SPEND" `
    --filter-projects="projects/$PROJECT" `
    2>&1

Write-Host ""
Write-Host "✅ Budget alert created." -ForegroundColor Green
Write-Host ""
Write-Host "You will receive email alerts when spending reaches:" -ForegroundColor Yellow
Write-Host "  50%  → ₹400  (heads-up)"
Write-Host "  90%  → ₹720  (warning)"
Write-Host " 100%  → ₹800  (hard limit approached)"
Write-Host ""
Write-Host "To view budgets:"
Write-Host "  GCP Console → Billing → Budgets & alerts"
Write-Host "  https://console.cloud.google.com/billing/$BILLING_ACCOUNT/budgets"
