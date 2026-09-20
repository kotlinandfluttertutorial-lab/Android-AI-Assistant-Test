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
    # Enable pgvector extension (idempotent on Neon)
    op.execute("CREATE EXTENSION IF NOT EXISTS vector")

    # Add embedding column — nullable so existing rows aren't broken
    op.add_column(
        "document_chunks",
        sa.Column(
            "embedding",
            sa.Text,  # stored as text initially; pgvector type registered at runtime
            nullable=True,
            comment="384-dim sentence embedding vector for cosine similarity search",
        ),
    )

    # Use raw SQL to alter to vector type after extension is enabled
    op.execute(
        f"ALTER TABLE document_chunks "
        f"ALTER COLUMN embedding TYPE vector({EMBEDDING_DIM}) "
        f"USING embedding::vector({EMBEDDING_DIM})"
    )

    # IVFFlat index for approximate nearest-neighbour search
    # lists=100 is a good default for up to ~1M vectors
    op.execute(
        "CREATE INDEX IF NOT EXISTS ix_document_chunks_embedding_cosine "
        f"ON document_chunks USING ivfflat (embedding vector_cosine_ops) "
        "WITH (lists = 100)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_document_chunks_embedding_cosine")
    op.drop_column("document_chunks", "embedding")
