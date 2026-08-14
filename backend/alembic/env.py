"""Alembic environment configuration for async PostgreSQL migrations.

This file is executed by Alembic during every migration command.  It:

1. Reads ``settings.DATABASE_URL`` from the application settings so that
   credentials are never hardcoded in ``alembic.ini``.
2. Imports **all** ORM models (via ``app.models``) so that Alembic's
   ``autogenerate`` feature can detect every table, column, index, and
   constraint in ``Base.metadata``.
3. Runs migrations using the ``run_sync`` / asyncio pattern required for
   ``create_async_engine`` (SQLAlchemy 2.0 style).

Async migration pattern
-----------------------
Alembic itself is synchronous but ``create_async_engine`` requires an async
connection.  The workaround is to call
``connectable.sync_engine`` to obtain the underlying synchronous engine and
run Alembic's migration context inside ``connection.run_sync``.

Usage::

    cd backend/
    alembic upgrade head
    alembic downgrade -1
    alembic revision --autogenerate -m "add_my_table"

Requirements: 9.3, 9.10
"""

from __future__ import annotations

import asyncio
from logging.config import fileConfig

from sqlalchemy import pool
from sqlalchemy.engine import Connection
from sqlalchemy.ext.asyncio import create_async_engine

from alembic import context

# ---------------------------------------------------------------------------
# Alembic Config object — provides access to values in alembic.ini
# ---------------------------------------------------------------------------
config = context.config

# Interpret the config file for Python logging if present.
if config.config_file_name is not None:
    fileConfig(config.config_file_name)

# ---------------------------------------------------------------------------
# Import all models so that Base.metadata is fully populated for autogenerate.
# Every table must be reachable through Base.metadata before the migration
# context is configured, otherwise Alembic will not detect schema changes.
# ---------------------------------------------------------------------------
from app.models import (  # noqa: F401  — side-effect import required
    APIKey,
    AuditLog,
    Base,
    Conversation,
    Document,
    DocumentChunk,
    Job,
    Memory,
    Message,
    Note,
    PromptTemplate,
    TokenUsage,
    User,
)

# The metadata object that Alembic compares against the live database schema.
target_metadata = Base.metadata

# ---------------------------------------------------------------------------
# Resolve the database URL from application settings (overrides alembic.ini).
# ---------------------------------------------------------------------------


def get_url() -> str:
    """Return the async database URL from application settings.

    Falls back gracefully to the ``sqlalchemy.url`` entry in alembic.ini when
    ``DATABASE_URL`` is not available in the environment (e.g., during
    documentation generation).
    """
    try:
        from app.config.settings import get_settings

        return get_settings().DATABASE_URL
    except Exception:
        # Fallback for environments where the full settings stack is unavailable.
        return config.get_main_option("sqlalchemy.url", "")


# ---------------------------------------------------------------------------
# Offline migration mode (generates SQL without a live DB connection)
# ---------------------------------------------------------------------------


def run_migrations_offline() -> None:
    """Run migrations in 'offline' mode.

    In this mode Alembic generates SQL migration scripts without requiring a
    live database connection.  The output can be reviewed and applied manually.
    """
    url = get_url()
    context.configure(
        url=url,
        target_metadata=target_metadata,
        literal_binds=True,
        dialect_opts={"paramstyle": "named"},
        # Include schema-level objects that SQLAlchemy doesn't track by default.
        include_schemas=False,
        compare_type=True,
        compare_server_default=True,
        # Revision IDs like "0007_usage_feature_and_spending_alerts" exceed the
        # Alembic default of 32 chars — use 64 to avoid VARCHAR truncation errors.
        version_table_pk_length=64,
    )

    with context.begin_transaction():
        context.run_migrations()


# ---------------------------------------------------------------------------
# Online migration mode (applies migrations to a live database)
# ---------------------------------------------------------------------------


def do_run_migrations(connection: Connection) -> None:
    """Configure the Alembic context and run pending migrations.

    This function is called inside ``connection.run_sync`` so it receives a
    synchronous ``Connection`` even though the engine is async.
    """
    context.configure(
        connection=connection,
        target_metadata=target_metadata,
        compare_type=True,
        compare_server_default=True,
        # Render AS expressions for server defaults so autogenerate can compare
        # them reliably.
        render_as_batch=False,
        # Revision IDs like "0007_usage_feature_and_spending_alerts" exceed the
        # Alembic default of 32 chars — use 64 to avoid VARCHAR truncation errors.
        version_table_pk_length=64,
    )

    with context.begin_transaction():
        context.run_migrations()


async def run_async_migrations() -> None:
    """Create an async engine and run migrations via run_sync.

    SQLAlchemy's ``AsyncConnection.run_sync`` bridges the gap between Alembic's
    synchronous migration API and the async engine.
    """
    url = get_url()
    connectable = create_async_engine(
        url,
        poolclass=pool.NullPool,  # NullPool: no connection reuse during migrations
    )

    async with connectable.connect() as connection:
        await connection.run_sync(do_run_migrations)

    await connectable.dispose()


def run_migrations_online() -> None:
    """Entry point for online (live DB) migration execution.

    Detects whether there is already a running event loop (e.g., inside a
    Jupyter notebook or async test fixture) and handles both cases.
    """
    asyncio.run(run_async_migrations())


# ---------------------------------------------------------------------------
# Dispatch: offline vs. online
# ---------------------------------------------------------------------------

if context.is_offline_mode():
    run_migrations_offline()
else:
    run_migrations_online()
