"""DevOps MCP tool connectors — Phase 13 AI DevOps Assistant.

Seven tool connectors that give the DevOps assistant access to real operational
data. Each implements ``MCPToolConnector`` from the existing MCP broker framework
so they can be discovered and invoked through the same pattern as the 15
productivity connectors already registered.

Tools:
  search_logs           — find observability events by text/level/type
  search_incidents      — list incidents by severity/status
  search_runbooks       — semantic search the devops_knowledge knowledge base
  analyse_errors        — trigger Phase 10 AI error analysis
  get_rca               — trigger or fetch Phase 12 root cause analysis
  get_incident_summary  — full detail of a single incident (Phase 10 + Phase 12)
  create_incident       — manually create an incident record

Design principles:
  - Each tool is read-only except ``create_incident`` (which sets requires_confirmation=True)
  - Tools return structured dicts so the LLM can format the final answer
  - Errors are gracefully returned as MCPToolResult(success=False, error=...)
    rather than raising exceptions — the LLM handles the "no data" case in prose

Phase 13 — AI DevOps Assistant
"""

from __future__ import annotations

import json
import logging
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)


# ── Base helper ───────────────────────────────────────────────────────────────

def _ok(data: Any) -> MCPToolResult:
    return MCPToolResult(tool_name="", success=True, data=data, result_status="success")

def _err(tool_name: str, message: str) -> MCPToolResult:
    return MCPToolResult(tool_name=tool_name, success=False, error=message, result_status="error")


# ── Tool 1: search_logs ───────────────────────────────────────────────────────

class SearchLogsConnector(MCPToolConnector):
    """Search observability events by text, level, and event type.

    Maps to: ObservabilityEventRepository.search_logs()
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "search_logs"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="search_logs",
            description=(
                "Search Android observability event logs by text, severity level, "
                "and event type. Returns the most recent matching events. "
                "Use this to answer 'What errors happened recently?' or "
                "'Why did the API fail?'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "query":      {"type": "string",  "description": "Substring to search in event messages (case-insensitive)."},
                    "level":      {"type": "string",  "description": "Severity: DEBUG|INFO|WARN|ERROR|CRITICAL. Omit for all levels."},
                    "event_type": {"type": "string",  "description": "Exact event type e.g. 'http_error', 'network_timeout'. Omit for all types."},
                    "minutes":    {"type": "integer", "description": "Look-back window in minutes (default 60, max 1440)."},
                    "limit":      {"type": "integer", "description": "Max events to return (default 20)."},
                },
                "required": [],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        from app.repositories.observability_event_repository import ObservabilityEventRepository

        repo = ObservabilityEventRepository(self._db)
        try:
            events = await repo.search_logs(
                query      = params.get("query"),
                level      = params.get("level"),
                event_type = params.get("event_type"),
                minutes    = min(int(params.get("minutes", 60)), 1440),
                limit      = min(int(params.get("limit", 20)), 100),
            )
            return _ok({
                "count": len(events),
                "events": [
                    {
                        "timestamp":  e.received_at.isoformat() if e.received_at else "",
                        "level":      e.level,
                        "event_type": e.event_type,
                        "message":    e.message,
                        "screen":     e.screen,
                        "session_id": e.session_id,
                    }
                    for e in events
                ],
            })
        except Exception as exc:
            logger.warning("search_logs: %s", exc)
            return _err("search_logs", str(exc))


# ── Tool 2: search_incidents ──────────────────────────────────────────────────

class SearchIncidentsConnector(MCPToolConnector):
    """List recent incidents filtered by severity and/or status."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "search_incidents"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="search_incidents",
            description=(
                "List recent production incidents. Filter by severity (CRITICAL/HIGH/MEDIUM/LOW) "
                "or status (OPEN/INVESTIGATING/RESOLVED/DISMISSED). "
                "Use this to answer 'Show me open incidents' or 'What critical issues are there?'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "severity": {"type": "string", "description": "CRITICAL|HIGH|MEDIUM|LOW. Omit for all."},
                    "status":   {"type": "string", "description": "OPEN|INVESTIGATING|RESOLVED|DISMISSED. Omit for all."},
                    "limit":    {"type": "integer", "description": "Max incidents to return (default 10)."},
                },
                "required": [],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        from app.repositories.incident_repository import IncidentRepository

        repo = IncidentRepository(self._db)
        try:
            incidents = await repo.list_recent(
                limit    = min(int(params.get("limit", 10)), 50),
                status   = params.get("status"),
                severity = params.get("severity"),
            )
            open_count = await repo.get_open_count()
            return _ok({
                "open_count": open_count,
                "count":      len(incidents),
                "incidents": [
                    {
                        "id":            str(i.id),
                        "title":         i.title,
                        "severity":      i.severity,
                        "status":        i.status,
                        "triggered_by":  i.triggered_by,
                        "detected_at":   i.detected_at.isoformat() if i.detected_at else "",
                        "ai_summary":    i.ai_summary,
                        "ai_confidence": i.ai_confidence,
                        "rca_summary":   i.rca_summary,
                        "rca_confidence":i.rca_confidence,
                    }
                    for i in incidents
                ],
            })
        except Exception as exc:
            logger.warning("search_incidents: %s", exc)
            return _err("search_incidents", str(exc))


# ── Tool 3: search_runbooks ───────────────────────────────────────────────────

class SearchRunbooksConnector(MCPToolConnector):
    """Semantic search of the DevOps knowledge base (runbooks + incidents + architecture)."""

    @property
    def tool_name(self) -> str:
        return "search_runbooks"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="search_runbooks",
            description=(
                "Search the DevOps knowledge base using semantic similarity. "
                "Finds relevant runbooks, historical incident reports, and architecture "
                "documentation. Use this to answer 'How do I restart the service?' or "
                "'Have we seen this error before?'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "query":    {"type": "string",  "description": "Natural language search query."},
                    "category": {"type": "string",  "description": "runbooks|incidents|architecture|deployment. Omit to search all."},
                    "top_k":    {"type": "integer", "description": "Number of results to return (default 5)."},
                },
                "required": ["query"],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        from app.services.rag_service import rag_service

        query    = params.get("query", "")
        category = params.get("category")
        top_k    = min(int(params.get("top_k", 5)), 10)

        if not query.strip():
            return _err("search_runbooks", "query parameter is required")

        try:
            categories = [category] if category else None
            chunks = await rag_service.query_knowledge_base(
                query=query, top_k=top_k, categories=categories
            )
            return _ok({
                "count": len(chunks),
                "results": [
                    {
                        "source":        c.get("source", ""),
                        "document_name": c.get("document_name", ""),
                        "category":      c.get("category", ""),
                        "content":       c.get("content", "")[:800],  # trim for prompt budget
                    }
                    for c in chunks
                ],
            })
        except Exception as exc:
            logger.warning("search_runbooks: %s", exc)
            return _err("search_runbooks", str(exc))


# ── Tool 4: analyse_errors ────────────────────────────────────────────────────

class AnalyseErrorsConnector(MCPToolConnector):
    """Trigger Phase 10 AI error analysis on recent events."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "analyse_errors"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="analyse_errors",
            description=(
                "Run AI error analysis on recent application events. "
                "Returns severity, likely root cause, confidence score, and recommended fix. "
                "Use this to answer 'What is causing the current errors?' or "
                "'Summarize today's errors.'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "lookback_minutes": {"type": "integer", "description": "How far back to look (default 30, max 1440)."},
                    "session_id":       {"type": "string",  "description": "Optional: analyse a specific session only."},
                },
                "required": [],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        from app.schemas.error_analysis import AnalyseErrorRequest
        from app.services.error_analysis_service import ErrorAnalysisService

        request = AnalyseErrorRequest(
            lookback_minutes = min(int(params.get("lookback_minutes", 30)), 1440),
            session_id       = params.get("session_id"),
        )
        try:
            service = ErrorAnalysisService(self._db)
            result  = await service.analyse(request)
            return _ok({
                "analysis_id":        result.analysis_id,
                "severity":           result.severity,
                "summary":            result.summary,
                "likely_root_cause":  result.likely_root_cause,
                "confidence":         result.confidence,
                "recommended_fix":    result.recommended_fix,
                "evidence":           result.evidence[:5],
                "possible_causes":    result.possible_causes[:3],
                "related_docs":       result.related_documentation[:3],
                "low_confidence_warning": result.low_confidence_warning,
                "events_analysed":    result.events_analysed,
            })
        except Exception as exc:
            logger.warning("analyse_errors: %s", exc)
            return _err("analyse_errors", str(exc))


# ── Tool 5: get_rca ───────────────────────────────────────────────────────────

class GetRcaConnector(MCPToolConnector):
    """Trigger or fetch Phase 12 Root Cause Analysis for an incident."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "get_rca"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="get_rca",
            description=(
                "Perform or retrieve a Root Cause Analysis for a specific incident. "
                "Returns ranked root cause candidates with per-candidate confidence scores. "
                "Use this to answer 'What is the likely root cause of INC-xxx?' or "
                "'Why did the API fail yesterday?'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "incident_id":         {"type": "string",  "description": "UUID of the incident to analyse."},
                    "evidence_window_min": {"type": "integer", "description": "Evidence window in minutes (default 30)."},
                    "force_rerun":         {"type": "boolean", "description": "Re-run even if a cached result exists."},
                },
                "required": ["incident_id"],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        import uuid as _uuid
        from app.schemas.rca import RcaRequest
        from app.services.rca_service import RcaService

        try:
            incident_id = _uuid.UUID(params["incident_id"])
        except (KeyError, ValueError):
            return _err("get_rca", "incident_id must be a valid UUID")

        request = RcaRequest(
            evidence_window_minutes = min(int(params.get("evidence_window_min", 30)), 240),
            force_rerun             = bool(params.get("force_rerun", False)),
        )
        try:
            service = RcaService(self._db)
            result  = await service.run(incident_id=incident_id, request=request)
            return _ok({
                "rca_id":            result.rca_id,
                "incident_id":       result.incident_id,
                "summary":           result.summary,
                "overall_confidence":result.overall_confidence,
                "top_candidate":     result.root_cause_candidates[0].model_dump() if result.root_cause_candidates else None,
                "all_candidates":    [c.model_dump() for c in result.root_cause_candidates[:3]],
                "investigation_steps":result.investigation_steps[:3],
                "related_docs":      result.related_documentation[:3],
                "low_confidence_warning": result.low_confidence_warning,
            })
        except Exception as exc:
            logger.warning("get_rca: %s", exc)
            return _err("get_rca", str(exc))


# ── Tool 6: get_incident_summary ──────────────────────────────────────────────

class GetIncidentSummaryConnector(MCPToolConnector):
    """Fetch full detail of a single incident including AI analysis and RCA."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "get_incident_summary"

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="get_incident_summary",
            description=(
                "Get the full summary of a specific incident, including its AI error "
                "analysis and root cause analysis results. "
                "Use this to answer 'Tell me about incident INC-xxx' or "
                "'Generate an incident report for this ID.'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "incident_id": {"type": "string", "description": "UUID of the incident."},
                },
                "required": ["incident_id"],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        import uuid as _uuid
        from app.repositories.incident_repository import IncidentRepository

        try:
            incident_id = _uuid.UUID(params["incident_id"])
        except (KeyError, ValueError):
            return _err("get_incident_summary", "incident_id must be a valid UUID")

        repo = IncidentRepository(self._db)
        try:
            incident = await repo.get_by_id(incident_id)
            if incident is None:
                return _err("get_incident_summary", f"Incident {incident_id} not found")

            # Deserialise RCA candidates if present
            rca_candidates: list = []
            if incident.rca_candidates_json:
                try:
                    rca_candidates = json.loads(incident.rca_candidates_json)[:3]
                except Exception:
                    pass

            rca_steps: list = []
            if incident.rca_investigation_steps_json:
                try:
                    rca_steps = json.loads(incident.rca_investigation_steps_json)[:3]
                except Exception:
                    pass

            return _ok({
                "id":                str(incident.id),
                "title":             incident.title,
                "severity":          incident.severity,
                "status":            incident.status,
                "detection_method":  incident.detection_method,
                "triggered_by":      incident.triggered_by,
                "metric_value":      incident.metric_value,
                "threshold_value":   incident.threshold_value,
                "detected_at":       incident.detected_at.isoformat() if incident.detected_at else "",
                "resolved_at":       incident.resolved_at.isoformat() if incident.resolved_at else None,
                "event_count":       incident.event_count,
                # Phase 10 error analysis
                "error_analysis": {
                    "analysis_id":       incident.analysis_id,
                    "summary":           incident.ai_summary,
                    "confidence":        incident.ai_confidence,
                    "recommended_fix":   incident.ai_recommended_fix,
                } if incident.ai_summary else None,
                # Phase 12 RCA
                "rca": {
                    "rca_id":             incident.rca_analysis_id,
                    "summary":            incident.rca_summary,
                    "confidence":         incident.rca_confidence,
                    "top_candidates":     rca_candidates,
                    "investigation_steps":rca_steps,
                } if incident.rca_summary else None,
            })
        except Exception as exc:
            logger.warning("get_incident_summary: %s", exc)
            return _err("get_incident_summary", str(exc))


# ── Tool 7: create_incident ───────────────────────────────────────────────────

class CreateIncidentConnector(MCPToolConnector):
    """Manually create a new incident record — requires confirmation."""

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    @property
    def tool_name(self) -> str:
        return "create_incident"

    @property
    def requires_confirmation(self) -> bool:
        # This is a write operation — the MCP broker will request confirmation
        # before actually invoking the tool.
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            name="create_incident",
            description=(
                "Manually create a new production incident record. "
                "⚠️ Requires explicit confirmation before execution. "
                "Use this to answer 'Create an incident for the current DB issue.'"
            ),
            input_schema={
                "type": "object",
                "properties": {
                    "title":    {"type": "string", "description": "Short description of the incident."},
                    "severity": {"type": "string", "description": "CRITICAL|HIGH|MEDIUM|LOW"},
                    "description": {"type": "string", "description": "Detailed description (stored as triggered_by)."},
                },
                "required": ["title", "severity"],
            },
        )

    async def invoke(self, params: dict, user_id: str) -> MCPToolResult:
        from app.repositories.incident_repository import IncidentRepository

        repo = IncidentRepository(self._db)
        try:
            incident = await repo.create(
                title            = params.get("title", "Untitled incident"),
                severity         = params.get("severity", "MEDIUM"),
                detection_method = "manual",
                triggered_by     = params.get("description", "manual:devops_assistant"),
                event_count      = 0,
                window_minutes   = 0,
            )
            await self._db.commit()
            logger.info(
                "create_incident: created %s severity=%s by user=%s",
                incident.id, incident.severity, user_id,
            )
            return _ok({
                "id":          str(incident.id),
                "title":       incident.title,
                "severity":    incident.severity,
                "status":      incident.status,
                "detected_at": incident.detected_at.isoformat() if incident.detected_at else "",
            })
        except Exception as exc:
            logger.warning("create_incident: %s", exc)
            return _err("create_incident", str(exc))
