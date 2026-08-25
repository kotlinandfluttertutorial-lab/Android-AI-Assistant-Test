# Skill: Backend API Endpoint

## Purpose
Add a production-ready FastAPI endpoint to the Android AI Assistant backend, following
the layered architecture already established across the 23 existing routers.

## When to Use
- Adding a new REST or WebSocket endpoint
- Adding a new router module under `backend/app/api/`
- Extending an existing router with new routes
- Adding a Celery background task triggered by an endpoint

---

## Backend Architecture Overview

```
HTTP Request
    └── Middleware stack (logging → rate-limit → data-residency → body-size → CORS)
            └── FastAPI Router  (app/api/<domain>/router.py)
                    └── Service  (app/services/<domain>_service.py)
                            └── Repository  (app/repositories/<domain>_repository.py)
                                    └── SQLAlchemy AsyncSession  (app/database/session.py)
```

The backend runs on **FastAPI 0.141.1** + **Python 3.11** with **SQLAlchemy 2.0** async ORM
and **asyncpg** driver. All functions touching I/O must be `async def`.

---

## Directory Structure for a New Domain

```
backend/app/api/<domain>/
    __init__.py
    router.py      ← FastAPI router, thin request/response handling only
    schemas.py     ← Pydantic v2 request/response models

backend/app/services/
    <domain>_service.py    ← business logic, calls repositories

backend/app/repositories/
    <domain>_repository.py ← raw DB queries via SQLAlchemy

backend/app/models/
    <domain>.py            ← SQLAlchemy ORM model

backend/alembic/versions/
    <timestamp>_add_<domain>_table.py   ← migration
```

---

## Step 1 — Pydantic Schemas

```python
# app/api/<domain>/schemas.py
from __future__ import annotations

from datetime import datetime
from uuid import UUID

from pydantic import BaseModel, Field, field_validator


class <Domain>Create(BaseModel):
    """Request body for POST /api/<domain>/."""
    title: str = Field(..., min_length=1, max_length=255)
    content: str = Field(default="", max_length=50_000)

    @field_validator("title")
    @classmethod
    def title_not_blank(cls, v: str) -> str:
        if not v.strip():
            raise ValueError("title must not be blank")
        return v.strip()


class <Domain>Update(BaseModel):
    """Request body for PATCH /api/<domain>/{id}."""
    title: str | None = Field(default=None, min_length=1, max_length=255)
    content: str | None = Field(default=None, max_length=50_000)


class <Domain>Response(BaseModel):
    """Response schema — never expose internal fields like hashed passwords or raw keys."""
    id: UUID
    user_id: UUID
    title: str
    content: str
    created_at: datetime
    updated_at: datetime

    model_config = {"from_attributes": True}   # Pydantic v2 ORM mode
```

---

## Step 2 — SQLAlchemy Model

```python
# app/models/<domain>.py
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, String, Text
from sqlalchemy.dialects.postgresql import UUID as PG_UUID
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.database.base import Base


class <Domain>(Base):
    __tablename__ = "<domain>s"

    id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True), primary_key=True, default=uuid.uuid4
    )
    user_id: Mapped[uuid.UUID] = mapped_column(
        PG_UUID(as_uuid=True),
        ForeignKey("users.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False, default="")
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    updated_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True),
        default=lambda: datetime.now(timezone.utc),
        onupdate=lambda: datetime.now(timezone.utc),
    )

    # Relationships (lazy="select" is safe for async with selectinload)
    # user: Mapped[User] = relationship(back_populates="<domain>s", lazy="select")
```

---

## Step 3 — Repository

```python
# app/repositories/<domain>_repository.py
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import select, delete
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.<domain> import <Domain>


class <Domain>Repository:
    """Raw database access for [<Domain>].

    All methods are async and receive an injected [AsyncSession].
    No business logic lives here — only SQL.
    """

    def __init__(self, db: AsyncSession) -> None:
        self._db = db

    async def get_by_id(self, item_id: uuid.UUID) -> <Domain> | None:
        result = await self._db.execute(
            select(<Domain>).where(<Domain>.id == item_id)
        )
        return result.scalar_one_or_none()

    async def get_all_for_user(
        self, user_id: uuid.UUID, *, limit: int = 20, offset: int = 0
    ) -> list[<Domain>]:
        result = await self._db.execute(
            select(<Domain>)
            .where(<Domain>.user_id == user_id)
            .order_by(<Domain>.created_at.desc())
            .limit(limit)
            .offset(offset)
        )
        return list(result.scalars().all())

    async def create(self, user_id: uuid.UUID, title: str, content: str) -> <Domain>:
        item = <Domain>(user_id=user_id, title=title, content=content)
        self._db.add(item)
        await self._db.flush()   # get the generated id without committing
        await self._db.refresh(item)
        return item

    async def update(
        self, item: <Domain>, title: str | None, content: str | None
    ) -> <Domain>:
        if title is not None:
            item.title = title
        if content is not None:
            item.content = content
        item.updated_at = datetime.now(timezone.utc)
        await self._db.flush()
        await self._db.refresh(item)
        return item

    async def delete(self, item_id: uuid.UUID) -> None:
        await self._db.execute(
            delete(<Domain>).where(<Domain>.id == item_id)
        )
```

---

## Step 4 — Service

```python
# app/services/<domain>_service.py
from __future__ import annotations

import uuid

from fastapi import HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.models.<domain> import <Domain>
from app.repositories.<domain>_repository import <Domain>Repository


class <Domain>Service:
    """Business logic for <Domain> management."""

    def __init__(self, db: AsyncSession) -> None:
        self._repo = <Domain>Repository(db)

    async def list_for_user(
        self, user_id: uuid.UUID, *, limit: int = 20, offset: int = 0
    ) -> list[<Domain>]:
        return await self._repo.get_all_for_user(user_id, limit=limit, offset=offset)

    async def get_or_404(self, item_id: uuid.UUID, user_id: uuid.UUID) -> <Domain>:
        item = await self._repo.get_by_id(item_id)
        if item is None or item.user_id != user_id:
            raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Not found")
        return item

    async def create(self, user_id: uuid.UUID, title: str, content: str) -> <Domain>:
        return await self._repo.create(user_id=user_id, title=title, content=content)

    async def update(
        self, item_id: uuid.UUID, user_id: uuid.UUID,
        title: str | None, content: str | None
    ) -> <Domain>:
        item = await self.get_or_404(item_id, user_id)
        return await self._repo.update(item, title=title, content=content)

    async def delete(self, item_id: uuid.UUID, user_id: uuid.UUID) -> None:
        await self.get_or_404(item_id, user_id)
        await self._repo.delete(item_id)
```

---

## Step 5 — Router

```python
# app/api/<domain>/router.py
from __future__ import annotations

import uuid

from fastapi import APIRouter, Depends, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.<domain>.schemas import <Domain>Create, <Domain>Response, <Domain>Update
from app.database.session import get_db
from app.dependencies.auth import get_current_user
from app.models.user import User
from app.services.<domain>_service import <Domain>Service

router = APIRouter(prefix="/api/<domain>s", tags=["<domain>s"])


def _service(db: AsyncSession = Depends(get_db)) -> <Domain>Service:
    return <Domain>Service(db)


@router.get("/", response_model=list[<Domain>Response])
async def list_items(
    limit: int = 20,
    offset: int = 0,
    current_user: User = Depends(get_current_user),
    service: <Domain>Service = Depends(_service),
) -> list[<Domain>Response]:
    items = await service.list_for_user(current_user.id, limit=limit, offset=offset)
    return [<Domain>Response.model_validate(i) for i in items]


@router.post("/", response_model=<Domain>Response, status_code=status.HTTP_201_CREATED)
async def create_item(
    body: <Domain>Create,
    current_user: User = Depends(get_current_user),
    service: <Domain>Service = Depends(_service),
    db: AsyncSession = Depends(get_db),
) -> <Domain>Response:
    item = await service.create(current_user.id, body.title, body.content)
    await db.commit()
    return <Domain>Response.model_validate(item)


@router.get("/{item_id}", response_model=<Domain>Response)
async def get_item(
    item_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    service: <Domain>Service = Depends(_service),
) -> <Domain>Response:
    item = await service.get_or_404(item_id, current_user.id)
    return <Domain>Response.model_validate(item)


@router.patch("/{item_id}", response_model=<Domain>Response)
async def update_item(
    item_id: uuid.UUID,
    body: <Domain>Update,
    current_user: User = Depends(get_current_user),
    service: <Domain>Service = Depends(_service),
    db: AsyncSession = Depends(get_db),
) -> <Domain>Response:
    item = await service.update(item_id, current_user.id, body.title, body.content)
    await db.commit()
    return <Domain>Response.model_validate(item)


@router.delete("/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_item(
    item_id: uuid.UUID,
    current_user: User = Depends(get_current_user),
    service: <Domain>Service = Depends(_service),
    db: AsyncSession = Depends(get_db),
) -> None:
    await service.delete(item_id, current_user.id)
    await db.commit()
```

---

## Step 6 — Register the Router in `app/main.py`

```python
from app.api.<domain>.router import router as <domain>_router

app.include_router(<domain>_router)
```

---

## Step 7 — Alembic Migration

```bash
cd backend
alembic revision --autogenerate -m "add_<domain>s_table"
# Review the generated file in alembic/versions/, then:
alembic upgrade head
```

---

## Authentication

All protected routes use `Depends(get_current_user)` which:
1. Reads the `Authorization: Bearer <jwt>` header
2. Verifies the JWT signature with `SECRET_KEY`
3. Returns the `User` ORM object, or raises `HTTP 401`

Public routes (login, register, health) are declared in `auth/router.py` and
listed in `CORS_ALLOW_LIST` in `main.py`.

---

## Middleware Awareness

All requests pass through (in order):
1. `RequestLoggingMiddleware` — adds correlation ID to logs
2. `RateLimitMiddleware` — Redis-backed, keyed on user ID or IP
3. `DataResidencyMiddleware` — enforces regional data rules
4. `RequestBodySizeLimitMiddleware` — rejects oversized payloads
5. `CORSMiddleware`

Do **not** implement your own rate limiting inside a router — extend `RateLimitMiddleware`.

---

## Checklist

- [ ] Pydantic schemas use `model_config = {"from_attributes": True}` for ORM mode
- [ ] All DB operations are `async def`; no `session.execute()` without `await`
- [ ] `db.commit()` called in the **router**, not in service or repository
- [ ] `db.flush()` used inside repository to get generated IDs before commit
- [ ] Ownership check (`item.user_id != user_id`) before returning data
- [ ] `HTTP 404` raised when item not found (not 403)
- [ ] Router registered in `app/main.py`
- [ ] Alembic migration generated and reviewed before running
- [ ] Endpoint covered by at least one pytest integration test
- [ ] No blocking I/O (`requests`, `time.sleep`) inside async functions
