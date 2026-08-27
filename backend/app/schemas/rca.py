"""Pydantic schemas for Phase 12 Root Cause Analysis.

Key difference from Phase 10 ErrorAnalysisResponse:
- Phase 10: one ``likely_root_cause`` string + flat evidence list
- Phase 12: RANKED list of ``RootCauseCandidate`` objects, each with its
  own confidence score, supporting evidence, and reasoning chain.

This lets the caller say "candidate A is 78% likely, candidate B is 15%
likely" — which is far more useful than a single answer that hides uncertainty.

AI Safety constraints:
- ``overall_confidence < 0.6`` → manual investigation warning (same gate as Phase 10)
- Every candidate must list its own evidence (no floating assertions)
- ``chain_of_thought`` exposes the LLM's reasoning so it can be audited
- ``investigation_steps`` are labelled as suggestions, not commands

Phase 12 — Root Cause Analysis
"""

from __future__ import annotations

from pydantic import BaseModel, Field, field_validator


# ── Sub-schemas ───────────────────────────────────────────────────────────────


class RootCauseCandidate(BaseModel):
    """A single ranked root cause candidate with per-candidate confidence.

    Phase 12 — Root Cause Analysis
    """

    rank: int = Field(
        description="1-based rank (1 = most likely candidate).",
        ge=1,
    )
    cause: str = Field(
        description=(
            "Concise description of the potential root cause. "
            "Must be specific enough to be actionable."
        ),
    )
    confidence: float = Field(
        description="LLM confidence that this is the actual root cause (0.0–1.0).",
        ge=0.0,
        le=1.0,
    )
    supporting_evidence: list[str] = Field(
        description=(
            "Specific evidence items (log lines, metric values, error messages) "
            "that support this candidate. Must be drawn from the provided evidence, "
            "never invented."
        ),
        default_factory=list,
    )
    reasoning: str = Field(
        description=(
            "One-paragraph explanation of why this evidence points to this cause. "
            "Shows the chain of reasoning the LLM used."
        ),
        default="",
    )

    @field_validator("confidence")
    @classmethod
    def round_confidence(cls, v: float) -> float:
        return round(v, 2)


class TimelineEvent(BaseModel):
    """A single event on the correlated incident timeline.

    Phase 12 — Root Cause Analysis
    """

    timestamp: str = Field(description="ISO-8601 UTC timestamp string.")
    source: str = Field(
        description="Data source: 'observability_event' | 'error_log' | 'deployment' | 'metric'"
    )
    level: str = Field(
        description="Severity: CRITICAL | ERROR | WARN | INFO | DEBUG",
        default="INFO",
    )
    event_type: str = Field(
        description="Machine-readable category (e.g. 'http_error', 'ValueError').",
        default="",
    )
    message: str = Field(description="Human-readable event description.")
    screen: str | None = Field(
        default=None,
        description="Active screen at time of event (Android events only).",
    )


# ── Primary response schema ───────────────────────────────────────────────────


class RcaAnalysisResponse(BaseModel):
    """Structured Root Cause Analysis result for a specific incident.

    Returned by ``POST /api/v1/incidents/{id}/rca``.

    Key differences from Phase 10 ``ErrorAnalysisResponse``:
    - Multiple ranked candidates, each with individual confidence scores
    - Correlated timeline across all evidence sources
    - Chain-of-thought reasoning exposed per candidate
    - Per-source evidence count (shows how much evidence was available)

    AI Safety:
    - ``overall_confidence < 0.6`` → ``low_confidence_warning`` populated
    - Each candidate lists its own evidence — no floating assertions
    - ``investigation_steps`` are suggestions, never commands
    - ``chain_of_thought`` is the LLM's reasoning, clearly labelled as inference

    Phase 12 — Root Cause Analysis
    """

    # ── Identity ──────────────────────────────────────────────────────────────
    rca_id: str = Field(description="Unique ID for this RCA run (UUID string).")
    incident_id: str = Field(description="UUID of the incident being analysed.")

    # ── Summary ───────────────────────────────────────────────────────────────
    summary: str = Field(
        description="One-line summary of what the RCA concluded.",
        max_length=400,
    )

    # ── Ranked candidates ─────────────────────────────────────────────────────
    root_cause_candidates: list[RootCauseCandidate] = Field(
        description=(
            "Ranked list of potential root causes, most likely first. "
            "Each candidate has its own confidence score and supporting evidence."
        ),
        default_factory=list,
    )

    # ── Overall confidence ────────────────────────────────────────────────────
    overall_confidence: float = Field(
        description=(
            "Overall confidence in the RCA result (0.0–1.0). "
            "Typically the confidence of the top-ranked candidate. "
            "Below 0.6 triggers low_confidence_warning."
        ),
        ge=0.0,
        le=1.0,
    )

    # ── Timeline ──────────────────────────────────────────────────────────────
    timeline: list[TimelineEvent] = Field(
        description=(
            "Chronologically sorted list of all evidence events used in the analysis. "
            "Spans observability events, server error logs, and deployment context."
        ),
        default_factory=list,
    )

    # ── Chain of thought ──────────────────────────────────────────────────────
    chain_of_thought: str = Field(
        description=(
            "The LLM's step-by-step reasoning process, exposed for auditability. "
            "Clearly labelled as inference — not ground truth."
        ),
        default="",
    )

    # ── Investigation steps ───────────────────────────────────────────────────
    investigation_steps: list[str] = Field(
        description=(
            "Ordered list of concrete investigation steps for the on-call engineer. "
            "These are SUGGESTIONS — no automated action is taken without human approval."
        ),
        default_factory=list,
    )

    # ── Related knowledge ─────────────────────────────────────────────────────
    related_documentation: list[str] = Field(
        description="Runbook and incident document names retrieved from the knowledge base.",
        default_factory=list,
    )

    # ── Evidence metadata ─────────────────────────────────────────────────────
    observability_events_count: int = Field(
        default=0,
        description="Number of Android observability events included in the analysis.",
    )
    error_logs_count: int = Field(
        default=0,
        description="Number of server-side error log entries included.",
    )
    knowledge_chunks_count: int = Field(
        default=0,
        description="Number of knowledge base chunks retrieved via RAG.",
    )
    llm_provider: str = Field(
        default="",
        description="LLM provider used for this RCA run.",
    )

    # ── AI safety ─────────────────────────────────────────────────────────────
    low_confidence_warning: str | None = Field(
        default=None,
        description=(
            "Populated when overall_confidence < 0.6. "
            "Instructs the engineer that manual investigation is required."
        ),
    )

    @field_validator("overall_confidence")
    @classmethod
    def round_confidence(cls, v: float) -> float:
        return round(v, 2)


# ── Request schema ────────────────────────────────────────────────────────────


class RcaRequest(BaseModel):
    """Optional parameters for ``POST /incidents/{id}/rca``.

    The incident ID is taken from the URL path — this body only allows
    overriding defaults.
    """

    evidence_window_minutes: int = Field(
        default=30,
        description=(
            "Half-width of the evidence collection window around incident.detected_at "
            "(minutes). Total range = 2 × this value."
        ),
        ge=5,
        le=240,
    )
    provider: str | None = Field(
        default=None,
        description="LLM provider override. None = use DEFAULT_LLM_PROVIDER.",
    )
    force_rerun: bool = Field(
        default=False,
        description=(
            "Re-run RCA even if a result already exists for this incident. "
            "Default False — returns cached result if available."
        ),
    )
