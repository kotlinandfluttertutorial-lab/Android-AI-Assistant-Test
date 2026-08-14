# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/memory
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the memory domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#   - ChromaDB semantic search via MemoryService
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Memory router — /memory/* endpoints.

Implements:
- POST   /memory            — store a new memory embedding
- GET    /memory            — semantic search (top-3 most relevant memories)
- DELETE /memory/{id}       — remove a memory embedding within 10 seconds

All endpoints require JWT authentication.

Requirements: 7.1, 7.2, 7.4, 9.1
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.models.memory import MemoryType
from app.schemas.memory import (
    MemoryCreate,
    MemoryResponse,
    MemorySearchResponse,
    MemorySearchResult,
)
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.memory_service import MemoryService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/memory",
    tags=["memory"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# POST /memory — store a new memory
# ---------------------------------------------------------------------------


@router.post(
    "",
    response_model=MemoryResponse,
    status_code=status.HTTP_201_CREATED,
    summary="Store a new memory embedding",
)
async def create_memory(
    body: MemoryCreate,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> MemoryResponse:
    """Store a memory entry as an embedding in ChromaDB, tagged with the
    authenticated user's ID.

    When privacy mode is enabled for the user, the request succeeds (HTTP 201)
    but no memory is persisted.

    Args:
        body: The memory content and type.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Returns:
        The stored memory, or a stub response when privacy mode is active.

    Requirements: 7.1, 7.6
    """
    user_id = uuid.UUID(current_user.sub)

    # Validate and map memory_type
    try:
        memory_type = MemoryType(body.memory_type)
    except ValueError:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=f"Invalid memory_type '{body.memory_type}'. Must be one of: preference, fact, style.",
        )

    service = MemoryService(db)
    memory = await service.store_memory(
        user_id=user_id,
        content=body.content,
        memory_type=memory_type,
    )
    await db.commit()

    if memory is None:
        # Privacy mode is active — return a stub so the client knows the API call succeeded
        import uuid as _uuid
        from datetime import datetime, timezone

        logger.info("Memory capture skipped for user %s (privacy mode)", user_id)
        return MemoryResponse(
            id=_uuid.uuid4(),
            content=body.content,
            memory_type=body.memory_type,
            created_at=datetime.now(tz=timezone.utc),
        )

    return MemoryResponse(
        id=memory.id,
        content=memory.content,
        memory_type=memory.memory_type.value,
        created_at=memory.created_at,
    )


# ---------------------------------------------------------------------------
# GET /memory — semantic search (top-K most relevant)
# ---------------------------------------------------------------------------


@router.get(
    "",
    response_model=MemorySearchResponse,
    status_code=status.HTTP_200_OK,
    summary="Retrieve top-3 most relevant memories for a query",
)
async def search_memories(
    query: str = Query(
        ...,
        min_length=1,
        max_length=2048,
        description="The query string for semantic similarity search.",
    ),
    top_k: int = Query(
        default=3,
        ge=1,
        le=10,
        description="Maximum number of memories to return (default: 3).",
    ),
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> MemorySearchResponse:
    """Perform semantic search over the authenticated user's memories and
    return the top-K most relevant results.

    No cross-user retrieval is possible — the query is scoped to the
    authenticated user's ChromaDB collection (``memory_{user_id}``).

    Args:
        query: Semantic search string.
        top_k: Number of results to return.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Returns:
        :class:`MemorySearchResponse` with ordered list of relevant memories.

    Requirements: 7.2, 7.5
    """
    user_id = uuid.UUID(current_user.sub)
    service = MemoryService(db)

    # Retrieve from memory repository via semantic search
    from sqlalchemy import select

    from app.models.memory import Memory
    from app.repositories.memory_repository import MemoryRepository

    repo = MemoryRepository(db)
    results = await repo.search_memories(
        user_id=user_id,
        query=query,
        top_k=top_k,
    )

    if not results:
        # Fall back to most recent memories
        results = await repo.get_recent_memories(user_id=user_id, top_k=top_k)

    # Fetch created_at timestamps from PostgreSQL for response
    if results:
        memory_ids = [r.memory_id for r in results]
        pg_result = await db.execute(select(Memory).where(Memory.id.in_(memory_ids)))
        pg_map = {m.id: m for m in pg_result.scalars().all()}
    else:
        pg_map = {}

    search_results = []
    for r in results:
        pg_memory = pg_map.get(r.memory_id)
        from datetime import datetime, timezone

        created_at = (
            pg_memory.created_at if pg_memory else datetime.now(tz=timezone.utc)
        )
        search_results.append(
            MemorySearchResult(
                id=r.memory_id,
                content=r.content,
                memory_type=r.memory_type,
                relevance_score=r.relevance_score,
                created_at=created_at,
            )
        )

    return MemorySearchResponse(
        query=query,
        results=search_results,
        total=len(search_results),
    )


# ---------------------------------------------------------------------------
# DELETE /memory/{id} — remove a memory embedding
# ---------------------------------------------------------------------------


@router.delete(
    "/{memory_id}",
    status_code=status.HTTP_204_NO_CONTENT,
    response_model=None,
    summary="Delete a memory embedding",
)
async def delete_memory(
    memory_id: uuid.UUID,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> None:
    """Remove the specified memory's embedding from ChromaDB and its metadata
    from PostgreSQL.

    The embedding is removed within 10 seconds (Requirement 7.4).
    Only the authenticated user's own memories can be deleted.

    Args:
        memory_id: UUID of the memory to delete.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Raises:
        HTTP 404 if the memory does not exist or belongs to another user.

    Requirements: 7.4, 7.5
    """
    user_id = uuid.UUID(current_user.sub)
    service = MemoryService(db)

    deleted = await service.delete_memory(
        memory_id=memory_id,
        user_id=user_id,
    )
    await db.commit()

    if not deleted:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail=f"Memory {memory_id} not found.",
        )
    # Return 204 No Content on success


# ---------------------------------------------------------------------------
# GET /memory/list — list all memories for the authenticated user
# ---------------------------------------------------------------------------


@router.get(
    "/list",
    response_model=list[MemoryResponse],
    status_code=status.HTTP_200_OK,
    summary="List all memories for the authenticated user",
)
async def list_memories(
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> list[MemoryResponse]:
    """Return all memories for the authenticated user, newest first.

    Args:
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Returns:
        List of memory responses.

    Requirements: 7.3
    """
    user_id = uuid.UUID(current_user.sub)
    service = MemoryService(db)
    memories = await service.list_memories(user_id=user_id)

    return [
        MemoryResponse(
            id=m.id,
            content=m.content,
            memory_type=m.memory_type.value,
            created_at=m.created_at,
        )
        for m in memories
    ]
