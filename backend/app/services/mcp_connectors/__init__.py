"""MCP Tool Connector implementations.

Exports all eight MCP tool connector classes (split into read/write pairs):

- GitHub:          GitHubReadConnector, GitHubWriteConnector
- Gmail:           GmailReadConnector, GmailWriteConnector
- Google Drive:    GDriveReadConnector, GDriveWriteConnector
- Google Calendar: GCalReadConnector, GCalWriteConnector
- Slack:           SlackReadConnector, SlackWriteConnector
- Jira:            JiraReadConnector, JiraWriteConnector
- Notion:          NotionReadConnector, NotionWriteConnector
- Figma:           FigmaReadConnector (read-only API)

Usage::

    from app.services.mcp_connectors import (
        GitHubReadConnector, GitHubWriteConnector,
        GmailReadConnector, GmailWriteConnector,
        GDriveReadConnector, GDriveWriteConnector,
        GCalReadConnector, GCalWriteConnector,
        SlackReadConnector, SlackWriteConnector,
        JiraReadConnector, JiraWriteConnector,
        NotionReadConnector, NotionWriteConnector,
        FigmaReadConnector,
    )

Requirements: 8.2, 8.3, 8.5
"""

from app.services.mcp_connectors.figma_connector import FigmaReadConnector
from app.services.mcp_connectors.gcal_connector import (
    GCalReadConnector,
    GCalWriteConnector,
)
from app.services.mcp_connectors.gdrive_connector import (
    GDriveReadConnector,
    GDriveWriteConnector,
)
from app.services.mcp_connectors.github_connector import (
    GitHubReadConnector,
    GitHubWriteConnector,
)
from app.services.mcp_connectors.gmail_connector import (
    GmailReadConnector,
    GmailWriteConnector,
)
from app.services.mcp_connectors.jira_connector import (
    JiraReadConnector,
    JiraWriteConnector,
)
from app.services.mcp_connectors.notion_connector import (
    NotionReadConnector,
    NotionWriteConnector,
)
from app.services.mcp_connectors.slack_connector import (
    SlackReadConnector,
    SlackWriteConnector,
)

__all__ = [
    "FigmaReadConnector",
    "GCalReadConnector",
    "GCalWriteConnector",
    "GDriveReadConnector",
    "GDriveWriteConnector",
    "GitHubReadConnector",
    "GitHubWriteConnector",
    "GmailReadConnector",
    "GmailWriteConnector",
    "JiraReadConnector",
    "JiraWriteConnector",
    "NotionReadConnector",
    "NotionWriteConnector",
    "SlackReadConnector",
    "SlackWriteConnector",
]
