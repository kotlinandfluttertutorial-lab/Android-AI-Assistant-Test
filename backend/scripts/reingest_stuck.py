"""
reingest_stuck.py — One-shot script to re-dispatch Celery ingest tasks for
all documents stuck in processing/pending state.

Run via:
    APP_MODE=reingest_script python scripts/reingest_stuck.py

Or as a Cloud Run Job with:
    --command=python --args=scripts/reingest_stuck.py
"""

import asyncio
import os
import sys

# Force settings load before any app module imports
os.environ.setdefault("ENVIRONMENT", "production")

try:
    from app.config.settings import get_settings
    settings = get_settings()
    print(f"Settings loaded OK (env={settings.ENVIRONMENT})", flush=True)
except Exception as e:
    print(f"ERROR loading settings: {type(e).__name__}: {e}", flush=True)
    sys.exit(1)

try:
    from app.database import AsyncSessionLocal
    print("AsyncSessionLocal imported OK", flush=True)
except Exception as e:
    print(f"ERROR importing database: {type(e).__name__}: {e}", flush=True)
    sys.exit(1)

try:
    from app.models.document import Document, IngestionStatus
    from app.workers.rag_worker import ingest_document_task
    from sqlalchemy import select, or_
    print("All imports OK", flush=True)
except Exception as e:
    print(f"ERROR importing models/worker: {type(e).__name__}: {e}", flush=True)
    sys.exit(1)


async def run() -> None:
    async with AsyncSessionLocal() as db:
        result = await db.execute(
            select(Document).where(
                or_(
                    Document.ingestion_status == IngestionStatus.processing,
                    Document.ingestion_status == IngestionStatus.pending,
                    Document.ingestion_status == IngestionStatus.failed,
                )
            )
        )
        docs = result.scalars().all()
        print(f"Found {len(docs)} stuck documents", flush=True)

        if not docs:
            print("Nothing to reingest — exiting.", flush=True)
            return

        for doc in docs:
            doc.ingestion_status = IngestionStatus.pending
            await db.flush()
            ingest_document_task.delay(str(doc.id), str(doc.user_id))
            print(f"  dispatched {doc.id} ({doc.file_name})", flush=True)

        await db.commit()
        print("All reingest tasks dispatched successfully.", flush=True)


if __name__ == "__main__":
    asyncio.run(run())
