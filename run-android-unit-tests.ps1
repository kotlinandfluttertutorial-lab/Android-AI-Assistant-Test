# =============================================================================
# run-android-unit-tests.ps1
# =============================================================================
# Local equivalent of the android-unit-tests GitHub Actions job.
#
# Usage (from the project root in PowerShell):
#   .\run-android-unit-tests.ps1
#
# Optional flags:
#   -Module   <name>   Run tests for a single module only, e.g. -Module domain
#   -Continue          Keep going after test failures (mirrors --continue flag)
#   -Stacktrace        Print full Gradle stack traces (mirrors --stacktrace flag)
#   -OpenReport        Open the HTML report in the default browser when done
#
# What it does:
#   1. Verifies gradlew.bat is present
#   2. Checks google-services.json exists (writes a CI placeholder if missing)
#   3. Runs ./gradlew test (or :module:test for a single module)
#   4. Collects all JUnit XML results and prints a pass/fail summary
#   5. Prints the path to the HTML report so you can open it
# =============================================================================

[CmdletBinding()]
param(
    [string]$Module      = "",
    [switch]$Continue    = $true,
    [switch]$Stacktrace  = $true,
    [switch]$OpenReport  = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$ProjectRoot = $PSScriptRoot
Set-Location $ProjectRoot

# ─── Colour helpers ───────────────────────────────────────────────────────────

function Write-Header([string]$msg) {
    Write-Host ""
    Write-Host ("=" * 70) -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host ("=" * 70) -ForegroundColor Cyan
}

function Write-Step([string]$msg) {
    Write-Host ""
    Write-Host ">> $msg" -ForegroundColor Yellow
}

function Write-Ok([string]$msg)   { Write-Host "  [OK] $msg"   -ForegroundColor Green }
function Write-Warn([string]$msg) { Write-Host "  [WARN] $msg" -ForegroundColor Yellow }
function Write-Fail([string]$msg) { Write-Host "  [FAIL] $msg" -ForegroundColor Red }

# ─── Step 1: gradlew check ────────────────────────────────────────────────────

Write-Header "Android Unit Tests — Local Runner"

Write-Step "Checking project structure..."

$gradlew = Join-Path $ProjectRoot "gradlew.bat"
if (-not (Test-Path $gradlew)) {
    Write-Fail "gradlew.bat not found at: $gradlew"
    Write-Host "  Run this script from the project root directory." -ForegroundColor Red
    exit 1
}
Write-Ok "gradlew.bat found"

# ─── Step 2: google-services.json ─────────────────────────────────────────────

Write-Step "Checking google-services.json..."

$gsJson = Join-Path $ProjectRoot "app\google-services.json"
if (Test-Path $gsJson) {
    Write-Ok "google-services.json exists — using real file"
} else {
    Write-Warn "google-services.json missing — writing CI placeholder"
    $placeholder = @'
{"project_info":{"project_number":"000000000000","project_id":"ci-placeholder","storage_bucket":"ci-placeholder.appspot.com"},"client":[{"client_info":{"mobilesdk_app_id":"1:000000000000:android:0000000000000000000000","android_client_info":{"package_name":"com.aiassistant"}},"oauth_client":[],"api_key":[{"current_key":"CI_PLACEHOLDER_KEY"}],"services":{"appinvite_service":{"other_platform_oauth_client":[]}}},{"client_info":{"mobilesdk_app_id":"1:000000000000:android:1111111111111111111111","android_client_info":{"package_name":"com.aiassistant.debug"}},"oauth_client":[],"api_key":[{"current_key":"CI_PLACEHOLDER_KEY"}],"services":{"appinvite_service":{"other_platform_oauth_client":[]}}}],"configuration_version":"1"}
'@
    Set-Content -Path $gsJson -Value $placeholder.Trim() -Encoding UTF8
    Write-Ok "Placeholder google-services.json written to app\"
}

# ─── Step 3: Build Gradle command ─────────────────────────────────────────────

Write-Step "Building Gradle command..."

if ($Module -ne "") {
    # Single-module mode: e.g. :domain:test
    $gradleTask = ":${Module}:test"
    Write-Ok "Target: $gradleTask (single module)"
} else {
    # All modules
    $gradleTask = "test"
    Write-Ok "Target: test (all modules)"
}

$gradleArgs = @($gradleTask)
if ($Stacktrace) { $gradleArgs += "--stacktrace" }
if ($Continue)   { $gradleArgs += "--continue"   }

# Quieter output — show warnings and errors but suppress info noise
$gradleArgs += "--warning-mode=all"

Write-Host ""
Write-Host "  Command: gradlew.bat $($gradleArgs -join ' ')" -ForegroundColor DarkCyan

# ─── Step 4: Run tests ────────────────────────────────────────────────────────

Write-Step "Running tests..."
$startTime = Get-Date

& "$gradlew" @gradleArgs
$gradleExitCode = $LASTEXITCODE

$elapsed = (Get-Date) - $startTime
$elapsedStr = "{0:mm\:ss}" -f $elapsed

# ─── Step 5: Parse JUnit XML results ──────────────────────────────────────────

Write-Step "Collecting test results..."

$xmlFiles = Get-ChildItem -Path $ProjectRoot `
    -Filter "*.xml" `
    -Recurse `
    -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "build[/\\]test-results[/\\]test" }

$totalTests   = 0
$totalPassed  = 0
$totalFailed  = 0
$totalSkipped = 0
$totalErrors  = 0
$failedTests  = @()

foreach ($file in $xmlFiles) {
    try {
        [xml]$xml = Get-Content $file.FullName -Encoding UTF8
        foreach ($suite in $xml.testsuite) {
        $tests    = if ($suite.tests)    { [int]$suite.tests }    else { 0 }
            $failures = if ($suite.failures) { [int]$suite.failures } else { 0 }
            $errors   = if ($suite.errors)   { [int]$suite.errors }   else { 0 }
            $skipped  = if ($suite.skipped)  { [int]$suite.skipped }  else { 0 }

            $totalTests   += $tests
            $totalFailed  += $failures
            $totalErrors  += $errors
            $totalSkipped += $skipped

            # Collect individual failure names for the summary
            foreach ($tc in $suite.testcase) {
                if ($tc.failure -or $tc.error) {
                    $failedTests += "  - $($suite.name).$($tc.name)"
                }
            }
        }
    } catch {
        Write-Warn "Could not parse: $($file.FullName)"
    }
}

$totalPassed = $totalTests - $totalFailed - $totalErrors - $totalSkipped

# ─── Step 6: Print summary ────────────────────────────────────────────────────

Write-Header "Test Summary"

Write-Host "  Time elapsed : $elapsedStr" -ForegroundColor White
Write-Host "  XML reports  : $($xmlFiles.Count) file(s) found" -ForegroundColor White
Write-Host ""
Write-Host ("  Total   : {0,6}" -f $totalTests)   -ForegroundColor White
Write-Host ("  Passed  : {0,6}" -f $totalPassed)  -ForegroundColor Green
Write-Host ("  Failed  : {0,6}" -f ($totalFailed + $totalErrors)) -ForegroundColor $(if ($totalFailed + $totalErrors -gt 0) { "Red" } else { "Green" })
Write-Host ("  Skipped : {0,6}" -f $totalSkipped) -ForegroundColor DarkYellow

if ($failedTests.Count -gt 0) {
    Write-Host ""
    Write-Host "  Failed tests:" -ForegroundColor Red
    $failedTests | ForEach-Object { Write-Host $_ -ForegroundColor Red }
}

# ─── Step 7: HTML report locations ────────────────────────────────────────────

Write-Step "HTML report locations:"

$htmlReports = Get-ChildItem -Path $ProjectRoot `
    -Filter "index.html" `
    -Recurse `
    -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -match "build[/\\]reports[/\\]tests" }

if ($htmlReports.Count -gt 0) {
    foreach ($r in $htmlReports) {
        Write-Host "  $($r.FullName)" -ForegroundColor DarkCyan
    }

    if ($OpenReport) {
        # Open the first report found
        $firstReport = $htmlReports[0].FullName
        Write-Host ""
        Write-Host "  Opening: $firstReport" -ForegroundColor Cyan
        Start-Process $firstReport
    } else {
        Write-Host ""
        Write-Host "  Tip: run with -OpenReport to open the first report automatically." -ForegroundColor DarkGray
    }
} else {
    Write-Warn "No HTML reports found — tests may not have run or Gradle task failed early."
}

# ─── Step 8: Final exit ───────────────────────────────────────────────────────

Write-Host ""

if ($gradleExitCode -ne 0 -or ($totalFailed + $totalErrors) -gt 0) {
    Write-Fail "BUILD FAILED  (Gradle exit: $gradleExitCode, test failures: $($totalFailed + $totalErrors))"
    Write-Host ""
    exit 1
} else {
    Write-Ok "BUILD PASSED  — all $totalPassed test(s) passed in $elapsedStr"
    Write-Host ""
    exit 0
}
