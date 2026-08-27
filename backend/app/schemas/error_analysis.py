"""Pydantic schemas for Phase 10 AI Error Analysis.

Defines the structured response returned by the error analysis pipeline.
The schema matches the Phase 10 specification in the master plan exactly.

AI Safety constraints encoded in this schema:
- ``confidence`` is required and bounded [0.0, 1.0] — never claim certainty
- ``facts_vs_inference`` separates confirmed observations from LLM reasoning
- ``possible_causes`` is a list — never present a single cause as the only answer
- Low-confidence responses (< 0.6) must trigger manual investigation messages

Phase 10 — AI Error Analysis
"""

from __future__ import annotations

from enum import Enum

from pydantic import BaseModel, ConfigDict, Field, field_validator


# ── Enums ─────────────────────────────────────────────────────────────────────


class ErrorSeverity(str, Enum):
    """Severity of the detected error condition."""
    CRITICAL = "CRITICAL"
    HIGH     = "HIGH"
    MEDIUM   = "MEDIUM"
    LOW      = "LOW"


# ── Sub-schemas ───────────────────────────────────────────────────────────────


class FactsVsInference(BaseModel):
    """Separates confirmed observations from LLM reasoning.

    AI Safety Principle: The AI must never present inferences as facts.
    Every claim labelled as a 'fact' must be directly observable in the
    provided evidence (log lines, metrics). Claims labelled as 'inferences'
    are the LLM's reasoning about what the facts mean.

    Phase 10 — AI Error Analysis
    """

    facts: list[str] = Field(
        description=(
            "Directly observable facts from logs and metrics. "
            "Each string must be a specific, verifiable observation — never a conclusion."
        ),
        default_factory=list,
    )
    inferences: list[str] = Field(
        description=(
            "LLM reasoning about probable causes based on the facts. "
            "These are hypotheses, not confirmed root causes."
        ),
        default_factory=list,
    )


# ── Primary response schema ───────────────────────────────────────────────────


class ErrorAnalysisResponse(BaseModel):
    """Structured AI error analysis result.

    Returned by ``POST /api/v1/analysis/errors`` after the full pipeline:
    evidence collection → RAG retrieval → LLM reasoning → structured response.

    AI Safety:
    - ``confidence < 0.6`` → manual investigation required (see low_confidence_warning)
    - All LLM-generated text is grounded in retrieved evidence and context
    - ``facts_vs_inference`` prevents the LLM from presenting guesses as facts
    - ``recommended_fix`` is a suggestion, never an automatic action

    Phase 10 — AI Error Analysis
    """

    model_config = ConfigDict(use_enum_values=True)

    # ── Identity ──────────────────────────────────────────────────────────────
    analysis_id: str = Field(
        description="Unique ID for this analysis run (UUID string)."
    )

    # ── Severity and summary ──────────────────────────────────────────────────
    severity: ErrorSeverity = Field(
        description="Overall severity of the detected error condition."
    )
    summary: str = Field(
        description="One-line human-readable description of what went wrong.",
        max_length=300,
    )

    # ── Evidence ──────────────────────────────────────────────────────────────
    evidence: list[str] = Field(
        description=(
            "Log lines, metric values, or event descriptions that directly support "
            "the analysis. Only items observable in the input data."
        ),
        default_factory=list,
    )

    # ── Root cause analysis ───────────────────────────────────────────────────
    possible_causes: list[str] = Field(
        description=(
            "Ranked list of potential root causes (most likely first). "
            "Having multiple candidates acknowledges uncertainty."
        ),
        default_factory=list,
    )
    likely_root_cause: str = Field(
        description=(
            "The single most probable cause with a brief reasoning statement. "
            "Must reference specific evidence. "
            "When confidence < 0.6, this field says 'Evidence is insufficient — "
            "manual investigation required.'"
        ),
    )

    # ── Confidence ────────────────────────────────────────────────────────────
    confidence: float = Field(
        description=(
            "LLM self-assessed confidence in the root cause identification. "
            "Range 0.0 (no confidence) to 1.0 (certain). "
            "Values below 0.6 trigger low_confidence_warning."
        ),
        ge=0.0,
        le=1.0,
    )

    # ── Recommendations ───────────────────────────────────────────────────────
    recommended_fix: str = Field(
        description=(
            "Step-by-step actionable fix suggestion. "
            "This is a RECOMMENDATION only — no automated action is taken without "
            "explicit human approval."
        ),
    )
    related_documentation: list[str] = Field(
        description=(
            "Runbook names, incident report IDs, or other documentation references "
            "retrieved from the knowledge base that are relevant to this error."
        ),
        default_factory=list,
    )

    # ── Facts vs inference ────────────────────────────────────────────────────
    facts_vs_inference: FactsVsInference = Field(
        description=(
            "Explicit separation of observed facts from LLM reasoning. "
            "Prevents the AI from presenting guesses as confirmed observations."
        ),
        default_factory=FactsVsInference,
    )

    # ── Low confidence warning ────────────────────────────────────────────────
    low_confidence_warning: str | None = Field(
        default=None,
        description=(
            "Populated when confidence < 0.6. "
            "Contains a message indicating that evidence is insufficient for "
            "automated diagnosis and that manual investigation is required."
        ),
    )

    # ── Analysis metadata ─────────────────────────────────────────────────────
    events_analysed: int = Field(
        default=0,
        description="Number of observability events included in this analysis.",
    )
    knowledge_chunks_retrieved: int = Field(
        default=0,
        description="Number of knowledge base chunks retrieved via RAG.",
    )
    llm_provider: str = Field(
        default="",
        description="LLM provider used for this analysis (openai | gemini | claude).",
    )

    @field_validator("confidence")
    @classmethod
    def validate_confidence(cls, v: float) -> float:
        """Round confidence to 2 decimal places for clean display."""
        return round(v, 2)


# ── Request schema ────────────────────────────────────────────────────────────


class AnalyseErrorRequest(BaseModel):
    """Request body for ``POST /api/v1/analysis/errors``."""

    # Analyse a specific event by ID
    event_id: str | None = Field(
        default=None,
        description="UUID of a specific ObservabilityEvent to analyse.",
    )

    # Or analyse all recent errors in a time window
    session_id: str | None = Field(
        default=None,
        description=(
            "Session ID to analyse. Returns all ERROR/CRITICAL events in this session. "
            "Mutually exclusive with event_id."
        ),
    )

    # Time window for recent error analysis (used when neither event_id nor session_id given)
    lookback_minutes: int = Field(
        default=30,
        description="Look-back window in minutes for recent error collection.",
        ge=1,
        le=1440,  # max 24 hours
    )

    # Optional: restrict RAG knowledge retrieval to specific categories
    knowledge_categories: list[str] | None = Field(
        default=None,
        description=(
            "Filter retrieved knowledge to specific categories: "
            "runbooks | incidents | architecture | deployment. "
            "None = search all categories."
        ),
    )

    # Provider selection
    provider: str | None = Field(
        default=None,
        description="LLM provider override. None = use DEFAULT_LLM_PROVIDER from settings.",
    )
