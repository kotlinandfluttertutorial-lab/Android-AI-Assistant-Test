# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : gcal_connector.py
# Purpose : gcal_connector — services/mcp_connectors module
#
# Architecture Layer : MCP Connector
# Pattern Used       : MCPToolConnector Implementation
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Google Calendar MCP Tool Connectors.

Provides two connectors:
- ``GCalReadConnector``  (tool_name="gcal_read")  — read-only operations.
- ``GCalWriteConnector`` (tool_name="gcal_write") — write operations (requires confirmation).

Read actions:  list_events, get_event, list_calendars
Write actions: create_event, update_event, delete_event

Auth: Google OAuth2 service-account credentials via ``GCAL_CREDENTIALS_PATH`` setting.
Uses ``google-api-python-client`` with ``build("calendar", "v3")``.

Requirements: 8.2, 8.3, 8.5
"""

from __future__ import annotations

import logging
from typing import Any

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

_GCAL_SCOPES = [
    "https://www.googleapis.com/auth/calendar",
    "https://www.googleapis.com/auth/calendar.readonly",
]


def _build_gcal_service():
    """Build and return a Google Calendar API service client."""
    from google.oauth2 import service_account
    from googleapiclient.discovery import build

    creds_path = get_settings().GCAL_CREDENTIALS_PATH
    credentials = service_account.Credentials.from_service_account_file(
        creds_path, scopes=_GCAL_SCOPES
    )
    return build("calendar", "v3", credentials=credentials)


class GCalReadConnector(MCPToolConnector):
    """Read-only Google Calendar connector.

    Supported actions (passed as ``params["action"]``):
    - ``list_events``:    List upcoming events. Params: ``calendar_id`` (opt),
                          ``max_results`` (int, opt).
    - ``get_event``:      Get a specific event. Params: ``calendar_id``, ``event_id``.
    - ``list_calendars``: List all calendars. Params: none.

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gcal_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gcal_read",
            description=(
                "Read from Google Calendar: list upcoming events,"
                " get event details, list calendars."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["list_events", "get_event", "list_calendars"],
                        "description": "The read action to perform.",
                    },
                    "calendar_id": {
                        "type": "string",
                        "description": "Calendar ID (default: 'primary').",
                    },
                    "event_id": {
                        "type": "string",
                        "description": "Google Calendar event ID (for get_event).",
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max events to return. Default: 10.",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_gcal_service()
        except Exception:
            logger.exception("GCalReadConnector: failed to build Calendar service")
            raise

        calendar_id = params.get("calendar_id", "primary")

        if action == "list_events":
            max_results = params.get("max_results", 10)
            result = (
                service.events()
                .list(
                    calendarId=calendar_id,
                    maxResults=max_results,
                    singleEvents=True,
                    orderBy="startTime",
                )
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"events": result.get("items", [])},
                result_status="success",
            )

        elif action == "get_event":
            event_id = params.get("event_id", "")
            result = (
                service.events().get(calendarId=calendar_id, eventId=event_id).execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "list_calendars":
            result = service.calendarList().list().execute()
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"calendars": result.get("items", [])},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )


class GCalWriteConnector(MCPToolConnector):
    """Write operations Google Calendar connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``create_event``: Create a new event. Params: ``calendar_id``, ``summary``,
                        ``start_datetime``, ``end_datetime``, ``description`` (opt),
                        ``attendees`` (opt).
    - ``update_event``: Update an event. Params: ``calendar_id``, ``event_id``,
                        ``updates`` (dict).
    - ``delete_event``: Delete an event. Params: ``calendar_id``, ``event_id``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gcal_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gcal_write",
            description="Write to Google Calendar: create, update, or delete events.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["create_event", "update_event", "delete_event"],
                        "description": "The write action to perform.",
                    },
                    "calendar_id": {
                        "type": "string",
                        "description": "Calendar ID. Default: 'primary'.",
                    },
                    "summary": {
                        "type": "string",
                        "description": "Event title (for create_event).",
                    },
                    "start_datetime": {
                        "type": "string",
                        "description": (
                            "RFC3339 start datetime,"
                            " e.g. '2024-01-15T10:00:00Z' (for create_event)."
                        ),
                    },
                    "end_datetime": {
                        "type": "string",
                        "description": (
                            "RFC3339 end datetime,"
                            " e.g. '2024-01-15T11:00:00Z' (for create_event)."
                        ),
                    },
                    "description": {
                        "type": "string",
                        "description": "Event description (for create_event).",
                    },
                    "attendees": {
                        "type": "array",
                        "items": {"type": "string"},
                        "description": "List of attendee email addresses (for create_event).",
                    },
                    "event_id": {
                        "type": "string",
                        "description": "Event ID (for update_event, delete_event).",
                    },
                    "updates": {
                        "type": "object",
                        "description": "Fields to update (for update_event).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_gcal_service()
        except Exception:
            logger.exception("GCalWriteConnector: failed to build Calendar service")
            raise

        calendar_id = params.get("calendar_id", "primary")

        if action == "create_event":
            event_body: dict[str, Any] = {
                "summary": params.get("summary", ""),
                "start": {
                    "dateTime": params.get("start_datetime", ""),
                    "timeZone": "UTC",
                },
                "end": {"dateTime": params.get("end_datetime", ""), "timeZone": "UTC"},
            }
            if params.get("description"):
                event_body["description"] = params["description"]
            if params.get("attendees"):
                event_body["attendees"] = [{"email": e} for e in params["attendees"]]

            result = (
                service.events()
                .insert(calendarId=calendar_id, body=event_body)
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "update_event":
            event_id = params.get("event_id", "")
            updates = params.get("updates", {})
            # Patch only the supplied fields
            result = (
                service.events()
                .patch(calendarId=calendar_id, eventId=event_id, body=updates)
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "delete_event":
            event_id = params.get("event_id", "")
            service.events().delete(calendarId=calendar_id, eventId=event_id).execute()
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"deleted": True, "event_id": event_id},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )
