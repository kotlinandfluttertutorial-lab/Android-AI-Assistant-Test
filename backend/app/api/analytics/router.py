# ============================================================
# Android AI Assistant (Enterprise Edition) — Backend
# ============================================================
# Module  : api/analytics
# File    : router.py
# Purpose : FastAPI router defining all HTTP endpoints for the analytics domain
#
# Architecture Layer : API Router
# Pattern Used       : FastAPI Router
#
# Key Concepts:
#   - FastAPI async request handling
#   - SQLAlchemy 2.x async ORM
#
# Dependencies:
#   - See import statements below
# ============================================================

"""Analytics router — /analytics/* endpoints (stub).

Full implementation: task 36 (analytics endpoints).

Requirements: 9.1
"""

from fastapi import APIRouter, Depends

from app.security.dependencies import get_current_user

router = APIRouter(
    prefix="/analytics",
    tags=["analytics"],
    dependencies=[Depends(get_current_user)],
)


@router.get("/")
async def analytics_root() -> dict:
    """Placeholder endpoint — analytics router is active."""
    return {"message": "analytics router"}
