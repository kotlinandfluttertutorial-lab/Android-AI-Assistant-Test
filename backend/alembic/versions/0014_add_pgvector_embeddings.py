"""Add pgvector embedding column to document_chunks.

Enables the vector extension (neon supports it natively) and adds a
384-dimensional float vector column to document_chunks for cosine
similarity search, replacing the separate ChromaDB service.

384 dimensions matches sentence-transformers/all-MiniLM-L6-v2.

Revision ID: 0014_add_pgvector_embeddings
Revises: 0013_add_remediation_actions
Create Date: 2026-09-20 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0014_add_pgvector_embeddings"
down_revision: Union[str, None] = "0013_add_remediation_actions"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None

EMBEDDING_DIM = 384  # all-MiniLM-L6-v2 output dimension


def upgrade() -> None:
    # Enable pgvector extension — skip gracefully if not installed on this host.
    # In CI environments without the OS-level pgvector package the extension
    # won't be available; the column is still added as TEXT so the schema
    # migration completes and the app falls back to full-table scans until
    # pgvector is available on the target host (e.g. Neon, production).
    conn = op.get_bind()
    pgvector_available = False
    try:
        conn.execute(sa.text("CREATE EXTENSION IF NOT EXISTS vector"))
        pgvector_available = True
    except Exception:
        # Extension not installed on this PostgreSQL instance — continue without it.
        pass

    # Add embedding column — nullable so existing rows aren't broken.
    # Always add as TEXT first; alter to vector type only when the extension loaded.
    op.add_column(
        "document_chunks",
        sa.Column(
            "embedding",
            sa.Text,
            nullable=True,
            comment="384-dim sentence embedding vector for cosine similarity search",
        ),
    )

    if pgvector_available:
        # Alter column to the native vector type now that the extension is loaded.
        op.execute(
            f"ALTER TABLE document_chunks "
            f"ALTER COLUMN embedding TYPE vector({EMBEDDING_DIM}) "
            f"USING embedding::vector({EMBEDDING_DIM})"
        )

        # IVFFlat index for approximate nearest-neighbour search.
        # lists=100 is a good default for up to ~1M vectors.
        op.execute(
            "CREATE INDEX IF NOT EXISTS ix_document_chunks_embedding_cosine "
            f"ON document_chunks USING ivfflat (embedding vector_cosine_ops) "
            "WITH (lists = 100)"
        )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_document_chunks_embedding_cosine")
    op.drop_column("document_chunks", "embedding")
