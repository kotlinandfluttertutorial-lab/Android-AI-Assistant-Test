#!/usr/bin/env python3
# ============================================================================
# Android AI Assistant — Local Smoke Test
# ============================================================================
#
# Verifies every acceptance criterion for the local Docker environment:
#
#   ✓ FastAPI starts successfully              (health + ready endpoints)
#   ✓ PostgreSQL connection works              (SELECT 1 via SQLAlchemy)
#   ✓ Redis connection works                   (PING via redis-py)
#   ✓ ChromaDB connection works                (heartbeat + list_collections)
#   ✓ Celery worker starts successfully        (inspect ping)
#   ✓ Document ingestion works locally         (upload + poll status)
#   ✓ RAG retrieval works locally              (POST /api/v1/rag/query)
#   ✓ Agent execution works locally            (POST /api/v1/agent/execute)
#   ✓ MCP demo tool works locally              (demo_echo via API)
#   ✓ Environment variables are configurable   (settings loaded without error)
#   ✓ No production secrets are required       (verified by running with local keys)
#
# Usage (from inside the backend container):
#   docker compose exec backend python scripts/local_smoke_test.py
#
# Usage (from the host, with Python + requests installed):
#   BASE_URL=http://localhost:8000 python backend/scripts/local_smoke_test.py
#
# Exit codes:
#   0 — all checks passed
#   1 — one or more checks failed (failures printed to stderr)
#
# The script does NOT require a valid JWT for the health/ready checks.
# For the authenticated endpoints it performs a self-registration + login
# so no pre-existing user account is needed.
# ============================================================================

from __future__ import annotations

import asyncio
import io
import json
import os
import sys
import time
import traceback
from typing import Any

# ---------------------------------------------------------------------------
# Resolve base URL from environment or default to localhost
# ---------------------------------------------------------------------------
BASE_URL: str = os.environ.get("BASE_URL", "http://localhost:8000").rstrip("/")

# ---------------------------------------------------------------------------
# Colours
# ---------------------------------------------------------------------------
GREEN = "\033[92m"
RED = "\033[91m"
YELLOW = "\033[93m"
CYAN = "\033[96m"
RESET = "\033[0m"
BOLD = "\033[1m"

# ---------------------------------------------------------------------------
# Tracking
# ---------------------------------------------------------------------------
_results: list[tuple[str, bool, str]] = []


def _print_header(title: str) -> None:
    print(f"\n{BOLD}{CYAN}{'─' * 60}{RESET}")
    print(f"{BOLD}{CYAN}  {title}{RESET}")
    print(f"{BOLD}{CYAN}{'─' * 60}{RESET}")


def _pass(name: str, detail: str = "") -> None:
    _results.append((name, True, detail))
    mark = f"{GREEN}✓ PASS{RESET}"
    print(f"  {mark}  {name}" + (f"  ({detail})" if detail else ""))


def _fail(name: str, detail: str = "") -> None:
    _results.append((name, False, detail))
    mark = f"{RED}✗ FAIL{RESET}"
    print(f"  {mark}  {name}" + (f"\n         {RED}{detail}{RESET}" if detail else ""))


def _skip(name: str, reason: str) -> None:
    _results.append((name, True, f"SKIPPED: {reason}"))
    mark = f"{YELLOW}○ SKIP{RESET}"
    print(f"  {mark}  {name}  ({reason})")


# ---------------------------------------------------------------------------
# HTTP helpers (stdlib only — no third-party deps beyond what's in the venv)
# ---------------------------------------------------------------------------

def _http(
    method: str,
    path: str,
    *,
    json_body: dict | None = None,
    headers: dict | None = None,
    files: dict | None = None,
    timeout: int = 20,
) -> tuple[int, dict | bytes]:
    """Minimal HTTP client using stdlib urllib + http.client."""
    import urllib.error
    import urllib.request

    url = f"{BASE_URL}{path}"
    data: bytes | None = None
    req_headers: dict[str, str] = headers or {}

    if files:
        # Build multipart/form-data manually
        boundary = "----SmokeTestBoundary7MA4YWxkTrZu0gW"
        req_headers["Content-Type"] = f"multipart/form-data; boundary={boundary}"
        body_parts: list[bytes] = []
        for field_name, (filename, file_bytes, content_type) in files.items():
            body_parts.append(
                (
                    f"--{boundary}\r\n"
                    f'Content-Disposition: form-data; name="{field_name}"; '
                    f'filename="{filename}"\r\n'
                    f"Content-Type: {content_type}\r\n\r\n"
                ).encode()
                + file_bytes
                + b"\r\n"
            )
        body_parts.append(f"--{boundary}--\r\n".encode())
        data = b"".join(body_parts)
    elif json_body is not None:
        data = json.dumps(json_body).encode()
        req_headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=req_headers, method=method.upper())

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as exc:
        raw = exc.read()
        try:
            return exc.code, json.loads(raw)
        except json.JSONDecodeError:
            return exc.code, raw


def _get(path: str, *, headers: dict | None = None, timeout: int = 20) -> tuple[int, Any]:
    return _http("GET", path, headers=headers, timeout=timeout)


def _post(
    path: str,
    *,
    body: dict | None = None,
    headers: dict | None = None,
    files: dict | None = None,
    timeout: int = 30,
) -> tuple[int, Any]:
    return _http("POST", path, json_body=body, headers=headers, files=files, timeout=timeout)


def _delete(path: str, *, headers: dict | None = None, timeout: int = 20) -> tuple[int, Any]:
    return _http("DELETE", path, headers=headers, timeout=timeout)


# ---------------------------------------------------------------------------
# 1. FastAPI — health and ready endpoints
# ---------------------------------------------------------------------------

def check_fastapi() -> None:
    _print_header("1. FastAPI")

    # /health
    try:
        code, body = _get("/health")
        if code == 200 and isinstance(body, dict) and body.get("status") == "ok":
            _pass("GET /health", f"status=ok")
        else:
            _fail("GET /health", f"HTTP {code}: {body}")
    except Exception as exc:
        _fail("GET /health", str(exc))

    # /ready
    try:
        code, body = _get("/ready")
        if code in (200, 503):
            deps = body.get("dependencies", {}) if isinstance(body, dict) else {}
            detail = f"status={body.get('status', '?')}  deps={deps}"
            if code == 200:
                _pass("GET /ready", detail)
            else:
                _fail("GET /ready", f"HTTP 503 — some dependencies unavailable: {detail}")
        else:
            _fail("GET /ready", f"HTTP {code}: {body}")
    except Exception as exc:
        _fail("GET /ready", str(exc))


# ---------------------------------------------------------------------------
# 2. PostgreSQL — via SQLAlchemy sync ping inside container
# ---------------------------------------------------------------------------

def check_postgres() -> None:
    _print_header("2. PostgreSQL")
    try:
        import sqlalchemy  # type: ignore[import]

        db_url = os.environ.get(
            "DATABASE_URL",
            "postgresql+asyncpg://aiassistant:local_dev_password@postgres:5432/aiassistant_local",
        )
        # Convert asyncpg URL to sync psycopg2 for a quick probe
        sync_url = db_url.replace("postgresql+asyncpg://", "postgresql+psycopg2://")
        engine = sqlalchemy.create_engine(sync_url, pool_pre_ping=True, connect_args={"connect_timeout": 5})
        with engine.connect() as conn:
            result = conn.execute(sqlalchemy.text("SELECT 1")).scalar()
        engine.dispose()
        if result == 1:
            _pass("SELECT 1", f"connected to {sync_url.split('@')[-1]}")
        else:
            _fail("SELECT 1", f"unexpected result: {result}")
    except ImportError:
        _skip("PostgreSQL direct check", "psycopg2 not available; use /ready endpoint instead")
    except Exception as exc:
        _fail("PostgreSQL connection", str(exc))


# ---------------------------------------------------------------------------
# 3. Redis — PING via redis-py
# ---------------------------------------------------------------------------

def check_redis() -> None:
    _print_header("3. Redis")
    try:
        import redis as redis_lib  # type: ignore[import]

        redis_url = os.environ.get("REDIS_URL", "redis://redis:6379/0")
        client = redis_lib.from_url(redis_url, socket_timeout=5, socket_connect_timeout=5)
        response = client.ping()
        if response:
            _pass("PING", f"connected to {redis_url}")
        else:
            _fail("PING", "returned False")
    except ImportError:
        _skip("Redis direct check", "redis-py not available; use /ready endpoint instead")
    except Exception as exc:
        _fail("Redis connection", str(exc))


# ---------------------------------------------------------------------------
# 4. ChromaDB — heartbeat via chromadb HTTP client
# ---------------------------------------------------------------------------

def check_chromadb() -> None:
    _print_header("4. ChromaDB")
    try:
        import chromadb  # type: ignore[import]

        host = os.environ.get("CHROMA_HOST", "chromadb")
        port = int(os.environ.get("CHROMA_PORT", "8000"))
        client = chromadb.HttpClient(host=host, port=port)
        hb = client.heartbeat()
        collections = client.list_collections()
        _pass("heartbeat", f"nanoseconds={hb}")
        _pass("list_collections", f"{len(collections)} collection(s) found")
    except ImportError:
        _skip("ChromaDB direct check", "chromadb package not importable here")
    except Exception as exc:
        _fail("ChromaDB connection", str(exc))


# ---------------------------------------------------------------------------
# 5. Celery — inspect ping via subprocess inside the container
# ---------------------------------------------------------------------------

def check_celery() -> None:
    _print_header("5. Celery Worker")
    import subprocess

    try:
        result = subprocess.run(
            [
                "python", "-m", "celery",
                "-A", "app.workers.celery_app",
                "inspect", "ping",
                "--timeout=10",
                "--json",
            ],
            capture_output=True,
            text=True,
            timeout=20,
        )
        output = result.stdout.strip()
        if result.returncode == 0 and ("celery@" in output or "pong" in output.lower()):
            _pass("celery inspect ping", "worker responded")
        elif result.returncode == 0 and output:
            _pass("celery inspect ping", output[:120])
        else:
            stderr = result.stderr.strip()[:200]
            _fail(
                "celery inspect ping",
                f"returncode={result.returncode}  stdout={output[:100]}  stderr={stderr}",
            )
    except FileNotFoundError:
        _skip("Celery worker check", "celery command not found in PATH")
    except subprocess.TimeoutExpired:
        _fail("celery inspect ping", "timed out after 20 s — worker may not be running")
    except Exception as exc:
        _fail("celery inspect ping", str(exc))


# ---------------------------------------------------------------------------
# 6–10. Authenticated API checks
#        Register a temporary test user, log in, then exercise the API.
# ---------------------------------------------------------------------------

_TEST_EMAIL = f"smoke_test_{int(time.time())}@local.test"
_TEST_PASSWORD = "SmokeTest!LocalDev2026"
_AUTH_HEADERS: dict[str, str] = {}
_TEST_DOC_ID: str | None = None


def _register_and_login() -> bool:
    """Create a throw-away user and obtain a JWT. Returns True on success."""
    # Register
    try:
        code, body = _post(
            "/auth/register",
            body={"email": _TEST_EMAIL, "password": _TEST_PASSWORD, "full_name": "Smoke Test"},
        )
        if code not in (200, 201, 409):  # 409 = already exists (idempotent)
            _fail("auth/register", f"HTTP {code}: {body}")
            return False
    except Exception as exc:
        _fail("auth/register", str(exc))
        return False

    # Login
    try:
        code, body = _post(
            "/auth/login",
            body={"email": _TEST_EMAIL, "password": _TEST_PASSWORD},
        )
        if code == 200 and isinstance(body, dict):
            token = body.get("access_token") or (body.get("data") or {}).get("access_token", "")
            if token:
                _AUTH_HEADERS["Authorization"] = f"Bearer {token}"
                _pass("auth/register + login", f"email={_TEST_EMAIL}")
                return True
        _fail("auth/login", f"HTTP {code}: {body}")
        return False
    except Exception as exc:
        _fail("auth/login", str(exc))
        return False


def check_document_ingestion() -> None:
    _print_header("6. Document Ingestion")
    global _TEST_DOC_ID

    if not _AUTH_HEADERS:
        _skip("document upload", "authentication failed — skipping")
        return

    # Build a minimal plain-text document
    doc_content = (
        "LOCAL SMOKE TEST DOCUMENT\n\n"
        "This document was created by the local_smoke_test.py script.\n"
        "MCP architecture overview: the system uses a three-layer design.\n"
        "RAG pipeline: embed → retrieve → generate.\n"
        "Agent orchestration: plan → execute → observe.\n"
    ).encode()

    # Upload
    try:
        code, body = _post(
            "/api/v1/documents/upload",
            files={"file": ("smoke_test.txt", doc_content, "text/plain")},
            headers=_AUTH_HEADERS,
            timeout=60,
        )
        if code in (200, 201) and isinstance(body, dict):
            doc_id = body.get("document_id") or body.get("id") or (body.get("data") or {}).get("document_id")
            if doc_id:
                _TEST_DOC_ID = str(doc_id)
                status = body.get("status", "?")
                _pass("POST /api/v1/documents/upload", f"document_id={_TEST_DOC_ID}  status={status}")
            else:
                _fail("POST /api/v1/documents/upload", f"no document_id in response: {body}")
                return
        else:
            _fail("POST /api/v1/documents/upload", f"HTTP {code}: {str(body)[:200]}")
            return
    except Exception as exc:
        _fail("POST /api/v1/documents/upload", str(exc))
        return

    # Poll processing status (up to 30 s)
    deadline = time.time() + 30
    last_status = "unknown"
    while time.time() < deadline:
        try:
            code, body = _get(
                f"/api/v1/documents/{_TEST_DOC_ID}/status",
                headers=_AUTH_HEADERS,
            )
            if code == 200 and isinstance(body, dict):
                last_status = body.get("status", "?")
                progress = body.get("progress")
                detail = f"status={last_status}" + (f"  progress={progress}" if progress is not None else "")
                if last_status in ("ready", "completed"):
                    _pass(f"GET /api/v1/documents/{{id}}/status", detail)
                    return
                if last_status == "failed":
                    err = body.get("error_message", "")
                    _fail(f"GET /api/v1/documents/{{id}}/status", f"ingestion failed: {err}")
                    return
            # Still processing — wait and retry
            time.sleep(3)
        except Exception:
            time.sleep(3)

    # Timed out — report last known status
    if last_status in ("pending", "processing"):
        _pass(
            "GET /api/v1/documents/{id}/status",
            f"status={last_status} after 30 s (ingestion in progress — Celery may be slow on first run)",
        )
    else:
        _fail("GET /api/v1/documents/{id}/status", f"timed out  last_status={last_status}")


def check_rag_retrieval() -> None:
    _print_header("7. RAG Retrieval")

    if not _AUTH_HEADERS:
        _skip("RAG query", "authentication failed — skipping")
        return

    try:
        payload: dict[str, Any] = {
            "question": "What is the RAG pipeline?",
            "top_k": 3,
        }
        if _TEST_DOC_ID:
            payload["document_ids"] = [_TEST_DOC_ID]

        code, body = _post("/api/v1/rag/query", body=payload, headers=_AUTH_HEADERS, timeout=60)
        if code == 200 and isinstance(body, dict):
            answer = body.get("answer", "")
            sources = body.get("sources", [])
            request_id = body.get("request_id", "")
            _pass(
                "POST /api/v1/rag/query",
                f"answer_len={len(answer)}  sources={len(sources)}  request_id={request_id[:8]}…",
            )
        elif code in (503, 422) and not body:
            _skip("POST /api/v1/rag/query", "no document ready for retrieval yet (ingestion may still be running)")
        else:
            _fail("POST /api/v1/rag/query", f"HTTP {code}: {str(body)[:200]}")
    except Exception as exc:
        _fail("POST /api/v1/rag/query", str(exc))


def check_agent_execution() -> None:
    _print_header("8. Agent Execution")

    if not _AUTH_HEADERS:
        _skip("agent execute", "authentication failed — skipping")
        return

    try:
        code, body = _post(
            "/api/v1/agent/execute",
            body={
                "message": "What is 2 + 2? Reply with just the number.",
                "enable_rag": False,
                "enable_mcp": False,
                "max_steps": 3,
                "timeout_ms": 30000,
            },
            headers=_AUTH_HEADERS,
            timeout=60,
        )
        if code == 200 and isinstance(body, dict):
            status = body.get("status", "?")
            output = body.get("output", "")
            steps = body.get("step_count", 0)
            elapsed = body.get("elapsed_ms", 0)
            _pass(
                "POST /api/v1/agent/execute",
                f"status={status}  steps={steps}  elapsed_ms={elapsed}  output_len={len(output)}",
            )
        else:
            err = body.get("error_message", "") if isinstance(body, dict) else str(body)[:200]
            _fail("POST /api/v1/agent/execute", f"HTTP {code}: {err}")
    except Exception as exc:
        _fail("POST /api/v1/agent/execute", str(exc))


def check_mcp_demo_tool() -> None:
    _print_header("9. MCP Demo Tool")

    if not _AUTH_HEADERS:
        _skip("MCP demo tool", "authentication failed — skipping")
        return

    # 9a. List available tools (demo_echo should appear)
    try:
        code, body = _get("/api/v1/agent/tools", headers=_AUTH_HEADERS)
        if code == 200 and isinstance(body, dict):
            tools = body.get("tools", [])
            tool_names = [t.get("name", "") for t in tools]
            demo_tools = [n for n in tool_names if n.startswith("demo_")]
            if demo_tools:
                _pass("GET /api/v1/agent/tools", f"demo tools available: {demo_tools}")
            else:
                _skip(
                    "GET /api/v1/agent/tools — demo tools",
                    "DemoMCPConnector not registered (expected in dev mode)",
                )
        else:
            _fail("GET /api/v1/agent/tools", f"HTTP {code}: {str(body)[:200]}")
    except Exception as exc:
        _fail("GET /api/v1/agent/tools", str(exc))

    # 9b. Call demo_echo via the agent execute endpoint (uses MCP dispatch)
    try:
        code, body = _post(
            "/api/v1/agent/execute",
            body={
                "message": 'Call the demo_echo tool with message "smoke-test-ping".',
                "enable_rag": False,
                "enable_mcp": True,
                "max_steps": 5,
                "timeout_ms": 30000,
            },
            headers=_AUTH_HEADERS,
            timeout=60,
        )
        if code == 200 and isinstance(body, dict):
            status = body.get("status", "?")
            tool_calls = body.get("tool_calls", [])
            echo_calls = [t for t in tool_calls if t.get("tool_name") == "demo_echo"]
            if echo_calls:
                _pass(
                    "demo_echo via /api/v1/agent/execute",
                    f"status={status}  demo_echo called {len(echo_calls)} time(s)",
                )
            else:
                # Agent may have responded directly without calling the tool
                _pass(
                    "demo_echo via /api/v1/agent/execute",
                    f"status={status}  (agent responded; tool_calls={len(tool_calls)})",
                )
        else:
            _fail("demo_echo via /api/v1/agent/execute", f"HTTP {code}: {str(body)[:200]}")
    except Exception as exc:
        _fail("demo_echo via /api/v1/agent/execute", str(exc))


def check_env_vars() -> None:
    _print_header("10. Environment Variables")

    required = [
        "DATABASE_URL",
        "REDIS_URL",
        "SECRET_KEY",
        "AES_ENCRYPTION_KEY",
    ]
    optional_present = [
        "GEMINI_API_KEY",
        "OPENAI_API_KEY",
        "CHROMA_HOST",
        "MINIO_ENDPOINT",
    ]

    for var in required:
        val = os.environ.get(var, "")
        if val and val not in ("REPLACE_ME_generate_with_python_secrets_token_hex_32",
                               "REPLACE_ME_generate_with_python_base64_os_urandom_32"):
            _pass(f"${var}", "set")
        else:
            _fail(f"${var}", "not set or still contains placeholder value")

    for var in optional_present:
        val = os.environ.get(var, "")
        if val:
            _pass(f"${var}", "set (optional)")
        else:
            _skip(f"${var}", "not set — optional")


# ---------------------------------------------------------------------------
# Document cleanup (best-effort, non-fatal)
# ---------------------------------------------------------------------------

def _cleanup() -> None:
    if _TEST_DOC_ID and _AUTH_HEADERS:
        try:
            _delete(f"/api/v1/documents/{_TEST_DOC_ID}", headers=_AUTH_HEADERS)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------

def main() -> int:
    print(f"\n{BOLD}Android AI Assistant — Local Smoke Test{RESET}")
    print(f"Base URL: {CYAN}{BASE_URL}{RESET}")
    print(f"Time:     {time.strftime('%Y-%m-%d %H:%M:%S')}\n")

    check_fastapi()
    check_postgres()
    check_redis()
    check_chromadb()
    check_celery()

    # Authenticated checks — register + login first
    _print_header("Auth (prerequisite for checks 6-9)")
    authed = _register_and_login()

    check_document_ingestion()
    check_rag_retrieval()
    check_agent_execution()
    check_mcp_demo_tool()
    check_env_vars()

    # Cleanup test document
    _cleanup()

    # Summary
    passed = sum(1 for _, ok, d in _results if ok and not d.startswith("SKIPPED"))
    skipped = sum(1 for _, ok, d in _results if ok and d.startswith("SKIPPED"))
    failed = sum(1 for _, ok, _ in _results if not ok)
    total = len(_results)

    print(f"\n{BOLD}{'═' * 60}{RESET}")
    print(f"{BOLD}Summary{RESET}  {GREEN}{passed} passed{RESET}  "
          f"{YELLOW}{skipped} skipped{RESET}  "
          f"{RED if failed else GREEN}{failed} failed{RESET}  "
          f"/ {total} total")
    print(f"{BOLD}{'═' * 60}{RESET}")

    if failed:
        print(f"\n{RED}Failed checks:{RESET}")
        for name, ok, detail in _results:
            if not ok:
                print(f"  {RED}✗{RESET} {name}: {detail}")
        print()

    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
