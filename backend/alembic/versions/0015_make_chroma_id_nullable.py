"""Make document_chunks.chroma_id nullable.

chroma_id is no longer required — pgvector embeddings are stored directly
in the embedding column. Existing rows keep their chroma_id values.

Revision ID: 0015_make_chroma_id_nullable
Revises: 0014_add_pgvector_embeddings
Create Date: 2026-09-20 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0015_make_chroma_id_nullable"
down_revision: Union[str, None] = "0014_add_pgvector_embeddings"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.alter_column(
        "document_chunks",
        "chroma_id",
        existing_type=sa.String(512),
        nullable=True,
    )


def downgrade() -> None:
    # Set NULL chroma_ids to empty string before restoring NOT NULL
    op.execute("UPDATE document_chunks SET chroma_id = '' WHERE chroma_id IS NULL")
    op.alter_column(
        "document_chunks",
        "chroma_id",
        existing_type=sa.String(512),
        nullable=False,
    )
