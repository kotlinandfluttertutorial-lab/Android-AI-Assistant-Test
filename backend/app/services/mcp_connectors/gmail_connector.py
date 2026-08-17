# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : gmail_connector.py
# Purpose : gmail_connector — services/mcp_connectors module
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

"""Gmail MCP Tool Connectors.

Provides two connectors:
- ``GmailReadConnector``  (tool_name="gmail_read")  — read-only operations.
- ``GmailWriteConnector`` (tool_name="gmail_write") — write operations (requires confirmation).

Read actions:  list_messages, get_message, search_messages
Write actions: send_email, create_draft

Auth: Google OAuth2 service-account credentials via ``GMAIL_CREDENTIALS_PATH`` setting.
Uses ``google-api-python-client`` with ``build("gmail", "v1")``.

Requirements: 8.2, 8.3, 8.5
"""

from __future__ import annotations

import base64
import logging
from email.mime.text import MIMEText
from typing import Any

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

_GMAIL_SCOPES = [
    "https://www.googleapis.com/auth/gmail.readonly",
    "https://www.googleapis.com/auth/gmail.send",
    "https://www.googleapis.com/auth/gmail.compose",
]


def _build_gmail_service() -> Any:  # type: ignore[return]
    """Build and return a Gmail API service client."""
    from google.oauth2 import service_account
    from googleapiclient.discovery import build

    creds_path = get_settings().GMAIL_CREDENTIALS_PATH
    credentials = service_account.Credentials.from_service_account_file(
        creds_path, scopes=_GMAIL_SCOPES
    )
    return build("gmail", "v1", credentials=credentials)


class GmailReadConnector(MCPToolConnector):
    """Read-only Gmail connector.

    Supported actions (passed as ``params["action"]``):
    - ``list_messages``:   List recent messages. Params: ``max_results`` (int, opt).
    - ``get_message``:     Get a message by ID. Params: ``message_id``.
    - ``search_messages``: Search messages. Params: ``query`` (Gmail search syntax).

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gmail_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gmail_read",
            description="Read Gmail messages: list recent messages, get message details, search.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["list_messages", "get_message", "search_messages"],
                        "description": "The read action to perform.",
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max messages to return. Default: 10.",
                    },
                    "message_id": {
                        "type": "string",
                        "description": "Gmail message ID (for get_message).",
                    },
                    "query": {
                        "type": "string",
                        "description": "Gmail search query (for search_messages).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_gmail_service()
        except Exception:
            logger.exception("GmailReadConnector: failed to build Gmail service")
            raise

        if action == "list_messages":
            max_results = params.get("max_results", 10)
            result = (
                service.users()
                .messages()
                .list(userId="me", maxResults=max_results)
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"messages": result.get("messages", [])},
                result_status="success",
            )

        elif action == "get_message":
            message_id = params.get("message_id", "")
            msg = (
                service.users()
                .messages()
                .get(userId="me", id=message_id, format="full")
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=msg,
                result_status="success",
            )

        elif action == "search_messages":
            query = params.get("query", "")
            result = service.users().messages().list(userId="me", q=query).execute()
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"messages": result.get("messages", [])},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )


class GmailWriteConnector(MCPToolConnector):
    """Write operations Gmail connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``send_email``:   Send an email. Params: ``to``, ``subject``, ``body``.
    - ``create_draft``: Create a draft. Params: ``to``, ``subject``, ``body``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gmail_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gmail_write",
            description="Write to Gmail: send emails or create drafts.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["send_email", "create_draft"],
                        "description": "The write action to perform.",
                    },
                    "to": {"type": "string", "description": "Recipient email address."},
                    "subject": {"type": "string", "description": "Email subject line."},
                    "body": {
                        "type": "string",
                        "description": "Email body (plain text).",
                    },
                },
                "required": ["action", "to", "subject", "body"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_gmail_service()
        except Exception:
            logger.exception("GmailWriteConnector: failed to build Gmail service")
            raise

        to = params.get("to", "")
        subject = params.get("subject", "")
        body = params.get("body", "")

        mime_msg = MIMEText(body)
        mime_msg["to"] = to
        mime_msg["subject"] = subject
        raw = base64.urlsafe_b64encode(mime_msg.as_bytes()).decode()

        if action == "send_email":
            result = (
                service.users()
                .messages()
                .send(userId="me", body={"raw": raw})
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={
                    "message_id": result.get("id"),
                    "thread_id": result.get("threadId"),
                },
                result_status="success",
            )

        elif action == "create_draft":
            result = (
                service.users()
                .drafts()
                .create(userId="me", body={"message": {"raw": raw}})
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"draft_id": result.get("id")},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )
