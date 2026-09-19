"""
ChromaDB server entry point — mirrors chromadb/app.py.

Key points:
- Import from chromadb.config directly (NOT `import chromadb`) to avoid
  SharedSystemClient's class-level Settings() call during module import.
- chromadb.server.fastapi.FastAPI takes a Settings object (not System).
- Call server.app() (with parens) to get the inner fastapi.FastAPI ASGI app.
"""
import os
from chromadb.config import Settings
from chromadb.server.fastapi import FastAPI

settings = Settings(
    is_persistent=True,
    persist_directory=os.environ.get("PERSIST_DIRECTORY", "/tmp/chroma"),
    anonymized_telemetry=False,
    allow_reset=True,
)

server = FastAPI(settings)

# Call .app() — this returns the inner fastapi.FastAPI instance with all routes.
# Do NOT use server.app without () — that is the bound method itself, not the app.
app = server.app()
