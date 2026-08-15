# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : services/mcp_connectors
# File    : gdrive_connector.py
# Purpose : gdrive_connector — services/mcp_connectors module
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

"""Google Drive MCP Tool Connectors.

Provides two connectors:
- ``GDriveReadConnector``  (tool_name="gdrive_read")  — read-only operations.
- ``GDriveWriteConnector`` (tool_name="gdrive_write") — write operations (requires confirmation).

Read actions:  list_files, get_file_metadata, search_files, download_file
Write actions: create_file, update_file, delete_file

Auth: Google OAuth2 service-account credentials via ``GDRIVE_CREDENTIALS_PATH``
      (falls back to ``GMAIL_CREDENTIALS_PATH`` if blank).
Uses ``google-api-python-client`` with ``build("drive", "v3")``.

Requirements: 8.2, 8.3, 8.5
"""

from __future__ import annotations

import logging
from typing import Any

from app.config.settings import get_settings
from app.schemas.mcp import MCPToolResult, MCPToolSchema
from app.services.mcp_broker import MCPToolConnector

logger = logging.getLogger(__name__)

_DRIVE_SCOPES = [
    "https://www.googleapis.com/auth/drive",
    "https://www.googleapis.com/auth/drive.file",
    "https://www.googleapis.com/auth/drive.readonly",
]


def _build_drive_service():
    """Build and return a Google Drive API service client."""
    from google.oauth2 import service_account
    from googleapiclient.discovery import build

    settings = get_settings()
    creds_path = settings.GDRIVE_CREDENTIALS_PATH or settings.GMAIL_CREDENTIALS_PATH
    credentials = service_account.Credentials.from_service_account_file(
        creds_path, scopes=_DRIVE_SCOPES
    )
    return build("drive", "v3", credentials=credentials)


class GDriveReadConnector(MCPToolConnector):
    """Read-only Google Drive connector.

    Supported actions (passed as ``params["action"]``):
    - ``list_files``:        List files in Drive. Params: ``max_results`` (int, opt).
    - ``get_file_metadata``: Get file metadata by ID. Params: ``file_id``.
    - ``search_files``:      Search files. Params: ``query`` (Drive query syntax).
    - ``download_file``:     Export/download file content. Params: ``file_id``, ``mime_type`` (opt).

    Requirements: 8.2, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gdrive_read"

    @property
    def requires_confirmation(self) -> bool:
        return False

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gdrive_read",
            description=(
                "Read from Google Drive: list files, get metadata,"
                " search, download content."
            ),
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": [
                            "list_files",
                            "get_file_metadata",
                            "search_files",
                            "download_file",
                        ],
                        "description": "The read action to perform.",
                    },
                    "max_results": {
                        "type": "integer",
                        "description": "Max files to return. Default: 20.",
                    },
                    "file_id": {
                        "type": "string",
                        "description": "Google Drive file ID.",
                    },
                    "query": {
                        "type": "string",
                        "description": "Drive query string (for search_files).",
                    },
                    "mime_type": {
                        "type": "string",
                        "description": (
                            "Export MIME type (for download_file,"
                            " e.g. text/plain). Default: text/plain."
                        ),
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=False,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_drive_service()
        except Exception:
            logger.exception("GDriveReadConnector: failed to build Drive service")
            raise

        if action == "list_files":
            max_results = params.get("max_results", 20)
            result = (
                service.files()
                .list(
                    pageSize=max_results,
                    fields="files(id,name,mimeType,modifiedTime,size)",
                )
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"files": result.get("files", [])},
                result_status="success",
            )

        elif action == "get_file_metadata":
            file_id = params.get("file_id", "")
            result = (
                service.files()
                .get(
                    fileId=file_id,
                    fields="id,name,mimeType,modifiedTime,size,parents,webViewLink",
                )
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "search_files":
            query = params.get("query", "")
            result = (
                service.files()
                .list(q=query, fields="files(id,name,mimeType,modifiedTime)")
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"files": result.get("files", [])},
                result_status="success",
            )

        elif action == "download_file":
            file_id = params.get("file_id", "")
            mime_type = params.get("mime_type", "text/plain")
            content = (
                service.files().export(fileId=file_id, mimeType=mime_type).execute()
            )
            if isinstance(content, bytes):
                content = content.decode("utf-8", errors="replace")
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"content": content, "mime_type": mime_type},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )


class GDriveWriteConnector(MCPToolConnector):
    """Write operations Google Drive connector (requires user confirmation).

    Supported actions (passed as ``params["action"]``):
    - ``create_file``: Create a new text file. Params: ``name``, ``content``, ``parent_id`` (opt).
    - ``update_file``: Update file content. Params: ``file_id``, ``content``.
    - ``delete_file``: Delete a file. Params: ``file_id``.

    Requirements: 8.3, 8.5
    """

    @property
    def tool_name(self) -> str:
        return "gdrive_write"

    @property
    def requires_confirmation(self) -> bool:
        return True

    def get_schema(self) -> MCPToolSchema:
        return MCPToolSchema(
            tool_name="gdrive_write",
            description="Write to Google Drive: create files, update content, delete files.",
            parameters={
                "type": "object",
                "properties": {
                    "action": {
                        "type": "string",
                        "enum": ["create_file", "update_file", "delete_file"],
                        "description": "The write action to perform.",
                    },
                    "name": {
                        "type": "string",
                        "description": "File name (for create_file).",
                    },
                    "content": {
                        "type": "string",
                        "description": "File text content (for create_file, update_file).",
                    },
                    "parent_id": {
                        "type": "string",
                        "description": "Parent folder ID (for create_file, optional).",
                    },
                    "file_id": {
                        "type": "string",
                        "description": "Google Drive file ID (for update_file, delete_file).",
                    },
                },
                "required": ["action"],
            },
            requires_confirmation=True,
        )

    async def invoke(self, params: dict[str, Any], user_id: str) -> MCPToolResult:
        action = params.get("action", "")
        try:
            service = _build_drive_service()
        except Exception:
            logger.exception("GDriveWriteConnector: failed to build Drive service")
            raise

        if action == "create_file":
            from googleapiclient.http import MediaInMemoryUpload

            name = params.get("name", "untitled.txt")
            content = params.get("content", "").encode("utf-8")
            metadata: dict[str, Any] = {"name": name, "mimeType": "text/plain"}
            parent_id = params.get("parent_id")
            if parent_id:
                metadata["parents"] = [parent_id]
            media = MediaInMemoryUpload(content, mimetype="text/plain")
            result = (
                service.files()
                .create(body=metadata, media_body=media, fields="id,name")
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "update_file":
            from googleapiclient.http import MediaInMemoryUpload

            file_id = params.get("file_id", "")
            content = params.get("content", "").encode("utf-8")
            media = MediaInMemoryUpload(content, mimetype="text/plain")
            result = (
                service.files()
                .update(fileId=file_id, media_body=media, fields="id,name,modifiedTime")
                .execute()
            )
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result=result,
                result_status="success",
            )

        elif action == "delete_file":
            file_id = params.get("file_id", "")
            service.files().delete(fileId=file_id).execute()
            return MCPToolResult(
                tool_name=self.tool_name,
                success=True,
                result={"deleted": True, "file_id": file_id},
                result_status="success",
            )

        else:
            return MCPToolResult(
                tool_name=self.tool_name,
                success=False,
                error=f"Unknown action: {action!r}",
                result_status="error",
            )
