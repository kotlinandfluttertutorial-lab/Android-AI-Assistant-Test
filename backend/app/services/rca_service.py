"""Root Cause Analysis Service — Phase 12.

Implements the complete RCA pipeline for a specific incident:

  1. Load incident        — fetch the Incident row and its Phase 10 analysis
  2. Collect evidence     — ObservabilityEvents + server ErrorLogs in time window
  3. Build timeline       — chronologically sort all evidence across sources
  4. RAG retrieval        — runbooks + incidents from devops_knowledge
  5. Build LLM prompt     — chain-of-thought prompt with all evidence
  6. LLM reasoning        — AIOrchestrator.complete() → structured JSON
  7. Parse response       — extract ranked candidates + investigation steps
  8. Apply safety gate    — overall_confidence < 0.6 → manual investigation warning
  9. Persist result       — attach RCA fields to the Incident row

Key difference from Phase 10 ErrorAnalysisService:
  Phase 10: time-window scoped, one likely_root_cause, flat evidence list
  Phase 12: incident-scoped, ranked RootCauseCandidate list (each with own
            confidence + evidence), correlated timeline, chain-of-thought exposed

Phase 12 — Root Cause Analysis
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from sqlalchemy.ext.asyncio import AsyncSession

from app.models.incident import Incident
from app.repositories.error_log_repository import ErrorLogRepository
from app.repositories.incident_repository import IncidentRepository
from app.repositories.observability_event_repository import ObservabilityEventRepository
from app.schemas.rca import (
    RcaAnalysisResponse,
    RcaRequest,
    RootCauseCandidate,
    TimelineEvent,
)
from app.services.rag_service import rag_service

logger = logging.getLogger(__name__)

# ── Constants ─────────────────────────────────────────────────────────────────

_LOW_CONFIDENCE_THRESHOLD = 0.6
_MAX_EVENTS_IN_PROMPT     = 40   # observability events
_MAX_ERROR_LOGS_IN_PROMPT = 20   # server error logs
_MAX_KB_CHUNKS            = 5    # per category (runbooks + incidents)
_LLM_MAX_TOKENS           = 1536 # larger than Phase 10 — chain-of-thought needs space
_LLM_TIMEOUT              = 60.0 # seconds


class RcaService:
    """Orchestrates the full Root Cause Analysis pipeline for one incident.

    Usage::

        service = RcaService(db)
        result  = await service.run(incident_id, request)
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db           = db
        self._inc_repo     = IncidentRepository(db)
        self._obs_repo     = ObservabilityEventRepository(db)
        self._errlog_repo  = ErrorLogRepository(db)

    # ── Public entry point ────────────────────────────────────────────────────

    async def run(
        self,
        incident_id: uuid.UUID,
        request: RcaRequest,
    ) -> RcaAnalysisResponse:
        """Run the full RCA pipeline and persist results on the Incident row.

        If a previous RCA result exists and ``request.force_rerun`` is False,
        returns the cached result without re-running the pipeline.

        Returns:
            :class:`RcaAnalysisResponse` with ranked candidates, timeline,
            chain of thought, and investigation steps.
        """
        rca_id = str(uuid.uuid4())

        # ── Step 1: Load incident ─────────────────────────────────────────────
        incident = await self._inc_repo.get_by_id(incident_id)
        if incident is None:
            return self._not_found_response(rca_id, str(incident_id))

        # Return cached result if available and not forced
        if incident.rca_analysis_id and not request.force_rerun:
            return self._cached_response(rca_id, incident)

        # ── Step 2: Collect evidence ──────────────────────────────────────────
        centre = incident.detected_at
        if centre.tzinfo is None:
            centre = centre.replace(tzinfo=UTC)

        obs_events = await self._obs_repo.get_recent_errors(
            minutes=request.evidence_window_minutes,
            levels=["ERROR", "CRITICAL", "WARN"],
            limit=_MAX_EVENTS_IN_PROMPT,
        )
        error_logs = await self._errlog_repo.get_errors_around_time(
            centre=centre,
            window_minutes=request.evidence_window_minutes,
            limit=_MAX_ERROR_LOGS_IN_PROMPT,
        )

        # ── Step 3: Build timeline ────────────────────────────────────────────
        timeline = self._build_timeline(obs_events, error_logs)

        # ── Step 4: RAG retrieval ─────────────────────────────────────────────
        search_query = self._derive_search_query(incident, obs_events, error_logs)

        runbook_chunks = await rag_service.query_knowledge_base(
            query=search_query, top_k=_MAX_KB_CHUNKS, categories=["runbooks"],
        )
        incident_chunks = await rag_service.query_knowledge_base(
            query=search_query, top_k=_MAX_KB_CHUNKS, categories=["incidents"],
        )

        # ── Step 5: Build chain-of-thought LLM prompt ────────────────────────
        prompt = self._build_prompt(
            incident, timeline, runbook_chunks, incident_chunks
        )

        # ── Step 6: LLM reasoning ─────────────────────────────────────────────
        llm_text, provider_name = await self._call_llm(prompt, request.provider)

        # ── Step 7: Parse response ────────────────────────────────────────────
        parsed = self._parse_llm_response(llm_text)

        # ── Step 8: Build validated response ─────────────────────────────────
        response = self._build_response(
            rca_id=rca_id,
            incident=incident,
            parsed=parsed,
            timeline=timeline,
            runbook_chunks=runbook_chunks,
            incident_chunks=incident_chunks,
            obs_count=len(obs_events),
            errlog_count=len(error_logs),
            provider_name=provider_name,
        )

        # ── Step 9: Persist result on Incident row ────────────────────────────
        await self._persist(incident_id, response)

        return response

    # ── Evidence helpers ──────────────────────────────────────────────────────

    def _build_timeline(self, obs_events: list, error_logs: list) -> list[TimelineEvent]:
        """Merge and chronologically sort all evidence into a unified timeline."""
        events: list[TimelineEvent] = []

        for evt in obs_events:
            ts = datetime.fromtimestamp(evt.timestamp_ms / 1000, tz=UTC).isoformat()
            events.append(TimelineEvent(
                timestamp=ts,
                source="observability_event",
                level=evt.level,
                event_type=evt.event_type,
                message=evt.message,
                screen=evt.screen,
            ))

        for log in error_logs:
            ts = log.created_at
            if ts.tzinfo is None:
                ts = ts.replace(tzinfo=UTC)
            events.append(TimelineEvent(
                timestamp=ts.isoformat(),
                source="error_log",
                level="ERROR",
                event_type=log.error_type,
                message=f"[{log.endpoint}] {log.message[:300]}",
                screen=None,
            ))

        events.sort(key=lambda e: e.timestamp)
        return events

    def _derive_search_query(
        self,
        incident: Incident,
        obs_events: list,
        error_logs: list,
    ) -> str:
        """Build a concise search query for the RAG knowledge base."""
        parts: list[str] = [incident.title]

        types: set[str] = set()
        for evt in obs_events[:8]:
            types.add(evt.event_type)
        for log in error_logs[:5]:
            types.add(log.error_type)

        if types:
            parts.append(f"error types: {', '.join(sorted(types))}")

        msgs: list[str] = []
        for evt in obs_events[:3]:
            msgs.append(evt.message[:120])
        if msgs:
            parts.append("; ".join(msgs))

        return " ".join(parts)

    def _format_timeline_for_prompt(self, timeline: list[TimelineEvent]) -> str:
        """Render the timeline as a readable block for the LLM prompt."""
        if not timeline:
            return "No events available."

        lines = []
        for evt in timeline[:50]:  # cap at 50 to avoid token overflow
            src_tag = {"observability_event": "APP", "error_log": "SRV"}.get(
                evt.source, evt.source.upper()[:3]
            )
            screen = f" [{evt.screen}]" if evt.screen else ""
            lines.append(
                f"[{evt.timestamp[11:19]}] {src_tag} {evt.level:8s} "
                f"{evt.event_type}{screen}: {evt.message}"
            )
        return "\n".join(lines)

    def _format_kb_chunks(self, chunks: list[dict], section: str) -> str:
        if not chunks:
            return f"No {section.lower()} found."
        lines = [f"=== {section} ==="]
        for i, chunk in enumerate(chunks, 1):
            lines.append(f"--- [{i}] {chunk.get('source', '')} ---")
            lines.append(chunk.get("content", "").strip())
            lines.append("")
        return "\n".join(lines)

    # ── Prompt ────────────────────────────────────────────────────────────────

    def _build_prompt(
        self,
        incident: Incident,
        timeline: list[TimelineEvent],
        runbook_chunks: list[dict],
        incident_chunks: list[dict],
    ) -> str:
        """Build the chain-of-thought RCA prompt.

        Chain-of-thought prompting asks the LLM to reason step-by-step before
        giving an answer. The key instruction is "Think step by step" plus
        explicit phases: Analyse → Hypothesise → Rank → Conclude.
        This produces better ranked candidates than a direct "give me the answer" prompt.
        """
        timeline_block   = self._format_timeline_for_prompt(timeline)
        runbooks_block   = self._format_kb_chunks(runbook_chunks,  "Relevant Runbooks")
        incidents_block  = self._format_kb_chunks(incident_chunks, "Historical Incidents")

        phase10_context = ""
        if incident.ai_summary:
            phase10_context = (
                f"\nPhase 10 Error Analysis (preliminary):\n"
                f"  Summary:    {incident.ai_summary}\n"
                f"  Confidence: {incident.ai_confidence or 'N/A'}\n"
                f"  Fix:        {incident.ai_recommended_fix or 'N/A'}\n"
            )

        return f"""You are an expert Site Reliability Engineer performing a Root Cause Analysis.
Use chain-of-thought reasoning to identify the most likely root causes of this incident.

INCIDENT
  ID:          {incident.id}
  Title:       {incident.title}
  Severity:    {incident.severity}
  Detected at: {incident.detected_at.isoformat() if incident.detected_at else 'unknown'}
  Triggered by: {incident.triggered_by}  (value: {incident.metric_value}, threshold: {incident.threshold_value})
{phase10_context}
---
CORRELATED EVIDENCE TIMELINE (oldest → newest)
{timeline_block}

---
KNOWLEDGE BASE CONTEXT
{runbooks_block}

---
{incidents_block}

---
RULES:
1. Only use information from the evidence above — never invent facts.
2. Think step by step: first analyse the timeline, then hypothesise causes, then rank them.
3. Every candidate MUST cite specific evidence from the timeline.
4. Provide a confidence score per candidate (0.0–1.0). Be honest about uncertainty.
5. If overall confidence < 0.6, say exactly: "Evidence is insufficient — manual investigation required."
6. Investigation steps are SUGGESTIONS only — no automated action will be taken.
7. Never expose credentials, tokens, or PII from the evidence.

Respond with ONLY valid JSON matching this exact schema (no markdown, no explanation):

{{
  "summary": "one-line RCA conclusion",
  "chain_of_thought": "step-by-step reasoning: timeline analysis → hypotheses → ranking",
  "root_cause_candidates": [
    {{
      "rank": 1,
      "cause": "most likely root cause",
      "confidence": 0.0,
      "supporting_evidence": ["evidence item 1", "evidence item 2"],
      "reasoning": "why this evidence points to this cause"
    }},
    {{
      "rank": 2,
      "cause": "second possible root cause",
      "confidence": 0.0,
      "supporting_evidence": ["evidence item"],
      "reasoning": "reasoning"
    }}
  ],
  "investigation_steps": [
    "step 1: specific action to verify the top candidate",
    "step 2: next action if step 1 inconclusive"
  ],
  "related_documentation": ["runbook or incident name 1", "runbook or incident name 2"]
}}"""

    # ── LLM call ──────────────────────────────────────────────────────────────

    async def _call_llm(
        self, prompt: str, provider_override: str | None
    ) -> tuple[str, str]:
        import asyncio

        from app.config.settings import get_settings
        from app.services.ai_orchestrator import AIOrchestrator, LLMProvider

        settings = get_settings()
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
                    user_id="system",
                ),
                timeout=_LLM_TIMEOUT,
            )
            return completion.text, provider.value
        except Exception as exc:
            logger.warning("RcaService: LLM call failed — %s", exc)
            return "", provider.value

    # ── Parse ─────────────────────────────────────────────────────────────────

    def _parse_llm_response(self, raw: str) -> dict:
        if not raw.strip():
            return {}

        text = raw.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            text = "\n".join(l for l in lines if not l.strip().startswith("```"))

        start = text.find("{")
        end   = text.rfind("}") + 1
        if start == -1 or end == 0:
            logger.warning("RcaService: no JSON in LLM response")
            return {}

        try:
            return json.loads(text[start:end])
        except json.JSONDecodeError as exc:
            logger.warning("RcaService: JSON parse error — %s", exc)
            return {}

    # ── Build response ────────────────────────────────────────────────────────

    def _build_response(
        self,
        rca_id: str,
        incident: Incident,
        parsed: dict,
        timeline: list[TimelineEvent],
        runbook_chunks: list[dict],
        incident_chunks: list[dict],
        obs_count: int,
        errlog_count: int,
        provider_name: str,
    ) -> RcaAnalysisResponse:
        if not parsed:
            return self._fallback_response(rca_id, incident, timeline, obs_count, errlog_count, provider_name)

        # Build candidates
        candidates: list[RootCauseCandidate] = []
        for c in parsed.get("root_cause_candidates", []):
            try:
                candidates.append(RootCauseCandidate(
                    rank=int(c.get("rank", len(candidates) + 1)),
                    cause=str(c.get("cause", "")),
                    confidence=float(c.get("confidence", 0.0)),
                    supporting_evidence=[str(e) for e in c.get("supporting_evidence", []) if e],
                    reasoning=str(c.get("reasoning", "")),
                ))
            except Exception:
                continue

        # Sort by confidence descending, re-number ranks
        candidates.sort(key=lambda c: c.confidence, reverse=True)
        for i, c in enumerate(candidates):
            c.rank = i + 1

        # Overall confidence = top candidate's confidence (or 0 if no candidates)
        overall_confidence = candidates[0].confidence if candidates else 0.0

        # AI safety gate
        low_confidence_warning: str | None = None
        if overall_confidence < _LOW_CONFIDENCE_THRESHOLD:
            if candidates:
                candidates[0] = RootCauseCandidate(
                    rank=1,
                    cause="Evidence is insufficient — manual investigation required.",
                    confidence=overall_confidence,
                    supporting_evidence=candidates[0].supporting_evidence,
                    reasoning=candidates[0].reasoning,
                )
            low_confidence_warning = (
                f"Overall RCA confidence {overall_confidence:.2f} is below 0.6. "
                "The evidence is insufficient to reliably identify the root cause. "
                "Manual investigation is required."
            )

        # Related documentation — from LLM + RAG source names
        llm_docs = [str(d) for d in parsed.get("related_documentation", []) if d]
        kb_docs  = list({c["document_name"] for c in (runbook_chunks + incident_chunks) if c.get("document_name")})
        related_docs = list(dict.fromkeys(llm_docs + kb_docs))

        return RcaAnalysisResponse(
            rca_id=rca_id,
            incident_id=str(incident.id),
            summary=str(parsed.get("summary", "No summary"))[:400],
            root_cause_candidates=candidates,
            overall_confidence=overall_confidence,
            timeline=timeline,
            chain_of_thought=str(parsed.get("chain_of_thought", "")),
            investigation_steps=[str(s) for s in parsed.get("investigation_steps", []) if s],
            related_documentation=related_docs,
            observability_events_count=obs_count,
            error_logs_count=errlog_count,
            knowledge_chunks_count=len(runbook_chunks) + len(incident_chunks),
            llm_provider=provider_name,
            low_confidence_warning=low_confidence_warning,
        )

    # ── Persist ───────────────────────────────────────────────────────────────

    async def _persist(self, incident_id: uuid.UUID, response: RcaAnalysisResponse) -> None:
        try:
            candidates_json = json.dumps(
                [c.model_dump() for c in response.root_cause_candidates]
            )
            steps_json = json.dumps(response.investigation_steps)

            await self._inc_repo.attach_rca(
                incident_id=incident_id,
                rca_analysis_id=response.rca_id,
                rca_summary=response.summary,
                rca_confidence=response.overall_confidence,
                rca_candidates_json=candidates_json,
                rca_investigation_steps_json=steps_json,
            )
            await self._db.commit()
        except Exception as exc:
            logger.warning("RcaService: failed to persist RCA to incident row — %s", exc)

    # ── Safe fallback responses ───────────────────────────────────────────────

    def _fallback_response(
        self,
        rca_id: str,
        incident: Incident,
        timeline: list[TimelineEvent],
        obs_count: int,
        errlog_count: int,
        provider_name: str,
    ) -> RcaAnalysisResponse:
        return RcaAnalysisResponse(
            rca_id=rca_id,
            incident_id=str(incident.id),
            summary="RCA could not be completed — LLM did not return a valid response.",
            root_cause_candidates=[
                RootCauseCandidate(
                    rank=1,
                    cause="Evidence is insufficient — manual investigation required.",
                    confidence=0.0,
                    supporting_evidence=[e.message for e in timeline[:3]],
                    reasoning="LLM analysis was unavailable for this incident.",
                )
            ],
            overall_confidence=0.0,
            timeline=timeline,
            chain_of_thought="LLM analysis failed — no chain of thought available.",
            investigation_steps=[
                "Review the timeline events above manually.",
                "Check runbook: runbooks/service-restart.md",
                "Check historical incidents for similar patterns.",
            ],
            related_documentation=[],
            observability_events_count=obs_count,
            error_logs_count=errlog_count,
            knowledge_chunks_count=0,
            llm_provider=provider_name,
            low_confidence_warning=(
                "RCA analysis could not be completed. Manual investigation required."
            ),
        )

    def _not_found_response(self, rca_id: str, incident_id: str) -> RcaAnalysisResponse:
        return RcaAnalysisResponse(
            rca_id=rca_id,
            incident_id=incident_id,
            summary=f"Incident {incident_id} not found.",
            root_cause_candidates=[],
            overall_confidence=0.0,
            timeline=[],
            chain_of_thought="",
            investigation_steps=[],
            related_documentation=[],
            low_confidence_warning="Incident not found — cannot perform RCA.",
        )

    def _cached_response(self, rca_id: str, incident: Incident) -> RcaAnalysisResponse:
        """Reconstruct an RcaAnalysisResponse from the cached fields on the Incident row."""
        candidates: list[RootCauseCandidate] = []
        if incident.rca_candidates_json:
            try:
                for c in json.loads(incident.rca_candidates_json):
                    candidates.append(RootCauseCandidate(**c))
            except Exception:
                pass

        steps: list[str] = []
        if incident.rca_investigation_steps_json:
            try:
                steps = json.loads(incident.rca_investigation_steps_json)
            except Exception:
                pass

        return RcaAnalysisResponse(
            rca_id=incident.rca_analysis_id or rca_id,
            incident_id=str(incident.id),
            summary=incident.rca_summary or "Cached RCA result.",
            root_cause_candidates=candidates,
            overall_confidence=incident.rca_confidence or 0.0,
            timeline=[],   # not stored on the row — re-run with force_rerun=True to get it
            chain_of_thought="(Cached result — re-run with force_rerun=True to get full chain of thought)",
            investigation_steps=steps,
            related_documentation=[],
            observability_events_count=0,
            error_logs_count=0,
            knowledge_chunks_count=0,
            llm_provider="cached",
        )
