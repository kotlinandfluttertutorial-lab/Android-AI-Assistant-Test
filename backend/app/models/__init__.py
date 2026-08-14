"""Models package — SQLAlchemy ORM models for all PostgreSQL tables.

Importing this package ensures that every model subclass is registered with
``Base.metadata``, which is required for:

- Alembic ``autogenerate`` to detect all tables.
- ``Base.metadata.create_all()`` in test fixtures.

All model classes and their companion Python enums are re-exported here so
that other packages can import them from a single location::

    from app.models import User, Conversation, Message, Document, ...

Requirements: 9.3, 9.10
"""

from app.models.api_key import APIKey, decrypt_api_key, encrypt_api_key
from app.models.audit_log import AuditLog
from app.models.base import Base, TimestampMixin
from app.models.calendar_event import CalendarEvent
from app.models.conversation import Conversation
from app.models.document import Document, IngestionStatus
from app.models.document_chunk import DocumentChunk
from app.models.error_log import ErrorLog
from app.models.feedback import Feedback
from app.models.habit import HabitDefinition, HabitEntry
from app.models.job import Job, JobStatus
from app.models.memory import Memory, MemoryType
from app.models.message import Message, MessageRole
from app.models.note import Note
from app.models.persona import Persona
from app.models.prompt_template import PromptTemplate
from app.models.refresh_token import RefreshToken
from app.models.reminder import Reminder
from app.models.spending_alert import SpendingAlert
from app.models.todo_item import TodoItem
from app.models.token_usage import TokenUsage, UsageFeature
from app.models.user import User, UserRole

__all__ = [
    # Base
    "Base",
    "TimestampMixin",
    # Models
    "User",
    "UserRole",
    "Conversation",
    "Message",
    "MessageRole",
    "Document",
    "IngestionStatus",
    "DocumentChunk",
    "Memory",
    "MemoryType",
    "APIKey",
    "encrypt_api_key",
    "decrypt_api_key",
    "AuditLog",
    "PromptTemplate",
    "RefreshToken",
    "TokenUsage",
    "UsageFeature",
    "SpendingAlert",
    "Note",
    "Job",
    "JobStatus",
    # Productivity models
    "TodoItem",
    "CalendarEvent",
    "Reminder",
    "HabitDefinition",
    "HabitEntry",
    # Admin models
    "Feedback",
    "ErrorLog",
    # Persona
    "Persona",
]
