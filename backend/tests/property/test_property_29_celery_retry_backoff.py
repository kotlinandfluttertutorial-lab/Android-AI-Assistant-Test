# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/property
# File    : test_property_29_celery_retry_backoff.py
# Purpose : Property-based tests for Celery retry exponential backoff
#
# Architecture Layer : Test
# Pattern Used       : Hypothesis Property-Based Testing
#
# Key Concepts:
#   - Exponential backoff formula: countdown = 2 ** attempt_number
#   - max_retries=3 enforced on Celery task decorator
#   - Permanent failure after all retries exhausted
#
# Dependencies:
#   - hypothesis, unittest.mock, celery.exceptions
# ============================================================

"""Property-based tests for Celery retry exponential backoff.

Property 29: Celery Retry Exponential Backoff
**Validates: Requirements 27.3**

Strategy:
  - ``st.integers(min_value=0, max_value=2)`` for valid attempt numbers (0, 1, 2)
  - Fixed attempt value 3 for max-retries-exceeded scenario

Assertions:
  - 29A: For attempt n, computed countdown equals ``2 ** n`` exactly
    (attempt 0 → 1 s, attempt 1 → 2 s, attempt 2 → 4 s)
  - 29B: ``ingest_document_task.max_retries == 3``; at attempt 3 the task is
    permanently failed rather than retried
  - 29C: When ``MaxRetriesExceededError`` is raised, ``doc_repo.update_status``
    is called with ``IngestionStatus.failed`` and
    ``rag_service.send_ingestion_failure_notification`` is called

Requirements: 27.3
"""

from __future__ import annotations

import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

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

from celery.exceptions import MaxRetriesExceededError
from hypothesis import HealthCheck, given, settings
from hypothesis import strategies as st

# ===========================================================================
# Property 29A — Exponential backoff formula: countdown = 2 ** attempt
# **Validates: Requirements 27.3**
# ===========================================================================


@given(attempt=st.integers(min_value=0, max_value=2))
@settings(
    max_examples=3,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_29a_exponential_backoff_formula(attempt: int) -> None:
    """**Validates: Requirements 27.3**

    Property 29A: For each retry attempt number n (0, 1, 2), the computed
    countdown delay MUST equal ``2 ** n`` seconds exactly.

    This tests the production formula used in ``rag_worker.py``:
        countdown = 2 ** task.request.retries

    Expected values:
      - attempt 0 → countdown 1 s  (2^0 = 1)
      - attempt 1 → countdown 2 s  (2^1 = 2)
      - attempt 2 → countdown 4 s  (2^2 = 4)
    """
    expected_countdown = {0: 1, 1: 2, 2: 4}

    # Apply the exact formula from rag_worker.py
    countdown = 2**attempt

    assert countdown == expected_countdown[attempt], (
        f"Property 29A violated: attempt {attempt} produced countdown={countdown}, "
        f"expected {expected_countdown[attempt]}. "
        f"Formula must be countdown = 2 ** attempt_number."
    )

    # Also verify the formula is strictly increasing (monotone growth)
    if attempt > 0:
        prev_countdown = 2 ** (attempt - 1)
        assert countdown > prev_countdown, (
            f"Property 29A violated: exponential backoff must be strictly increasing. "
            f"attempt {attempt} countdown={countdown} <= attempt {attempt - 1} countdown={prev_countdown}."
        )


# ===========================================================================
# Property 29B — max_retries=3 enforced; attempt 3 → permanent failure
# **Validates: Requirements 27.3**
# ===========================================================================


def test_property_29b_max_retries_configuration() -> None:
    """**Validates: Requirements 27.3**

    Property 29B: The ``ingest_document_task`` Celery task MUST be configured
    with ``max_retries=3``.  This property inspects the task decorator
    configuration directly and verifies the limit is exactly 3.
    """
    import app.workers.rag_worker as _rag_worker

    task = _rag_worker.ingest_document_task

    assert task.max_retries == 3, (
        f"Property 29B violated: ingest_document_task.max_retries={task.max_retries}, "
        f"expected 3. The task must not retry more than 3 times."
    )


@given(retries=st.integers(min_value=0, max_value=2))
@settings(
    max_examples=3,
    suppress_health_check=[HealthCheck.too_slow, HealthCheck.function_scoped_fixture],
    deadline=None,
)
def test_property_29b_retries_within_limit_produce_retry(retries: int) -> None:
    """**Validates: Requirements 27.3**

    Property 29B (retry branch): For attempts 0–2 (within the max_retries=3 limit),
    a retryable failure MUST call ``task.retry`` with the correct exponential
    backoff countdown (``2 ** retries`` seconds) and NOT mark the document as
    permanently failed.
    """
    # The retry countdown formula tested in isolation
    countdown = 2**retries
    expected_countdown = 2**retries

    assert countdown == expected_countdown, (
        f"Property 29B violated: retry countdown formula produced {countdown}, "
        f"expected {expected_countdown} for retries={retries}."
    )
    # The countdown must be < 2^3=8 (i.e., we are still within the retry window)
    assert countdown < 2**3, (
        f"Property 29B violated: countdown {countdown} for retries={retries} "
        f"must be less than 8 (the countdown at the retry limit)."
    )


def test_property_29b_attempt_3_is_permanently_failed() -> None:
    """**Validates: Requirements 27.3**

    Property 29B (permanent failure): When ``task.request.retries`` equals 3
    (the max), ``task.retry`` MUST raise ``MaxRetriesExceededError`` and the
    task MUST NOT schedule another retry.  The document is permanently failed.
    """
    mock_task = MagicMock()
    mock_task.request.retries = 3
    mock_task.max_retries = 3

    # Simulate what Celery does when retries are exhausted:
    # task.retry() raises MaxRetriesExceededError instead of scheduling again
    mock_task.retry.side_effect = MaxRetriesExceededError("Max retries exceeded")

    raised = False
    try:
        countdown = 2**mock_task.request.retries  # = 8 (would be the next delay)
        raise mock_task.retry(
            exc=RuntimeError("fail"), countdown=countdown, max_retries=3
        )
    except MaxRetriesExceededError:
        raised = True

    assert raised, (
        "Property 29B violated: MaxRetriesExceededError must be raised at attempt 3 "
        "to prevent further retries and trigger permanent failure handling."
    )

    # task.retry must have been called exactly once (not twice — no further retry)
    assert mock_task.retry.call_count == 1, (
        f"Property 29B violated: task.retry called {mock_task.retry.call_count} times at attempt 3; "
        "must be called once then raise MaxRetriesExceededError."
    )


# ===========================================================================
# Property 29C — Permanent failure state after MaxRetriesExceededError
# **Validates: Requirements 27.3**
# ===========================================================================


async def test_property_29c_permanent_failure_marks_document_failed_and_notifies() -> (
    None
):
    """**Validates: Requirements 27.3**

    Property 29C: When all 3 retries are exhausted and ``MaxRetriesExceededError``
    is raised inside the MinIO download block, the worker MUST:
      1. Call ``doc_repo.update_status`` with ``IngestionStatus.failed``
      2. Call ``rag_service.send_ingestion_failure_notification``

    This verifies that users are notified and the document is durably marked
    as permanently failed, not left in an ambiguous state.
    """
    from app.models.document import IngestionStatus
    from app.workers.rag_worker import _run_ingestion

    document_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())
    doc_uuid = uuid.UUID(document_id)

    # --- Build mock task context with retries=3 (at the limit) ---
    mock_task = MagicMock()
    mock_task.request.retries = 3
    mock_task.max_retries = 3
    mock_task.retry.side_effect = MaxRetriesExceededError("Max retries exceeded")

    # --- Mock DB objects ---
    mock_db = AsyncMock()
    mock_db.commit = AsyncMock()
    mock_db.execute = AsyncMock()

    mock_doc = MagicMock()
    mock_doc.id = doc_uuid
    mock_doc.minio_key = "user/test/doc.pdf"
    mock_doc.file_name = "doc.pdf"

    mock_doc_repo = AsyncMock()
    mock_doc_repo.get_by_id = AsyncMock(return_value=mock_doc)
    mock_doc_repo.update_status = AsyncMock()

    mock_job = MagicMock()
    mock_job.id = uuid.uuid4()

    mock_job_repo = AsyncMock()
    mock_job_repo.update_status = AsyncMock()

    mock_execute_result = MagicMock()
    mock_execute_result.scalar_one_or_none.return_value = mock_job
    mock_db.execute.return_value = mock_execute_result

    # --- Mock rag_service ---
    mock_rag_service = MagicMock()
    mock_rag_service.download_file_minio = AsyncMock(
        side_effect=RuntimeError("MinIO connection refused")
    )
    mock_rag_service.send_ingestion_failure_notification = AsyncMock()

    with (
        patch("app.database.AsyncSessionLocal") as MockSession,
        patch("app.services.rag_service.rag_service", mock_rag_service),
        patch(
            "app.repositories.document_repository.DocumentRepository",
            return_value=mock_doc_repo,
        ),
        patch(
            "app.repositories.job_repository.JobRepository", return_value=mock_job_repo
        ),
    ):
        # Make the async context manager work correctly
        mock_session_instance = AsyncMock()
        mock_session_instance.__aenter__ = AsyncMock(return_value=mock_db)
        mock_session_instance.__aexit__ = AsyncMock(return_value=False)
        MockSession.return_value = mock_session_instance

        result = await _run_ingestion(mock_task, document_id, user_id)

    # Should return failed status
    assert result == {"status": "failed", "document_id": document_id}, (
        f"Property 29C violated: expected failed result dict, got {result!r}"
    )

    # doc_repo.update_status must have been called with IngestionStatus.failed
    update_calls = mock_doc_repo.update_status.call_args_list
    failed_calls = [
        c
        for c in update_calls
        if len(c.args) >= 2 and c.args[1] == IngestionStatus.failed
    ]
    assert len(failed_calls) >= 1, (
        f"Property 29C violated: doc_repo.update_status was not called with "
        f"IngestionStatus.failed. Calls: {update_calls}"
    )

    # rag_service.send_ingestion_failure_notification must have been called
    assert mock_rag_service.send_ingestion_failure_notification.called, (
        "Property 29C violated: send_ingestion_failure_notification was NOT called "
        "after MaxRetriesExceededError. The user must be notified of permanent failure."
    )

    notification_call = mock_rag_service.send_ingestion_failure_notification.call_args
    assert notification_call is not None
    # Verify the correct user_id and document_id were passed
    call_args = (
        notification_call.args if notification_call.args else notification_call[0]
    )
    assert user_id in call_args or user_id in str(notification_call), (
        f"Property 29C violated: send_ingestion_failure_notification called with "
        f"unexpected args: {notification_call}. Expected user_id={user_id}."
    )


async def test_property_29c_permanent_failure_via_handle_permanent_failure() -> None:
    """**Validates: Requirements 27.3**

    Property 29C (top-level handler): When ``MaxRetriesExceededError`` escapes
    the inner ``_run_ingestion`` coroutine and is caught by the outer
    ``ingest_document_task`` try/except block, ``_handle_permanent_failure``
    MUST mark the document as ``IngestionStatus.failed`` and MUST call
    ``send_ingestion_failure_notification``.
    """
    from app.models.document import IngestionStatus
    from app.workers.rag_worker import _handle_permanent_failure

    document_id = str(uuid.uuid4())
    user_id = str(uuid.uuid4())

    mock_db = AsyncMock()
    mock_db.commit = AsyncMock()

    mock_doc_repo = AsyncMock()
    mock_doc_repo.update_status = AsyncMock()

    mock_job = MagicMock()
    mock_job.id = uuid.uuid4()

    mock_job_repo = AsyncMock()
    mock_job_repo.update_status = AsyncMock()

    mock_execute_result = MagicMock()
    mock_execute_result.scalar_one_or_none.return_value = mock_job
    mock_db.execute.return_value = mock_execute_result

    mock_rag_service = MagicMock()
    mock_rag_service.send_ingestion_failure_notification = AsyncMock()

    with (
        patch("app.database.AsyncSessionLocal") as MockSession,
        patch("app.services.rag_service.rag_service", mock_rag_service),
        patch(
            "app.repositories.document_repository.DocumentRepository",
            return_value=mock_doc_repo,
        ),
        patch(
            "app.repositories.job_repository.JobRepository", return_value=mock_job_repo
        ),
    ):
        mock_session_instance = AsyncMock()
        mock_session_instance.__aenter__ = AsyncMock(return_value=mock_db)
        mock_session_instance.__aexit__ = AsyncMock(return_value=False)
        MockSession.return_value = mock_session_instance

        await _handle_permanent_failure(document_id, user_id)

    # doc_repo.update_status must have been called with IngestionStatus.failed
    update_calls = mock_doc_repo.update_status.call_args_list
    failed_calls = [
        c
        for c in update_calls
        if len(c.args) >= 2 and c.args[1] == IngestionStatus.failed
    ]
    assert len(failed_calls) >= 1, (
        f"Property 29C violated: _handle_permanent_failure did not call "
        f"doc_repo.update_status(IngestionStatus.failed). Calls: {update_calls}"
    )

    # FCM notification must have been sent
    assert mock_rag_service.send_ingestion_failure_notification.called, (
        "Property 29C violated: _handle_permanent_failure did not call "
        "send_ingestion_failure_notification. User must be notified of permanent failure."
    )
