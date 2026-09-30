"""Unit tests for ErrorAnalysisService — Phase 10.

Tests cover the complete 8-step AI error analysis pipeline:

  analyse() — entry point:
    - No events found → _no_data_response (confidence 0.0, low_confidence_warning set)
    - Events found, LLM returns valid JSON → builds ErrorAnalysisResponse
    - LLM returns empty string → safe fallback response (confidence 0.0)
    - LLM returns malformed JSON → safe fallback response
    - Confidence < 0.6 → overrides likely_root_cause with insufficient-evidence message
    - Confidence >= 0.6 → passes likely_root_cause through from LLM
    - request.event_id mode → calls get_by_id then get_by_session
    - request.session_id mode → calls get_by_session
    - request.lookback_minutes mode → calls get_recent_errors

  _derive_search_query():
    - Extracts event types, deduped message prefixes, and screens
    - Falls back to "application error" when events list is empty
    - Only includes ERROR/CRITICAL events in the query derivation

  _build_prompt():
    - Contains all six AI safety rules
    - Includes evidence block when events are present
    - Formats ERROR and WARN events in separate sections

  _parse_llm_response():
    - Parses clean JSON
    - Strips markdown code fences (```json ... ```)
    - Returns {} on empty string
    - Returns {} on invalid JSON

  _build_response():
    - Maps parsed dict fields to ErrorAnalysisResponse correctly
    - Merges LLM-cited docs with RAG source document_name values
    - Populates low_confidence_warning only when confidence < 0.6
    - Populates events_analysed and knowledge_chunks_retrieved counts
    - Unknown severity string defaults to MEDIUM

  _no_data_response():
    - Returns LOW severity response with confidence 0.0
    - Sets low_confidence_warning

Teaching notes (Phase 10 — AI Error Analysis):
  The service is the most important layer to unit-test because it embeds
  AI Safety rules. The key invariants:
    1. confidence < 0.6 ALWAYS overrides likely_root_cause — test this explicitly.
    2. LLM failures produce SAFE fallbacks, not exceptions — the system degrades
       gracefully so the incident is always visible even when the LLM is down.
    3. Facts and inferences are always separate — never merge them.

  Mock strategy:
    - ObservabilityEventRepository is mocked at the class level via patch()
    - rag_service.query_knowledge_base is mocked via patch()
    - AIOrchestrator.complete is mocked via patch()
    - The db session itself is a bare AsyncMock — the service never calls it
      directly (only passes it to the repository constructor)

Requirements: Phase 10
"""

from __future__ import annotations

import json
import os
import uuid
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

# Set env vars before any app import
os.environ.setdefault("SECRET_KEY", "test-secret-key-at-least-32-chars-long!!")
os.environ.setdefault("DATABASE_URL", "postgresql+asyncpg://test:test@localhost/test")
os.environ.setdefault("REDIS_URL", "redis://localhost:6379/0")
os.environ.setdefault("AES_ENCRYPTION_KEY", "dGVzdGtleXRlc3RrZXl0ZXN0a2V5dGVzdA==")

from app.models.observability_event import ObservabilityEvent
from app.schemas.error_analysis import AnalyseErrorRequest, ErrorSeverity
from app.services.error_analysis_service import (
    ErrorAnalysisService,
    _LOW_CONFIDENCE_THRESHOLD,
    _MAX_EVENTS_IN_PROMPT,
)


# ---------------------------------------------------------------------------
# Shared test data helpers
# ---------------------------------------------------------------------------

_SESSION_ID  = "sess-phase10-test-001"
_EVENT_ID    = uuid.uuid4()
_ANALYSIS_ID = str(uuid.uuid4())


def _mock_db() -> AsyncMock:
    """Minimal AsyncMock that satisfies the service constructor."""
    db = AsyncMock()
    db.execute = AsyncMock()
    db.commit  = AsyncMock()
    return db


def _make_orm_event(
    level: str = "ERROR",
    event_type: str = "network_error",
    message: str = "POST /api returned HTTP 500",
    session_id: str = _SESSION_ID,
    screen: str | None = "HomeScreen",
    metadata_json: str = '{"http_status":"500"}',
) -> MagicMock:
    """Return a MagicMock shaped like an ObservabilityEvent ORM row."""
    from datetime import UTC, datetime

    ev = MagicMock(spec=ObservabilityEvent)
    ev.id            = _EVENT_ID
    ev.timestamp_ms  = 1_700_000_000_000
    ev.level         = level
    ev.event_type    = event_type
    ev.message       = message
    ev.session_id    = session_id
    ev.request_id    = "req-001"
    ev.trace_id      = "trace-001"
    ev.screen        = screen
    ev.metadata_json = metadata_json
    ev.received_at   = datetime(2025, 6, 1, 14, 32, 0, tzinfo=UTC)
    return ev


def _make_valid_llm_json(
    severity: str = "HIGH",
    confidence: float = 0.87,
    likely_root_cause: str = "Connection pool exhausted",
) -> str:
    """Return a well-formed LLM JSON response string."""
    return json.dumps({
        "severity":              severity,
        "summary":               "Database connection pool exhausted",
        "evidence":              ["Pool at 20/20 connections", "Latency spike +340%"],
        "possible_causes":       ["Pool too small", "Slow queries holding connections"],
        "likely_root_cause":     likely_root_cause,
        "confidence":            confidence,
        "recommended_fix":       "1. Increase pool size 10→20. 2. Add query timeout 10s.",
        "related_documentation": ["runbooks/db-connection.md"],
        "facts":                 ["Pool at capacity 20/20", "Error rate 23% in 5 min"],
        "inferences":            ["Slow query in recent deployment may be holding connections"],
    })


def _make_kb_chunk(source: str = "runbooks/db-connection.md") -> dict:
    """Return a minimal knowledge-base chunk dict."""
    return {
        "source":        source,
        "document_name": source,
        "content":       "Restart the connection pool if all connections are in use.",
    }


# ---------------------------------------------------------------------------
# Helpers for patching the service's collaborators
# ---------------------------------------------------------------------------

def _patch_repo(
    get_recent_errors_return: list | None = None,
    get_by_session_return:    list | None = None,
    get_by_id_return:         MagicMock | None = None,
):
    """Return a patch context for ObservabilityEventRepository."""
    mock_repo = AsyncMock()
    mock_repo.get_recent_errors.return_value = get_recent_errors_return or []
    mock_repo.get_by_session.return_value    = get_by_session_return or []
    mock_repo.get_by_id.return_value         = get_by_id_return

    return patch(
        "app.services.error_analysis_service.ObservabilityEventRepository",
        return_value=mock_repo,
    ), mock_repo


def _patch_rag(
    runbook_chunks: list | None = None,
    incident_chunks: list | None = None,
):
    """Return a patch context for rag_service.query_knowledge_base."""
    chunks_by_call: list[list] = [
        runbook_chunks or [],
        incident_chunks or [],
    ]
    call_count = {"n": 0}

    async def _side_effect(*_args, **_kwargs):
        idx = call_count["n"]
        call_count["n"] += 1
        if idx < len(chunks_by_call):
            return chunks_by_call[idx]
        return []

    return patch(
        "app.services.error_analysis_service.rag_service.query_knowledge_base",
        side_effect=_side_effect,
    )


def _patch_orchestrator(response_text: str = ""):
    """Return a patch context for AIOrchestrator.complete."""
    completion = MagicMock()
    completion.text = response_text

    mock_orch = AsyncMock()
    mock_orch.complete = AsyncMock(return_value=completion)

    return patch(
        "app.services.error_analysis_service.AIOrchestrator",
        return_value=mock_orch,
    )


# ---------------------------------------------------------------------------
# analyse() — no events path
# ---------------------------------------------------------------------------


class TestAnalyseNoData:
    """analyse() returns a safe _no_data_response when the DB has no matching events."""

    async def test_returns_no_data_response_when_repo_returns_empty_list(self) -> None:
        patch_repo, _ = _patch_repo(get_recent_errors_return=[])

        with patch_repo, _patch_rag(), _patch_orchestrator():
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.events_analysed == 0
        assert result.confidence == 0.0
        assert result.low_confidence_warning is not None

    async def test_no_data_severity_is_low(self) -> None:
        patch_repo, _ = _patch_repo(get_recent_errors_return=[])

        with patch_repo, _patch_rag(), _patch_orchestrator():
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.severity == ErrorSeverity.LOW

    async def test_no_data_response_sets_meaningful_summary(self) -> None:
        patch_repo, _ = _patch_repo(get_recent_errors_return=[])

        with patch_repo, _patch_rag(), _patch_orchestrator():
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert "No error events" in result.summary

    async def test_no_data_analysis_id_is_a_valid_uuid(self) -> None:
        patch_repo, _ = _patch_repo(get_recent_errors_return=[])

        with patch_repo, _patch_rag(), _patch_orchestrator():
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        uuid.UUID(result.analysis_id)  # raises ValueError if not a UUID


# ---------------------------------------------------------------------------
# analyse() — happy path: valid LLM response
# ---------------------------------------------------------------------------


class TestAnalyseHappyPath:
    """analyse() with real events and a well-formed LLM JSON response."""

    async def test_returns_error_analysis_response_with_correct_fields(self) -> None:
        events     = [_make_orm_event()]
        llm_json   = _make_valid_llm_json(confidence=0.87)
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(llm_json):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.severity      == ErrorSeverity.HIGH
        assert result.confidence    == 0.87
        assert "Connection pool"    in result.likely_root_cause
        assert "Connection pool"    in result.summary
        assert len(result.evidence) == 2

    async def test_events_analysed_count_matches_events_returned(self) -> None:
        events     = [_make_orm_event() for _ in range(5)]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.events_analysed == 5

    async def test_knowledge_chunks_retrieved_counts_both_rag_results(self) -> None:
        events         = [_make_orm_event()]
        runbook_chunk  = _make_kb_chunk("runbooks/db.md")
        incident_chunk = _make_kb_chunk("incidents/INC-101.md")
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with (
            patch_repo,
            _patch_rag(runbook_chunks=[runbook_chunk], incident_chunks=[incident_chunk]),
            _patch_orchestrator(_make_valid_llm_json()),
        ):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.knowledge_chunks_retrieved == 2

    async def test_facts_and_inferences_are_populated_separately(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert len(result.facts_vs_inference.facts)      > 0
        assert len(result.facts_vs_inference.inferences) > 0

    async def test_llm_provider_name_is_populated(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        # Provider name should be a non-empty string (e.g. "gemini")
        assert isinstance(result.llm_provider, str)

    async def test_low_confidence_warning_is_none_when_confidence_above_threshold(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)
        high_conf  = _make_valid_llm_json(confidence=0.90)

        with patch_repo, _patch_rag(), _patch_orchestrator(high_conf):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.low_confidence_warning is None

    async def test_related_docs_merged_from_llm_and_rag_sources(self) -> None:
        events     = [_make_orm_event()]
        kb_chunk   = _make_kb_chunk("runbooks/db-restart.md")
        llm_json   = _make_valid_llm_json()  # includes "runbooks/db-connection.md"
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with (
            patch_repo,
            _patch_rag(runbook_chunks=[kb_chunk]),
            _patch_orchestrator(llm_json),
        ):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        # Should include both the LLM-cited doc AND the RAG source
        assert "runbooks/db-connection.md" in result.related_documentation
        assert "runbooks/db-restart.md"    in result.related_documentation

    async def test_related_docs_are_deduplicated(self) -> None:
        """If LLM cites the same doc that RAG returned, it appears only once."""
        events     = [_make_orm_event()]
        # RAG returns the same doc that the LLM will cite
        kb_chunk   = _make_kb_chunk("runbooks/db-connection.md")
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with (
            patch_repo,
            _patch_rag(runbook_chunks=[kb_chunk]),
            _patch_orchestrator(_make_valid_llm_json()),
        ):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.related_documentation.count("runbooks/db-connection.md") == 1


# ---------------------------------------------------------------------------
# analyse() — AI safety: confidence gate
# ---------------------------------------------------------------------------


class TestConfidenceGate:
    """AI Safety Rule: confidence < 0.6 must override likely_root_cause."""

    @pytest.mark.parametrize("confidence", [0.0, 0.1, 0.55, 0.59])
    async def test_low_confidence_overrides_likely_root_cause(
        self, confidence: float
    ) -> None:
        events     = [_make_orm_event()]
        llm_json   = _make_valid_llm_json(
            confidence=confidence,
            likely_root_cause="Should be overridden",
        )
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(llm_json):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert "Evidence is insufficient" in result.likely_root_cause
        assert "manual investigation" in result.likely_root_cause.lower()

    @pytest.mark.parametrize("confidence", [0.0, 0.1, 0.59])
    async def test_low_confidence_sets_low_confidence_warning(
        self, confidence: float
    ) -> None:
        events     = [_make_orm_event()]
        llm_json   = _make_valid_llm_json(confidence=confidence)
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(llm_json):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.low_confidence_warning is not None
        assert len(result.low_confidence_warning) > 0

    @pytest.mark.parametrize("confidence", [0.60, 0.75, 0.95, 1.0])
    async def test_adequate_confidence_preserves_likely_root_cause(
        self, confidence: float
    ) -> None:
        events     = [_make_orm_event()]
        cause      = "Slow query holding DB connections"
        llm_json   = _make_valid_llm_json(confidence=confidence, likely_root_cause=cause)
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(llm_json):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.likely_root_cause == cause

    def test_low_confidence_threshold_constant_is_0_6(self) -> None:
        """Regression guard — the threshold must never silently change."""
        assert _LOW_CONFIDENCE_THRESHOLD == 0.6


# ---------------------------------------------------------------------------
# analyse() — LLM failure paths
# ---------------------------------------------------------------------------


class TestLlmFailurePaths:
    """When the LLM returns nothing useful, analyse() must degrade safely."""

    async def test_empty_llm_response_returns_safe_fallback(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(""):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.confidence == 0.0
        assert result.low_confidence_warning is not None
        assert "AI analysis unavailable" in result.summary

    async def test_malformed_json_returns_safe_fallback(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator("not json at all {{{"):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.confidence == 0.0
        assert result.low_confidence_warning is not None

    async def test_fallback_response_includes_raw_events_as_evidence(self) -> None:
        """Even when LLM fails, the first 5 raw events appear in evidence."""
        events     = [_make_orm_event(message=f"error-{i}") for i in range(3)]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(""):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        # Evidence should contain at least the events we provided
        assert len(result.evidence) > 0

    async def test_fallback_response_includes_events_analysed_count(self) -> None:
        events     = [_make_orm_event() for _ in range(4)]
        patch_repo, _ = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(""):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(AnalyseErrorRequest())

        assert result.events_analysed == 4


# ---------------------------------------------------------------------------
# analyse() — request modes (event_id / session_id / lookback)
# ---------------------------------------------------------------------------


class TestRequestModes:
    """analyse() calls the correct repository method based on request fields."""

    async def test_event_id_mode_calls_get_by_id(self) -> None:
        event      = _make_orm_event()
        patch_repo, mock_repo = _patch_repo(
            get_by_id_return=event,
            get_by_session_return=[event],
        )

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            await service.analyse(AnalyseErrorRequest(event_id=str(_EVENT_ID)))

        mock_repo.get_by_id.assert_awaited_once()
        call_arg = mock_repo.get_by_id.call_args[0][0]
        assert str(call_arg) == str(_EVENT_ID)

    async def test_event_id_mode_fetches_session_context_when_session_id_present(
        self,
    ) -> None:
        """When the specific event has a session_id, fetch full session context."""
        event      = _make_orm_event()  # has session_id=_SESSION_ID
        patch_repo, mock_repo = _patch_repo(
            get_by_id_return=event,
            get_by_session_return=[event],
        )

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            await service.analyse(AnalyseErrorRequest(event_id=str(_EVENT_ID)))

        mock_repo.get_by_session.assert_awaited_once()

    async def test_event_id_not_found_returns_no_data_response(self) -> None:
        """If event_id exists in the request but not in the DB, return no-data."""
        patch_repo, _ = _patch_repo(get_by_id_return=None, get_by_session_return=[])

        with patch_repo, _patch_rag(), _patch_orchestrator(""):
            service = ErrorAnalysisService(_mock_db())
            result  = await service.analyse(
                AnalyseErrorRequest(event_id=str(uuid.uuid4()))
            )

        assert result.events_analysed == 0

    async def test_session_id_mode_calls_get_by_session(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, mock_repo = _patch_repo(get_by_session_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            await service.analyse(
                AnalyseErrorRequest(session_id=_SESSION_ID, lookback_minutes=45)
            )

        mock_repo.get_by_session.assert_awaited_once()
        kwargs = mock_repo.get_by_session.call_args[1]
        assert kwargs["session_id"] == _SESSION_ID
        assert kwargs["minutes"]    == 45

    async def test_default_mode_calls_get_recent_errors(self) -> None:
        events     = [_make_orm_event()]
        patch_repo, mock_repo = _patch_repo(get_recent_errors_return=events)

        with patch_repo, _patch_rag(), _patch_orchestrator(_make_valid_llm_json()):
            service = ErrorAnalysisService(_mock_db())
            await service.analyse(AnalyseErrorRequest(lookback_minutes=60))

        mock_repo.get_recent_errors.assert_awaited_once()
        kwargs = mock_repo.get_recent_errors.call_args[1]
        assert kwargs["minutes"] == 60


# ---------------------------------------------------------------------------
# _parse_llm_response()
# ---------------------------------------------------------------------------


class TestParseLlmResponse:
    """_parse_llm_response handles various LLM output formats."""

    def _service(self) -> ErrorAnalysisService:
        return ErrorAnalysisService(_mock_db())

    def test_parses_clean_json(self) -> None:
        raw  = json.dumps({"severity": "HIGH", "confidence": 0.9})
        svc  = self._service()
        result = svc._parse_llm_response(raw)
        assert result["severity"]   == "HIGH"
        assert result["confidence"] == 0.9

    def test_strips_markdown_code_fence(self) -> None:
        raw = '```json\n{"severity": "LOW", "confidence": 0.5}\n```'
        svc = self._service()
        result = svc._parse_llm_response(raw)
        assert result["severity"] == "LOW"

    def test_strips_backtick_only_fence(self) -> None:
        raw = '```\n{"severity": "MEDIUM"}\n```'
        svc = self._service()
        result = svc._parse_llm_response(raw)
        assert result["severity"] == "MEDIUM"

    def test_returns_empty_dict_for_empty_string(self) -> None:
        svc = self._service()
        assert svc._parse_llm_response("") == {}

    def test_returns_empty_dict_for_whitespace_only(self) -> None:
        svc = self._service()
        assert svc._parse_llm_response("   \n  ") == {}

    def test_returns_empty_dict_for_invalid_json(self) -> None:
        svc = self._service()
        assert svc._parse_llm_response("not json at all") == {}

    def test_returns_empty_dict_for_unclosed_brace(self) -> None:
        svc = self._service()
        assert svc._parse_llm_response('{"key": "value"') == {}

    def test_extracts_json_from_surrounding_prose(self) -> None:
        """LLM sometimes adds explanation text around the JSON object."""
        raw = 'Here is my analysis:\n{"severity": "HIGH", "confidence": 0.8}\nEnd.'
        svc = self._service()
        result = svc._parse_llm_response(raw)
        assert result["severity"]   == "HIGH"
        assert result["confidence"] == 0.8


# ---------------------------------------------------------------------------
# _derive_search_query()
# ---------------------------------------------------------------------------


class TestDeriveSearchQuery:
    """_derive_search_query extracts a meaningful RAG search query from events."""

    def _service(self) -> ErrorAnalysisService:
        return ErrorAnalysisService(_mock_db())

    def test_includes_event_types_in_query(self) -> None:
        events  = [_make_orm_event(event_type="http_timeout")]
        svc     = self._service()
        query   = svc._derive_search_query(events)
        assert "http_timeout" in query

    def test_includes_screen_context_when_available(self) -> None:
        events  = [_make_orm_event(screen="CheckoutScreen")]
        svc     = self._service()
        query   = svc._derive_search_query(events)
        assert "CheckoutScreen" in query

    def test_returns_fallback_for_empty_list(self) -> None:
        svc   = self._service()
        query = svc._derive_search_query([])
        assert query == "application error"

    def test_deduplicates_similar_messages(self) -> None:
        """Duplicate message prefixes should not appear multiple times."""
        msg     = "POST /api returned HTTP 500"
        events  = [_make_orm_event(message=msg) for _ in range(5)]
        svc     = self._service()
        query   = svc._derive_search_query(events)
        # Message text should appear at most once in the query
        assert query.count(msg[:80]) <= 1

    def test_prefers_error_events_over_info_events(self) -> None:
        """When both ERROR and INFO events exist, ERROR drives the query."""
        events = [
            _make_orm_event(level="INFO", event_type="page_view"),
            _make_orm_event(level="ERROR", event_type="db_connection_refused"),
        ]
        svc   = self._service()
        query = svc._derive_search_query(events)
        assert "db_connection_refused" in query


# ---------------------------------------------------------------------------
# _build_prompt()
# ---------------------------------------------------------------------------


class TestBuildPrompt:
    """_build_prompt constructs a safe, evidence-grounded LLM prompt."""

    def _service(self) -> ErrorAnalysisService:
        return ErrorAnalysisService(_mock_db())

    def test_prompt_contains_ai_safety_rule_no_invented_data(self) -> None:
        events  = [_make_orm_event()]
        svc     = self._service()
        prompt  = svc._build_prompt(events, [], [])
        assert "Only use information present" in prompt

    def test_prompt_contains_confidence_gate_rule(self) -> None:
        events = [_make_orm_event()]
        svc    = self._service()
        prompt = svc._build_prompt(events, [], [])
        assert "0.6" in prompt  # the low-confidence threshold is stated explicitly

    def test_prompt_contains_facts_inference_separation_rule(self) -> None:
        events = [_make_orm_event()]
        svc    = self._service()
        prompt = svc._build_prompt(events, [], [])
        assert "facts" in prompt.lower()
        assert "inferences" in prompt.lower()

    def test_prompt_contains_human_approval_safety_rule(self) -> None:
        """Prompt must state that recommended fix is a SUGGESTION, not an action."""
        events = [_make_orm_event()]
        svc    = self._service()
        prompt = svc._build_prompt(events, [], [])
        assert "SUGGESTION" in prompt or "suggestion" in prompt.lower()

    def test_prompt_includes_event_message_in_evidence_block(self) -> None:
        events = [_make_orm_event(message="Connection refused at pool")]
        svc    = self._service()
        prompt = svc._build_prompt(events, [], [])
        assert "Connection refused at pool" in prompt

    def test_prompt_includes_runbook_content(self) -> None:
        events  = [_make_orm_event()]
        chunk   = _make_kb_chunk()
        svc     = self._service()
        prompt  = svc._build_prompt(events, [chunk], [])
        assert "Restart the connection pool" in prompt

    def test_prompt_includes_incident_content(self) -> None:
        events  = [_make_orm_event()]
        chunk   = {
            "source": "incidents/INC-100.md",
            "document_name": "incidents/INC-100.md",
            "content": "Previous DB failure was caused by a missing index.",
        }
        svc    = self._service()
        prompt = svc._build_prompt(events, [], [chunk])
        assert "missing index" in prompt


# ---------------------------------------------------------------------------
# _no_data_response()
# ---------------------------------------------------------------------------


class TestNoDataResponse:
    """_no_data_response() returns a safe, fully-populated fallback."""

    def _service(self) -> ErrorAnalysisService:
        return ErrorAnalysisService(_mock_db())

    def test_confidence_is_zero(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert result.confidence == 0.0

    def test_severity_is_low(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert result.severity == ErrorSeverity.LOW

    def test_low_confidence_warning_is_set(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert result.low_confidence_warning is not None

    def test_events_analysed_is_zero(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert result.events_analysed == 0

    def test_analysis_id_is_propagated(self) -> None:
        svc    = self._service()
        my_id  = str(uuid.uuid4())
        result = svc._no_data_response(my_id)
        assert result.analysis_id == my_id

    def test_likely_root_cause_mentions_no_events(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert "no error events" in result.likely_root_cause.lower() \
            or "insufficient" in result.likely_root_cause.lower()

    def test_possible_causes_list_is_not_empty(self) -> None:
        svc    = self._service()
        result = svc._no_data_response("test-id")
        assert len(result.possible_causes) > 0


# ---------------------------------------------------------------------------
# _build_response() — field mapping and safety
# ---------------------------------------------------------------------------


class TestBuildResponse:
    """_build_response() maps the LLM dict to ErrorAnalysisResponse correctly."""

    def _service(self) -> ErrorAnalysisService:
        return ErrorAnalysisService(_mock_db())

    def test_unknown_severity_defaults_to_medium(self) -> None:
        parsed = json.loads(_make_valid_llm_json())
        parsed["severity"] = "CATASTROPHIC"  # invalid enum value
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed=parsed,
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="gemini",
        )

        assert result.severity == ErrorSeverity.MEDIUM

    def test_confidence_is_clamped_to_1_0_max(self) -> None:
        parsed = json.loads(_make_valid_llm_json())
        parsed["confidence"] = 1.5  # out of range
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed=parsed,
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="gemini",
        )

        assert result.confidence <= 1.0

    def test_confidence_is_clamped_to_0_0_min(self) -> None:
        parsed = json.loads(_make_valid_llm_json())
        parsed["confidence"] = -0.5  # out of range
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed=parsed,
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="gemini",
        )

        assert result.confidence >= 0.0

    def test_summary_is_truncated_to_300_chars(self) -> None:
        parsed         = json.loads(_make_valid_llm_json())
        parsed["summary"] = "x" * 500
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed=parsed,
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="gemini",
        )

        assert len(result.summary) <= 300

    def test_empty_parsed_dict_returns_fallback_response(self) -> None:
        """When parsed == {}, _build_response returns the LLM-unavailable fallback."""
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed={},
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="gemini",
        )

        assert result.confidence == 0.0
        assert "AI analysis unavailable" in result.summary

    def test_provider_name_is_passed_through(self) -> None:
        parsed = json.loads(_make_valid_llm_json())
        events = [_make_orm_event()]
        svc    = self._service()

        result = svc._build_response(
            analysis_id="test-id",
            parsed=parsed,
            events=events,
            runbook_chunks=[],
            incident_chunks=[],
            provider_name="openai",
        )

        assert result.llm_provider == "openai"
