"""Add citation_type, char_offset_start, char_offset_end to document_chunks.

Required for TXT/Markdown character-offset citations (Requirement 4.7).

Revision ID: 0009_add_citation_fields
Revises: 0008_add_fcm_token_to_users
Create Date: 2026-08-14 00:00:00.000000
"""

from __future__ import annotations

from typing import Sequence, Union

import sqlalchemy as sa
from alembic import op

revision: str = "0009_add_citation_fields"
down_revision: Union[str, None] = "0008_add_fcm_token_to_users"
branch_labels: Union[str, Sequence[str], None] = None
depends_on: Union[str, Sequence[str], None] = None


def upgrade() -> None:
    op.add_column(
        "document_chunks",
        sa.Column(
            "citation_type",
            sa.String(length=32),
            nullable=False,
            server_default="page",
            comment="'page' for PDF/DOCX; 'char_offset' for TXT/Markdown",
        ),
    )
    op.add_column(
        "document_chunks",
        sa.Column(
            "char_offset_start",
            sa.Integer(),
            nullable=True,
            comment="Character offset start (TXT/Markdown only)",
        ),
    )
    op.add_column(
        "document_chunks",
        sa.Column(
            "char_offset_end",
            sa.Integer(),
            nullable=True,
            comment="Character offset end (TXT/Markdown only)",
        ),
    )


def downgrade() -> None:
    op.drop_column("document_chunks", "char_offset_end")
    op.drop_column("document_chunks", "char_offset_start")
    op.drop_column("document_chunks", "citation_type")
