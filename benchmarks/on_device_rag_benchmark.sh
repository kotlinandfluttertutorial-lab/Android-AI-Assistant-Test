#!/usr/bin/env bash
# =============================================================================
# on_device_rag_benchmark.sh
# =============================================================================
# Purpose : Installs the debug APK on a connected Android device via ADB,
#           triggers BenchmarkOnDeviceUseCase through an ADB shell broadcast,
#           captures the structured JSON result from logcat, and saves it to
#           benchmarks/results/benchmark_<timestamp>.json.
#
# Usage   : ./benchmarks/on_device_rag_benchmark.sh [options]
#
# Options :
#   -s <serial>   ADB device serial (required when multiple devices are connected)
#   -a <apk>      Path to the debug APK (default: app/build/outputs/apk/debug/app-debug.apk)
#   -o <dir>      Output directory for result JSON (default: benchmarks/results)
#   -h            Print this help message
#
# Prerequisites:
#   - ADB installed and on PATH (included with Android SDK Platform Tools)
#   - Device connected via USB or ADB over Wi-Fi with USB debugging enabled
#   - Gemma model file downloaded to the device via ManageModelsScreen before running
#   - jq installed for JSON formatting (optional — raw JSON is saved regardless)
#
# Example:
#   ./benchmarks/on_device_rag_benchmark.sh
#   ./benchmarks/on_device_rag_benchmark.sh -s emulator-5554 -a ./app-release.apk
#
# Output format (benchmarks/results/benchmark_<timestamp>.json):
#   {
#     "timestamp": "2026-08-25T14:32:00Z",
#     "device_model": "Pixel 8 Pro",
#     "chipset": "Google Tensor G3",
#     "accelerator": "NPU",
#     "gemma_variant": "Gemma 2B INT4",
#     "ttft_mean_ms": 420,
#     "ttft_p95_ms": 610,
#     "tokens_per_sec_mean": 18.4,
#     "tokens_per_sec_p95": 15.2,
#     "peak_ram_mb": 1842
#   }
# =============================================================================

set -euo pipefail

# ── Defaults ─────────────────────────────────────────────────────────────────
APK_PATH="app/build/outputs/apk/debug/app-debug.apk"
OUTPUT_DIR="benchmarks/results"
ADB_SERIAL=""
PACKAGE="com.aiassistant.app"
BENCHMARK_ACTION="com.aiassistant.app.ACTION_RUN_BENCHMARK"
LOGCAT_TAG="OnDeviceBenchmark"
TIMEOUT_SECONDS=300   # 10 iterations × ~30 s each, with headroom

# ── Argument parsing ──────────────────────────────────────────────────────────
usage() {
    sed -n '/^# Usage/,/^# =\+/p' "$0" | head -n -1
    exit 0
}

while getopts "s:a:o:h" opt; do
    case $opt in
        s) ADB_SERIAL="-s $OPTARG" ;;
        a) APK_PATH="$OPTARG" ;;
        o) OUTPUT_DIR="$OPTARG" ;;
        h) usage ;;
        *) echo "Unknown option -$OPTARG" >&2; exit 1 ;;
    esac
done

# ── Helpers ───────────────────────────────────────────────────────────────────
log()  { echo "[$(date '+%H:%M:%S')] $*"; }
fail() { echo "[ERROR] $*" >&2; exit 1; }

adb_cmd() { adb $ADB_SERIAL "$@"; }

# ── Pre-flight checks ─────────────────────────────────────────────────────────
log "=== On-Device RAG Benchmark ==="

command -v adb >/dev/null 2>&1 || fail "adb not found. Install Android SDK Platform Tools and add to PATH."

if ! adb_cmd devices | grep -q "device$"; then
    fail "No ADB device found. Connect a device and enable USB debugging."
fi

[[ -f "$APK_PATH" ]] || fail "APK not found at '$APK_PATH'. Build the debug APK first: ./gradlew assembleDebug"

mkdir -p "$OUTPUT_DIR"

# ── Install APK ───────────────────────────────────────────────────────────────
log "Installing APK: $APK_PATH"
adb_cmd install -r "$APK_PATH"
log "APK installed successfully."

# ── Clear logcat buffer ───────────────────────────────────────────────────────
log "Clearing logcat buffer..."
adb_cmd logcat -c

# ── Launch app and trigger benchmark ─────────────────────────────────────────
log "Triggering BenchmarkOnDeviceUseCase via broadcast intent..."
adb_cmd shell am broadcast \
    -a "$BENCHMARK_ACTION" \
    -n "${PACKAGE}/.receiver.BenchmarkReceiver" \
    --ez "auto_mode" true \
    2>/dev/null || true    # broadcast may not be registered in all builds; logcat is the fallback

# Alternatively launch the BenchmarkScreen activity directly:
adb_cmd shell am start \
    -n "${PACKAGE}/.MainActivity" \
    -d "aiassistant://open/ondevicerag/benchmark" \
    --ez "auto_run" true \
    2>/dev/null || true

# ── Capture benchmark result from logcat ──────────────────────────────────────
log "Waiting for benchmark result (timeout: ${TIMEOUT_SECONDS}s)..."

RESULT_JSON=""
START_TIME=$(date +%s)

while true; do
    ELAPSED=$(( $(date +%s) - START_TIME ))
    if [[ $ELAPSED -ge $TIMEOUT_SECONDS ]]; then
        fail "Timed out after ${TIMEOUT_SECONDS}s waiting for benchmark result. " \
             "Ensure the Gemma model is downloaded and the device meets minimum requirements."
    fi

    # Look for the structured JSON line emitted by BenchmarkOnDeviceUseCase
    LINE=$(adb_cmd logcat -d -s "$LOGCAT_TAG:I" 2>/dev/null \
           | grep -o '{.*"peak_ram_mb":[0-9]*.*}' \
           | tail -1 || true)

    if [[ -n "$LINE" ]]; then
        RESULT_JSON="$LINE"
        break
    fi

    sleep 2
done

# ── Save result ───────────────────────────────────────────────────────────────
TIMESTAMP=$(date -u '+%Y%m%dT%H%M%SZ')
OUTPUT_FILE="${OUTPUT_DIR}/benchmark_${TIMESTAMP}.json"

# Enrich with device metadata if available
DEVICE_MODEL=$(adb_cmd shell getprop ro.product.model 2>/dev/null | tr -d '\r' || echo "unknown")
ANDROID_VER=$(adb_cmd shell getprop ro.build.version.release 2>/dev/null | tr -d '\r' || echo "unknown")

# Merge device metadata into the result JSON (requires Python 3 as a fallback to jq)
if command -v jq >/dev/null 2>&1; then
    echo "$RESULT_JSON" | jq \
        --arg ts "$TIMESTAMP" \
        --arg dev "$DEVICE_MODEL" \
        --arg av "$ANDROID_VER" \
        '. + {"run_timestamp": $ts, "device_model": $dev, "android_version": $av}' \
        > "$OUTPUT_FILE"
else
    # Fallback: prepend metadata fields without jq
    python3 - <<PYEOF > "$OUTPUT_FILE"
import json, sys
data = json.loads("""$RESULT_JSON""")
data["run_timestamp"] = "$TIMESTAMP"
data["device_model"] = "$DEVICE_MODEL"
data["android_version"] = "$ANDROID_VER"
print(json.dumps(data, indent=2))
PYEOF
fi

log "Benchmark complete. Results saved to: $OUTPUT_FILE"
echo ""

# ── Print summary ─────────────────────────────────────────────────────────────
if command -v jq >/dev/null 2>&1; then
    echo "=== Benchmark Summary ==="
    jq -r '"Device      : " + .device_model,
           "Accelerator : " + (.accelerator // "unknown"),
           "Gemma       : " + (.gemma_variant // "unknown"),
           "TTFT p50    : " + (.ttft_mean_ms | tostring) + " ms",
           "TTFT p95    : " + (.ttft_p95_ms | tostring) + " ms",
           "Tokens/sec  : " + (.tokens_per_sec_mean | tostring),
           "Peak RAM    : " + (.peak_ram_mb | tostring) + " MB"' \
        "$OUTPUT_FILE"
else
    cat "$OUTPUT_FILE"
fi
echo ""
log "Full results: $OUTPUT_FILE"
log "Update docs/on-device-rag.md benchmark table with these numbers."
