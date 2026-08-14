"""Initial schema — all tables and constraints.

Creates the complete PostgreSQL schema for the Android AI Assistant backend,
including all tables, enum types, indexes, foreign-key constraints, and the
``pgcrypto`` extension required for ``gen_random_uuid()``.

Revision ID: 0001_initial_schema
Revises: (none — this is the first migration)
Create Date: 2024-01-01 00:00:00.000000

Requirements: 9.3, 9.10
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from sqlalchemy.dialects import postgresql

from alembic import op

# ---------------------------------------------------------------------------
# Revision identifiers, used by Alembic.
# ---------------------------------------------------------------------------

revision: str = "0001_initial"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# ---------------------------------------------------------------------------
# Upgrade: create all tables
# ---------------------------------------------------------------------------


def upgrade() -> None:
    """Create all tables, enum types, indexes, and constraints."""

    # -----------------------------------------------------------------------
    # Enable pgcrypto for gen_random_uuid() if not already available.
    # gen_random_uuid() is also built-in from PostgreSQL 13+; the extension
    # provides backward compatibility with older versions.
    # -----------------------------------------------------------------------
    op.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto")

    # -----------------------------------------------------------------------
    # Enum types
    # -----------------------------------------------------------------------
    user_role_enum = postgresql.ENUM(
        "user",
        "premium",
        "admin",
        name="user_role",
        create_type=False,
    )
    user_role_enum.create(op.get_bind(), checkfirst=True)

    message_role_enum = postgresql.ENUM(
        "user",
        "assistant",
        "system",
        "tool",
        name="message_role",
        create_type=False,
    )
    message_role_enum.create(op.get_bind(), checkfirst=True)

    ingestion_status_enum = postgresql.ENUM(
        "pending",
        "processing",
        "ready",
        "failed",
        name="ingestion_status",
        create_type=False,
    )
    ingestion_status_enum.create(op.get_bind(), checkfirst=True)

    memory_type_enum = postgresql.ENUM(
        "preference",
        "fact",
        "style",
        name="memory_type",
        create_type=False,
    )
    memory_type_enum.create(op.get_bind(), checkfirst=True)

    job_status_enum = postgresql.ENUM(
        "queued",
        "running",
        "completed",
        "failed",
        name="job_status",
        create_type=False,
    )
    job_status_enum.create(op.get_bind(), checkfirst=True)

    # -----------------------------------------------------------------------
    # Table: users
    # -----------------------------------------------------------------------
    op.create_table(
        "users",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("email", sa.String(255), nullable=False),
        sa.Column(
            "password_hash",
            sa.String(255),
            nullable=False,
            comment="bcrypt digest — work factor controlled by settings.BCRYPT_WORK_FACTOR (default 12)",
        ),
        sa.Column("google_id", sa.String(255), nullable=True),
        sa.Column("display_name", sa.String(255), nullable=False, server_default=""),
        sa.Column("avatar_url", sa.String(2048), nullable=True),
        sa.Column(
            "role",
            postgresql.ENUM(
                "user", "premium", "admin", name="user_role", create_type=False
            ),
            nullable=False,
            server_default="user",
        ),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column(
            "push_token",
            sa.String(512),
            nullable=True,
            comment="Firebase Cloud Messaging device token",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_users_email", "users", ["email"], unique=True)
    op.create_index("ix_users_google_id", "users", ["google_id"], unique=True)

    # -----------------------------------------------------------------------
    # Table: conversations
    # -----------------------------------------------------------------------
    op.create_table(
        "conversations",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "title", sa.String(512), nullable=False, server_default="New Conversation"
        ),
        sa.Column("provider", sa.String(64), nullable=False, server_default=""),
        sa.Column("is_pinned", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column("is_deleted", sa.Boolean, nullable=False, server_default=sa.false()),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_conversations_user_id", "conversations", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: messages
    # -----------------------------------------------------------------------
    op.create_table(
        "messages",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "conversation_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("conversations.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "role",
            postgresql.ENUM(
                "user",
                "assistant",
                "system",
                "tool",
                name="message_role",
                create_type=False,
            ),
            nullable=False,
        ),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("input_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("output_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("provider", sa.String(64), nullable=False, server_default=""),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_messages_conversation_id", "messages", ["conversation_id"])

    # -----------------------------------------------------------------------
    # Table: documents
    # -----------------------------------------------------------------------
    op.create_table(
        "documents",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("file_name", sa.String(512), nullable=False),
        sa.Column("mime_type", sa.String(128), nullable=False),
        sa.Column("size_bytes", sa.BigInteger, nullable=False),
        sa.Column("minio_key", sa.String(1024), nullable=False),
        sa.Column(
            "ingestion_status",
            postgresql.ENUM(
                "pending",
                "processing",
                "ready",
                "failed",
                name="ingestion_status",
                create_type=False,
            ),
            nullable=False,
            server_default="pending",
        ),
        sa.Column("page_count", sa.Integer, nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_documents_user_id", "documents", ["user_id"])
    op.create_index("ix_documents_ingestion_status", "documents", ["ingestion_status"])

    # -----------------------------------------------------------------------
    # Table: document_chunks
    # -----------------------------------------------------------------------
    op.create_table(
        "document_chunks",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "document_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("documents.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("chunk_index", sa.Integer, nullable=False),
        sa.Column("page_number", sa.Integer, nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("chroma_id", sa.String(512), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index(
        "ix_document_chunks_document_id", "document_chunks", ["document_id"]
    )
    op.create_index("ix_document_chunks_chroma_id", "document_chunks", ["chroma_id"])

    # -----------------------------------------------------------------------
    # Table: memories
    # -----------------------------------------------------------------------
    op.create_table(
        "memories",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column(
            "memory_type",
            postgresql.ENUM(
                "preference", "fact", "style", name="memory_type", create_type=False
            ),
            nullable=False,
        ),
        sa.Column("chroma_id", sa.String(512), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_memories_user_id", "memories", ["user_id"])
    op.create_index("ix_memories_memory_type", "memories", ["memory_type"])
    op.create_index("ix_memories_chroma_id", "memories", ["chroma_id"])

    # -----------------------------------------------------------------------
    # Table: api_keys
    # -----------------------------------------------------------------------
    op.create_table(
        "api_keys",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("provider", sa.String(64), nullable=False),
        sa.Column(
            "encrypted_key",
            sa.LargeBinary,
            nullable=False,
            comment="AES-256-GCM encrypted API key: [nonce(12B) | ciphertext+tag(n+16B)]",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_api_keys_user_id", "api_keys", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: audit_logs
    # -----------------------------------------------------------------------
    op.create_table(
        "audit_logs",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="SET NULL"),
            nullable=True,
        ),
        sa.Column("event_type", sa.String(64), nullable=False),
        sa.Column("ip_address", sa.String(45), nullable=False),
        sa.Column("user_agent", sa.String(512), nullable=False, server_default=""),
        sa.Column(
            "metadata",
            postgresql.JSONB,
            nullable=False,
            server_default=sa.text("'{}'::jsonb"),
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_audit_logs_user_id", "audit_logs", ["user_id"])
    op.create_index("ix_audit_logs_event_type", "audit_logs", ["event_type"])
    op.create_index("ix_audit_logs_created_at", "audit_logs", ["created_at"])

    # -----------------------------------------------------------------------
    # Table: prompt_templates
    # -----------------------------------------------------------------------
    op.create_table(
        "prompt_templates",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column("name", sa.String(255), nullable=False),
        sa.Column("content", sa.Text, nullable=False),
        sa.Column("version", sa.Integer, nullable=False, server_default="1"),
        sa.Column(
            "author_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="RESTRICT"),
            nullable=False,
        ),
        sa.Column("is_active", sa.Boolean, nullable=False, server_default=sa.true()),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.UniqueConstraint("name", name="uq_prompt_templates_name"),
    )
    op.create_index(
        "ix_prompt_templates_name", "prompt_templates", ["name"], unique=True
    )

    # -----------------------------------------------------------------------
    # Table: token_usage
    # -----------------------------------------------------------------------
    op.create_table(
        "token_usage",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column(
            "message_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("messages.id", ondelete="CASCADE"),
            nullable=False,
            unique=True,
        ),
        sa.Column("provider", sa.String(64), nullable=False),
        sa.Column("input_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column("output_tokens", sa.Integer, nullable=False, server_default="0"),
        sa.Column(
            "cost_usd",
            sa.Numeric(10, 6),
            nullable=False,
            server_default="0",
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_token_usage_user_id", "token_usage", ["user_id"])
    op.create_index("ix_token_usage_created_at", "token_usage", ["created_at"])

    # -----------------------------------------------------------------------
    # Table: notes
    # -----------------------------------------------------------------------
    op.create_table(
        "notes",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("title", sa.String(512), nullable=False, server_default=""),
        sa.Column("content", sa.Text, nullable=False, server_default=""),
        sa.Column(
            "tags",
            postgresql.ARRAY(sa.Text),
            nullable=False,
            server_default=sa.text("ARRAY[]::text[]"),
        ),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_notes_user_id", "notes", ["user_id"])

    # -----------------------------------------------------------------------
    # Table: jobs
    # -----------------------------------------------------------------------
    op.create_table(
        "jobs",
        sa.Column(
            "id",
            postgresql.UUID(as_uuid=True),
            primary_key=True,
            server_default=sa.text("gen_random_uuid()"),
            nullable=False,
        ),
        sa.Column(
            "user_id",
            postgresql.UUID(as_uuid=True),
            sa.ForeignKey("users.id", ondelete="CASCADE"),
            nullable=False,
        ),
        sa.Column("job_type", sa.String(64), nullable=False),
        sa.Column(
            "status",
            postgresql.ENUM(
                "queued",
                "running",
                "completed",
                "failed",
                name="job_status",
                create_type=False,
            ),
            nullable=False,
            server_default="queued",
        ),
        sa.Column("celery_task_id", sa.String(255), nullable=True),
        sa.Column("result_payload", postgresql.JSONB, nullable=True),
        sa.Column("error_message", sa.String(2048), nullable=True),
        sa.Column("retry_count", sa.Integer, nullable=False, server_default="0"),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.func.now(),
            nullable=False,
        ),
    )
    op.create_index("ix_jobs_user_id", "jobs", ["user_id"])
    op.create_index("ix_jobs_job_type", "jobs", ["job_type"])
    op.create_index("ix_jobs_status", "jobs", ["status"])


# ---------------------------------------------------------------------------
# Downgrade: drop all tables in reverse dependency order
# ---------------------------------------------------------------------------


def downgrade() -> None:
    """Drop all tables and enum types created by this migration."""

    # Drop tables in reverse FK dependency order to avoid constraint violations.
    op.drop_table("jobs")
    op.drop_table("notes")
    op.drop_table("token_usage")
    op.drop_table("prompt_templates")
    op.drop_table("audit_logs")
    op.drop_table("api_keys")
    op.drop_table("memories")
    op.drop_table("document_chunks")
    op.drop_table("documents")
    op.drop_table("messages")
    op.drop_table("conversations")
    op.drop_table("users")

    # Drop enum types after all tables referencing them are gone.
    op.execute("DROP TYPE IF EXISTS job_status")
    op.execute("DROP TYPE IF EXISTS memory_type")
    op.execute("DROP TYPE IF EXISTS ingestion_status")
    op.execute("DROP TYPE IF EXISTS message_role")
    op.execute("DROP TYPE IF EXISTS user_role")
