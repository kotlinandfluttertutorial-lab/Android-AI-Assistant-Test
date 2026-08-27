# Phase 13 — AI DevOps Assistant Guide

> **Learning goal:** Understand the ReAct (Reason + Act) pattern for tool-using
> LLM agents, how tools are selected and executed, and why grounding answers in
> real data is the key to a trustworthy DevOps assistant.
>
> **Career connection:** Tool-using LLM agents are the core of every production
> AI system in 2026 — from GitHub Copilot Workspace to AWS DevOps Guru. This
> pattern appears in every GenAI/AIOps interview.

---

## 1. Concept — What Is a Tool-Using AI Assistant?

A plain LLM answers questions from its training data. A tool-using assistant
answers questions by calling real APIs and basing its response on the results.

```
Plain LLM:
  User:  "What errors happened in our app today?"
  LLM:   "I don't have access to your application logs." (or hallucinates)

Tool-using assistant:
  User:  "What errors happened in our app today?"
  LLM:   THINK → calls search_logs(level="ERROR", minutes=1440)
  Tool:  returns 47 ERROR events from today
  LLM:   OBSERVE → synthesises a summary
  Answer: "Today there were 47 errors, predominantly http_error (31 events)
           and network_timeout (12 events). The highest concentration was
           between 14:30–14:45 UTC, coinciding with incident INC-001..."
```

The difference: the second answer is **grounded** in real data. The LLM cannot
make up event counts or timestamps because the tool returned them.

---

## 2. The ReAct Pattern

ReAct (Reasoning + Acting) is the standard pattern for tool-using agents.
The name describes the alternating loop of reasoning and action:

```
User question
     │
     ▼
THINK: "Which tool do I need?"
     │
     ▼
ACT:   Call the tool
     │
     ▼
OBSERVE: Read the result
     │
     ├── "I need more data" → THINK again (loop, max 3 rounds)
     │
     └── "I have enough" → ANSWER
```

### Why not just call all tools upfront?

1. **Token budget** — tool results consume context window. Calling all 7 tools
   every time would use 3,000–5,000 tokens before the LLM even starts reasoning.

2. **Relevance** — "How do I restart the service?" needs `search_runbooks`, not
   `search_incidents`. Calling the wrong tool adds noise.

3. **Latency** — each tool call takes ~100ms–5s. Unnecessary calls slow the response.

ReAct lets the LLM decide which tools to call based on what it already knows.

---

## 3. Architecture — How This Implementation Works

```
POST /api/v1/devops/chat
  {"question": "Why did the API fail at 14:32?"}
        │
        ▼
DevOpsAssistantService.ask()
        │
        ▼
  _build_broker() — registers 7 DevOps MCPToolConnectors with MCPBroker
        │
        ▼
  ┌─────────────────────────────────────────────────────┐
  │  ReAct Loop (max 3 rounds)                          │
  │                                                     │
  │  messages = [system_prompt, user_question]          │
  │              │                                     │
  │              ▼                                     │
  │  LLM call (AIOrchestrator.complete())              │
  │              │                                     │
  │   ┌──────────┴──────────┐                          │
  │   │                     │                          │
  │ {action: tool_call}  {action: answer}              │
  │   │                     │                          │
  │   ▼                     └──────────────────────── EXIT
  │ MCPBroker.invoke(tool_name, params)                │
  │   │                                                │
  │   ▼                                                │
  │ Tool executes (DB query / ChromaDB / service call) │
  │   │                                                │
  │   ▼                                                │
  │ Inject result into messages                        │
  │   │                                                │
  │   └────── next round ──────────────────────────────┘
  └─────────────────────────────────────────────────────┘
        │
        ▼
DevOpsChatResponse
  {
    "answer":      "At 14:32, the API began returning 500 errors...",
    "citations":   ["INC-001", "INC-001-db-connection-pool.md"],
    "tool_calls":  [{tool: "search_logs", ...}, {tool: "search_incidents", ...}],
    "rounds_used": 2,
    "llm_provider": "gemini"
  }
```

---

## 4. The 7 DevOps Tools

| Tool | Purpose | Data source |
|------|---------|-------------|
| `search_logs` | Find log events by text/level/type | `observability_events` table |
| `search_incidents` | List incidents by severity/status | `incidents` table |
| `search_runbooks` | Semantic search of knowledge base | `devops_knowledge` ChromaDB |
| `analyse_errors` | AI error analysis (Phase 10) | `observability_events` + LLM |
| `get_rca` | Root cause analysis (Phase 12) | `incidents` + multi-source + LLM |
| `get_incident_summary` | Full detail of one incident | `incidents` (Phase 10 + 12 data) |
| `create_incident` | Create a new incident record | `incidents` table (write) |

### Tool input/output format

Each tool follows the same contract from the MCP broker framework:

```python
# Input: dict of parameters
params = {"query": "connection pool", "level": "ERROR", "minutes": 60}

# Output: MCPToolResult
MCPToolResult(
    tool_name="search_logs",
    success=True,
    data={"count": 23, "events": [...]},
    result_status="success",
)
```

The LLM sees the `data` field as JSON in the next message.

### The only write tool — `create_incident`

`create_incident` sets `requires_confirmation = True`. This means `MCPBroker.invoke()`
returns `result_status="confirmation_required"` without actually calling the tool.
The assistant will tell the user "I need your confirmation to create an incident."
No production change happens without explicit human approval.

---

## 5. System Prompt Design

The system prompt defines the assistant's capabilities and output format.
Two rules make the ReAct loop work reliably:

### Rule 1 — Strict JSON response format

The LLM is instructed to respond with one of exactly two JSON shapes:

```json
// To call a tool:
{"action": "tool_call", "tool": "search_logs", "params": {"level": "ERROR", "minutes": 60}}

// To give the final answer:
{"action": "answer", "text": "The API failed because...", "citations": ["INC-001"]}
```

Without this constraint, the LLM would mix JSON and prose, making parsing
unreliable. The strict format is the glue between LLM reasoning and code execution.

### Rule 2 — Grounding constraint

```
"Only use data from tool results — never invent log lines, metric values, or incident IDs."
```

This is the same rule as Phase 10/12 — the LLM's job is reasoning and synthesis,
not memory retrieval. It cannot invent an incident ID that wasn't returned by
`search_incidents`.

### How the prompt grows over the loop

Each round appends to the message list:

```
Round 0: [SYSTEM, USER: "Why did the API fail?"]
Round 1: [SYSTEM, USER, ASSISTANT: {tool_call: search_logs}, TOOL RESULT: {23 events}]
Round 2: [SYSTEM, USER, ASSISTANT, TOOL RESULT, ASSISTANT: {tool_call: search_incidents}, TOOL RESULT: {INC-001}]
Round 3: [SYSTEM, USER, ASSISTANT, TOOL RESULT, ASSISTANT, TOOL RESULT, ASSISTANT: {answer: "..."}]
```

The full conversation history gives the LLM context about what it already found —
it doesn't re-call `search_logs` if it already has the logs.

---

## 6. Example Conversations and Tool Selection

### "Why did the API fail at 14:32?"

```
Round 1: search_logs(query="fail", level="ERROR", minutes=60)
         → 23 ERROR events near 14:32, mostly http_error on /chat
Round 2: search_incidents(status="OPEN")
         → INC-001 detected at 14:32, severity=HIGH, triggered_by=error_rate
Answer:  "At 14:32, the /chat endpoint began returning HTTP 500 errors (23 events
          in 13 minutes). This triggered incident INC-001 — DB connection pool
          exhausted. Root cause: LLM calls holding connections open."
Citations: ["INC-001", "observability_events: 2026-08-26T14:32:01"]
```

### "How do I restart the backend service?"

```
Round 1: search_runbooks(query="restart backend service", category="runbooks")
         → service-restart.md content
Answer:  "To restart the backend: gcloud run services update ai-assistant-backend
          --region=asia-south1 --image=<current_image>. This forces a new revision
          without changing the image. See: runbooks/service-restart.md"
Citations: ["runbooks/service-restart.md"]
```

### "Show me today's critical incidents"

```
Round 1: search_incidents(severity="CRITICAL", status="OPEN")
         → 2 OPEN incidents: INC-001 (HIGH), none CRITICAL
Answer:  "There are currently no CRITICAL incidents. There are 2 HIGH incidents:
          INC-001 (DB connection pool, OPEN) and INC-002 (LLM timeout, INVESTIGATING)."
Citations: ["INC-001", "INC-002"]
```

### "Generate an incident report for INC-001"

```
Round 1: search_incidents(status="OPEN")
         → INC-001 id=abc-123, severity=HIGH, ai_summary="DB pool exhausted"
Round 2: get_incident_summary(incident_id="abc-123")
         → full detail: Phase 10 analysis + Phase 12 RCA
Answer:  "# Incident Report: INC-001
          Severity: HIGH | Status: OPEN | Detected: 2026-08-26T14:32
          Summary: DB connection pool exhausted by LLM calls
          Root cause (confidence 0.87): LLM calls holding DB connections open...
          Recommended fix: Add asyncio.wait_for(timeout=30)..."
Citations: ["INC-001", "database-recovery.md", "INC-001-db-connection-pool.md"]
```

---

## 7. How to Test the DevOps Assistant

### Start the server

```bash
docker-compose up -d
# or locally:
uvicorn app.main:app --reload --host 0.0.0.0 --port 8000
```

### Get a JWT

```bash
JWT=$(curl -s -X POST http://localhost:8000/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@test.com","password":"testpass"}' | jq -r .access_token)
```

### Ask a question

```bash
curl -s -X POST http://localhost:8000/api/v1/devops/chat \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"question": "How do I restart the backend service?"}' | jq '
    {
      answer:      .answer,
      citations:   .citations,
      tools_used:  [.tool_calls[].tool_name],
      rounds:      .rounds_used,
      provider:    .llm_provider
    }
  '
```

### List available tools

```bash
curl -s http://localhost:8000/api/v1/devops/tools \
  -H "Authorization: Bearer $JWT" | jq '.[].name'
# "search_logs"
# "search_incidents"
# "search_runbooks"
# "analyse_errors"
# "get_rca"
# "get_incident_summary"
# "create_incident"
```

### Invoke a tool directly

```bash
# Search for connection pool errors in the last 2 hours
curl -s -X POST http://localhost:8000/api/v1/devops/tools/search_logs/invoke \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"params": {"query": "connection pool", "level": "ERROR", "minutes": 120}}' \
  | jq '.data.count'
```

---

## 8. Debug — Common Issues

### The assistant always says "No answer was produced"

The LLM is not returning valid JSON. Check:
1. Set `LOG_LEVEL=DEBUG` — look for the raw LLM response in the logs
2. The LLM may be responding in prose instead of JSON — this usually means the
   system prompt didn't reach it. Check `_messages_to_prompt()` output.
3. Try a different provider: `{"question": "...", "provider": "gemini"}`

### `rounds_used` is always 0

The LLM answered directly without calling any tools. This is correct for simple
questions like "How do I restart the service?" (answered via `search_runbooks`
without needing `search_logs` first). Check `tool_calls` — if empty, the LLM
went straight to an answer (which may or may not be grounded).

### Tool returns `{"error": "Tool 'X' is not registered"}`

The tool name in the LLM's JSON response doesn't match the registered tool name.
Common mismatch: LLM says `"search_log"` (singular) instead of `"search_logs"`.
Fix: add the exact tool names to the system prompt or rename the connector.

### Answer lacks citations

The LLM didn't include `"citations"` in its answer JSON. Add to the system prompt:
```
"Always include specific evidence sources in the citations list: log timestamps, incident IDs, document names."
```

### `create_incident` never actually creates anything

By design — `requires_confirmation = True` returns `result_status="confirmation_required"`.
To test actual creation, call the tool directly with the `invoke` endpoint, or
temporarily set `requires_confirmation = False` in development.

---

## 9. Interview Questions

**Q1: What is the ReAct pattern? How does it differ from plain RAG?**

ReAct (Reasoning + Acting) is an agent pattern where the LLM alternates between
reasoning about what to do and executing tools. The LLM decides *which* tool to
call based on the question, calls it, reads the result, and decides whether to
call more tools or give an answer.

Plain RAG always retrieves first, then answers. It uses a fixed retrieval path
(usually vector search). ReAct is dynamic: the LLM might call a database query
tool for one question and a knowledge base search tool for another. RAG is
appropriate when the data source is fixed and the retrieval path is obvious.
ReAct is better when multiple data sources exist and the right source depends
on the question.

---

**Q2: Why is a strict JSON response format important for tool-calling agents?**

The LLM's output becomes code input — the application code needs to parse it
reliably. A strict format (`{"action": "tool_call", "tool": "...", "params": {...}}`)
means the parser only needs to handle two shapes. Without it, the LLM might
return "I'll call search_logs" as prose, which requires fragile regex parsing
that breaks across models and versions.

The trade-off: strict JSON reduces flexibility (the LLM can't explain its reasoning
in prose during the loop). We solve this by capturing the `chain_of_thought` in
a field rather than expecting it in the control flow JSON.

---

**Q3: How do you prevent the assistant from making up data?**

Two mechanisms:

1. **Grounding constraint in the system prompt:** "Only use data from tool results —
   never invent log lines, metric values, or incident IDs." This directly instructs
   the LLM not to fabricate.

2. **Structured tool outputs:** Tools return structured dicts with real data fields.
   The LLM quotes specific values from these dicts in its answer. If a field isn't
   in the tool output, the LLM has no value to cite — it can only say "not available."

This is the same principle as RAG: ground the LLM in retrieved content so it
reports rather than invents.

---

**Q4: What is the `requires_confirmation` flag on `create_incident` and why does it matter?**

`MCPToolConnector.requires_confirmation = True` causes `MCPBroker.invoke()` to
return `result_status="confirmation_required"` without executing the tool's
`invoke()` method. The tool is never called until the user explicitly confirms.

This implements the master plan's core safety principle: "The AI must never
automatically execute destructive production actions without explicit approval."

Creating an incident is a production-affecting action — it notifies engineers,
shows up in dashboards, and may trigger automated responses. Requiring confirmation
ensures a human is in the loop before this happens.

---

**Q5: What is the maximum rounds limit and why is it necessary?**

The `_MAX_TOOL_ROUNDS = 3` constant limits how many tool calls the assistant makes
per turn. Without it, a confused LLM could loop indefinitely — calling the same
tools over and over, consuming tokens and time.

Three rounds is enough for the most complex DevOps queries: one to search logs,
one to search incidents, and one to look up the full incident detail. If three
rounds aren't enough, the assistant returns what it has with a note that evidence
was limited.

The limit also controls cost: each round is an LLM call. Three rounds × ~500
tokens/prompt = ~1,500 tokens per question. Without a limit, a buggy interaction
could cost orders of magnitude more.

---

**Q6: How would you add streaming support to the DevOps assistant?**

The current implementation uses `AIOrchestrator.complete()` (non-streaming).
For streaming tool-calling there are two approaches:

**Approach A — Stream the final answer only:**
1. Run the tool-calling loop synchronously (tools + LLM for action selection)
2. Once all tools have been called and the LLM is ready to answer, switch to
   `AIOrchestrator.stream_chat()` for the synthesis step
3. Stream the final tokens over WebSocket

**Approach B — Stream tool call events too:**
1. When the LLM decides to call a tool, emit a `{"type": "tool_call", "toolName": "..."}` message
2. Execute the tool
3. Emit `{"type": "tool_result", "toolName": "...", "data": {...}}`
4. Continue the loop, streaming the final answer

Approach B is what the WebSocket router already declares (`tool_call` message type
is in the docstring) but has not yet been implemented. This would give the Android
app a real-time view of the assistant's reasoning process — visible "thinking" steps
before the final answer.

---

## 10. Exercise

1. **Ask a grounded question** — with error events in the database, ask:
   ```
   "What errors happened in the last hour?"
   ```
   Verify the answer contains real timestamps and event counts, not invented data.
   Check `tool_calls` to confirm `search_logs` was called.

2. **Ask a knowledge-base question** — ask:
   ```
   "How do I rollback a bad deployment?"
   ```
   Verify `search_runbooks` was called and the answer cites `rollback.md`.

3. **Force a multi-round interaction** — ask:
   ```
   "Is there an open incident related to the current errors?"
   ```
   This should trigger: `search_logs` (find errors) → `search_incidents` (find
   matching incident). Verify `rounds_used = 2`.

4. **Invoke a tool directly** — call `search_runbooks` with your own query:
   ```bash
   curl -X POST http://localhost:8000/api/v1/devops/tools/search_runbooks/invoke \
     -H "Authorization: Bearer $JWT" \
     -d '{"params": {"query": "database connection pool", "category": "incidents"}}'
   ```
   Verify `INC-001-db-connection-pool.md` appears in the results.

5. **Test the confirmation gate** — ask:
   ```
   "Create an incident for the high error rate."
   ```
   Verify the response says confirmation is required and no incident was created
   in the database (check `GET /incidents`).

---

## Phase 13 Summary

**What was built:**

```
Search extension (observability_event_repository.py)
  search_logs(query, level, event_type, minutes, limit)
  — ILIKE text search over event messages

7 DevOps MCP tool connectors (devops_connectors.py)
  SearchLogsConnector        — query observability_events
  SearchIncidentsConnector   — list incidents with filters
  SearchRunbooksConnector    — devops_knowledge ChromaDB search
  AnalyseErrorsConnector     — trigger Phase 10 error analysis
  GetRcaConnector            — trigger Phase 12 RCA
  GetIncidentSummaryConnector — full incident detail (P10 + P12)
  CreateIncidentConnector    — write (requires_confirmation=True)

DevOpsAssistantService (devops_assistant_service.py)
  ReAct loop: LLM → parse JSON → tool call → inject result → repeat
  System prompt with strict JSON format constraint
  Max 3 tool rounds per question

API (api/devops/router.py)
  POST /api/v1/devops/chat         — conversational question answering
  GET  /api/v1/devops/tools        — list available tools
  POST /api/v1/devops/tools/{n}/invoke — direct tool invocation
```

**Connection to next phases:**
- Phase 14 (Android Dashboard) adds a Compose UI that calls `/devops/chat`
  and renders `tool_calls` as expandable cards showing the assistant's reasoning
- Phase 15 (AIOps) adds automated incident creation + analysis triggered by
  anomaly detection, with human approval before remediation

Say `NEXT` to continue to **Phase 14 — Android AI DevOps Dashboard**.
