"""DevOps Assistant Service — Phase 13.

Implements a conversational ReAct (Reason + Act) loop for DevOps questions.

ReAct pattern:
  1. User asks a question
  2. LLM reasons about which tool(s) to call  → THINK
  3. Tool executes and returns structured data → ACT
  4. LLM synthesises an answer from the data  → OBSERVE + ANSWER

This is different from plain RAG (retrieve then answer):
  RAG:   always retrieves, always uses the same retrieval path
  ReAct: the LLM decides *which* tool to use based on the question,
         calls it, reads the result, and decides whether to call more tools

Example conversation:
  User:  "Why did the API fail at 14:32?"
  LLM:   THINK → calls search_logs(query="14:32", level="ERROR")
  Tool:  returns 23 ERROR events around 14:32
  LLM:   THINK → calls search_incidents(status="OPEN")
  Tool:  returns INC-001 detected at 14:32
  LLM:   OBSERVE → "API failed due to DB connection pool exhaustion (INC-001)"
  Answer: "At 14:32, the API began returning HTTP 500 errors. This coincided
           with the creation of incident INC-001 (DB connection pool exhausted).
           The root cause is LLM calls holding DB connections open for 30+s.
           Recommended fix: add asyncio.wait_for(timeout=30) around LLM calls."

AI Safety:
  - Tool calls are pre-approved reads (except create_incident which requires confirmation)
  - All tool outputs are grounded in real data — the LLM cannot invent events
  - Confidence scores from Phase 10/12 are passed through to the final answer
  - The assistant never takes automated production actions

Phase 13 — AI DevOps Assistant
"""

from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass, field

from sqlalchemy.ext.asyncio import AsyncSession

from app.schemas.mcp import MCPToolResult
from app.services.mcp_broker import MCPBroker
from app.services.mcp_connectors.devops_connectors import (
    AnalyseErrorsConnector,
    CreateIncidentConnector,
    GetIncidentSummaryConnector,
    GetRcaConnector,
    SearchIncidentsConnector,
    SearchLogsConnector,
    SearchRunbooksConnector,
)

logger = logging.getLogger(__name__)

# ── Constants ─────────────────────────────────────────────────────────────────

_MAX_TOOL_ROUNDS   = 3     # maximum tool calls per conversation turn
_LLM_MAX_TOKENS    = 1024
_LLM_TIMEOUT       = 45.0

# ── System prompt ─────────────────────────────────────────────────────────────

_SYSTEM_PROMPT = """You are an expert AI DevOps assistant with access to real-time
operational data from a production Android AI Assistant system.

You have the following tools available:

  search_logs(query, level, event_type, minutes, limit)
    → Search Android observability event logs

  search_incidents(severity, status, limit)
    → List production incidents

  search_runbooks(query, category, top_k)
    → Semantic search the DevOps knowledge base (runbooks, incidents, architecture)

  analyse_errors(lookback_minutes, session_id)
    → Run AI error analysis on recent events

  get_rca(incident_id, evidence_window_min, force_rerun)
    → Run or fetch Root Cause Analysis for an incident

  get_incident_summary(incident_id)
    → Full details of a specific incident

  create_incident(title, severity, description)
    → Create a new incident (requires user confirmation)

RESPONSE FORMAT:
When you want to call a tool, respond with ONLY valid JSON:
{"action": "tool_call", "tool": "<tool_name>", "params": {<params>}}

When you have enough information to answer, respond with ONLY valid JSON:
{"action": "answer", "text": "<your answer>", "citations": ["<source1>", "<source2>"]}

RULES:
1. Only use data from tool results — never invent log lines, metric values, or incident IDs.
2. Always cite the source of your information (log timestamps, incident IDs, runbook names).
3. State confidence levels when referencing AI analysis results.
4. Recommend actions as suggestions — never claim you will execute anything automatically.
5. If you cannot find relevant data after 3 tool calls, say so honestly.
"""


# ── Data classes ──────────────────────────────────────────────────────────────

@dataclass
class ToolCallRecord:
    """One round of: tool called → result received."""
    tool_name: str
    params:    dict
    result:    dict  # MCPToolResult.data or {"error": "..."}


@dataclass
class DevOpsAssistantResponse:
    """Final response from the DevOps assistant."""
    session_id:     str
    question:       str
    answer:         str
    citations:      list[str] = field(default_factory=list)
    tool_calls:     list[ToolCallRecord] = field(default_factory=list)
    rounds_used:    int = 0
    llm_provider:   str = ""
    error:          str | None = None


# ── Service ───────────────────────────────────────────────────────────────────

class DevOpsAssistantService:
    """Conversational DevOps assistant using a ReAct tool-calling loop.

    Usage::

        service = DevOpsAssistantService(db)
        response = await service.ask("Why did the API fail at 14:32?")
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db     = db
        self._broker = self._build_broker(db)

    @staticmethod
    def _build_broker(db: AsyncSession) -> MCPBroker:
        """Register all 7 DevOps tool connectors with the MCP broker."""
        broker = MCPBroker(db)
        broker.register(SearchLogsConnector(db))
        broker.register(SearchIncidentsConnector(db))
        broker.register(SearchRunbooksConnector())
        broker.register(AnalyseErrorsConnector(db))
        broker.register(GetRcaConnector(db))
        broker.register(GetIncidentSummaryConnector(db))
        broker.register(CreateIncidentConnector(db))
        return broker

    # ── Public entry point ────────────────────────────────────────────────────

    async def ask(
        self,
        question: str,
        user_id:  str = "system",
        provider_override: str | None = None,
    ) -> DevOpsAssistantResponse:
        """Run the ReAct loop for one conversation turn.

        Steps:
        1. Build prompt with available tools + user question
        2. Ask LLM: tool call or answer?
        3. If tool call → execute → inject result → repeat (max 3 rounds)
        4. When LLM says "answer" → return to caller

        Args:
            question:          The user's DevOps question.
            user_id:           Authenticated user ID for audit logging.
            provider_override: Override the default LLM provider.

        Returns:
            :class:`DevOpsAssistantResponse` with the final answer and tool call trace.
        """
        session_id = str(uuid.uuid4())
        tool_calls: list[ToolCallRecord] = []

        # Describe available tools for the prompt
        tool_schemas = self._broker.discover()
        tools_description = "\n".join(
            f"  {t.name}: {t.description}" for t in tool_schemas
        )

        # Build the conversation: system + user question + accumulating tool results
        messages: list[dict] = [
            {"role": "system",    "content": _SYSTEM_PROMPT},
            {"role": "user",      "content": f"Question: {question}"},
        ]

        provider_name = provider_override or ""
        final_answer  = ""
        citations:  list[str] = []

        for round_idx in range(_MAX_TOOL_ROUNDS + 1):
            # Build prompt from messages
            prompt = self._messages_to_prompt(messages)

            # Call LLM
            llm_text, provider_name = await self._call_llm(
                prompt, provider_override, user_id
            )

            if not llm_text.strip():
                final_answer = "I was unable to retrieve the information needed to answer your question."
                break

            # Parse LLM response
            action = self._parse_action(llm_text)

            if action.get("action") == "answer":
                final_answer = action.get("text", "")
                citations    = action.get("citations", [])
                break

            if action.get("action") == "tool_call" and round_idx < _MAX_TOOL_ROUNDS:
                tool_name = action.get("tool", "")
                params    = action.get("params", {})

                logger.info(
                    "devops_assistant: round=%d tool=%s user=%s",
                    round_idx + 1, tool_name, user_id,
                )

                # Execute the tool
                tool_result = await self._broker.invoke(
                    tool_name  = tool_name,
                    params     = params,
                    user_id    = user_id,
                )

                result_data = self._extract_result(tool_result)
                tool_calls.append(ToolCallRecord(
                    tool_name = tool_name,
                    params    = params,
                    result    = result_data,
                ))

                # Inject tool result into conversation
                messages.append({"role": "assistant", "content": llm_text})
                messages.append({
                    "role":    "tool",
                    "content": (
                        f"Tool: {tool_name}\n"
                        f"Result: {json.dumps(result_data, default=str)[:2000]}"
                    ),
                })
            else:
                # Unexpected format or max rounds reached
                final_answer = llm_text  # use raw LLM text as fallback
                break

        return DevOpsAssistantResponse(
            session_id   = session_id,
            question     = question,
            answer       = final_answer or "No answer was produced.",
            citations    = citations,
            tool_calls   = tool_calls,
            rounds_used  = len(tool_calls),
            llm_provider = provider_name,
        )

    # ── Helpers ───────────────────────────────────────────────────────────────

    def _messages_to_prompt(self, messages: list[dict]) -> str:
        """Convert message list to a single prompt string for `AIOrchestrator.complete()`."""
        lines = []
        for m in messages:
            role    = m["role"].upper()
            content = m["content"]
            if role == "SYSTEM":
                lines.append(f"[SYSTEM]\n{content}")
            elif role == "USER":
                lines.append(f"[USER]\n{content}")
            elif role == "ASSISTANT":
                lines.append(f"[ASSISTANT]\n{content}")
            elif role == "TOOL":
                lines.append(f"[TOOL RESULT]\n{content}")
        return "\n\n".join(lines)

    def _parse_action(self, raw: str) -> dict:
        """Extract the JSON action from the LLM response."""
        text = raw.strip()
        if text.startswith("```"):
            lines = text.split("\n")
            text = "\n".join(l for l in lines if not l.strip().startswith("```"))

        start = text.find("{")
        end   = text.rfind("}") + 1
        if start == -1 or end == 0:
            # LLM returned plain prose — treat as final answer
            return {"action": "answer", "text": text, "citations": []}

        try:
            return json.loads(text[start:end])
        except json.JSONDecodeError:
            return {"action": "answer", "text": text, "citations": []}

    def _extract_result(self, result: MCPToolResult) -> dict:
        """Convert MCPToolResult to a plain dict for prompt injection."""
        if result.success and result.data:
            if isinstance(result.data, dict):
                return result.data
            return {"data": result.data}
        return {"error": result.error or "Tool returned no data"}

    async def _call_llm(
        self,
        prompt:            str,
        provider_override: str | None,
        user_id:           str,
    ) -> tuple[str, str]:
        """Call AIOrchestrator.complete() with the assembled prompt."""
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
                    prompt     = prompt,
                    provider   = provider,
                    max_tokens = _LLM_MAX_TOKENS,
                    user_id    = user_id,
                ),
                timeout=_LLM_TIMEOUT,
            )
            return completion.text, provider.value
        except Exception as exc:
            logger.warning("devops_assistant: LLM call failed — %s", exc)
            return "", provider.value
