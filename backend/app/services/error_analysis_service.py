"""AI Error Analysis service — Phase 10.

Implements the complete error analysis pipeline:

  1. Collect evidence   — recent ERROR/CRITICAL ObservabilityEvents from PostgreSQL
  2. Retrieve runbooks  — RAG search on devops_knowledge (runbooks category)
  3. Retrieve incidents — RAG search on devops_knowledge (incidents category)
  4. Build LLM prompt   — error evidence + runbook context + incident context
  5. LLM reasoning      — AIOrchestrator.complete() with structured JSON output
  6. Parse + validate   — extract ErrorAnalysisResponse from LLM JSON
  7. Apply AI safety    — confidence gate, facts/inference labelling
  8. Return             — ErrorAnalysisResponse

AI Safety Principles (from master plan):
  - Never invent data — only analyse real events from PostgreSQL
  - Never claim unsupported root causes — every conclusion cites evidence
  - Provide confidence levels — communicate uncertainty explicitly
  - Separate facts from inferences — label each clearly in the response
  - Confidence < 0.6 → "Evidence is insufficient — manual investigation required"
  - No automated action — this service only produces recommendations

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

import json
import logging
import uuid
from datetime import UTC, datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.observability_event import ObservabilityEvent
from app.repositories.observability_event_repository import ObservabilityEventRepository
from app.schemas.error_analysis import (
    AnalyseErrorRequest,
    ErrorAnalysisResponse,
    ErrorSeverity,
    FactsVsInference,
)
from app.services.rag_service import rag_service

logger = logging.getLogger(__name__)

# ── Constants ─────────────────────────────────────────────────────────────────

# Confidence threshold below which we flag the analysis as insufficient
_LOW_CONFIDENCE_THRESHOLD = 0.6

# Maximum tokens for the LLM prompt components
_MAX_EVENTS_IN_PROMPT = 50   # beyond this the prompt gets too long
_MAX_KB_CHUNKS = 5           # top-K from knowledge base (runbooks + incidents)
_LLM_MAX_TOKENS = 1024       # output token limit for the analysis

# Default LLM timeout (seconds)
_LLM_TIMEOUT = 45.0


class ErrorAnalysisService:
    """Orchestrates the full AI error analysis pipeline.

    Usage::

        service = ErrorAnalysisService(db)
        result = await service.analyse(request)
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db
        self._repo = ObservabilityEventRepository(db)

    # ── Public entry point ────────────────────────────────────────────────────

    async def analyse(self, request: AnalyseErrorRequest) -> ErrorAnalysisResponse:
        """Run the full error analysis pipeline and return a structured result.

        Steps:
        1. Collect evidence (events) from PostgreSQL
        2. Build a search query from the error events
        3. Retrieve relevant runbooks from devops_knowledge via RAG
        4. Retrieve relevant historical incidents from devops_knowledge via RAG
        5. Build the LLM prompt
        6. Call the LLM
        7. Parse and validate the structured JSON response
        8. Apply AI safety rules (confidence gate)

        Returns:
            :class:`ErrorAnalysisResponse` with full analysis including
            facts/inference separation and confidence score.
        """
        analysis_id = str(uuid.uuid4())

        # ── Step 1: Collect evidence ──────────────────────────────────────────
        events = await self._collect_events(request)

        if not events:
            return self._no_data_response(analysis_id)

        # ── Step 2: Derive search query from error events ─────────────────────
        search_query = self._derive_search_query(events)

        # ── Step 3 & 4: RAG retrieval from knowledge base ────────────────────
        runbook_chunks = await rag_service.query_knowledge_base(
            query=search_query,
            top_k=_MAX_KB_CHUNKS,
            categories=["runbooks"],
        )
        incident_chunks = await rag_service.query_knowledge_base(
            query=search_query,
            top_k=_MAX_KB_CHUNKS,
            categories=["incidents"],
        )

        # ── Step 5: Build the LLM prompt ──────────────────────────────────────
        prompt = self._build_prompt(events, runbook_chunks, incident_chunks)

        # ── Step 6: Call the LLM ──────────────────────────────────────────────
        llm_response_text, provider_name = await self._call_llm(
            prompt=prompt,
            provider_override=request.provider,
        )

        # ── Step 7: Parse the LLM JSON response ──────────────────────────────
        parsed = self._parse_llm_response(llm_response_text)

        # ── Step 8: Build the final response with AI safety rules ─────────────
        return self._build_response(
            analysis_id=analysis_id,
            parsed=parsed,
            events=events,
            runbook_chunks=runbook_chunks,
            incident_chunks=incident_chunks,
            provider_name=provider_name,
        )

    # ── Private helpers ───────────────────────────────────────────────────────

    async def _collect_events(
        self, request: AnalyseErrorRequest
    ) -> list[ObservabilityEvent]:
        """Collect the relevant ObservabilityEvents based on the request parameters."""

        if request.event_id:
            # Single specific event
            event = await self._repo.get_by_id(uuid.UUID(request.event_id))
            if event is None:
                return []
            # Also pull surrounding context from the same session
            if event.session_id:
                return await self._repo.get_by_session(
                    session_id=event.session_id,
                    minutes=60,
                    limit=_MAX_EVENTS_IN_PROMPT,
                )
            return [event]

        elif request.session_id:
            # All events in a specific session
            return await self._repo.get_by_session(
                session_id=request.session_id,
                minutes=request.lookback_minutes,
                limit=_MAX_EVENTS_IN_PROMPT,
            )

        else:
            # Recent ERROR/CRITICAL events across all sessions
            return await self._repo.get_recent_errors(
                minutes=request.lookback_minutes,
                levels=["ERROR", "CRITICAL"],
                limit=_MAX_EVENTS_IN_PROMPT,
            )

    def _derive_search_query(self, events: list[ObservabilityEvent]) -> str:
        """Derive a natural language search query from the error events.

        Extracts the most informative signal from the events:
        - Unique event types
        - Error messages (first few, deduped)
        - Screen context

        This query is used to find relevant runbooks and past incidents.
        """
        error_events = [e for e in events if e.level in ("ERROR", "CRITICAL")]
        if not error_events:
            error_events = events

        # Collect unique event types and messages
        seen_types: set[str] = set()
        seen_messages: list[str] = []
        screens: set[str] = set()

        for evt in error_events[:10]:  # first 10 error events
            seen_types.add(evt.event_type)
            # Deduplicate similar messages by prefix
            msg_prefix = evt.message[:80]
            if not any(msg_prefix in existing for existing in seen_messages):
                seen_messages.append(evt.message[:200])
            if evt.screen:
                screens.add(evt.screen)

        parts = []
        if seen_types:
            parts.append(f"Error types: {', '.join(sorted(seen_types))}")
        if seen_messages:
            parts.append(f"Error messages: {'; '.join(seen_messages[:3])}")
        if screens:
            parts.append(f"Screens: {', '.join(sorted(screens))}")

        return " ".join(parts) if parts else "application error"

    def _format_events_for_prompt(self, events: list[ObservabilityEvent]) -> str:
        """Format events as a readable evidence block for the LLM prompt."""
        if not events:
            return "No events available."

        # Limit to the most recent and relevant events
        error_events = [e for e in events if e.level in ("ERROR", "CRITICAL")]
        context_events = [e for e in events if e.level not in ("ERROR", "CRITICAL")]

        lines = []

        if error_events:
            lines.append("=== ERROR / CRITICAL Events ===")
            for evt in error_events[:20]:
                ts = datetime.fromtimestamp(evt.timestamp_ms / 1000, tz=UTC).strftime(
                    "%Y-%m-%d %H:%M:%S"
                )
                meta = ""
                try:
                    m = json.loads(evt.metadata_json or "{}")
                    if m:
                        meta = f" | {json.dumps(m, separators=(',', ':'))}"
                except Exception:
                    pass
                screen = f" [{evt.screen}]" if evt.screen else ""
                lines.append(
                    f"[{ts}] {evt.level} {evt.event_type}{screen}: {evt.message}{meta}"
                )

        if context_events:
            lines.append("")
            lines.append("=== Context Events (WARN / INFO) ===")
            for evt in context_events[:10]:
                ts = datetime.fromtimestamp(evt.timestamp_ms / 1000, tz=UTC).strftime(
                    "%H:%M:%S"
                )
                screen = f" [{evt.screen}]" if evt.screen else ""
                lines.append(f"[{ts}] {evt.level} {evt.event_type}{screen}: {evt.message}")

        return "\n".join(lines)

    def _format_kb_chunks(self, chunks: list[dict], section_title: str) -> str:
        """Format retrieved knowledge base chunks as a context section."""
        if not chunks:
            return f"No {section_title.lower()} found for this error pattern."

        lines = [f"=== {section_title} ==="]
        for i, chunk in enumerate(chunks, 1):
            source = chunk.get("source", "unknown")
            lines.append(f"--- [{i}] Source: {source} ---")
            lines.append(chunk.get("content", "").strip())
            lines.append("")
        return "\n".join(lines)

    def _build_prompt(
        self,
        events: list[ObservabilityEvent],
        runbook_chunks: list[dict],
        incident_chunks: list[dict],
    ) -> str:
        """Build the complete LLM prompt for error analysis.

        Prompt design principles:
        - System role: establish the AI's expertise and safety constraints
        - Evidence section: raw facts the LLM must ground its analysis in
        - Knowledge section: retrieved runbooks and incidents for context
        - Output format: strict JSON schema with all required fields
        - Explicit constraint: low confidence → say "Evidence insufficient"
        """
        events_block    = self._format_events_for_prompt(events)
        runbooks_block  = self._format_kb_chunks(runbook_chunks, "Relevant Runbooks")
        incidents_block = self._format_kb_chunks(incident_chunks, "Historical Incidents")

        return f"""You are an expert Site Reliability Engineer and AI-powered DevOps assistant.
Analyse the following application error events and provide a structured root cause analysis.

IMPORTANT RULES:
1. Only use information present in the provided evidence and context — never invent facts.
2. Separate facts (directly observable in the evidence) from inferences (your reasoning).
3. Provide a confidence score between 0.0 and 1.0. Be honest about uncertainty.
4. If confidence is below 0.6, set likely_root_cause to exactly: "Evidence is insufficient — manual investigation required."
5. Recommended fix is a SUGGESTION only — never imply automated action will be taken.
6. Never expose credentials, tokens, or PII even if present in the evidence.

---
EVIDENCE — Application Error Events
{events_block}

---
CONTEXT FROM KNOWLEDGE BASE
{runbooks_block}

---
{incidents_block}

---
Respond with ONLY valid JSON matching this exact schema (no markdown, no explanation):

{{
  "severity": "CRITICAL|HIGH|MEDIUM|LOW",
  "summary": "one-line description of what went wrong",
  "evidence": ["specific log line or observation 1", "specific log line or observation 2"],
  "possible_causes": ["most likely cause", "second possible cause", "third possible cause"],
  "likely_root_cause": "most probable cause with brief reasoning referencing evidence",
  "confidence": 0.0,
  "recommended_fix": "step-by-step actionable fix recommendation",
  "related_documentation": ["runbook or incident name 1", "runbook or incident name 2"],
  "facts": ["directly observable fact 1", "directly observable fact 2"],
  "inferences": ["reasoning inference 1", "reasoning inference 2"]
}}"""

    async def _call_llm(
        self,
        prompt: str,
        provider_override: str | None,
    ) -> tuple[str, str]:
        """Call the LLM with the analysis prompt.

        Returns:
            Tuple of (response_text, provider_name_used).
        """
        import asyncio

        from app.services.ai_orchestrator import AIOrchestrator, LLMProvider
        from app.config.settings import get_settings

        settings = get_settings()

        # Resolve provider
        provider_str = provider_override or settings.DEFAULT_LLM_PROVIDER or "gemini"
        try:
            provider = LLMProvider(provider_str.lower())
        except ValueError:
            provider = LLMProvider.gemini

        try:
            orchestrator = AIOrchestrator(db=self._db)
            completion = await asyncio.wait_for(
                orchestrator.complete(
                    prompt=prompt,
                    provider=provider,
                    max_tokens=_LLM_MAX_TOKENS,
                    user_id="system",  # analysis is a system call, not user-scoped
                ),
                timeout=_LLM_TIMEOUT,
            )
            return completion.text, provider.value

        except Exception as exc:
            logger.warning("ErrorAnalysisService: LLM call failed — %s", exc)
            return "", provider.value

    def _parse_llm_response(self, raw_text: str) -> dict:
        """Extract the JSON object from the LLM response.

        The LLM is instructed to return pure JSON but may sometimes add
        markdown code fences. This parser handles both cases.
        """
        if not raw_text.strip():
            return {}

        text = raw_text.strip()

        # Strip markdown code fences if present
        if text.startswith("```"):
            lines = text.split("\n")
            text = "\n".join(
                line for line in lines
                if not line.strip().startswith("```")
            )

        # Find the first { and last } to extract the JSON object
        start = text.find("{")
        end   = text.rfind("}") + 1

        if start == -1 or end == 0:
            logger.warning("ErrorAnalysisService: no JSON object found in LLM response")
            return {}

        try:
            return json.loads(text[start:end])
        except json.JSONDecodeError as exc:
            logger.warning("ErrorAnalysisService: JSON parse error — %s", exc)
            return {}

    def _build_response(
        self,
        analysis_id: str,
        parsed: dict,
        events: list[ObservabilityEvent],
        runbook_chunks: list[dict],
        incident_chunks: list[dict],
        provider_name: str,
    ) -> ErrorAnalysisResponse:
        """Build the final validated ErrorAnalysisResponse from parsed LLM output."""

        if not parsed:
            # LLM call failed or returned unparseable output — return a safe fallback
            return ErrorAnalysisResponse(
                analysis_id=analysis_id,
                severity=ErrorSeverity.MEDIUM,
                summary="AI analysis unavailable — LLM did not return a valid response.",
                evidence=[f"[{e.level}] {e.event_type}: {e.message}" for e in events[:5]],
                possible_causes=["LLM analysis failed — check provider availability"],
                likely_root_cause="Evidence is insufficient — manual investigation required.",
                confidence=0.0,
                recommended_fix=(
                    "Review the raw events above manually. "
                    "Check runbook: runbooks/service-restart.md"
                ),
                related_documentation=[],
                facts_vs_inference=FactsVsInference(
                    facts=[f"[{e.level}] {e.event_type}: {e.message}" for e in events[:3]],
                    inferences=["LLM analysis was not available for this incident"],
                ),
                low_confidence_warning=(
                    "AI analysis could not be completed. Manual investigation required."
                ),
                events_analysed=len(events),
                knowledge_chunks_retrieved=len(runbook_chunks) + len(incident_chunks),
                llm_provider=provider_name,
            )

        # Extract confidence and apply the safety gate
        confidence = float(parsed.get("confidence", 0.0))
        confidence = max(0.0, min(1.0, confidence))

        # Parse severity — default to MEDIUM if invalid
        severity_raw = str(parsed.get("severity", "MEDIUM")).upper()
        try:
            severity = ErrorSeverity(severity_raw)
        except ValueError:
            severity = ErrorSeverity.MEDIUM

        # Apply AI Safety Rule: confidence < 0.6 → override likely_root_cause
        likely_root_cause = str(parsed.get("likely_root_cause", ""))
        low_confidence_warning = None

        if confidence < _LOW_CONFIDENCE_THRESHOLD:
            likely_root_cause = (
                "Evidence is insufficient — manual investigation required."
            )
            low_confidence_warning = (
                f"Confidence score {confidence:.2f} is below the 0.6 threshold. "
                "The AI does not have enough evidence to reliably identify the root cause. "
                "Please investigate manually using the evidence and documentation listed above."
            )

        # Build related documentation from both RAG sources
        related_docs_from_llm = [
            str(d) for d in parsed.get("related_documentation", []) if d
        ]
        # Also include source file names from retrieved chunks
        kb_sources = list({
            chunk["document_name"]
            for chunk in (runbook_chunks + incident_chunks)
            if chunk.get("document_name")
        })
        # Merge without duplicates
        related_docs = list(dict.fromkeys(related_docs_from_llm + kb_sources))

        return ErrorAnalysisResponse(
            analysis_id=analysis_id,
            severity=severity,
            summary=str(parsed.get("summary", "No summary available"))[:300],
            evidence=[str(e) for e in parsed.get("evidence", []) if e],
            possible_causes=[str(c) for c in parsed.get("possible_causes", []) if c],
            likely_root_cause=likely_root_cause,
            confidence=confidence,
            recommended_fix=str(parsed.get("recommended_fix", "")),
            related_documentation=related_docs,
            facts_vs_inference=FactsVsInference(
                facts=[str(f) for f in parsed.get("facts", []) if f],
                inferences=[str(i) for i in parsed.get("inferences", []) if i],
            ),
            low_confidence_warning=low_confidence_warning,
            events_analysed=len(events),
            knowledge_chunks_retrieved=len(runbook_chunks) + len(incident_chunks),
            llm_provider=provider_name,
        )

    def _no_data_response(self, analysis_id: str) -> ErrorAnalysisResponse:
        """Return a safe response when no events are available to analyse."""
        return ErrorAnalysisResponse(
            analysis_id=analysis_id,
            severity=ErrorSeverity.LOW,
            summary="No error events found in the specified time window.",
            evidence=[],
            possible_causes=["No events were captured in the requested time window"],
            likely_root_cause=(
                "Evidence is insufficient — no error events found. "
                "The app may be healthy or events have not been uploaded yet."
            ),
            confidence=0.0,
            recommended_fix=(
                "Check that the Android app is running and that "
                "ObservabilityUploadWorker has had a chance to upload events "
                "(runs every 15 minutes when CONNECTED)."
            ),
            related_documentation=[],
            facts_vs_inference=FactsVsInference(
                facts=["No ERROR or CRITICAL events found in the requested time window"],
                inferences=["The application may be functioning normally"],
            ),
            low_confidence_warning=(
                "No evidence available. Cannot perform AI analysis. "
                "Expand the lookback_minutes window or wait for events to upload."
            ),
            events_analysed=0,
            knowledge_chunks_retrieved=0,
            llm_provider="",
        )
