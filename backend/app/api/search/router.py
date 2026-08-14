# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/search
# File    : router.py
# Purpose : FastAPI router defining the /search/semantic endpoint
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - Strict user scoping via JWT
#   - ChromaDB semantic search via SearchService
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Search router — /search/* endpoints.

Implements:
- POST /search/semantic — AI-powered semantic search across all content types

All endpoints require JWT authentication.

Requirements: 36.2, 36.3, 36.5, 36.7
"""

from __future__ import annotations

import logging
import uuid

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.database import get_db
from app.schemas.search import SemanticSearchRequest, SemanticSearchResponse
from app.security.dependencies import get_current_user
from app.security.jwt_handler import TokenPayload
from app.services.search_service import SearchService

logger = logging.getLogger(__name__)

router = APIRouter(
    prefix="/search",
    tags=["search"],
    dependencies=[Depends(get_current_user)],
)


# ---------------------------------------------------------------------------
# POST /search/semantic — AI-powered semantic search
# ---------------------------------------------------------------------------


@router.post(
    "/semantic",
    response_model=SemanticSearchResponse,
    status_code=status.HTTP_200_OK,
    summary="AI-powered semantic search across all content types",
)
async def semantic_search(
    body: SemanticSearchRequest,
    current_user: TokenPayload = Depends(get_current_user),
    db: AsyncSession = Depends(get_db),
) -> SemanticSearchResponse:
    """Perform semantic search across conversations, notes, documents, and memories.

    The search uses SentenceTransformer embeddings for the query and performs cosine
    similarity search across four per-user ChromaDB collections:
      - conversations_{user_id}
      - notes_{user_id}
      - documents_{user_id}
      - memories_{user_id}

    Only results with cosine similarity ≥ 0.5 are returned.
    Content types with no results above the threshold are omitted from the response.
    Results are sorted by relevance score descending.

    Args:
        body: Request body containing the natural language query string.
        current_user: JWT payload of the authenticated user.
        db: SQLAlchemy async session.

    Returns:
        :class:`SemanticSearchResponse` with ranked results.

    Requirements: 36.2, 36.3, 36.5, 36.7
    """
    user_id = uuid.UUID(current_user.sub)
    service = SearchService(db)

    results = await service.semantic_search(user_id=user_id, query=body.query)

    logger.info(
        "Semantic search completed for user %s: query=%r, results=%d",
        user_id,
        body.query,
        len(results),
    )

    return SemanticSearchResponse(results=results, total=len(results))
