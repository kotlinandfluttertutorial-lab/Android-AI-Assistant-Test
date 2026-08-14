"""Unit tests for the request logging middleware.

Tests:
- Request log emission with all required fields
- X-Correlation-ID header presence in responses
- User ID extraction from JWT
- User ID null for unauthenticated requests
- Unhandled exception counter increment and ERROR log
- Response time measurement

Requirements: 18.1, 18.5
"""

from __future__ import annotations

import json
import logging
from typing import Any

import pytest
from fastapi import FastAPI
from starlette.testclient import TestClient

from app.middleware.logging_middleware import RequestLoggingMiddleware


@pytest.fixture
def test_app() -> FastAPI:
    """Create a minimal FastAPI app with RequestLoggingMiddleware for testing."""
    app = FastAPI()
    app.add_middleware(RequestLoggingMiddleware)

    @app.get("/test")
    async def test_route() -> dict[str, str]:
        """Simple test route that returns 200 OK."""
        return {"status": "ok"}

    @app.get("/test-exception")
    async def test_exception_route() -> dict[str, str]:
        """Test route that raises an unhandled ValueError."""
        raise ValueError("Test unhandled exception")

    return app


@pytest.fixture
def client(test_app: FastAPI) -> TestClient:
    """Create a TestClient for the test app."""
    return TestClient(test_app)


def _build_test_jwt(sub: str) -> str:
    """Build a minimal JWT-like token with a ``sub`` claim (no signature).

    Args:
        sub: The ``sub`` claim value.

    Returns:
        A JWT-like Bearer token string (header.payload.signature).
    """
    import base64

    header = (
        base64.urlsafe_b64encode(json.dumps({"alg": "HS256", "typ": "JWT"}).encode())
        .decode()
        .rstrip("=")
    )

    payload = (
        base64.urlsafe_b64encode(json.dumps({"sub": sub}).encode()).decode().rstrip("=")
    )

    signature = "fake-signature"

    return f"{header}.{payload}.{signature}"


def test_request_log_emitted(client: TestClient, caplog: Any) -> None:
    """Test that a structured log entry is emitted for every request.

    Validates that the log contains all required fields: correlation_id,
    user_id, path, method, status_code, response_time_ms.
    """
    with caplog.at_level(logging.INFO):
        response = client.get("/test")

    assert response.status_code == 200

    # Find the request log entry
    request_logs = [r for r in caplog.records if r.message == "request"]
    assert len(request_logs) == 1

    log_record = request_logs[0]
    assert "correlation_id" in log_record.__dict__
    assert "user_id" in log_record.__dict__
    assert log_record.__dict__["path"] == "/test"
    assert log_record.__dict__["method"] == "GET"
    assert log_record.__dict__["status_code"] == 200
    assert "response_time_ms" in log_record.__dict__
    assert isinstance(log_record.__dict__["response_time_ms"], (int, float))


def test_correlation_id_in_response_header(client: TestClient) -> None:
    """Test that the X-Correlation-ID header is set in the response."""
    response = client.get("/test")

    assert response.status_code == 200
    assert "X-Correlation-ID" in response.headers

    correlation_id = response.headers["X-Correlation-ID"]
    # Validate UUID format (8-4-4-4-12)
    assert len(correlation_id) == 36
    assert correlation_id.count("-") == 4


def test_user_id_extracted_from_jwt(client: TestClient, caplog: Any) -> None:
    """Test that the user_id is extracted from a JWT Authorization header."""
    test_sub = "test-user-123"
    token = _build_test_jwt(sub=test_sub)

    with caplog.at_level(logging.INFO):
        response = client.get("/test", headers={"Authorization": f"Bearer {token}"})

    assert response.status_code == 200

    request_logs = [r for r in caplog.records if r.message == "request"]
    assert len(request_logs) == 1

    log_record = request_logs[0]
    assert log_record.__dict__["user_id"] == test_sub


def test_unauthenticated_request_user_id_null(client: TestClient, caplog: Any) -> None:
    """Test that user_id is None when no Authorization header is present."""
    with caplog.at_level(logging.INFO):
        response = client.get("/test")

    assert response.status_code == 200

    request_logs = [r for r in caplog.records if r.message == "request"]
    assert len(request_logs) == 1

    log_record = request_logs[0]
    assert log_record.__dict__["user_id"] is None


def test_unhandled_exception_increments_counter(
    client: TestClient, caplog: Any
) -> None:
    """Test that unhandled exceptions log ERROR and increment the counter."""
    from app.middleware.logging_middleware import (
        http_unhandled_exceptions_total,
    )

    # Get initial counter value
    initial_count = http_unhandled_exceptions_total.labels(
        path="/test-exception"
    )._value.get()

    with caplog.at_level(logging.ERROR):
        with pytest.raises(ValueError, match="Test unhandled exception"):
            client.get("/test-exception")

    # Check that an ERROR log was emitted with correlation_id
    error_logs = [r for r in caplog.records if r.levelno == logging.ERROR]
    assert len(error_logs) >= 1

    error_log = error_logs[0]
    assert "correlation_id" in error_log.__dict__
    assert "Unhandled exception during request" in error_log.message

    # Check that the counter was incremented
    final_count = http_unhandled_exceptions_total.labels(
        path="/test-exception"
    )._value.get()
    assert final_count == initial_count + 1


def test_response_time_ms_positive(client: TestClient, caplog: Any) -> None:
    """Test that response_time_ms is a positive number for any request."""
    with caplog.at_level(logging.INFO):
        response = client.get("/test")

    assert response.status_code == 200

    request_logs = [r for r in caplog.records if r.message == "request"]
    assert len(request_logs) == 1

    log_record = request_logs[0]
    response_time_ms = log_record.__dict__["response_time_ms"]
    assert isinstance(response_time_ms, (int, float))
    assert response_time_ms > 0


def test_log_contains_all_required_fields(client: TestClient, caplog: Any) -> None:
    """Test that a request log record contains ALL required structured fields.

    **Validates: Requirements 18.1**

    Property 24: Every API request emits a JSON log entry containing
    correlation_id, user_id, path, method, status_code, and response_time_ms.
    """
    with caplog.at_level(logging.INFO):
        response = client.get("/test")

    assert response.status_code == 200

    request_logs = [r for r in caplog.records if r.message == "request"]
    assert len(request_logs) == 1

    record_dict = request_logs[0].__dict__
    required_fields = {
        "correlation_id",
        "user_id",
        "path",
        "method",
        "status_code",
        "response_time_ms",
    }
    for field in required_fields:
        assert field in record_dict, f"Log record missing required field: {field!r}"
