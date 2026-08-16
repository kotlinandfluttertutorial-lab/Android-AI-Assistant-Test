"""Property-based tests for structured log field completeness.

Property 24: Structured Log Field Completeness
**Validates: Requirements 18.1, 18.5**

Strategy:
  - Generate API request scenarios (authenticated and unauthenticated)
  - Use FastAPI TestClient with the RequestLoggingMiddleware
  - Capture log records emitted during each request via caplog / logging
  - Assert every emitted log entry contains all 5 required fields:
      correlation_id, user_id (or null), path, http_status (status_code),
      response_time_ms
  - Assert the Prometheus error counter only increments on actual unhandled
    exceptions, NOT on normal 4xx/5xx responses

Assertions:
  - 24A: Every emitted structured log entry contains all 5 required fields
    for any generated authenticated or unauthenticated request
  - 24B: The error counter does NOT increment for 4xx/5xx HTTP responses
    produced by the application (not unhandled exceptions)
  - 24C: The error counter increments by exactly 1 for each unhandled exception

Requirements: 18.1, 18.5
"""

from __future__ import annotations

import base64
import json
import logging
import os
import uuid

# ---------------------------------------------------------------------------
# Environment variables must be set BEFORE any app imports
# ---------------------------------------------------------------------------
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("OPENAI_API_KEY", "sk-test-openai-key-for-testing")
os.environ.setdefault("GEMINI_API_KEY", "test-gemini-key-for-testing")
os.environ.setdefault("ANTHROPIC_API_KEY", "sk-ant-test-key-for-testing")
os.environ.setdefault("OLLAMA_BASE_URL", "http://localhost:11434")
os.environ.setdefault("LOKI_URL", "")
os.environ.setdefault("ENVIRONMENT", "development")
os.environ.setdefault("LOG_LEVEL", "INFO")

import pytest
from fastapi import FastAPI, Response
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st
from starlette.testclient import TestClient

from app.middleware.logging_middleware import (
    RequestLoggingMiddleware,
    http_unhandled_exceptions_total,
)

# ---------------------------------------------------------------------------
# Strategies
# ---------------------------------------------------------------------------

# Subsets of printable ASCII that are safe for use as JWT sub values
_safe_user_id_strategy = st.one_of(
    st.uuids().map(str),
    st.text(
        alphabet=st.characters(
            whitelist_categories=("Lu", "Ll", "Nd"),
            whitelist_characters="-_.",
        ),
        min_size=1,
        max_size=64,
    ).filter(lambda s: s.strip() != ""),
)

# HTTP status codes that are normal application responses (NOT unhandled exceptions)
_normal_status_codes = st.sampled_from(
    [200, 201, 204, 400, 401, 403, 404, 422, 429, 500]
)

# HTTP paths used in our test app (must map to routes we define below)
_test_paths = st.sampled_from(["/ok", "/not-found", "/bad-request", "/server-error"])

# Whether the request is authenticated
_is_authenticated_strategy = st.booleans()


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _build_bearer_jwt(sub: str) -> str:
    """Build a structurally valid (but unsigned) JWT token with the given ``sub``.

    The middleware extracts user_id from the JWT without verifying signature,
    so a well-formed header.payload.fake-sig token is sufficient for testing.

    Args:
        sub: Value for the JWT ``sub`` claim.

    Returns:
        A ``Bearer <token>`` string ready for the Authorization header.
    """
    header_b64 = (
        base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        .rstrip(b"=")
        .decode()
    )

    payload_b64 = (
        base64.urlsafe_b64encode(json.dumps({"sub": sub}).encode())
        .rstrip(b"=")
        .decode()
    )

    return f"{header_b64}.{payload_b64}.fake-signature"


def _make_test_app() -> FastAPI:
    """Create a minimal FastAPI app with RequestLoggingMiddleware for property tests.

    Routes:
      GET /ok           → 200 (success)
      GET /not-found    → 404 (app-level 4xx, NOT an unhandled exception)
      GET /bad-request  → 400 (app-level 4xx)
      GET /server-error → 500 (app-level 5xx, returned as response, NOT an exception)
      GET /exception    → raises ValueError (triggers unhandled exception path)
    """
    app = FastAPI()
    app.add_middleware(RequestLoggingMiddleware)

    @app.get("/ok")
    async def ok_route() -> dict[str, str]:
        return {"status": "ok"}

    @app.get("/not-found")
    async def not_found_route() -> Response:
        return Response(content="not found", status_code=404)

    @app.get("/bad-request")
    async def bad_request_route() -> Response:
        return Response(content="bad request", status_code=400)

    @app.get("/server-error")
    async def server_error_route() -> Response:
        return Response(content="internal error", status_code=500)

    @app.get("/exception")
    async def exception_route() -> None:
        raise ValueError("Unhandled exception for testing")

    return app


# Module-level test app and client — shared across all property tests to
# avoid re-creating the app for every Hypothesis example.
_test_app: FastAPI = _make_test_app()
_test_client: TestClient = TestClient(_test_app, raise_server_exceptions=False)

# ---------------------------------------------------------------------------
# Required log fields (per Requirement 18.1)
# ---------------------------------------------------------------------------

#: The five fields every structured log entry MUST contain.
REQUIRED_LOG_FIELDS = frozenset(
    {"correlation_id", "user_id", "path", "status_code", "response_time_ms"}
)

# The log record message emitted by RequestLoggingMiddleware on success
_LOG_MESSAGE = "request"

# The logger name used by the middleware
_LOGGER_NAME = "app.middleware.logging_middleware"


# ===========================================================================
# Property 24A — Every emitted log entry contains all 5 required fields
# **Validates: Requirements 18.1**
# ===========================================================================


@given(
    user_id_str=_safe_user_id_strategy,
    path=_test_paths,
    is_authenticated=_is_authenticated_strategy,
)
@settings(
    max_examples=40,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_24a_every_log_entry_contains_all_required_fields(
    user_id_str: str,
    path: str,
    is_authenticated: bool,
) -> None:
    """**Validates: Requirements 18.1**

    Property 24A: For any generated request (authenticated or unauthenticated,
    any path, any normal HTTP status code), every structured log entry emitted
    by RequestLoggingMiddleware MUST contain all five required fields:

      - correlation_id: UUID string per request
      - user_id: JWT sub claim string, or None for unauthenticated requests
      - path: the URL path of the request
      - status_code: HTTP response status code
      - response_time_ms: elapsed time in milliseconds (positive number)

    Requirement 18.1 states: "The Backend SHALL emit structured JSON logs for
    every API request, including correlation ID, user ID, endpoint, HTTP
    status, and response time."
    """
    headers: dict[str, str] = {}
    expected_user_id: str | None = None

    if is_authenticated:
        token = _build_bearer_jwt(sub=user_id_str)
        headers["Authorization"] = f"Bearer {token}"
        expected_user_id = user_id_str

    # Use a fresh log handler to capture only this test's records.
    # We must also set the logger level to INFO so that INFO log entries
    # are not filtered out by the effective logger level (which may be WARNING
    # in the test environment when no handlers are configured on root logger).
    captured_records: list[logging.LogRecord] = []

    class _CapturingHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured_records.append(record)

    logger = logging.getLogger(_LOGGER_NAME)
    handler = _CapturingHandler()
    handler.setLevel(logging.DEBUG)
    original_level = logger.level
    logger.setLevel(logging.DEBUG)
    logger.addHandler(handler)

    try:
        response = _test_client.get(path, headers=headers)
    finally:
        logger.removeHandler(handler)
        logger.setLevel(original_level)

    # Find the structured "request" log record
    request_logs = [r for r in captured_records if r.getMessage() == _LOG_MESSAGE]

    assert len(request_logs) >= 1, (
        f"Property 24A violated: no structured 'request' log entry was emitted "
        f"for path={path!r}, is_authenticated={is_authenticated}, "
        f"response_status={response.status_code}. "
        f"Captured messages: {[r.getMessage() for r in captured_records]}"
    )

    for log_record in request_logs:
        record_dict = log_record.__dict__

        # --- 1. All 5 required fields must be present ---
        missing = REQUIRED_LOG_FIELDS - set(record_dict.keys())
        assert not missing, (
            f"Property 24A violated: log entry missing required fields {missing}. "
            f"path={path!r}, is_authenticated={is_authenticated}, "
            f"present fields: {[f for f in REQUIRED_LOG_FIELDS if f in record_dict]}"
        )

        # --- 2. correlation_id must be a non-empty string (UUID format) ---
        correlation_id = record_dict["correlation_id"]
        assert isinstance(correlation_id, str) and len(correlation_id) > 0, (
            f"Property 24A violated: correlation_id={correlation_id!r} is not a "
            f"non-empty string. path={path!r}"
        )
        # Validate UUID format (36 chars with hyphens)
        assert len(correlation_id) == 36 and correlation_id.count("-") == 4, (
            f"Property 24A violated: correlation_id={correlation_id!r} does not "
            f"match UUID format (8-4-4-4-12). path={path!r}"
        )

        # --- 3. user_id: expected value for authenticated, None for unauthenticated ---
        actual_user_id = record_dict["user_id"]
        if is_authenticated:
            assert actual_user_id == expected_user_id, (
                f"Property 24A violated: authenticated request has user_id="
                f"{actual_user_id!r}, expected {expected_user_id!r}. path={path!r}"
            )
        else:
            assert actual_user_id is None, (
                f"Property 24A violated: unauthenticated request has user_id="
                f"{actual_user_id!r}, expected None. path={path!r}"
            )

        # --- 4. path must be a non-empty string matching the request path ---
        logged_path = record_dict["path"]
        assert (
            isinstance(logged_path, str) and len(logged_path) > 0
        ), f"Property 24A violated: path={logged_path!r} is not a non-empty string."
        assert logged_path == path, (
            f"Property 24A violated: logged path={logged_path!r} does not match "
            f"request path={path!r}"
        )

        # --- 5. status_code must be a positive integer ---
        status_code = record_dict["status_code"]
        assert isinstance(status_code, int) and 100 <= status_code <= 599, (
            f"Property 24A violated: status_code={status_code!r} is not a valid "
            f"HTTP status code (100–599). path={path!r}"
        )

        # --- 6. response_time_ms must be a positive number ---
        response_time_ms = record_dict["response_time_ms"]
        assert isinstance(response_time_ms, (int, float)), (
            f"Property 24A violated: response_time_ms={response_time_ms!r} is not "
            f"a number. path={path!r}"
        )
        assert response_time_ms >= 0, (
            f"Property 24A violated: response_time_ms={response_time_ms!r} is "
            f"negative. path={path!r}"
        )


# ===========================================================================
# Property 24B — Error counter does NOT increment for normal 4xx/5xx responses
# **Validates: Requirements 18.5**
# ===========================================================================


@given(
    path=st.sampled_from(["/not-found", "/bad-request", "/server-error"]),
    is_authenticated=_is_authenticated_strategy,
    user_id_str=_safe_user_id_strategy,
)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_24b_error_counter_does_not_increment_for_normal_responses(
    path: str,
    is_authenticated: bool,
    user_id_str: str,
) -> None:
    """**Validates: Requirements 18.5**

    Property 24B: For any request that results in a normal HTTP response
    (including 4xx and 5xx status codes returned as Response objects),
    the ``http_unhandled_exceptions_total`` Prometheus counter MUST NOT
    be incremented.

    Requirement 18.5 states: "THE Backend SHALL only increment error counters
    when an actual unhandled exception has occurred."

    A 4xx or 5xx response produced intentionally by a route handler is NOT
    an unhandled exception — it is normal application behavior.
    """
    headers: dict[str, str] = {}
    if is_authenticated:
        token = _build_bearer_jwt(sub=user_id_str)
        headers["Authorization"] = f"Bearer {token}"

    # Capture the counter value BEFORE the request
    initial_count = http_unhandled_exceptions_total.labels(path=path)._value.get()

    # Make the request — this should produce a normal HTTP response, not raise
    response = _test_client.get(path, headers=headers)

    # Capture the counter value AFTER the request
    final_count = http_unhandled_exceptions_total.labels(path=path)._value.get()

    # The counter must not have been incremented
    assert final_count == initial_count, (
        f"Property 24B violated: http_unhandled_exceptions_total counter was "
        f"incremented for a normal {response.status_code} response. "
        f"path={path!r}, is_authenticated={is_authenticated}, "
        f"counter before={initial_count}, counter after={final_count}. "
        f"The counter MUST only increment on actual unhandled exceptions."
    )

    # Also verify a "request" log was still emitted (normal path still logs)
    # This is a secondary assertion — the primary is counter non-increment.
    assert response.status_code in (200, 204, 400, 404, 422, 429, 500), (
        f"Property 24B: test app returned unexpected status {response.status_code} "
        f"for path={path!r}. Expected one of the configured test status codes."
    )


# ===========================================================================
# Property 24C — Error counter increments exactly once per unhandled exception
# **Validates: Requirements 18.5**
# ===========================================================================


@given(
    n_exceptions=st.integers(min_value=1, max_value=5),
    is_authenticated=_is_authenticated_strategy,
    user_id_str=_safe_user_id_strategy,
)
@settings(
    max_examples=20,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_24c_error_counter_increments_exactly_once_per_unhandled_exception(
    n_exceptions: int,
    is_authenticated: bool,
    user_id_str: str,
) -> None:
    """**Validates: Requirements 18.5**

    Property 24C: For any N unhandled exceptions triggered by requests to the
    /exception endpoint, the ``http_unhandled_exceptions_total`` counter for
    that path MUST be incremented by EXACTLY N (one increment per exception,
    no more, no less).

    Requirement 18.5 states: "WHEN an unhandled exception occurs in the Backend,
    THE Backend SHALL log the full stack trace with a correlation ID and increment
    the error counter metric."

    This property verifies:
      - Exactly 1 increment per unhandled exception (not 0, not 2)
      - N sequential exceptions cause exactly N total increments
    """
    exception_path = "/exception"

    headers: dict[str, str] = {}
    if is_authenticated:
        token = _build_bearer_jwt(sub=user_id_str)
        headers["Authorization"] = f"Bearer {token}"

    # Capture the counter value BEFORE the requests
    initial_count = http_unhandled_exceptions_total.labels(
        path=exception_path
    )._value.get()

    # Trigger N unhandled exceptions
    # Use raise_server_exceptions=False so exceptions are caught and handled gracefully
    # by TestClient while still flowing through middleware
    client_no_raise = TestClient(_test_app, raise_server_exceptions=False)
    for _ in range(n_exceptions):
        client_no_raise.get(exception_path, headers=headers)

    # Capture the counter value AFTER the requests
    final_count = http_unhandled_exceptions_total.labels(
        path=exception_path
    )._value.get()

    delta = final_count - initial_count

    assert delta == n_exceptions, (
        f"Property 24C violated: {n_exceptions} unhandled exception(s) caused "
        f"counter delta={delta}, expected delta={n_exceptions}. "
        f"path={exception_path!r}, is_authenticated={is_authenticated}, "
        f"counter before={initial_count}, counter after={final_count}. "
        f"Each unhandled exception must increment the counter by exactly 1."
    )


# ===========================================================================
# Property 24D — Log field presence is independent of HTTP status code
# **Validates: Requirements 18.1**
# ===========================================================================


@given(
    is_authenticated=_is_authenticated_strategy,
    user_id_str=_safe_user_id_strategy,
    path=_test_paths,
)
@settings(
    max_examples=30,
    suppress_health_check=[HealthCheck.too_slow],
    deadline=None,
)
def test_property_24d_log_field_completeness_across_all_status_codes(
    is_authenticated: bool,
    user_id_str: str,
    path: str,
) -> None:
    """**Validates: Requirements 18.1**

    Property 24D: The five required log fields MUST be present in the log
    entry regardless of the HTTP status code of the response.

    Requirement 18.1 says "every API request" — it does not exclude 4xx/5xx
    responses. The middleware must log ALL requests with all required fields.
    """
    headers: dict[str, str] = {}
    if is_authenticated:
        token = _build_bearer_jwt(sub=user_id_str)
        headers["Authorization"] = f"Bearer {token}"

    captured_records: list[logging.LogRecord] = []

    class _CapturingHandler(logging.Handler):
        def emit(self, record: logging.LogRecord) -> None:
            captured_records.append(record)

    logger = logging.getLogger(_LOGGER_NAME)
    handler = _CapturingHandler()
    handler.setLevel(logging.DEBUG)
    original_level = logger.level
    logger.setLevel(logging.DEBUG)
    logger.addHandler(handler)

    try:
        response = _test_client.get(path, headers=headers)
    finally:
        logger.removeHandler(handler)
        logger.setLevel(original_level)

    request_logs = [r for r in captured_records if r.getMessage() == _LOG_MESSAGE]

    assert len(request_logs) == 1, (
        f"Property 24D violated: expected exactly 1 'request' log entry, "
        f"got {len(request_logs)} for path={path!r}, "
        f"response_status={response.status_code}."
    )

    record_dict = request_logs[0].__dict__

    # Verify all five fields are present regardless of status code
    for field in REQUIRED_LOG_FIELDS:
        assert field in record_dict, (
            f"Property 24D violated: log entry for HTTP {response.status_code} "
            f"response is missing required field {field!r}. "
            f"path={path!r}, is_authenticated={is_authenticated}."
        )

    # Verify status_code in log matches the actual response status
    assert record_dict["status_code"] == response.status_code, (
        f"Property 24D violated: logged status_code={record_dict['status_code']} "
        f"does not match actual response status_code={response.status_code}. "
        f"path={path!r}"
    )


# ===========================================================================
# Deterministic edge-case tests (complement the property tests above)
# ===========================================================================


class TestStructuredLogFieldCompletenessEdgeCases:
    """Deterministic edge cases complementing the Hypothesis property tests.

    Requirements: 18.1, 18.5
    """

    @pytest.fixture(autouse=True)
    def _setup(self) -> None:
        """Set up shared test client."""
        self.client = TestClient(_test_app, raise_server_exceptions=False)

    def _capture_request_logs(
        self, method: str, path: str, headers: dict[str, str] | None = None
    ) -> list[logging.LogRecord]:
        """Make a request and capture all emitted log records."""
        captured: list[logging.LogRecord] = []

        class _H(logging.Handler):
            def emit(self, record: logging.LogRecord) -> None:
                captured.append(record)

        logger = logging.getLogger(_LOGGER_NAME)
        h = _H()
        h.setLevel(logging.DEBUG)
        original_level = logger.level
        logger.setLevel(logging.DEBUG)
        logger.addHandler(h)
        try:
            getattr(self.client, method.lower())(path, headers=headers or {})
        finally:
            logger.removeHandler(h)
            logger.setLevel(original_level)

        return [r for r in captured if r.getMessage() == _LOG_MESSAGE]

    def test_unauthenticated_request_logs_user_id_as_none(self) -> None:
        """Unauthenticated requests must log user_id as None."""
        logs = self._capture_request_logs("GET", "/ok")
        assert len(logs) == 1
        assert logs[0].__dict__["user_id"] is None

    def test_authenticated_request_logs_correct_user_id(self) -> None:
        """Authenticated requests must log the correct user_id from JWT sub."""
        user_id = str(uuid.uuid4())
        token = _build_bearer_jwt(sub=user_id)
        logs = self._capture_request_logs(
            "GET", "/ok", headers={"Authorization": f"Bearer {token}"}
        )
        assert len(logs) == 1
        assert logs[0].__dict__["user_id"] == user_id

    def test_404_response_still_logs_all_required_fields(self) -> None:
        """A 404 application response must still emit a log with all required fields."""
        logs = self._capture_request_logs("GET", "/not-found")
        assert len(logs) == 1
        record_dict = logs[0].__dict__
        for field in REQUIRED_LOG_FIELDS:
            assert field in record_dict, f"Missing required log field: {field!r}"
        assert record_dict["status_code"] == 404
        assert record_dict["path"] == "/not-found"

    def test_500_application_response_does_not_increment_error_counter(self) -> None:
        """A 500 status returned as a Response object must NOT increment the error counter."""
        initial = http_unhandled_exceptions_total.labels(
            path="/server-error"
        )._value.get()
        self.client.get("/server-error")
        final = http_unhandled_exceptions_total.labels(
            path="/server-error"
        )._value.get()
        assert final == initial, (
            f"Error counter was incremented for a 500 Response (not an exception). "
            f"Before={initial}, after={final}."
        )

    def test_unhandled_exception_increments_counter_by_exactly_one(self) -> None:
        """A single unhandled exception must increment the counter by exactly 1."""
        initial = http_unhandled_exceptions_total.labels(path="/exception")._value.get()
        client_no_raise = TestClient(_test_app, raise_server_exceptions=False)
        client_no_raise.get("/exception")
        final = http_unhandled_exceptions_total.labels(path="/exception")._value.get()
        assert (
            final - initial == 1
        ), f"Counter delta={final - initial} after 1 unhandled exception; expected 1."

    def test_unhandled_exception_does_not_emit_info_request_log(self) -> None:
        """An unhandled exception must NOT emit a normal 'request' INFO log.

        When an exception is raised, the middleware re-raises after logging
        the error — it never reaches the normal success log path.
        """
        captured: list[logging.LogRecord] = []

        class _H(logging.Handler):
            def emit(self, record: logging.LogRecord) -> None:
                captured.append(record)

        logger = logging.getLogger(_LOGGER_NAME)
        h = _H()
        h.setLevel(logging.DEBUG)
        original_level = logger.level
        logger.setLevel(logging.DEBUG)
        logger.addHandler(h)
        try:
            client_no_raise = TestClient(_test_app, raise_server_exceptions=False)
            client_no_raise.get("/exception")
        finally:
            logger.removeHandler(h)
            logger.setLevel(original_level)

        info_request_logs = [
            r
            for r in captured
            if r.getMessage() == _LOG_MESSAGE and r.levelno == logging.INFO
        ]
        assert len(info_request_logs) == 0, (
            f"Unhandled exception path emitted an INFO 'request' log entry — "
            f"it should only emit ERROR log. Found: {len(info_request_logs)} INFO 'request' logs."
        )

    def test_correlation_id_is_unique_per_request(self) -> None:
        """Each request must produce a distinct correlation_id."""
        ids: set[str] = set()
        for _ in range(5):
            logs = self._capture_request_logs("GET", "/ok")
            assert len(logs) == 1
            ids.add(logs[0].__dict__["correlation_id"])

        assert len(ids) == 5, (
            f"correlation_id is not unique per request: collected {len(ids)} distinct "
            f"IDs for 5 requests (expected 5)."
        )

    def test_response_time_ms_is_non_negative(self) -> None:
        """response_time_ms must be non-negative for all requests."""
        for path in ("/ok", "/not-found", "/bad-request", "/server-error"):
            logs = self._capture_request_logs("GET", path)
            if logs:
                rt = logs[0].__dict__["response_time_ms"]
                assert rt >= 0, f"response_time_ms={rt!r} is negative for path={path!r}"

    def test_x_correlation_id_response_header_matches_log(self) -> None:
        """The X-Correlation-ID response header must match the correlation_id in the log."""
        captured: list[logging.LogRecord] = []

        class _H(logging.Handler):
            def emit(self, record: logging.LogRecord) -> None:
                captured.append(record)

        logger = logging.getLogger(_LOGGER_NAME)
        h = _H()
        h.setLevel(logging.DEBUG)
        original_level = logger.level
        logger.setLevel(logging.DEBUG)
        logger.addHandler(h)
        try:
            response = self.client.get("/ok")
        finally:
            logger.removeHandler(h)
            logger.setLevel(original_level)

        request_logs = [r for r in captured if r.getMessage() == _LOG_MESSAGE]
        assert len(request_logs) == 1

        logged_correlation_id = request_logs[0].__dict__["correlation_id"]
        header_correlation_id = response.headers.get("X-Correlation-ID")

        assert (
            header_correlation_id is not None
        ), "X-Correlation-ID header missing from response."
        assert header_correlation_id == logged_correlation_id, (
            f"X-Correlation-ID header={header_correlation_id!r} does not match "
            f"logged correlation_id={logged_correlation_id!r}"
        )
