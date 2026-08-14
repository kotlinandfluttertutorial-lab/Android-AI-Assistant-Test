# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : tests/unit
# File    : test_generation_router.py
# Purpose : Unit tests for resume/cover letter/email generation endpoints
#
# Architecture Layer : Test
# Pattern Used       : pytest + FastAPI TestClient with dependency overrides
#
# Key Concepts:
#   - POST /resumes/generate  — ATS resume in Markdown
#   - POST /covers/generate   — Cover letter ≤400 words
#   - POST /emails/generate   — Structured email with all four sections
#   - POST /emails/grammar    — Grammar correction with diff + no_changes_needed
#   - AIOrchestrator mocked throughout
#   - JWT authentication bypassed via dependency override
#
# Requirements: 14.1, 14.2, 14.4, 14.5
# ============================================================

"""Unit tests for generation endpoints.

Tests cover:
- POST /resumes/generate  (Requirement 14.1)
- POST /covers/generate   (Requirement 14.2)
- POST /emails/generate   (Requirement 14.4)
- POST /emails/grammar    (Requirement 14.5)

Requirements: 14.1, 14.2, 14.4, 14.5
"""

from __future__ import annotations

import os
from datetime import datetime, timezone
from unittest.mock import AsyncMock, patch

import pytest

os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.api.generation.router import covers_router, emails_router, resumes_router
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _fake_user() -> TokenPayload:
    return TokenPayload(
        sub="user-gen-01",
        role="user",
        jti="jti-gen",
        iat=datetime.now(tz=timezone.utc),
        exp=datetime(2099, 1, 1, tzinfo=timezone.utc),
    )


def _build_app() -> FastAPI:
    app = FastAPI()
    app.dependency_overrides[get_current_user] = lambda: _fake_user()
    app.include_router(resumes_router)
    app.include_router(covers_router)
    app.include_router(emails_router)
    return app


@pytest.fixture()
def client() -> TestClient:
    return TestClient(_build_app(), raise_server_exceptions=False)


def _mock_orchestrate(return_text: str):
    """Patch _orchestrate in generation router to return a fixed string."""
    return patch(
        "app.api.generation.router._orchestrate",
        new=AsyncMock(return_value=return_text),
    )


# Realistic resume markdown
_RESUME_MD = """# John Doe
## Summary
Software engineer with 5 years of experience.
## Experience
- Senior Developer at Acme Corp (2020–present)
## Education
- BS Computer Science, State University, 2018
## Skills
Python, FastAPI, Kotlin
"""

# Realistic cover letter (50 words)
_COVER_LETTER = (
    "Dear Hiring Manager, I am excited to apply for the Software Engineer role. "
    "My five years of Python and FastAPI experience make me an excellent fit. "
    "I look forward to discussing how I can contribute to your team. "
    "Best regards, John Doe."
)

# Realistic email JSON response
_EMAIL_JSON = (
    '{"subject": "Project Update", "greeting": "Hi Team,", '
    '"body": "Please find the attached project update.", "closing": "Best regards,"}'
)

# Realistic grammar response (no changes needed)
_GRAMMAR_NO_CHANGE = (
    '{"corrected_text": "Hello, this is correct.", '
    '"no_changes_needed": true, "diff": []}'
)

# Grammar response with changes
_GRAMMAR_WITH_CHANGE = (
    '{"corrected_text": "The meeting was productive.", '
    '"no_changes_needed": false, '
    '"diff": [{"type": "delete", "text": "waz"}, {"type": "insert", "text": "was"}]}'
)


# ---------------------------------------------------------------------------
# POST /resumes/generate (Requirement 14.1)
# ---------------------------------------------------------------------------


class TestResumeGenerate:
    """Tests for POST /resumes/generate."""

    def _valid_payload(self) -> dict:
        return {
            "work_experience": [
                {
                    "title": "Software Engineer",
                    "company": "Acme Corp",
                    "dates": "2020–present",
                    "description": "Built scalable APIs",
                }
            ],
            "contact_info": {"name": "John Doe", "email": "john@example.com"},
            "job_description": "We need a backend engineer with Python experience.",
            "skills": ["Python", "FastAPI"],
        }

    def test_returns_200_with_valid_payload(self, client: TestClient) -> None:
        """Valid request should return 200."""
        with _mock_orchestrate(_RESUME_MD):
            response = client.post("/resumes/generate", json=self._valid_payload())
        assert response.status_code == 200

    def test_response_contains_resume_markdown(self, client: TestClient) -> None:
        """Response must include resume_markdown field."""
        with _mock_orchestrate(_RESUME_MD):
            response = client.post("/resumes/generate", json=self._valid_payload())
        assert response.status_code == 200
        body = response.json()
        assert "resume_markdown" in body
        assert body["resume_markdown"] == _RESUME_MD

    def test_response_contains_generated_at(self, client: TestClient) -> None:
        """Response must include generated_at datetime field."""
        with _mock_orchestrate(_RESUME_MD):
            response = client.post("/resumes/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert "generated_at" in response.json()

    def test_rejects_missing_work_experience(self, client: TestClient) -> None:
        """Missing work_experience should return 422."""
        payload = {
            "contact_info": {"name": "John"},
            "job_description": "Developer role",
        }
        response = client.post("/resumes/generate", json=payload)
        assert response.status_code == 422

    def test_rejects_empty_work_experience(self, client: TestClient) -> None:
        """Empty work_experience list should return 422 (min_length=1)."""
        payload = self._valid_payload()
        payload["work_experience"] = []
        response = client.post("/resumes/generate", json=payload)
        assert response.status_code == 422

    def test_rejects_missing_contact_info(self, client: TestClient) -> None:
        """Missing contact_info should return 422."""
        payload = {
            "work_experience": [{"title": "Dev", "company": "X"}],
            "job_description": "Dev role",
        }
        response = client.post("/resumes/generate", json=payload)
        assert response.status_code == 422

    def test_rejects_missing_job_description(self, client: TestClient) -> None:
        """Missing job_description should return 422."""
        payload = {
            "work_experience": [{"title": "Dev", "company": "X"}],
            "contact_info": {"name": "John"},
        }
        response = client.post("/resumes/generate", json=payload)
        assert response.status_code == 422

    def test_returns_503_on_orchestrator_failure(self, client: TestClient) -> None:
        """If LLM fails, should return 503 or 504 (not 500)."""
        with patch(
            "app.api.generation.router._orchestrate",
            new=AsyncMock(side_effect=Exception("LLM timeout")),
        ):
            response = client.post("/resumes/generate", json=self._valid_payload())
        assert response.status_code in (503, 504)


# ---------------------------------------------------------------------------
# POST /covers/generate (Requirement 14.2)
# ---------------------------------------------------------------------------


class TestCoverLetterGenerate:
    """Tests for POST /covers/generate."""

    def _valid_payload(self) -> dict:
        return {
            "job_description": "We are looking for a Python backend engineer.",
            "resume_data": {
                "name": "Jane Smith",
                "experience": "5 years in Python",
                "skills": ["Python", "Django"],
            },
        }

    def test_returns_200_with_valid_payload(self, client: TestClient) -> None:
        """Valid request should return 200."""
        with _mock_orchestrate(_COVER_LETTER):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 200

    def test_response_contains_cover_letter(self, client: TestClient) -> None:
        """Response must include cover_letter field."""
        with _mock_orchestrate(_COVER_LETTER):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert "cover_letter" in response.json()

    def test_response_contains_word_count(self, client: TestClient) -> None:
        """Response must include word_count."""
        with _mock_orchestrate(_COVER_LETTER):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 200
        body = response.json()
        assert "word_count" in body
        assert isinstance(body["word_count"], int)
        assert body["word_count"] > 0

    def test_word_count_matches_actual_words(self, client: TestClient) -> None:
        """word_count should equal the actual word count of the cover letter."""
        with _mock_orchestrate(_COVER_LETTER):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 200
        body = response.json()
        actual_wc = len(body["cover_letter"].split())
        assert body["word_count"] == actual_wc

    def test_response_contains_generated_at(self, client: TestClient) -> None:
        """Response must include generated_at."""
        with _mock_orchestrate(_COVER_LETTER):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert "generated_at" in response.json()

    def test_rejects_missing_job_description(self, client: TestClient) -> None:
        """Missing job_description should return 422 (Requirement 14.2)."""
        payload = {"resume_data": {"name": "Jane"}}
        response = client.post("/covers/generate", json=payload)
        assert response.status_code == 422

    def test_rejects_missing_resume_data(self, client: TestClient) -> None:
        """Missing resume_data should return 422 (Requirement 14.2)."""
        payload = {"job_description": "Developer role"}
        response = client.post("/covers/generate", json=payload)
        assert response.status_code == 422

    def test_returns_503_on_orchestrator_failure(self, client: TestClient) -> None:
        """If LLM fails, should return 503."""
        with patch(
            "app.api.generation.router._orchestrate",
            new=AsyncMock(side_effect=Exception("LLM error")),
        ):
            response = client.post("/covers/generate", json=self._valid_payload())
        assert response.status_code == 503


# ---------------------------------------------------------------------------
# POST /emails/generate (Requirement 14.4)
# ---------------------------------------------------------------------------


class TestEmailGenerate:
    """Tests for POST /emails/generate."""

    def _valid_payload(self) -> dict:
        return {
            "context": "Following up on last week's meeting about Q3 goals.",
            "intent": "Request feedback on the Q3 proposal document.",
            "recipient_name": "Alice",
            "tone": "professional",
        }

    def test_returns_200_with_valid_payload(self, client: TestClient) -> None:
        """Valid request should return 200."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200

    def test_response_has_all_four_components(self, client: TestClient) -> None:
        """Response must include subject, greeting, body, and closing (Req 14.4)."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        body = response.json()
        assert "subject" in body
        assert "greeting" in body
        assert "body" in body
        assert "closing" in body

    def test_subject_is_non_empty(self, client: TestClient) -> None:
        """Subject line should be a non-empty string."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert response.json()["subject"]  # Non-empty

    def test_greeting_is_non_empty(self, client: TestClient) -> None:
        """Greeting should be a non-empty string."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert response.json()["greeting"]

    def test_body_is_non_empty(self, client: TestClient) -> None:
        """Body should be a non-empty string."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert response.json()["body"]

    def test_closing_is_non_empty(self, client: TestClient) -> None:
        """Closing should be a non-empty string."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert response.json()["closing"]

    def test_response_contains_generated_at(self, client: TestClient) -> None:
        """Response must include generated_at."""
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        assert "generated_at" in response.json()

    def test_rejects_missing_context(self, client: TestClient) -> None:
        """Missing context should return 422."""
        payload = {"intent": "Request update"}
        response = client.post("/emails/generate", json=payload)
        assert response.status_code == 422

    def test_rejects_missing_intent(self, client: TestClient) -> None:
        """Missing intent should return 422."""
        payload = {"context": "Background context"}
        response = client.post("/emails/generate", json=payload)
        assert response.status_code == 422

    def test_returns_503_on_orchestrator_failure(self, client: TestClient) -> None:
        """If LLM fails, should return 503."""
        with patch(
            "app.api.generation.router._orchestrate",
            new=AsyncMock(side_effect=Exception("LLM error")),
        ):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 503

    def test_malformed_llm_json_uses_fallback(self, client: TestClient) -> None:
        """When LLM returns non-JSON, fallback fills in all four fields."""
        with _mock_orchestrate("This is a plain text email response."):
            response = client.post("/emails/generate", json=self._valid_payload())
        assert response.status_code == 200
        body = response.json()
        # Fallback should still provide all four keys
        assert "subject" in body
        assert "greeting" in body
        assert "body" in body
        assert "closing" in body

    def test_optional_recipient_name(self, client: TestClient) -> None:
        """recipient_name is optional — omitting it should not cause 422."""
        payload = {
            "context": "Reaching out about the proposal",
            "intent": "Schedule a meeting",
        }
        with _mock_orchestrate(_EMAIL_JSON):
            response = client.post("/emails/generate", json=payload)
        assert response.status_code == 200


# ---------------------------------------------------------------------------
# POST /emails/grammar (Requirement 14.5)
# ---------------------------------------------------------------------------


class TestEmailGrammar:
    """Tests for POST /emails/grammar."""

    def test_returns_200_with_valid_text(self, client: TestClient) -> None:
        """Valid text should return 200."""
        with _mock_orchestrate(_GRAMMAR_NO_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "Hello, this is correct."},
            )
        assert response.status_code == 200

    def test_no_changes_needed_indicator_true(self, client: TestClient) -> None:
        """When LLM says no changes, no_changes_needed should be True."""
        with _mock_orchestrate(_GRAMMAR_NO_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "Hello, this is correct."},
            )
        assert response.status_code == 200
        body = response.json()
        assert "no_changes_needed" in body
        assert body["no_changes_needed"] is True

    def test_diff_empty_when_no_changes(self, client: TestClient) -> None:
        """When no changes needed, diff list should be empty."""
        with _mock_orchestrate(_GRAMMAR_NO_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "Hello, this is correct."},
            )
        assert response.status_code == 200
        assert response.json()["diff"] == []

    def test_no_changes_needed_false_when_corrections_made(
        self, client: TestClient
    ) -> None:
        """When LLM makes corrections, no_changes_needed should be False."""
        with _mock_orchestrate(_GRAMMAR_WITH_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "The meeting waz productive."},
            )
        assert response.status_code == 200
        assert response.json()["no_changes_needed"] is False

    def test_diff_contains_changes_when_corrections_made(
        self, client: TestClient
    ) -> None:
        """When corrections are made, diff should be non-empty."""
        with _mock_orchestrate(_GRAMMAR_WITH_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "The meeting waz productive."},
            )
        assert response.status_code == 200
        diff = response.json()["diff"]
        assert len(diff) > 0

    def test_diff_items_have_type_and_text(self, client: TestClient) -> None:
        """Each diff item must have 'type' and 'text' fields."""
        with _mock_orchestrate(_GRAMMAR_WITH_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "The meeting waz productive."},
            )
        assert response.status_code == 200
        for item in response.json()["diff"]:
            assert "type" in item
            assert "text" in item
            assert item["type"] in ("insert", "delete")

    def test_corrected_text_present_in_response(self, client: TestClient) -> None:
        """Response must include corrected_text field."""
        with _mock_orchestrate(_GRAMMAR_WITH_CHANGE):
            response = client.post(
                "/emails/grammar",
                json={"text": "The meeting waz productive."},
            )
        assert response.status_code == 200
        assert "corrected_text" in response.json()
        assert response.json()["corrected_text"]

    def test_rejects_missing_text(self, client: TestClient) -> None:
        """Missing text field should return 422."""
        response = client.post("/emails/grammar", json={})
        assert response.status_code == 422

    def test_returns_503_on_orchestrator_failure(self, client: TestClient) -> None:
        """If LLM fails, should return 503."""
        with patch(
            "app.api.generation.router._orchestrate",
            new=AsyncMock(side_effect=Exception("LLM down")),
        ):
            response = client.post(
                "/emails/grammar",
                json={"text": "Some email text."},
            )
        assert response.status_code == 503

    def test_malformed_llm_response_uses_fallback(self, client: TestClient) -> None:
        """Non-JSON LLM response should use fallback (original text, no_changes=True)."""
        original = "Some correctly written text."
        with _mock_orchestrate("This is not valid JSON at all."):
            response = client.post(
                "/emails/grammar",
                json={"text": original},
            )
        assert response.status_code == 200
        body = response.json()
        assert body["no_changes_needed"] is True
        assert body["diff"] == []


# ---------------------------------------------------------------------------
# Authentication
# ---------------------------------------------------------------------------


class TestGenerationAuth:
    """All generation endpoints require JWT authentication."""

    def _make_unauthenticated_client(self) -> TestClient:
        app = FastAPI()
        app.include_router(resumes_router)
        app.include_router(covers_router)
        app.include_router(emails_router)
        return TestClient(app, raise_server_exceptions=False)

    def test_resumes_unauthenticated_rejected(self) -> None:
        client = self._make_unauthenticated_client()
        response = client.post("/resumes/generate", json={})
        assert response.status_code in (401, 403, 422)

    def test_covers_unauthenticated_rejected(self) -> None:
        client = self._make_unauthenticated_client()
        response = client.post("/covers/generate", json={})
        assert response.status_code in (401, 403, 422)

    def test_emails_generate_unauthenticated_rejected(self) -> None:
        client = self._make_unauthenticated_client()
        response = client.post("/emails/generate", json={})
        assert response.status_code in (401, 403, 422)

    def test_emails_grammar_unauthenticated_rejected(self) -> None:
        client = self._make_unauthenticated_client()
        response = client.post("/emails/grammar", json={})
        assert response.status_code in (401, 403, 422)
