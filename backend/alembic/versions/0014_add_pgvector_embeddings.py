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
    # Enable pgvector extension.
    #
    # CREATE EXTENSION cannot run inside a transaction on some Postgres versions,
    # and if it fails (extension not installed) it aborts the transaction — which
    # would break every DDL statement that follows.
    #
    # Strategy: commit the current transaction, attempt the extension in its own
    # autocommit statement, then let Alembic open a new transaction for the rest
    # of the migration.  The migration is configured with transaction_per_migration
    # = False (or we use op.get_context().autocommit) via a raw execute below.
    #
    # The safest portable approach for Alembic's synchronous DDL runner is to
    # close and reopen the transaction around this DDL:
    #
    #   COMMIT;
    #   CREATE EXTENSION IF NOT EXISTS vector;   -- runs autocommit
    #   BEGIN;                                    -- reopen for rest of migration
    #
    # If the extension is unavailable the CREATE EXTENSION fails, but because we
    # restart the transaction with BEGIN the subsequent ADD COLUMN still succeeds.

    pgvector_available = False
    try:
        # Step outside the transaction so a failure doesn't abort it.
        op.execute(sa.text("COMMIT"))
        op.execute(sa.text("CREATE EXTENSION IF NOT EXISTS vector"))
        pgvector_available = True
    except Exception:
        # Extension not available on this PostgreSQL instance.
        pass
    finally:
        # Always reopen a transaction so Alembic's DDL tracking stays intact.
        op.execute(sa.text("BEGIN"))

    # Add embedding column — nullable so existing rows aren't broken.
    # Always added as TEXT first; altered to the native vector type only
    # when pgvector is available.
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
            sa.text(
                f"ALTER TABLE document_chunks "
                f"ALTER COLUMN embedding TYPE vector({EMBEDDING_DIM}) "
                f"USING embedding::vector({EMBEDDING_DIM})"
            )
        )

        # IVFFlat index for approximate nearest-neighbour search.
        # lists=100 is a good default for up to ~1M vectors.
        op.execute(
            sa.text(
                "CREATE INDEX IF NOT EXISTS ix_document_chunks_embedding_cosine "
                f"ON document_chunks USING ivfflat (embedding vector_cosine_ops) "
                "WITH (lists = 100)"
            )
        )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS ix_document_chunks_embedding_cosine")
    op.drop_column("document_chunks", "embedding")
