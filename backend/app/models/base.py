# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : models
# File    : base.py
# Purpose : base — models module
#
# Architecture Layer : ORM Model
# Pattern Used       : Python Module
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Shared base classes and mixins for all SQLAlchemy ORM models.

Every ORM model in this package imports ``Base`` from here (re-exported via
``app.database``) and may optionally inherit ``TimestampMixin`` to gain
``created_at`` / ``updated_at`` columns without repeating the column definition
in each model.

Design decisions
----------------
- ``Base`` is defined in ``app.database`` (single metadata object for Alembic).
  This module re-exports it for convenience.
- ``TimestampMixin`` uses ``mapped_column`` with ``server_default`` so that the
  database sets the timestamp even when rows are inserted outside of the ORM
  (e.g., raw SQL scripts, fixtures).  ``onupdate`` keeps ``updated_at`` accurate
  on every ORM-level UPDATE.
- All primary keys use ``uuid.uuid4`` as the Python-side default so that UUIDs
  are generated before the INSERT statement (helpful for unit tests that do not
  need a live database).

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import DateTime, func
from sqlalchemy.orm import Mapped, mapped_column

from app.database import Base

__all__ = ["Base", "TimestampMixin", "uuid_pk"]


def uuid_pk() -> Mapped[uuid.UUID]:
    """Return a ``Mapped`` ``UUID`` primary-key column with ``uuid4`` default.

    Usage::

        class MyModel(Base, TimestampMixin):
            __tablename__ = "my_table"
            id: Mapped[uuid.UUID] = uuid_pk()
    """
    return mapped_column(
        primary_key=True,
        default=uuid.uuid4,
    )


class TimestampMixin:
    """Mixin that adds ``created_at`` and ``updated_at`` audit columns.

    - ``created_at`` is set once by the database at INSERT time (``NOW()``).
    - ``updated_at`` mirrors ``created_at`` on INSERT and is refreshed by the
      database on every subsequent UPDATE via ``onupdate``.

    Both columns use ``timezone=True`` to store UTC timestamps as
    ``TIMESTAMP WITH TIME ZONE`` in PostgreSQL, making them unambiguous across
    deployments regardless of server locale.
    """

    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        nullable=False,
    )

    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
