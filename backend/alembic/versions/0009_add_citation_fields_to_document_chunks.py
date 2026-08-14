"""Add citation metadata fields to document_chunks table.

This migration extends the ``document_chunks`` table with structured citation
fields required by the RAG citation feature:

  - ``page_number``   : page number within the source document (nullable int)
  - ``section_title`` : section or heading the chunk belongs to (nullable str)
  - ``chunk_index``   : zero-based position of the chunk within its document
  - ``citation_text`` : pre-formatted citation string returned to the client

Revision ID: 0009_add_citation_fields_to_document_chunks
Revises: 0008_add_fcm_token_to_users
"""

from alembic import op
import sqlalchemy as sa

revision: str = "0009_add_citation_fields_to_document_chunks"
down_revision: str = "0008_add_fcm_token_to_users"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column("document_chunks", sa.Column("page_number", sa.Integer(), nullable=True))
    op.add_column("document_chunks", sa.Column("section_title", sa.String(length=512), nullable=True))
    op.add_column("document_chunks", sa.Column("chunk_index", sa.Integer(), nullable=False, server_default="0"))
    op.add_column("document_chunks", sa.Column("citation_text", sa.Text(), nullable=True))
    op.create_index("ix_document_chunks_document_page", "document_chunks", ["document_id", "page_number"])


def downgrade() -> None:
    op.drop_index("ix_document_chunks_document_page", table_name="document_chunks")
    op.drop_column("document_chunks", "citation_text")
    op.drop_column("document_chunks", "chunk_index")
    op.drop_column("document_chunks", "section_title")
    op.drop_column("document_chunks", "page_number")
