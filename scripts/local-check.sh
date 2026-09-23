#!/usr/bin/env bash
# ============================================================================
# local-check.sh — Local Development Validation Script (Linux / macOS)
# ============================================================================
#
# Purpose:
#   Validates the complete local development environment before pushing code.
#   Starts the Docker stack, waits for services to become healthy, checks
#   health endpoints, runs backend tests, and runs Android unit tests.
#
# Usage (run from the repository root):
#   ./scripts/local-check.sh               # full suite
#   ./scripts/local-check.sh --skip-docker # skip Docker start/health
#   ./scripts/local-check.sh --skip-android
#   ./scripts/local-check.sh --skip-backend
#   ./scripts/local-check.sh --health-only # only check service health
#
# Prerequisites:
#   - Docker (with Compose plugin) installed and daemon running
#   - .env.local exists (copy .env.local.example → .env.local and fill values)
#   - Java 17+ on PATH (for Gradle)
#   - Python venv at backend/venv311 or backend/venv
#
# Exit codes:
#   0  all checks passed
#   1  one or more checks failed
# ============================================================================

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT/docker-compose.local.yml"
ENV_LOCAL="$ROOT/.env.local"
BACKEND_DIR="$ROOT/backend"

SKIP_DOCKER=false
SKIP_ANDROID=false
SKIP_BACKEND=false
HEALTH_ONLY=false

for arg in "$@"; do
    case $arg in
        --skip-docker)  SKIP_DOCKER=true  ;;
        --skip-android) SKIP_ANDROID=true ;;
        --skip-backend) SKIP_BACKEND=true ;;
        --health-only)  HEALTH_ONLY=true; SKIP_BACKEND=true; SKIP_ANDROID=true ;;
    esac
done

# ── Python venv detection ─────────────────────────────────────────────────────
if   [ -f "$BACKEND_DIR/venv311/bin/python" ]; then PYTHON="$BACKEND_DIR/venv311/bin/python"
elif [ -f "$BACKEND_DIR/venv/bin/python"    ]; then PYTHON="$BACKEND_DIR/venv/bin/python"
else                                                 PYTHON="python3"
fi

# ── Colour helpers ────────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; GRAY='\033[0;37m'; NC='\033[0m'

header()  { echo ""; echo -e "${CYAN}============================================================${NC}"; echo -e "${CYAN}  $1${NC}"; echo -e "${CYAN}============================================================${NC}"; }
step()    { echo -e "\n${YELLOW}  >> $1${NC}"; }
pass()    { echo -e "${GREEN}  OK   $1${NC}"; }
fail()    { echo -e "${RED}  FAIL $1${NC}"; }
skip()    { echo -e "${GRAY}  --   $1${NC}"; }
info()    { echo -e "${GRAY}       $1${NC}"; }

PASS_COUNT=0; FAIL_COUNT=0; SKIP_COUNT=0
declare -A RESULTS

record_pass() { RESULTS["$1"]="PASS"; pass "$1"; ((PASS_COUNT++)); }
record_fail() { RESULTS["$1"]="FAIL"; fail "$1 FAILED"; ((FAIL_COUNT++)); }
record_skip() { RESULTS["$1"]="SKIP"; skip "$1 — $2"; ((SKIP_COUNT++)); }

# ── Preflight ─────────────────────────────────────────────────────────────────
header "Preflight"

if [ ! -f "$ENV_LOCAL" ]; then
    fail ".env.local not found"
    info "Run:  cp .env.local.example .env.local"
    info "Then fill in SECRET_KEY, AES_ENCRYPTION_KEY, GEMINI_API_KEY."
    exit 1
fi
pass ".env.local exists"

DOCKER_OK=false
if docker info > /dev/null 2>&1; then
    DOCKER_OK=true
    pass "Docker is running"
else
    if $SKIP_DOCKER; then
        skip "Docker" "not running — --skip-docker flag set"
    else
        fail "Docker is not running. Start Docker daemon first."
        exit 1
    fi
fi

# ── Docker stack ──────────────────────────────────────────────────────────────
header "Local Docker Stack"

if $SKIP_DOCKER || ! $DOCKER_OK; then
    record_skip "Docker stack" "--skip-docker"
else
    step "Starting services"
    cd "$ROOT"
    if docker compose -f "$COMPOSE_FILE" up -d; then
        record_pass "Docker stack start"
    else
        record_fail "Docker stack start"
        info "Check: docker compose -f docker-compose.local.yml logs"
        exit 1
    fi

    step "Waiting for services to become healthy (up to 120s)..."
    SERVICES=("postgres" "redis" "chromadb" "minio" "backend" "nginx")
    MAX_WAIT=120; ELAPSED=0; INTERVAL=5

    while [ "$ELAPSED" -lt "$MAX_WAIT" ]; do
        ALL_HEALTHY=true
        for SVC in "${SERVICES[@]}"; do
            STATE=$(docker compose -f "$COMPOSE_FILE" ps --format json 2>/dev/null \
                | python3 -c "import sys,json; data=sys.stdin.read(); items=[json.loads(l) for l in data.splitlines() if l]; svc=next((x for x in items if x.get('Service')=='$SVC'),None); print(svc.get('Health','') if svc else 'unknown')" 2>/dev/null || echo "unknown")
            if [ "$STATE" != "healthy" ]; then
                ALL_HEALTHY=false
                break
            fi
        done
        $ALL_HEALTHY && break
        sleep $INTERVAL
        ELAPSED=$((ELAPSED + INTERVAL))
        info "  ...waiting (${ELAPSED}/${MAX_WAIT}s)"
    done

    if $ALL_HEALTHY; then
        record_pass "All services healthy"
    else
        info "Some services may not yet be healthy — continuing with endpoint checks..."
    fi
fi

# ── Health endpoint checks ────────────────────────────────────────────────────
header "Service Health Checks"

if $SKIP_DOCKER || ! $DOCKER_OK; then
    record_skip "Health checks" "--skip-docker"
else
    check_health() {
        local name="$1" url="$2"
        step "$name"
        if curl -sf --max-time 10 "$url" > /dev/null 2>&1; then
            record_pass "$name"
        else
            record_fail "$name ($url)"
        fi
    }

    check_health "Nginx gateway"  "http://localhost:8080/health"
    check_health "FastAPI"        "http://localhost:8000/health"
    check_health "ChromaDB"       "http://127.0.0.1:8001/api/v1/heartbeat"
    check_health "MinIO"          "http://localhost:9000/minio/health/live"
    check_health "Prometheus"     "http://localhost:9090/-/healthy"
    check_health "Grafana"        "http://localhost:3000/api/health"
    check_health "Loki"           "http://localhost:3100/ready"
fi

# ── Backend tests ─────────────────────────────────────────────────────────────
header "Backend Tests"

if $SKIP_BACKEND; then
    record_skip "Backend unit tests"        "--skip-backend"
    record_skip "Backend integration tests" "--skip-backend"
else
    export SECRET_KEY="ci-local-check-secret-key-must-be-32-chars!"
    export DATABASE_URL="postgresql+asyncpg://testuser:testpass@localhost:5432/testdb"
    export REDIS_URL="redis://localhost:6379/0"
    export OPENAI_API_KEY="sk-test-not-real"
    export GEMINI_API_KEY="test-gemini-not-real"
    export ANTHROPIC_API_KEY="sk-ant-test-not-real"
    export MINIO_ENDPOINT="localhost:9000"
    export MINIO_ACCESS_KEY="minioadmin"
    export MINIO_SECRET_KEY="minioadmin123"
    export MINIO_BUCKET_NAME="test-bucket"
    export LOKI_URL=""
    export ENVIRONMENT="test"
    export LOG_LEVEL="WARNING"
    export AES_ENCRYPTION_KEY="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    step "Backend unit tests"
    cd "$BACKEND_DIR"
    if "$PYTHON" -m pytest tests/unit/ --tb=short -q --junit-xml=unit-test-results.xml; then
        record_pass "Backend unit tests"
    else
        record_fail "Backend unit tests"
    fi

    step "Backend integration tests (mocked)"
    if "$PYTHON" -m pytest tests/integration/ --tb=short -q --timeout=30 --junit-xml=integration-test-results.xml; then
        record_pass "Backend integration tests"
    else
        record_fail "Backend integration tests"
    fi
    cd "$ROOT"
fi

# ── Android tests ─────────────────────────────────────────────────────────────
header "Android Tests (local flavor)"

if $SKIP_ANDROID; then
    record_skip "Android KSP gate"   "--skip-android"
    record_skip "Android unit tests" "--skip-android"
else
    cd "$ROOT"

    step "Hilt KSP gate (kspLocalDebugKotlin)"
    if ./gradlew kspLocalDebugKotlin --no-daemon --quiet; then
        record_pass "Android KSP gate"
    else
        record_fail "Android KSP gate"
    fi

    step "Android unit tests (core-network + core-ai)"
    if ./gradlew :core-network:testLocalDebugUnitTest :core-ai:test --no-daemon --quiet --continue; then
        record_pass "Android unit tests"
    else
        record_fail "Android unit tests"
    fi
fi

# ── Summary ───────────────────────────────────────────────────────────────────
header "Summary"

for k in "${!RESULTS[@]}"; do
    case "${RESULTS[$k]}" in
        PASS) echo -e "${GREEN}  OK   $k${NC}" ;;
        FAIL) echo -e "${RED}  FAIL $k${NC}"   ;;
        SKIP) echo -e "${GRAY}  --   $k${NC}"  ;;
    esac
done

echo ""
echo -e "  Passed: ${PASS_COUNT}   Failed: ${FAIL_COUNT}   Skipped: ${SKIP_COUNT}"
echo ""

if [ "$FAIL_COUNT" -gt 0 ]; then
    echo -e "${RED}  Local check FAILED — fix ${FAIL_COUNT} issue(s) before pushing.${NC}"
    exit 1
else
    echo -e "${GREEN}  Local check PASSED — safe to push.${NC}"
    exit 0
fi
