"""Seed the DevOps knowledge base into ChromaDB.

Ingests all Markdown and text files from the ``knowledge/`` folder at the
project root into a shared ChromaDB collection named ``devops_knowledge``.

This collection is queried by the AI DevOps Assistant (Phase 13) to answer
questions about runbooks, historical incidents, architecture, and deployments.

DESIGN DECISIONS
----------------
- Shared collection (not per-user): knowledge base documents belong to the
  system, not individual users.
- Idempotent: running this script multiple times with the same files produces
  the same state. Existing chunk IDs are deleted and re-inserted, so the
  collection always reflects the current state of the knowledge/ folder.
- Deterministic chunk IDs: ``{relative_path}::{chunk_index}`` ensures that
  re-running the script overwrites previous chunks for the same file.
- Uses the same embedding model (all-MiniLM-L6-v2) and chunk settings as the
  main RAGService so queries are comparable across user and system documents.

USAGE
-----
From the project root (Windows):
    python backend/scripts/seed_knowledge.py

From the backend/ directory:
    python scripts/seed_knowledge.py

Environment variables (optional — use defaults for local dev):
    CHROMA_HOST   — ChromaDB host (default: localhost)
    CHROMA_PORT   — ChromaDB port (default: 8001)
    KNOWLEDGE_DIR — Path to knowledge/ folder (default: ../knowledge relative to this file)

Run this script:
  1. After first deploying ChromaDB (it will be empty)
  2. After adding or editing any knowledge/ document
  3. After a Cloud Run deployment (ChromaDB filesystem is wiped on each revision)
"""

from __future__ import annotations

import logging
import os
import sys
import time
from pathlib import Path

# ---------------------------------------------------------------------------
# Paths
# ---------------------------------------------------------------------------

# This file: backend/scripts/seed_knowledge.py
# Project root: two levels up
_SCRIPT_DIR = Path(__file__).resolve().parent
_BACKEND_DIR = _SCRIPT_DIR.parent
_PROJECT_ROOT = _BACKEND_DIR.parent

# Add backend/ to sys.path so we can import app modules
if str(_BACKEND_DIR) not in sys.path:
    sys.path.insert(0, str(_BACKEND_DIR))

# Default knowledge directory
_DEFAULT_KNOWLEDGE_DIR = _PROJECT_ROOT / "knowledge"

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

CHROMA_HOST = os.environ.get("CHROMA_HOST", "localhost")
CHROMA_PORT = int(os.environ.get("CHROMA_PORT", "8001"))
KNOWLEDGE_DIR = Path(os.environ.get("KNOWLEDGE_DIR", str(_DEFAULT_KNOWLEDGE_DIR)))
COLLECTION_NAME = "devops_knowledge"

# Chunking settings — must match RAGService for consistent embedding space
CHUNK_SIZE_TOKENS = 512
CHUNK_OVERLAP_TOKENS = 64

# ---------------------------------------------------------------------------
# Logging
# ---------------------------------------------------------------------------

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s  %(levelname)-8s  %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger(__name__)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _find_documents(knowledge_dir: Path) -> list[Path]:
    """Recursively find all .md and .txt files in knowledge_dir."""
    if not knowledge_dir.exists():
        logger.error("Knowledge directory not found: %s", knowledge_dir)
        sys.exit(1)

    docs = sorted(
        p for p in knowledge_dir.rglob("*")
        if p.is_file() and p.suffix.lower() in {".md", ".txt"}
        and p.name != "README.md"  # skip the meta README
    )
    return docs


def _chunk_text(text: str) -> list[tuple[str, int]]:
    """Split text into overlapping token-based chunks.

    Returns a list of (chunk_text, chunk_index) tuples.
    Uses tiktoken for consistent tokenisation with the main RAGService.
    """
    try:
        import tiktoken
        enc = tiktoken.encoding_for_model("gpt-3.5-turbo")
    except ImportError:
        logger.warning("tiktoken not installed — falling back to word-based chunking")
        return _chunk_text_words(text)

    tokens = enc.encode(text)
    if not tokens:
        return []

    stride = CHUNK_SIZE_TOKENS - CHUNK_OVERLAP_TOKENS
    chunks: list[tuple[str, int]] = []
    start = 0
    idx = 0

    while start < len(tokens):
        end = min(start + CHUNK_SIZE_TOKENS, len(tokens))
        chunk_text = enc.decode(tokens[start:end])
        chunks.append((chunk_text, idx))
        idx += 1
        if end == len(tokens):
            break
        start += stride

    return chunks


def _chunk_text_words(text: str) -> list[tuple[str, int]]:
    """Fallback chunker using word count (~400 words per chunk)."""
    words = text.split()
    chunk_words = 400
    overlap_words = 50
    chunks = []
    start = 0
    idx = 0
    while start < len(words):
        end = min(start + chunk_words, len(words))
        chunks.append((" ".join(words[start:end]), idx))
        idx += 1
        if end == len(words):
            break
        start += chunk_words - overlap_words
    return chunks


def _embed_texts(texts: list[str]) -> list[list[float]]:
    """Embed a list of texts using SentenceTransformer all-MiniLM-L6-v2."""
    from sentence_transformers import SentenceTransformer
    model = SentenceTransformer("all-MiniLM-L6-v2")
    embeddings = model.encode(texts, show_progress_bar=False)
    return [emb.tolist() for emb in embeddings]


def _chunk_id(relative_path: str, chunk_index: int) -> str:
    """Deterministic ChromaDB document ID for a chunk."""
    # Replace path separators and spaces with underscores for a clean ID
    safe_path = relative_path.replace("\\", "/").replace(" ", "_")
    return f"{safe_path}::{chunk_index}"


# ---------------------------------------------------------------------------
# Main seeding function
# ---------------------------------------------------------------------------


def seed(knowledge_dir: Path = KNOWLEDGE_DIR) -> None:
    """Ingest all knowledge/ documents into the devops_knowledge ChromaDB collection."""

    logger.info("=" * 60)
    logger.info("DevOps Knowledge Base Seeder")
    logger.info("ChromaDB: %s:%s", CHROMA_HOST, CHROMA_PORT)
    logger.info("Knowledge dir: %s", knowledge_dir)
    logger.info("Collection: %s", COLLECTION_NAME)
    logger.info("=" * 60)

    # ── 1. Connect to ChromaDB ────────────────────────────────────────────────
    try:
        import chromadb
        client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
        client.heartbeat()
        logger.info("✅ Connected to ChromaDB at %s:%s", CHROMA_HOST, CHROMA_PORT)
    except Exception as exc:
        logger.error("❌ Cannot connect to ChromaDB at %s:%s — %s", CHROMA_HOST, CHROMA_PORT, exc)
        logger.error("   Is ChromaDB running? Start with: docker-compose up chromadb -d")
        sys.exit(1)

    # ── 2. Get or create the collection ──────────────────────────────────────
    collection = client.get_or_create_collection(COLLECTION_NAME)
    logger.info("Collection '%s' ready (current item count: %d)", COLLECTION_NAME, collection.count())

    # ── 3. Discover documents ─────────────────────────────────────────────────
    docs = _find_documents(knowledge_dir)
    if not docs:
        logger.warning("No documents found in %s", knowledge_dir)
        return

    logger.info("Found %d documents to index", len(docs))

    # ── 4. Process each document ──────────────────────────────────────────────
    total_chunks = 0
    total_files = 0
    errors = []

    for doc_path in docs:
        relative = str(doc_path.relative_to(knowledge_dir))
        logger.info("  Processing: %s", relative)

        try:
            text = doc_path.read_text(encoding="utf-8", errors="replace").strip()
            if not text:
                logger.warning("    Skipping (empty file)")
                continue

            chunks = _chunk_text(text)
            if not chunks:
                logger.warning("    Skipping (no chunks produced)")
                continue

            # Generate deterministic IDs for this file's chunks
            ids = [_chunk_id(relative, idx) for _, idx in chunks]
            chunk_texts = [t for t, _ in chunks]

            # Delete existing chunks for this file (idempotency)
            try:
                collection.delete(ids=ids)
            except Exception:
                pass  # IDs may not exist on first run

            # Embed all chunks in one batch
            embeddings = _embed_texts(chunk_texts)

            # Build metadata for each chunk
            metadatas = [
                {
                    "source": relative,
                    "chunk_index": idx,
                    "category": relative.split("/")[0],  # runbooks | incidents | architecture | deployment
                    "document_name": doc_path.name,
                }
                for _, idx in chunks
            ]

            # Store in ChromaDB
            collection.add(
                ids=ids,
                embeddings=embeddings,
                documents=chunk_texts,
                metadatas=metadatas,
            )

            logger.info("    ✅ %d chunks ingested", len(chunks))
            total_chunks += len(chunks)
            total_files += 1

        except Exception as exc:
            logger.error("    ❌ Failed: %s", exc)
            errors.append((relative, str(exc)))

    # ── 5. Summary ────────────────────────────────────────────────────────────
    logger.info("=" * 60)
    logger.info("Seeding complete")
    logger.info("  Files processed : %d / %d", total_files, len(docs))
    logger.info("  Total chunks    : %d", total_chunks)
    logger.info("  Collection size : %d", collection.count())
    if errors:
        logger.warning("  Errors (%d):", len(errors))
        for path, err in errors:
            logger.warning("    %s — %s", path, err)
    else:
        logger.info("  Errors          : 0")
    logger.info("=" * 60)

    if errors:
        sys.exit(1)


# ---------------------------------------------------------------------------
# Async wrapper (for calling from FastAPI startup)
# ---------------------------------------------------------------------------


async def seed_async(knowledge_dir: Path = KNOWLEDGE_DIR) -> dict:
    """Async wrapper so the admin reindex endpoint can call this from FastAPI.

    Returns a summary dict for the HTTP response.
    """
    import asyncio
    import functools

    loop = asyncio.get_event_loop()
    # Run the synchronous seed() in a thread pool to avoid blocking the event loop
    result: dict = {}

    def _seed_with_result() -> dict:
        knowledge_dir.exists()  # early check
        docs = _find_documents(knowledge_dir)
        if not docs:
            return {"status": "empty", "files": 0, "chunks": 0, "errors": []}

        try:
            import chromadb
            client = chromadb.HttpClient(host=CHROMA_HOST, port=CHROMA_PORT)
            collection = client.get_or_create_collection(COLLECTION_NAME)
        except Exception as exc:
            return {"status": "error", "detail": f"ChromaDB unavailable: {exc}", "files": 0, "chunks": 0, "errors": [str(exc)]}

        total_chunks = 0
        total_files = 0
        errors = []

        for doc_path in docs:
            relative = str(doc_path.relative_to(knowledge_dir))
            try:
                text = doc_path.read_text(encoding="utf-8", errors="replace").strip()
                if not text:
                    continue
                chunks = _chunk_text(text)
                if not chunks:
                    continue
                ids = [_chunk_id(relative, idx) for _, idx in chunks]
                chunk_texts = [t for t, _ in chunks]
                try:
                    collection.delete(ids=ids)
                except Exception:
                    pass
                embeddings = _embed_texts(chunk_texts)
                metadatas = [
                    {"source": relative, "chunk_index": idx, "category": relative.split("/")[0], "document_name": doc_path.name}
                    for _, idx in chunks
                ]
                collection.add(ids=ids, embeddings=embeddings, documents=chunk_texts, metadatas=metadatas)
                total_chunks += len(chunks)
                total_files += 1
            except Exception as exc:
                errors.append(f"{relative}: {exc}")

        return {
            "status": "ok" if not errors else "partial",
            "files": total_files,
            "chunks": total_chunks,
            "collection_size": collection.count(),
            "errors": errors,
        }

    result = await loop.run_in_executor(None, functools.partial(_seed_with_result))
    return result


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------


if __name__ == "__main__":
    start = time.time()
    seed()
    elapsed = time.time() - start
    logger.info("Total time: %.1f seconds", elapsed)
